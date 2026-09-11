package io.github.longislandicetea.dshnative

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stream that ends must be subscribed again, whether or not it ended badly.
 *
 * The app's two long-lived streams — forwarded Host events and the open
 * conversation's follow — only exist while something collects them. A socket
 * teardown reaches them as a plain completion, and the original code used
 * `retryWhen`, which only sees failures: the collection ended, nothing
 * re-subscribed, and the banner still said connected. For the events stream that
 * is not a cosmetic problem. The Host replays a pending question to a client that
 * is listening at that moment, so an unsubscribed client never learns that an
 * agent is waiting, and the agent waits for an answer nobody can give.
 *
 * `retry` is what the fix relies on, and its behaviour on a *successful*
 * completion is the whole point of this test: if it only retried failures, the
 * fix would compile, look right, and change nothing.
 */
class StreamResubscribeTest {

    /**
     * A stream that opens, emits the ready frame, and then completes as a socket
     * teardown does. Counts its own subscriptions.
     */
    private class EndingStream(private val endings: Int) {
        var subscriptions = 0
            private set

        val flow = flow {
            subscriptions++
            emit("ready")
            if (subscriptions > endings) {
                // Hold the last subscription open, the way a live socket does, so
                // the collector does not spin forever and the test can end.
                delay(Long.MAX_VALUE)
            }
        }
    }

    @Test
    fun a_stream_that_completes_is_subscribed_again() = runBlocking {
        val stream = EndingStream(endings = 3)
        var frames = 0

        // Four frames is four subscriptions, since each one emits exactly one:
        // three clean completions must have produced three re-subscriptions.
        // `take` is how the collection ends -- throwing from inside `collect`
        // would be swallowed by `retryWhen` and retried, which is a property of
        // the operator chain rather than of the code under test.
        withTimeoutOrNull(5_000) {
            stream.flow
                .resubscribe(delayMillis = 50)
                .take(4)
                .collect { frames++ }
        }

        assertTrue(
            "expected 4 subscriptions after 3 clean completions, saw ${stream.subscriptions}",
            stream.subscriptions >= 4,
        )
        assertEquals("each subscription delivered its frame", 4, frames)
    }

    /**
     * A stream that fails is also resubscribed -- the behaviour the original
     * `retryWhen` already had, which this must not regress.
     */
    @Test
    fun a_stream_that_fails_is_subscribed_again() = runBlocking {
        var subscriptions = 0
        val failing = flow<String> {
            subscriptions++
            if (subscriptions >= 3) {
                emit("ready")
                delay(Long.MAX_VALUE)
            }
            throw IllegalStateException("socket dropped")
        }
        var got = 0

        withTimeoutOrNull(3_000) {
            failing
                .resubscribe(delayMillis = 20)
                .collect { got++ }
        }

        assertTrue("expected 3 subscriptions, saw $subscriptions", subscriptions >= 3)
        assertEquals(1, got)
    }
}
