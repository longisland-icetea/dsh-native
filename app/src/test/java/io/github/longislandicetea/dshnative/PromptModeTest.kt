package io.github.longislandicetea.dshnative

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a prompt is delivered while the agent is busy.
 *
 * The Host's descriptor accepts `mode: 'queue' | 'steer'` and defaults to
 * `queue`; the desktop composer exposes the choice as a "busy Enter" preference.
 * This client sends `steer`, which folds the text into the running turn so a
 * correction lands while the agent is still working. It is best-effort by
 * design: a submission that arrives after the steer window closed becomes the
 * next queue item, so the text is never dropped either way.
 *
 * Pinned because it is a one-word default with no visible effect until someone
 * tests it mid-turn -- exactly the kind of change that gets reverted by accident.
 */
class PromptModeTest {

    private fun request(mode: String? = null) = PromptRequest(
        requestId = "r1",
        sessionId = "session-1",
        mode = mode ?: PromptRequest(requestId = "r1", sessionId = "s", content = emptyList()).mode,
        content = listOf(PromptContent("text", "hello")),
    )

    @Test
    fun the_default_mode_is_steer() {
        val encoded = DshWire.json.encodeToJsonElement(PromptRequest.serializer(), request())
        assertEquals("steer", encoded.jsonObject["mode"]?.jsonPrimitive?.content)
    }

    @Test
    fun the_wire_still_accepts_the_host_vocabulary() {
        // Both values the descriptor declares must round-trip unchanged, so a
        // future "queue instead" preference cannot silently send a third string.
        for (mode in listOf("queue", "steer")) {
            val encoded = DshWire.json.encodeToJsonElement(PromptRequest.serializer(), request(mode))
            assertEquals(mode, encoded.jsonObject["mode"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun the_prompt_still_carries_its_text() {
        val encoded = DshWire.json.encodeToJsonElement(PromptRequest.serializer(), request())
        val content = encoded.jsonObject["content"]
        assertEquals(false, content == null)
        assertEquals(true, content.toString().contains("hello"))
    }
}
