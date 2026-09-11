package io.github.longislandicetea.dshnative

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which control the composer shows.
 *
 * Aligned with the web client. The rule that matters is the third case: with text
 * in the box the button sends even while a turn is running, so a draft can never
 * be thrown away by tapping stop.
 */
class ComposerActionTest {
    @Test
    fun `running with an empty box offers stop`() {
        assertEquals(ComposerAction.Stop, composerAction(running = true, input = ""))
        assertEquals(ComposerAction.Stop, composerAction(running = true, input = "   "))
    }

    @Test
    fun `running with text offers send, not stop`() {
        assertEquals(ComposerAction.Send, composerAction(running = true, input = "draft"))
    }

    @Test
    fun `idle shows an inactive send`() {
        assertEquals(ComposerAction.Idle, composerAction(running = false, input = ""))
        assertEquals(ComposerAction.Send, composerAction(running = false, input = "hello"))
    }

    @Test
    fun `whitespace alone never enables send`() {
        // `input.isNotBlank()` gates the button, so a space must not read as content.
        assertEquals(ComposerAction.Idle, composerAction(running = false, input = "\n\t "))
    }
}
