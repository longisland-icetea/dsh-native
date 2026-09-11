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
 * The queue is the Host's, not this client's: `session/control` streams a complete
 * snapshot per session, so this is a rendering of the Host's state rather than an
 * optimistic list. That matters because the Host claims an item the moment a steer
 * window opens, and a locally-maintained list would keep showing it.
 */
@Serializable
data class QueuedItem(
    val id: String,
    /** `queued` waits its turn; `steering` is being folded into the running turn. */
    val placement: String = "queued",
    val preview: String = "",
    val text: String? = null,
    val content: JsonArray? = null,
) {
    /** Whether this row is on its way into the running turn already. */
    val steering: Boolean get() = placement == "steering"

    /** What to put in the row: the Host's preview, falling back to the text. */
    val label: String get() = preview.ifBlank { text.orEmpty() }

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
 * Decode one `session/control` queue snapshot.
 *
 * The frame carries `items[]` for a whole session, and each item's shape is the
 * controller's `SessionQueuedItem`: an id, a placement, and a `message` whose own
 * `content[]` holds the blocks. A shape this build does not recognise yields
 * nothing rather than a broken row, because a queue row the reader cannot act on
 * is worse than no row.
 */
object QueueCodec {
    fun parse(items: JsonArray?): List<QueuedItem> {
        if (items == null) return emptyList()
        return items.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val message = obj["message"] as? JsonObject
        val content = message?.get("content") as? JsonArray
        val text = content?.mapNotNull { part ->
            (part as? JsonObject)?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                ?.get("text")?.jsonPrimitive?.contentOrNull
        }?.joinToString("\n")?.ifBlank { null }
        QueuedItem(
            id = id,
            placement = obj["placement"]?.jsonPrimitive?.contentOrNull ?: "queued",
            preview = (obj["preview"] as? JsonPrimitive)?.contentOrNull
                ?: obj["previewText"]?.jsonPrimitive?.contentOrNull
                ?: message?.get("preview")?.jsonPrimitive?.contentOrNull
                ?: "",
            text = text,
            content = content,
        )
        }
    }
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
