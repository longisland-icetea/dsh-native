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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Where the harness lives. LAN cleartext only, by design. */
data class DshEndpoint(val host: String, val port: Int = 3080) {
    val httpBase get() = "http://$host:$port"
    val wsUrl get() = "ws://$host:$port${DshWire.MUX_PATH}"

    companion object {
        /** Accepts `host`, `host:port`, or a pasted URL; rejects anything but http/ws. */
        fun parse(input: String): DshEndpoint? {
            val trimmed = input.trim().removeSuffix("/")
            if (trimmed.isEmpty()) return null
            val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
            val url = runCatching { withScheme.toHttpUrl() }.getOrNull() ?: return null
            if (url.scheme != "http") return null
            return DshEndpoint(url.host, if (url.port != 0) url.port else 3080)
        }
    }
}

class DshException(message: String, cause: Throwable? = null) : IOException(message, cause)

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

    /** Suspend until this socket generation ends. */
    private suspend fun runSocket() = suspendCancellableCoroutine<Unit> { cont ->
        val myGeneration = generation
        val request = Request.Builder().url(endpoint.wsUrl).build()
        val live = HashMap<String, Channel<MuxFrame>>()

        fun finish(cause: Throwable?) {
            synchronized(streamLock) {
                live.values.forEach { it.close(cause) }
                live.clear()
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
                synchronized(streamLock) {
                    live[frame.streamId]?.trySend(frame)
                    if (frame is MuxFrame.End || frame is MuxFrame.Failure) live.remove(frame.streamId)
                }
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
    private var activeStreams: HashMap<String, Channel<MuxFrame>>? = null

    // ── unary ─────────────────────────────────────────────────────────────────

    /** One `POST /api/<method>`; throws [DshException] on transport or host error. */
    suspend fun call(method: String, args: JsonObject): JsonElement {
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
            val text = decodeBody(raw).toString(Charsets.UTF_8)
            val parsed = json.decodeFromString<RpcResponse>(text)
            if (parsed.rpcId != rpcId) throw DshException("$method: rpcId mismatch")
            val result = parsed.result
            if (!result.ok) throw DshException("$method: ${result.error ?: "unknown error"}")
            return result.value ?: JsonPrimitive("")
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

    suspend fun prompt(sessionId: String, text: String) {
        promptContent(sessionId, listOf(PromptContent("text", text)))
    }

    suspend fun promptContent(sessionId: String, content: List<PromptContent>) {
        val args = buildJsonObject {
            put(
                "request",
                json.encodeToJsonElement(
                    PromptRequest.serializer(),
                    PromptRequest(requestId = UUID.randomUUID().toString(), sessionId = sessionId, content = content),
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
     * Read one text file through the Host, for the deliverable preview.
     *
     * The argument names come from the endpoint descriptor: the scope is
     * `workspaceFileScopeId` (not `workspaceFileScope`, which the wire rejects),
     * and `range` is required even though it may be empty -- omitting it fails
     * with `gateway/arguments-invalid`.
     */
    suspend fun readWorkspaceFile(sessionId: String, path: String): String {
        val args = buildJsonObject {
            put("workspaceFileScopeId", sessionId)
            put("path", path)
            put("range", buildJsonObject { })
        }
        val value = call("workspaceFiles/read", args)
        val obj = value as? JsonObject ?: throw DshException("read: unexpected result")
        return obj["text"]?.jsonPrimitive?.contentOrNull
            ?: throw DshException("read: no text in result")
    }

    /**
     * Read one file's raw bytes, base64 over the wire.
     *
     * `read` refuses anything that is not UTF-8 -- which is correct for a text
     * endpoint and useless for a deliverable, since the deliverables worth
     * looking at are frequently figures. This path has no decoding and no
     * rejection, so an image can be rendered from it.
     */
    suspend fun readWorkspaceBytes(sessionId: String, path: String): ByteArray {
        val args = buildJsonObject {
            put("workspaceFileScopeId", sessionId)
            put("path", path)
            put("range", buildJsonObject { })
        }
        val value = call("workspaceFiles/readBytes", args)
        val obj = value as? JsonObject ?: throw DshException("readBytes: unexpected result")
        val data = obj["data"]?.jsonPrimitive?.contentOrNull
            ?: throw DshException("readBytes: no data in result")
        return android.util.Base64.decode(data, android.util.Base64.DEFAULT)
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
    suspend fun runCommand(sessionId: String, line: String) {
        val args = buildJsonObject {
            put("agentId", JsonPrimitive(sessionId))
            put("line", JsonPrimitive(line))
        }
        call("commands/execute", args)
    }

    /** Archive one session; the Host answers with the complete resulting set. */
    suspend fun archiveSession(sessionId: String): List<String> {
        val args = buildJsonObject {
            put(
                "request",
                json.encodeToJsonElement(ArchiveSessionRequest.serializer(), ArchiveSessionRequest(sessionId)),
            )
        }
        val value = call("workspace/archiveSession", args)
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
        val channel = Channel<MuxFrame>(capacity = 128)
        // Register before sending: otherwise a snapshot that arrives during the
        // send has no channel to land in and the conversation opens empty.
        val paired = synchronized(streamLock) {
            val table = activeStreams
            val socket = activeSocket
            if (table == null || socket == null) null else {
                table[streamId] = channel
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
            synchronized(streamLock) { table.remove(streamId) }
            close(DshException("mux send failed"))
            return@callbackFlow
        }

        val pump = scope.launch(Dispatchers.IO) {
            for (frame in channel) {
                trySend(frame)
                if (frame is MuxFrame.End || frame is MuxFrame.Failure) break
            }
            close()
        }

        awaitClose {
            pump.cancel()
            synchronized(streamLock) { table.remove(streamId) }
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
