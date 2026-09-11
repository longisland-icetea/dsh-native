package io.github.longislandicetea.dshnative

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A `user-questions/request` waterfall must become a pending interaction.
 *
 * This is the path that matters when the question was asked while the app was
 * away: the Host replays pending waterfalls to a newly connected client, so the
 * frame below is what arrives on a fresh connection -- there is no user message
 * to lean on, and a projection that drops the frame leaves the question invisible
 * while the agent waits.
 *
 * The payloads are the shapes measured from a live Host, not invented: the
 * request is the question object itself, the array lives under `questions`, and
 * the item's fields are camelCase on the wire.
 */
class PendingQuestionTest {

    /** One question with the optional fields a real asker filled in. */
    private val oneQuestion = buildJsonObject {
        put("questions", buildJsonArray {
            add(buildJsonObject {
                put("id", "alpha_beta")
                put("question", "Choose one")
                put("header", "First")
                put("options", buildJsonArray {
                    add(buildJsonObject {
                        put("label", "alpha")
                        put("description", "the first one")
                    })
                    add(buildJsonObject { put("label", "beta") })
                })
                put("multi_select", false)
            })
        })
    }

    private fun waterfall(
        event: String = "user-questions/request",
        request: JsonObject,
    ) = HostEvent.Waterfall(
        event = event,
        eventId = "event-1",
        agentId = "session-abc",
        request = request,
    )

    @Test
    fun one_question_request_becomes_a_pending_question() {
        val pending = PendingInteraction.from(waterfall(request = oneQuestion))
        assertNotNull("the waterfall was dropped instead of presented", pending)
        val interaction = pending!!
        assertEquals("session-abc", interaction.sessionId)
        assertEquals("event-1", interaction.eventId)
        assertEquals(1, interaction.questions.size)
        assertEquals("alpha_beta", interaction.questions[0].id)
        assertEquals("Choose one", interaction.questions[0].question)
        assertEquals(listOf("alpha", "beta"), interaction.questions[0].options.map { it.label })
    }

    /**
     * The Host omits optional fields rather than sending nulls, and `multi_select`
     * is absent for a single-select question. A decoder that requires them turns
     * every ordinary question into a dropped frame.
     */
    @Test
    fun absent_optional_fields_do_not_drop_the_question() {
        val bare = buildJsonObject {
            put("questions", buildJsonArray {
                add(buildJsonObject {
                    put("id", "q1")
                    put("question", "Are you there?")
                })
            })
        }
        val pending = PendingInteraction.from(waterfall(request = bare))
        assertNotNull("a question without header/options was dropped", pending)
        val interaction = pending!!
        assertEquals(0, interaction.questions[0].options.size)
        assertNull(interaction.questions[0].multiSelect)
        assertNull(interaction.questions[0].header)
    }

    /** More than one question is not a plan review, however few options each has. */
    @Test
    fun several_questions_stay_a_question() {
        val many = buildJsonObject {
            put("questions", buildJsonArray {
                add(buildJsonObject { put("id", "a"); put("question", "A?") })
                add(buildJsonObject { put("id", "b"); put("question", "B?") })
            })
        }
        val pending = PendingInteraction.from(waterfall(request = many))
        assertNotNull(pending)
        assertEquals("question", pending!!.kind)
    }

    /**
     * A plan review is one question with a detail and at most two options. Losing
     * `detail` would silently reclassify every plan review as a plain question.
     */
    @Test
    fun single_question_with_detail_is_a_plan_review() {
        val review = buildJsonObject {
            put("questions", buildJsonArray {
                add(buildJsonObject {
                    put("id", "plan")
                    put("question", "Approve this plan?")
                    put("detail", "the plan text")
                    put("options", buildJsonArray {
                        add(buildJsonObject { put("label", "Approve") })
                        add(buildJsonObject { put("label", "Reject") })
                    })
                })
            })
        }
        val pending = PendingInteraction.from(waterfall(request = review))
        assertNotNull(pending)
        assertEquals("plan-review", pending!!.kind)
        assertEquals("the plan text", pending.questions[0].detail)
    }

    /** An unknown waterfall is declined, not presented as something it is not. */
    @Test
    fun unknown_event_is_not_a_pending_interaction() {
        assertNull(PendingInteraction.from(waterfall(event = "something/else", request = oneQuestion)))
    }

    /** An empty batch is not worth a card, and answering it would be meaningless. */
    @Test
    fun empty_question_list_is_dropped() {
        val empty = buildJsonObject { put("questions", buildJsonArray { }) }
        assertNull(PendingInteraction.from(waterfall(request = empty)))
    }
}
