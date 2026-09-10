package io.github.longislandicetea.dshnative

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
        val holder = AppStateHolder(lifecycleScope)
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
                    ConversationView(conversation, holder)
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
            items(state.sessions, key = { it.sessionId }) { session ->
                val active = state.conversation?.sessionId == session.sessionId
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(session) }
                        .background(if (active) Color(0xFF262B36) else Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = session.title,
                        fontSize = 13.sp,
                        maxLines = 2,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (session.running) {
                            Text("running", color = ACCENT, fontSize = 10.sp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(session.cwd ?: "", color = MUTED, fontSize = 10.sp, maxLines = 1)
                    }
                }
                HorizontalDivider(color = Color(0xFF23262E))
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
private fun ConversationView(conversation: Conversation, holder: AppStateHolder) {
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val total = conversation.items.size
    val liveText = conversation.liveText

    LaunchedEffect(total, liveText) {
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

@Composable
private fun TranscriptRow(item: TranscriptItem) {
    when (item) {
        is TranscriptItem.User -> UserBubble(item.text)
        is TranscriptItem.Assistant -> AssistantBubble(item.text, streaming = item.streaming)
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
