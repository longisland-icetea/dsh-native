package io.github.longislandicetea.dshnative

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.launch

internal val INK = Color(0xFF16181D)
internal val PANEL = Color(0xFF1E2128)
internal val BUBBLE_USER = Color(0xFF243044)
internal val BUBBLE_ASSISTANT = Color(0xFF1C1F26)
internal val CODE_BG = Color(0xFF12141A)
internal val ACCENT = Color(0xFF6E9BF7)
internal val MUTED = Color(0xFF8A93A5)
internal val WARN = Color(0xFFE5A06B)
/** Session states: a turn is running, the session wants an answer, or neither. */
internal val STATUS_RUNNING = Color(0xFF4C8DFF)
internal val STATUS_ATTENTION = Color(0xFFE5A06B)
internal val STATUS_DONE = Color(0xFF4CAF72)

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
        // Set before the first frame, so a phone never flashes a landscape layout
        // on its way to portrait. Tablets are left unspecified and rotate.
        val smallestWidth = resources.configuration.smallestScreenWidthDp
        requestedOrientation = if (locksToPortrait(smallestWidth)) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        val holder = AppStateHolder(lifecycleScope, applicationContext)
        // Every foreground re-reads the lists the Host does not stream. The phone
        // spends most of its life asleep with the app still on screen, and a
        // session archived -- or a turn that finished -- on another client while
        // it slept is otherwise only visible after a manual Refresh. The streams
        // look after themselves; these two are read, so they are read again here.
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                holder.refreshLists()
            }
        })
        setContent {
            MaterialTheme(colorScheme = scheme) {
                DshApp(holder, applicationContext)
            }
        }
    }
}

/**
 * Whether this device should be held to portrait.
 *
 * A phone is a portrait surface: the transcript, the composer and the drawer are
 * all laid out for one column, and rotating buys a reader nothing while costing
 * half the vertical space the transcript needs. A tablet is the opposite -- the
 * extra width is the point, and refusing to turn it is refusing the hardware.
 *
 * The boundary is Android's own: 600dp of smallest width is where the platform
 * itself stops calling a screen a phone (sw600dp is the tablet resource
 * qualifier, and `SCREENLAYOUT_SIZE_LARGE` begins there). Using smallest width
 * rather than current width means a phone held sideways is still a phone, and a
 * foldable keeps one answer however it is folded.
 *
 * Pure, so the boundary is pinned by a test instead of by remembering why 600.
 */
internal fun locksToPortrait(smallestWidthDp: Int): Boolean = smallestWidthDp < 600

/** What a session in the drawer is doing, as far as this client can tell. */
internal enum class SessionStatus { Running, NeedsYou, Done }

/**
 * The state of one session row.
 *
 * `running` comes from the Host. A session that is not running but has an
 * unanswered approval or question still wants the reader, and that is the state
 * worth interrupting for -- it is the one that blocks an agent. Everything else has
 * finished what it was asked to do.
 */
internal fun sessionStatus(
    running: Boolean,
    needsAnswer: Boolean,
    unread: Boolean = true,
): SessionStatus? = when {
    running -> SessionStatus.Running
    needsAnswer -> SessionStatus.NeedsYou
    // Idle and read is the unremarkable case: no dot, no label, exactly as the
    // web sidebar leaves a session it has nothing to say about. Marking every
    // finished session green said nothing, because almost every session is
    // finished almost all of the time.
    unread -> SessionStatus.Done
    else -> null
}

internal fun statusColor(status: SessionStatus): Color = when (status) {
    SessionStatus.Running -> STATUS_RUNNING
    SessionStatus.NeedsYou -> STATUS_ATTENTION
    SessionStatus.Done -> STATUS_DONE
}

internal fun statusLabel(status: SessionStatus): String = when (status) {
    SessionStatus.Running -> "running"
    SessionStatus.NeedsYou -> "needs you"
    SessionStatus.Done -> "done"
}

/** Which control the composer shows, as the web client decides it. */
internal enum class ComposerAction { Stop, Send, Idle }

/**
 * What the single composer button does right now.
 *
 * While a turn runs with an empty box it stops the turn; the moment there is
 * something typed -- or the turn is over -- it sends. Typing therefore replaces
 * stop with send, which is the point: a half-written follow-up can never be lost
 * to a stray tap on stop, and the reader never has to clear the box to interrupt.
 */
internal fun composerAction(running: Boolean, input: String): ComposerAction = when {
    running && input.isBlank() -> ComposerAction.Stop
    input.isBlank() -> ComposerAction.Idle
    else -> ComposerAction.Send
}

/** What the top-bar chip says: the model in force, with its effort when set. */
private fun selectionLabel(selection: ModelSelection?): String {
    val model = selection?.model?.takeIf { it.isNotBlank() } ?: return "model: default"
    val effort = selection.reasoningEffort?.takeIf { it.isNotBlank() }
    return if (effort == null) model else "$model · $effort"
}

/**
 * Model and reasoning-effort picker.
 *
 * The catalog is a provider -> model -> effort tree, so the dialog walks it in
 * that order: choosing a model with several efforts shows them as chips rather
 * than hiding the choice behind a second screen. `/compact` sits here because
 * both are mid-conversation knobs.
 */
@Composable
private fun ModelPickerDialog(
    state: AppState,
    onDismiss: () -> Unit,
    onPick: (String, String, String?) -> Unit,
    onCompact: () -> Unit,
) {
    val catalog = state.catalog
    // A session nobody has switched a model in runs the Host default, which the
    // catalog reports; without this fallback the picker shows nothing selected.
    val current = state.selection ?: catalog?.default
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Model", fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (catalog == null) {
                    Text("loading catalog…", color = MUTED, fontSize = 12.sp)
                }
                catalog?.groups?.forEach { group ->
                    Text(
                        text = group.name ?: group.id,
                        color = MUTED,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    )
                    group.models.forEach { model ->
                        val selected = current?.provider == group.id && current.model == model.id
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onPick(group.id, model.id, model.reasoning?.defaultEffort) }
                                .padding(vertical = 4.dp),
                        ) {
                            Text(
                                text = (if (selected) "● " else "○ ") + (model.name ?: model.id),
                                color = if (selected) ACCENT else Color(0xFFB9C1CE),
                                fontSize = 13.sp,
                            )
                            // Efforts appear only for the selected model: showing
                            // every model's efforts at once is a wall of text.
                            if (selected) {
                                val efforts = model.reasoning?.efforts.orEmpty()
                                if (efforts.isNotEmpty()) {
                                    Row(
                                        Modifier.padding(start = 14.dp, top = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        efforts.forEach { effort ->
                                            val on = current?.reasoningEffort == effort.id
                                            Text(
                                                text = effort.name ?: effort.id,
                                                color = if (on) INK else MUTED,
                                                fontSize = 11.sp,
                                                modifier = Modifier
                                                    .background(
                                                        if (on) ACCENT else Color(0xFF23272F),
                                                        RoundedCornerShape(6.dp),
                                                    )
                                                    .clickable(
                                                        interactionSource = remember { MutableInteractionSource() },
                                                        indication = null,
                                                    ) { onPick(group.id, model.id, effort.id) }
                                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 6.dp))
                Text(
                    text = "Compact conversation",
                    color = Color(0xFFB9C1CE),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onCompact() }
                        .padding(vertical = 4.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        containerColor = PANEL,
    )
}

/** A tap-opened preview of one deliverable, read through the Host. */
@Composable
private fun FilePreviewDialog(
    preview: FilePreview?,
    loading: String?,
    onDismiss: () -> Unit,
    onZoom: (FilePreview.Bitmap) -> Unit,
) {
    if (preview == null && loading == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = preview?.path?.substringAfterLast('/')
                        ?: loading?.substringAfterLast('/').orEmpty(),
                    fontSize = 15.sp,
                )
                Text(
                    text = preview?.path ?: loading.orEmpty(),
                    color = MUTED,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        text = {
            // The read is usually a fraction of a second, but a large file over
            // Wi-Fi is not: the sheet has to say it is working, and the transcript
            // row that used to carry this is behind the dialog.
            if (preview == null) {
                Text("reading…", color = MUTED, fontSize = 12.sp)
                return@AlertDialog
            }
            when (preview) {
                is FilePreview.Failed -> Text(preview.reason, color = WARN, fontSize = 12.sp)
                is FilePreview.Bitmap -> ImagePreview(preview) { onZoom(preview) }
                is FilePreview.Text -> {
                    val body = preview.body
                    if (body.isEmpty()) {
                        Text("(empty file)", color = MUTED, fontSize = 12.sp)
                        return@AlertDialog
                    }
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        // One selectable block: a preview is for reading and
                        // copying, and markdown rendering would hide the source the
                        // model wrote.
                        SelectionContainer {
                            Text(body, color = Color(0xFFB9C1CE), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val bitmap = preview as? FilePreview.Bitmap
                if (bitmap != null && bitmap.mime != null) {
                    TextButton(onClick = { onZoom(bitmap) }) { Text("Zoom") }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        containerColor = PANEL,
    )
}

/**
 * A figure, decoded once and shown scaled to the sheet's width.
 *
 * Decoding happens off the composition (`produceState` on the IO dispatcher)
 * because a 400 KB PNG is a visible stall on the main thread, and a deliverable
 * of several megabytes is a dropped frame the user sees as a freeze.
 *
 * The bitmap is capped: a phone has a fraction of a desktop's heap, and an
 * oversized decode fails with an OutOfMemoryError that would take the process
 * with it. Beyond the cap the sheet reports the size instead of trying.
 */
@Composable
private fun ImagePreview(preview: FilePreview.Bitmap, onZoom: () -> Unit) {
    val maxBytes = 12L * 1024 * 1024
    if (preview.mime == null || preview.bytes.size.toLong() > maxBytes) {
        Text(
            text = buildString {
                append("Not drawn here: ")
                append(preview.mime ?: "unrecognised image type")
                append(", ")
                append("%.1f MB".format(preview.bytes.size / 1024.0 / 1024.0))
                append(". Open it on the host at the path above.")
            },
            color = MUTED,
            fontSize = 12.sp,
        )
        return
    }
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, preview.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                android.graphics.BitmapFactory.decodeByteArray(preview.bytes, 0, preview.bytes.size)
            }.getOrNull()
        }
    }
    val image = bitmap
    when {
        image == null -> Text("The image could not be decoded.", color = WARN, fontSize = 12.sp)
        else -> Column(Modifier.verticalScroll(rememberScrollState())) {
            androidx.compose.foundation.Image(
                bitmap = image.asImageBitmap(),
                contentDescription = preview.path.substringAfterLast('/'),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onZoom() },
                contentScale = ContentScale.Fit,
            )
            Text(
                text = "${image.width}×${image.height} · %.0f KB · tap to zoom".format(preview.bytes.size / 1024.0),
                color = MUTED,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * A figure, full screen, pinch-to-zoom and draggable.
 *
 * A phone screen cannot show a 1817x1596 figure legibly, so the useful gesture is
 * "make this bigger and move it" rather than "fit it". Scale is clamped to
 * [1, 8]: below 1 the figure floats in empty space, above 8 the bitmap turns to
 * mush. Panning is clamped to the scaled image bounds so the figure cannot be
 * dragged off-screen and lost.
 *
 * Built on `transformable` + `detectTapGestures` rather than a scrollable
 * container: a scroll container would fight the pinch for the same pointers, and
 * the transcript behind this already scrolls.
 */
@Composable
private fun ImageZoomViewer(preview: FilePreview.Bitmap, onDismiss: () -> Unit) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, preview.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                android.graphics.BitmapFactory.decodeByteArray(preview.bytes, 0, preview.bytes.size)
            }.getOrNull()
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var boxSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    val transformable = rememberTransformableState { zoomChange, panChange, _ ->
        scale = clampScale(scale * zoomChange)
        offset = clampOffset(offset + panChange, scale, boxSize.width, boxSize.height)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF05070A))
                .onSizeChanged { boxSize = it }
                .transformable(transformable)
                .pointerInput(Unit) {
                    detectTapGestures(
                        // Double tap toggles between fit and a useful working zoom,
                        // because pinching to an exact factor on a phone is fiddly.
                        onDoubleTap = {
                            if (scale > IMAGE_MIN_SCALE + 0.05f) {
                                scale = IMAGE_MIN_SCALE
                                offset = androidx.compose.ui.geometry.Offset.Zero
                            } else {
                                scale = IMAGE_DOUBLE_TAP_SCALE
                            }
                        },
                        onTap = { onDismiss() },
                    )
                },
        ) {
            val image = bitmap
            if (image == null) {
                Text("decoding…", color = MUTED, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
            } else {
                androidx.compose.foundation.Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = preview.path.substringAfterLast('/'),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            }
            Text(
                text = "${(scale * 100).roundToInt()}% · double-tap to reset · tap to close",
                color = Color(0xFF6C7484),
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            )
        }
    }
}

/** The zoom range: below 1 the figure floats in space, above this it is mush. */
internal const val IMAGE_MIN_SCALE = 1f
internal const val IMAGE_MAX_SCALE = 8f

/** The working zoom a double tap lands on, since pinching to an exact factor is fiddly. */
internal const val IMAGE_DOUBLE_TAP_SCALE = 3f

internal fun clampScale(scale: Float): Float =
    scale.coerceIn(IMAGE_MIN_SCALE, IMAGE_MAX_SCALE)

/**
 * Keep the scaled image covering the viewport where it can.
 *
 * The image is fitted, so at scale 1 it matches the viewport on one axis and
 * leaves margin on the other; that margin is part of the allowed range. Without
 * this the figure can be flung out of view with no way back except a reset.
 *
 * Pure and internal so the arithmetic can be tested: a gesture cannot be injected
 * from a shell, but the bounds it depends on can be checked directly.
 */
internal fun clampOffset(
    offset: androidx.compose.ui.geometry.Offset,
    scale: Float,
    width: Int,
    height: Int,
): androidx.compose.ui.geometry.Offset {
    if (width <= 0 || height <= 0) return androidx.compose.ui.geometry.Offset.Zero
    // At fit there is no range to clamp into. Returning early also avoids
    // `coerceIn(-0.0f, 0.0f)`, which normalises -0.0f to +0.0f and makes the
    // result unequal to the offset that produced it.
    if (scale <= IMAGE_MIN_SCALE) return androidx.compose.ui.geometry.Offset.Zero
    val maxX = (width * (scale - 1f)) / 2f
    val maxY = (height * (scale - 1f)) / 2f
    return androidx.compose.ui.geometry.Offset(
        offset.x.coerceIn(-maxX, maxX),
        offset.y.coerceIn(-maxY, maxY),
    )
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
    var showModels by remember { mutableStateOf(false) }
    // The full-screen zoom target. Held here rather than in AppState: it is a
    // property of this device's screen, not of the session.
    var zoomed by remember { mutableStateOf<FilePreview.Bitmap?>(null) }
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
                    // Refresh means "bring this screen in step with the Host", so it
                    // re-reads everything rather than only the list: a reader who
                    // taps it is saying the screen looks wrong, and the screen is
                    // five mirrors, not one.
                    onRefresh = holder::resync,
                    onToggleGroup = holder::toggleGroup,
                    onArchive = holder::archive,
                    onNewSession = { workspaceId ->
                        holder.createSession(workspaceId)
                        scope.launch { drawerState.close() }
                    },
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
                        IconButton(onClick = {
                            // The Host pushes session state only on a change, and does
                            // not replay it, so anything that changed while this app
                            // was closed or offline would leave the list stale until
                            // a manual refresh. Opening the drawer is exactly when it
                            // has to be right.
                            holder.refreshSessions()
                            scope.launch { drawerState.open() }
                        }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Sessions")
                        }
                    },
                    title = {
                        Column {
                            Text(
                                // The harness's own name, matching the launcher
                                // label rather than a second spelling of it.
                                text = state.conversation?.title?.ifEmpty { "DSH" } ?: "DSH",
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Messages the outbox is still holding are the one
                                // thing about a weak link a reader must not have to
                                // guess at: they were typed, and they have not
                                // arrived. Saying so is the whole status line.
                                Text(
                                    text = connectionStatus(endpoint, state.connected, state.outbox.size),
                                    fontSize = 11.sp,
                                    color = if (state.connected && state.outbox.isEmpty()) MUTED else WARN,
                                )
                                // Model and effort are the two knobs worth reaching
                                // mid-conversation; both live behind this chip rather
                                // than in the settings dialog, which is about the
                                // connection.
                                val openConversation = state.conversation
                                if (openConversation != null) {
                                    Spacer(Modifier.width(8.dp))
                                    // The window's occupancy sits beside the model
                                    // because both answer "what am I talking to
                                    // right now", and the ring is the one warning
                                    // before the harness compacts the conversation.
                                    state.metrics[openConversation.sessionId]?.let { metrics ->
                                        ContextMeter(metrics)
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = selectionLabel(state.selection ?: state.catalog?.default),
                                        color = ACCENT,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        modifier = Modifier
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                            ) { showModels = true }
                                            .padding(horizontal = 2.dp, vertical = 1.dp),
                                    )
                                }
                            }
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
                        sessionCount = state.visibleSessions.size,
                        loading = !state.archivedKnown && !state.workspaceFailed,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                    )
                } else {
                    ConversationView(conversation, state, holder)
                }
            }
        }
    }

    if (state.preview != null || state.previewLoading != null) {
        FilePreviewDialog(
            preview = state.preview,
            loading = state.previewLoading,
            onDismiss = holder::dismissPreview,
            onZoom = { zoomed = it },
        )
    }
    zoomed?.let { ImageZoomViewer(it, onDismiss = { zoomed = null }) }

    if (showModels) {
        ModelPickerDialog(
            state = state,
            onDismiss = { showModels = false },
            onPick = { provider, model, effort ->
                holder.selectModel(provider, model, effort)
                showModels = false
            },
            onCompact = {
                holder.runCommand("/compact")
                showModels = false
            },
        )
    }

    if (showSettings) {
        ConnectionDialog(
            initial = state.endpoint?.let { "${it.host}:${it.port}" } ?: saved.orEmpty(),
            archivedCount = state.archived.size,
            showArchived = state.showArchived,
            onShowArchived = holder::setShowArchived,
            showLog = state.showLog,
            onShowLog = holder::setShowLog,
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
private fun EmptyState(
    connected: Boolean,
    sessionCount: Int,
    /**
     * True while the archive set is still on its way.
     *
     * The count is not knowable before then -- it reads 34 and then 7 -- so the
     * screen says it is loading instead of showing a number it will contradict.
     */
    loading: Boolean,
    onOpenDrawer: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = when {
                !connected -> "Not connected"
                loading -> "Loading sessions…"
                else -> "$sessionCount sessions"
            },
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
    onToggleGroup: (String) -> Unit,
    onArchive: (String) -> Unit,
    onNewSession: (String?) -> Unit,
) {
    // The list waits for the archive set so no frame can show rows that are about
    // to disappear. The wait is released by the stream ending, not by a stopwatch:
    // the workspace call takes a second or two on a cold start, and a two-second
    // timer is what let 34 sessions appear before dropping to 7. The 10s timer is
    // only a last resort for a stream that is neither delivering nor ending.
    var waitedTooLong by remember(state.endpoint) { mutableStateOf(false) }
    LaunchedEffect(state.archivedKnown, state.workspaceFailed) {
        if (state.archivedKnown || state.workspaceFailed) return@LaunchedEffect
        delay(10_000)
        waitedTooLong = true
    }
    val waiting = !state.archivedKnown && !state.workspaceFailed && !waitedTooLong

    Column(Modifier.fillMaxSize().background(PANEL)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Sessions", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    // Counts live sessions: archived ones are hidden by default,
                    // so a total that included them would not match the list.
                    text = when {
                        !state.connected -> "offline"
                        state.sessionsError != null -> "list failed"
                        !state.archivedKnown -> "loading…"
                        // Counts what the list shows: subagent sessions are
                        // children of a parent row, and archived ones are hidden
                        // by default.
                        else -> "${state.visibleSessions.size} sessions"
                    },
                    color = if (state.sessionsError != null || !state.connected) WARN else MUTED,
                    fontSize = 11.sp,
                )
            }
            TextButton(onClick = onRefresh) { Text("Refresh") }
            // No workspace: the Host starts the session in the process default
            // directory, which is what a "just let me type" session wants.
            TextButton(onClick = { onNewSession(null) }) { Text("New") }
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
            if (waiting && state.sessionsError == null) {
                item {
                    Text(
                        text = "Loading sessions…",
                        color = MUTED, fontSize = 12.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else if (state.sessions.isEmpty() && state.sessionsError == null) {
                item {
                    Text(
                        text = if (state.connected) "No sessions reported yet. Pull Refresh."
                        else "Not connected.",
                        color = MUTED, fontSize = 12.sp,
                        modifier = Modifier.padding(16.dp),
                    )
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
                        // A new session belongs in a directory: creating it from
                        // the group header is the only way to choose one without
                        // a directory picker, and the Host rejects workspaceId
                        // together with cwd, so the workspace is the choice.
                        group.workspaceId?.let { id ->
                            Text(
                                text = "＋",
                                color = MUTED,
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { onNewSession(id) }
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (group.expanded) {
                    items(group.sessions, key = { it.sessionId }) { session ->
                        SessionRow(
                            session = session,
                            active = state.conversation?.sessionId == session.sessionId,
                            archived = state.archived.contains(session.sessionId),
                            needsAnswer = state.pending.any { it.sessionId == session.sessionId },
                            unread = session.sessionId in state.unread,
                            onPick = { onPick(session) },
                            onArchive = { onArchive(session.sessionId) },
                        )
                    }
                }
            }
            // The log is a debugging aid, not part of the session list: it is off
            // unless asked for and sits below the sessions when it is on.
            if (state.showLog && state.log.isNotEmpty()) {
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
    archivedCount: Int,
    showArchived: Boolean,
    onShowArchived: (Boolean) -> Unit,
    showLog: Boolean,
    onShowLog: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
                Text(
                    "The LAN address the phone can reach, e.g. 192.168.1.20:3080. " +
                        "Plain HTTP is used, so keep it on a network you trust.",
                    fontSize = 12.sp,
                    color = MUTED,
                )
                Spacer(Modifier.height(6.dp))
                // Which build this is. Three releases went out stamped 0.1.0, so
                // there was no way to tell from the phone what was installed --
                // and no way for a bug report to say either.
                Text(
                    text = "Version ${BuildInfo.VERSION_NAME} (${BuildInfo.VERSION_CODE})",
                    fontSize = 11.sp,
                    color = MUTED,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("host:port") },
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { onShowArchived(!showArchived) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = showArchived, onCheckedChange = { onShowArchived(it) })
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Show archived sessions ($archivedCount)",
                        fontSize = 12.sp,
                        color = if (archivedCount == 0) MUTED else Color(0xFFDDE2EC),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().clickable { onShowLog(!showLog) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = showLog, onCheckedChange = { onShowLog(it) })
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Show transport log in the session list",
                        fontSize = 12.sp,
                        color = Color(0xFFDDE2EC),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConnect(text) }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationView(conversation: Conversation, state: AppState, holder: AppStateHolder) {
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    // Derived from the draft on every recomposition: the menu has to disappear
    // the moment the draft stops being a command line, and a remembered copy
    // would lag a keystroke behind.
    val commandSuggestions = if (state.editingQueued == null) {
        CommandMenu.suggestions(input, state.commands)
    } else {
        emptyList()
    }
    val total = conversation.items.size
    val liveText = conversation.liveText
    // Follow the *newest* item, not the item count. Keying on the count made
    // "load older" scroll to the bottom, because prepending a page changed it.
    val newestKey = conversation.items.lastOrNull()?.key

    // Follow the newest message only while the reader is already at the bottom.
    // Without this the transcript yanked itself back down on every streaming
    // update, so a page loaded with "Load older" was pulled out from under the
    // reader within a second -- "load older does not work".
    // Whether the reader is at the bottom drives both the follow behaviour above
    // and the jump button below; one signal, read live from the list.
    //
    // `canScrollForward` is the wrong test here: the bottom content padding is part
    // of the scrollable extent, so it stays true even when the last message is on
    // screen, which pinned the jump button on permanently. "The last item is laid
    // out and reaches the bottom of the viewport" is the question that actually
    // matters.
    val atBottom by remember(conversation.sessionId) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            // "Near the end" rather than "the last row reaches the bottom edge".
            // The strict form breaks the moment a message is appended: the last row
            // moves down by its own height, which is greater than the tolerance, so
            // following switched itself off exactly when it was needed.
            last.index >= info.totalItemsCount - 2
        }
    }

    // Follow the newest row only while the reader is already at the bottom.
    // `canScrollForward` is read live inside the effect: a `following` flag would
    // be captured stale by the flow, and re-running the effect when the flag
    // changed made the list animate back to the newest row the moment the reader
    // scrolled away from it -- which is what made "Load older" feel broken.
    // Land on the newest message when a conversation opens. Without this the list
    // started at the oldest row of the window, which also left the jump button
    // showing on a conversation the reader had not scrolled at all.
    LaunchedEffect(conversation.sessionId, total > 0) {
        if (total > 0) listState.scrollToItem(total - 1)
    }

    // Follow the newest row while the reader is at the bottom.
    //
    // Keyed on the newest key and the live text length rather than driven by a
    // `snapshotFlow`: reading `Conversation`'s fields from a snapshot observer
    // registers no dependency (it is a stable type), and reading the `state`
    // delegate from one did not observe updates either -- both produced a flow that
    // emitted once and never again, which is a follow that never follows. An effect
    // keyed on the values themselves is restart-based and cannot miss a change.
    LaunchedEffect(newestKey, liveText.length, atBottom) {
        if (!atBottom) return@LaunchedEffect
        // `scrollToItem`, not `animateScrollToItem`: the animation is cancelled and
        // restarted by the next token, so it never arrives, and the reader sees a
        // transcript that does not move.
        val lastIndex = total - 1 + if (liveText.isNotEmpty()) 1 else 0
        if (lastIndex >= 0) {
            val reach = listState.layoutInfo.viewportEndOffset + listState.layoutInfo.beforeContentPadding
            listState.scrollToItem(lastIndex, reach)
        }
    }

    // Opening a conversation must not raise the keyboard: the field is there to be
    // tapped, and a session that opens under a keyboard hides the transcript the
    // reader came for. `stateAlwaysHidden` covers the window regaining focus;
    // clearing focus covers the field holding it across a configuration change.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(conversation.sessionId) {
        focusManager.clearFocus(force = true)
    }

    // Paging up is meant to leave the reader where they were: the row under the top
    // of the viewport is remembered by *key* before the page is requested and
    // scrolled back to afterwards.
    //
    // KNOWN LIMITATION, deliberately left in place: it does not hold reliably. A
    // page that was just prepended has never been measured, and `scrollToItem`
    // places an item using the *estimated* height of everything above it, so the
    // restored position drifts -- on a 2772px screen it was measured at ~1074px,
    // which looks like a screenful of older messages appearing under the button.
    // The robust fix is `reverseLayout = true` (bottom-anchored, as chat clients
    // do): prepending then needs no compensation at all. That touches the follow
    // scroll, the button's position, and several index calculations, so it is
    // tracked as its own change rather than folded into this one.
    // The list is not only messages: the paging button, an error line, and the
    // streaming bubble can each sit at the top, so a list index is not a message
    // index. Counting the leading items is what keeps the anchor pointing at the
    // row the reader is actually looking at.
    val leadingItems =
        (if (conversation.hasMore) 1 else 0) +
            (if (conversation.error != null) 1 else 0) +
            (if (liveText.isNotEmpty()) 1 else 0)

    var anchorKey by remember(conversation.sessionId) { mutableStateOf<String?>(null) }
    var anchorOffset by remember(conversation.sessionId) { mutableStateOf(0) }

    // Watch the item list itself rather than a count: a page can land while the
    // session is streaming, and the key is the only thing that survives both the
    // prepend and a rebuilt snapshot. Once the row has been found the anchor is
    // dropped, so this cannot fight the reader afterwards.
    //
    // The offset is read from the list inside the effect, not from a captured
    // variable: a `snapshotFlow` body closes over the values it saw when it was
    // created, so a captured offset would be the one from before the page landed.
    LaunchedEffect(listState, conversation.sessionId) {
        // Re-resolve on every change to the items or the anchor, and only clear the
        // anchor once the row has actually been found. The transcript is transiently
        // empty in a real session -- a follow stream rebuilds it, and a page load can
        // overlap that -- and one-shot resolution simply gave up in that case, which
        // left the reader at the top of the new page.
        snapshotFlow { Triple(anchorKey, anchorOffset, conversation.items) }
            .collect { (wanted, offset, items) ->
                if (wanted == null || items.isEmpty()) return@collect
                // The message index has to be shifted back into list space, and the
                // leading items may have changed: the button disappears once the last
                // page lands, which removes one.
                fun target(): Int =
                    items.indexOfFirst { it.key == wanted }
                        .takeIf { it >= 0 }
                        ?.plus(leadingItems)
                        ?: -1
                val index = target()
                if (index < 0) return@collect

                // Two passes, because `scrollToItem` places an item using the
                // *estimated* height of everything above it -- and a page that was
                // just prepended has never been measured. One pass therefore landed
                // hundreds of pixels off, which is what made a load look like it had
                // dumped a screenful of older messages into the viewport. The second
                // pass runs after a frame, when the estimates have been replaced by
                // real heights, and only nudges.
                listState.scrollToItem(index, offset)
                withFrameNanos { }
                val settled = target()
                if (settled >= 0) listState.scrollToItem(settled, offset)
                anchorKey = null
            }
    }

    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // Deliberately small: reserving room for the jump button here left a
            // band of blank space under the last message even when the button was
            // not shown. The button is inset from the bottom by its own 16dp gap
            // instead, so nothing is reserved while it is hidden.
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 12.dp,
                bottom = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (conversation.hasMore) {
                item {
                    TextButton(
                        onClick = {
                            // Remember the reader's place before the page lands: the
                            // row at the top of the viewport, by key, and how far
                            // into it the viewport starts. The key survives the
                            // prepend, which shifts every index.
                            //
                            // This button is itself a list item at the very top, so
                            // the row under the viewport is usually the *second*
                            // visible item. Taking `firstVisibleItemIndex` alone
                            // resolved to index -1, set no anchor, and let the page
                            // land with the reader at the newest line of it.
                            val firstMessage = listState.firstVisibleItemIndex - leadingItems
                            val index = if (firstMessage >= 0) firstMessage else 0
                            val offset =
                                if (firstMessage >= 0) listState.firstVisibleItemScrollOffset else 0
                            conversation.items.getOrNull(index)?.let { row ->
                                anchorKey = row.key
                                anchorOffset = offset
                            }
                            holder.loadOlder()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Load older", fontSize = 12.sp)
                    }
                }
            }
            items(conversation.items, key = { it.key }) { item ->
                TranscriptRow(
                    item,
                    onOpenFile = holder::previewFile,
                    usageStats = state.metrics[conversation.sessionId]?.stats,
                )
            }
            if (liveText.isNotEmpty()) {
                item(key = "live") { AssistantBubble(liveText, streaming = true) }
            }
            conversation.error?.let { message ->
                item {
                    Text("⚠ $message", color = WARN, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                }
            }
        }

            // Jump to the newest message. It appears exactly when the reader has
            // scrolled away -- which is also when a new message stops dragging the
            // viewport, so the affordance replaces the old compulsory follow.
            if (!atBottom && total > 0) {
                // A bare circular arrow rather than a labelled pill: it is a
                // one-gesture affordance that sits over the transcript, so it should
                // take as little of the reader's view as possible.
                Surface(
                    color = PANEL,
                    shape = CircleShape,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 14.dp, bottom = 14.dp)
                        .size(48.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            // Returning to the bottom also resumes following, so the
                            // next message arrives in view as it used to.
                            scope.launch { listState.animateScrollToItem(total - 1) }
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Jump to newest",
                            tint = ACCENT,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }
        }

        // Pending Host calls sit directly above the composer: an unanswered
        // approval blocks the agent, so it must be impossible to miss.
        state.pending.filter { it.sessionId == conversation.sessionId }.forEach { interaction ->
            PendingCard(interaction, holder)
        }

        // Waiting messages, then the command menu: both belong to the composer
        // rather than the transcript, and both have to be visible without a tap
        // because they change what sending will do.
        val queued = state.queues[conversation.sessionId].orEmpty()
        QueueDock(
            items = queued,
            running = conversation.running,
            editing = state.editingQueued,
            error = state.queueError,
            onSteer = { holder.queueAction(it, QueueAction.steer(), "steer") },
            onEdit = holder::editQueued,
            onRemove = { holder.queueAction(it, QueueAction.remove(), "remove") },
            onCancelEdit = holder::cancelQueueEdit,
        )
        CommandMenuPanel(
            suggestions = commandSuggestions,
            onPick = { suggestion ->
                input = CommandMenu.lineFor(suggestion.command)
                if (suggestion.command.runsOnPick) {
                    val line = input.trim()
                    input = ""
                    holder.runCommand(line)
                }
            },
        )

        HorizontalDivider(color = Color(0xFF2A2E38))
        Row(
            Modifier.fillMaxWidth().background(PANEL).padding(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            val fieldFocus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(fieldFocus)
                    // A tap must raise the keyboard even when the field already holds
                    // focus: after the IME is dismissed with Back the field is still
                    // focused, so tapping it produces no focus *change* and Compose
                    // never asks for the keyboard again. Requesting both explicitly
                    // makes every tap work, which is what a reader expects.
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            fieldFocus.requestFocus()
                            keyboard?.show()
                        })
                    },
                placeholder = { Text("Message", fontSize = 13.sp, color = MUTED) },
                maxLines = 5,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            when (composerAction(conversation.running, input)) {
                ComposerAction.Stop -> IconButton(onClick = holder::cancel) {
                    Icon(Icons.Filled.Stop, contentDescription = "Cancel turn", tint = WARN)
                }
                ComposerAction.Send, ComposerAction.Idle -> {
                    val editing = state.editingQueued
                    var showQueueMenu by remember { mutableStateOf(false) }
                    fun submit(mode: String) {
                        val text = input
                        input = ""
                        // Editing a queued row keeps the message in the queue: a
                        // save must not turn into a second message, so the action
                        // follows the mode the composer is in.
                        when {
                            editing != null -> holder.queueAction(editing, QueueAction.edit(text), "edit")
                            isCommand(state, text) -> holder.runCommand(text.trim())
                            // Default is steer, so a correction lands while the
                            // agent is working; queueing is the deliberate choice
                            // behind a long press, for "after this turn".
                            mode == "queue" -> holder.enqueue(text)
                            else -> holder.send(text)
                        }
                    }
                    Box {
                        IconButton(
                            onClick = { submit("steer") },
                            enabled = input.isNotBlank(),
                            // Long press offers the other delivery mode, but only
                            // while a turn is running: with nothing running, queue
                            // and steer are the same thing.
                            modifier = if (conversation.running && editing == null) {
                                Modifier.combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { submit("steer") },
                                    onLongClick = { showQueueMenu = true },
                                )
                            } else {
                                Modifier
                            },
                        ) {
                            Icon(
                                Icons.Filled.Send,
                                contentDescription = if (editing != null) "Save queued message" else "Send",
                                tint = if (input.isNotBlank()) ACCENT else MUTED,
                            )
                        }
                        DropdownMenu(expanded = showQueueMenu, onDismissRequest = { showQueueMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Steer now", fontSize = 13.sp) },
                                onClick = { showQueueMenu = false; submit("steer") },
                            )
                            DropdownMenuItem(
                                text = { Text("Queue for next turn", fontSize = 13.sp) },
                                onClick = { showQueueMenu = false; submit("queue") },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The connection line: what this client is doing about the network, in words.
 *
 * Three states, and the third is the one a weak link needs: a message that was
 * typed, has not arrived, and is being retried is not "connected" from the
 * reader's point of view. Pure so the wording is pinned by a test rather than by
 * whoever last edited the layout.
 */
internal fun connectionStatus(endpoint: DshEndpoint?, connected: Boolean, waiting: Int): String {
    if (endpoint == null) return "not configured"
    val held = if (waiting == 0) "" else "$waiting waiting · "
    return if (connected) "${if (waiting == 0) "connected" else "${waiting} waiting for the network"} · ${endpoint.host}"
    else "reconnecting · $held${endpoint.host}"
}

/** One answerable Host call: tool, reason, and its decisions. */
/** One session row: title, cwd, running state, and a long-press archive action. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: SessionSummary,
    active: Boolean,
    archived: Boolean,
    /** Whether this session has an approval or question waiting to be answered. */
    needsAnswer: Boolean,
    /** Whether its last turn finished without the reader looking at it. */
    unread: Boolean,
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
            // One dot per row, coloured by state: blue while a turn runs, orange
            // when the session is waiting on an answer, green once it is done. The
            // label carries the meaning for anyone who cannot rely on the colour.
            val status = sessionStatus(session.running, needsAnswer, unread)
            if (status != null) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(statusColor(status), CircleShape),
                )
                Spacer(Modifier.width(6.dp))
                Text(statusLabel(status), color = statusColor(status), fontSize = 10.sp)
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
            confirmButton = {
                TextButton(enabled = !archived, onClick = { onArchive(); menu = false }) {
                    Text("Archive")
                }
            },
            dismissButton = { TextButton(onClick = { menu = false }) { Text("Cancel") } },
            // Archiving is one-way in this harness: there is no unarchive API, so
            // offering "Unarchive" here (as an earlier version did) was a button
            // that did nothing. Say where it can be undone instead.
            text = if (archived) {
                {
                    Text(
                        "This session is archived. Archiving is one-way in DSH, so it " +
                            "cannot be restored from here.",
                        fontSize = 12.sp,
                        color = MUTED,
                    )
                }
            } else {
                null
            },
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
 * Render a user-questions batch, one question at a time.
 *
 * The order and the rules mirror the web client's card, because the same Host
 * asks the same question in both and an answer the web client would not produce
 * is not one the asker is prepared for:
 *
 * - one question on screen, with `1 / n` and arrows to move between them;
 * - choosing an option on a single-select question answers it and moves on;
 *   toggling one on a multi-select question keeps it open;
 * - every question with options also offers a text field, and typing in it
 *   answers the question in place of the option;
 * - "Skip" answers the question with nothing, which is different from not
 *   having answered it yet;
 * - the primary button advances until the last question, then submits the whole
 *   batch -- the Host wants all of the answers at once, in one waterfall value.
 */
@Composable
private fun QuestionBody(interaction: PendingInteraction, holder: AppStateHolder) {
    val questions = interaction.questions
    var index by remember(interaction.eventId) { mutableStateOf(0) }
    val drafts = remember(interaction.eventId) {
        mutableStateListOf<QuestionDraft>().apply { addAll(QuestionFlow.drafts(questions)) }
    }
    var error by remember(interaction.eventId) { mutableStateOf<String?>(null) }
    var busy by remember(interaction.eventId) { mutableStateOf(false) }

    val question = questions.getOrNull(index) ?: return
    val draft = drafts.getOrNull(index) ?: QuestionDraft()
    val multiSelect = question.multiSelect == true
    val last = index == questions.lastIndex

    fun put(value: QuestionDraft) {
        if (index in drafts.indices) drafts[index] = value
        error = null
    }

    fun submit() {
        val missing = QuestionFlow.firstIncomplete(drafts)
        if (missing != null) {
            index = missing
            error = "Please answer this question, or skip it."
            return
        }
        busy = true
        holder.answerQuestions(interaction, QuestionFlow.answerValue(questions, drafts))
    }

    Column {
        // One question at a time: the pager also shows how much of the batch is
        // left, which a single scrolling card cannot.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (questions.size > 1) {
                Text(
                    text = "${index + 1} / ${questions.size}",
                    color = MUTED,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(6.dp))
            }
            question.header?.let {
                Text(it.uppercase(), color = MUTED, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            if (questions.size > 1) {
                IconButton(
                    onClick = { index -= 1; error = null },
                    enabled = index > 0 && !busy,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Previous question", tint = if (index > 0) ACCENT else MUTED, modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = { index += 1; error = null },
                    enabled = !last && !busy,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Next question", tint = if (!last) ACCENT else MUTED, modifier = Modifier.size(18.dp))
                }
            }
        }
        Text(question.question, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        question.detail?.let {
            Text(it.take(600), color = MUTED, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }

        Spacer(Modifier.height(6.dp))
        question.options.forEachIndexed { optionIndex, option ->
            val isSelected = draft.selected.contains(option.label)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !busy) {
                        val next = QuestionFlow.choose(draft, option.label, multiSelect)
                        // A single-select answer is complete on its own, so it
                        // moves on; a toggle is not, so it stays.
                        if (!multiSelect && index < questions.lastIndex) {
                            put(next)
                            index += 1
                            error = null
                        } else {
                            put(next)
                        }
                    }
                    .background(
                        if (isSelected) Color(0xFF33405A) else Color(0xFF23262E),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = if (multiSelect) (if (isSelected) "\u2611" else "\u2610") else "${optionIndex + 1}.",
                    color = if (isSelected) ACCENT else MUTED,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(20.dp),
                )
                Column {
                    Text(option.label, fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
                    option.description?.let { Text(it, color = MUTED, fontSize = 11.sp) }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        // Every question takes a typed answer, options or not: the Host accepts
        // free text alongside a selection and the web client offers the same
        // field, so a reader with an answer nobody listed is never stuck.
        var draftText by remember(interaction.eventId, index) { mutableStateOf(draft.custom) }
        TextField(
            value = draftText,
            onValueChange = {
                draftText = it
                put(QuestionFlow.type(draft, it, multiSelect))
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (question.options.isEmpty()) "Type your answer" else "Or type your own answer", fontSize = 12.sp, color = MUTED) },
            maxLines = 4,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF23262E),
                unfocusedContainerColor = Color(0xFF23262E),
                focusedIndicatorColor = if (draftText.isNotEmpty()) ACCENT else Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )

        error?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = WARN, fontSize = 11.sp)
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { put(QuestionFlow.skip(draft)) }, enabled = !busy) {
                Text("Skip this question", fontSize = 12.sp, color = MUTED)
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { if (last) submit() else { index += 1; error = null } },
                enabled = !busy && draft.answered(),
            ) {
                Text(
                    text = when {
                        busy -> "Sending…"
                        last -> "Submit"
                        else -> "Next question"
                    },
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun TranscriptRow(
    item: TranscriptItem,
    onOpenFile: (String) -> Unit = {},
    /** The open session's totals, for the dialog a usage row opens. */
    usageStats: SessionStats? = null,
) {
    when (item) {
        is TranscriptItem.User -> UserBubble(item.text)
        is TranscriptItem.Pending -> PendingBubble(item)
        is TranscriptItem.Assistant -> AssistantBubble(item.text, streaming = item.streaming)
        is TranscriptItem.ToolCall -> ToolCard(item)
        is TranscriptItem.Activity -> ActivityRow(item.label, item.detail)
        // Unfolded results are folded away by the reducer; this keeps the `when`
        // exhaustive without ever rendering a bare result line.
        is TranscriptItem.ToolResultRow -> Unit
        is TranscriptItem.Note -> Text(item.text, color = MUTED, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
        is TranscriptItem.Notice -> NoticeCard(item)
        is TranscriptItem.Todo -> TodoCard(item.todos)
        is TranscriptItem.Usage -> {
            var showUsage by remember(item.key) { mutableStateOf(false) }
            TurnUsageRow(
                usage = item.usage,
                stats = usageStats,
                onOpen = { showUsage = true },
            )
            if (showUsage) {
                TurnUsageDialog(item.usage, usageStats, onDismiss = { showUsage = false })
            }
        }
        is TranscriptItem.Deliverables -> DeliverablesCard(item, onOpenFile)
    }
}

/**
 * One prose block, styled by the kind the parser assigned.
 *
 * Headings and quotes get their emphasis from size and a left rule rather than
 * from `#` and `>` markers: the markers are syntax, and a phone reading a plan
 * wants the hierarchy, not the source.
 */
@Composable
private fun ProseBlock(block: MarkdownBlock.Prose) {
    val body = block.lines.joinToString("\n").trim('\n')
    if (body.isEmpty()) return
    val (size, weight, lineHeight) = when (block.kind) {
        ProseKind.Heading1 -> Triple(19.sp, FontWeight.SemiBold, 25.sp)
        ProseKind.Heading2 -> Triple(17.sp, FontWeight.SemiBold, 23.sp)
        ProseKind.Heading3 -> Triple(15.sp, FontWeight.SemiBold, 21.sp)
        ProseKind.Bullet, ProseKind.Ordered -> Triple(14.sp, FontWeight.Normal, 21.sp)
        else -> Triple(14.sp, FontWeight.Normal, 21.sp)
    }
    val content: @Composable () -> Unit = {
        SelectionContainer {
            Text(
                text = SimpleMarkdown.inline(body, ACCENT, Color(0xFF8FD6FF)),
                fontSize = size,
                lineHeight = lineHeight,
                fontWeight = weight,
                color = if (block.kind == ProseKind.Quote) MUTED else Color.Unspecified,
                // A bullet list is indented as a block; the marker is in the text,
                // so the indent is all the layout has to add.
                modifier = if (block.kind == ProseKind.Bullet || block.kind == ProseKind.Ordered) {
                    Modifier.padding(start = 10.dp)
                } else {
                    Modifier
                },
            )
        }
    }
    when (block.kind) {
        // A quote is a left rule plus an indent. The rule is sized by the text
        // rather than stretched: `fillMaxHeight` needs a bounded parent, and the
        // transcript's Column is unbounded by design.
        ProseKind.Quote -> Surface(
            color = Color(0xFF191D24),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp, end = 6.dp)) {
                Text("▎", color = Color(0xFF3A4150), fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                Column { content() }
            }
        }
        else -> content()
    }
}

/** Cell padding, shared so the header and body columns line up. */
/**
 * The turn a transcript row belongs to, read from its key.
 *
 * Rows are keyed `turn:<turn>:<seq>`, which is the only place the turn number is
 * retained -- the renderer does not model turns, it draws a stream of rows.
 */
/**
 * Whether this draft is a command line the Host knows.
 *
 * Checked against the loaded command list rather than by shape alone: sending
 * `/etc/hosts` as a command would fail, and the reader meant it as prose.
 */
internal fun isCommand(state: AppState, draft: String): Boolean {
    val line = CommandMenu.commandLine(draft) ?: return false
    val name = line.substringBefore(' ').drop(1)
    return state.commands.any { it.name == name }
}

internal val TABLE_CELL_PADDING = 8.dp
internal val TABLE_RULE = Color(0xFF2A2F38)
private val TABLE_STRIPE = Color(0xFF1B1F27)

/**
 * A pipe table: proportional columns, ruled cells, and sideways scroll.
 *
 * Sizing by the *widest* cell in each column, instead of by the header, is what
 * stops columns from being too narrow to read and then wrapping into a shape that
 * is no longer a table. Weights are then normalised to fill the width, with a
 * floor and a ceiling: the floor keeps a short column clickable, and the ceiling
 * stops one chatty column from squeezing the rest to nothing.
 *
 * Every cell is ruled and every other row is tinted. A phone shows four or five
 * rows at a time, so losing the row line costs the reader their place; the rules
 * are what make a dense result table scannable rather than a wall of numbers.
 */
@Composable
private fun TableBlock(table: MarkdownBlock.Table) {
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return

    val paddingPx = with(LocalDensity.current) { TABLE_CELL_PADDING.toPx() }

    // The grid needs a *bounded* width or `weight` cannot resolve: inside
    // `horizontalScroll` the width constraint is infinite, and `Row` skips
    // weighing under infinite constraints -- which measured every column as zero
    // and rendered an empty frame. `BoxWithConstraints` supplies the viewport
    // width to lay the grid out against; the scroll stays as the overflow path
    // for a wide table.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val viewport = maxWidth
        val viewportPx = with(LocalDensity.current) { viewport.toPx() }
        val maxColumnPx = with(LocalDensity.current) { TABLE_MAX_CELL_WIDTH.toPx() }

        val layout = remember(table, viewportPx) {
            equalColumnWidths(columns, viewportPx, paddingPx, maxColumnPx)
        }
        val widths = layout.first
        val gridWidth = with(LocalDensity.current) { layout.second.toDp() }
        val cellWidth = with(LocalDensity.current) {
            Array(columns) { index -> widths.getOrElse(index) { 0f }.toDp() }
        }

        Column(
            Modifier
                .horizontalScroll(rememberScrollState())
                .background(Color(0xFF171A20), RoundedCornerShape(6.dp))
                .padding(vertical = 4.dp),
        ) {
            Row(Modifier.width(gridWidth).height(IntrinsicSize.Min)) {
                table.header.forEachIndexed { index, cell ->
                    TableCell(
                        text = cell,
                        width = cellWidth[index],
                        header = true,
                        last = index == columns - 1,
                    )
                }
            }
            HorizontalDivider(color = TABLE_RULE, thickness = 1.dp)
            table.rows.forEachIndexed { rowIndex, row ->
                Row(
                    Modifier
                        .width(gridWidth)
                        // `IntrinsicSize.Min` is what gives the vertical rules a
                        // height: without a bounded height `fillMaxHeight` inside
                        // resolves to nothing and the rules vanish.
                        .height(IntrinsicSize.Min)
                        .background(if (rowIndex % 2 == 1) TABLE_STRIPE else Color.Transparent),
                ) {
                    for (index in 0 until columns) {
                        TableCell(
                            text = row.getOrElse(index) { "" },
                            width = cellWidth[index],
                            header = false,
                            last = index == columns - 1,
                        )
                    }
                }
                if (rowIndex != table.rows.lastIndex) {
                    HorizontalDivider(color = TABLE_RULE.copy(alpha = 0.5f), thickness = 0.5.dp)
                }
            }
        }
    }
}

/**
 * Widest a column may grow to. A single column of prose would otherwise take the
 * whole width and become an unreadable line length.
 */
private val TABLE_MAX_CELL_WIDTH = 320.dp

/**
 * Column widths in pixels: equal shares of the viewport, always.
 *
 * `table-layout: fixed` with equal columns. A table is read down its columns, so
 * equal widths let the eye find a column without re-measuring it on every row --
 * and on a phone the whole grid then fits, which matters more than giving a short
 * column less room. Cells wrap inside their share; a long token is allowed to
 * overflow its own cell rather than be given a wider one, because widening one
 * column is what pushed the grid past the viewport and left the screen empty.
 *
 * Returns the widths *and* the grid width, since the two have to agree: the grid
 * is their sum plus the padding, and computing that separately is how it drifted
 * from the viewport in the first place.
 */
internal fun equalColumnWidths(
    columns: Int,
    available: Float,
    cellPadding: Float,
    maxColumnWidth: Float,
): Pair<List<Float>, Float> {
    if (columns <= 0) return emptyList<Float>() to 0f
    val viewport = maxOf(available, 1f)
    val budget = maxOf(viewport - cellPadding * columns, 1f)
    // The cap only bites when the columns are wide enough to be unreadable lines;
    // it can never make the grid wider than the viewport.
    val each = minOf(budget / columns, maxOf(maxColumnWidth, 1f))
    val grid = each * columns + cellPadding * columns
    return List(columns) { each } to minOf(grid, viewport)
}

/**
 * The narrowest a column can be without breaking a word: its longest unbreakable
 * token, measured in the font the table renders with.
 *
 * CJK text breaks between any two characters, so a run of Han characters is not
 * one token; treating it as one would lock every Chinese column at the width of
 * its longest phrase and force the table to scroll.
 */
internal fun minContentWidth(text: String, measure: (String) -> Float): Float {
    if (text.isEmpty()) return 0f
    var widest = 0f
    var token = StringBuilder()
    fun flush() {
        if (token.isNotEmpty()) {
            widest = maxOf(widest, measure(token.toString()))
            token = StringBuilder()
        }
    }
    text.forEach { ch ->
        when {
            ch.isWhitespace() -> flush()
            isBreakableCjk(ch) -> {
                flush()
                widest = maxOf(widest, measure(ch.toString()))
            }
            else -> token.append(ch)
        }
    }
    flush()
    return widest
}

/** Whether a line may break on either side of this character. */
internal fun isBreakableCjk(ch: Char): Boolean = when (Character.UnicodeBlock.of(ch)) {
    Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
    Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
    Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
    Character.UnicodeBlock.HIRAGANA,
    Character.UnicodeBlock.KATAKANA,
    Character.UnicodeBlock.HANGUL_SYLLABLES,
    -> true
    else -> false
}

/**
 * One grid cell: the text, its padding, and the rule on its trailing edge.
 *
 * A `RowScope` extension because the column weight belongs to the enclosing row,
 * not to a composable of its own.
 */
@Composable
private fun RowScope.TableCell(text: String, width: androidx.compose.ui.unit.Dp, header: Boolean, last: Boolean) {
    Row(Modifier.width(width).fillMaxHeight()) {
        Text(
            text = SimpleMarkdown.inline(text, ACCENT, Color(0xFF8FD6FF)),
            color = if (header) ACCENT else Color(0xFFB9C1CE),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
            // `weight(1f)` is what confines the text to its column. Without it the
            // Text takes its full intrinsic width, overflows the fixed-width cell,
            // and every row then appears to have different column positions.
            modifier = Modifier
                .weight(1f)
                .padding(
                    start = TABLE_CELL_PADDING,
                    end = if (last) TABLE_CELL_PADDING else 4.dp,
                    top = 6.dp,
                    bottom = 6.dp,
                ),
        )
        // The vertical rule between columns is the strongest cue that a row of
        // numbers is a row of fields.
        if (!last) {
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(TABLE_RULE),
            )
        }
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

/**
 * A message this client sent that the Host has not logged yet.
 *
 * Deliberately the reader's own bubble, in the reader's own place, dimmed rather
 * than hidden. A message you sent being invisible is the whole bug this row
 * exists for: it used to live only in the queue dock -- out of the conversation,
 * under a Remove button -- until a turn read it, which on a long tool call is
 * minutes and, if the Host let it go, is never. The line under it says which of
 * those it is.
 */
@Composable
private fun PendingBubble(item: TranscriptItem.Pending) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            modifier = Modifier.fillMaxWidth(0.88f),
            horizontalAlignment = Alignment.End,
        ) {
            Surface(color = BUBBLE_USER.copy(alpha = 0.55f), shape = RoundedCornerShape(12.dp)) {
                SelectionContainer {
                    Text(item.text, fontSize = 14.sp, modifier = Modifier.padding(12.dp))
                }
            }
            Text(
                text = item.failure
                    ?: item.note
                    ?: if (item.admitted) "waiting for the agent to read it" else "sending…",
                color = if (item.failure != null) WARN else MUTED,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 3.dp, end = 2.dp),
            )
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
                    is MarkdownBlock.Prose -> ProseBlock(block)
                    is MarkdownBlock.Code -> CodeBlock(block.language, block.code)
                    is MarkdownBlock.Table -> TableBlock(block)
                    MarkdownBlock.Rule -> HorizontalDivider(
                        Modifier.padding(vertical = 6.dp),
                        color = Color(0xFF2A2F38),
                    )
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
    var expanded by remember { mutableStateOf(false) }
    val accent = when (call.status) {
        TranscriptItem.ToolCall.Status.FAILED -> WARN
        else -> ACCENT
    }
    val args = call.arguments

    fun argText(key: String): String? = (args?.get(key) as? JsonPrimitive)?.contentOrNull
    val preview: String? = when (call.name) {
        "bash", "pwsh" -> argText("command") ?: argText("description")
        "read", "write", "edit" -> argText("file_path") ?: argText("path")
        else -> argText("command") ?: argText("path") ?: call.rawArguments
    }
    val statusLabel = when (call.status) {
        TranscriptItem.ToolCall.Status.RUNNING -> "running"
        TranscriptItem.ToolCall.Status.DONE -> "done"
        TranscriptItem.ToolCall.Status.FAILED -> "failed"
    }

    // Tap toggles visibility with no animation: the transition read as noise in
    // an already dense stream. Collapsed shows one identifying line; expanded
    // shows the argument and output in full, untruncated, because a truncated
    // output is what forces a trip back to the desktop.
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(Color(0xFF1A1D23), RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (expanded) "v" else ">",
                color = MUTED,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(12.dp),
            )
            Text(
                text = call.name,
                color = accent,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = statusLabel,
                color = if (call.status == TranscriptItem.ToolCall.Status.FAILED) WARN else MUTED,
                fontSize = 10.sp,
            )
        }
        if (!preview.isNullOrBlank()) {
            Text(
                text = if (expanded) preview.trim() else preview.replace("\n", " ").trim().take(120),
                color = Color(0xFFB9C1CE),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                modifier = Modifier.padding(start = 12.dp, top = 3.dp),
            )
        }
        if (expanded) {
            val output = call.result
            if (!output.isNullOrBlank()) {
                Spacer(Modifier.height(5.dp))
                SelectionContainer {
                    Text(
                        text = output.trim(),
                        color = if (call.status == TranscriptItem.ToolCall.Status.FAILED) WARN
                        else Color(0xFF8A93A5),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/**
 * A harness notice, laid out like a tool card.
 *
 * Background-job results and other plugin messages are the harness talking about
 * its own work; the tool-card shape marks them as machinery instead of letting
 * them read as a reply.
 */
@Composable
private fun NoticeCard(item: TranscriptItem.Notice) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(Color(0xFF1A1D23), RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (expanded) "v" else ">",
                color = MUTED,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(12.dp),
            )
            Text(
                text = item.label,
                color = ACCENT,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
            // A subagent message names its sender; which one spoke is the point.
            item.sender?.let {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "from " + it.takeLast(8),
                    color = Color(0xFF7C8598),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
            // Only a scoped plugin name carries information the short label lost.
            item.plugin?.takeIf { it.contains('/') }?.let {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = it,
                    color = Color(0xFF5D6577),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val body = item.body?.trim()
        if (!body.isNullOrEmpty()) {
            Text(
                text = if (expanded) body else body.replace("\n", " ").take(120),
                color = Color(0xFFB9C1CE),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                modifier = Modifier.padding(start = 12.dp, top = 3.dp),
            )
        }
    }
}

/**
 * The model's plan, as the newest `todo/write` left it.
 *
 * A todo list is state, not an event: only the latest write matters, which is why
 * this renders the list it was handed rather than accumulating rows.
 */
@Composable
private fun TodoCard(todos: List<EventPayload.Todo>) {
    val done = todos.count { it.status == "completed" }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(Color(0xFF1A1D23), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("todos", color = ACCENT, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text("$done/${todos.size}", color = MUTED, fontSize = 10.sp)
        }
        todos.forEach { todo ->
            Row(Modifier.padding(start = 2.dp, top = 3.dp)) {
                Text(
                    text = when (todo.status) {
                        "completed" -> "✓"
                        "in_progress" -> "▶"
                        else -> "○"
                    },
                    color = when (todo.status) {
                        "completed" -> Color(0xFF6FBF73)
                        "in_progress" -> ACCENT
                        else -> MUTED
                    },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(16.dp),
                )
                Text(
                    text = todo.content,
                    color = if (todo.status == "completed") Color(0xFF6C7484) else Color(0xFFB9C1CE),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    // Struck-through would be prettier, but a completed item still
                    // has to be readable: the dim colour carries it.
                    textDecoration = if (todo.status == "completed") TextDecoration.LineThrough else null,
                )
            }
        }
    }
}

/**
 * Files a turn declared, each opening the preview sheet.
 *
 * `present` is how the model says "this is the deliverable"; a path on its own
 * would be buried in a tool card's output.
 */
@Composable
private fun DeliverablesCard(item: TranscriptItem.Deliverables, onOpen: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(Color(0xFF1C2230), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text("deliverables", color = ACCENT, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        item.files.forEach { file ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onOpen(file.path) },
            ) {
                Text(
                    text = file.path.substringAfterLast('/'),
                    color = Color(0xFF9CC4FF),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    textDecoration = TextDecoration.Underline,
                )
                file.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Color(0xFF8A93A5), fontSize = 11.sp, lineHeight = 14.sp)
                }
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
