package io.github.longislandicetea.dshnative

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A message that is waiting rather than running.
 *
 * The queue is the Host's, not this client's: the session's `inbox` projection is
 * streamed whole -- on `session/control`, and in every follow snapshot -- so this
 * is a rendering of the Host's state rather than an optimistic list. That matters
 * because the Host claims an item the moment a steer window opens, and a
 * locally-maintained list would keep showing it.
 */
@Serializable
data class QueuedItem(
    val id: String,
    /**
     * The prompt identity the Host echoed back, when it has one.
     *
     * It is what lets this client recognise its *own* message in the Host's
     * queue -- the id is the Host's, minted on admission, while the rpcId is
     * what this client sent and what the durable message will carry.
     */
    val rpcId: String? = null,
    /** `queued` waits its turn; `steering` is being folded into the running turn. */
    val placement: String = "queued",
    val text: String? = null,
    val content: JsonArray? = null,
) {
    /** Whether this row is on its way into the running turn already. */
    val steering: Boolean get() = placement == "steering"

    /** What to put in the row: the message the Host is holding. */
    val label: String get() = text.orEmpty()

    /** Only text can be edited; the Host rejects anything else. */
    val editable: Boolean
        get() = text != null || (content ?: JsonArray(emptyList())).all { part ->
            (part as? JsonObject)?.get("type")?.jsonPrimitive?.content == "text"
        }

    /** Text this row contributes when editing starts. */
    val editSeed: String get() = text ?: label
}

/** The queue mutations the Host accepts (`QueueAction`). */
object QueueAction {
    fun edit(text: String) = buildJsonObject {
        put("kind", JsonPrimitive("edit"))
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(text))
            })
        })
    }

    fun remove() = buildJsonObject { put("kind", JsonPrimitive("remove")) }

    fun steer() = buildJsonObject { put("kind", JsonPrimitive("steer")) }
}

/**
 * The dock's rows for one session's pending input.
 *
 * Which list a message is in *is* its placement: `next-turn` waits for a turn of
 * its own, `next-step` is being folded into the running turn, which is where the
 * Host puts a steer. 0.1.5 said the same thing in a `placement` field on a queue
 * snapshot; 0.1.7 says it with the list, and the item is now the message itself --
 * an id, content blocks, and a `source` carrying the prompt identity this client
 * retires its own echo on.
 *
 * The steering rows come first because that is the order the Host consumes them
 * in -- it claims `next-step` before `next-turn`, so the row that is about to
 * disappear is the one at the top of the dock.
 *
 * A message with no id is dropped rather than shown broken: every action the row
 * offers names the id, so a row without one would offer buttons that cannot work.
 */
internal fun InboxProjection?.queueRows(): List<QueuedItem> {
    val inbox = this ?: return emptyList()
    return inbox.nextStep.mapNotNull { it.queued("steering") } +
        inbox.nextTurn.mapNotNull { it.queued("queued") }
}

/** One pending message as a dock row, or null when it has nothing to act on. */
private fun InboxMessage.queued(placement: String): QueuedItem? {
    if (id.isEmpty()) return null
    val text = content?.mapNotNull { part ->
        (part as? JsonObject)?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            ?.get("text")?.jsonPrimitive?.contentOrNull
    }?.joinToString("\n")?.ifBlank { null }
    return QueuedItem(id = id, rpcId = rpcId, placement = placement, text = text, content = content)
}

/**
 * The queue's own small decisions, kept out of the UI so they can be tested.
 */
object QueueView {
    /**
     * The row to show as a count when the queue is not expanded.
     *
     * Steering rows are counted with the rest: they are still not in the
     * conversation, and hiding them would make a message the reader just sent
     * vanish until it landed.
     */
    fun countLabel(items: List<QueuedItem>): String? = when (items.size) {
        0 -> null
        1 -> "1 message waiting"
        else -> "${items.size} messages waiting"
    }

    /** Whether steering is worth offering: only while a turn can still take it. */
    fun canSteer(running: Boolean, item: QueuedItem): Boolean = running && !item.steering
}
