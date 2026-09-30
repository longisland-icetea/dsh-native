package io.github.longislandicetea.dshnative

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which events never become transcript rows, in both directions.
 *
 * The fallback in `toItem` is deliberately loud: an event type this build has no
 * branch for becomes an activity row naming its type, which is how an
 * out-of-repo plugin's events stay visible instead of vanishing. That is also how
 * `session-log-deepseek/delivery-accepted` reached the screen -- the Host appends
 * one after *every* step, so one upstream update put a raw type name under every
 * message.
 *
 * Both halves are pinned here, because a careless fix could take either one out:
 * the machinery that must stay silent, and the fallback that must keep naming
 * what it does not know. PAYLOADS ARE COPIED FROM REAL `session.v4.jsonl.zstd`
 * LOGS, per `docs/event-coverage.md`.
 */
class EventVisibilityTest {
    private fun event(json: String): SessionEvent {
        val obj = DshWire.json.parseToJsonElement(json) as JsonObject
        return SessionEvent(
            seq = obj["seq"].toString().trim('"').toLong(),
            type = obj["type"].toString().trim('"'),
            time = 0,
            data = obj["data"]!!,
        )
    }

    /** One watermark, exactly as the upload writes it. */
    private val deliveryAccepted = event(
        """{"type":"session-log-deepseek/delivery-accepted","seq":16,"time":1790746492641,
           "data":{"sessionId":"session-40ab661c-27e5-4324-bc0f-7e20a1154832",
           "sessionFormatVersion":4,"throughSeq":15}}""",
    )

    @Test
    fun `a delivery watermark is not a row`() {
        // 4042 of these across 40 sampled sessions -- one per step, which is one
        // per message once the turn is over.
        assertNull(toItem(deliveryAccepted))
        // The replay path builds its rows through the same mapper, so a snapshot
        // or an opened session is covered by the same guard.
        assertTrue(usageAwareItems(listOf(deliveryAccepted)).isEmpty())
    }

    @Test
    fun `the hide is the namespace, not the one name that leaked`() {
        // Anything else that subsystem logs is the same fact about the
        // transport. Hiding one literal would leave the next one to leak.
        assertNull(toItem(event("""{"type":"session-log-deepseek/upload-skipped","seq":17,"time":0,"data":{}}""")))
    }

    /**
     * Everything addressed to the model rather than to the reader.
     *
     * Each of these was seen live, drawn as a row carrying nothing but its own
     * type name -- except `system/message`, which drags the system prompt in with
     * it (its text is shortened here; the shape is verbatim).
     */
    private val machinery = listOf(
        """{"type":"system/message","seq":8,"time":1790746492275,
           "data":{"turn":1,"step":1,"message":{"role":"system","content":[
           {"type":"text","text":"You are an AI agent powered by DeepSeek Harness..."}]}}}""",
        """{"type":"developer/message","seq":13071,"time":1790680135448,
           "data":{"turn":130,"step":1,"message":{"source":{"kind":"tool-registry"},
           "content":[{"type":"tool-removal","toolName":"ralph"}],"role":"developer","id":"72710ce4"}},
           "surfaceOp":"append"}""",
        """{"type":"web/deepseek-search-llm-request","seq":162,"time":1790417942623,
           "data":{"endpoint":"https://api.deepseek.com/anthropic/v1/messages","apiVersion":"2023-06-01",
           "body":{"model":"deepseek-v4-flash","max_tokens":4096,"messages":[
           {"role":"user","content":[{"type":"text","text":"Perform a web search for the query: moire"}]}]}}}""",
        """{"type":"workspace/changes","seq":3757,"time":1790608731987,"data":{"turn":17}}""",
        // The dock draws the subagent roster from its own stream, not from here.
        """{"type":"subagent/catalog","seq":221,"time":1790418052638,
           "data":{"version":0,"childId":"00fc6d57-c555-42ea-a0a1-853ee7be2aa5",
           "childCreatedAt":1790418052619,"mode":"continuable","label":"Research moire work"}}""",
        """{"type":"subagent/descriptor","seq":4063,"time":1790659970885,
           "data":{"version":3,"mode":"continuable","provider":"fork","label":"Audit the prompt",
           "agentProvider":"deepseek-official","agentModel":"deepseek-flash","agentReasoningEffort":"max"}}""",
        """{"type":"subagent/model-selection-policy","seq":3,"time":1790746462139,
           "data":{"allowedModels":[{"provider":"workbench","model":"flash"}]}}""",
        // The second half of a retry whose first half is a note; two rows for one
        // fact is what the reader complained about.
        """{"type":"llm/retry-started","seq":3686,"time":1790608673828,
           "data":{"retryId":"41cc6cf7-1e3c-4e25-be3e-7d28f458aeba","turn":17,"step":38,"retry":1}}""",
    )

    @Test
    fun `what the model is told is not what the reader is shown`() {
        machinery.forEach { payload ->
            val row = toItem(event(payload))
            assertNull("a row for ${payload.take(60)}: $row", row)
        }
        // Nothing in that list may come back through the replay path either.
        assertTrue(usageAwareItems(machinery.map(::event)).isEmpty())
    }

    @Test
    fun `a retry is one note, not two rows`() {
        // The pair the Host writes per attempt, from one stalled session: the
        // first carries the failure and the attempt number, the second only says
        // the next request went out.
        val retry = toItem(
            event(
                """{"type":"llm/retry","seq":3685,"time":1790608673370,
                   "data":{"retryId":"41cc6cf7-1e3c-4e25-be3e-7d28f458aeba","turn":17,"step":38,
                   "provider":"deepseek-official","mode":"normal","retry":1,"maxRetries":5,
                   "delayMs":458.9688524694306,
                   "failure":{"message":"DeepSeek Messages transport failed","code":"TRANSPORT"}}}""",
            ),
        )
        assertTrue("a note, not a raw type name: $retry", retry is TranscriptItem.Note)
        assertEquals(
            "retrying (attempt 1 of 5) after TRANSPORT: DeepSeek Messages transport failed",
            (retry as TranscriptItem.Note).text,
        )
    }

    @Test
    fun `an event type this build does not know still names itself`() {
        // The discovery mechanism, and the reason the leak was findable at all:
        // a plugin's event is not conversation either, but it is *unclassified*,
        // and silently dropping it is how a whole feature goes missing.
        val row = toItem(event("""{"type":"acme/widget-spun","seq":18,"time":0,"data":{"widgets":3}}"""))
        assertTrue("named, not dropped: $row", row is TranscriptItem.Activity)
        assertEquals("acme/widget-spun", (row as TranscriptItem.Activity).label)
    }
}
