package io.github.longislandicetea.dshnative

import kotlinx.serialization.Serializable

/**
 * A message this client has taken responsibility for delivering.
 *
 * The app used to treat sending as a single attempt: the HTTP call went out, and
 * if it did not come back -- a timeout, a dropped Wi-Fi association, a lift --
 * the row said "not sent" and the words were stranded there, with no retry and no
 * way to get them out again short of retyping them. On a phone, on a bad link,
 * that is the normal case rather than the exception.
 *
 * So a send is now an entry in an outbox: it is attempted, and if the attempt
 * fails in a way that could succeed later it is retried with a backoff until it
 * lands. The entry is persisted, so an app killed by the system -- which is what
 * happens to a phone in a pocket -- still sends what the reader typed when it
 * comes back.
 */
@Serializable
data class OutboxEntry(
    /** The prompt identity, which is also the row's identity and what the Host echoes back. */
    val rpcId: String,
    val sessionId: String,
    val text: String,
    /** `steer` folds into the running turn, `queue` waits for the next one. */
    val mode: String,
    /** Attempts that failed in a way worth retrying. */
    val attempts: Int = 0,
    /** The last of those failures, for the row's note. */
    val lastError: String? = null,
)

/**
 * The outbox's own decisions, kept out of the UI and the network so they can be
 * tested: how long to wait, what is worth retrying, and what to say meanwhile.
 */
object Outbox {
    private const val FIRST_DELAY_MS = 1_000L
    private const val MAX_DELAY_MS = 15_000L

    /** 1s, 2s, 4s, 8s, then every 15s: quick enough to feel immediate, slow enough to be polite. */
    fun delayMillis(attempts: Int): Long {
        var delay = FIRST_DELAY_MS
        repeat(attempts.coerceIn(0, 8)) { delay = (delay * 2).coerceAtMost(MAX_DELAY_MS) }
        return delay
    }

    /**
     * Whether another attempt could succeed.
     *
     * A Host that *answered* refused this message: no amount of waiting changes
     * that, and retrying would only hide the refusal behind a spinner. Everything
     * else -- a timeout, a refused connection, a socket reset -- is the network,
     * which is exactly what a weak link does and what the outbox exists for.
     */
    fun worthRetrying(error: Throwable): Boolean = error !is HostRefused

    /**
     * What a queued row says while it is still being tried.
     *
     * Deliberately not a failure: the message is on its way, slowly. The attempt
     * count is worth showing because "sending…" that lasts a minute is otherwise
     * indistinguishable from a client that has given up.
     */
    fun note(entry: OutboxEntry): String? = when {
        entry.attempts == 0 -> null
        else -> "no connection yet — retrying (attempt ${entry.attempts})"
    }
}

/** The outbox as it is written to the view store, and read back. */
object OutboxCodec {
    fun encode(entries: List<OutboxEntry>): String =
        DshWire.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(OutboxEntry.serializer()), entries)

    /** A blob this build cannot read yields nothing rather than a crash on start. */
    fun decode(text: String?): List<OutboxEntry> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            DshWire.json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(OutboxEntry.serializer()),
                text,
            )
        }.getOrDefault(emptyList())
    }
}
