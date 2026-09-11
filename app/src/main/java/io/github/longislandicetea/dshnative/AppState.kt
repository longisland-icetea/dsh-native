package io.github.longislandicetea.dshnative

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import android.util.Log

/**
 * A Host call waiting for this client.
 *
 * Approval prompts and user questions arrive as waterfalls on `$events`; the
 * answer is the listener's return value, sent back through `$events/result`.
 * Until it is answered the agent stays blocked, so these are the only items the
 * UI must never let a user miss.
 */
/** One question in a user-questions request. */
@Serializable
data class QuestionItem(
    val id: String,
    val question: String,
    val detail: String? = null,
    val header: String? = null,
    val options: List<QuestionOption> = emptyList(),
    val multiSelect: Boolean? = null,
)

@Serializable
data class QuestionOption(val label: String, val description: String? = null)

@Serializable
data class QuestionRequest(val questions: List<QuestionItem> = emptyList())

/** The answer batch a user-questions waterfall returns. */
@Serializable
data class QuestionAnswer(val answers: List<QuestionAnswerItem> = emptyList())

@Serializable
data class QuestionAnswerItem(
    val id: String,
    val selected: List<String> = emptyList(),
    val custom: String? = null,
)

data class PendingInteraction(
    val sessionId: String,
    val eventId: String,
    val kind: String,
    val toolName: String?,
    val callId: String?,
    val reason: String?,
    /** Answer values this presentation offers, in display order. */
    val choices: List<Choice>,
    /** Populated for a user-questions request. */
    val questions: List<QuestionItem> = emptyList(),
) {
    data class Choice(val label: String, val value: String)

    companion object {
        /** Approval decisions the Host accepts, per the approval client contract. */
        private val APPROVAL_CHOICES = listOf(
            Choice("Allow once", "allowed-once"),
            Choice("Reject", "rejected"),
        )

        /** Build one from a decoded waterfall, or null when unsupported. */
        fun from(event: HostEvent.Waterfall): PendingInteraction? {
            val request = event.request
            fun field(name: String): String? = (request[name] as? JsonPrimitive)?.contentOrNull

            return when (event.event) {
                "approval/request" -> PendingInteraction(
                    sessionId = event.agentId,
                    eventId = event.eventId,
                    kind = "approval",
                    toolName = field("toolName"),
                    callId = field("callId"),
                    reason = field("reason"),
                    choices = APPROVAL_CHOICES,
                )
                // Questions and plan reviews arrive on their own event: the
                // answer is a structured batch keyed by question id, not a
                // single decision string.
                "user-questions/request" -> {
                    // Decode the whole request: it IS the QuestionRequest. Feeding
                    // its `questions` array to that serializer fails, because the
                    // serializer expects the object that contains the field.
                    val questions = runCatching {
                        DshWire.json.decodeFromJsonElement(QuestionRequest.serializer(), request).questions
                    }.getOrDefault(emptyList())
                    if (questions.isEmpty()) null else PendingInteraction(
                        sessionId = event.agentId,
                        eventId = event.eventId,
                        kind = if (questions.size == 1 && questions[0].detail != null &&
                            questions[0].options.size <= 2 && questions[0].multiSelect != true
                        ) "plan-review" else "question",
                        toolName = null,
                        callId = null,
                        reason = null,
                        choices = emptyList(),
                        questions = questions,
                    )
                }
                else -> null
            }
        }
    }
}

/**
 * Keep a long-lived stream subscribed, whatever ended it.
 *
 * `retry` and `retryWhen` only see failures, and a socket teardown reaches these
 * streams as a plain completion. Nothing re-subscribed, so the banner still said
 * connected while the stream was gone -- and for the forwarded-event stream that
 * is not cosmetic: the Host replays a pending question only to a client that is
 * listening at that moment, so an unsubscribed client never learns an agent is
 * waiting, and the agent waits for an answer nobody can give.
 *
 * `onCompletion` turns a *clean* end into an `UpstreamEnded` failure, which
 * `retryWhen` does see; a real failure is retried on its own cause. Verified by
 * `StreamResubscribeTest`, which is what showed the plain `retry` version of this
 * fix does nothing at all for a clean end.
 */
internal fun <T> Flow<T>.resubscribe(
    delayMillis: Long,
    onEnd: (Throwable?) -> Unit = {},
): Flow<T> = onCompletion { cause ->
    if (cause == null) throw UpstreamEnded()
}.retryWhen { cause, _ ->
    onEnd(cause)
    delay(delayMillis)
    true
}

/** A stream that finished cleanly, so [resubscribe] can retry it. */
internal class UpstreamEnded : RuntimeException("stream closed")

/** One row in the transcript. */
/**
 * The unread set after one running-state change.
 *
 * A turn that stops in a session the reader is not looking at finished something
 * they have not seen, which is what marks it unread; opening the session clears the
 * mark. A turn that starts is busy again, so it is not "finished and unseen".
 *
 * Pure so the transitions can be tested: the Host reports running state but nothing
 * about read state, so this rule is the whole definition of unread in the client.
 */
internal fun unreadAfter(
    unread: Set<String>,
    sessionId: String,
    running: Boolean,
    opened: String?,
): Set<String> = when {
    running -> unread - sessionId
    sessionId != opened -> unread + sessionId
    else -> unread
}

/**
 * The card header for a notice: the plugin's short name, or the source kind.
 *
 * `source.plugin` is a package path for built-ins (`tool-jobs`) but a scope path
 * for others (`@deepseek-ai/dsh-system-prompt`); the trailing segment is the part
 * a reader recognises. Kinds that are not plugin notices fall back to the kind
 * itself with its dashes opened up -- `agent-message` reads as "agent message".
 */
private fun noticeLabel(plugin: String?, kind: String?): String {
    val name = plugin?.substringAfterLast('/')?.removePrefix("@")?.removePrefix("dsh-")
    if (!name.isNullOrBlank()) return name
    return kind?.replace('-', ' ')?.takeIf { it.isNotBlank() } ?: "notice"
}

sealed interface TranscriptItem {
    val key: String

    data class User(override val key: String, val text: String) : TranscriptItem
    data class Assistant(override val key: String, val text: String, val streaming: Boolean) : TranscriptItem
    data class Activity(override val key: String, val label: String, val detail: String?) : TranscriptItem

    /**
     * One tool invocation with its outcome, kept as a single row.
     *
     * The Host emits `tool/call` and `tool/result` as separate events correlated
     * by `callId`; rendering them as two unrelated lines makes a long tool-heavy
     * turn unreadable, so the reducer folds the result into the call it answers.
     */
    data class ToolCall(
        override val key: String,
        val callId: String?,
        val name: String,
        val arguments: kotlinx.serialization.json.JsonObject?,
        val rawArguments: String?,
        val result: String?,
        /**
         * Whether the call has been answered. A null result means "no output",
         * which is different from "no result yet": keying status off a nullable
         * result left completed calls showing as running forever.
         */
        val status: Status,
    ) : TranscriptItem {
        enum class Status { RUNNING, DONE, FAILED }

        fun callIdOrNull(): String? = callId
    }

    /** A result waiting to be folded into its call. */
    /**
     * A harness notice, shown as a card in the tool-call style.
     *
     * Background jobs arrive as plugin-sourced user messages; rendering them as
     * chat text made the harness look like the human.
     */
    /**
     * One turn's cost, placed after the turn it describes.
     *
     * A row rather than something the renderer derives: the first attempt looked
     * the turn up from the row key while drawing, which needed the row to be the
     * conversation's last and put the figure in the wrong place besides. The
     * reducer knows when a turn ends and what it cost, so it says so once.
     */
    data class Usage(
        override val key: String,
        val usage: TokenUsage,
        val turn: Int,
    ) : TranscriptItem

    data class Notice(
        override val key: String,
        val label: String,
        val plugin: String?,
        /**
         * The subagent that sent this, when it came from one.
         *
         * A relayed message and a settle notice both name their sender, and which
         * subagent spoke is the whole point of showing the card.
         */
        val sender: String?,
        val body: String?,
    ) : TranscriptItem

    /** The model's plan, as the newest `todo/write` in the window left it. */
    data class Todo(
        override val key: String,
        val todos: List<EventPayload.Todo>,
    ) : TranscriptItem

    /**
     * Files a turn declared as its output.
     *
     * [workspaceRoot] is carried down from the session's Workspace so a relative
     * path can be resolved for the preview; the Host reads an absolute path or
     * one relative to that root, and nothing else.
     */
    data class Deliverables(
        override val key: String,
        val files: List<EventPayload.DeliveredFile>,
        val workspaceRoot: String?,
    ) : TranscriptItem

    data class ToolResultRow(
        override val key: String,
        val toolCallId: String?,
        val text: String?,
        val failed: Boolean,
    ) : TranscriptItem
    data class Note(override val key: String, val text: String) : TranscriptItem
}

/**
 * What the preview sheet is showing for one file.
 *
 * A failed read is a first-class outcome, not an exception: a path the Host
 * cannot resolve is a normal answer, and the sheet says so instead of leaving the
 * tap with no visible effect.
 */
sealed interface FilePreview {
    val path: String

    /** UTF-8 text, shown as a selectable monospace block. */
    data class Text(override val path: String, val body: String) : FilePreview

    /**
     * An image, decoded from the Host's raw bytes.
     *
     * `mime` decides whether it can be drawn; a file that is neither text nor a
     * known image ends up here with a null `mime` and the sheet reports its size
     * instead of pretending to render it.
     */
    data class Bitmap(
        override val path: String,
        val bytes: ByteArray,
        val mime: String?,
        val totalBytes: Long?,
    ) : FilePreview {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    /** The read failed; [reason] is written for a reader, not a developer. */
    data class Failed(override val path: String, val reason: String) : FilePreview
}

data class Conversation(
    val sessionId: String,
    val title: String = "",
    val items: List<TranscriptItem> = emptyList(),
    /** Highest durable seq folded into [items]. */
    val lastSeq: Long = 0,
    /** Snapshot cursor of the newest follow window; bounds earlier paging. */
    val throughSeq: Long = 0,
    val hasMore: Boolean = false,
    val running: Boolean = false,
    /** Live assistant text for the attempt in flight. */
    val liveText: String = "",
    /**
     * The session's working directory, used to resolve a deliverable the model
     * named relatively. The Host resolves reads against this root, so a preview
     * has to resolve them the same way.
     */
    val workspaceRoot: String? = null,
    val error: String? = null,
)

/**
 * Sessions grouped the way the desktop sidebar groups them.
 *
 * `session/list` carries no workspace or archive fields — the desktop derives
 * both from `cwd` plus client-side state — so the same derivation happens here:
 * ordinary sessions (a subagent's own session is a child, not a row), a blank
 * session only while it is the current one, grouped by directory and ordered
 * newest-first with the id as tiebreak.
 */
data class SessionGroup(
    val key: String,
    val label: String,
    val path: String?,
    val sessions: List<SessionSummary>,
    val expanded: Boolean,
    /**
     * The Host Workspace this group came from, when it came from one.
     *
     * A new session is created against a workspace id rather than a path: the
     * Host refuses `workspaceId` and `cwd` together, and only the workspace route
     * makes the session a member of the group it was created from.
     */
    val workspaceId: String? = null,
) {
    val newestAt: Long get() = sessions.maxOfOrNull { it.updatedAt } ?: 0L
    companion object {
        fun labelOf(cwd: String?): String {
            val path = cwd?.trimEnd('/') ?: return ""
            if (path.isEmpty()) return ""
            return path.substringAfterLast('/').ifEmpty { path }
        }

        /**
         * Build groups from the Host's own Workspace list.
         *
         * Membership and order come from `WorkspaceView.sessionIds`; sessions the
         * Host has not placed in any Workspace land in a trailing "other" group
         * rather than disappearing. Archive membership is the Host's set, which
         * is what makes the choice visible on every client.
         */
        fun fromWorkspaces(
            sessions: List<SessionSummary>,
            workspaces: List<WorkspaceView>,
            currentId: String?,
            archived: Set<String>,
            collapsed: Set<String>,
            showArchived: Boolean,
        ): List<SessionGroup> {
            val byId = sessions.associateBy { it.sessionId }
            fun visible(session: SessionSummary?): Boolean =
                session != null &&
                    session.origin != "subagent" &&
                    (showArchived || !archived.contains(session.sessionId)) &&
                    (!session.blank || session.sessionId == currentId)

            val groups = mutableListOf<SessionGroup>()
            val accounted = mutableSetOf<String>()
            for (workspace in workspaces) {
                val members = workspace.sessionIds.mapNotNull { byId[it] }.filter(::visible)
                workspace.sessionIds.forEach { accounted += it }
                groups += SessionGroup(
                    key = workspace.workspaceId,
                    label = workspace.title.ifEmpty { labelOf(workspace.path) },
                    path = workspace.path,
                    sessions = members,
                    expanded = !collapsed.contains(workspace.workspaceId),
                    workspaceId = workspace.workspaceId,
                )
            }
            val stray = sessions.filter { it.sessionId !in accounted && visible(it) }
                .sortedWith(compareByDescending<SessionSummary> { it.updatedAt }.thenBy { it.sessionId })
            if (stray.isNotEmpty()) {
                groups += SessionGroup(
                    key = "·other",
                    label = "Other",
                    path = null,
                    sessions = stray,
                    expanded = !collapsed.contains("·other"),
                )
            }
            // Empty Workspaces stay listed: the desktop shows them too, and they
            // are where a new session would go.
            return groups
        }
    }
}

data class AppState(
    val endpoint: DshEndpoint? = null,
    val connected: Boolean = false,
    val sessions: List<SessionSummary> = emptyList(),
    val conversation: Conversation? = null,
    val busy: Boolean = false,
    val log: List<String> = emptyList(),
    /** Last session-list failure, surfaced in the drawer. */
    val sessionsError: String? = null,
    /** Bytes of the last session/list body, decompressed; a cheap sanity check. */
    val sessionsBytes: Int = 0,
    /** Host calls blocked on this client, newest last. */
    val pending: List<PendingInteraction> = emptyList(),
    /** Workspaces as the Host defines them, with canonical membership and order. */
    val workspaces: List<WorkspaceView> = emptyList(),
    /** The Host's archive set; shared by every client. */
    val archived: Set<String> = emptySet(),
    /** Group keys (cwd) the user collapsed. */
    val collapsed: Set<String> = emptySet(),
    val showArchived: Boolean = false,
    /** Whether the drawer's transport-log panel is shown. Off by default. */
    val showLog: Boolean = false,
    /**
     * Whether the Host has stated which sessions are archived.
     *
     * `session/list` and `workspace/follow` are separate calls and the list usually
     * wins the race by a second or so, so for that window the archive set is empty
     * and every archived session looks live. The drawer therefore waits for this
     * before drawing a list: showing 75 sessions and then filtering down to 38 is
     * worse than showing a loading line for a second.
     */
    val archivedKnown: Boolean = false,
    /**
     * Sessions that finished a turn the reader has not looked at yet.
     *
     * The Host reports `running` but nothing about read state, and `updatedAt`
     * tracks only the reader's own prompts, so "unread" is maintained here: a
     * session entering the set when its turn ends while it is not on screen, and
     * leaving it when the reader opens it.
     */
    val unread: Set<String> = emptySet(),
    /**
     * Set when the workspace stream has stopped without ever delivering a baseline.
     *
     * A slow stream is not a failed one, so the drawer waits for the baseline rather
     * than for a timer; this is what releases it if the stream is genuinely gone.
     */
    val workspaceFailed: Boolean = false,
    /**
     * Usage, context pressure and session totals per session.
     *
     * Filled from the `session/control` baseline and its projection frames, and
     * seeded from each session summary on a list refresh so the numbers are there
     * before the stream has spoken. Keyed by session because the control stream is
     * Host-wide, not per-session.
     */
    val metrics: Map<String, Metrics> = emptyMap(),
    /** Pending queue per session, as the Host reports it. */
    val queues: Map<String, List<QueuedItem>> = emptyMap(),
    /**
     * Usage accumulated for the turn that is still open, keyed by turn number.
     *
     * A turn has several assistant messages, each carrying its own `usage`, and
     * the Host projects session totals rather than turns -- so the turn's cost is
     * their sum, gathered here and turned into a [TranscriptItem.Usage] row when
     * `turn/end` arrives. Only the open turn is kept: a finished turn's figures
     * live on its row.
     */
    val pendingTurnUsage: Map<Int, TokenUsage> = emptyMap(),
    /** Seqs already folded into [pendingTurnUsage], so a replay cannot double-count. */
    val countedUsageSeqs: Set<Long> = emptySet(),
    /** Slash commands the Host offers for the open conversation. */
    val commands: List<CommandInfo> = emptyList(),
    /**
     * A message being edited in the composer, or null when composing a new one.
     *
     * Editing reuses the composer rather than growing an input inside the queue
     * row: on a phone the real keyboard is the largest text surface available, and
     * a 30-character-tall row is the wrong place to rewrite a paragraph.
     */
    val editingQueued: QueuedItem? = null,
    /** Last queue failure, shown on the dock rather than swallowed. */
    val queueError: String? = null,
    /** Routable models, loaded when the picker first opens. */
    val catalog: ModelCatalog? = null,
    /** Provider/model/effort currently in force for the open conversation. */
    val selection: ModelSelection? = null,
    /**
     * The deliverable the preview sheet is showing.
     *
     * Null means no sheet. A [Conversation] is not the right home for this: the
     * sheet is a property of the reader's attention, not of the transcript, and
     * it must survive a transcript re-snapshot without reopening.
     */
    val preview: FilePreview? = null,
    /** Path whose read is in flight, so the sheet can show that rather than a blank. */
    val previewLoading: String? = null,
) {
    /**
     * Sessions the drawer actually shows.
     *
     * Subagent sessions are children of a parent row and never appear on their
     * own, so counting them made the total disagree with the list — 56 counted
     * against 33 shown. The grouping below applies the same predicate.
     */
    val visibleSessions: List<SessionSummary>
        get() = sessions.filter { session ->
            session.origin != "subagent" &&
                (showArchived || !archived.contains(session.sessionId)) &&
                (!session.blank || session.sessionId == conversation?.sessionId)
        }

    /**
     * Sessions grouped and ordered for the drawer.
     *
     * Empty until the archive set is known, so the first frame cannot show a list
     * that is about to lose a third of its rows.
     */
    val groups: List<SessionGroup>
        get() = if (!archivedKnown) {
            emptyList()
        } else {
            SessionGroup.fromWorkspaces(
                sessions, workspaces, conversation?.sessionId, archived, collapsed, showArchived,
            )
        }
}

/**
 * Owns the client, the session list, and one live conversation.
 *
 * A reconnect deliberately does *not* try to resume silently: the follow stream
 * is reopened and its opening snapshot is merged by seq, which is what makes the
 * client cheap (a 50 KiB snapshot) rather than a full repaint of history.
 */
private const val TAG = "DshNative"

/**
 * Per-device session view state (archive + collapsed groups).
 *
 * `session/list` has no archive field: the desktop keeps its own set in browser
 * storage, so this client keeps its own here. The two do not see each other's
 * choices; sharing them would need server-side state in a plugin.
 */
private class SessionViewStore(context: android.content.Context) {
    private val prefs = context.getSharedPreferences("dsh_session_view", android.content.Context.MODE_PRIVATE)

    fun collapsed(): Set<String> = prefs.getStringSet("collapsed", emptySet()) ?: emptySet()
    fun showArchived(): Boolean = prefs.getBoolean("showArchived", false)
    fun showLog(): Boolean = prefs.getBoolean("showLog", false)
    fun unread(): Set<String> = prefs.getStringSet("unread", emptySet()) ?: emptySet()

    fun saveCollapsed(value: Set<String>) = prefs.edit().putStringSet("collapsed", value).apply()
    fun saveShowArchived(value: Boolean) = prefs.edit().putBoolean("showArchived", value).apply()
    fun saveShowLog(value: Boolean) = prefs.edit().putBoolean("showLog", value).apply()
    fun saveUnread(value: Set<String>) = prefs.edit().putStringSet("unread", value).apply()
}

class AppStateHolder(private val scope: CoroutineScope, context: android.content.Context? = null) {
    private val viewStore = context?.let(::SessionViewStore)
    private val _state = MutableStateFlow(
        AppState(
            collapsed = viewStore?.collapsed() ?: emptySet(),
            // Archived sessions are hidden unless asked for; the switch lives in
            // settings and this is its default, not its remembered value.
            showArchived = false,
            // The transport log is off by default: it is a debugging aid, and it
            // pushes the session list up the drawer whenever it is on.
            showLog = viewStore?.showLog() ?: false,
            // Unread survives a restart: a turn that finished while the app was
            // closed is still something the reader has not seen, and the Host does
            // not track read state for us.
            unread = viewStore?.unread() ?: emptySet(),
        ),
    )
    val state: StateFlow<AppState> = _state.asStateFlow()

    fun toggleGroup(key: String) {
        _state.update { current ->
            val next = if (current.collapsed.contains(key)) current.collapsed - key else current.collapsed + key
            viewStore?.saveCollapsed(next)
            current.copy(collapsed = next)
        }
    }

    fun setShowArchived(value: Boolean) {
        viewStore?.saveShowArchived(value)
        _state.update { it.copy(showArchived = value) }
    }

    fun setShowLog(value: Boolean) {
        viewStore?.saveShowLog(value)
        _state.update { it.copy(showLog = value) }
    }

    private var client: DshClient? = null
    private var followJob: Job? = null
    private var eventsJob: Job? = null
    private var workspaceJob: Job? = null
    private var controlJob: Job? = null
    /** Bound by the `$events` ready frame; every answer must name it. */
    private var eventClientId: String? = null

    fun connect(endpoint: DshEndpoint) {
        disconnect(quiet = true)
        val created = DshClient(endpoint, scope)
        client = created
        // Connecting somewhere else invalidates the previous Host's grouping and
        // archive set, and the drawer waits for the new baseline before drawing a
        // list -- otherwise the old Host's archive choices would hide rows on the
        // new one for a second.
        _state.update {
            it.copy(
                endpoint = endpoint,
                workspaces = emptyList(),
                archived = emptySet(),
                sessions = emptyList(),
                archivedKnown = false,
            )
        }
        created.start()

        scope.launch(Dispatchers.IO) {
            created.connected.collect { alive ->
                _state.update { it.copy(connected = alive) }
                // Bounce the conversation stream through every reconnect so the
                // snapshot lands again instead of leaving a silent gap.
                val conversation = _state.value.conversation
                if (alive && conversation != null) openFollow(conversation.sessionId, conversation.title)
            }
        }
        scope.launch(Dispatchers.IO) {
            created.log.collect { line -> record(line) }
        }
        openEvents(created)
        openWorkspaces(created)
        openControl(created)
        refreshSessions()
    }

    /** Subscribe to forwarded Host events so waterfalls can be answered. */
    private fun openEvents(active: DshClient) {
        eventsJob?.cancel()
        eventsJob = scope.launch(Dispatchers.IO) {
            // A mux that is not up yet, or that just dropped, must not kill the
            // process: `callbackFlow` closing with a cause while nothing collects
            // surfaces as an unhandled exception. Retry until the socket is up.
            try {
            // Resubscribe on any end, completion included. `retryWhen` only sees
            // failures, and a socket teardown reaching this flow as a plain
            // completion left nothing subscribed: the banner still said connected
            // while waterfalls went nowhere, because the Host replays a pending
            // question only to a client that is listening at that moment. That is
            // the whole difference between the question appearing and the agent
            // waiting for an answer nobody can give.
            val stream: Flow<MuxFrame> = active.events()
                .resubscribe(delayMillis = 2_000) { cause ->
                    record("events ended: ${cause?.message ?: "stream closed"}; resubscribing")
                }
            stream
                .catch { error: Throwable -> record("events stopped: ${error.message}") }
                .collect { frame: MuxFrame ->
                when (frame) {
                    is MuxFrame.Item -> {
                        when (val host = HostEventCodec.decode(frame.value)) {
                            is HostEvent.Ready -> {
                                eventClientId = host.clientId
                                record("events ready (client ${host.clientId.take(8)})")
                            }
                            is HostEvent.Waterfall -> {
                                val pending = PendingInteraction.from(host)
                                if (pending == null) {
                                    // Not something this client can present; let
                                    // the Host fall through instead of hanging.
                                    record(
                                        "declining unsupported waterfall ${host.event}; " +
                                            "request=${host.request.toString().take(320)}",
                                    )
                                    eventClientId?.let { id ->
                                        runCatching { active.delegateWaterfall(id, host.eventId) }
                                    }
                                } else {
                                    record("pending ${pending.kind}: ${pending.toolName ?: "?"}")
                                    _state.update { it.copy(pending = it.pending + pending) }
                                }
                            }
                            is HostEvent.Cancelled ->
                                _state.update { it.copy(pending = it.pending.filterNot { p -> p.eventId == host.eventId }) }
                            // Everything else the Host emits is a notification. The
                            // session-state ones keep the drawer live: without them
                            // a session's running dot and its place in the order
                            // only changed on a manual refresh.
                            is HostEvent.Notify -> applySessionDelta(active, host.event, host.args)
                            else -> Unit
                        }
                    }
                    is MuxFrame.Failure -> record("events stream failed: ${frame.code}")
                    else -> Unit
                }
            }
            } catch (error: Throwable) {
                // A transport failure can surface from the socket's own thread;
                // if it escapes here the process dies instead of retrying.
                record("events stream aborted: ${error.message}")
            }
        }
    }

    /**
     * Apply one pushed session-state change to the list.
     *
     * `api-session/added` carries a whole summary, so it replaces the row outright;
     * `status` and `activity` are one field each and are patched in place, which is
     * what keeps the list live without a `session/list` round trip per event.
     */
    /**
     * Add one assistant message's usage to its turn.
     *
     * A turn has several steps, each with its own usage, so the turn's cost is
     * their sum. The Host's session totals cannot answer "which turn was
     * expensive", which is the question a reader actually has.
     */
    /**
     * Add one assistant message's usage to the turn it belongs to.
     *
     * Returns the accumulated map and the seq guard together, so the caller can
     * keep both in state without this reading or writing any.
     */
    private fun accumulateTurnUsage(
        frame: FollowFrame,
        current: Map<Int, TokenUsage>,
        counted: Set<Long>,
    ): Pair<Map<Int, TokenUsage>, Set<Long>> {
        val unchanged = current to counted
        val event = (frame as? FollowFrame.Event)?.event ?: return unchanged
        if (event.type != "assistant/message") return unchanged
        // One event contributes once, however many times a reconnect replays it.
        if (event.seq in counted) return unchanged
        val data = event.data as? JsonObject ?: return unchanged
        val turn = (data["turn"] as? JsonPrimitive)?.intOrNull ?: return unchanged
        val usage = data["usage"] ?: return unchanged
        val fresh = runCatching {
            DshWire.json.decodeFromJsonElement(TokenUsage.serializer(), usage)
        }.getOrNull() ?: return unchanged
        val previous = current[turn] ?: TokenUsage()
        val sum = TokenUsage(
            uncachedInputTokens = previous.uncachedInputTokens + fresh.uncachedInputTokens,
            outputTokens = previous.outputTokens + fresh.outputTokens,
            cacheReadTokens = previous.cacheReadTokens + fresh.cacheReadTokens,
            cacheWriteTokens = previous.cacheWriteTokens + fresh.cacheWriteTokens,
            reasoningTokens = previous.reasoningTokens + fresh.reasoningTokens,
        )
        return (current + (turn to sum)) to (counted + event.seq)
    }

    private fun applySessionDelta(active: DshClient, event: String, args: List<JsonElement>) {
        when (val delta = SessionDelta.from(event, args)) {
            is SessionDelta.Running -> _state.update { current ->
                val unread = unreadAfter(current.unread, delta.sessionId, delta.running, current.conversation?.sessionId)
                if (unread != current.unread) viewStore?.saveUnread(unread)
                current.copy(
                    // The open conversation takes its running flag from here, so
                    // the composer's Stop/Send control flips the moment the Host
                    // says the turn is over rather than when a row happens to
                    // arrive.
                    conversation = current.conversation?.let { open ->
                        if (open.sessionId == delta.sessionId) open.copy(running = delta.running) else open
                    },
                    sessions = current.sessions.map { summary ->
                        if (summary.sessionId == delta.sessionId) {
                            summary.copy(running = delta.running)
                        } else {
                            summary
                        }
                    },
                    unread = unread,
                )
            }
            is SessionDelta.Activity -> _state.update { current ->
                current.copy(
                    sessions = current.sessions.map { summary ->
                        if (summary.sessionId == delta.sessionId) {
                            summary.copy(updatedAt = delta.updatedAt)
                        } else {
                            summary
                        }
                    },
                )
            }
            is SessionDelta.Added -> _state.update { current ->
                val existing = current.sessions.any { it.sessionId == delta.session.sessionId }
                current.copy(
                    sessions = if (existing) {
                        current.sessions.map {
                            if (it.sessionId == delta.session.sessionId) delta.session else it
                        }
                    } else {
                        current.sessions + delta.session
                    },
                )
            }
            is SessionDelta.Removed -> _state.update { current ->
                current.copy(sessions = current.sessions.filterNot { it.sessionId == delta.sessionId })
            }
            null -> Unit
        }
    }

    /**
     * Follow the Host's workspace browser state.
     *
     * The baseline replaces both grouping and the archive set wholesale, so a
     * reconnect cannot leave a stale membership behind; increments are applied
     * on top. This is what makes an archive chosen on the desktop disappear on
     * the phone, and vice versa.
     */
    private fun openWorkspaces(active: DshClient) {
        workspaceJob?.cancel()
        workspaceJob = scope.launch(Dispatchers.IO) {
            try {
            active.workspaces()
                .retryWhen { cause, _ ->
                    record("workspaces retry: ${cause.message}")
                    // A retry is not a failure: the archive set may still arrive, and
                    // the drawer will keep waiting for it.
                    delay(2_000)
                    true
                }
                .catch { error ->
                    record("workspaces stopped: ${error.message}")
                    _state.update { it.copy(workspaceFailed = true) }
                }
                .collect { frame ->
                    val value = (frame as? MuxFrame.Item)?.value ?: return@collect
                    val obj = value as? kotlinx.serialization.json.JsonObject ?: return@collect
                    when (obj["type"]?.let { (it as? JsonPrimitive)?.contentOrNull }) {
                        "baseline" -> runCatching {
                            DshWire.json.decodeFromJsonElement(
                                WorkspaceBaseline.serializer(),
                                obj["value"] ?: obj,
                            )
                        }.getOrNull()?.let { baseline ->
                            record("workspaces: ${baseline.items.size} groups, ${baseline.archivedSessionIds.size} archived")
                            _state.update {
                                it.copy(
                                    workspaces = baseline.items,
                                    archived = baseline.archivedSessionIds.toSet(),
                                    archivedKnown = true,
                                    workspaceFailed = false,
                                )
                            }
                        }
                        "upsert" -> runCatching {
                            DshWire.json.decodeFromJsonElement(
                                WorkspaceIncrement.serializer(),
                                obj["value"] ?: obj,
                            )
                        }.getOrNull()?.workspace?.let { updated ->
                            _state.update { current ->
                                val next = current.workspaces.filterNot { it.workspaceId == updated.workspaceId } + updated
                                current.copy(workspaces = next.sortedBy { it.createdAt ?: "" })
                            }
                        }
                        // The Host pushes the whole archive set whenever it changes,
                        // including for a change made on another client. Without this
                        // branch the drawer kept showing a session archived on the
                        // desktop, and vice versa, until a manual refresh.
                        "archived" -> {
                            val ids = (obj["archivedSessionIds"] as? JsonArray)
                                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                            if (ids != null) {
                                record("archived set: ${ids.size}")
                                _state.update {
                                    it.copy(archived = ids.toSet(), archivedKnown = true)
                                }
                            }
                        }
                        "removed" -> {
                            val workspaceId = obj["workspaceId"]?.jsonPrimitive?.contentOrNull
                            if (workspaceId != null) {
                                _state.update { current ->
                                    current.copy(
                                        workspaces = current.workspaces.filterNot { it.workspaceId == workspaceId },
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                record("workspaces stream aborted: ${error.message}")
            }
        }
    }

    /** Archive one session on the Host; every client sees the resulting set. */
    fun archive(sessionId: String) {
        val active = client ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { active.archiveSession(sessionId) }
                .onSuccess { ids ->
                    record("archived (${ids.size} total)")
                    _state.update { it.copy(archived = ids.toSet()) }
                }
                .onFailure { record("archive failed: ${it.message}") }
        }
    }

    /** Load the model catalog once, for the picker. */
    fun loadCatalog() {
        val active = client ?: return
        if (_state.value.catalog != null) return
        scope.launch(Dispatchers.IO) {
            runCatching { active.modelCatalog() }
                .onSuccess { catalog ->
                    record("catalog: ${catalog.groups.size} providers")
                    _state.update { it.copy(catalog = catalog) }
                }
                .onFailure { record("catalog failed: ${it.message}") }
        }
    }

    /** Switch model and reasoning effort for the open conversation. */
    fun selectModel(provider: String, model: String, reasoningEffort: String?) {
        val active = client ?: return
        val conversation = _state.value.conversation ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { active.selectModel(conversation.sessionId, provider, model, reasoningEffort) }
                .onSuccess { selected ->
                    record("model: ${selected.provider}/${selected.model} ${selected.reasoningEffort ?: ""}")
                    _state.update { it.copy(selection = selected) }
                }
                .onFailure { record("selectModel failed: ${it.message}") }
        }
    }

    /** Run a slash command such as `/compact` against the open conversation. */
    fun runCommand(line: String) {
        val active = client ?: return
        val conversation = _state.value.conversation ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { active.runCommand(conversation.sessionId, line) }
                .onSuccess { record("command sent: $line") }
                .onFailure { record("command failed: ${it.message}") }
        }
    }

    /**
     * Answer a user-questions request.
     *
     * The waterfall's return value is the structured batch
     * `{answers:[{id, selected, custom?}]}`, keyed by the caller's question ids,
     * so one call answers the whole batch -- the reader works through the
     * questions one at a time, but the Host is given all of the answers at once.
     * The value is assembled by [QuestionFlow], which is where the rules live.
     */
    fun answerQuestions(interaction: PendingInteraction, value: JsonObject) {
        val active = client ?: return
        val clientId = eventClientId ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { active.answerWaterfall(clientId, interaction.eventId, value) }
                .onSuccess { record("answered ${interaction.questions.size} question(s)") }
                .onFailure { record("answer failed: ${it.message}") }
            _state.update { it.copy(pending = it.pending.filterNot { p -> p.eventId == interaction.eventId }) }
        }
    }

    /** Answer one pending interaction; the value is the waterfall's return value. */
    fun answer(interaction: PendingInteraction, value: String) {
        val active = client ?: return
        val clientId = eventClientId ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { active.answerWaterfall(clientId, interaction.eventId, JsonPrimitive(value)) }
                .onSuccess { record("answered ${interaction.kind}: $value") }
                .onFailure { record("answer failed: ${it.message}") }
            _state.update { it.copy(pending = it.pending.filterNot { p -> p.eventId == interaction.eventId }) }
        }
    }

    fun disconnect(quiet: Boolean = false) {
        followJob?.cancel()
        followJob = null
        eventsJob?.cancel()
        eventsJob = null
        workspaceJob?.cancel()
        workspaceJob = null
        controlJob?.cancel()
        controlJob = null
        eventClientId = null
        _state.update { it.copy(pending = emptyList()) }
        client?.stop()
        client = null
        _state.update {
            it.copy(connected = false, conversation = if (quiet) it.conversation else null)
        }
    }

    /**
     * Follow the Host's live control state: queues, projections and jobs.
     *
     * Separate from the events stream because it is a different kind of fact. The
     * events stream says what happened (a waterfall to answer, a session's running
     * flag); this says what a session's state *is* -- what is queued, how full the
     * window is -- and its frames are complete snapshots rather than deltas, so a
     * dropped frame is repaired by the next one.
     */
    private fun openControl(active: DshClient) {
        controlJob?.cancel()
        controlJob = scope.launch(Dispatchers.IO) {
            val stream: Flow<MuxFrame> = active.control()
                .resubscribe(delayMillis = 2_000) { cause ->
                    record("control ended: ${cause?.message ?: "stream closed"}; resubscribing")
                }
            stream
                .catch { error: Throwable -> record("control stopped: ${error.message}") }
                .collect { frame: MuxFrame ->
                    if (frame !is MuxFrame.Item) return@collect
                    applyControl(frame.value)
                }
        }
    }

    /** Fold one `session/control` frame into the per-session state it carries. */
    private fun applyControl(value: JsonElement) {
        val obj = value as? JsonObject ?: return
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "baseline" -> {
                val snapshot = obj["value"] as? JsonObject ?: return
                val metrics = mutableMapOf<String, Metrics>()
                (snapshot["projections"] as? JsonObject)?.forEach { (sessionId, projection) ->
                    val bag = (projection as? JsonObject)?.get("values") as? JsonObject
                    val parsed = runCatching {
                        DshWire.json.decodeFromJsonElement(ProjectionValues.serializer(), bag ?: JsonObject(emptyMap()))
                    }.getOrNull()
                    Metrics.from(parsed).takeUnless { it.isEmpty }?.let { metrics[sessionId] = it }
                }
                val queues = mutableMapOf<String, List<QueuedItem>>()
                (snapshot["queues"] as? JsonObject)?.forEach { (sessionId, items) ->
                    val parsed = QueueCodec.parse(items as? JsonArray)
                    if (parsed.isNotEmpty()) queues[sessionId] = parsed
                }
                _state.update { it.copy(metrics = it.metrics + metrics, queues = it.queues + queues) }
                record("control baseline: ${metrics.size} sessions with usage, ${queues.size} with a queue")
            }
            // A queue frame is that session's whole queue, so an empty array is
            // the removal -- there is no per-item delta to apply.
            "queue" -> {
                val sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
                val items = QueueCodec.parse(obj["items"] as? JsonArray)
                _state.update {
                    it.copy(queues = if (items.isEmpty()) it.queues - sessionId else it.queues + (sessionId to items))
                }
            }
            "projection" -> {
                val sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
                val key = obj["key"]?.jsonPrimitive?.contentOrNull ?: return
                val parsed = runCatching {
                    DshWire.json.decodeFromJsonElement(
                        ProjectionValues.serializer(),
                        buildJsonObject { put(key, obj["value"] ?: JsonNull) },
                    )
                }.getOrNull() ?: return
                val incoming = Metrics.from(parsed)
                if (incoming.isEmpty) return
                _state.update { current ->
                    // One key per frame, so merge into what is already known
                    // rather than replacing: usage and pressure arrive separately.
                    val existing = current.metrics[sessionId] ?: Metrics()
                    current.copy(
                        metrics = current.metrics + (sessionId to Metrics(
                            usage = incoming.usage ?: existing.usage,
                            pressure = incoming.pressure ?: existing.pressure,
                            breakdown = incoming.breakdown ?: existing.breakdown,
                            stats = incoming.stats ?: existing.stats,
                        )),
                    )
                }
            }
        }
    }

    /** Load the commands the Host offers this session, for the `/` menu. */
    private fun loadCommands(sessionId: String) {
        val active = client ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { active.listCommands(sessionId) }
                .onSuccess { list ->
                    record("commands: ${list.size}")
                    _state.update { it.copy(commands = list) }
                }
                .onFailure { record("commands/list failed: ${it.message}") }
        }
    }

    /** Queue a message for the next turn: what the composer does while busy. */
    fun enqueue(text: String) {
        val active = client ?: return
        val conversation = _state.value.conversation ?: return
        if (text.isBlank()) return
        scope.launch(Dispatchers.IO) {
            runCatching { active.prompt(conversation.sessionId, text, mode = "queue") }
                .onSuccess { record("queued") }
                .onFailure { error ->
                    _state.update { it.copy(queueError = "Queue failed: ${error.message}") }
                }
        }
    }

    /** Mutate one pending queue row; the Host's snapshot is what redraws it. */
    fun queueAction(item: QueuedItem, action: JsonObject, what: String) {
        val active = client ?: return
        val sessionId = _state.value.conversation?.sessionId ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { active.updateQueue(sessionId, item.id, action) }
                .onSuccess {
                    record("queue $what: ${item.id.take(8)}")
                    _state.update { it.copy(queueError = null, editingQueued = null) }
                }
                .onFailure { error ->
                    // The web client's copy for this is worth matching: the row may
                    // already have been claimed by the agent, which is not a bug.
                    _state.update {
                        it.copy(queueError = "$what failed: this message may have already started sending.")
                    }
                    record("queue $what failed: ${error.message}")
                }
        }
    }

    /** Begin editing a queued message in the composer. */
    fun editQueued(item: QueuedItem) {
        if (!item.editable) {
            _state.update { it.copy(queueError = "Only text messages can be edited.") }
            return
        }
        _state.update { it.copy(editingQueued = item, queueError = null) }
    }

    /** Abandon an in-progress queue edit. */
    fun cancelQueueEdit() {
        _state.update { it.copy(editingQueued = null, queueError = null) }
    }

    fun refreshSessions() {
        val active = client ?: run {
            record("refresh skipped: no client yet")
            return
        }
        scope.launch(Dispatchers.IO) {
            _state.update { it.copy(sessionsError = null) }
            // The harness may not be reachable yet on the first attempt (the app
            // starts before the network settles), so retry rather than leaving an
            // empty list that looks like "no sessions".
            var attempt = 0
            while (true) {
                val result = runCatching { active.listSessionsDetailed() }
                result.onSuccess { (sessions, bytes) ->
                    record("session/list ok: ${sessions.size} sessions, ${bytes}B")
                    // Seed usage from the summaries: the control stream is the
                    // live source, but a reader who opens the drawer before its
                    // baseline lands should not see an empty meter.
                    val seeded = sessions.mapNotNull { summary ->
                        Metrics.from(projectionsOf(summary.projections))
                            .takeUnless { it.isEmpty }
                            ?.let { summary.sessionId to it }
                    }.toMap()
                    _state.update {
                        it.copy(
                            sessions = sessions,
                            sessionsBytes = bytes,
                            sessionsError = null,
                            metrics = seeded + it.metrics,
                        )
                    }
                    return@launch
                }.onFailure { error ->
                    val message = "${error::class.simpleName}: ${error.message}"
                    record("session/list failed (attempt ${attempt + 1}): $message")
                    _state.update { it.copy(sessionsError = message) }
                }
                if (++attempt >= 10) return@launch
                delay(2_000)
            }
        }
    }

    /**
     * Append one line to the in-app log and to logcat.
     *
     * The drawer renders the log at the end of the session list, which is
     * unreachable once a harness has dozens of sessions; logcat is what makes
     * these events observable while the UI is still being built.
     */
    private fun record(line: String) {
        Log.i(TAG, line)
        _state.update { it.copy(log = (listOf(line) + it.log).take(80)) }
    }

    /** Mark a session as looked at, which is what clears its unread state. */
    fun markRead(sessionId: String) {
        _state.update { current ->
            if (sessionId !in current.unread) {
                current
            } else {
                val next = current.unread - sessionId
                viewStore?.saveUnread(next)
                current.copy(unread = next)
            }
        }
    }

    fun openSession(session: SessionSummary) {
        markRead(session.sessionId)
        // The summary already carries the session's model selection inside its
        // projections, so the picker opens showing the truth rather than waiting
        // for a catalog round trip.
        val selection = projectionsOf(session.projections)
            ?.modelSelection
            ?.let { it.next ?: it.lastUsed }
        _state.update {
            it.copy(
                conversation = Conversation(
                    sessionId = session.sessionId,
                    title = session.title,
                    running = session.running,
                    workspaceRoot = session.cwd,
                ),
                // Turn numbers restart per session, so a stale map would label
                // this session's turn 2 with another session's tokens.
                pendingTurnUsage = emptyMap(),
                countedUsageSeqs = emptySet(),
                selection = selection,
            )
        }
        openFollow(session.sessionId, session.title)
        loadCatalog()
        loadCommands(session.sessionId)
    }

    /**
     * Read a deliverable back for the preview sheet.
     *
     * The read is host-side over `workspaceFiles/read` because that is the only
     * path that can see a file the harness wrote outside the app sandbox. A
     * relative path is resolved against the session's workspace root, which is
     * what the Host does with it.
     */
    fun previewFile(path: String) {
        val active = client
        val session = _state.value.conversation
        val root = session?.workspaceRoot?.trimEnd('/')
        val resolved = if (path.startsWith("/") || root == null) path else "$root/$path"
        if (active == null || session == null) {
            _state.update { it.copy(preview = FilePreview.Failed(resolved, "Not connected.")) }
            return
        }
        _state.update { it.copy(previewLoading = resolved) }
        scope.launch(Dispatchers.IO) {
            val preview = readPreview(active, session.sessionId, resolved)
            _state.update { it.copy(preview = preview, previewLoading = null) }
        }
    }

    /**
     * Read a deliverable, text first and bytes second.
     *
     * The text endpoint is the cheap path and refuses anything non-UTF-8; when it
     * refuses, the bytes endpoint answers the question the reader actually asked,
     * which for a figure is "show me the picture".
     */
    private suspend fun readPreview(active: DshClient, sessionId: String, path: String): FilePreview {
        val asText = runCatching { active.readWorkspaceFile(sessionId, path) }
        asText.getOrNull()?.let { return FilePreview.Text(path, it) }
        val textFailure = asText.exceptionOrNull()?.message.orEmpty()
        if (!textFailure.contains("not-text")) {
            return FilePreview.Failed(path, explainReadFailure(textFailure))
        }
        val asBytes = runCatching { active.readWorkspaceBytes(sessionId, path) }
        val bytes = asBytes.getOrNull()
            ?: return FilePreview.Failed(path, explainReadFailure(asBytes.exceptionOrNull()?.message.orEmpty()))
        return FilePreview.Bitmap(path, bytes, imageMimeOf(bytes), bytes.size.toLong())
    }

    /**
     * The image type of a byte array, or null when it is not one this can draw.
     *
     * Magic bytes rather than the extension: a deliverable named `.png` that is
     * really a PDF would otherwise be handed to the bitmap decoder, fail, and
     * report a decode error instead of the truth.
     */
    private fun imageMimeOf(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte() -> "image/jpeg"
        bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() -> "image/gif"
        bytes.size >= 12 && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte() -> "image/webp"
        else -> null
    }

    /**
     * Turn a Host read refusal into something a reader can act on.
     *
     * The wire error is precise but written for a developer: `workspace-file/
     * not-text` says nothing about what to do, and this sheet is often the first
     * thing that tells someone the deliverable is a figure rather than a
     * document.
     */
    private fun explainReadFailure(message: String): String = when {
        message.contains("not-text") ->
            "This file is neither text nor an image this build can draw, so there is nothing to show here."
        message.contains("not-found") ->
            "The Host cannot find this path. It may have been moved or deleted since the turn that produced it."
        else -> message
    }

    fun dismissPreview() {
        _state.update { it.copy(preview = null, previewLoading = null) }
    }

    /**
     * Start a new session, optionally inside one Workspace.
     *
     * The Host answers with the new id, so the app never guesses it: it opens
     * immediately and the follow stream supplies the (empty) transcript. The list
     * is refreshed in the background because the new session is `blank` until its
     * first turn, and a blank session is exactly the one the drawer should show
     * as current.
     */
    fun createSession(workspaceId: String? = null, onCreated: (String) -> Unit = {}) {
        val active = client ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { active.createSession(workspaceId) }
                .onSuccess { sessionId ->
                    record("created session ${sessionId.take(20)}${workspaceId?.let { " in $it" } ?: ""}")
                    _state.update {
                        it.copy(
                            conversation = Conversation(
                                sessionId = sessionId,
                                title = "",
                                workspaceRoot = _state.value.workspaces
                                    .firstOrNull { view -> view.workspaceId == workspaceId }
                                    ?.path,
                            ),
                        )
                    }
                    onCreated(sessionId)
                    refreshSessions()
                    // A brand-new session has no selection of its own, so the
                    // picker needs the catalog to name the default it is running.
                    loadCatalog()
                    openFollow(sessionId, "")
                }
                .onFailure { record("create session failed: ${it.message}") }
        }
    }

    fun closeSession() {
        followJob?.cancel()
        followJob = null
        _state.update { it.copy(conversation = null) }
    }

    private fun openFollow(sessionId: String, title: String) {
        val active = client ?: return
        followJob?.cancel()
        followJob = scope.launch(Dispatchers.IO) {
            try {
            active.follow(sessionId)
                .resubscribe(delayMillis = 2_000) { cause ->
                    _state.update { current ->
                        val live = current.conversation ?: return@update current
                        current.copy(conversation = live.copy(error = cause?.message))
                    }
                }
                .catch { error ->
                    _state.update { current ->
                        val live = current.conversation ?: return@update current
                        current.copy(conversation = live.copy(error = error.message))
                    }
                }
                .collect { frame -> reduce(sessionId, title, frame) }
            } catch (error: Throwable) {
                _state.update { current ->
                    val live = current.conversation ?: return@update current
                    current.copy(conversation = live.copy(error = error.message))
                }
            }
        }
    }

    private fun reduce(sessionId: String, title: String, frame: MuxFrame) {
        _state.update { current ->
            val conversation = current.conversation
            if (conversation == null || conversation.sessionId != sessionId) return@update current
            when (frame) {
                is MuxFrame.Item -> {
                    val decoded = FollowCodec.decode(frame.value)
                    // Decoded once and handed to both: a reconnect replays the
                    // same assistant messages, and accumulating them twice would
                    // inflate every turn's cost.
                    val (usage, counted) = accumulateTurnUsage(
                        decoded,
                        current.pendingTurnUsage,
                        current.countedUsageSeqs,
                    )
                    current.copy(
                        conversation = merge(conversation, decoded, usage),
                        pendingTurnUsage = usage,
                        countedUsageSeqs = counted,
                    )
                }
                is MuxFrame.End -> current.copy(conversation = conversation.copy(running = false))
                is MuxFrame.Failure -> current.copy(
                    conversation = conversation.copy(error = "${frame.code}: ${frame.message}"),
                )
            }
        }
    }

    private fun merge(
        conversation: Conversation,
        frame: FollowFrame,
        pendingTurnUsage: Map<Int, TokenUsage> = emptyMap(),
    ): Conversation = when (frame) {
        is FollowFrame.Snapshot -> {
            // The snapshot is the newest window, not a delta: rebuild by seq so a
            // reconnect cannot duplicate or reorder what is already on screen.
            val merged = pairToolResults(
                (conversation.items + frame.records.mapNotNull { toItem(it, conversation.workspaceRoot) })
                    .associateBy { it.key }
                    .values
                    .sortedBy(::seqOf),
            )
            conversation.copy(
                title = conversation.title.ifEmpty { conversation.sessionId.takeLast(8) },
                items = merged,
                lastSeq = maxOf(conversation.lastSeq, frame.records.maxOfOrNull { it.seq } ?: 0L),
                throughSeq = frame.cursor,
                hasMore = frame.hasMore,
                error = null,
                liveText = "",
            )
        }

        is FollowFrame.Event -> {
            val event = frame.event
            val turn = (event.data as? JsonObject)?.get("turn")?.jsonPrimitive?.intOrNull
            // The turn's usage accumulated as its assistant messages arrived;
            // `turn/end` is where it becomes a row of its own. The reducer knows
            // both facts, which a renderer reading keys at draw time did not.
            val closed = if (event.type == "turn/end" && turn != null) {
                pendingTurnUsage[turn]?.let { used ->
                    TranscriptItem.Usage(key = "usage:$turn", usage = used, turn = turn)
                }
            } else {
                null
            }
            conversation.copy(
            items = pairToolResults(
                (conversation.items + listOfNotNull(toItem(event, conversation.workspaceRoot)) + listOfNotNull(closed))
                    .distinctBy { it.key },
            ),
            lastSeq = maxOf(conversation.lastSeq, event.seq),
            // Deliberately not derived from the event type. The Host reports
            // whether a turn is running on `api-session/status`, and an inferred
            // flag disagrees with it whenever a turn ends with a row that still
            // says "running" -- which is what left the composer showing Stop after
            // the turn was over. `api-session/status` is authoritative; this keeps
            // whatever it last said.
            running = conversation.running,
            )
        }

        is FollowFrame.AssistantChunk -> {
            val delta = frame.text ?: return conversation
            conversation.copy(liveText = conversation.liveText + delta)
        }

        FollowFrame.Unknown -> conversation
    }

    private fun seqOf(item: TranscriptItem): Long =
        item.key.removePrefix("seq-").toLongOrNull() ?: Long.MAX_VALUE

    /**
     * Map one durable event to a transcript row, or to null when the event is
     * machinery the reader does not need ([docs/event-coverage.md] has the full
     * table and the reason for each choice).
     */
    private fun toItem(event: SessionEvent, workspaceRoot: String? = null): TranscriptItem? {
        // `turn:<turn>:<seq>`: the turn is part of the key because the usage row
        // is placed under the last row of its turn, and the renderer draws a
        // stream of rows with no turn model of its own. A key of `seq-<n>` left
        // that lookup with nothing to read.
        val turn = (event.data as? JsonObject)?.get("turn")?.jsonPrimitive?.intOrNull
        val key = if (turn == null) "seq-${event.seq}" else "turn:$turn:${event.seq}"
        val text = event.text
        return when (event.type) {
            "user/message" -> when {
                // A `user/message` whose source is not the user is the harness or
                // another agent talking; only `kind == "user"` is a person typing.
                // The distinction is the source kind and not the plugin field,
                // because a relayed subagent message carries no plugin name.
                EventPayload.isHiddenSource(event) -> null
                EventPayload.isNotice(event) -> {
                    val kind = EventPayload.sourceKind(event)
                    val plugin = EventPayload.noticePlugin(event)
                    TranscriptItem.Notice(
                        key = key,
                        label = noticeLabel(plugin, kind),
                        plugin = plugin,
                        sender = EventPayload.senderSessionId(event),
                        body = EventPayload.noticeSummary(event) ?: text,
                    )
                }
                text != null -> TranscriptItem.User(key, text)
                else -> null
            }
            // An assistant message may carry only tool calls and no prose, which
            // is a normal step rather than a renderable reply.
            // A message with no text block carries only reasoning or tool calls,
            // which are steps rather than a reply. Rendering nothing is correct;
            // falling through printed the literal event name.
            "assistant/message" -> text?.let { TranscriptItem.Assistant(key, it, streaming = false) }
            // A turn that failed says so; a clean one is implied by the next
            // message and a row per turn would just be noise.
            "turn/end" -> EventPayload.turnOutcome(event)?.let { TranscriptItem.Note(key, it) }
            "todo/write" -> EventPayload.todoList(event)?.let { TranscriptItem.Todo(key, it) }
            "deliverables/presented" -> EventPayload.deliveredFiles(event)?.let {
                TranscriptItem.Deliverables(key, it, workspaceRoot)
            }
            // Model switches and session settings are facts about the session
            // rather than turns: one compact line each, no card.
            "model/selection" -> EventPayload.modelChoice(event)?.let { choice ->
                val effort = choice.effort?.let { " · $it" } ?: ""
                TranscriptItem.Note(key, "model: ${choice.provider}/${choice.model}$effort")
            }
            "permission/preset", "sandbox/mode", "approval/policy",
            "compaction/start", "compaction/end",
            -> EventPayload.settingChange(event)?.let { (name, value) ->
                TranscriptItem.Note(key, "$name: $value")
            }
            // Rendering every step boundary and inbox splice buries the
            // conversation: one sampled turn produced hundreds of such rows
            // against 41 assistant messages.
            "turn/start", "step/start", "step/end", "agent/inbox/spliced",
            "request/header", "request/context",
            // Raw stream chunks: the message they assemble into is rendered, and
            // the seed marker carries nothing.
            "assistant/attempt", "session/end-seed", "session/title",
            "session/title-llm-request", "compaction/summary", "compaction/prune",
            -> null
            "tool/call" -> EventPayload.toolCallOf(event)?.let { call ->
                TranscriptItem.ToolCall(
                    key = key,
                    callId = call.callId,
                    name = call.name,
                    arguments = call.arguments,
                    rawArguments = call.rawArguments,
                    result = null,
                    status = TranscriptItem.ToolCall.Status.RUNNING,
                )
            } ?: TranscriptItem.Activity(key, event.label, event.detail)
            // tool/result must produce a row even though it is never rendered:
            // pairToolResults needs the row to fold into its call, and hiding it
            // here left every tool stuck at "running".
            "tool/result" -> {
                val result = EventPayload.toolResultOf(event)
                TranscriptItem.ToolResultRow(key, result?.toolCallId, result?.text, result?.isError ?: false)
            }
            else -> if (text != null) TranscriptItem.Activity(key, event.label, text.take(400))
            else TranscriptItem.Activity(key, event.label, event.detail)
        }
    }

    /**
     * Fold every `tool/result` row into the `tool/call` row it answers.
     *
     * Pairing is done over the whole window rather than as events arrive,
     * because paging and reconnects can deliver the halves in either order and
     * the reducer itself must stay free of side effects. A result with no
     * matching call in this window keeps its own row instead of disappearing.
     */
    private fun pairToolResults(items: List<TranscriptItem>): List<TranscriptItem> {
        val results = items.filterIsInstance<TranscriptItem.ToolResultRow>()
        if (results.isEmpty()) return items
        val byCallId = results.mapNotNull { row -> row.toolCallId?.let { it to row } }.toMap()
        if (byCallId.isEmpty()) return items
        // Calls consume their result in order, so a repeated callId across turns
        // still pairs with the nearest unconsumed call.
        val consumed = mutableSetOf<String>()
        return items.mapNotNull { item ->
            when {
                item is TranscriptItem.ToolCall -> {
                    val row = byCallId[item.callIdOrNull()]?.takeIf { it.key !in consumed }
                    if (row == null) {
                        item
                    } else {
                        consumed += row.key
                        item.copy(
                            result = row.text,
                            status = if (row.failed) TranscriptItem.ToolCall.Status.FAILED
                            else TranscriptItem.ToolCall.Status.DONE,
                        )
                    }
                }
                // Every result row is dropped: a paired one has been folded into
                // its call, and an unpaired one (paging landed mid-pair) would
                // otherwise surface as a bare result line.
                item is TranscriptItem.ToolResultRow -> null
                else -> item
            }
        }
    }

    /** Load one older page and prepend it, keeping seq order and the paging cursor. */
    fun loadOlder() {
        val active = client ?: return
        val conversation = _state.value.conversation ?: return
        if (!conversation.hasMore || conversation.items.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val oldest = conversation.items.minOfOrNull(::seqOf) ?: return@launch
            if (oldest == Long.MAX_VALUE) return@launch
            runCatching {
                active.pageOlder(
                    sessionId = conversation.sessionId,
                    throughSeq = conversation.throughSeq,
                    beforeSeq = oldest,
                )
            }.onSuccess { events ->
                _state.update { current ->
                    val live = current.conversation ?: return@update current
                    val merged = pairToolResults(
                        (live.items + events.mapNotNull { toItem(it, live.workspaceRoot) })
                            .associateBy { it.key }
                            .values
                            .sortedBy(::seqOf),
                    )
                    // throughSeq stays the newest bound; hasMore now describes
                    // whether another newer-than-`oldest` page exists behind us.
                    current.copy(conversation = live.copy(items = merged, hasMore = events.isNotEmpty()))
                }
            }
        }
    }

    fun send(text: String) {
        val active = client ?: return
        val conversation = _state.value.conversation ?: return
        if (text.isBlank()) return
        scope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true) }
            runCatching { active.prompt(conversation.sessionId, text) }
                .onFailure { error ->
                    _state.update { current ->
                        val live = current.conversation ?: return@update current
                        current.copy(conversation = live.copy(error = "prompt failed: ${error.message}"))
                    }
                }
            _state.update { it.copy(busy = false) }
        }
    }

    fun cancel() {
        val active = client ?: return
        val conversation = _state.value.conversation ?: return
        scope.launch(Dispatchers.IO) { runCatching { active.cancel(conversation.sessionId) } }
    }
}
