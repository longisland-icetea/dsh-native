package io.github.longislandicetea.dshnative

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live bubble, against the deltas a Host really sends.
 *
 * It existed for several releases without ever holding a character: the decoder
 * accepted `type == "text"`, the wire says `text-delta`, and nothing failed --
 * a stream that renders nothing looks exactly like a slow model. The capture
 * this replays is the only reason the difference is visible at all.
 */
class LiveStreamTest {
    private val capture = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/steer-session.json")).bufferedReader().readText(),
    ).jsonObject

    /** Replay, reporting the longest the live bubble ever got and where it ended. */
    private fun replay(): Pair<String, String> {
        var state = TranscriptFold(
            Conversation(sessionId = capture["sessionId"]!!.jsonPrimitive.content),
        )
        var longest = ""
        for (frame in capture["follow"]!!.jsonArray) {
            state = foldFollowFrame(state, FollowCodec.decode(frame))
            val live = state.conversation.liveText
            if (live.length > longest.length) longest = live
        }
        return longest to state.conversation.liveText
    }

    @Test
    fun a_streamed_reply_fills_the_live_bubble_while_it_streams() {
        val (longest, _) = replay()
        assertTrue("the reply streamed, so the bubble held it: got \"$longest\"", longest.contains("DONE-A"))
    }

    @Test
    fun the_live_bubble_lets_go_once_the_message_is_logged() {
        val (_, settled) = replay()
        assertEquals("otherwise every reply would be on screen twice", "", settled)
    }

    @Test
    fun a_text_delta_carries_its_token() {
        assertEquals("D", EventPayload.chunkText(chunk("text-delta")))
    }

    @Test
    fun a_reasoning_delta_is_still_dropped() {
        assertNull("chain of thought is not the answer", EventPayload.chunkText(chunk("reasoning-delta")))
    }

    @Test
    fun a_finished_block_is_still_read() {
        val block = buildJsonObject {
            put("type", JsonPrimitive("text"))
            put("text", JsonPrimitive("a whole block"))
        }
        assertEquals("a whole block", EventPayload.chunkText(block))
    }

    private fun chunk(kind: String) = buildJsonObject {
        put("type", JsonPrimitive(kind))
        put("index", JsonPrimitive(1))
        put("text", JsonPrimitive("D"))
    }
}
