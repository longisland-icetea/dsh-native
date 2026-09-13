package io.github.longislandicetea.dshnative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * The outbox: what a weak link is allowed to do to a message the reader sent.
 *
 * The failures here are the ones a phone actually sees -- a timeout, a refused
 * connection, a reset socket -- against the one that means waiting will not help:
 * the Host having answered.
 */
class OutboxTest {
    private fun entry(attempts: Int = 0) = OutboxEntry(
        rpcId = "rpc-1",
        sessionId = "s1",
        text = "hello",
        mode = "steer",
        attempts = attempts,
    )

    @Test
    fun the_backoff_grows_then_stops_growing() {
        assertEquals(1_000L, Outbox.delayMillis(0))
        assertEquals(2_000L, Outbox.delayMillis(1))
        assertEquals(4_000L, Outbox.delayMillis(2))
        assertEquals(8_000L, Outbox.delayMillis(3))
        assertEquals(15_000L, Outbox.delayMillis(4))
        assertEquals("and it stays there rather than running away", 15_000L, Outbox.delayMillis(30))
    }

    @Test
    fun the_network_is_worth_retrying_and_a_refusal_is_not() {
        assertTrue(Outbox.worthRetrying(SocketTimeoutException("read timed out")))
        assertTrue(Outbox.worthRetrying(IOException("connection reset")))
        assertTrue(Outbox.worthRetrying(DshException("mux send failed")))
        assertFalse("the Host answered; waiting changes nothing", Outbox.worthRetrying(HostRefused("session/prompt: session/not-found")))
    }

    @Test
    fun a_queued_row_says_it_is_still_being_tried() {
        assertNull("the first attempt needs no announcement", Outbox.note(entry(attempts = 0)))
        val note = Outbox.note(entry(attempts = 3))
        assertTrue("it says what is happening: $note", note!!.contains("retrying"))
        assertTrue("and how many times: $note", note.contains("3"))
    }

    /** A message that outlives the process is the point of persisting it. */
    @Test
    fun the_outbox_survives_being_written_and_read_back() {
        val entries = listOf(entry(attempts = 2).copy(lastError = "timeout"), entry().copy(rpcId = "rpc-2", mode = "queue"))
        assertEquals(entries, OutboxCodec.decode(OutboxCodec.encode(entries)))
    }

    @Test
    fun an_unreadable_blob_yields_nothing_rather_than_a_crash_on_start() {
        assertEquals(emptyList<OutboxEntry>(), OutboxCodec.decode(null))
        assertEquals(emptyList<OutboxEntry>(), OutboxCodec.decode(""))
        assertEquals(emptyList<OutboxEntry>(), OutboxCodec.decode("{not json"))
    }
}
