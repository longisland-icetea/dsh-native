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

    /**
     * A discard survives a mirror that cannot apply the splice.
     *
     * The device showed this: a frame missed while the stream was down left the
     * mirror empty, so the discard splice failed its bounds check and the fact
     * that the Host had thrown the message away was swallowed -- the row waited
     * forever. The splice's `outcome` is the fact; applying it is bookkeeping.
     */
    @Test
    fun a_discard_is_believed_even_when_the_mirror_is_out_of_sync() {
        val rpcId = "2ee42fc7-fcac-4e6c-83db-7d81c13fb1bb"
        val echo = TranscriptItem.Pending(TranscriptItem.Pending.keyOf(rpcId), rpcId, "DROPX")
            .copy(admitted = true)
        var state = TranscriptFold(
            // The mirror has lost track: it is empty while the row is admitted.
            Conversation(sessionId = "s", items = listOf(echo)),
        )
        state = foldFollowFrame(
            state,
            FollowFrame.Event(splice(seq = 19, start = 0, removed = 1, outcome = "canceled")),
        )
        assertEquals(TranscriptItem.Pending.DROPPED, echoes(state).single().failure)
    }

    /**
     * Adopting a folded frame is idempotent, because the fold is not.
     *
     * `MutableStateFlow.update` re-runs its lambda when another writer wins the
     * race -- five times, in one device log -- and a splice is a delta, so a
     * frame folded twice left the inbox mirror holding a message the Host had
     * already dropped. The fold therefore happens before the update and this is
     * all the lambda does.
     */
    @Test
    fun adopting_a_folded_frame_twice_is_the_same_as_once() {
        val rpcId = "2ee42fc7-fcac-4e6c-83db-7d81c13fb1bb"
        val echo = TranscriptItem.Pending(TranscriptItem.Pending.keyOf(rpcId), rpcId, "DROPX")
        var fold = TranscriptFold(Conversation(sessionId = "s", items = listOf(echo)))
        fold = foldFollowFrame(fold, FollowFrame.Event(splice(seq = 18, start = 0, inserted = listOf("m1"))))
        val state = AppState(conversation = Conversation(sessionId = "s", items = listOf(echo)))

        val once = state.withFolded("s", fold)
        val twice = once.withFolded("s", fold)
        assertEquals(once, twice)
        assertEquals("the mirror holds the message exactly once", 1, state.withFolded("s", fold).conversation!!.items.size)
    }

    /**
     * The fold's memory survives from one frame to the next.
     *
     * This is the bug the live harness found in one run. An inbox splice is a
     * delta against the inbox, so an insert at index 1 only lands if the frame
     * before it was remembered -- and the app rebuilt the fold from scratch on
     * every frame, which silently dropped every splice that was not the first.
     * The second message a client sent was therefore never admitted, so its row
     * could never be given up on when the Host discarded it.
     */
    @Test
    fun the_fold_memory_survives_from_one_frame_to_the_next() {
        var state = AppState(conversation = Conversation(sessionId = "s"))
        state = state.folded(FollowFrame.Event(splice(seq = 1, start = 0, inserted = listOf("m1"), rpcId = "rpc-a")))
        state = state.folded(FollowFrame.Event(splice(seq = 2, start = 1, inserted = listOf("m2"), rpcId = "rpc-b")))
        assertEquals("both inserts landed", 2, state.fold!!.inbox.nextStep.size)
        assertTrue("so the second message is known to be pending", state.fold!!.inbox.holds("rpc-b"))
    }

    /**
     * A session that starts being used elsewhere stops being blank.
     *
     * `api-session/added` arrives once, while the session is still blank, and no
     * later frame carries the flag -- but `blank` is exactly what the drawer
     * hides on, so a session created and used on another client stayed invisible
     * here for good. A turn being taken is proof enough, and these two events are
     * the only ones that say so.
     */
    @Test
    fun a_session_used_elsewhere_stops_being_blank() {
        val summary = SessionSummary(sessionId = "s1", cwd = "/tmp", blank = true)
        val state = AppState(sessions = listOf(summary))

        val afterMessage = state.withSessionDelta(SessionDelta.Activity("s1", 1_000L))
        assertEquals("a message means it is not blank", false, afterMessage.sessions.single().blank)
        assertEquals(1_000L, afterMessage.sessions.single().updatedAt)

        val afterTurn = state.withSessionDelta(SessionDelta.Running("s1", running = true))
        assertEquals("a turn means it is not blank", false, afterTurn.sessions.single().blank)
        assertTrue("and it is running", afterTurn.sessions.single().running)
    }

    /** And it becomes visible under the drawer's own rule. */
    @Test
    fun the_used_session_shows_up_in_the_drawer() {
        val blank = AppState(sessions = listOf(SessionSummary(sessionId = "s1", cwd = "/tmp", blank = true)))
        val used = blank.withSessionDelta(SessionDelta.Activity("s1", 1_000L))
        fun drawerIds(state: AppState) = SessionGroup.fromWorkspaces(
            sessions = state.sessions,
            workspaces = emptyList(),
            currentId = null,
            archived = emptySet(),
            collapsed = emptySet(),
            showArchived = false,
        ).flatMap { group -> group.sessions.map { it.sessionId } }
        assertEquals("a blank session is not listed", emptyList<String>(), drawerIds(blank))
        assertEquals("the one that was used is", listOf("s1"), drawerIds(used))
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

    /**
     * A discard this client never saw reported is still a discard.
     *
     * The splice that says so is a frame like any other: if the stream was
     * reconnecting, the snapshot that arrives instead says only what the inbox
     * *is*, not how it emptied. A row the fresh inbox does not hold, with no
     * durable message behind it, has to be given up on -- otherwise it waits
     * forever, which is the state the device showed.
     */
    @Test
    fun a_snapshot_that_no_longer_lists_the_row_gives_up_on_it() {
        val rpcId = "2ee42fc7-fcac-4e6c-83db-7d81c13fb1bb"
        val echo = TranscriptItem.Pending(TranscriptItem.Pending.keyOf(rpcId), rpcId, "STEERDROP")
            .copy(admitted = true)
        var state = TranscriptFold(Conversation(sessionId = "s", items = listOf(echo)))

        val snapshot = FollowFrame.Snapshot(
            cursor = 90,
            records = emptyList(),
            hasMore = false,
            inbox = InboxProjection(nextTurn = emptyList(), nextStep = emptyList()),
        )
        state = foldFollowFrame(state, snapshot)
        assertEquals(TranscriptItem.Pending.DROPPED, echoes(state).single().failure)
    }

    /** A snapshot that still lists it leaves the row waiting. */
    @Test
    fun a_snapshot_that_still_lists_the_row_leaves_it_waiting() {
        val rpcId = "2ee42fc7-fcac-4e6c-83db-7d81c13fb1bb"
        val echo = TranscriptItem.Pending(TranscriptItem.Pending.keyOf(rpcId), rpcId, "STEERDROP")
        var state = TranscriptFold(Conversation(sessionId = "s", items = listOf(echo)))

        val snapshot = FollowFrame.Snapshot(
            cursor = 90,
            records = emptyList(),
            hasMore = false,
            inbox = InboxProjection(nextStep = listOf(InboxMessage("m1", rpcId))),
        )
        state = foldFollowFrame(state, snapshot)
        val row = echoes(state).single()
        assertTrue("still on its way", row.waiting)
        assertTrue("and the inbox said so", row.admitted)
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
        rpcId: String = "rpc-1",
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
                            put("rpcId", JsonPrimitive(rpcId))
                        })
                    })
                }
            })
            outcome?.let { put("outcome", JsonPrimitive(it)) }
        },
    )
}
