package io.github.longislandicetea.dshnative

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app's state does when the connection is *not* there.
 *
 * Streams are the app's live mirrors, but a stream is not a guarantee: it ends,
 * it reconnects, and the Host keeps changing while it is gone. Everything that
 * arrives as a delta therefore has to be applied as a patch to state that a
 * snapshot can also correct -- and a snapshot has to be treated as what it is,
 * a complete picture that replaces the old one instead of being merged into it.
 *
 * Each case here is a bug that reached a phone: a queue row that could never
 * leave the dock, a question card that came back twice, and a steered message
 * reported as undelivered because the reconnect could not see far enough back.
 */
class DisconnectedStateTest {
    private fun queued(id: String, text: String) = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("placement", JsonPrimitive("steering"))
        put("message", buildJsonObject {
            put("id", JsonPrimitive(id))
            put("content", buildJsonArray {
                add(buildJsonObject { put("type", JsonPrimitive("text")); put("text", JsonPrimitive(text)) })
            })
        })
    }

    private fun baseline(queues: Map<String, List<String>>) = buildJsonObject {
        put("queues", buildJsonObject {
            queues.forEach { (sessionId, texts) ->
                put(sessionId, buildJsonArray { texts.forEach { add(queued("id-$it", it)) } })
            }
        })
    }

    /**
     * The bug the phone showed: a steer claimed while the client's socket was
     * down stayed in the dock after every reconnect, because the baseline was
     * merged into the queue map instead of replacing it.
     */
    @Test
    fun a_baseline_clears_a_queue_row_the_host_no_longer_has() {
        val stale = AppState(queues = mapOf("s1" to QueueCodec.parse(buildJsonArray { add(queued("id-old", "STEER-OLD")) })))
        assertEquals(1, stale.queues.getValue("s1").size)

        val afterReconnect = stale.withControlBaseline(baseline(mapOf("s1" to emptyList())))
        assertTrue("an empty queue in the baseline is the removal", afterReconnect.queues.isEmpty())
    }

    /**
     * A card the Host does not hand back on reconnect is settled.
     *
     * Answering a question on another client settles it for everyone, and the
     * Host never replays the `cancel` that retired it -- only what is still
     * unanswered. Left on screen, that card looks answerable and its Submit does
     * nothing at all.
     */
    @Test
    fun a_card_the_host_does_not_hand_back_is_dropped() {
        fun card(id: String) = PendingInteraction(
            sessionId = "s1",
            eventId = id,
            kind = "question",
            toolName = null,
            callId = null,
            reason = null,
            choices = emptyList(),
            questions = listOf(QuestionItem(id = "q", question = "Which?")),
        )
        val held = listOf(card("still-open"), card("answered-elsewhere"))
        val afterReplay = pendingAfterReplay(held, replayed = setOf("still-open"))
        assertEquals(listOf("still-open"), afterReplay.map { it.eventId })
    }

    /** A baseline that does carry a queue is adopted as the whole truth. */
    @Test
    fun a_baseline_replaces_the_queue_it_describes() {
        val before = AppState(queues = mapOf("s1" to QueueCodec.parse(buildJsonArray { add(queued("id-old", "OLD")) })))
        val after = before.withControlBaseline(baseline(mapOf("s1" to listOf("NEW"))))
        assertEquals(listOf("NEW"), after.queues.getValue("s1").map { it.label })
    }

    /**
     * The Host replays every unanswered waterfall to a client that connects, and
     * this client re-opens `$events` on every reconnect, foreground and Refresh:
     * without dedup the same question was put on screen again and again.
     */
    @Test
    fun a_replayed_waterfall_does_not_become_a_second_card() {
        val card = PendingInteraction(
            sessionId = "s1",
            eventId = "event-1",
            kind = "question",
            toolName = null,
            callId = null,
            reason = null,
            choices = emptyList(),
            questions = listOf(QuestionItem(id = "colour", question = "Which colour?")),
        )
        val once = pendingAfter(emptyList(), card)
        val twice = pendingAfter(once, card.copy())
        assertEquals("the same call is one card", 1, twice.size)
        assertEquals("event-1", twice.single().eventId)

        val other = card.copy(eventId = "event-2")
        assertEquals("a different call is another card", 2, pendingAfter(twice, other).size)
    }
}
