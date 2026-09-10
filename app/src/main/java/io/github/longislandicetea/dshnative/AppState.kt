package io.github.longislandicetea.dshnative

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
)

/**
 * Owns the client, the session list, and one live conversation.
 *
 * A reconnect deliberately does *not* try to resume silently: the follow stream
 * is reopened and its opening snapshot is merged by seq, which is what makes the
 * client cheap (a 50 KiB snapshot) rather than a full repaint of history.
 */
class AppStateHolder(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var client: DshClient? = null
    private var followJob: Job? = null

    fun connect(endpoint: DshEndpoint) {
        disconnect(quiet = true)
        val created = DshClient(endpoint, scope)
        client = created
        _state.update { it.copy(endpoint = endpoint) }
        created.start()

        scope.launch {
            created.connected.collect { alive ->
                _state.update { it.copy(connected = alive) }
                // Bounce the conversation stream through every reconnect so the
                // snapshot lands again instead of leaving a silent gap.
                val conversation = _state.value.conversation
                if (alive && conversation != null) openFollow(conversation.sessionId, conversation.title)
            }
        }
        scope.launch {
            created.log.collect { line -> record(line) }
        }
        refreshSessions()
    }

    fun disconnect(quiet: Boolean = false) {
        followJob?.cancel()
        followJob = null
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
        scope.launch {
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

    /** Append one line to the in-app log the drawer shows. */
    private fun record(line: String) {
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
        followJob = scope.launch {
            active.follow(sessionId)
                .catch { error ->
                    _state.update { current ->
                        val conversation = current.conversation ?: return@update current
                        current.copy(conversation = conversation.copy(error = error.message))
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
        scope.launch {
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
        scope.launch {
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
        scope.launch { runCatching { active.cancel(conversation.sessionId) } }
    }
}
