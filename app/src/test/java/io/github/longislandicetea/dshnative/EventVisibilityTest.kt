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
 * a machinery namespace that must stay silent, and the fallback that must keep
 * naming what it does not know. PAYLOADS ARE COPIED FROM A REAL
 * `session.v4.jsonl.zstd` LOG, per `docs/event-coverage.md`.
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
