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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
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

/** One row in the transcript. */
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
        val failed: Boolean,
    ) : TranscriptItem {
        fun callIdOrNull(): String? = callId
    }

    /** A result waiting to be folded into its call. */
    data class ToolResultRow(
        override val key: String,
        val toolCallId: String?,
        val text: String?,
        val failed: Boolean,
    ) : TranscriptItem
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
                    (!archived.contains(session.sessionId) || showArchived) &&
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
    /** Routable models, loaded when the picker first opens. */
    val catalog: ModelCatalog? = null,
    /** Provider/model/effort currently in force for the open conversation. */
    val selection: ModelSelection? = null,
) {
    /** Sessions grouped and ordered for the drawer. */
    val groups: List<SessionGroup>
        get() = SessionGroup.fromWorkspaces(
            sessions, workspaces, conversation?.sessionId, archived, collapsed, showArchived,
        )
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

    fun saveCollapsed(value: Set<String>) = prefs.edit().putStringSet("collapsed", value).apply()
    fun saveShowArchived(value: Boolean) = prefs.edit().putBoolean("showArchived", value).apply()
}

class AppStateHolder(private val scope: CoroutineScope, context: android.content.Context? = null) {
    private val viewStore = context?.let(::SessionViewStore)
    private val _state = MutableStateFlow(
        AppState(
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

    fun setShowArchived(value: Boolean) {
        viewStore?.saveShowArchived(value)
        _state.update { it.copy(showArchived = value) }
    }

    private var client: DshClient? = null
    private var followJob: Job? = null
    private var eventsJob: Job? = null
    private var workspaceJob: Job? = null
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
        openWorkspaces(created)
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
                    delay(2_000)
                    true
                }
                .catch { record("workspaces stopped: ${it.message}") }
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
     * so one call answers the whole batch rather than one question at a time.
     */
    fun answerQuestions(interaction: PendingInteraction, selected: Map<String, List<String>>, custom: Map<String, String>) {
        val active = client ?: return
        val clientId = eventClientId ?: return
        val batch = QuestionAnswer(
            answers = interaction.questions.map { question ->
                QuestionAnswerItem(
                    id = question.id,
                    selected = selected[question.id] ?: emptyList(),
                    custom = custom[question.id],
                )
            },
        )
        scope.launch(Dispatchers.IO) {
            runCatching {
                active.answerWaterfall(
                    clientId,
                    interaction.eventId,
                    DshWire.json.encodeToJsonElement(QuestionAnswer.serializer(), batch),
                )
            }
                .onSuccess { record("answered question batch (${batch.answers.size})") }
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
            // The harness may not be reachable yet on the first attempt (the app
            // starts before the network settles), so retry rather than leaving an
            // empty list that looks like "no sessions".
            var attempt = 0
            while (true) {
                val result = runCatching { active.listSessionsDetailed() }
                result.onSuccess { (sessions, bytes) ->
                    record("session/list ok: ${sessions.size} sessions, ${bytes}B")
                    _state.update { it.copy(sessions = sessions, sessionsBytes = bytes, sessionsError = null) }
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

    fun openSession(session: SessionSummary) {
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
                ),
                selection = selection,
            )
        }
        openFollow(session.sessionId, session.title)
        loadCatalog()
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
            val merged = pairToolResults(
                (conversation.items + frame.records.mapNotNull(::toItem))
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

        is FollowFrame.Event -> conversation.copy(
            items = pairToolResults(
                (conversation.items + listOfNotNull(toItem(frame.event))).distinctBy { it.key },
            ),
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

    private fun toItem(event: SessionEvent): TranscriptItem? {
        val key = "seq-${event.seq}"
        val text = event.text
        return when (event.type) {
            "user/message" -> when {
                // A plugin-sourced user message is the harness talking to
                // itself; only a human prompt is rendered as one.
                EventPayload.isNotice(event) -> TranscriptItem.Note(key, text?.take(200) ?: "notice")
                text != null -> TranscriptItem.User(key, text)
                else -> null
            }
            // An assistant message may carry only tool calls and no prose, which
            // is a normal step rather than a renderable reply.
            "assistant/message" -> text?.let { TranscriptItem.Assistant(key, it, streaming = false) }
                ?: TranscriptItem.Activity(key, event.label, event.detail)
            "turn/end" -> TranscriptItem.Note(key, "turn finished")
            // Rendering every step boundary, inbox splice and tool result buries
            // the conversation: one sampled turn produced 315 such rows against
            // 41 assistant messages. A tool result is folded into its call.
            "step/start", "step/end", "agent/inbox/spliced",
            "tool/result", "request/header", "request/context",
            -> null
            "tool/call" -> EventPayload.toolCallOf(event)?.let { call ->
                TranscriptItem.ToolCall(
                    key = key,
                    callId = call.callId,
                    name = call.name,
                    arguments = call.arguments,
                    rawArguments = call.rawArguments,
                    result = null,
                    failed = false,
                )
            } ?: TranscriptItem.Activity(key, event.label, event.detail)
            // A result is folded into its call by pairToolResults once the whole
            // window is known; standalone here so nothing is ever dropped.
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
                    if (row != null) consumed += row.key
                    item.copy(result = row?.text ?: item.result, failed = row?.failed ?: item.failed)
                }
                item is TranscriptItem.ToolResultRow && item.key in consumed -> null
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
                        (live.items + events.mapNotNull(::toItem))
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
