package io.github.longislandicetea.dshnative

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * One background job, as the Host's control stream reports it.
 *
 * Named `HostJob` because `Job` is the coroutines type this app uses everywhere
 * else, and a file that imported both would be a trap.
 *
 * The Host broadcasts a job list per session and keeps the finished ones in it,
 * so the distinction that matters is made here rather than by the wire: a reader
 * who has to open this list is asking what is *still running*, and a list of
 * everything ever run answers a different question.
 */
data class HostJob(
    val id: String,
    /** `bash`, `task`, … -- the producer's own word for what this is. */
    val kind: String,
    val label: String,
    /** `running`, `stopping`, `completed`, `killed`, `failed`. */
    val status: String,
    /** The producer's longer description, when it gave one. */
    val detail: String? = null,
    val startedAt: Long = 0,
    val finishedAt: Long? = null,
) {
    /** Whether the registry still holds this job open, so its duration ticks. */
    val live: Boolean get() = status == "running" || status == "stopping"

    /** How long it has run, or ran, in milliseconds. */
    fun elapsedMillis(now: Long = System.currentTimeMillis()): Long =
        ((finishedAt ?: now) - startedAt).coerceAtLeast(0)
}

/**
 * Jobs, and the rules for reading them.
 *
 * The status vocabulary is the Host's own closed set; the words are the web
 * client's, so the same job reads the same way in both places.
 */
object Jobs {
    /** Whether the registry still holds this status open. */
    fun isLive(status: String): Boolean = status == "running" || status == "stopping"

    /** The one-word state of a job, in the vocabulary the web client uses. */
    fun statusLabel(status: String): String = when (status) {
        "running" -> "running"
        "stopping" -> "stopping"
        "completed" -> "done"
        "killed" -> "cancelled"
        "failed" -> "failed"
        else -> status
    }

    /**
     * A duration in at most two adjacent units.
     *
     * A background job that outlives an hour is already exceptional, so hours is
     * the widest unit -- the same ceiling the web client uses.
     */
    fun formatDuration(elapsedMs: Long): String {
        val total = (elapsedMs / 1000).coerceAtLeast(0)
        val seconds = total % 60
        val minutes = (total / 60) % 60
        val hours = total / 3600
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    /** Every job the Host still holds open, oldest first. */
    fun live(jobs: List<HostJob>): List<HostJob> = jobs.filter { it.live }.sortedBy { it.startedAt }

    /**
     * The subagent sessions of one session that are running right now.
     *
     * A subagent is a child session -- the Host marks it with `origin` and names
     * its parent -- and "running" is the Host's own status for it, which is the
     * same flag the drawer's dot reads.
     */
    fun runningSubagents(sessions: List<SessionSummary>, parentSessionId: String): List<SessionSummary> =
        sessions.filter { it.parentSessionId == parentSessionId && it.running }
}

/** Decode the control stream's job lists, which arrive per session. */
object JobCodec {
    fun parse(element: JsonElement?): List<HostJob> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val id = obj.string("id") ?: return@mapNotNull null
            HostJob(
                id = id,
                kind = obj.string("kind") ?: "job",
                label = obj.string("label") ?: id,
                status = obj.string("status") ?: "running",
                detail = obj.string("detail"),
                startedAt = obj.long("startedAt") ?: 0,
                finishedAt = obj.long("finishedAt"),
            )
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeUnless { it.isEmpty() }

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}

/**
 * The connection, as a light rather than a sentence.
 *
 * Three states, and the middle one is the reason a two-state flag was not enough:
 * "not connected" covered both a client that is retrying and one that has stopped
 * trying, and those are different things to tell a reader.
 */
enum class LinkLight { DOWN, RECONNECTING, UP }

/**
 * Which light the header shows.
 *
 * [attached] is whether a client exists at all: with an endpoint configured and a
 * client running, a lost socket is a link being retried (amber); with neither, the
 * app is simply not connected to anything (red).
 */
internal fun linkLight(endpoint: DshEndpoint?, connected: Boolean, attached: Boolean): LinkLight = when {
    connected -> LinkLight.UP
    endpoint != null && attached -> LinkLight.RECONNECTING
    else -> LinkLight.DOWN
}

/** What the light says beside it, or nothing when there is nothing to say. */
internal fun linkNote(outboxSize: Int): String? = when (outboxSize) {
    0 -> null
    1 -> "1 message waiting"
    else -> "$outboxSize messages waiting"
}
