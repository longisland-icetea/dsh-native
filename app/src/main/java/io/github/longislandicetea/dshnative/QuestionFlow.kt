package io.github.longislandicetea.dshnative

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One question's answer while the reader is still working through the batch.
 *
 * The batch is answered one question at a time, and the Host receives all of them
 * together at the end, so every question the reader has not reached yet needs a
 * placeholder. `skipped` is a deliberate answer -- an empty selection -- rather
 * than the absence of one.
 */
data class QuestionDraft(
    val selected: List<String> = emptyList(),
    val custom: String = "",
    val skipped: Boolean = false,
) {
    /** A question counts as answered once an option is chosen or text is typed. */
    fun answered(): Boolean = selected.isNotEmpty() || custom.trim().isNotEmpty()

    /** Answered or deliberately skipped; otherwise the batch cannot be submitted. */
    fun completed(): Boolean = answered() || skipped
}

/**
 * The rules the web client uses to turn drafts into the waterfall's answer batch.
 *
 * These are not this client's invention: they are read off `dsh-client-ui-user-questions`
 * so both clients answer identically, and they exist here as pure functions
 * because the interesting parts are the interactions -- selecting an option
 * clears typed text, typing text clears the selection, and a skipped question
 * answers with nothing at all -- and those are worth pinning with tests rather
 * than discovering on a phone.
 */
object QuestionFlow {

    /** Drafts, one per question, all untouched. */
    fun drafts(questions: List<QuestionItem>): List<QuestionDraft> = List(questions.size) { QuestionDraft() }

    /**
     * Choose one option.
     *
     * A multi-select question toggles, and its typed text survives because the two
     * are sent together. A single-select question replaces the selection and
     * clears any typed text: one answer is being given, and leaving both would
     * send a selection the reader believes they replaced.
     */
    fun choose(draft: QuestionDraft, label: String, multiSelect: Boolean): QuestionDraft = when {
        multiSelect -> draft.copy(
            selected = if (draft.selected.contains(label)) draft.selected - label else draft.selected + label,
            skipped = false,
        )
        else -> QuestionDraft(selected = listOf(label))
    }

    /**
     * Type a custom answer.
     *
     * The mirror image of [choose]: for a single-select question the typed answer
     * replaces the selection, because the reader is answering rather than adding.
     */
    fun type(draft: QuestionDraft, text: String, multiSelect: Boolean): QuestionDraft = draft.copy(
        selected = if (multiSelect) draft.selected else emptyList(),
        custom = text,
        skipped = false,
    )

    /** Mark a question skipped: its answer is an empty selection. */
    fun skip(draft: QuestionDraft): QuestionDraft = QuestionDraft(skipped = true)

    /**
     * A question that still needs the reader before the batch can move on, or
     * null when every question is answered or skipped.
     *
     * Nullable rather than the `-1` that `indexOfFirst` would return: the caller
     * uses the index to jump to the blocking question, and a caller that forgets
     * to check for `-1` would jump to the last question instead.
     */
    fun firstIncomplete(drafts: List<QuestionDraft>): Int? =
        drafts.indexOfFirst { !it.completed() }.takeIf { it >= 0 }

    /** The batch the Host receives, keyed by the caller's question ids. */
    fun answerBatch(questions: List<QuestionItem>, drafts: List<QuestionDraft>): List<QuestionAnswerItem> =
        questions.mapIndexed { index, question ->
            val draft = drafts.getOrNull(index) ?: QuestionDraft()
            val multiSelect = question.multiSelect == true
            if (draft.skipped) {
                // Answered with nothing: the asker's own "no answer" case.
                QuestionAnswerItem(id = question.id, selected = emptyList())
            } else {
                val custom = draft.custom.trim()
                QuestionAnswerItem(
                    id = question.id,
                    // Typed text answers a single-select question *instead of* the
                    // option, so the selection is dropped here rather than at type
                    // time alone -- a draft edited by other means could carry both.
                    selected = if (custom.isEmpty() || multiSelect) draft.selected else emptyList(),
                    custom = custom.ifEmpty { null },
                )
            }
        }

    /**
     * The waterfall's return value for this batch.
     *
     * Built as JSON by hand rather than through the serializer so an absent
     * `custom` stays absent: the Host reads `custom` as optional, and sending an
     * explicit null is a different thing from not sending it.
     */
    fun answerValue(questions: List<QuestionItem>, drafts: List<QuestionDraft>): JsonObject = buildJsonObject {
        put("answers", buildJsonArray {
            answerBatch(questions, drafts).forEach { item ->
                add(buildJsonObject {
                    put("id", JsonPrimitive(item.id))
                    put("selected", buildJsonArray { item.selected.forEach { add(JsonPrimitive(it)) } })
                    item.custom?.let { put("custom", JsonPrimitive(it)) }
                })
            }
        })
    }
}
