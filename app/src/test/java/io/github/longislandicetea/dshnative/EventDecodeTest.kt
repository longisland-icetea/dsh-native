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
                   "files":[{"path":"/home/example/report.md","description":"audit report"}]}}""",
            ),
        )
        assertEquals("/home/example/report.md", files?.single()?.path)
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
    fun `the agent preset names itself`() {
        assertEquals(
            "standard",
            EventPayload.presetChoice(
                event("""{"type":"agent-preset/selected","seq":4,"time":0,"data":{"agentPreset":"standard"}}"""),
            ),
        )
        assertNull(
            EventPayload.presetChoice(
                event("""{"type":"agent-preset/selected","seq":4,"time":0,"data":{}}"""),
            ),
        )
    }

    @Test
    fun `a retry reads as one line about the stalled attempt`() {
        // Copied from a session that stalled on the transport: attempt 1 of 5.
        assertEquals(
            "retrying (attempt 1 of 5) after TRANSPORT: DeepSeek Messages transport failed",
            EventPayload.retryAttempt(
                event(
                    """{"type":"llm/retry","seq":3685,"time":0,"data":{"retryId":"41cc6cf7-1e3c-4e25-be3e-7d28f458aeba",
                       "turn":17,"step":38,"provider":"deepseek-official","mode":"normal",
                       "policyKey":"[\"normal\",5,[\"EMPTY_RESPONSE\",\"RATE_LIMIT\"],500,10000,0.1]",
                       "retry":1,"maxRetries":5,"delayMs":458.9688524694306,
                       "failure":{"message":"DeepSeek Messages transport failed","code":"TRANSPORT"}}}""",
                ),
            ),
        )
        // A payload missing the counters still says what it can: the failure is
        // the part a reader is waiting for.
        assertEquals(
            "retrying after SERVER",
            EventPayload.retryAttempt(
                event("""{"type":"llm/retry","seq":1,"time":0,"data":{"failure":{"code":"SERVER"}}}"""),
            ),
        )
    }

    @Test
    fun `a goal says what it now is, and how it ended`() {
        // Copied from a session where a goal was created mid-conversation.
        assertEquals(
            "goal set: finish the audit",
            EventPayload.goalChange(
                event(
                    """{"type":"goal/change","seq":1803,"time":0,"data":{"kind":"goal/change","version":1,
                       "operation":"create","goal":{"id":"goal-73efad2e","revision":1,
                       "objective":"finish the audit","phase":"active","maxGoalRounds":12},
                       "roundsStarted":0,"createdAt":1,"updatedAt":1}}""",
                ),
            ),
        )
        // A blocked goal is explained by its reason, not by the objective it was
        // already carrying when it was set.
        assertEquals(
            "goal blocked: three rounds made no progress",
            EventPayload.goalChange(
                event(
                    """{"type":"goal/change","seq":9,"time":0,"data":{"kind":"goal/change","version":1,
                       "operation":"block","goal":{"id":"goal-73efad2e","revision":3,
                       "objective":"finish the audit","phase":"blocked",
                       "blockedReason":{"code":"NO_PROGRESS","message":"three rounds made no progress"}},
                       "roundsStarted":3,"createdAt":1,"updatedAt":2}}""",
                ),
            ),
        )
        // `clear` writes a tombstone instead of a goal, so there is nothing to
        // quote -- the operation itself is the whole fact.
        assertEquals(
            "goal cleared",
            EventPayload.goalChange(
                event(
                    """{"type":"goal/change","seq":10,"time":0,"data":{"kind":"goal/change","version":1,
                       "operation":"clear","cleared":{"id":"goal-73efad2e","revision":4},"clearedAt":2}}""",
                ),
            ),
        )
        // The second half of the pair a real session wrote: the same goal, now
        // finished. Its objective is long, so what reaches the row is truncated.
        val completed = EventPayload.goalChange(
            event(
                """{"type":"goal/change","seq":3488,"time":0,"data":{"kind":"goal/change","version":1,
                   "operation":"complete","goal":{"id":"goal-73efad2e-3c45-4efa-9512-51347ec75d76","revision":2,
                   "objective":"按用户 2026-09-27 定案完成第七轮审计剩余项：(A1/A2) 把\"允许融资\"改为\"不借现金、卖沽义务可超现金、90% 比例控杠杆\"的正确表述；(B) M7 未持有期权挂单致 -inf、文字全量对账、引擎层时点/补价诚实化、守护进程重试语义与状态级去重；(C) 研究护栏（闸门与回测同源、无套利网格、full_scan 隐式丢弃与结论矛盾、结构性零）；(D) 测试体系（补 guard.py 11 条 fail-closed 用例、减少源码文本锁、selftest or True）；(E) 低危 16 条。每项都要跑通全量验收（回归/安全/研究/verify_docs/calibrate --check）。",
                   "phase":"complete","maxGoalRounds":12},"roundsStarted":1,
                   "createdAt":1790514981677,"updatedAt":1790519248671}}""",
            ),
        )
        assertTrue("a goal line stays one line: $completed", completed != null && completed.length <= "goal complete: ".length + 120)
        assertTrue("and says what happened: $completed", completed!!.startsWith("goal complete: 按用户 2026-09-27"))
        // No operation, no row: this decoder is not the place to guess.
        assertNull(
            EventPayload.goalChange(
                event("""{"type":"goal/change","seq":11,"time":0,"data":{"kind":"goal/change","version":1}}"""),
            ),
        )
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
