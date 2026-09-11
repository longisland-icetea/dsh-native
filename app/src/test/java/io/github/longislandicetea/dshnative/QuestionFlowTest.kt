package io.github.longislandicetea.dshnative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The answer rules, read off the web client so both clients behave identically.
 *
 * The interesting parts are the interactions, not the data: choosing an option
 * clears typed text, typing clears the selection, a multi-select question keeps
 * both, and a skipped question answers with nothing. Each of those is a decision
 * a reader would notice if it went the other way.
 */
class QuestionFlowTest {

    private fun question(
        id: String,
        options: List<String> = listOf("alpha", "beta"),
        multiSelect: Boolean? = null,
    ) = QuestionItem(
        id = id,
        question = "Question $id",
        options = options.map { QuestionOption(label = it) },
        multiSelect = multiSelect,
    )

    @Test
    fun drafts_start_unanswered() {
        val drafts = QuestionFlow.drafts(listOf(question("a"), question("b")))
        assertEquals(2, drafts.size)
        assertTrue(drafts.none { it.answered() })
        assertTrue(drafts.none { it.completed() })
        assertEquals(0, QuestionFlow.firstIncomplete(drafts))
    }

    /**
     * A single-select choice is one answer, so typing is discarded: keeping both
     * would send a selection the reader believes they replaced.
     */
    @Test
    fun choosing_on_single_select_replaces_selection_and_clears_text() {
        var draft = QuestionFlow.type(QuestionDraft(), "typed something", multiSelect = false)
        draft = QuestionFlow.choose(draft, "alpha", multiSelect = false)
        assertEquals(listOf("alpha"), draft.selected)
        assertEquals("", draft.custom)

        // Choosing again replaces rather than accumulating.
        draft = QuestionFlow.choose(draft, "beta", multiSelect = false)
        assertEquals(listOf("beta"), draft.selected)
    }

    /** The mirror image: a typed answer is the answer for a single-select question. */
    @Test
    fun typing_on_single_select_clears_the_selection() {
        var draft = QuestionFlow.choose(QuestionDraft(), "alpha", multiSelect = false)
        draft = QuestionFlow.type(draft, "something else", multiSelect = false)
        assertTrue(draft.selected.isEmpty())
        assertEquals("something else", draft.custom)
        assertTrue(draft.answered())
    }

    /**
     * Multi-select is the case where both travel together: the Host expects the
     * ticks and the typed note in one answer.
     */
    @Test
    fun multi_select_keeps_ticks_and_text_together() {
        var draft = QuestionFlow.choose(QuestionDraft(), "alpha", multiSelect = true)
        draft = QuestionFlow.choose(draft, "beta", multiSelect = true)
        assertEquals(listOf("alpha", "beta"), draft.selected)

        draft = QuestionFlow.type(draft, "and a note", multiSelect = true)
        assertEquals(listOf("alpha", "beta"), draft.selected)
        assertEquals("and a note", draft.custom)

        // Toggling off keeps the text.
        draft = QuestionFlow.choose(draft, "alpha", multiSelect = true)
        assertEquals(listOf("beta"), draft.selected)
        assertEquals("and a note", draft.custom)
    }

    @Test
    fun skip_answers_with_nothing_and_counts_as_completed() {
        val skipped = QuestionFlow.skip(QuestionFlow.choose(QuestionDraft(), "alpha", multiSelect = false))
        assertTrue(skipped.selected.isEmpty())
        assertEquals("", skipped.custom)
        assertFalse(skipped.answered())
        assertTrue("a skipped question must not block the batch", skipped.completed())
    }

    @Test
    fun first_incomplete_reports_the_question_that_blocks_submission() {
        val drafts = listOf(
            QuestionFlow.choose(QuestionDraft(), "alpha", multiSelect = false),
            QuestionDraft(),
            QuestionFlow.skip(QuestionDraft()),
        )
        assertEquals(1, QuestionFlow.firstIncomplete(drafts))
        assertNull(QuestionFlow.firstIncomplete(drafts.mapIndexed { i, d -> if (i == 1) QuestionFlow.skip(d) else d }))
    }

    @Test
    fun batch_reports_selected_and_omits_an_absent_custom() {
        val questions = listOf(question("a"), question("b", multiSelect = true))
        val drafts = listOf(
            QuestionFlow.choose(QuestionDraft(), "alpha", multiSelect = false),
            QuestionFlow.type(QuestionFlow.choose(QuestionDraft(), "beta", multiSelect = true), "plus this", multiSelect = true),
        )
        val batch = QuestionFlow.answerBatch(questions, drafts)
        assertEquals(2, batch.size)
        assertEquals("a", batch[0].id)
        assertEquals(listOf("alpha"), batch[0].selected)
        assertNull("an ordinary answer must not carry a custom field", batch[0].custom)
        assertEquals(listOf("beta"), batch[1].selected)
        assertEquals("plus this", batch[1].custom)
    }

    /** Typed text answers a single-select question instead of its option. */
    @Test
    fun batch_drops_the_selection_when_text_answers_a_single_select() {
        val questions = listOf(question("a"))
        // Built directly rather than through `type`, which already clears it: the
        // rule has to hold for any draft that carries both.
        val draft = QuestionDraft(selected = listOf("alpha"), custom = "  my own answer  ")
        val batch = QuestionFlow.answerBatch(questions, listOf(draft))
        assertTrue(batch[0].selected.isEmpty())
        assertEquals("my own answer", batch[0].custom)
    }

    @Test
    fun batch_answers_a_skipped_question_with_an_empty_selection() {
        val batch = QuestionFlow.answerBatch(listOf(question("a")), listOf(QuestionFlow.skip(QuestionDraft())))
        assertEquals(1, batch.size)
        assertTrue(batch[0].selected.isEmpty())
        assertNull(batch[0].custom)
    }

    /**
     * The wire value is what the Host's answerer reads back. Its shape is the
     * contract: `answers[].id`, `selected` always present (an empty array is a
     * valid answer), and `custom` present only when there is text.
     */
    @Test
    fun answer_value_matches_the_host_shape() {
        val questions = listOf(question("a"), question("b"))
        val drafts = listOf(
            QuestionFlow.choose(QuestionDraft(), "alpha", multiSelect = false),
            QuestionFlow.type(QuestionDraft(), "free text", multiSelect = false),
        )
        val value = QuestionFlow.answerValue(questions, drafts)
        val expected = """
            {"answers":[
              {"id":"a","selected":["alpha"]},
              {"id":"b","selected":[],"custom":"free text"}
            ]}
        """.trimIndent().replace("\n", "").replace("  ", "")
        assertEquals(expected, value.toString())
    }
}
