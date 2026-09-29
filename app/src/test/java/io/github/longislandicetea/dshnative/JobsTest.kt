package io.github.longislandicetea.dshnative

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The running-tasks button: what it counts, what it says, and what it hides.
 *
 * The button exists to answer "what is this machine doing for me right now?", so
 * a settled job must not be counted and a job that is still stopping must be --
 * the Host's own two live statuses, which the web client reads the same way.
 */
class JobsTest {
    private fun job(
        id: String,
        status: String,
        startedAt: Long = 0,
        finishedAt: Long? = null,
        kind: String = "bash",
        label: String = id,
        detail: String? = null,
    ) = HostJob(id, kind, label, status, detail, startedAt, finishedAt)

    @Test
    fun only_work_the_registry_still_holds_open_is_running() {
        val all = listOf(
            job("a", "running"),
            job("b", "stopping"),
            job("c", "completed"),
            job("d", "killed"),
            job("e", "failed"),
        )
        assertEquals(listOf("a", "b"), Jobs.live(all).map { it.id })
        assertTrue(Jobs.isLive("running"))
        assertTrue(Jobs.isLive("stopping"))
        assertFalse(Jobs.isLive("completed"))
    }

    /** Oldest first, so a list that changes under the reader does not reshuffle. */
    @Test
    fun the_list_is_ordered_by_when_the_work_started() {
        val all = listOf(job("late", "running", startedAt = 900), job("early", "running", startedAt = 100))
        assertEquals(listOf("early", "late"), Jobs.live(all).map { it.id })
    }

    /** Durations read the way the web client writes them: two units at most. */
    @Test
    fun a_duration_is_at_most_two_units() {
        assertEquals("0s", Jobs.formatDuration(0))
        assertEquals("9s", Jobs.formatDuration(9_400))
        assertEquals("1m 5s", Jobs.formatDuration(65_000))
        assertEquals("2h 3m", Jobs.formatDuration(2 * 3_600_000 + 3 * 60_000 + 59_000))
        assertEquals("a clock that went backwards is not a negative duration", "0s", Jobs.formatDuration(-5_000))
    }

    @Test
    fun a_running_job_is_measured_against_now_and_a_settled_one_against_its_end() {
        val running = job("a", "running", startedAt = 1_000)
        assertEquals(4_000, running.elapsedMillis(now = 5_000))
        val done = job("b", "completed", startedAt = 1_000, finishedAt = 3_500)
        assertEquals("a finished job stops counting", 2_500, done.elapsedMillis(now = 60_000))
    }

    /** The wire shape, as the control stream carries it. */
    @Test
    fun jobs_are_read_from_the_control_stream_shape() {
        val parsed = JobCodec.parse(
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive("job-1"))
                        put("kind", JsonPrimitive("bash"))
                        put("label", JsonPrimitive("sleep 300"))
                        put("status", JsonPrimitive("running"))
                        put("detail", JsonPrimitive("a long one"))
                        put("startedAt", JsonPrimitive(1_700_000_000_000))
                    },
                )
                add(buildJsonObject { put("kind", JsonPrimitive("task")) }) // no id: not a job
            },
        )
        assertEquals(1, parsed.size)
        assertEquals("job-1", parsed.single().id)
        assertEquals("a long one", parsed.single().detail)
        assertNull("a job with no detail has none, not an empty string", JobCodec.parse(buildJsonArray { }).firstOrNull())
        assertEquals(1_700_000_000_000, parsed.single().startedAt)
        assertNull(parsed.single().finishedAt)
    }

    /** A subagent is a child session that the Host says is running. */
    @Test
    fun running_subagents_are_the_children_the_host_calls_running() {
        val sessions = listOf(
            SessionSummary("parent", running = true),
            SessionSummary("child-running", running = true, origin = "subagent", parentSessionId = "parent"),
            SessionSummary("child-idle", running = false, origin = "subagent", parentSessionId = "parent"),
            SessionSummary("someone-elses-child", running = true, origin = "subagent", parentSessionId = "other"),
        )
        assertEquals(
            listOf("child-running"),
            Jobs.runningSubagents(sessions, "parent").map { it.sessionId },
        )
    }
}

/**
 * The header's light: three states, and the note beside it.
 */
class LinkLightTest {
    private val point = DshEndpoint("192.168.1.20", 3080)

    @Test
    fun a_socket_that_is_up_is_green() {
        assertEquals(LinkLight.UP, linkLight(point, connected = true, attached = true))
    }

    @Test
    fun a_socket_being_retried_is_amber_not_red() {
        assertEquals(LinkLight.RECONNECTING, linkLight(point, connected = false, attached = true))
    }

    @Test
    fun an_app_attached_to_nothing_is_red() {
        assertEquals(LinkLight.DOWN, linkLight(point, connected = false, attached = false))
        assertEquals(LinkLight.DOWN, linkLight(null, connected = false, attached = false))
    }

    /** The address is a setting, not news: the header says nothing about it. */
    @Test
    fun nothing_beside_the_light_except_what_is_still_being_sent() {
        assertNull(linkNote(0))
        assertEquals("1 message waiting", linkNote(1))
        assertEquals("3 messages waiting", linkNote(3))
    }
}

/**
 * The wiring from the roster stream to the state the button reads.
 *
 * The roster has its own stream since 0.1.7: `job/list`, scoped to a session, and
 * one frame is the whole set that session can see -- its own jobs plus every
 * unowned one. So a frame replaces what the state holds rather than adding to it:
 * a session whose roster arrives empty has no jobs, and one that was holding jobs
 * must not keep them, which is the same rule that keeps a claimed steer from
 * sitting in the dock forever.
 *
 * The frame is the Host's own (`job-list.json`, read from a live 0.1.7 Host).
 */
class JobsWiringTest {
    private val capture = DshWire.json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/job-list.json")).bufferedReader().readText(),
    ).jsonObject

    private val sessionId = (capture["sessionId"] as JsonPrimitive).content
    private val frame = capture["frames"]!!.jsonArray.first()

    private fun emptyRoster() = DshWire.json.parseToJsonElement("""{"type":"rows","jobs":[]}""")

    @Test
    fun a_roster_frame_carries_the_jobs_into_the_state() {
        val state = AppState().withJobRows(sessionId, frame)
        val rows = state.jobs.getValue(sessionId)
        assertEquals(JobCodec.parseFrame(frame), rows)
        assertTrue("the capture holds both kinds", rows.any { it.live } && rows.any { !it.live })
        assertEquals(
            "the live ones are what the button counts",
            rows.filter { it.live }.map { it.id },
            Jobs.live(rows).map { it.id },
        )
    }

    @Test
    fun a_roster_that_reports_no_jobs_takes_the_ones_it_had_away() {
        val before = AppState().withJobRows(sessionId, frame)
        assertTrue(before.jobs.containsKey(sessionId))
        val after = before.withJobRows(sessionId, emptyRoster())
        assertTrue("an empty roster is the removal", after.jobs.isEmpty())
    }

    @Test
    fun a_frame_that_is_not_a_roster_is_not_an_empty_roster() {
        // `job/follow` streams one job's output over the same namespace, and a
        // frame of that stream read as "no jobs" would empty the button.
        val before = AppState().withJobRows(sessionId, frame)
        val output = DshWire.json.parseToJsonElement("""{"type":"output","chunks":[],"next":0}""")
        assertEquals(before, before.withJobRows(sessionId, output))
    }

    @Test
    fun a_roster_is_filed_under_the_session_it_was_opened_for() {
        // The stream is opened for the conversation on screen; a frame that lands
        // after the reader has moved on must not be filed under the new session.
        val state = AppState().withJobRows("other", frame)
        assertNull(state.jobs[sessionId])
        assertTrue(state.jobs.containsKey("other"))
    }
}
