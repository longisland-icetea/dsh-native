package io.github.longislandicetea.dshnative

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three surfaces added for parity: usage, the queue, and the command menu.
 *
 * Every number here came off a live Host, so the shapes are the ones the app
 * actually receives rather than the ones the types suggest.
 */
class UsageAndQueueTest {

    // ---- usage -------------------------------------------------------------

    /**
     * Cache reads count toward the total. A cached token was still sent and still
     * occupies the conversation; leaving it out makes a long session look cheaper
     * the more it reused its own context -- measured on a real session, the
     * difference between 1.3M and 65M.
     */
    @Test
    fun total_counts_cache_reads() {
        val metrics = Metrics(
            usage = TokenUsage(
                uncachedInputTokens = 1_000,
                outputTokens = 2_000,
                cacheReadTokens = 97_000,
                cacheWriteTokens = 0,
            ),
        )
        assertEquals(100_000, metrics.totalTokens)
        assertEquals(2_000, metrics.outputTokens)
    }

    /** Without a window there is no fraction to show, so the meter must hide. */
    @Test
    fun the_meter_needs_a_window() {
        assertNull(Metrics(pressure = ContextPressure(pressureTokens = 500, contextWindow = 0)).usedFraction())
        assertNull(Metrics().usedFraction())
        assertEquals(
            0.5,
            Metrics(pressure = ContextPressure(pressureTokens = 500_000, contextWindow = 1_000_000))
                .usedFraction()!!,
            0.0001,
        )
    }

    /** Pressure, not the projection: the projection includes the next request. */
    @Test
    fun the_meter_uses_stored_pressure_not_the_projection() {
        val metrics = Metrics(
            pressure = ContextPressure(
                pressureTokens = 10_000,
                projectedTokens = 40_000,
                contextWindow = 100_000,
            ),
        )
        assertEquals(10_000, metrics.usedTokens)
        assertEquals(0.1, metrics.usedFraction()!!, 0.0001)
    }

    @Test
    fun tokens_are_shortened_by_truncation_not_rounding() {
        assertEquals("999", compactTokens(999))
        assertEquals("1.0K", compactTokens(1_000))
        assertEquals("12.4K", compactTokens(12_499))
        assertEquals("1.2M", compactTokens(1_262_612))
        assertEquals("65.0M", compactTokens(65_000_000))
    }

    @Test
    fun durations_read_as_seconds_then_minutes_then_hours() {
        assertEquals("6.5s", compactDuration(6_598))
        assertEquals("1m 0s", compactDuration(60_000))
        assertEquals("52m 33s", compactDuration(3_153_677))
        assertEquals("1h 0m", compactDuration(3_600_000))
    }

    /** Largest first: the row that answers "what is eating the window" leads. */
    @Test
    fun the_breakdown_is_ordered_by_size() {
        val rows = breakdownRows(ContextBreakdown(systemTokens = 1_720, toolsTokens = 7_699, messageTokens = 351_398))
        assertEquals(listOf("Conversation", "Tool definitions", "System prompt"), rows.map { it.label })
    }

    @Test
    fun zero_rows_are_left_out() {
        assertTrue(breakdownRows(ContextBreakdown(messageTokens = 0)).isEmpty())
        assertTrue(breakdownRows(null).isEmpty())
    }

    /** A summary's projections are enough to seed the meter before the stream. */
    @Test
    fun metrics_come_from_a_summary_projection_bag() {
        val values = ProjectionValues(
            tokenUsage = TokenUsage(uncachedInputTokens = 5, outputTokens = 5, cacheReadTokens = 0),
            contextPressure = ContextPressure(pressureTokens = 25, contextWindow = 100),
            contextBreakdown = ContextBreakdown(systemTokens = 1, toolsTokens = 2, messageTokens = 3),
        )
        val metrics = Metrics.from(values)
        assertFalse(metrics.isEmpty)
        assertEquals(0.25, metrics.usedFraction()!!, 0.0001)
        assertEquals(3, breakdownRows(metrics.breakdown).first().tokens)
    }

    // ---- queue -------------------------------------------------------------

    /** The frame shape measured from `session/control`. */
    private fun queueFrame(placement: String = "queued", text: String = "second thoughts") = buildJsonArray {
        add(buildJsonObject {
            put("id", "message-1")
            put("placement", placement)
            put("message", buildJsonObject {
                put("id", "message-1")
                put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", text) })
                })
            })
        })
    }

    @Test
    fun a_queue_frame_becomes_rows() {
        val items = QueueCodec.parse(queueFrame())
        assertEquals(1, items.size)
        assertEquals("message-1", items[0].id)
        assertEquals("second thoughts", items[0].text)
        assertFalse(items[0].steering)
        assertTrue(items[0].editable)
    }

    @Test
    fun a_steering_row_is_marked_and_not_offered_again() {
        val item = QueueCodec.parse(queueFrame(placement = "steering")).single()
        assertTrue(item.steering)
        assertTrue(item.editable)
        assertFalse("a row already going in cannot be steered again", QueueView.canSteer(running = true, item = item))
        assertTrue(QueueView.canSteer(running = true, item = QueueCodec.parse(queueFrame()).single()))
        assertFalse("nothing to steer when no turn is running", QueueView.canSteer(running = false, item = QueueCodec.parse(queueFrame()).single()))
    }

    /** A row whose blocks this build cannot read is dropped, not shown broken. */
    @Test
    fun an_unreadable_row_is_dropped() {
        val noId = buildJsonArray { add(buildJsonObject { put("placement", "queued") }) }
        assertTrue(QueueCodec.parse(noId).isEmpty())
        assertTrue(QueueCodec.parse(null).isEmpty())
    }

    /** A non-text row can be removed and steered but not edited. */
    @Test
    fun a_non_text_row_is_not_editable() {
        val withImage = buildJsonArray {
            add(buildJsonObject {
                put("id", "m2")
                put("placement", "queued")
                put("message", buildJsonObject {
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "image") })
                    })
                })
            })
        }
        val item = QueueCodec.parse(withImage).single()
        assertFalse(item.editable)
        // It still needs a label, so the preview or the fallback shows something.
        assertEquals("", item.label)
    }

    @Test
    fun the_count_label_names_what_is_waiting() {
        assertNull(QueueView.countLabel(emptyList()))
        assertEquals("1 message waiting", QueueView.countLabel(QueueCodec.parse(queueFrame())))
        assertEquals(
            "2 messages waiting",
            QueueView.countLabel(QueueCodec.parse(queueFrame()) + QueueCodec.parse(queueFrame(placement = "steering"))),
        )
    }

    /** The three mutations the Host's `QueueAction` declares. */
    @Test
    fun queue_actions_match_the_host_vocabulary() {
        assertEquals("edit", QueueAction.edit("hi").getValue("kind").toString().trim('"'))
        assertTrue(QueueAction.edit("hi").getValue("content").toString().contains("hi"))
        assertEquals("remove", QueueAction.remove().getValue("kind").toString().trim('"'))
        assertEquals("steer", QueueAction.steer().getValue("kind").toString().trim('"'))
    }

    // ---- command menu ------------------------------------------------------

    private val commands = listOf(
        CommandInfo("compact", "Compact older conversation history"),
        CommandInfo("export", "Download this Session log as a ZIP archive"),
        CommandInfo("feedback", "record feedback about this session", CommandInput(hint = "<text>")),
        CommandInfo("permission", "Switch the permission preset (sandbox mode + approval policy)", CommandInput(hint = "<preset>")),
        CommandInfo("plan", "Enter or leave plan mode", CommandInput(hint = "[off|message]", attachments = true)),
        CommandInfo("goal", "set or view the goal for a long-running task", CommandInput(hint = "[<objective>|clear]")),
    )

    /**
     * A slash only starts a command at the beginning of the draft. Mid-sentence it
     * is punctuation, and a menu that opened there would fight the reader.
     */
    @Test
    fun the_menu_opens_only_for_a_leading_slash() {
        assertTrue(CommandMenu.isOpen("/"))
        assertTrue(CommandMenu.isOpen("/com"))
        assertFalse(CommandMenu.isOpen("see /com"))
        assertFalse(CommandMenu.isOpen("http://example.com"))
        assertEquals("com", CommandMenu.query("  /com"))
    }

    /** A space ends the name: what follows is an argument, not a suggestion. */
    @Test
    fun a_space_closes_the_menu() {
        assertNull(CommandMenu.query("/permission workspace-write"))
        assertFalse(CommandMenu.isOpen("/compact "))
    }

    @Test
    fun suggestions_match_the_name_first_then_the_description() {
        val byName = CommandMenu.suggestions("/co", commands)
        assertEquals(listOf("compact"), byName.map { it.command.name })

        val byDescription = CommandMenu.suggestions("/zip", commands)
        assertEquals(listOf("export"), byDescription.map { it.command.name })
        assertTrue(byDescription.all { it.line == "/export" })
    }

    @Test
    fun every_command_is_offered_for_a_bare_slash() {
        assertEquals(commands.size, CommandMenu.suggestions("/", commands).size)
    }

    /** No arguments means picking it can send it; a hint means the reader decides. */
    @Test
    fun commands_that_take_no_arguments_run_on_pick() {
        assertTrue(commands.first { it.name == "compact" }.runsOnPick)
        assertTrue(commands.first { it.name == "export" }.runsOnPick)
        assertFalse(commands.first { it.name == "permission" }.runsOnPick)
        assertFalse(commands.first { it.name == "feedback" }.runsOnPick)
    }

    @Test
    fun a_picked_command_leaves_a_usable_line() {
        val pick = CommandMenu.suggestions("/pl", commands).single()
        assertEquals("/plan ", CommandMenu.lineFor(pick.command))
        assertEquals("/plan", CommandMenu.commandLine("/plan "))
        assertEquals("/permission workspace-write", CommandMenu.commandLine("/permission workspace-write "))
        assertNull("a bare slash is not a command", CommandMenu.commandLine("/"))
        assertNull("ordinary prose is not a command", CommandMenu.commandLine("hello"))
        assertNull("so is a slash inside a word", CommandMenu.commandLine("and/or"))
    }
}
