package io.github.longislandicetea.dshnative

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slash commands, from the wire shape outwards.
 *
 * `/compact` did nothing on the phone: the gateway's descriptor requires
 * `submittedAttachments` and this client did not send it, so *every* command was
 * refused with `arguments-invalid` and the app logged a failure nobody could see.
 */
class CommandLineTest {
    @Test
    fun the_execute_call_carries_every_field_the_gateway_requires() {
        val args = commandExecuteArgs("session-1", "/compact")
        assertEquals(setOf("agentId", "line", "submittedAttachments"), args.keys)
        assertEquals(JsonPrimitive("session-1"), args["agentId"])
        assertEquals(JsonPrimitive("/compact"), args["line"])
        assertEquals("an empty list, not an absent field", JsonArray(emptyList()), args["submittedAttachments"])
    }

    private fun event(type: String, data: kotlinx.serialization.json.JsonObject) =
        SessionEvent(seq = 1, type = type, time = 0, data = data)

    @Test
    fun a_command_run_reads_as_the_line_the_reader_typed() {
        val withArgs = EventPayload.commandRun(
            event("command/run", buildJsonObject {
                put("commandId", JsonPrimitive("cmd-1"))
                put("name", JsonPrimitive("permission"))
                put("args", JsonPrimitive("danger-full-access"))
            }),
        )
        assertEquals("/permission danger-full-access", withArgs)

        // Commands that do not keep their input omit `args` entirely.
        val plain = EventPayload.commandRun(
            event("command/run", buildJsonObject {
                put("commandId", JsonPrimitive("cmd-2"))
                put("name", JsonPrimitive("compact"))
            }),
        )
        assertEquals("/compact", plain)
    }

    @Test
    fun a_finished_command_says_what_came_of_it() {
        val failed = EventPayload.commandOutcome(
            event("command/done", buildJsonObject {
                put("commandId", JsonPrimitive("cmd-1"))
                put("kind", JsonPrimitive("error"))
                put("text", JsonPrimitive("Compaction could not produce a useful summary."))
            }),
        )
        assertEquals("Compaction could not produce a useful summary.", failed)

        val answered = EventPayload.commandOutcome(
            event("command/done", buildJsonObject {
                put("commandId", JsonPrimitive("cmd-2"))
                put("kind", JsonPrimitive("success"))
                put("text", JsonPrimitive("No goal is currently set."))
            }),
        )
        assertEquals("No goal is currently set.", answered)

        // A success with nothing to say adds no row: the run above it already
        // said what was asked, and an empty line is noise.
        val silent = EventPayload.commandOutcome(
            event("command/done", buildJsonObject {
                put("commandId", JsonPrimitive("cmd-3"))
                put("kind", JsonPrimitive("success"))
            }),
        )
        assertEquals(null, silent)
    }

    /** The rows the transcript builds from a real command, end to end. */
    @Test
    fun both_halves_of_a_command_become_rows() {
        val run = toItem(
            event("command/run", buildJsonObject {
                put("commandId", JsonPrimitive("cmd-1"))
                put("name", JsonPrimitive("compact"))
            }),
        )
        val done = toItem(
            event("command/done", buildJsonObject {
                put("commandId", JsonPrimitive("cmd-1"))
                put("kind", JsonPrimitive("error"))
                put("text", JsonPrimitive("nothing to compact"))
            }),
        )
        assertTrue("the run is a row: $run", run is TranscriptItem.Note && run.text == "/compact")
        assertTrue("so is the outcome: $done", done is TranscriptItem.Note && done.text == "nothing to compact")
    }
}
