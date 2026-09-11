package io.github.longislandicetea.dshnative

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Does a person's own message survive the transcript rebuild? */
class UserMessageSurvivalTest {
    private fun userMessage(seq: Long, turn: Int, text: String) = SessionEvent(
        seq = seq,
        type = "user/message",
        time = 0,
        data = buildJsonObject {
            put("turn", turn)
            put("source", buildJsonObject { put("kind", "user") })
            put("message", buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", text) })
                })
            })
        },
    )

    private fun assistantMessage(seq: Long, turn: Int) = SessionEvent(
        seq = seq,
        type = "assistant/message",
        time = 0,
        data = buildJsonObject {
            put("turn", turn)
            put("usage", buildJsonObject { put("inputTokens", 5); put("outputTokens", 7) })
            put("message", buildJsonObject {
                put("role", "assistant")
                put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", "reply") })
                })
            })
        },
    )

    private fun turnEnd(seq: Long, turn: Int) = SessionEvent(
        seq = seq,
        type = "turn/end",
        time = 0,
        data = buildJsonObject { put("turn", turn) },
    )

    /**
     * Rows sort by seq however their key is spelled.
     *
     * The regression this pins: keys became `turn:<turn>:<seq>`, the sorter still
     * understood only `seq-<n>`, and every turn-keyed row therefore sorted to
     * `Long.MAX_VALUE` -- so replies and tool calls moved to the end of the
     * transcript while the reader's own messages sat at the top, which is what
     * "my messages disappeared" looked like on screen.
     */
    @Test
    fun a_row_key_yields_its_seq_in_every_form() {
        assertEquals(42L, seqOfKey("seq-42"))
        assertEquals(42L, seqOfKey("turn:3:42"))
        assertEquals(42L, seqOfKey("usage:3:42"))
        assertNull("a key with no seq sorts last, it does not throw", seqOfKey("live"))
    }

    /** The order a replayed history is drawn in is the order it happened in. */
    @Test
    fun rows_keep_their_order_across_key_forms() {
        val keys = listOf("turn:1:9", "seq-10", "turn:1:11", "usage:1:12", "turn:2:13")
        val sorted = keys.sortedBy { seqOfKey(it) ?: Long.MAX_VALUE }
        assertEquals(keys, sorted)
    }

    @Test
    fun a_persons_message_survives_the_rebuild() {
        val rows = usageAwareItems(
            listOf(
                userMessage(1, 1, "hello there"),
                assistantMessage(2, 1),
                turnEnd(3, 1),
                userMessage(4, 2, "second one"),
                assistantMessage(5, 2),
                turnEnd(6, 2),
            ),
        )
        val users = rows.mapNotNull { it as? TranscriptItem.User }
        assertEquals("both of the person's messages survive", 2, users.size)
        assertEquals(listOf("hello there", "second one"), users.map { it.text })
        assertEquals("and one usage row per turn", 2, rows.count { it is TranscriptItem.Usage })
    }
}
