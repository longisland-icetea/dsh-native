package io.github.longislandicetea.dshnative

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a message the reader sends renders as, replayed from real Host captures.
 *
 * The fixtures are the wire itself, not a paraphrase of it:
 * `app/src/test/resources/steer-session.json` is one turn in which a steer was
 * sent mid-tool-call and then read, and `steer-removed.json` is the same shape
 * with the pending row removed before a turn could read it. Both are the exact
 * frames one phone received, in arrival order.
 *
 * These are the tests that would have caught the bug they exist for. The wire
 * carried the steered message correctly every time -- it was in the Host's inbox,
 * and later in its log -- while the app showed nothing at all until a turn read
 * it: minutes, on a long tool call, and never if the Host let the message go.
 */
class SteerDisplayTest {
    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/$name")) {
            "$name is missing from the test resources"
        }.bufferedReader().readText(),
    ).jsonObject

    private val delivered = fixture("steer-session.json")
    private val removed = fixture("steer-removed.json")

    private fun text(capture: JsonObject) = capture["steerText"]!!.jsonPrimitive.content
    private fun rpcId(capture: JsonObject) = capture["steerRpcId"]!!.jsonPrimitive.content

    /** The row this client shows the moment it sends, before any frame arrives. */
    private fun seeded(capture: JsonObject): TranscriptFold {
        val id = rpcId(capture)
        return TranscriptFold(
            Conversation(
                sessionId = capture["sessionId"]!!.jsonPrimitive.content,
                items = listOf(TranscriptItem.Pending(TranscriptItem.Pending.keyOf(id), id, text(capture))),
            ),
        )
    }

    /** Replay a capture the way the app folds the follow stream. */
    private fun replay(capture: JsonObject): TranscriptFold {
        var state = seeded(capture)
        for (frame in capture["follow"]!!.jsonArray) {
            state = foldFollowFrame(state, FollowCodec.decode(frame))
        }
        return state
    }

    private fun echoes(state: TranscriptFold) =
        state.conversation.items.filterIsInstance<TranscriptItem.Pending>()

    private fun users(state: TranscriptFold) =
        state.conversation.items.filterIsInstance<TranscriptItem.User>()

    /** The message is on screen from the moment it is sent, before any reply. */
    @Test
    fun a_sent_message_is_visible_before_the_host_reads_it() {
        var state = seeded(delivered)
        // Fold only up to the tool call the steer interrupted: no durable
        // material mentions the message yet.
        for (frame in delivered["follow"]!!.jsonArray) {
            val decoded = FollowCodec.decode(frame)
            if ((decoded as? FollowFrame.Event)?.event?.seq ?: 0L > 18L) break
            state = foldFollowFrame(state, decoded)
        }
        val echo = echoes(state).single()
        assertEquals(text(delivered), echo.text)
        assertTrue("still on its way", echo.waiting)
        assertTrue("and the Host's own inbox listed it", echo.admitted)
    }

    /** The echo is replaced by the message it becomes, not duplicated. */
    @Test
    fun the_message_replaces_its_echo_when_the_host_logs_it() {
        val state = replay(delivered)
        assertEquals("no echo survives the message it became", 0, echoes(state).size)
        assertEquals(listOf(text(delivered)), users(state).map { it.text }.filter { it == text(delivered) })
    }

    /** The steer lands in the turn it interrupted, in order, before the reply. */
    @Test
    fun the_steer_lands_where_it_happened() {
        val items = replay(delivered).conversation.items
        val steer = items.indexOfFirst { it is TranscriptItem.User && it.text == text(delivered) }
        val tool = items.indexOfFirst { it is TranscriptItem.ToolCall }
        val reply = items.indexOfFirst { it is TranscriptItem.Assistant }
        assertTrue("the steer is in the transcript", steer > 0)
        assertTrue("after the tool call it interrupted", steer > tool)
        assertTrue("and before the reply that answered it", steer < reply)
    }

    /** A message the Host discarded is not a message that is still coming. */
    @Test
    fun a_discarded_message_says_so_instead_of_waiting_forever() {
        val state = replay(removed)
        val echo = echoes(state).single()
        assertEquals(text(removed), echo.text)
        assertEquals(TranscriptItem.Pending.DROPPED, echo.failure)
        assertFalse("the Host never logged it", state.deliveredRpcIds.contains(rpcId(removed)))
        assertFalse("so no bubble of it is on screen", users(state).any { it.text == text(removed) })
        assertFalse("and its inbox no longer holds it", state.inbox.holds(rpcId(removed)))
    }

    /** An echo survives a turn that read other messages and left this one be. */
    @Test
    fun a_message_still_in_the_inbox_is_still_waiting() {
        val state = replay(delivered, stopAfterSeq = 18L)
        val echo = echoes(state).single()
        assertTrue(echo.waiting)
        assertNull(echo.failure)
        assertTrue("the Host's inbox still holds it", state.inbox.holds(rpcId(delivered)))
    }

    private fun replay(capture: JsonObject, stopAfterSeq: Long): TranscriptFold {
        var state = seeded(capture)
        for (frame in capture["follow"]!!.jsonArray) {
            val decoded = FollowCodec.decode(frame)
            state = foldFollowFrame(state, decoded)
            if ((decoded as? FollowFrame.Event)?.event?.seq ?: 0L >= stopAfterSeq) break
        }
        return state
    }

    /**
     * The inbox mirror is the Host's own fold, so a splice that removes what a
     * turn claimed is not a splice that discarded it.
     */
    @Test
    fun claiming_a_message_is_not_discarding_it() {
        val inbox = Inbox(nextStep = listOf(InboxMessage("m1", "rpc-1")))
        val claimed = inbox.apply(splice(seq = 1, start = 0, removed = 1, outcome = null))
        assertFalse("a claim empties the row", claimed.inbox.holds("rpc-1"))
        assertFalse("but discards nothing", claimed.discarded)

        val discarded = inbox.apply(splice(seq = 2, start = 0, removed = 1, outcome = "canceled"))
        assertTrue("a removal the Host canceled is a discard", discarded.discarded)
    }

    /** An unreadable splice is ignored rather than corrupting the mirror. */
    @Test
    fun an_out_of_range_splice_changes_nothing() {
        val inbox = Inbox(nextStep = listOf(InboxMessage("m1", "rpc-1")))
        val past = splice(seq = 3, start = 4, removed = 1, outcome = "canceled")
        assertEquals(inbox, inbox.apply(past).inbox)
    }

    /** The queue frames a capture carried say the same thing the inbox does. */
    @Test
    fun the_queue_frame_marks_the_steer_and_then_retires_it() {
        for (capture in listOf(delivered, removed)) {
            val frames = capture["control"]!!.jsonArray.map { QueueCodec.parse(it.jsonObject["items"]!!.jsonArray) }
            val steering = frames.flatten().singleOrNull { it.steering }
            assertEquals(rpcId(capture), steering?.rpcId)
            assertEquals(text(capture), steering?.label)
            assertEquals("the steering row is retired", 0, frames.last().size)
        }
    }

    /** The row is retired by identity, not by a timer. */
    @Test
    fun an_echo_is_retired_by_the_prompt_identity_the_host_echoes_back() {
        assertTrue(replay(delivered).deliveredRpcIds.contains(rpcId(delivered)))
        assertFalse(replay(removed).deliveredRpcIds.contains(rpcId(removed)))
    }

    /** A row the app has no record of admitting is left alone by a discard. */
    @Test
    fun a_row_with_no_record_of_admission_is_left_alone() {
        val row = TranscriptItem.Pending("pending:x", "x", "hello")
        val settled = settle(listOf(row), Inbox(), discarded = true, delivered = emptySet())
        assertEquals(listOf(row), settled)
        assertNull((settled.single() as TranscriptItem.Pending).failure)
    }

    private fun splice(
        seq: Long,
        start: Int,
        removed: Int = 0,
        inserted: List<String> = emptyList(),
        outcome: String? = null,
    ): SessionEvent = SessionEvent(
        seq = seq,
        type = "agent/inbox/spliced",
        time = 0,
        data = buildJsonObject {
            put("target", JsonPrimitive("next-step"))
            put("start", JsonPrimitive(start))
            put("removedCount", JsonPrimitive(removed))
            put("inserted", buildJsonArray {
                inserted.forEach { id ->
                    add(buildJsonObject {
                        put("id", JsonPrimitive(id))
                        put("source", buildJsonObject {
                            put("kind", JsonPrimitive("user"))
                            put("rpcId", JsonPrimitive("rpc-1"))
                        })
                    })
                }
            })
            outcome?.let { put("outcome", JsonPrimitive(it)) }
        },
    )
}
