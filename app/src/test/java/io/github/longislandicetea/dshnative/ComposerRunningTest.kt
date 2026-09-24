package io.github.longislandicetea.dshnative

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer's Stop button, and the two ways it used to get stuck.
 *
 * The button is drawn from one boolean -- the open conversation's `running` --
 * and that boolean has exactly one live source: `api-session/status`, which the
 * Host *emits* on a change and never replays. A client that was not listening
 * when the turn ended therefore has no way to hear that it is over, and the
 * button stays on Stop with the agent idle. That is the bug this file exists for;
 * it is reproduced below by dropping the emit, which is what a phone in a pocket
 * does for real.
 *
 * The capture is one real turn from a live Host (a tool call, then the reply),
 * holding both streams the app opens, so the ordinary path is pinned against the
 * wire rather than against a guess about its shape. It was made by
 * `tools/capture-turn.mjs`; see `docs/reconnect.md` for the rule it belongs to.
 */
class ComposerRunningTest {
    private val capture = DshWire.json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/turn-running.json")).bufferedReader().readText(),
    ) as JsonObject

    private val sessionId = (capture["sessionId"] as JsonPrimitive).content
    private val frames = capture["frames"] as JsonArray

    /**
     * Replay the capture in arrival order, exactly as `openEvents`/`openFollow` do.
     *
     * [hearTurnEnd] says whether this client was still listening when the turn
     * finished. It is a parameter because the two ends of a turn are independently
     * missable: a phone awake when the turn starts and asleep when it ends hears
     * `true` and not `false`, which is exactly the state the repair exists for.
     */
    private fun replay(hearTurnEnd: Boolean = true): AppState {
        var state = AppState(conversation = Conversation(sessionId = sessionId))
        for (element in frames) {
            val frame = element as JsonObject
            val stream = frame.string("stream")
            val kind = frame.string("kind")
            if (stream == "events" && kind == "emit") {
                val args = (frame["args"] as JsonArray).toList()
                val delta = SessionDelta.from(frame.string("event") ?: continue, args) ?: continue
                // The emit that says the turn is over never arrived.
                if (delta is SessionDelta.Running && !delta.running && !hearTurnEnd) continue
                state = state.withSessionDelta(delta)
            } else if (stream == "follow" && kind == "frame") {
                val live = state.conversation!!
                val memory = (state.fold ?: TranscriptFold(live)).copy(conversation = live)
                val folded = foldFollowFrame(memory, FollowCodec.decode(frame["value"]!!))
                // The same rebase `withFolded` performs: the fold's own copy of the
                // flag is older than the one the composer is drawn from.
                state = state.copy(conversation = folded.conversation.copy(running = live.running), fold = folded)
            }
        }
        return state
    }

    /** The list a `session/list` read would return for this session, right now. */
    private fun listed(running: Boolean) = listOf(SessionSummary(sessionId = sessionId, running = running))

    /** What `refreshSessions` does with a read: adopt the list for the open session. */
    private fun AppState.withList(read: List<SessionSummary>): AppState = copy(
        conversation = conversation?.copy(
            running = runningFromList(sessionId, read, conversation.running),
        ),
    )

    /**
     * The ordinary path, end to end on real frames: a turn goes up, comes down,
     * and the button follows it.
     */
    @Test
    fun `a captured turn ends with the composer offering send`() {
        val settled = replay()
        assertEquals(false, settled.conversation?.running)
        assertEquals(ComposerAction.Idle, composerAction(settled.conversation?.running == true, ""))
    }

    /** Both ends of the turn are in the capture, which is what makes it evidence. */
    @Test
    fun `the capture carries a status emit for each end of the turn`() {
        val emitted = frames.mapNotNull { element ->
            val frame = element as JsonObject
            if (frame.string("stream") == "events" && frame.string("event") == "api-session/status") {
                (frame["args"] as JsonArray)[1].jsonPrimitive.contentOrNull
            } else {
                null
            }
        }
        // Arrival order, so the second is the one that has to come home.
        assertEquals(listOf("true", "false"), emitted)
    }

    /**
     * The bug, exactly as a phone in a pocket produces it: the asleep client hears
     * the turn start and not the emit that says it is over, so the flag is left
     * claiming a turn that has finished and the button stays on Stop. The next
     * `session/list` read -- which `resync` already performs on every new socket
     * generation -- is what has to correct it.
     */
    @Test
    fun `a turn that ended while the socket was down is repaired by the list`() {
        val blind = replay(hearTurnEnd = false)
        assertEquals("the missed emit is the only thing that would have said so", true, blind.conversation?.running)
        assertEquals(ComposerAction.Stop, composerAction(blind.conversation?.running == true, ""))

        val repaired = blind.withList(listed(running = false))
        assertEquals(false, repaired.conversation?.running)
        assertEquals(ComposerAction.Idle, composerAction(repaired.conversation?.running == true, ""))
    }

    /**
     * The other direction, which the same read must not get wrong: a turn that is
     * still going. The list is a *repair*, not a replacement for the live source,
     * so it has to be able to say `true` as well -- a client that lost the emit
     * saying "the turn started" would otherwise never show Stop at all.
     */
    @Test
    fun `a turn the list says is running turns the button back into stop`() {
        val idle = AppState(conversation = Conversation(sessionId = sessionId, running = false))
        val fromList = runningFromList(sessionId, listed(running = true), fallback = false)
        assertTrue(fromList)
        assertEquals(ComposerAction.Stop, composerAction(fromList, ""))
        assertEquals(ComposerAction.Idle, composerAction(idle.conversation!!.running, ""))
    }

    /**
     * A list that says nothing about the session must not be read as "not
     * running". A session created a moment ago has a row the last read may not
     * include yet, and answering `false` there would take Stop away in the middle
     * of the turn the reader just started.
     */
    @Test
    fun `a list with no row for the session leaves the flag alone`() {
        assertTrue(runningFromList("someone-else", listed(running = false), fallback = true))
        assertEquals(false, runningFromList("someone-else", listed(running = true), fallback = false))
        assertEquals(true, runningFromList(sessionId, emptyList(), fallback = true))
    }

    /**
     * The repair must not re-create the bug it fixes.
     *
     * A `session/list` response describes the Host as of the moment it was served
     * and lands later than that, so a turn that ends while the read is in flight is
     * simply absent from it. Adopting such a response wholesale would put the
     * composer back on Stop -- the same stuck button, in a narrower window. The
     * statuses seen during the read are replayed over it instead.
     */
    @Test
    fun `a status that arrived during the read outranks the response`() {
        // The turn ended while the read was out; the response still says running.
        assertEquals(
            false,
            runningFromList(sessionId, listed(running = true), fallback = true, since = listOf(false)),
        )
        // And the opposite: a turn that started during the read is not undone by a
        // response served before it began.
        assertEquals(
            true,
            runningFromList(sessionId, listed(running = false), fallback = false, since = listOf(true)),
        )
        // The newest status wins, not the first: a turn can start and end inside
        // one slow read.
        assertEquals(
            false,
            runningFromList(sessionId, listed(running = true), fallback = true, since = listOf(true, false)),
        )
        // With nothing seen during the read, the response is what speaks.
        assertEquals(
            false,
            runningFromList(sessionId, listed(running = false), fallback = true, since = emptyList()),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
