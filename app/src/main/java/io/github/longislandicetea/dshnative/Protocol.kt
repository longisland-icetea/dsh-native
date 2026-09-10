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

/**
 * One item from the forwarded-event stream (`$events`).
 *
 * The stream opens with `ready` (which carries the `clientId` every answer must
 * name), then emits plain notifications and agent-scoped *waterfalls*. A
 * waterfall is a Host call the client must answer: the listener's return value
 * travels back through the `$events/result` RPC. Approval prompts and user
 * questions arrive this way, so ignoring these frames is what leaves a phone
 * unable to unblock a waiting agent.
 */
sealed interface HostEvent {
    /** Opening frame binding this event generation. */
    data class Ready(val clientId: String, val home: String?) : HostEvent

    /** Fire-and-forget notification. */
    data class Notify(val event: String, val args: List<JsonElement>) : HostEvent

    /** A Host call awaiting this client's answer. */
    data class Waterfall(
        val event: String,
        val eventId: String,
        val agentId: String,
        val request: JsonObject,
    ) : HostEvent

    /** The Host withdrew a pending waterfall. */
    data class Cancelled(val eventId: String) : HostEvent
}

object HostEventCodec {
    fun decode(value: JsonElement): HostEvent? {
        val obj = value as? JsonObject ?: return null
        return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "ready" -> HostEvent.Ready(
                clientId = obj["clientId"]?.jsonPrimitive?.contentOrNull ?: return null,
                home = (obj["host"] as? JsonObject)?.get("home")?.jsonPrimitive?.contentOrNull,
            )
            "emit" -> HostEvent.Notify(
                event = obj["event"]?.jsonPrimitive?.contentOrNull ?: return null,
                args = (obj["args"] as? JsonArray)?.toList() ?: emptyList(),
            )
            "waterfall" -> HostEvent.Waterfall(
                event = obj["event"]?.jsonPrimitive?.contentOrNull ?: return null,
                eventId = obj["eventId"]?.jsonPrimitive?.contentOrNull ?: return null,
                agentId = obj["agentId"]?.jsonPrimitive?.contentOrNull ?: return null,
                request = obj["request"] as? JsonObject ?: JsonObject(emptyMap()),
            )
            "cancel" -> HostEvent.Cancelled(
                eventId = obj["eventId"]?.jsonPrimitive?.contentOrNull ?: return null,
            )
            else -> null
        }
    }
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

/**
 * One Workspace as the Host defines it.
 *
 * The Host owns workspace grouping: `sessionIds` is the membership and order it
 * considers canonical, and `title` is the label the desktop shows. Deriving
 * groups from `cwd` locally (an earlier attempt) produced the right shape but
 * the wrong labels and ignored the order the user had arranged.
 */
@Serializable
data class WorkspaceView(
    val workspaceId: String,
    val path: String,
    val title: String,
    val sessionIds: List<String> = emptyList(),
    val createdAt: String? = null,
)

/** Reconnect baseline for the workspace browser. */
@Serializable
data class WorkspaceBaseline(
    val items: List<WorkspaceView> = emptyList(),
    val archivedSessionIds: List<String> = emptyList(),
)

/** One ordered change after a baseline. */
@Serializable
data class WorkspaceIncrement(
    val type: String,
    val workspace: WorkspaceView? = null,
    val workspaceId: String? = null,
)

@Serializable
data class ArchiveSessionRequest(val sessionId: String)

@Serializable
data class ArchiveValue(val archivedSessionIds: List<String> = emptyList())

@Serializable
data class SessionSummary(
    val sessionId: String,
    val updatedAt: Long = 0,
    val running: Boolean = false,
    val blank: Boolean = false,
    /** `subagent` marks a child session, which the list shows under its parent. */
    val origin: String? = null,
    val parentSessionId: String? = null,
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

/**
 * Decode `session/list` by hand.
 *
 * A generated serializer is the wrong tool here: `projections.values` holds
 * heterogeneous server-owned state (`goal` is null or a string, `permissions`
 * and `modelSelection` are nested objects, `turnOutline` is an array), so
 * strict decoding throws on the first shape it does not know and the item is
 * silently dropped — which presents as "connected, but no sessions". Only the
 * fields this client renders are read, and anything unexpected is ignored.
 */
object SessionListCodec {
    fun parse(value: JsonElement): List<SessionSummary> {
        val items = (value as? JsonObject)?.get("items") as? JsonArray ?: return emptyList()
        return items.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val sessionId = obj.string("sessionId") ?: return@mapNotNull null
            SessionSummary(
                sessionId = sessionId,
                updatedAt = obj.long("updatedAt") ?: 0L,
                running = obj.bool("running") ?: false,
                blank = obj.bool("blank") ?: false,
                origin = obj.string("origin"),
                parentSessionId = obj.string("parentSessionId"),
                cwd = obj.string("cwd"),
                projections = obj["projections"],
            )
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull
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

/** One tool invocation: what was called, with what, and its correlation id. */
data class ToolCall(
    val name: String,
    val callId: String?,
    val arguments: JsonObject?,
    val rawArguments: String?,
)

/** Output of one tool invocation, correlated to its call by id. */
data class ToolResult(
    val toolCallId: String?,
    val text: String?,
    val isError: Boolean,
)

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
            "user/message", "assistant/message", "system/message" -> EventPayload.textOf(this)
            else -> null
        }

    /** Compact one-line description for events rendered as activity. */
    val label: String
        get() = when (type) {
            "tool/call" -> EventPayload.toolCallOf(this)?.name?.let { "→ $it" } ?: "→ tool"
            "tool/result" -> "← result"
            "step/start" -> "step"
            "step/end" -> "step done"
            "turn/start" -> "turn started"
            "turn/end" -> "turn finished"
            "agent/inbox/spliced" -> "inbox"
            else -> type
        }

    /** Secondary line for activity rows (tool arguments, tool output). */
    val detail: String?
        get() = when (type) {
            "tool/call" -> EventPayload.toolCallOf(this)?.rawArguments?.replace('\n', ' ')?.take(160)
            "tool/result" -> EventPayload.toolResultOf(this)?.text?.replace('\n', ' ')?.take(200)
            else -> null
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
            // A chunk carries the same block shape as a finished message, so the
            // same text-only filter applies (reasoning deltas are not the reply).
            text = EventPayload.chunkText(obj["chunk"]),
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

/** One content block inside a message. Only `text` is rendered as prose. */
@Serializable
data class ContentBlock(
    val type: String,
    val text: String? = null,
    val name: String? = null,
    val arguments: String? = null,
)

/** The `message` wrapper both the transcript and tool results use. */
@Serializable
data class WireMessage(
    val role: String? = null,
    val content: List<ContentBlock> = emptyList(),
)

/**
 * Decode the payloads of the event types the transcript renders.
 *
 * Shapes measured against a live host:
 *  - `user/message`      -> `data.content[]` where every block is `text`
 *  - `assistant/message` -> `data.message.content[]` with `reasoning`, `text`
 *    and `tool-call` blocks **mixed together**
 *  - `tool/call`         -> `data.name`, `data.arguments` (a JSON string)
 *  - `tool/result`       -> `data.message` (same message wrapper)
 *
 * `reasoning` is deliberately dropped: it is the model's private thinking and
 * joining it into the transcript would show walls of chain-of-thought as if it
 * were the reply.
 */
object EventPayload {
    private val json = DshWire.json

    private fun blocksOf(element: JsonElement?): List<ContentBlock> {
        val obj = element as? JsonObject ?: return emptyList()
        val container = (obj["message"] as? JsonObject) ?: obj
        val array = container["content"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { part ->
            runCatching { json.decodeFromJsonElement(ContentBlock.serializer(), part) }.getOrNull()
        }
    }

    /** Visible text of one event, or null when it carries none. */
    fun textOf(event: SessionEvent): String? {
        val joined = blocksOf(event.data)
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString("\n")
        return joined.ifBlank { null }
    }

    /**
     * `tool/call` specifics: the tool name, its parsed arguments, and the
     * correlation id.
     *
     * `arguments` is a JSON *string*, not an object, so it is parsed here; a
     * tool that sends something unparsable still yields its raw text rather than
     * losing the call.
     */
    fun toolCallOf(event: SessionEvent): ToolCall? {
        val obj = event.data as? JsonObject ?: return null
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return null
        val raw = (obj["arguments"] as? JsonPrimitive)?.contentOrNull
        val parsed = raw?.let { text ->
            runCatching { DshWire.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        }
        return ToolCall(
            name = name,
            callId = (obj["callId"] as? JsonPrimitive)?.contentOrNull,
            arguments = parsed,
            rawArguments = raw,
        )
    }

    /**
     * `tool/result` payload: the tool's output text and whether it failed.
     *
     * Measured shape: `data.message.content[]` holds exactly one `tool-result`
     * block whose own `content[]` carries the text; `toolCallId` correlates it
     * back to the call and `isError` marks failure. Note the two levels of
     * `content` — reading only the outer one yields nothing.
     */
    fun toolResultOf(event: SessionEvent): ToolResult? {
        val outer = ((event.data as? JsonObject)?.get("message") as? JsonObject) ?: (event.data as? JsonObject)
        val blocks = (outer?.get("content") as? JsonArray) ?: return null
        for (part in blocks) {
            val block = part as? JsonObject ?: continue
            if (block["type"]?.jsonPrimitive?.contentOrNull != "tool-result") continue
            val text = (block["content"] as? JsonArray)?.mapNotNull { item ->
                ((item as? JsonObject)?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content
            }.orEmpty().joinToString("\n")
            return ToolResult(
                toolCallId = block["toolCallId"]?.jsonPrimitive?.contentOrNull,
                text = text.ifBlank { null },
                isError = block["isError"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
        return null
    }

    /**
     * Visible text of one live assistant chunk.
     *
     * A streamed delta uses the same block shape as a finished message, so the
     * `type == "text"` filter matters most here: a reasoning delta appended to
     * the live bubble would be indistinguishable from the answer while it
     * streams. A bare string chunk is accepted as-is because some frames carry
     * plain concatenated text.
     */
    fun chunkText(chunk: JsonElement?): String? {
        (chunk as? JsonPrimitive)?.takeIf { it.isString }?.let { return it.content }
        val obj = chunk as? JsonObject ?: return null
        (obj["type"] as? JsonPrimitive)?.contentOrNull?.let { kind ->
            if (kind != "text") return null
        }
        val joined = blocksOf(obj).filter { it.type == "text" }.mapNotNull { it.text }.joinToString("")
        if (joined.isNotEmpty()) return joined
        return (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

