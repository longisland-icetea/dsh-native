package io.github.longislandicetea.dshnative

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Session state in the drawer: running, waiting on the reader, or done.
 *
 * Running comes from the Host; the middle state is derived here, because a session
 * that is idle but holds an unanswered approval is the one that blocks an agent and
 * is worth interrupting for.
 */
class SessionStatusTest {
    @Test
    fun `a running turn is running`() {
        assertEquals(SessionStatus.Running, sessionStatus(running = true, needsAnswer = false))
    }

    @Test
    fun `running wins over a pending answer`() {
        // Both true means the agent is working on something the reader also has to
        // answer later; "running" is what it is doing now.
        assertEquals(SessionStatus.Running, sessionStatus(running = true, needsAnswer = true))
    }

    @Test
    fun `an idle session with a waiting question needs you`() {
        assertEquals(SessionStatus.NeedsYou, sessionStatus(running = false, needsAnswer = true))
    }

    @Test
    fun `an idle session with nothing pending is done`() {
        assertEquals(SessionStatus.Done, sessionStatus(running = false, needsAnswer = false))
    }

    @Test
    fun `every state has a distinct colour and a label`() {
        val colours = SessionStatus.entries.map(::statusColor)
        assertEquals("states must be distinguishable", colours.size, colours.distinct().size)
        val labels = SessionStatus.entries.map(::statusLabel)
        assertEquals(labels.size, labels.distinct().size)
        assertEquals(listOf("running", "needs you", "done"), labels)
    }
}
