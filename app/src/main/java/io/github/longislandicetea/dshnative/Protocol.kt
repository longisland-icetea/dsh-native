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

/**
 * A lightweight session-state change the Host pushes on its own.
 *
 * These arrive as `emit` frames on `$events` and are how the sidebar stays live
 * on the desktop: `api-session/status` carries the running flag and
 * `api-session/activity` a new timestamp, both by session id, so neither needs a
 * full `session/list` round trip or a deserialised summary.
 */
sealed interface SessionDelta {
    /** The session this delta is about, whatever kind it is. */
    val sessionId: String

    /** `api-session/status(sessionId, running)`. */
    data class Running(override val sessionId: String, val running: Boolean) : SessionDelta

    /** `api-session/activity(sessionId, updatedAt)` — a human sent a message. */
    data class Activity(override val sessionId: String, val updatedAt: Long) : SessionDelta

    /** `api-session/added(summary)`: a session appeared, or its shape changed. */
    data class Added(val session: SessionSummary) : SessionDelta {
        override val sessionId: String get() = session.sessionId
    }

    /** `api-session/removed(sessionId)`. */
    data class Removed(override val sessionId: String) : SessionDelta

    companion object {
        /**
         * Decode one emit frame, or null when it is not a session-state event.
         *
         * Null is the common case: `$events` also carries waterfalls and notices
         * this client answers elsewhere.
         */
        fun from(event: String, args: List<JsonElement>): SessionDelta? = when (event) {
            "api-session/status" -> {
                val id = args.getOrNull(0)?.jsonPrimitive?.contentOrNull
                val running = args.getOrNull(1)?.jsonPrimitive?.booleanOrNull
                if (id == null || running == null) null else Running(id, running)
            }
            "api-session/activity" -> {
                val id = args.getOrNull(0)?.jsonPrimitive?.contentOrNull
                val at = args.getOrNull(1)?.jsonPrimitive?.longOrNull
                if (id == null || at == null) null else Activity(id, at)
            }
            "api-session/added" -> args.getOrNull(0)
                ?.let { SessionListCodec.parseOne(it) }
                ?.let(::Added)
            "api-session/removed" -> args.getOrNull(0)
                ?.jsonPrimitive?.contentOrNull
                ?.let(::Removed)
            else -> null
        }
    }
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

/**
 * The projection values the client reads.
 *
 * `session/list` and `session/follow` both carry `projections.values`, a map of
 * derived session facts. Only the ones the UI shows are modelled; the map holds
 * far more (turn outlines, subagent catalogs, permissions) and is deliberately
 * not given a closed schema, because strict decoding of it is what once made the
 * session list come back empty.
 */
@Serializable
data class ProjectionValues(
    val title: String? = null,
    val modelSelection: ProjectionModelSelection? = null,
    val tokenUsage: TokenUsage? = null,
    val contextPressure: ContextPressure? = null,
    val contextBreakdown: ContextBreakdown? = null,
    val sessionStats: SessionStats? = null,
)

@Serializable
data class ProjectionModelSelection(
    val lastUsed: ModelSelection? = null,
    val next: ModelSelection? = null,
)

@Serializable
data class TokenUsage(
    val uncachedInputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    /** Tokens written into the prompt cache; usually 0 after the first request. */
    val cacheWriteTokens: Long = 0,
    /** The reasoning share of the output, when the provider breaks it out. */
    val reasoningTokens: Long = 0,
)

@Serializable
data class ContextPressure(
    val pressureTokens: Long = 0,
    val projectedTokens: Long = 0,
    /**
     * The provider's window, or 0 when it reported none. Zero and absent mean the
     * same thing here -- no window to divide by -- so the meter hides rather than
     * inventing a denominator.
     */
    val contextWindow: Long = 0,
)

/** What the context window currently holds, split three ways. */
@Serializable
data class ContextBreakdown(
    val systemTokens: Long = 0,
    val toolsTokens: Long = 0,
    val messageTokens: Long = 0,
)

/** Session totals: turns, steps, and where the wall-clock time went. */
@Serializable
data class SessionStats(
    val turns: Long = 0,
    val steps: Long = 0,
    val llmMs: Long = 0,
    val toolMs: Long = 0,
    val ttftMs: Long = 0,
    val ttftSteps: Long = 0,
    val decodeMs: Long = 0,
    val decodeTokens: Long = 0,
) {
    /** Tokens per second over decoding time alone, or null when nothing decoded. */
    fun tokensPerSecond(): Double? =
        if (decodeMs <= 0) null else decodeTokens.toDouble() / (decodeMs / 1000.0)

    /** Mean time to first token, or null when no step reported one. */
    fun meanTtftMs(): Long? = if (ttftSteps <= 0) null else ttftMs / ttftSteps
}

/** Read the projection values out of a session summary or event payload. */
fun projectionsOf(element: JsonElement?): ProjectionValues? {
    val values = (element as? JsonObject)?.get("values") ?: return null
    return runCatching {
        DshWire.json.decodeFromJsonElement(ProjectionValues.serializer(), values)
    }.getOrNull()
}

/** One selectable provider/model pair, with its reasoning options. */
@Serializable
data class ModelEntry(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val reasoning: ModelReasoning? = null,
)

@Serializable
data class ModelReasoning(
    val efforts: List<ReasoningEffort> = emptyList(),
    val defaultEffort: String? = null,
)

@Serializable
data class ReasoningEffort(val id: String, val name: String? = null, val description: String? = null)

@Serializable
data class ModelProviderGroup(
    val id: String,
    val name: String? = null,
    val models: List<ModelEntry> = emptyList(),
)

@Serializable
data class ModelCatalog(
    val default: ModelSelection = ModelSelection(),
    val routableProviders: List<String> = emptyList(),
    val groups: List<ModelProviderGroup> = emptyList(),
)

/** Current or requested provider/model/reasoning choice. */
@Serializable
data class ModelSelection(
    val provider: String = "",
    val model: String = "",
    val reasoningEffort: String? = null,
)

@Serializable
data class SelectModelRequest(
    val sessionId: String,
    val provider: String,
    val model: String,
    val reasoningEffort: String? = null,
)

@Serializable
data class SelectModelValue(val selected: ModelSelection = ModelSelection())

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
        return items.mapNotNull { parseOne(it) }
    }

    /** One summary, as `session/list` carries it and as `api-session/added` pushes it. */
    fun parseOne(element: JsonElement): SessionSummary? {
        val obj = element as? JsonObject ?: return null
        val sessionId = obj.string("sessionId") ?: return null
        return SessionSummary(
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
    /**
     * How a prompt sent while the agent is busy is delivered.
     *
     * `steer` folds the text into the running turn, so a correction reaches the
     * agent while it is still working instead of waiting for the next turn. The
     * Host keeps the window: a submission arriving after it closed becomes the
     * next queue item, so steering is best-effort and never drops the text.
     * `queue` is the Host's own default and what the desktop composer does for a
     * plain Enter; this client steers, because a phone sending one short
     * correction mid-turn is the case that mode exists for.
     */
    val mode: String = "steer",
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
        /**
         * What the agent has not read yet, straight from the projection bag.
         *
         * A snapshot is the only frame that carries the whole inbox, so it is
         * what seeds the mirror the durable splices then maintain.
         */
        val inbox: InboxProjection? = null,
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
                inbox = inboxOf(obj["projections"]),
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

    /**
     * The `inbox` projection inside a snapshot's projection bag.
     *
     * Decoded by hand rather than through the strict serializer: this is a
     * message list whose blocks this build does not model, and a shape it cannot
     * read must yield "no inbox" instead of failing the whole snapshot -- which
     * is what a strict decode of an unknown block did to the session list once.
     */
    private fun inboxOf(projections: JsonElement?): InboxProjection? {
        val bag = (projections as? JsonObject)?.get("values") as? JsonObject ?: return null
        val inbox = bag["inbox"] as? JsonObject ?: return null
        return InboxProjection(
            nextTurn = inbox["next-turn"].toInboxMessages(),
            nextStep = inbox["next-step"].toInboxMessages(),
        )
    }

    private fun JsonElement?.toInboxMessages(): List<InboxMessage> =
        (this as? JsonArray).orEmpty().mapNotNull { element ->
            val message = element as? JsonObject ?: return@mapNotNull null
            val id = (message["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val source = message["source"] as? JsonObject
            InboxMessage(id, (source?.get("rpcId") as? JsonPrimitive)?.contentOrNull)
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
 * The `inbox` projection: input the agent has been given but has not read.
 *
 * `next-turn` waits for a turn of its own, `next-step` is folded into the next
 * step of the running one -- which is where a steer goes.
 */
data class InboxProjection(
    val nextTurn: List<InboxMessage> = emptyList(),
    val nextStep: List<InboxMessage> = emptyList(),
)

/** One pending message, by the two identities a reply can be matched on. */
data class InboxMessage(val id: String, val rpcId: String?)

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
     * Whether a user message came from a plugin rather than the human.
     *
     * Background-job results arrive as `user/message` with
     * `source.kind == "plugin"` (10 of the 11 in a sampled turn), which renders
     * as if the user had typed the harness's own status text. A real prompt
     * carries `source.kind == "user"`.
     */
    /**
     * The `source.kind` of a message, e.g. `plugin`, `agent-message`, `user`.
     *
     * This is the field that says who produced a message, and it is a wider set
     * than `plugin`: a relayed subagent message is `agent-message`, a subagent
     * finishing is `subagent-settled`, a skill catalog is `skill-catalog`, and an
     * instructions update is `agent-instructions`. Only `user` is a person.
     */
    fun sourceKind(event: SessionEvent): String? =
        ((event.data as? JsonObject)?.get("source") as? JsonObject)
            ?.get("kind")?.jsonPrimitive?.contentOrNull

    /**
     * The session a relayed message came from, when it came from another agent.
     *
     * Present on `agent-message` and `subagent-settled`; absent on the user's own
     * messages, which is another way to tell them apart.
     */
    fun senderSessionId(event: SessionEvent): String? =
        ((event.data as? JsonObject)?.get("source") as? JsonObject)
            ?.get("senderSessionId")?.jsonPrimitive?.contentOrNull

    /**
     * The plugin that emitted a notice, e.g. `tool-jobs` or `model-selection`.
     *
     * The plugin name is the stable part of a `plugin` notice; `summary` is often
     * absent. Null for every other kind, so a caller can fall back to the kind.
     */
    fun noticePlugin(event: SessionEvent): String? {
        val source = (event.data as? JsonObject)?.get("source") as? JsonObject ?: return null
        if ((source["kind"] as? JsonPrimitive)?.contentOrNull != "plugin") return null
        return (source["plugin"] as? JsonPrimitive)?.contentOrNull
    }

    /** The one-line summary a plugin attached to its notice, if any. */
    fun noticeSummary(event: SessionEvent): String? {
        val source = (event.data as? JsonObject)?.get("source") as? JsonObject ?: return null
        return (source["summary"] as? JsonPrimitive)?.contentOrNull?.lineSequence()?.firstOrNull()?.trim()
    }

    /** One row of a `todo/write` snapshot: the plan the model is working to. */
    data class Todo(val content: String, val status: String)

    /**
     * Read the todo list a `todo/write` event replaces wholesale.
     *
     * Every write carries the complete list, so the newest event is the plan --
     * there is no delta to fold. Statuses are `pending`, `in_progress`, and
     * `completed`; anything else is shown verbatim rather than dropped, because a
     * status this build has not seen is still information.
     */
    fun todoList(event: SessionEvent): List<Todo>? {
        val data = event.data as? JsonObject ?: return null
        val todos = data["todos"] as? JsonArray ?: return null
        return todos.mapNotNull { entry ->
            val row = entry as? JsonObject ?: return@mapNotNull null
            val content = (row["content"] as? JsonPrimitive)?.contentOrNull?.trim() ?: return@mapNotNull null
            if (content.isEmpty()) return@mapNotNull null
            Todo(content, (row["status"] as? JsonPrimitive)?.contentOrNull ?: "pending")
        }.takeIf { it.isNotEmpty() }
    }

    /** One file a turn declared as a deliverable. */
    data class DeliveredFile(val path: String, val description: String?)

    /**
     * Read the files a `deliverables/presented` event announces.
     *
     * The paths are absolute and are what the preview reads back over
     * `workspaceFiles/read`; `description` is the model's own note about why the
     * file matters, which is usually more useful than the name.
     */
    fun deliveredFiles(event: SessionEvent): List<DeliveredFile>? {
        val data = event.data as? JsonObject ?: return null
        val files = data["files"] as? JsonArray ?: return null
        return files.mapNotNull { entry ->
            val row = entry as? JsonObject ?: return@mapNotNull null
            val path = (row["path"] as? JsonPrimitive)?.contentOrNull?.trim() ?: return@mapNotNull null
            if (path.isEmpty()) return@mapNotNull null
            DeliveredFile(path, (row["description"] as? JsonPrimitive)?.contentOrNull)
        }.takeIf { it.isNotEmpty() }
    }

    /** The model a `model/selection` event pinned, e.g. `deepseek-flash` + effort. */
    data class ModelChoice(val provider: String, val model: String, val effort: String?)

    fun modelChoice(event: SessionEvent): ModelChoice? {
        val data = event.data as? JsonObject ?: return null
        val model = (data["model"] as? JsonPrimitive)?.contentOrNull ?: return null
        val provider = (data["provider"] as? JsonPrimitive)?.contentOrNull ?: ""
        return ModelChoice(provider, model, (data["reasoningEffort"] as? JsonPrimitive)?.contentOrNull)
    }

    /** A session-scoped setting change the transcript is worth marking. */
    fun settingChange(event: SessionEvent): Pair<String, String>? {
        val data = event.data as? JsonObject ?: return null
        fun value(key: String) = (data[key] as? JsonPrimitive)?.contentOrNull
        return when (event.type) {
            "permission/preset" -> value("preset")?.let { "permission" to it }
            "sandbox/mode" -> value("mode")?.let { "sandbox" to it }
            "approval/policy" -> value("policy")?.let { "approval" to it }
            "compaction/start" -> "compaction" to "summarizing history"
            "compaction/end" -> "compaction" to "history summarized"
            else -> null
        }
    }

    /**
     * What a `turn/end` says, or null when the turn simply finished.
     *
     * `TurnEndReasonMap` has six members: `completed`, `aborted`, `blocked`,
     * `error`, `max-tokens`, and `interrupted`. Only `completed` is unremarkable
     * -- a turn that was aborted, blocked, cut off at the token ceiling, or
     * orphaned by a crash is exactly what a reader needs told, and it is
     * otherwise invisible because the transcript just stops.
     */
    fun turnOutcome(event: SessionEvent): String? {
        val reason = ((event.data as? JsonObject)?.get("reason") as? JsonObject) ?: return null
        val kind = (reason["kind"] as? JsonPrimitive)?.contentOrNull ?: return null
        return when (kind) {
            "completed" -> null
            "error" -> {
                val error = reason["error"] as? JsonObject
                // Provider messages arrive with a trailing newline; trimming keeps
                // the note one clean line.
                val message = error?.get("message")?.jsonPrimitive?.contentOrNull?.trim()
                val code = error?.get("code")?.jsonPrimitive?.contentOrNull
                "turn failed: " + (message?.take(300)?.takeIf { it.isNotEmpty() } ?: code ?: "unknown error")
            }
            "max-tokens" -> "turn hit its output-token ceiling"
            "interrupted" -> "turn was interrupted (the session was closed mid-turn)"
            "aborted" -> "turn aborted"
            "blocked" -> "turn blocked"
            // A plugin may merge its own reason into the map; naming it is better
            // than dropping the row.
            else -> "turn ended: $kind"
        }
    }

    /**
     * Whether a `user/message` was produced by the harness and not by a person.
     *
     * The test is "kind is present and is not `user`", not "kind is `plugin`":
     * matching only `plugin` let relayed subagent messages, subagent settle
     * notices, skill catalogs, and instructions updates fall through to the human
     * bubble, which rendered machine reports as if the user had typed them.
     */
    fun isNotice(event: SessionEvent): Boolean {
        val kind = sourceKind(event) ?: return false
        return kind != "user"
    }

    /**
     * Kinds that are the harness talking to itself and should not be shown at all.
     *
     * An instructions update and a skill catalog are inputs the harness injects,
     * not turns of the conversation: they are long, they repeat on every change,
     * and the reader never acts on them from a phone.
     */
    fun isHiddenSource(event: SessionEvent): Boolean = when (sourceKind(event)) {
        "agent-instructions", "skill-catalog" -> true
        else -> false
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
     * The shape is the wire's, measured: a streamed token is
     * `{"type":"text-delta","index":1,"text":"D"}`, and the thinking that
     * precedes it is the same with `reasoning-delta`. The first version here
     * accepted only `type == "text"` -- a kind the Host never sends -- so every
     * delta was dropped and the live bubble it feeds never held a character.
     *
     * A reasoning delta is still dropped deliberately: chain of thought in the
     * live bubble would be indistinguishable from the answer while it streams.
     * A bare string chunk is accepted as-is because some frames carry plain
     * concatenated text, and a finished block is accepted because a frame that
     * carries one is a message rather than a delta.
     */
    fun chunkText(chunk: JsonElement?): String? {
        (chunk as? JsonPrimitive)?.takeIf { it.isString }?.let { return it.content }
        val obj = chunk as? JsonObject ?: return null
        val kind = (obj["type"] as? JsonPrimitive)?.contentOrNull
        when (kind) {
            "text-delta" -> return (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            "reasoning-delta", "reasoning" -> return null
        }
        if (kind != null && kind != "text") return null
        val joined = blocksOf(obj).filter { it.type == "text" }.mapNotNull { it.text }.joinToString("")
        if (joined.isNotEmpty()) return joined
        return (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

