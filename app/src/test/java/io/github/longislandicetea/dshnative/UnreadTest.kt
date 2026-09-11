package io.github.longislandicetea.dshnative

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When a session counts as unread.
 *
 * The Host reports `running` and nothing about read state, and `updatedAt` tracks
 * only the reader's own prompts, so this rule is the entire definition. It is what
 * decides whether a finished session gets a green mark or nothing at all.
 */
class UnreadTest {
    private val a = "session-a"
    private val b = "session-b"

    @Test
    fun `a turn finishing in a session you are not reading is unread`() {
        val after = unreadAfter(emptySet(), a, running = false, opened = b)
        assertEquals(setOf(a), after)
    }

    @Test
    fun `a turn finishing in the session on screen is read`() {
        // The reader watched it happen; marking it unread would ask them to look at
        // something they are looking at.
        val after = unreadAfter(emptySet(), a, running = false, opened = a)
        assertEquals(emptySet<String>(), after)
    }

    @Test
    fun `opening a session clears its mark`() {
        val marked = unreadAfter(emptySet(), a, running = false, opened = b)
        assertEquals(setOf(a), marked)
        // ...which is what `markRead` does when the session is opened.
        assertEquals(emptySet<String>(), marked - a)
    }

    @Test
    fun `a turn starting clears the mark`() {
        // Busy again: no longer "finished and unseen".
        val after = unreadAfter(setOf(a), a, running = true, opened = null)
        assertEquals(emptySet<String>(), after)
    }

    @Test
    fun `nothing open still marks a finished turn unread`() {
        // No conversation selected is not the same as reading every session.
        val after = unreadAfter(emptySet(), a, running = false, opened = null)
        assertEquals(setOf(a), after)
    }

    @Test
    fun `other sessions keep their marks`() {
        val after = unreadAfter(setOf(b), a, running = false, opened = null)
        assertEquals(setOf(a, b), after)
    }
}
