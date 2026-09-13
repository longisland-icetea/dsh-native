package io.github.longislandicetea.dshnative

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * What a weak link does to one unary call, and what the client does about it.
 *
 * The failure this exists for is not an outage: it is a pooled connection the
 * peer has already closed, which arrives as "unexpected end of stream" on a
 * request that would have worked a moment later. The policy is tested here, as a
 * decision; that OkHttp really reports that case this way, and that the retry
 * really rescues a call, is what `tools/live-harness.sh` shows -- it drives the
 * app through a proxy it cuts, and the run that added this failed on exactly that
 * error.
 */
class UnaryRetryTest {
    @Test
    fun a_connection_dropped_mid_body_is_tried_again() = runBlocking {
        var attempts = 0
        val answer = withOneRetry("session/list") {
            attempts++
            if (attempts == 1) throw IOException("unexpected end of stream") else "hello"
        }
        assertEquals("the retry's answer is what comes back", "hello", answer)
        assertEquals("two attempts were made", 2, attempts)
    }

    @Test
    fun a_refusal_is_not_tried_again() = runBlocking {
        var attempts = 0
        val failure = runCatching {
            withOneRetry<Unit>("session/prompt") {
                attempts++
                throw HostRefused("session/prompt: session/not-found")
            }
        }.exceptionOrNull()
        assertTrue("it surfaces as a refusal: $failure", failure is HostRefused)
        assertEquals("and it was asked exactly once", 1, attempts)
    }

    @Test
    fun a_cancellation_is_not_tried_again() = runBlocking {
        var attempts = 0
        runCatching {
            withOneRetry<Unit>("session/list") {
                attempts++
                throw CancellationException("the caller went away")
            }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun a_second_failure_is_reported_rather_than_retried_forever() = runBlocking {
        var attempts = 0
        val failure = runCatching {
            withOneRetry<Unit>("session/list") {
                attempts++
                throw SocketTimeoutException("read timed out")
            }
        }.exceptionOrNull()
        assertTrue("the last failure is what surfaces: $failure", failure is SocketTimeoutException)
        assertEquals("two attempts, not a loop", 2, attempts)
    }

    @Test
    fun only_the_network_is_worth_retrying() {
        assertTrue(worthRetryingUnary(SocketTimeoutException("read timed out")))
        assertTrue(worthRetryingUnary(IOException("connection reset")))
        assertFalse(worthRetryingUnary(HostRefused("session/not-found")))
        assertFalse(worthRetryingUnary(CancellationException("gone")))
    }
}
