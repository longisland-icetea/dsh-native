package io.github.longislandicetea.dshnative

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide whether a logical stream can recover.
 *
 * This is where the app went silent: a stream's `end` frame was handed over
 * with `trySend` (whose failure was ignored) and then the stream was dropped
 * from the table without closing its channel. The collector was left waiting on
 * a channel nothing would feed or close, and the socket teardown that should
 * have released it only closes the channels still in the table. No error, no
 * completion, no retry -- and the visible half of the app (the transcript, whose
 * stream was re-opened on every reconnect) kept working while the queue dock and
 * the question cards stayed dead.
 */
class MuxStreamTest {
    private fun items(): MuxStreams = MuxStreams().also { it.register("s1", Channel(Channel.UNLIMITED)) }

    private fun channelOf(streams: MuxStreams): Channel<MuxFrame> {
        val field = MuxStreams::class.java.getDeclaredField("table")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val table = field.get(streams) as HashMap<String, Channel<MuxFrame>>
        return assertNotNull(table["s1"])!!.let { table["s1"]!! }
    }

    private fun item(streamId: String = "s1") = MuxFrame.Item(streamId, JsonNull)

    @Test
    fun a_frame_reaches_the_stream_it_names() = runBlocking {
        val streams = items()
        streams.deliver(item())
        val received = withTimeout(1_000) { channelOf(streams).receive() }
        assertEquals("s1", received.streamId)
    }

    @Test
    fun a_frame_for_an_unknown_stream_is_ignored() {
        val streams = items()
        streams.deliver(item("other"))
        assertEquals("nothing was registered under that id", 1, streams.size)
    }

    /**
     * The end of a stream ends it, whatever the buffer is doing.
     *
     * The old code removed the stream from the table and left the channel open,
     * so the collector's flow neither completed nor failed -- `resubscribe`
     * never ran and the stream was gone for good.
     */
    @Test
    fun an_end_frame_closes_the_stream() = runBlocking {
        val streams = MuxStreams()
        // A channel with room for one frame, already full: the frame that ends
        // the stream cannot be handed over, which is exactly the burst case.
        val channel = Channel<MuxFrame>(capacity = 1)
        streams.register("s1", channel)
        streams.deliver(item())
        streams.deliver(MuxFrame.End("s1"))
        assertEquals("the stream left the table", 0, streams.size)
        assertTrue("and its channel is closed, so the collector can retry", channel.isClosedForSend)
        // What was buffered is still readable: closing does not discard frames.
        assertEquals("s1", withTimeout(1_000) { channel.receive() }.streamId)
        assertTrue("and the collector sees the end rather than waiting forever", channel.isClosedForReceive)
    }

    @Test
    fun an_error_frame_closes_the_stream_too() {
        val streams = MuxStreams()
        val channel = Channel<MuxFrame>(capacity = 1)
        streams.register("s1", channel)
        streams.deliver(MuxFrame.Failure("s1", "gateway/bad-request", "no"))
        assertEquals(0, streams.size)
        assertTrue("closed for send, so no frame can be added after the error", channel.isClosedForSend)
        runBlocking { withTimeout(1_000) { channel.receive() } }
        assertTrue(channel.isClosedForReceive)
    }

    /** A dying socket ends every stream on it, so every collector retries. */
    @Test
    fun a_socket_teardown_ends_every_registered_stream() = runBlocking {
        val streams = MuxStreams()
        val first = Channel<MuxFrame>(Channel.UNLIMITED)
        val second = Channel<MuxFrame>(Channel.UNLIMITED)
        streams.register("a", first)
        streams.register("b", second)
        streams.closeAll(RuntimeException("socket gone"))
        assertEquals(0, streams.size)
        assertTrue(first.isClosedForSend && second.isClosedForSend)
        val cause = runCatching { withTimeout(1_000) { first.receive() } }.exceptionOrNull()
        assertNotNull("the collector sees why, not a silent end", cause)
    }

    /** Forgetting a stream (the sender cancelled it) does not close it twice. */
    @Test
    fun a_forgotten_stream_is_no_longer_fed() {
        val streams = items()
        streams.forget("s1")
        streams.deliver(item())
        assertEquals(0, streams.size)
    }
}
