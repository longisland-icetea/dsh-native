package io.github.longislandicetea.dshnative

import kotlinx.coroutines.CoroutineScope
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

    fun start() {
        if (muxJob != null) return
        muxJob = scope.launch {
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
            val text = it.body?.string() ?: throw DshException("$method: empty body")
            val parsed = json.decodeFromString<RpcResponse>(text)
            if (parsed.rpcId != rpcId) throw DshException("$method: rpcId mismatch")
            val result = parsed.result
            if (!result.ok) throw DshException("$method: ${result.error ?: "unknown error"}")
            return result.value ?: JsonPrimitive("")
        }
    }

    suspend fun listSessions(): List<SessionSummary> {
        val value = call("session/list", buildJsonObject { put("_request", buildJsonObject { }) })
        return SessionListCodec.parse(value)
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
     * Follow one session: the first frame is a snapshot carrying `cursor` plus
     * the newest records, then durable events and live assistant chunks.
     */
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

        val pump = scope.launch {
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

    private fun log(line: String) {
        _log.tryEmit("${System.currentTimeMillis() % 1_000_000}  $line")
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
