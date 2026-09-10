package io.github.longislandicetea.dshnative

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The DSH Remote wire protocol, as measured against a live 0.1.5-rc.1 host.
 *
 * Unary calls are plain HTTP POSTs to `/api/<namespace>/<method>` carrying
 * `{type,rpcId,method,payload:{args}}`; every stream shares one WebSocket at
 * `/api/remote.mux` and is opened with `{type:"open",streamId,endpoint,payload}`.
 *
 * `args` holds *named* parameters, and the names are not uniform:
 *  - `session/list`   -> `{_request:{}}`   (the parameter really is `_request`)
 *  - `session/follow` -> `{request:{address,maxMessages,assistantStream}}`
 *
 * A `SessionAddress` is a discriminated union, not `{sessionId,cwd}`:
 *  - `{kind:"session", sessionId}`
 *  - `{kind:"subagent", parentSessionId, childSessionId, mode}`
 */
object DshWire {
    const val API_PREFIX = "/api"
    const val MUX_PATH = "/api/remote.mux"
    const val EVENT_STREAM_ENDPOINT = "\$events"

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }
}

@Serializable
data class RpcRequest(
    val type: String = "client-request",
    val rpcId: String,
    val method: String,
    val payload: RpcPayload,
)

@Serializable
data class RpcPayload(val args: JsonObject)

@Serializable
data class RpcResponse(
    val type: String,
    val rpcId: String,
    val result: RpcResult,
)

@Serializable
data class RpcResult(
    val ok: Boolean,
    val value: JsonElement? = null,
    val error: RpcError? = null,
)

@Serializable
data class RpcError(val code: String, val message: String) {
    override fun toString() = "$code: $message"
}

/** One validated item from the mux socket. */
sealed interface MuxFrame {
    val streamId: String

    data class Item(override val streamId: String, val value: JsonElement) : MuxFrame
    data class End(override val streamId: String) : MuxFrame
    data class Failure(override val streamId: String, val code: String, val message: String) : MuxFrame
}

object MuxFrames {
    /** Parse one server text message; null for an unrecognized shape. */
    fun parse(text: String): MuxFrame? {
        val obj = runCatching { DshWire.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val streamId = obj["streamId"]?.jsonPrimitive?.contentOrNull ?: return null
        return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "item" -> MuxFrame.Item(streamId, obj["value"] ?: JsonNull)
            "end" -> MuxFrame.End(streamId)
            "error" -> {
                val error = obj["error"]?.jsonObject
                MuxFrame.Failure(
                    streamId,
                    error?.get("code")?.jsonPrimitive?.contentOrNull ?: "gateway/unknown",
                    error?.get("message")?.jsonPrimitive?.contentOrNull ?: "stream failed",
                )
            }
            else -> null
        }
    }
}

@Serializable
data class SessionSummary(
    val sessionId: String,
    val updatedAt: Long = 0,
    val running: Boolean = false,
    val blank: Boolean = false,
    val cwd: String? = null,
    val projections: JsonElement? = null,
) {
    /** The title lives inside `projections`, not in a first-class field. */
    val title: String
        get() = projections?.jsonObject
            ?.get("values")?.jsonObject
            ?.get("title")?.jsonPrimitive?.contentOrNull
            ?: sessionId.takeLast(8)
}

/** Session or subagent address; the union `kind` is required by the host. */
@Serializable
data class SessionAddress(val kind: String = "session", val sessionId: String)

@Serializable
data class FollowRequest(
    val address: SessionAddress,
    val maxMessages: Int? = null,
    val assistantStream: Boolean? = null,
)

@Serializable
data class PageRequest(
    val address: SessionAddress,
    val throughSeq: Long,
    val beforeSeq: Long? = null,
    val maxMessages: Int? = null,
)

@Serializable
data class PromptContent(val type: String, val text: String)

@Serializable
data class PromptRequest(
    val requestId: String,
    val sessionId: String,
    val mode: String = "queue",
    val content: List<PromptContent>,
)

@Serializable
data class CancelRequest(val sessionId: String)

/** One durable event from a followed session. */
data class SessionEvent(
    val seq: Long,
    val type: String,
    val time: Long,
    val data: JsonElement,
) {
    /** Text this event contributes to the transcript, when it carries any. */
    val text: String?
        get() = when (type) {
            "user/message", "assistant/message", "system/message" -> extractText(data)
            else -> null
        }

    /** Compact one-line description for events rendered as activity. */
    val label: String
        get() = when (type) {
            "tool/call" -> "tool: " + (data.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: "?")
            "tool/result" -> "tool result"
            "step/start" -> "step started"
            "step/end" -> "step finished"
            else -> type
        }
}

/**
 * Decode one `session/follow` frame.
 *
 * Opening frame is `{type:"snapshot", header, cursor, records, hasMore,
 * projections}`; later frames are `{type:"event", event:{…}}` or a
 * process-local `{type:"assistant-stream", frame:{…}}` chunk.
 */
sealed interface FollowFrame {
    data class Snapshot(
        val cursor: Long,
        val records: List<SessionEvent>,
        val hasMore: Boolean,
    ) : FollowFrame

    data class Event(val event: SessionEvent) : FollowFrame

    /** A live assistant delta; `text` is null for non-text chunks. */
    data class AssistantChunk(val index: Int, val text: String?) : FollowFrame

    data object Unknown : FollowFrame
}

object FollowCodec {
    fun decode(value: JsonElement): FollowFrame {
        val obj = value as? JsonObject ?: return FollowFrame.Unknown
        return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "snapshot" -> FollowFrame.Snapshot(
                cursor = obj["cursor"]?.jsonPrimitive?.longOrNull ?: 0L,
                records = obj["records"]?.jsonArray?.mapNotNull(::eventOf).orEmpty(),
                hasMore = obj["hasMore"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
            "event" -> eventOf(obj["event"])?.let(FollowFrame::Event) ?: FollowFrame.Unknown
            "assistant-stream" -> decodeChunk(obj["frame"]) ?: FollowFrame.Unknown
            else -> FollowFrame.Unknown
        }
    }

    private fun decodeChunk(frame: JsonElement?): FollowFrame? {
        val obj = frame as? JsonObject ?: return null
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "chunk") return null
        return FollowFrame.AssistantChunk(
            index = obj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            text = extractText(obj["chunk"]),
        )
    }

    private fun eventOf(element: JsonElement?): SessionEvent? {
        val obj = element as? JsonObject ?: return null
        val event = (obj["event"] as? JsonObject) ?: obj
        val seq = event["seq"]?.jsonPrimitive?.longOrNull ?: return null
        return SessionEvent(
            seq = seq,
            type = event["type"]?.jsonPrimitive?.contentOrNull ?: "?",
            time = event["time"]?.jsonPrimitive?.longOrNull ?: 0L,
            data = event["data"] ?: JsonNull,
        )
    }
}

/**
 * Pull human-readable text out of an event or chunk payload.
 *
 * The host publishes no closed schema for `data`, so this walks the shapes the
 * wire actually uses (a string primitive, a `text` field, an OpenAI-style
 * `content` array, a `message` wrapper) rather than betting on one exact path.
 * Returns null when nothing matches and the caller falls back to a structural
 * summary.
 */
fun extractText(element: JsonElement?): String? {
    (element as? JsonPrimitive)?.takeIf { it.isString }?.let { return it.content }
    val obj = element as? JsonObject ?: return null

    (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.let { return it.content }

    (obj["content"] as? JsonArray)?.let { content ->
        val joined = content.joinToString("") { part ->
            when (part) {
                is JsonPrimitive -> if (part.isString) part.content else ""
                is JsonObject -> (part["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
                else -> ""
            }
        }
        if (joined.isNotEmpty()) return joined
    }

    obj["message"]?.let { message -> extractText(message)?.let { return it } }
    obj["chunk"]?.let { chunk -> extractText(chunk)?.let { return it } }
    return null
}
