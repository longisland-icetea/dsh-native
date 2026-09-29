package io.github.longislandicetea.dshnative

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue dock, drawn from the session's own `inbox` projection.
 *
 * This is the second break the 0.1.7 update caused, and the reason the first one
 * looked like the only one. `session/control` stopped carrying a `queues` map
 * beside the projections, and pending input moved into the session's `inbox`
 * projection: two lists whose *names* are the placement, holding whole messages
 * rather than a wrapper with a preview. A client still reading `snapshot["queues"]`
 * gets nothing at all -- no error, no empty-row notice, just a dock that never
 * shows the message the reader just sent.
 *
 * The frames are the Host's own (`steer-inbox.json`): one running turn, one steer
 * folded into it, and one message queued behind it, captured over `session/control`
 * from a live 0.1.7 Host.
 */
class PendingQueueTest {
    private val capture = DshWire.json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/steer-inbox.json")).bufferedReader().readText(),
    ) as JsonObject

    private val sessionId = (capture["sessionId"] as JsonPrimitive).content
    private val steerText = (capture["steerText"] as JsonPrimitive).content
    private val steerRpcId = (capture["steerRpcId"] as JsonPrimitive).content
    private val queueText = (capture["queueText"] as JsonPrimitive).content

    private val frames: List<JsonElement> = capture["control"]!!.jsonArray.map { it.jsonObject["value"]!! }

    /** Replay the capture through the real fold, which is what `applyControl` calls. */
    private fun replay(frames: List<JsonElement> = this.frames): AppState =
        frames.fold(AppState()) { state, frame -> state.withControlFrame(frame) }

    @Test
    fun `a steer folded into the running turn is a steering row`() {
        val steer = replay().queues.getValue(sessionId).first { it.steering }
        assertEquals(steerText, steer.label)
        assertEquals("the row names the prompt this client sent", steerRpcId, steer.rpcId)
        assertEquals("steering", steer.placement)
        assertTrue("its text can be edited", steer.editable)
    }

    @Test
    fun `a message queued behind the turn is a queued row of its own`() {
        // The frame that carries both is the point: the two lists are told apart
        // by which one a message is in, not by a field on the message.
        val rows = replay().queues.getValue(sessionId)
        assertEquals(listOf("steering", "queued"), rows.map { it.placement })
        assertEquals(listOf(steerText, queueText), rows.map { it.label })
    }

    @Test
    fun `the dock counts both, and offers steering only to the row that can take it`() {
        val rows = replay().queues.getValue(sessionId)
        assertFalse("a row already going in is not offered steering again", QueueView.canSteer(true, rows[0]))
        assertTrue("a row waiting its turn is", QueueView.canSteer(true, rows[1]))
        assertEquals("2 messages waiting", QueueView.countLabel(rows))
    }

    @Test
    fun `a later frame replaces the whole queue, so a row cannot linger`() {
        // The capture's last inbox frame holds the pair above; the earlier ones
        // show the steer arriving on its own. Replaying only up to that point must
        // not put the queued message on screen early -- a frame is the whole list.
        val upToSteer = replay(frames.takeWhile { !it.toString().contains(queueText) })
        assertEquals(listOf(steerText), upToSteer.queues.getValue(sessionId).map { it.label })
    }

    @Test
    fun `a baseline with no pending input empties the dock`() {
        // The reconnect case: a baseline is a snapshot, so absence is the removal.
        // This is what a merged queue map got wrong, and why a claimed steer used
        // to sit in the dock for good.
        val stale = replay()
        assertEquals(2, stale.queues.getValue(sessionId).size)
        val afterReconnect = stale.withControlFrame(frames.first())
        assertTrue("the baseline's empty inbox is the removal", afterReconnect.queues.isEmpty())
    }

    // ── the shapes the capture did not show ──────────────────────────────────

    /** One pending message, as the captured frames spell it. */
    private fun message(id: String?, content: JsonArray, rpcId: String? = null) = buildJsonObject {
        id?.let { put("id", it) }
        put("role", "user")
        put("content", content)
        rpcId?.let { put("source", buildJsonObject { put("kind", "user"); put("rpcId", it) }) }
    }

    private fun text(value: String) = buildJsonObject {
        put("type", "text")
        put("text", value)
    }

    private fun inbox(nextTurn: List<JsonObject> = emptyList(), nextStep: List<JsonObject> = emptyList()) =
        buildJsonObject {
            put("next-turn", JsonArray(nextTurn))
            put("next-step", JsonArray(nextStep))
        }

    private fun JsonElement.rows(): List<QueuedItem> = inboxProjection(this).queueRows()

    @Test
    fun `a message with no id is dropped rather than shown as a row that cannot act`() {
        // Every action a row offers names the id, so a row without one would offer
        // buttons that cannot work.
        val nameless = inbox(nextTurn = listOf(message(null, buildJsonArray { add(text("hi")) })))
        assertTrue(nameless.rows().isEmpty())
        assertTrue(inboxProjection(null).queueRows().isEmpty())
        assertTrue(DshWire.json.parseToJsonElement("[]").rows().isEmpty())
    }

    @Test
    fun `a row whose blocks this build cannot read is still a row, but not an editable one`() {
        val image = message("m2", buildJsonArray { add(buildJsonObject { put("type", "image") }) })
        val row = inbox(nextTurn = listOf(image)).rows().single()
        assertFalse(row.editable)
        assertEquals("", row.label)
    }

    @Test
    fun `the text of several blocks is joined, and a block this build does not read adds nothing`() {
        val two = message(
            "m3",
            buildJsonArray {
                add(text("first line"))
                add(buildJsonObject { put("type", "image") })
                add(text("second line"))
            },
            rpcId = "rpc-3",
        )
        val row = inbox(nextStep = listOf(two)).rows().single()
        assertEquals("first line\nsecond line", row.label)
        assertEquals("rpc-3", row.rpcId)
        assertTrue(row.steering)
    }

    @Test
    fun `a projection frame carries one key of one session, and empty is the removal`() {
        fun frame(value: JsonElement) = buildJsonObject {
            put("type", "projection")
            put("sessionId", "s1")
            put("key", "inbox")
            put("value", value)
        }
        val rows = AppState().withControlFrame(frame(inbox(nextTurn = listOf(message("m1", buildJsonArray { add(text("later")) })))))
        assertEquals(listOf("later"), rows.queues.getValue("s1").map { it.label })
        assertNull(rows.withControlFrame(frame(inbox())).queues["s1"])
    }

    @Test
    fun `a frame this build does not know changes nothing`() {
        val before = AppState(queues = mapOf("s1" to listOf(QueuedItem(id = "m1", text = "kept"))))
        // The `queue` and `jobs` frames of 0.1.5 are in the list on purpose: a Host
        // that still sent them would be one this client cannot draw a dock for, and
        // the failure should be a still dock rather than a mangled one.
        val shapes = listOf(
            """{"type":"queue","sessionId":"s1","items":[]}""",
            """{"type":"jobs","sessionId":"s1","jobs":[]}""",
            """{"type":"projection","sessionId":"s1"}""",
            """{"type":"projection","key":"inbox"}""",
            """[]""",
        )
        for (shape in shapes) {
            assertEquals(shape, before, before.withControlFrame(DshWire.json.parseToJsonElement(shape)))
        }
    }
}
