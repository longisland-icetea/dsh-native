package io.github.longislandicetea.dshnative

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for payload decoding, driven by a response captured from a
 * live host rather than by hand-written JSON.
 *
 * The bug these exist for: `session/list` items carry heterogeneous
 * server-owned state under `projections.values` (`goal` null-or-string,
 * `permissions` and `modelSelection` nested objects, `turnOutline` an array).
 * Decoding an item with a generated serializer threw on the first unknown
 * shape, the throw was swallowed, and the app showed "connected, but no
 * sessions".
 */
class SessionListCodecTest {
    private fun fixture(): JsonObject {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("session-list.json")) {
            "session-list.json fixture missing"
        }.bufferedReader().use { it.readText() }
        val decoded = DshWire.json.parseToJsonElement(text)
        return decoded as JsonObject
    }

    private fun valueOf(envelope: JsonObject) =
        ((envelope["result"] as JsonObject)["value"] as JsonObject)

    @Test
    fun `parses every item from a live capture`() {
        val sessions = SessionListCodec.parse(valueOf(fixture()))
        // The trap was returning an empty list here; the fixture has three items.
        assertEquals(3, sessions.size)
    }

    @Test
    fun `reads the fields the transcript needs`() {
        val first = SessionListCodec.parse(valueOf(fixture())).first()
        assertTrue(first.sessionId.startsWith("session-"))
        assertTrue(first.updatedAt > 0L)
        assertNotNull(first.cwd)
        // Title comes from inside projections, not from a first-class field.
        assertTrue(first.title.isNotBlank())
        assertTrue(!first.title.startsWith("session-"))
    }

    @Test
    fun `tolerates heterogeneous projection values`() {
        val sessions = SessionListCodec.parse(valueOf(fixture()))
        sessions.forEach { session ->
            val values = ((session.projections as JsonObject)["values"] as JsonObject)
            // These exact keys are what a strict serializer choked on.
            assertTrue("goal" in values)
            assertTrue("permissions" in values)
            assertTrue("modelSelection" in values)
        }
    }

    @Test
    fun `skips malformed items instead of failing the whole list`() {
        val value = DshWire.json.parseToJsonElement(
            """{"items":[{"sessionId":"session-keep"},{"cwd":"/no/id"},{"sessionId":"session-2"}]}""",
        )
        val sessions = SessionListCodec.parse(value)
        assertEquals(2, sessions.size)
        assertEquals("session-keep", sessions[0].sessionId)
        assertEquals("session-2", sessions[1].sessionId)
    }

    @Test
    fun `falls back to the id tail when a session has no title`() {
        val value = DshWire.json.parseToJsonElement("""{"items":[{"sessionId":"session-abcdef123456"}]}""")
        assertEquals("abcdef123456", SessionListCodec.parse(value).single().title)
    }
}
