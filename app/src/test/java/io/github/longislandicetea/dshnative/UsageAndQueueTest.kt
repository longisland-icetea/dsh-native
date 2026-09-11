package io.github.longislandicetea.dshnative

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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

    // ---- the two bugs the first version of the usage row shipped with -------

    /**
     * The turn's cost is a row the reducer produces, not one the renderer derives.
     *
     * The first version looked the turn up from the row key while drawing -- and
     * the key format it assumed was not the one in use, so the figure never
     * appeared, and it was drawn in the wrong place even when it did. Keeping the
     * decision in the reducer is what makes it testable at all.
     */
    @Test
    fun usage_is_a_row_of_its_own() {
        val item = TranscriptItem.Usage(
            key = "usage:4",
            usage = TokenUsage(uncachedInputTokens = 10, outputTokens = 20),
            turn = 4,
        )
        assertEquals("usage:4", item.key)
        assertEquals(4, item.turn)
        assertEquals(30, item.usage.uncachedInputTokens + item.usage.outputTokens)
    }

    /**
     * Only a status frame turns the composer's Stop back into Send.
     *
     * Inferred from the transcript it went wrong whenever a turn ended with a row
     * that still read "running", leaving Stop on screen after the turn was over.
     * The Host reports the flag; the app now only copies it.
     */
    @Test
    fun running_comes_from_the_host_status_not_from_the_transcript() {
        // The event name the Host uses, and the argument order the decoder reads.
        val frame = SessionDelta.from("api-session/status", listOf(JsonPrimitive("session-1"), JsonPrimitive(false)))
        assertEquals(SessionDelta.Running("session-1", false), frame)
        assertNull("a status frame for another shape is not a running flag", SessionDelta.from("api-session/status", listOf(JsonPrimitive("session-1"))))
    }

    // ---- per-turn usage: the part that silently rendered nothing twice ------

    /**
     * A turn's cost is the sum of its assistant messages.
     *
     * Measured shape: `assistant/message` carries `{turn, step, message, usage,
     * stream}` and `usage` is `{inputTokens, outputTokens, totalTokens,
     * cacheReadTokens, reasoningTokens}` -- 104 of 104 assistant messages in a
     * sampled session carried it. A turn is several steps, so its row is their
     * sum and not the last one's.
     */
    @Test
    fun a_turn_is_the_sum_of_its_steps() {
        val usage = turnUsageOf(
            listOf(
                event(seq = 10, type = "assistant/message", turn = 1, output = 100, cached = 900),
                event(seq = 11, type = "assistant/message", turn = 1, output = 50, cached = 100),
            ),
        )
        assertEquals(150, usage[1]!!.outputTokens)
        assertEquals(1_000, usage[1]!!.cacheReadTokens)
    }

    /**
     * A finished turn keeps its total.
     *
     * It has to: the turn's row and the session diagram are both read from this
     * after the turn closed. The first version dropped the turn at `turn/end`,
     * which left the row with nothing to look up -- and the row silently never
     * appeared, which is the bug this test exists because of.
     */
    @Test
    fun a_closed_turn_keeps_its_total() {
        val usage = turnUsageOf(
            listOf(
                event(seq = 10, type = "assistant/message", turn = 1, output = 100),
                event(seq = 11, type = "turn/end", turn = 1),
                event(seq = 12, type = "assistant/message", turn = 2, output = 7),
            ),
        )
        assertEquals(100, usage[1]!!.outputTokens)
        assertEquals(7, usage[2]!!.outputTokens)
    }

    /**
     * A replayed event counts once. Reconnecting replays a window that overlaps
     * what is already on screen, and counting twice would inflate every turn after
     * a reconnect -- the failure mode is invisible, which is why it is pinned.
     */
    @Test
    fun a_replayed_event_is_counted_once() {
        val replayed = listOf(
            event(seq = 10, type = "assistant/message", turn = 1, output = 100),
            event(seq = 10, type = "assistant/message", turn = 1, output = 100),
            event(seq = 11, type = "assistant/message", turn = 1, output = 100),
        )
        assertEquals(200, turnUsageOf(replayed)[1]!!.outputTokens)
    }

    /** Machinery with no usage contributes nothing and does not throw. */
    @Test
    fun events_without_usage_are_skipped() {
        val usage = turnUsageOf(
            listOf(
                event(seq = 1, type = "turn/start", turn = 1),
                event(seq = 2, type = "tool/call", turn = 1),
                event(seq = 3, type = "assistant/message", turn = 1, output = 5),
            ),
        )
        assertEquals(1, usage.size)
        assertEquals(5, usage[1]!!.outputTokens)
    }

    /** The row lands after its turn's closing row, and once only. */
    @Test
    fun usage_rows_land_after_their_turn() {
        val records = listOf(
            event(seq = 10, type = "assistant/message", turn = 1, output = 100),
            event(seq = 11, type = "turn/end", turn = 1),
            // A replay of the same turn/end must not add a second row.
            event(seq = 11, type = "turn/end", turn = 1),
            event(seq = 12, type = "assistant/message", turn = 2, output = 7),
        )
        val placed = usageRowsFor(records)
        assertEquals("one row placed", 1, placed.size)
        assertEquals("placed after the turn/end at index 1", 1, placed.single().first)
        assertEquals(1, placed.single().second.turn)
        assertEquals(100, placed.single().second.usage.outputTokens)
    }

    /** A turn whose messages carried no usage gets no row, rather than a zero. */
    @Test
    fun a_turn_without_usage_gets_no_row() {
        val records = listOf(
            event(seq = 10, type = "turn/start", turn = 1),
            event(seq = 11, type = "turn/end", turn = 1),
        )
        assertTrue(usageRowsFor(records).isEmpty())
    }

    /** Raw wire objects, in the shape the Host sends them. */
    private fun event(seq: Long, type: String, turn: Int, output: Long = 0, cached: Long = 0) =
        buildJsonObject {
            put("seq", seq)
            put("type", type)
            put("data", buildJsonObject {
                put("turn", turn)
                if (type == "assistant/message") {
                    put("usage", buildJsonObject {
                        put("inputTokens", 10)
                        put("outputTokens", output)
                        put("cacheReadTokens", cached)
                        put("reasoningTokens", 1)
                    })
                }
            })
        }
}
