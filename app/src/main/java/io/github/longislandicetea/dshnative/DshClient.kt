package io.github.longislandicetea.dshnative

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartReader
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The logical streams one socket carries, and the rules for ending them.
 *
 * Extracted from the socket listener because the rules here decide whether a
 * stream can ever recover, and getting them wrong is invisible. The bug this
 * class exists for: a stream's last frame was handed over with `trySend`, whose
 * failure was ignored, and the stream was then dropped from the table *without
 * closing its channel*. `trySend` fails when the buffer is full, so under a
 * burst the `end` frame was discarded, the collector was left waiting on a
 * channel nothing would ever feed or close, and the socket teardown that should
 * have released it only closes the channels still in the table -- which this one
 * was not. The stream was then dead for the life of the app: no queue rows, no
 * question cards, no error, no retry.
 */
internal class MuxStreams {
    private val table = HashMap<String, Channel<MuxFrame>>()
    private val names = LinkedHashSet<String>()

    fun register(streamId: String, channel: Channel<MuxFrame>) {
        table[streamId] = channel
    }

    /** Note which endpoint a stream id belongs to, so liveness can be reported per name. */
    fun name(streamId: String, endpoint: String) {
        names += endpoint
    }

    fun forget(streamId: String) {
        table.remove(streamId)
    }

    /**
     * Hand one frame to its stream.
     *
     * An `end` or an `error` is the stream's last frame, so the channel closes
     * with it: the collector's flow then completes and `resubscribe` re-opens
     * the stream. Closing keeps whatever is already buffered readable, so the
     * end frame itself is still delivered.
     */
    fun deliver(frame: MuxFrame) {
        val channel = table[frame.streamId] ?: return
        channel.trySend(frame)
        if (frame is MuxFrame.End || frame is MuxFrame.Failure) {
            table.remove(frame.streamId)
            channel.close()
        }
    }

    /** The socket is gone: end every stream on it, so every collector can retry. */
    fun closeAll(cause: Throwable?) {
        table.values.forEach { it.close(cause) }
        table.clear()
    }

    /** Streams still registered; a test reads this to prove none is left dangling. */
    val size: Int get() = table.size

    /** The endpoints currently open, for the liveness log. */
    fun names(): Set<String> = names.toSet()
}

/** Where the harness lives. LAN cleartext only, by design. */
data class DshEndpoint(val host: String, val port: Int = 3080) {
    val httpBase get() = "http://$host:$port"
    val wsUrl get() = "ws://$host:$port${DshWire.MUX_PATH}"

    companion object {
        /** Accepts `host`, `host:port`, or a pasted URL; rejects anything but http/ws. */
        fun parse(input: String): DshEndpoint? {
            val trimmed = input.trim().removeSuffix("/")
            if (trimmed.isEmpty()) return null
            // Whether a scheme was typed decides how to read a missing port.
            // Prepending `http://` unconditionally made a bare host resolve to
            // OkHttp's default port for http -- 80 -- so `192.168.1.20` silently
            // became `192.168.1.20:80` instead of the harness's 3080.
            val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
            val url = runCatching { withScheme.toHttpUrl() }.getOrNull() ?: return null
            if (url.scheme != "http") return null
            // OkHttp fills in 80 for `http://` with no port, so the *typed* address
            // decides: a port someone wrote is honoured, otherwise this client's
            // default applies -- `http://host` means the harness, not port 80.
            val port = if (hasExplicitPort(trimmed)) url.port else DEFAULT_PORT
            return DshEndpoint(url.host, port)
        }

        /** The harness's port, and the default a typed address gets. */
        const val DEFAULT_PORT = 3080

        /**
         * Whether the address names a port itself.
         *
         * A trailing `:digits` after the host. An IPv6 literal in brackets is
         * skipped so its own colons are not mistaken for a port separator.
         */
        private fun hasExplicitPort(address: String): Boolean {
            val afterHost = address.substringAfterLast(']', address)
            val colon = afterHost.lastIndexOf(':')
            if (colon < 0) return false
            val digits = afterHost.substring(colon + 1)
            return digits.isNotEmpty() && digits.all { it.isDigit() }
        }
    }
}

open class DshException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * How long to wait before repeating a unary call the network dropped.
 *
 * Long enough for a stale pooled connection to be out of the way, short enough
 * that the reader does not notice a pause.
 */
internal const val RETRY_AFTER_MS = 150L

/**
 * The result field `workspaceFiles/readBytes` fills with the file's bytes.
 *
 * The field is named in two places on the wire -- the JSON key holding the null
 * placeholder and the attachment path saying which part fills it -- and both
 * spellings have to agree for the bytes to be found, so the name is written
 * once here.
 */
private const val DATA_FIELD = "data"

/**
 * Whether a failed unary call is worth one more attempt.
 *
 * A refusal is the Host's final word and repeating it would only delay saying
 * so; a cancellation is the caller's. Everything else -- a refused connection, a
 * timeout, a pooled connection the peer has already closed -- is the network,
 * and the same request may well work a moment later.
 */
internal fun worthRetryingUnary(error: Throwable): Boolean =
    error !is HostRefused && error !is kotlinx.coroutines.CancellationException

/**
 * Run one unary call under this client's transport policy: at most one more
 * attempt, and only for a failure the network caused.
 *
 * Separate from the call itself so the policy can be tested without a socket --
 * which also makes it honest about what it is: a decision, not a mechanism.
 */
internal suspend fun <T> withOneRetry(
    what: String,
    onRetry: (Throwable) -> Unit = {},
    block: suspend () -> T,
): T {
    var last: Throwable? = null
    repeat(2) { attempt ->
        try {
            return block()
        } catch (error: Throwable) {
            if (!worthRetryingUnary(error)) throw error
            last = error
            if (attempt == 0) {
                onRetry(error)
                delay(RETRY_AFTER_MS)
            }
        }
    }
    throw last ?: DshException("$what failed")
}

/**
 * The Host answered, and the answer was a refusal.
 *
 * Kept distinct from every other failure on purpose: a timeout, a refused
 * connection or a reset socket is the network, and the same request may well
 * succeed in a moment -- which is what the outbox is for -- while a refusal is
 * the Host's final word, and retrying it would only hide it behind a spinner.
 */
class HostRefused(message: String) : DshException(message)

/**
 * The wire shape of `commands/execute`.
 *
 * All three fields are required by the gateway's descriptor, `submittedAttachments`
 * included even when there are none: leaving it out made the gateway reject
 * *every* command with `arguments-invalid`, so `/compact` and its siblings did
 * nothing at all on the phone while the log said only that a command had failed.
 * Pure, and separate, because the bug was a missing field rather than bad logic.
 */
internal fun commandExecuteArgs(sessionId: String, line: String): JsonObject = buildJsonObject {
    put("agentId", JsonPrimitive(sessionId))
    put("line", JsonPrimitive(line))
    put("submittedAttachments", buildJsonArray { })
}

/**
 * The args `workspaceFiles/readBytes` takes.
 *
 * Named here, apart from the call, for the same reason `commands/execute`'s
 * are: the failure this exists to prevent is a *name*, not logic. The gateway
 * checks a call's fields against the endpoint's descriptor before running it, so
 * a window spelled `range` -- which is what 0.1.5 called it, and what this
 * client sent -- is refused outright by the 0.1.7 Host with
 * `missing "options"; unexpected "range"`, and the preview sheet shows a
 * protocol error where the figure should be.
 *
 * The empty window is the whole file: both fields are optional, and both
 * omitted is what "read me this picture" means.
 */
internal fun byteReadArgs(sessionId: String, path: String): JsonObject = buildJsonObject {
    put("workspaceFileScopeId", sessionId)
    put("path", path)
    put("options", buildJsonObject { })
}

/**
 * One unary answer: the value the Host sent, and any binary it sent beside it.
 *
 * Both halves are kept because either may be the point -- a caller that reads
 * JSON wants [value], and a caller that asked for a figure reads its bytes out
 * of [bytesAt] -- and because the JSON alone cannot represent the second.
 */
internal class RpcAnswer(
    val value: JsonElement?,
    private val parts: Map<String, ByteArray>,
    private val refs: List<RpcAttachmentRef>,
) {
    /**
     * The bytes the Host put at one field of the value, or null when it sent
     * none there.
     *
     * A declared attachment whose part is missing from the body is a null too:
     * the envelope said where the bytes belong and the body did not carry them,
     * which is a truncated answer, and the caller reports it as no bytes rather
     * than as an empty file.
     */
    fun bytesAt(path: List<String>): ByteArray? =
        refs.firstOrNull { it.path == path }?.let { parts[it.part] }
}

/**
 * The named parts of one `multipart/form-data` body.
 *
 * OkHttp's own delimiter scan rather than a search for the boundary bytes: the
 * boundary is the Host's to choose, the parts are binary, and finding where one
 * ends is precisely what a real parser is for. A file whose own bytes happened
 * to contain the boundary would break a hand-rolled scan and cannot break this
 * one, because the format forbids the boundary from appearing in a part.
 */
internal object MultipartForm {
    /** The part holding the JSON envelope; every other part is binary. */
    const val METADATA_PART = "metadata"

    fun parts(body: ByteArray, boundary: String): Map<String, ByteArray> {
        val parts = LinkedHashMap<String, ByteArray>()
        val reader = MultipartReader(Buffer().write(body), boundary)
        while (true) {
            val part = reader.nextPart() ?: break
            // The name is a part's whole identity: the envelope's attachment
            // list refers to parts by it, so a part that has none is one this
            // client could never place.
            val name = partName(part.headers["Content-Disposition"]) ?: continue
            parts[name] = part.body.readByteArray()
        }
        return parts
    }

    /** The `name` parameter of one `Content-Disposition`, quoted or bare. */
    private fun partName(header: String?): String? {
        for (parameter in header?.split(';').orEmpty()) {
            val trimmed = parameter.trim()
            if (!trimmed.startsWith("name=", ignoreCase = true)) continue
            return trimmed.substringAfter('=').trim().removeSurrounding("\"").takeIf { it.isNotEmpty() }
        }
        return null
    }
}

/**
 * A protocol-level client for one harness.
 *
 * Unary calls are independent HTTP POSTs, so they need no shared state. Streams
 * share one mux socket; a drop cancels every live stream and the socket is
 * reopened with capped backoff. Streams are *not* silently resurrected: the
 * caller re-opens with the cursor it last saw, because only it knows how far it
 * had read (this mirrors how the host's own client treats a reconnect).
 */
class DshClient(
    private val endpoint: DshEndpoint,
    private val scope: CoroutineScope,
) {
    private val http: OkHttpClient = OkHttpClient.Builder()
        // Without an explicit dispatcher OkHttp runs its callbacks on the thread
        // that created the socket. The mux WebSocket is created from the main
        // thread, so its reader loop then reads the socket on the main thread and
        // any transport error kills the process with a SocketException instead of
        // reaching the retry logic.
        .dispatcher(okhttp3.Dispatcher(java.util.concurrent.Executors.newCachedThreadPool()))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val json = DshWire.json
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _log = MutableSharedFlow<String>(replay = 60, extraBufferCapacity = 60)
    val log: SharedFlow<String> = _log.asSharedFlow()

    /** One socket generation; a new generation cancels every stream on the old one. */
    @Volatile
    private var generation = 0

    /**
     * Guards the live stream table and the socket reference that goes with it.
     * `onMessage` mutates the table from an OkHttp callback thread while
     * `openStream` mutates it from a coroutine, and it is a plain HashMap.
     */
    private val streamLock = Any()

    private var muxJob: Job? = null

    @Volatile
    private var socketCrashGuardInstalled = false

    fun start() {
        if (muxJob != null) return
        installSocketCrashGuard()
        // Dispatchers.IO, not the caller's scope: the caller passes a
        // lifecycleScope, so launching here put the WebSocket connect and its
        // reader loop on the main thread, where Android forbids socket I/O, and
        // a dropped connection killed the process with a SocketException.
        muxJob = scope.launch(Dispatchers.IO) {
            record("mux loop on ${Thread.currentThread().name}")
            var attempt = 0
            while (true) {
                val startedAt = System.currentTimeMillis()
                try {
                    runSocket()
                    attempt = 0
                } catch (error: Throwable) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    log("mux down: ${error.message ?: error::class.simpleName}")
                }
                _connected.value = false
                generation++
                // Only back off when the socket died quickly; a long-lived one
                // that just dropped reconnects immediately.
                if (System.currentTimeMillis() - startedAt < 3_000) {
                    delay(minOf(8_000L, 250L shl minOf(attempt, 5)))
                    attempt++
                }
            }
        }
    }

    fun stop() {
        muxJob?.cancel()
        muxJob = null
        _connected.value = false
    }

    /**
     * Drop the current socket, the way a phone in a lift does.
     *
     * `cancel`, not `close`: a close is a polite goodbye the reader loop treats as
     * a normal end, while a cancel is the failure a lost network actually looks
     * like. The mux loop reconnects on its own, which is the path this exists to
     * exercise -- the live harness uses it to prove that every mirror is re-read
     * after a reconnect rather than left stale.
     */
    internal fun dropSocket(): Boolean = activeSocket?.cancel() != null

    /** Suspend until this socket generation ends. */
    private suspend fun runSocket() = suspendCancellableCoroutine<Unit> { cont ->
        val myGeneration = generation
        val request = Request.Builder().url(endpoint.wsUrl).build()
        val live = MuxStreams()

        fun finish(cause: Throwable?) {
            synchronized(streamLock) {
                live.closeAll(cause)
                if (activeStreams === live) {
                    activeStreams = null
                    activeSocket = null
                }
            }
            if (cont.isActive) {
                if (cause == null) cont.resume(Unit) else cont.resumeWithException(cause)
            }
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                response.close()
                // Publish the stream table only once the socket can carry frames,
                // so a stream can never be registered against a dead generation.
                if (myGeneration == generation) {
                    synchronized(streamLock) {
                        activeSocket = webSocket
                        activeStreams = live
                    }
                    _connected.value = true
                    log("mux connected (${endpoint.wsUrl})")
                } else {
                    webSocket.close(1000, "superseded")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (myGeneration != generation) return
                val frame = MuxFrames.parse(text) ?: return
                synchronized(streamLock) { live.deliver(frame) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                response?.close()
                if (myGeneration != generation) return
                _connected.value = false
                finish(t)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (myGeneration != generation) return
                _connected.value = false
                finish(DshException("mux closed: $code $reason"))
            }
        }

        val socket = http.newWebSocket(request, listener)

        cont.invokeOnCancellation {
            runCatching { socket.close(1000, "cancelled") }
            finish(kotlinx.coroutines.CancellationException("socket cancelled"))
        }
    }

    /**
     * Keep a dropped connection from killing a foreground app.
     *
     * A phone loses its network constantly -- a lift, a screen lock, a router
     * reboot, the host restarting. Every one of those surfaces here as a
     * [java.net.SocketException] on whichever worker thread happened to be
     * reading the socket, and an uncaught one kills the process even though the
     * mux loop has already logged the drop and is about to reconnect.
     *
     * The filter is the exception class, not the thread: an [IOException] from a
     * socket is exactly the failure this client exists to ride out. Anything
     * else -- a `NullPointerException`, an `IllegalStateException`, a bad cast --
     * still reaches the default handler and crashes loudly, because those are
     * bugs and hiding them would cost more than the crash.
     */
    private fun installSocketCrashGuard() {
        if (socketCrashGuardInstalled) return
        socketCrashGuardInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val transientNetwork = error is IOException || error.cause is IOException
            if (transientNetwork) {
                record("ignored socket failure on ${thread.name}: ${error.message ?: error::class.simpleName}")
            } else {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    @Volatile
    private var activeSocket: WebSocket? = null

    @Volatile
    private var activeStreams: MuxStreams? = null

    /**
     * When each named stream last delivered a frame.
     *
     * A weak link's worst state is not a failure but a silence: the socket is
     * still up, the banner still says connected, and nothing arrives. Ages are
     * the only honest way to see that, and they are cheap -- one timestamp per
     * frame -- so they are kept for the transport log rather than guessed at.
     */
    private val lastFrameAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Seconds since each stream last delivered, for the log: `events 1.2s control 0.3s`. */
    fun streamAges(): String {
        val now = System.currentTimeMillis()
        val open = synchronized(streamLock) { activeStreams?.names().orEmpty() }
        if (open.isEmpty()) return "no streams open"
        return open.sorted().joinToString(" ") { name ->
            val age = lastFrameAt[name]?.let { (now - it) / 1000.0 } ?: null
            "$name ${age?.let { "%.1fs".format(it) } ?: "never"}"
        }
    }

    // ── unary ─────────────────────────────────────────────────────────────────

    /**
     * One `POST /api/<method>`; throws [DshException] on transport or host error.
     *
     * A transport failure is tried once more, because on a weak link the most
     * common failure is not an outage at all: it is a pooled connection the peer
     * has already closed, which surfaces as "unexpected end of stream" on a
     * request that would have worked a moment later. One silent retry turns that
     * from an error the reader sees into a request that took a moment longer.
     *
     * Only transport failures: a refusal from the Host is its final word, and
     * repeating it would delay saying so. Every call this client makes is safe to
     * repeat -- the Host deduplicates a prompt by its `requestId` -- so the retry
     * cannot cause two of anything.
     */
    suspend fun call(method: String, args: JsonObject): JsonElement =
        callOrNull(method, args) ?: JsonPrimitive("")

    /**
     * The same call, keeping "the Host sent no value" distinct from a value.
     *
     * `commands/execute` answers an unrecognised line with a bare `{ok: true}`,
     * and that absence *is* the answer: the line was not a command, so the caller
     * may send it as a message instead. Folding it into an empty value -- which
     * the plain [call] does -- threw exactly that distinction away.
     */
    suspend fun callOrNull(method: String, args: JsonObject): JsonElement? = callAnswer(method, args).value

    /**
     * The same call, keeping the binary parts an answer may carry beside its value.
     *
     * A result field the Host has no JSON spelling for -- a file's bytes -- comes
     * back as a part of a `multipart/form-data` response, with the field left
     * `null` in the JSON and an `attachments` entry saying which part fills it.
     * Every other call still answers plain JSON, so this is the same request; only
     * the decoding differs.
     */
    private suspend fun callAnswer(method: String, args: JsonObject): RpcAnswer = withOneRetry(
        what = method,
        onRetry = { error -> log("$method: ${error.message ?: error::class.simpleName}; retrying once") },
    ) {
        callOnce(method, args)
    }

    private suspend fun callOnce(method: String, args: JsonObject): RpcAnswer {
        val rpcId = UUID.randomUUID().toString()
        val body = json.encodeToString(RpcRequest(rpcId = rpcId, method = method, payload = RpcPayload(args)))
        val request = Request.Builder()
            .url("${endpoint.httpBase}${DshWire.API_PREFIX}/$method")
            .post(body.toRequestBody(jsonMedia))
            .build()
        val response = http.newCall(request).await()
        response.use {
            if (!it.isSuccessful) throw HostRefused("$method: HTTP ${it.code}")
            val raw = it.body?.bytes() ?: throw DshException("$method: empty body")
            val decoded = decodeBody(raw)
            // The content type, not the bytes, says which envelope this is: a
            // multipart body can begin with anything, since the boundary is the
            // Host's to choose.
            val boundary = it.body?.contentType()
                ?.takeIf { type -> type.type == "multipart" && type.subtype == "form-data" }
                ?.parameter("boundary")
            val parts: Map<String, ByteArray>
            val text: String
            if (boundary == null) {
                parts = emptyMap()
                text = decoded.toString(Charsets.UTF_8)
            } else {
                parts = MultipartForm.parts(decoded, boundary)
                text = parts[MultipartForm.METADATA_PART]?.toString(Charsets.UTF_8)
                    ?: throw DshException("$method: a multipart answer with no ${MultipartForm.METADATA_PART} part")
            }
            val parsed = json.decodeFromString<RpcResponse>(text)
            if (parsed.rpcId != rpcId) throw DshException("$method: rpcId mismatch")
            val result = parsed.result
            if (!result.ok) throw HostRefused("$method: ${result.error ?: "unknown error"}")
            return RpcAnswer(result.value, parts, RpcAttachmentCodec.refs(parsed.attachments))
        }
    }

    suspend fun listSessions(): List<SessionSummary> = listSessionsDetailed().first

    /**
     * `session/list` plus the decoded body size.
     *
     * The size is not decoration: when the list comes back empty the first
     * question is whether the body arrived at all and whether it was the JSON
     * this client expects. A failed decode now reports the first bytes instead
     * of an empty list.
     */
    suspend fun listSessionsDetailed(): Pair<List<SessionSummary>, Int> {
        val body = callRaw("session/list", buildJsonObject { put("_request", buildJsonObject { }) })
        val text = body.toString(Charsets.UTF_8)
        // Report the leading bytes in hex as well: the difference between
        // "an envelope this client mis-parses" and "compressed bytes that were
        // never decoded" is 1f 8b, and the summary line alone cannot show it.
        val head = body.take(16).joinToString(" ") { "%02x".format(it) }
        val parsed = runCatching { json.decodeFromString<RpcResponse>(text) }
            .getOrElse { error ->
                throw DshException(
                    "session/list envelope decode failed (${body.size}B, head=[$head], text=${text.take(80)}): ${error.message}",
                    error,
                )
            }
        val result = parsed.result
        if (!result.ok) throw DshException("session/list refused: ${result.error}")
        val value = result.value ?: throw DshException("session/list returned no value")
        val sessions = SessionListCodec.parse(value)
        if (sessions.isEmpty()) {
            throw DshException("session/list decoded 0 items from ${body.size}B (head=[$head]): ${text.take(80)}")
        }
        return sessions to body.size
    }

    /** One POST, returning the decoded body bytes. */
    private suspend fun callRaw(method: String, args: JsonObject): ByteArray {
        val rpcId = UUID.randomUUID().toString()
        val body = json.encodeToString(RpcRequest(rpcId = rpcId, method = method, payload = RpcPayload(args)))
        val request = Request.Builder()
            .url("${endpoint.httpBase}${DshWire.API_PREFIX}/$method")
            .post(body.toRequestBody(jsonMedia))
            .build()
        val response = http.newCall(request).await()
        response.use {
            if (!it.isSuccessful) throw DshException("$method: HTTP ${it.code}")
            val raw = it.body?.bytes() ?: throw DshException("$method: empty body")
            return decodeBody(raw)
        }
    }

    /**
     * Send one prompt.
     *
     * `mode` decides what happens when the agent is busy: `steer` folds the text
     * into the running turn, `queue` waits for the next one. The composer steers
     * (a correction should land while the agent is still working) and the queue
     * dock's re-send queues, so the choice belongs to the caller.
     */
    suspend fun prompt(
        sessionId: String,
        text: String,
        mode: String = "steer",
        /**
         * The prompt's identity. The caller may mint it so it can recognise its
         * own message in the Host's queue and log -- the Host echoes this value
         * on `source.rpcId` and on the pending inbox row -- and a reconnect
         * that replays the prompt's effects still matches what it sent.
         */
        requestId: String = UUID.randomUUID().toString(),
    ) {
        promptContent(sessionId, listOf(PromptContent("text", text)), mode, requestId)
    }

    suspend fun promptContent(
        sessionId: String,
        content: List<PromptContent>,
        mode: String = "steer",
        requestId: String = UUID.randomUUID().toString(),
    ) {
        val args = buildJsonObject {
            put(
                "request",
                json.encodeToJsonElement(
                    PromptRequest.serializer(),
                    PromptRequest(
                        requestId = requestId,
                        sessionId = sessionId,
                        mode = mode,
                        content = content,
                    ),
                ),
            )
        }
        call("session/prompt", args)
    }

    suspend fun cancel(sessionId: String) {
        val args = buildJsonObject {
            put("request", json.encodeToJsonElement(CancelRequest.serializer(), CancelRequest(sessionId)))
        }
        call("session/cancel", args)
    }

    // ── streams ───────────────────────────────────────────────────────────────

    /**
     * Decode a response body, gunzipping it when it is still compressed.
     *
     * OkHttp advertises gzip and normally decodes transparently, so the usual
     * path is a plain pass-through. A body that still starts with the gzip
     * magic number means that did not happen (an intermediary stripped
     * `Content-Encoding`, a cached response, or a negotiation quirk), and
     * handing compressed bytes to a JSON parser is exactly the failure this
     * client kept hitting. Decompressing here costs one branch and removes the
     * whole class of failure.
     */
    private fun decodeBody(raw: ByteArray): ByteArray {
        if (raw.size < 2 || raw[0] != 0x1f.toByte() || raw[1] != 0x8b.toByte()) return raw
        return runCatching {
            java.util.zip.GZIPInputStream(raw.inputStream()).use { it.readBytes() }
        }.getOrElse { raw }
    }

    /**
     * Follow one session: the first frame is a snapshot carrying `cursor` plus
     * the newest records, then durable events and live assistant chunks.
     */
    /**
     * Forwarded Host events: the opening `ready` frame, notifications, and the
     * agent-scoped waterfalls that carry approval prompts and user questions.
     * A client that never opens this stream cannot be asked anything.
     */
    fun events(): Flow<MuxFrame> = openStream(DshWire.EVENT_STREAM_ENDPOINT, buildJsonObject { })

    /**
     * Workspace browser state: the baseline carries every Workspace with its
     * canonical session order plus the Host's archive set, then ordered
     * increments follow. This is the Host's own grouping, not a local
     * derivation, so titles and ordering match the desktop.
     */
    fun workspaces(): Flow<MuxFrame> = openStream("workspace/follow", buildJsonObject { })

    /** Every routable provider, its models, and their reasoning efforts. */
    /**
     * Read one page of a text file through the Host, for the deliverable preview.
     *
     * The argument names come from the endpoint descriptor: the scope is
     * `workspaceFileScopeId` (not `workspaceFileScope`, which the wire rejects),
     * and `range` is required even though it may be empty -- omitting it fails
     * with `gateway/arguments-invalid`.
     *
     * The result is the whole page rather than its text, because the text alone
     * cannot say whether the Host reached the end of the file. It frequently
     * does not: the read is cut at 5000 lines and 2 MiB by deployment default,
     * and a reader shown a cut page with no marker has no way to tell it from a
     * complete document.
     */
    suspend fun readWorkspaceFile(sessionId: String, path: String): WorkspaceFilePage {
        val args = buildJsonObject {
            put("workspaceFileScopeId", sessionId)
            put("path", path)
            put("range", buildJsonObject { })
        }
        val value = call("workspaceFiles/read", args)
        return WorkspaceFileCodec.readPage(value)
            ?: throw DshException("read: no text in result")
    }

    /**
     * Read one file's raw bytes.
     *
     * `read` refuses anything that is not UTF-8 -- which is correct for a text
     * endpoint and useless for a deliverable, since the deliverables worth
     * looking at are frequently figures. This path has no decoding and no
     * rejection, so an image can be rendered from it.
     *
     * The bytes do not come back inside the JSON: the value carries
     * `"data": null` and an `attachments` entry names the part of the multipart
     * body that fills it (`[{path:["data"],codec:"bytes",part:"bytes-0"}]`,
     * captured from a 0.1.7 Host), which is what [RpcAnswer.bytesAt] puts back
     * together. A 0.1.5 Host answered with the same window base64-encoded inside
     * `data` -- and renamed the window's argument in the release that moved the
     * bytes out -- so a call shaped for one Host is refused by the other. This
     * speaks the Host it is pointed at rather than guessing from an empty field.
     */
    suspend fun readWorkspaceBytes(sessionId: String, path: String): ByteArray {
        val answer = callAnswer("workspaceFiles/readBytes", byteReadArgs(sessionId, path))
        return answer.bytesAt(listOf(DATA_FIELD))
            ?: throw DshException("readBytes: the result carried no bytes for \"$DATA_FIELD\"")
    }

    /**
     * Create a session and return its id.
     *
     * `workspaceId` and `cwd` are alternatives, not a pair: sending both is
     * rejected with `gateway/bad-request`, and a workspace id already implies the
     * directory. An empty request creates the session in the process default
     * directory.
     */
    suspend fun createSession(workspaceId: String?): String {
        val args = buildJsonObject {
            // The descriptor declares `request` required, so it is always sent,
            // possibly empty.
            put(
                "request",
                buildJsonObject {
                    workspaceId?.let { put("workspaceId", JsonPrimitive(it)) }
                },
            )
        }
        val value = call("session/create", args)
        val obj = value as? JsonObject ?: throw DshException("session/create: unexpected result")
        return obj["sessionId"]?.jsonPrimitive?.contentOrNull
            ?: throw DshException("session/create: no sessionId in result")
    }

    suspend fun modelCatalog(): ModelCatalog {
        // The descriptor declares no parameters; sending a `request` field is
        // rejected with gateway/arguments-invalid.
        val value = call("session/modelCatalog", buildJsonObject { })
        return runCatching {
            json.decodeFromJsonElement(ModelCatalog.serializer(), value)
        }.getOrDefault(ModelCatalog())
    }

    /** Switch the session's model and reasoning effort. */
    suspend fun selectModel(
        sessionId: String,
        provider: String,
        model: String,
        reasoningEffort: String?,
    ): ModelSelection {
        val request = SelectModelRequest(sessionId, provider, model, reasoningEffort)
        val args = buildJsonObject {
            put("request", json.encodeToJsonElement(SelectModelRequest.serializer(), request))
        }
        val value = call("session/selectModel", args)
        return runCatching {
            json.decodeFromJsonElement(SelectModelValue.serializer(), value).selected
        }.getOrDefault(ModelSelection(provider, model, reasoningEffort))
    }

    /**
     * Run a slash command.
     *
     * Commands are not a separate transport: the Host executes the line against
     * the session's agent. Compaction is therefore reachable as a command line,
     * and its effect arrives as ordinary compaction events on the follow stream.
     */
    suspend fun runCommand(sessionId: String, line: String): Boolean =
        callOrNull("commands/execute", commandExecuteArgs(sessionId, line)) != null

    /**
     * Live Host-wide control state: pending projections and usage.
     *
     * One generation starts with a `baseline` frame carrying a complete snapshot
     * per session -- the projection bag, including the `inbox` the queue dock is
     * drawn from -- then frames that replace one projection of it. This is where
     * the queue and the usage numbers come from: `session/follow` carries the
     * transcript, not the control state, and polling the session list for them
     * would miss every change inside a turn.
     */
    fun control(): Flow<MuxFrame> = openStream("session/control", buildJsonObject { })

    /**
     * The background-job roster one session can see, as whole-set frames.
     *
     * Its own stream since 0.1.7: the control baseline used to carry a `jobs` map
     * beside the projections, and the roster now arrives over `job/list`, scoped
     * to a session -- its own jobs plus every unowned one. One frame is the whole
     * set, so a reconnect's first frame is already the truth.
     */
    fun jobs(sessionId: String): Flow<MuxFrame> = openStream(
        "job/list",
        buildJsonObject {
            put("request", buildJsonObject { put("sessionId", JsonPrimitive(sessionId)) })
        },
    )

    /**
     * Every command the Host offers this session.
     *
     * Takes the session id as `agentId`, which is the descriptor's field name --
     * the same identity the prompt and cancel calls use under a different name.
     */
    suspend fun listCommands(sessionId: String): List<CommandInfo> {
        val value = call("commands/list", buildJsonObject { put("agentId", JsonPrimitive(sessionId)) })
        return CommandCodec.parse(value)
    }

    /**
     * Mutate one still-pending queue item: edit, remove, or steer it.
     *
     * The action is the Host's own `QueueAction` shape. A rejected mutation comes
     * back as an error rather than a silent no-op, which is what the queue row
     * shows instead of pretending the change landed.
     */
    suspend fun updateQueue(sessionId: String, itemId: String, action: JsonObject) {
        val args = buildJsonObject {
            put(
                "request",
                buildJsonObject {
                    put("sessionId", JsonPrimitive(sessionId))
                    put("itemId", JsonPrimitive(itemId))
                    put("action", action)
                },
            )
        }
        call("session/updateQueue", args)
    }

    /** Archive one session; the Host answers with the complete resulting set. */
    suspend fun archiveSession(sessionId: String): List<String> = archive("workspace/archiveSession", sessionId)

    /**
     * Restore one archived session, which the Host added in 0.1.7.
     *
     * Worth knowing what archiving *means* on that Host: an archived session may
     * not run a model step until it is restored (the controller's archived-session
     * gate), so a prompt to one is admitted and then ends as `blocked` without a
     * request. Nothing this app does depends on that -- it has no unarchive of its
     * own -- but a client that prompts an archived session and waits for a turn
     * waits forever.
     */
    suspend fun unarchiveSession(sessionId: String): List<String> = archive("workspace/unarchiveSession", sessionId)

    private suspend fun archive(method: String, sessionId: String): List<String> {
        val args = buildJsonObject {
            put(
                "request",
                json.encodeToJsonElement(ArchiveSessionRequest.serializer(), ArchiveSessionRequest(sessionId)),
            )
        }
        val value = call(method, args)
        return runCatching {
            json.decodeFromJsonElement(ArchiveValue.serializer(), value).archivedSessionIds
        }.getOrDefault(emptyList())
    }

    /**
     * Answer one waterfall. The value is the listener's return value on the
     * Host side, which is why the approval decision is a plain string such as
     * `allowed-once`; `$events/result` is the carrier for it.
     */
    suspend fun answerWaterfall(clientId: String, eventId: String, value: JsonElement?) {
        val outcome = buildJsonObject {
            put("kind", JsonPrimitive("result"))
            if (value != null) put("value", value)
        }
        val args = buildJsonObject {
            put("clientId", JsonPrimitive(clientId))
            put("eventId", JsonPrimitive(eventId))
            put("outcome", outcome)
        }
        call("\$events/result", args)
    }

    /** Decline a waterfall so the Host can fall through to its next listener. */
    suspend fun delegateWaterfall(clientId: String, eventId: String) {
        val args = buildJsonObject {
            put("clientId", JsonPrimitive(clientId))
            put("eventId", JsonPrimitive(eventId))
            put("outcome", buildJsonObject { put("kind", JsonPrimitive("next")) })
        }
        call("\$events/result", args)
    }

    fun follow(sessionId: String, maxMessages: Int = 50): Flow<MuxFrame> {
        val args = buildJsonObject {
            put(
                "request",
                json.encodeToJsonElement(
                    FollowRequest.serializer(),
                    FollowRequest(
                        address = SessionAddress(sessionId = sessionId),
                        maxMessages = maxMessages,
                        assistantStream = true,
                    ),
                ),
            )
        }
        return openStream("session/follow", args)
    }

    /**
     * Page strictly older history. `beforeSeq` is the oldest seq already held and
     * `throughSeq` is the snapshot cursor, so a page is bounded on both ends.
     */
    suspend fun pageOlder(
        sessionId: String,
        throughSeq: Long,
        beforeSeq: Long?,
        maxMessages: Int = 50,
    ): List<SessionEvent> {
        val args = buildJsonObject {
            put(
                "request",
                json.encodeToJsonElement(
                    PageRequest.serializer(),
                    PageRequest(
                        address = SessionAddress(sessionId = sessionId),
                        throughSeq = throughSeq,
                        beforeSeq = beforeSeq,
                        maxMessages = maxMessages,
                    ),
                ),
            )
        }
        val value = call("session/page", args)
        val records = (value as? JsonObject)?.get("records") as? JsonArray ?: return emptyList()
        return records.mapNotNull { element ->
            (FollowCodec.decode(element) as? FollowFrame.Event)?.event
        }
    }

    /**
     * Open one logical stream on the current socket generation.
     *
     * `callbackFlow` gives cancellation semantics for free: the host is told to
     * cancel the logical stream when the collector goes away, and a socket drop
     * closes the channel so the collector can decide to re-open.
     */
    private fun openStream(endpointName: String, args: JsonObject): Flow<MuxFrame> = callbackFlow {
        // The socket send and the channel pump must not run on the main thread
        // (Android throws NetworkOnMainThreadException on socket I/O).
        val streamId = UUID.randomUUID().toString()
        // Unbounded on purpose: this channel is fed from OkHttp's reader thread,
        // which must never block, and a frame dropped here is silent data loss
        // -- a lost `user/message` is a message that never appears, and a lost
        // `end` is a stream that never ends, so nothing ever retries it.
        val channel = Channel<MuxFrame>(capacity = Channel.UNLIMITED)
        // Register before sending: otherwise a snapshot that arrives during the
        // send has no channel to land in and the conversation opens empty.
        val paired = synchronized(streamLock) {
            val table = activeStreams
            val socket = activeSocket
            if (table == null || socket == null) null else {
                table.register(streamId, channel)
                table.name(streamId, endpointName)
                table to socket
            }
        }
        if (paired == null) {
            close(DshException("mux is not connected yet; retry once the banner clears"))
            return@callbackFlow
        }
        val (table, socket) = paired

        val opened = socket.send(openFrame(streamId, endpointName, args))
        if (!opened) {
            synchronized(streamLock) { table.forget(streamId) }
            close(DshException("mux send failed"))
            return@callbackFlow
        }

        // `send`, not `trySend`: if the collector falls behind, the pump waits
        // for it instead of dropping the frames it cannot hand over. Dropping
        // here is how a durable event went missing while the stream looked
        // healthy.
        val pump = scope.launch(Dispatchers.IO) {
            for (frame in channel) {
                lastFrameAt[endpointName] = System.currentTimeMillis()
                send(frame)
                if (frame is MuxFrame.End || frame is MuxFrame.Failure) break
            }
            close()
        }

        awaitClose {
            pump.cancel()
            synchronized(streamLock) { table.forget(streamId) }
            runCatching { socket.send("{\"type\":\"cancel\",\"streamId\":\"$streamId\"}") }
        }
    }

    private fun openFrame(streamId: String, endpointName: String, args: JsonObject): String = buildString {
        append("{\"type\":\"open\",\"streamId\":")
        append(JsonPrimitive(streamId))
        append(",\"endpoint\":")
        append(JsonPrimitive(endpointName))
        append(",\"payload\":{\"args\":")
        append(args.toString())
        append("}}")
    }

    /** Mirror one transport line into logcat, so the thread is visible. */
    private fun record(line: String) {
        android.util.Log.i("DshNative", line)
    }

    private fun log(line: String) {
        _log.tryEmit("${System.currentTimeMillis() % 1_000_000}  $line")
    }
}
