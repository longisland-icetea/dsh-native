package io.github.longislandicetea.dshnative

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.launch

private val INK = Color(0xFF16181D)
private val PANEL = Color(0xFF1E2128)
private val BUBBLE_USER = Color(0xFF243044)
private val BUBBLE_ASSISTANT = Color(0xFF1C1F26)
private val CODE_BG = Color(0xFF12141A)
private val ACCENT = Color(0xFF6E9BF7)
private val MUTED = Color(0xFF8A93A5)
private val WARN = Color(0xFFE5A06B)

private val scheme = darkColorScheme(
    primary = ACCENT,
    background = INK,
    surface = PANEL,
    onBackground = Color(0xFFDDE2EC),
    onSurface = Color(0xFFDDE2EC),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val holder = AppStateHolder(lifecycleScope, applicationContext)
        setContent {
            MaterialTheme(colorScheme = scheme) {
                DshApp(holder, applicationContext)
            }
        }
    }
}

/** Last-used endpoint, so a restart does not ask again. */
private object EndpointStore {
    private const val FILE = "dsh_native"
    private const val KEY = "endpoint"

    fun load(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)

    fun save(context: Context, value: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, value).apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DshApp(holder: AppStateHolder, context: Context) {
    val state by holder.state.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    val saved = remember { EndpointStore.load(context) }
    // A delegated property cannot be smart-cast, so read it once per recomposition.
    val endpoint = state.endpoint

    LaunchedEffect(Unit) {
        if (saved != null) DshEndpoint.parse(saved)?.let(holder::connect)
        else showSettings = true
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(Modifier.width(300.dp)) {
                SessionDrawer(
                    state = state,
                    onPick = { session ->
                        holder.openSession(session)
                        scope.launch { drawerState.close() }
                    },
                    onRefresh = holder::refreshSessions,
                    onSettings = { showSettings = true },
                    onToggleGroup = holder::toggleGroup,
                    onArchive = holder::archive,
                    onShowArchived = holder::setShowArchived,
                )
            }
        },
    ) {
        Scaffold(
            containerColor = INK,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = PANEL),
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Sessions")
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = state.conversation?.title?.ifEmpty { "DSH Native" } ?: "DSH Native",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            // Pending interactions are global: an agent waiting in
                            // another session must not be invisible from here.
                            if (state.pending.isNotEmpty()) {
                                Text(
                                    text = "${state.pending.size} waiting for you",
                                    color = WARN,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text(
                                text = endpoint?.let { point ->
                                    if (state.connected) "connected · ${point.host}"
                                    else "reconnecting · ${point.host}"
                                } ?: "not configured",
                                fontSize = 11.sp,
                                color = if (state.connected) MUTED else WARN,
                            )
                        }
                    },
                    actions = {
                        if (state.pending.isNotEmpty()) {
                            IconButton(onClick = {
                                // Jump to the session that is waiting, if it is not
                                // the one already open.
                                val waiting = state.pending.first()
                                if (state.conversation?.sessionId != waiting.sessionId) {
                                    state.sessions.firstOrNull { it.sessionId == waiting.sessionId }
                                        ?.let(holder::openSession)
                                }
                            }) {
                                Icon(Icons.Filled.Notifications, contentDescription = "Waiting", tint = WARN)
                            }
                        }
                        if (state.pending.isNotEmpty()) {
                            IconButton(onClick = {
                                // Jump to the session that is waiting when it is not
                                // the one already open.
                                val waiting = state.pending.first()
                                if (state.conversation?.sessionId != waiting.sessionId) {
                                    state.sessions.firstOrNull { it.sessionId == waiting.sessionId }
                                        ?.let(holder::openSession)
                                }
                            }) {
                                Icon(Icons.Filled.Notifications, contentDescription = "Waiting", tint = WARN)
                            }
                        }
                        if (state.conversation?.running == true) {
                            IconButton(onClick = holder::cancel) {
                                Icon(Icons.Filled.Stop, contentDescription = "Cancel turn")
                            }
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Connection")
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                val conversation = state.conversation
                if (conversation == null) {
                    EmptyState(
                        connected = state.connected,
                        sessionCount = state.sessions.size,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                    )
                } else {
                    ConversationView(conversation, state, holder)
                }
            }
        }
    }

    if (showSettings) {
        ConnectionDialog(
            initial = state.endpoint?.let { "${it.host}:${it.port}" } ?: saved.orEmpty(),
            onDismiss = { showSettings = false },
            onConnect = { text ->
                DshEndpoint.parse(text)?.let { endpoint ->
                    EndpointStore.save(context, "${endpoint.host}:${endpoint.port}")
                    holder.connect(endpoint)
                    showSettings = false
                }
            },
        )
    }
}

@Composable
private fun EmptyState(connected: Boolean, sessionCount: Int, onOpenDrawer: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (connected) "$sessionCount sessions" else "Not connected",
            color = MUTED,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Open the session list to pick a conversation.",
            color = MUTED,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onOpenDrawer) { Text("Sessions") }
    }
}

@Composable
private fun SessionDrawer(
    state: AppState,
    onPick: (SessionSummary) -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onToggleGroup: (String) -> Unit,
    onArchive: (String) -> Unit,
    onShowArchived: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(PANEL)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Sessions", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    text = when {
                        !state.connected -> "offline"
                        state.sessionsError != null -> "list failed"
                        else -> "${state.sessions.size} sessions · ${state.sessionsBytes}B"
                    },
                    color = if (state.sessionsError != null || !state.connected) WARN else MUTED,
                    fontSize = 11.sp,
                )
            }
            TextButton(onClick = onRefresh) { Text("Refresh") }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Connection")
            }
        }
        state.sessionsError?.let { message ->
            Text(
                text = message,
                color = WARN,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        HorizontalDivider(color = Color(0xFF2A2E38))
        LazyColumn(Modifier.fillMaxSize()) {
            if (state.sessions.isEmpty() && state.sessionsError == null) {
                item {
                    Text(
                        text = if (state.connected) "No sessions reported yet. Pull Refresh."
                        else "Not connected.",
                        color = MUTED, fontSize = 12.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            if (state.archived.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { onShowArchived(!state.showArchived) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (state.showArchived) "Hide archived (${state.archived.size})"
                            else "Show archived (${state.archived.size})",
                            color = ACCENT, fontSize = 12.sp,
                        )
                    }
                }
            }
            state.groups.forEach { group ->
                item(key = "group-${group.key}") {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onToggleGroup(group.key) }
                            .background(Color(0xFF23262E))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (group.expanded) "▾" else "▸",
                            color = MUTED, fontSize = 12.sp,
                            modifier = Modifier.width(18.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = group.label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            group.path?.let {
                                Text(it, color = MUTED, fontSize = 9.sp, maxLines = 1)
                            }
                        }
                        Text(
                            text = "${group.sessions.size}",
                            color = MUTED, fontSize = 10.sp,
                        )
                    }
                }
                if (group.expanded) {
                    items(group.sessions, key = { it.sessionId }) { session ->
                        SessionRow(
                            session = session,
                            active = state.conversation?.sessionId == session.sessionId,
                            archived = state.archived.contains(session.sessionId),
                            onPick = { onPick(session) },
                            onArchive = { onArchive(session.sessionId) },
                        )
                    }
                }
            }
            if (state.log.isNotEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("LOG", color = MUTED, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        state.log.take(6).forEach { line ->
                            Text(line, color = Color(0xFF6C7484), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Harness address") },
        text = {
            Column {
                Text(
                    "The LAN address the phone can reach, e.g. 192.168.1.20:3080. " +
                        "Plain HTTP is used, so keep it on a network you trust.",
                    fontSize = 12.sp,
                    color = MUTED,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("host:port") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConnect(text) }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConversationView(conversation: Conversation, state: AppState, holder: AppStateHolder) {
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val total = conversation.items.size
    val liveText = conversation.liveText
    // Follow the *newest* item, not the item count. Keying on the count made
    // "load older" scroll to the bottom, because prepending a page changed it.
    val newestKey = conversation.items.lastOrNull()?.key

    LaunchedEffect(newestKey, liveText) {
        val lastIndex = total + if (liveText.isNotEmpty()) 1 else 0
        if (lastIndex > 0) listState.animateScrollToItem(lastIndex)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (conversation.hasMore) {
                item {
                    TextButton(onClick = holder::loadOlder, modifier = Modifier.fillMaxWidth()) {
                        Text("Load older", fontSize = 12.sp)
                    }
                }
            }
            items(conversation.items, key = { it.key }) { item -> TranscriptRow(item) }
            if (liveText.isNotEmpty()) {
                item(key = "live") { AssistantBubble(liveText, streaming = true) }
            }
            conversation.error?.let { message ->
                item {
                    Text("⚠ $message", color = WARN, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                }
            }
        }

        // Pending Host calls sit directly above the composer: an unanswered
        // approval blocks the agent, so it must be impossible to miss.
        state.pending.filter { it.sessionId == conversation.sessionId }.forEach { interaction ->
            PendingCard(interaction, holder)
        }

        HorizontalDivider(color = Color(0xFF2A2E38))
        Row(
            Modifier.fillMaxWidth().background(PANEL).padding(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message", fontSize = 13.sp, color = MUTED) },
                maxLines = 5,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            IconButton(
                onClick = {
                    val text = input
                    input = ""
                    holder.send(text)
                },
                enabled = input.isNotBlank(),
            ) {
                Icon(Icons.Filled.Send, contentDescription = "Send", tint = if (input.isNotBlank()) ACCENT else MUTED)
            }
        }
    }
}

/** One answerable Host call: tool, reason, and its decisions. */
/** One session row: title, cwd, running state, and a long-press archive action. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: SessionSummary,
    active: Boolean,
    archived: Boolean,
    onPick: () -> Unit,
    onArchive: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onPick, onLongClick = { menu = true })
            .background(if (active) Color(0xFF262B36) else Color.Transparent)
            .padding(start = 30.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Text(
            text = session.title,
            fontSize = 13.sp,
            maxLines = 2,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (archived) MUTED else Color(0xFFDDE2EC),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (session.running) {
                Text("running", color = ACCENT, fontSize = 10.sp)
                Spacer(Modifier.width(8.dp))
            }
            if (session.origin == "subagent") {
                Text("subagent", color = MUTED, fontSize = 10.sp)
                Spacer(Modifier.width(8.dp))
            }
        }
    }
    if (menu) {
        AlertDialog(
            onDismissRequest = { menu = false },
            title = { Text(session.title, fontSize = 14.sp) },
            text = null,
            confirmButton = {
                TextButton(onClick = { onArchive(); menu = false }) {
                    Text(if (archived) "Unarchive" else "Archive")
                }
            },
            dismissButton = { TextButton(onClick = { menu = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PendingCard(interaction: PendingInteraction, holder: AppStateHolder) {
    Surface(
        color = Color(0xFF2A2118),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = when (interaction.kind) {
                    "approval" -> "Approval required"
                    "question" -> "Question"
                    "plan-review" -> "Plan review"
                    else -> interaction.kind
                },
                color = WARN,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            interaction.toolName?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
            interaction.reason?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, fontSize = 12.sp, color = MUTED)
            }
            Spacer(Modifier.height(10.dp))
            if (interaction.questions.isEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    interaction.choices.forEach { choice ->
                        TextButton(onClick = { holder.answer(interaction, choice.value) }) {
                            Text(choice.label, fontSize = 13.sp)
                        }
                    }
                }
            } else {
                QuestionBody(interaction, holder)
            }
        }
    }
}

/**
 * Render a user-questions batch.
 *
 * Single-select questions settle as soon as an option is chosen; a question
 * with no options (or a multi-select one) also offers a text field, because the
 * Host accepts free text alongside or instead of a selection and refusing to
 * send it would strand the agent.
 */
@Composable
private fun QuestionBody(interaction: PendingInteraction, holder: AppStateHolder) {
    val selected = remember(interaction.eventId) { mutableStateMapOf<String, List<String>>() }
    val custom = remember(interaction.eventId) { mutableStateMapOf<String, String>() }

    fun submit() = holder.answerQuestions(interaction, selected.toMap(), custom.toMap())

    Column {
        interaction.questions.forEach { question ->
            question.header?.let {
                Text(it.uppercase(), color = MUTED, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(question.question, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            question.detail?.let {
                Text(it.take(600), color = MUTED, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
            val options = question.options
            if (options.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                options.forEach { option ->
                    val isSelected = selected[question.id]?.contains(option.label) == true
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (question.multiSelect == true) {
                                    val current = selected[question.id].orEmpty()
                                    selected[question.id] =
                                        if (isSelected) current - option.label else current + option.label
                                } else {
                                    // Single select answers immediately: a second
                                    // tap target would add a step for nothing.
                                    selected[question.id] = listOf(option.label)
                                    submit()
                                }
                            }
                            .background(
                                if (isSelected) Color(0xFF33405A) else Color(0xFF23262E),
                                RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(option.label, fontSize = 13.sp)
                        option.description?.let {
                            Text(it, color = MUTED, fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            if (options.isEmpty() || question.multiSelect == true) {
                var draft by remember(question.id) { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = draft,
                        onValueChange = {
                            draft = it
                            custom[question.id] = it
                        },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Answer", fontSize = 12.sp, color = MUTED) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                }
            }
        }
        if (interaction.questions.size > 1 || interaction.questions.any { it.multiSelect == true || it.options.isEmpty() }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { submit() }) { Text("Submit", fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun TranscriptRow(item: TranscriptItem) {
    when (item) {
        is TranscriptItem.User -> UserBubble(item.text)
        is TranscriptItem.Assistant -> AssistantBubble(item.text, streaming = item.streaming)
        is TranscriptItem.ToolCall -> ToolCard(item)
        is TranscriptItem.ToolResultRow -> ActivityRow("← result", item.text?.take(200))
        is TranscriptItem.Activity -> ActivityRow(item.label, item.detail)
        is TranscriptItem.Note -> Text(item.text, color = MUTED, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = BUBBLE_USER,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(0.88f),
        ) {
            SelectionContainer {
                Text(text, fontSize = 14.sp, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

@Composable
private fun AssistantBubble(text: String, streaming: Boolean) {
    Surface(
        color = BUBBLE_ASSISTANT,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            val blocks = remember(text) { SimpleMarkdown.parse(text) }
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Prose -> {
                        val body = block.lines.joinToString("\n").trim('\n')
                        if (body.isNotEmpty()) {
                            SelectionContainer {
                                Text(
                                    text = SimpleMarkdown.inline(body, ACCENT),
                                    fontSize = 14.sp,
                                    lineHeight = 21.sp,
                                )
                            }
                        }
                    }
                    is MarkdownBlock.Code -> CodeBlock(block.language, block.code)
                }
            }
            if (streaming) {
                Text("▍", color = ACCENT, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun CodeBlock(language: String?, code: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(CODE_BG, RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        if (!language.isNullOrBlank()) {
            Text(language, color = MUTED, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(6.dp))
        }
        SelectionContainer {
            Text(
                text = remember(code, language) { CodeHighlight.highlight(code, language) },
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}

/**
 * One tool invocation.
 *
 * Collapsed it shows the tool and its most identifying argument (the command, the
 * path) because that is what makes a long tool-heavy turn scannable; expanded it
 * shows every argument and the full output. Failures stay marked even collapsed,
 * so a failed step cannot scroll past unnoticed.
 */
@Composable
private fun ToolCard(call: TranscriptItem.ToolCall) {
    val accent = if (call.failed) WARN else ACCENT
    val args = call.arguments

    fun argText(key: String): String? = (args?.get(key) as? JsonPrimitive)?.contentOrNull
    // The argument that identifies the call at a glance.
    val preview: String? = when (call.name) {
        "bash", "pwsh" -> argText("command") ?: argText("description")
        "read", "write", "edit" -> argText("file_path") ?: argText("path")
        else -> argText("command") ?: argText("path") ?: call.rawArguments
    }
    val status = when {
        call.result == null -> "running"
        call.failed -> "failed"
        else -> "done"
    }

    // A flat card: the call, its identifying argument, and its output, all
    // visible. An earlier version expanded on tap, and the animation read as
    // noise in a stream that is already dense.
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(Color(0xFF1A1D23), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = call.name,
                color = accent,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = status,
                color = if (call.failed) WARN else MUTED,
                fontSize = 10.sp,
            )
        }
        if (!preview.isNullOrBlank()) {
            SelectionContainer {
                Text(
                    text = preview.trim().take(600),
                    color = Color(0xFFB9C1CE),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        val output = call.result
        if (!output.isNullOrBlank()) {
            Spacer(Modifier.height(5.dp))
            SelectionContainer {
                Text(
                    text = output.trim().take(4000),
                    color = if (call.failed) WARN else Color(0xFF8A93A5),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun ActivityRow(label: String, detail: String?) {
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp)) {
        Text(label, color = MUTED, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        if (!detail.isNullOrBlank()) {
            Text(
                detail,
                color = Color(0xFF6C7484),
                fontSize = 11.sp,
                maxLines = 4,
                modifier = Modifier.padding(start = 8.dp, top = 2.dp),
            )
        }
    }
}
