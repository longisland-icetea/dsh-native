package io.github.longislandicetea.dshnative

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoding for the event types the transcript gained after the coverage audit.
 *
 * Every payload here is copied from a live capture rather than written by hand,
 * because that is the only way this file stays honest about the wire: the
 * `turn/end` case in particular first asserted a `reason.kind` of "error" for a
 * turn that had completed, which is exactly the class of mistake a hand-written
 * fixture hides. See `docs/event-coverage.md` for the full type table.
 */
class EventDecodeTest {
    private fun event(json: String, type: String? = null): SessionEvent {
        val obj = DshWire.json.parseToJsonElement(json) as JsonObject
        return SessionEvent(
            seq = obj["seq"].toString().trim('"').toLong(),
            type = type ?: obj["type"].toString().trim('"'),
            time = 0,
            data = obj["data"]!!,
        )
    }

    @Test
    fun `todo write carries the whole plan`() {
        val todos = EventPayload.todoList(
            event(
                """{"type":"todo/write","seq":356,"time":1,"data":{"todos":[
                   {"content":"P0: rewrite the doc","status":"in_progress"},
                   {"content":"P1: prune","status":"pending"}]}}""",
            ),
        )
        assertEquals(2, todos?.size)
        assertEquals("in_progress", todos?.first()?.status)
        assertEquals("P0: rewrite the doc", todos?.first()?.content)
    }

    @Test
    fun `an empty or foreign todo payload yields nothing`() {
        assertNull(EventPayload.todoList(event("""{"type":"todo/write","seq":1,"time":0,"data":{"todos":[]}}""")))
        assertNull(EventPayload.todoList(event("""{"type":"model/selection","seq":1,"time":0,"data":{}}""")))
        // A row without content is dropped rather than rendered blank.
        val sparse = EventPayload.todoList(
            event("""{"type":"todo/write","seq":1,"time":0,"data":{"todos":[{"status":"pending"},{"content":"kept"}]}}"""),
        )
        assertEquals(1, sparse?.size)
        assertEquals("kept", sparse?.first()?.content)
    }

    @Test
    fun `a delivered file keeps its description`() {
        val files = EventPayload.deliveredFiles(
            event(
                """{"type":"deliverables/presented","seq":342,"time":1,"data":{"turn":1,"callId":"c1",
                   "files":[{"path":"/home/cxxiao/report.md","description":"audit report"}]}}""",
            ),
        )
        assertEquals("/home/cxxiao/report.md", files?.single()?.path)
        assertEquals("audit report", files?.single()?.description)
    }

    @Test
    fun `model selection reports its effort only when set`() {
        val bare = EventPayload.modelChoice(
            event("""{"type":"model/selection","seq":1,"time":0,"data":{"provider":"p","model":"m"}}"""),
        )
        assertEquals("p", bare?.provider)
        assertEquals("m", bare?.model)
        assertNull(bare?.effort)

        val effort = EventPayload.modelChoice(
            event("""{"type":"model/selection","seq":1,"time":0,"data":{"provider":"p","model":"m","reasoningEffort":"max"}}"""),
        )
        assertEquals("max", effort?.effort)
    }

    @Test
    fun `a completed turn says nothing`() {
        assertNull(
            EventPayload.turnOutcome(
                event("""{"type":"turn/end","seq":5,"time":0,"data":{"turn":5,"reason":{"kind":"completed"}}}"""),
            ),
        )
    }

    @Test
    fun `an interrupted turn is reported`() {
        // Observed live: a turn left open when the session closed is closed with
        // this marker, and the transcript otherwise just stops.
        assertEquals(
            "turn was interrupted (the session was closed mid-turn)",
            EventPayload.turnOutcome(
                event("""{"type":"turn/end","seq":6,"time":0,"data":{"turn":6,"reason":{"kind":"interrupted"}}}"""),
            ),
        )
    }

    @Test
    fun `a failed turn reports the provider message without its newline`() {
        assertEquals(
            "turn failed: OpenAI API error (404): 404 page not found",
            EventPayload.turnOutcome(
                event(
                    """{"type":"turn/end","seq":7,"time":0,"data":{"turn":7,"reason":{"kind":"error",
                       "error":{"message":"OpenAI API error (404): 404 page not found\n","code":"PI_AI_ERROR"}}}}""",
                ),
            ),
        )
    }

    @Test
    fun `an unknown plugin reason is still named`() {
        assertEquals(
            "turn ended: watchdog",
            EventPayload.turnOutcome(
                event("""{"type":"turn/end","seq":8,"time":0,"data":{"turn":8,"reason":{"kind":"watchdog"}}}"""),
            ),
        )
    }

    @Test
    fun `session settings map to a label and a value`() {
        assertEquals(
            "permission" to "danger-full-access",
            EventPayload.settingChange(
                event("""{"type":"permission/preset","seq":0,"time":0,"data":{"preset":"danger-full-access"}}"""),
            ),
        )
        assertEquals(
            "compaction" to "summarizing history",
            EventPayload.settingChange(
                event("""{"type":"compaction/start","seq":400,"time":0,"data":{"compactionId":"c","turn":3}}"""),
            ),
        )
        // The seed marker carries nothing worth a row.
        assertNull(EventPayload.settingChange(event("""{"type":"session/end-seed","seq":3,"time":0,"data":{}}""")))
    }

    @Test
    fun `a message with no text block produces no reply`() {
        val reasoningOnly = event(
            """{"type":"assistant/message","seq":9,"time":0,
               "data":{"turn":1,"step":1,"message":{"role":"assistant","content":[{"type":"reasoning","text":"thinking"}]}}}""",
        )
        assertNull(reasoningOnly.text)
    }
}
