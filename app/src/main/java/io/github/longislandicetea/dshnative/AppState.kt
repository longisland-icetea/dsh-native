package io.github.longislandicetea.dshnative

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
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
data class PendingInteraction(
    val sessionId: String,
    val eventId: String,
    val kind: String,
    val toolName: String?,
    val callId: String?,
    val reason: String?,
    /** Answer values this presentation offers, in display order. */
    val choices: List<Choice>,
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
                // Questions and plan reviews are answered with free text or a
                // selection; wire them once their request shapes are confirmed
                // rather than guessing a payload the Host would reject.
                else -> null
            }
        }
    }
}

/** One row in the transcript. */
sealed interface TranscriptItem {
    val key: String

    data class User(override val key: String, val text: String) : TranscriptItem
    data class Assistant(override val key: String, val text: String, val streaming: Boolean) : TranscriptItem
    data class Activity(override val key: String, val label: String, val detail: String?) : TranscriptItem
    data class Note(override val key: String, val text: String) : TranscriptItem
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
) {
    val newestAt: Long get() = sessions.maxOfOrNull { it.updatedAt } ?: 0L
    companion object {
        fun labelOf(cwd: String?): String {
            val path = cwd?.trimEnd('/') ?: return ""
            if (path.isEmpty()) return ""
            return path.substringAfterLast('/').ifEmpty { path }
        }

        /** Build groups from a flat list; [archived] and [expanded] are per-device state. */
        fun derive(
            sessions: List<SessionSummary>,
            currentId: String?,
            archived: Set<String>,
            collapsed: Set<String>,
            showArchived: Boolean,
        ): List<SessionGroup> {
            val visible = sessions.filter { session ->
                session.origin != "subagent" &&
                    (!archived.contains(session.sessionId) || showArchived) &&
                    (!session.blank || session.sessionId == currentId)
            }
            return visible
                .groupBy { it.cwd ?: "" }
                .map { (cwd, members) ->
                    val ordered = members.sortedWith(
                        compareByDescending<SessionSummary> { it.updatedAt }.thenBy { it.sessionId },
                    )
                    SessionGroup(
                        key = cwd.ifEmpty { "·none" },
                        label = labelOf(cwd).ifEmpty { "No workspace" },
                        path = cwd.ifEmpty { null },
                        sessions = ordered,
                        expanded = !collapsed.contains(cwd.ifEmpty { "·none" }),
                    )
                }
                .sortedByDescending { it.newestAt }
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
    /** Session ids this device hides; per-device, like the desktop's own view state. */
    val archived: Set<String> = emptySet(),
    /** Group keys (cwd) the user collapsed. */
    val collapsed: Set<String> = emptySet(),
    val showArchived: Boolean = false,
) {
    /** Sessions grouped and ordered for the drawer. */
    val groups: List<SessionGroup>
        get() = SessionGroup.derive(sessions, conversation?.sessionId, archived, collapsed, showArchived)
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

    fun archived(): Set<String> = prefs.getStringSet("archived", emptySet()) ?: emptySet()
    fun collapsed(): Set<String> = prefs.getStringSet("collapsed", emptySet()) ?: emptySet()
    fun showArchived(): Boolean = prefs.getBoolean("showArchived", false)

    fun saveArchived(value: Set<String>) = prefs.edit().putStringSet("archived", value).apply()
    fun saveCollapsed(value: Set<String>) = prefs.edit().putStringSet("collapsed", value).apply()
    fun saveShowArchived(value: Boolean) = prefs.edit().putBoolean("showArchived", value).apply()
}

class AppStateHolder(private val scope: CoroutineScope, context: android.content.Context? = null) {
    private val viewStore = context?.let(::SessionViewStore)
    private val _state = MutableStateFlow(
        AppState(
            archived = viewStore?.archived() ?: emptySet(),
            collapsed = viewStore?.collapsed() ?: emptySet(),
            showArchived = viewStore?.showArchived() ?: false,
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

    fun setArchived(sessionId: String, archived: Boolean) {
        _state.update { current ->
            val next = if (archived) current.archived + sessionId else current.archived - sessionId
            viewStore?.saveArchived(next)
            current.copy(archived = next)
        }
    }

    fun setShowArchived(value: Boolean) {
        viewStore?.saveShowArchived(value)
        _state.update { it.copy(showArchived = value) }
    }

    private var client: DshClient? = null
    private var followJob: Job? = null
    private var eventsJob: Job? = null
    /** Bound by the `$events` ready frame; every answer must name it. */
    private var eventClientId: String? = null

    fun connect(endpoint: DshEndpoint) {
        disconnect(quiet = true)
        val created = DshClient(endpoint, scope)
        client = created
        _state.update { it.copy(endpoint = endpoint) }
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
        refreshSessions()
    }

    /** Subscribe to forwarded Host events so waterfalls can be answered. */
    private fun openEvents(active: DshClient) {
        eventsJob?.cancel()
        eventsJob = scope.launch(Dispatchers.IO) {
            // A mux that is not up yet, or that just dropped, must not kill the
            // process: `callbackFlow` closing with a cause while nothing collects
            // surfaces as an unhandled exception. Retry until the socket is up.
            active.events()
                .retryWhen { cause, _ ->
                    record("events retry: ${cause.message}")
                    delay(2_000)
                    true
                }
                .catch { record("events stopped: ${it.message}") }
                .collect { frame ->
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
                                    record("declining unsupported waterfall ${host.event}")
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
                            else -> Unit
                        }
                    }
                    is MuxFrame.Failure -> record("events stream failed: ${frame.code}")
                    else -> Unit
                }
            }
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
        eventClientId = null
        _state.update { it.copy(pending = emptyList()) }
        client?.stop()
        client = null
        _state.update {
            it.copy(connected = false, conversation = if (quiet) it.conversation else null)
        }
    }

    fun refreshSessions() {
        val active = client ?: run {
            record("refresh skipped: no client yet")
            return
        }
        scope.launch(Dispatchers.IO) {
            _state.update { it.copy(sessionsError = null) }
            runCatching { active.listSessionsDetailed() }
                .onSuccess { (sessions, bytes) ->
                    record("session/list ok: ${sessions.size} sessions, ${bytes}B")
                    _state.update { it.copy(sessions = sessions, sessionsBytes = bytes, sessionsError = null) }
                }
                .onFailure { error ->
                    val message = "${error::class.simpleName}: ${error.message}"
                    record("session/list FAILED: $message")
                    _state.update { it.copy(sessionsError = message) }
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

    fun openSession(session: SessionSummary) {
        _state.update {
            it.copy(
                conversation = Conversation(
                    sessionId = session.sessionId,
                    title = session.title,
                    running = session.running,
                ),
            )
        }
        openFollow(session.sessionId, session.title)
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
            active.follow(sessionId)
                .retryWhen { cause, _ ->
                    _state.update { current ->
                        val live = current.conversation ?: return@update current
                        current.copy(conversation = live.copy(error = cause.message))
                    }
                    delay(2_000)
                    true
                }
                .catch { error ->
                    _state.update { current ->
                        val live = current.conversation ?: return@update current
                        current.copy(conversation = live.copy(error = error.message))
                    }
                }
                .collect { frame -> reduce(sessionId, title, frame) }
        }
    }

    private fun reduce(sessionId: String, title: String, frame: MuxFrame) {
        _state.update { current ->
            val conversation = current.conversation
            if (conversation == null || conversation.sessionId != sessionId) return@update current
            when (frame) {
                is MuxFrame.Item -> current.copy(
                    conversation = merge(conversation, FollowCodec.decode(frame.value)),
                )
                is MuxFrame.End -> current.copy(conversation = conversation.copy(running = false))
                is MuxFrame.Failure -> current.copy(
                    conversation = conversation.copy(error = "${frame.code}: ${frame.message}"),
                )
            }
        }
    }

    private fun merge(conversation: Conversation, frame: FollowFrame): Conversation = when (frame) {
        is FollowFrame.Snapshot -> {
            // The snapshot is the newest window, not a delta: rebuild by seq so a
            // reconnect cannot duplicate or reorder what is already on screen.
            val merged = (conversation.items + frame.records.map(::toItem))
                .associateBy { it.key }
                .values
                .sortedBy(::seqOf)
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

        is FollowFrame.Event -> conversation.copy(
            items = (conversation.items + toItem(frame.event)).distinctBy { it.key },
            lastSeq = maxOf(conversation.lastSeq, frame.event.seq),
            running = when (frame.event.type) {
                "turn/start" -> true
                "turn/end" -> false
                else -> conversation.running
            },
        )

        is FollowFrame.AssistantChunk -> {
            val delta = frame.text ?: return conversation
            conversation.copy(liveText = conversation.liveText + delta)
        }

        FollowFrame.Unknown -> conversation
    }

    private fun seqOf(item: TranscriptItem): Long =
        item.key.removePrefix("seq-").toLongOrNull() ?: Long.MAX_VALUE

    private fun toItem(event: SessionEvent): TranscriptItem {
        val key = "seq-${event.seq}"
        val text = event.text
        return when {
            event.type == "user/message" && text != null -> TranscriptItem.User(key, text)
            // An assistant message may carry only tool calls and no prose, which
            // is a normal step rather than a renderable reply.
            event.type == "assistant/message" && text != null -> TranscriptItem.Assistant(key, text, streaming = false)
            event.type == "turn/end" -> TranscriptItem.Note(key, "turn finished")
            event.type == "tool/result" -> TranscriptItem.Activity(key, event.label, event.detail)
            text != null -> TranscriptItem.Activity(key, event.label, text.take(400))
            else -> TranscriptItem.Activity(key, event.label, event.detail)
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
                    val merged = (live.items + events.map(::toItem))
                        .associateBy { it.key }
                        .values
                        .sortedBy(::seqOf)
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
