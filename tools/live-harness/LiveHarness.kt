package io.github.longislandicetea.dshnative

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.system.exitProcess

/**
 * Drive the app's own transport and state machine against a live Host, with no
 * phone and no emulator.
 *
 * The point is that this is *not* a re-implementation: it constructs the real
 * `DshClient` and the real `AppStateHolder`, and then reads `AppState` -- the
 * same queues, pending sends and transcript rows the UI is drawn from. Every bug
 * this file exists for was found on a phone and could have been found here:
 * a steer that never appeared, a queue row that never arrived, a discarded
 * message that waited forever.
 *
 * What it cannot check is pixels. Compose layout is the one thing that still
 * needs a device; everything else about a frame's journey is covered.
 *
 * It creates its own throwaway session and archives it on the way out, so it
 * never reads or writes a session that is not its own. A run costs a few
 * thousand tokens of the Host's model quota.
 *
 *   DSH_HOST=192.168.255.5 tools/live-harness.sh
 */
private const val TIMEOUT_MS = 30_000L

/** What the harness observed, for the summary at the end. */
private class Report {
    private val checks = mutableListOf<Triple<String, Boolean, String>>()

    fun check(what: String, ok: Boolean, detail: String = "") {
        checks += Triple(what, ok, detail)
        println("${if (ok) "ok  " else "FAIL"} $what${if (detail.isEmpty()) "" else "  ($detail)"}")
    }

    fun summary(): Int {
        val failed = checks.count { !it.second }
        println()
        println(if (failed == 0) "ALL PASS (${checks.size} checks)" else "$failed FAILED of ${checks.size}")
        checks.filterNot { it.second }.forEach { println("  - ${it.first} ${it.third}") }
        return if (failed == 0) 0 else 1
    }
}

/** The switch file the proxy watches, when the harness was started with one. */
private fun linkSwitch(): String? = System.getenv("DSH_LINK_SWITCH")?.takeIf { it.isNotBlank() }

private suspend fun <T> until(what: String, timeoutMs: Long = TIMEOUT_MS, read: () -> T?): T =
    withTimeoutOrNull(timeoutMs) {
        while (true) {
            read()?.let { return@withTimeoutOrNull it }
            delay(200)
        }
        @Suppress("UNREACHABLE_CODE") null
    } ?: error("timed out after ${timeoutMs}ms waiting for $what")

fun main(args: Array<String>): Unit = runBlocking {
    val host = args.firstOrNull() ?: System.getenv("DSH_HOST") ?: "192.168.255.5"
    val port = (args.getOrNull(1) ?: System.getenv("DSH_PORT") ?: "3080").toInt()
    val endpoint = DshEndpoint(host, port)
    val report = Report()
    println("== live harness against ${endpoint.httpBase}")

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val holder = AppStateHolder(scope, context = null)
    // The setup client is how "another client" is simulated: the app must react
    // to a Host that was changed by someone else, not by itself.
    val outsider = DshClient(endpoint, scope)
    outsider.start()
    // One more that does *not* go through the cuttable link: some checks need the
    // Host to change while this app cannot hear it, and a client behind the same
    // cut cannot cause that.
    val direct = DshClient(
        DshEndpoint(
            System.getenv("DSH_REAL_HOST") ?: "192.168.255.5",
            (System.getenv("DSH_REAL_PORT") ?: "3080").toInt(),
        ),
        scope,
    ).also { it.start() }
    holder.connect(endpoint)

    // `SessionGroup.fromWorkspaces` is the drawer's own rule -- origin, archive
    // and blank -- so a check through it is a check on what a reader would see,
    // not merely on what the state holds.
    fun listed(id: String): Boolean = SessionGroup.fromWorkspaces(
        sessions = holder.state.value.sessions,
        workspaces = holder.state.value.workspaces,
        currentId = holder.state.value.conversation?.sessionId,
        archived = holder.state.value.archived,
        collapsed = emptySet(),
        showArchived = false,
    ).any { group -> group.sessions.any { it.sessionId == id } }

    /**
     * The Host's own queue for one session, read on a fresh subscriber.
     *
     * A fresh `session/control` connection starts with a baseline, which is the
     * authoritative answer -- "the app's dock is empty" is the wrong question,
     * because a message can legitimately still be pending.
     */
    suspend fun hostQueueNow(id: String): List<QueuedItem> =
        withTimeoutOrNull(10_000) {
            val answer = CompletableDeferred<List<QueuedItem>>()
            val job = scope.launch(Dispatchers.IO) {
                outsider.control().collect { frame ->
                    val value = (frame as? MuxFrame.Item)?.value as? JsonObject ?: return@collect
                    if (value["type"]?.jsonPrimitive?.contentOrNull != "baseline") return@collect
                    val table = (value["value"] as? JsonObject)?.get("queues") as? JsonObject
                    answer.complete(QueueCodec.parse(table?.get(id) as? JsonArray))
                }
            }
            val queues = answer.await()
            job.cancel()
            queues
        } ?: emptyList()

    var sessionId: String? = null
    var failure: Throwable? = null
    try {
        until("the socket to come up") { if (holder.state.value.connected) Unit else null }
        report.check("the mux socket connects", holder.state.value.connected)

        holder.createSession(workspaceId = null) { }
        val session = until("a new session") { holder.state.value.conversation?.sessionId }
        sessionId = session
        println("   throwaway session: $session")

        val snapshot = until("the follow snapshot") {
            holder.state.value.conversation?.takeIf { it.items.isNotEmpty() || it.throughSeq > 0 }
        }
        report.check("the transcript loads its snapshot", snapshot.items.isNotEmpty() || snapshot.throughSeq > 0,
            "throughSeq=${snapshot.throughSeq}")

        // ── a message sent while idle becomes durable, and its echo retires ──
        val first = "HARNESS-ONE reply with the single word PINEAPPLE"
        holder.send(first)
        val echo = until("the sent message to appear") {
            holder.state.value.conversation?.items?.filterIsInstance<TranscriptItem.Pending>()?.firstOrNull()
        }
        report.check("a sent message is on screen before the Host logs it", echo.text == first,
            "admitted=${echo.admitted} waiting=${echo.waiting}")
        val durable = until("the durable message", timeoutMs = 120_000) {
            holder.state.value.conversation?.items
                ?.filterIsInstance<TranscriptItem.User>()
                ?.firstOrNull { it.text == first }
        }
        report.check("the Host's own copy replaces it", durable.text == first)
        report.check("and the echo is gone", holder.state.value.conversation?.items
            ?.none { it is TranscriptItem.Pending && it.text == first } == true)

        // ── a steer sent mid-turn shows in the queue and then lands ──────────
        val busy = "HARNESS-TWO run bash: sleep 20 — then reply TWO-DONE"
        holder.send(busy)
        until("the turn to start", timeoutMs = 60_000) {
            holder.state.value.conversation?.takeIf { it.running }
        }
        report.check("the turn is reported running by the Host", holder.state.value.conversation?.running == true)

        val steer = "HARNESS-STEER also mention apples"
        holder.send(steer)
        // By text: the busy prompt above went through the same next-step inbox,
        // so it too is briefly a `steering` row.
        val queued = until("the steer to be admitted to the queue", timeoutMs = 60_000) {
            holder.state.value.queues[session]?.firstOrNull { it.steering && it.label == steer }
        }
        report.check("the steer appears in the Host's queue as steering", queued.placement == "steering",
            "id=${queued.id.take(8)}")
        report.check("the queue row carries the text the reader typed", queued.label == steer)
        // Watch the steer through its whole life in one loop. Its states are
        // transient -- pending, admitted, logged -- and a check that waits for
        // one the message has already left can only time out.
        var sawEcho = false
        var sawAdmitted = false
        val landed = until("the steer to be logged", timeoutMs = 180_000) {
            val rows = holder.state.value.conversation?.items ?: return@until null
            rows.filterIsInstance<TranscriptItem.Pending>().firstOrNull { it.text == steer }?.let { echo ->
                sawEcho = true
                if (echo.admitted) sawAdmitted = true
            }
            rows.filterIsInstance<TranscriptItem.User>().firstOrNull { it.text == steer }
        }
        report.check("the steer is on screen while it is pending", sawEcho)
        report.check("and the Host's inbox admits it", sawAdmitted)
        report.check("the steer lands in the transcript", landed.text == steer)
        report.check("the echo is replaced, not duplicated", holder.state.value.conversation?.items
            ?.none { it is TranscriptItem.Pending && it.text == steer } == true)
        // Whether the Host folds a steer into the running turn or opens the next
        // one with it is the Host's timing, not this client's: what this client
        // owns is that the row lands where it happened rather than stacked at
        // one end -- the shape the "my messages disappeared" bug had.
        val order = holder.state.value.conversation!!.items
        val seqs = order.map { seqOfKey(it.key) }
        val loggedSeqs = seqs.filterNotNull()
        report.check("the transcript is in seq order", loggedSeqs == loggedSeqs.sorted())
        report.check("and rows with no seq yet come last", seqs.drop(loggedSeqs.size).all { it == null })
        report.check("and the queue is empty again", holder.state.value.queues[session].isNullOrEmpty())

        // ── a steer another client removes is given up on, not waited on ─────
        val third = "HARNESS-THREE run bash: sleep 20 — then reply THREE-DONE"
        holder.send(third)
        until("the next turn to start", timeoutMs = 60_000) { holder.state.value.conversation?.takeIf { it.running } }
        val dropped = "HARNESS-DROPPED this one is taken away"
        holder.send(dropped)
        // By text again: the prompt that opened this turn went through the same
        // inbox and is briefly a `steering` row of its own.
        val pending = until("the steer to be admitted", timeoutMs = 60_000) {
            holder.state.value.queues[session]?.firstOrNull { it.steering && it.label == dropped }
        }
        outsider.updateQueue(session, pending.id, buildJsonObject { put("kind", JsonPrimitive("remove")) })
        val givenUp = until("the row to stop claiming it is on its way", timeoutMs = 60_000) {
            holder.state.value.conversation?.items
                ?.filterIsInstance<TranscriptItem.Pending>()
                ?.firstOrNull { it.text == dropped && !it.waiting }
        }
        report.check("a message removed elsewhere says so instead of waiting forever",
            givenUp.failure == TranscriptItem.Pending.DROPPED, givenUp.failure ?: "no reason")
        holder.cancel()
        delay(1_500)

        // ── a message sent on a dead link goes out by itself ──────────────────
        //
        // Through the cuttable proxy the harness controls, so this is a real
        // failure: the socket dies, HTTP dies, and the words the reader typed
        // have to survive both without being retyped.
        linkSwitch()?.let { switch ->
            val weak = "HARNESS-WEAK sent while the link was down"
            java.io.File(switch).delete()
            until("the link to be down", timeoutMs = 15_000) { holder.state.value.connected.not().takeIf { it } }
            holder.send(weak)
            val retrying = until("the row to admit it is being retried", timeoutMs = 30_000) {
                holder.state.value.conversation?.items
                    ?.filterIsInstance<TranscriptItem.Pending>()
                    ?.firstOrNull { it.text == weak && it.note != null }
            }
            report.check("a message sent on a dead link is kept and retried",
                retrying.waiting, retrying.note ?: "no note")
            report.check("and it is in the outbox, not lost",
                holder.state.value.outbox.any { it.text == weak })
            java.io.File(switch).createNewFile()
            until("the link to come back", timeoutMs = 30_000) { holder.state.value.connected.takeIf { it } }
            val delivered = until("the outbox to empty itself", timeoutMs = 60_000) {
                holder.state.value.outbox.none { it.text == weak }.takeIf { it }
            }
            report.check("the outbox delivers it once the link returns, with no retyping", delivered)
            until("the message to be logged", timeoutMs = 60_000) {
                holder.state.value.conversation?.items
                    ?.filterIsInstance<TranscriptItem.User>()
                    ?.firstOrNull { it.text == weak }
            }
            report.check("and the Host has it", true)
        }

        // ── a slow link is survivable, and says so ────────────────────────────
        //
        // Run with `DSH_PROXY_ARGS=--delay-ms 1500` and every frame and every
        // request takes a second and a half. Nothing fails; everything is slow,
        // which is the state a reader cannot tell from a hang unless the client
        // says so.
        if (System.getenv("DSH_PROXY_ARGS")?.contains("delay-ms") == true) {
            val slow = "HARNESS-SLOW typed on a slow link"
            holder.send(slow)
            // Watch the whole life of the row: a note may appear and be replaced,
            // and a check that waits for one the message has already left can only
            // time out.
            var notes = emptyList<String>()
            var failures = emptyList<String>()
            val landed = until("it to arrive on a slow link", timeoutMs = 120_000) {
                val rows = holder.state.value.conversation?.items ?: return@until null
                rows.filterIsInstance<TranscriptItem.Pending>().firstOrNull { it.text == slow }?.let { row ->
                    row.note?.let { if (it !in notes) notes = notes + it }
                    row.failure?.let { if (it !in failures) failures = failures + it }
                }
                rows.filterIsInstance<TranscriptItem.User>().firstOrNull { it.text == slow }
            }
            report.check("a message on a slow link arrives without the reader doing anything", landed.text == slow)
            report.check("and it is never called failed for being slow", failures.isEmpty(), failures.joinToString())
            // `DSH_EXPECT_SLOW_NOTE=1` says the run means to be slow enough that the
            // row must say so (a request in flight for seconds, not a merely slow
            // link). Without it, the check is the other way round: a link that is
            // slow but working must *not* be reported as trouble.
            val expected = System.getenv("DSH_EXPECT_SLOW_NOTE") == "1"
            if (expected) {
                report.check("a request that takes seconds says it is slow, not stuck",
                    notes.any { it.contains("slow") }, notes.joinToString())
            } else {
                report.check("a slow but working link is not reported as trouble",
                    notes.none { it.contains("slow") }, notes.joinToString())
            }
        }

        // ── the Host's own lists stay in step, including across a reconnect ──
        //
        // These are the two mirrors that went stale in the field more than once:
        // the archive set (streamed, and it used to die silently when the stream
        // ended cleanly) and the session list (read, never re-read after a
        // transport hiccup). Both are checked the only way that proves anything:
        // by changing the Host from another client and watching this client find
        // out.
        val other = outsider.createSession(null)
        try {
            // Created blank and never used: the drawer is right to hide it.
            until("the blank session to appear in the state", timeoutMs = 60_000) {
                holder.state.value.sessions.firstOrNull { it.sessionId == other }
            }
            report.check("a blank session is not listed in the drawer", !listed(other))
            outsider.prompt(other, "HARNESS-LISTED reply with the single word LISTED")
            until("using it elsewhere to make it visible here", timeoutMs = 60_000) {
                listed(other).takeIf { it }
            }
            report.check("a session used on another client shows up in the drawer", listed(other))
            // The Host's workspace API is archive-only -- there is no unarchive
            // to call -- so this checks the one direction that exists. A session
            // archived on another client must leave this client's list.
            outsider.archiveSession(other)
            until("the archive set to reach this client") {
                holder.state.value.archived.contains(other).takeIf { it }
            }
            report.check("a session archived elsewhere leaves the list", true)

            // A lost socket, and a turn the Host starts while this client is not
            // listening: `api-session/status` is an emit, not a durable event, so
            // it is never replayed. Only re-reading the list finds it -- which is
            // the whole point of resyncing on a new socket generation.
            //
            // The reconnect is fast enough that the gap cannot be observed
            // reliably, so the proof of the re-read is the app's own log line,
            // which only a new generation produces.
            fun resyncs(): Int = holder.state.value.log.count { it == "resync" }
            val before = resyncs()
            report.check("the harness can drop the socket", holder.dropTransport())
            until("a new socket generation to re-read the Host", timeoutMs = 60_000) {
                (holder.state.value.connected && resyncs() > before).takeIf { it }
            }
            report.check("a reconnect re-reads every mirror", resyncs() > before)
            // The dock must not keep a row the Host no longer has. A baseline is
            // a snapshot, so this is the check that it is applied as one: the
            // steer below is claimed while this client's socket is down, and the
            // dock has to be empty afterwards either way.
            // Whatever the Host ends up holding, this client's dock has to say
            // the same thing: merging a stale row into the baseline left one in
            // the dock for good, which is what the phone showed.
            delay(1_500)
            val appDock = holder.state.value.queues[session].orEmpty().map { it.label }
            val hostDock = hostQueueNow(session).map { it.label }
            report.check(
                "the dock agrees with the Host's own queue after a reconnect",
                appDock == hostDock,
                "dock=$appDock host=$hostDock",
            )
            // A turn this app cannot hear about. The link is cut for real -- the
            // proxy refuses everything, so it cannot reconnect while it is down --
            // and the prompt comes from a client that is not behind the cut, so
            // what is being tested is the re-read and not the race between "the
            // app reconnects" and "the turn starts".
            val switch = linkSwitch()
            if (switch == null) {
                report.check("a link that can be cut is available to test with", false, "DSH_LINK_SWITCH unset")
            } else {
                java.io.File(switch).delete()
                until("the app's link to be cut", timeoutMs = 15_000) {
                    holder.state.value.connected.not().takeIf { it }
                }
                // A turn that is still running when this client re-reads the list:
                // a one-word reply can finish inside the reconnect, which would
                // make the check depend on the race rather than on the re-read.
                direct.prompt(other, "HARNESS-OFFLINE run bash: sleep 20 — then reply FOUND")
                java.io.File(switch).createNewFile()
                until("the link to come back", timeoutMs = 30_000) { holder.state.value.connected.takeIf { it } }
                // `api-session/status` is an emit the app was not there to receive,
                // so only re-reading the list can tell it.
                val sawRunning = until("the re-read list to show the turn running", timeoutMs = 60_000) {
                    holder.state.value.sessions.firstOrNull { it.sessionId == other }?.takeIf { it.running }
                }
                report.check("a turn it was not listening for is visible afterwards", sawRunning.running)
            }
            report.check("and the transcript was re-established too",
                holder.state.value.conversation?.items?.isNotEmpty() == true)
            // Archived earlier in this run and never unarchived (the Host has no
            // unarchive), so being in step now means the re-read kept it.
            report.check("with the archive set still in step",
                holder.state.value.archived.contains(other))
        } finally {
            runCatching { direct.cancel(other) }
            runCatching { outsider.cancel(other) }
            runCatching { outsider.archiveSession(other) }
        }
    } catch (error: Throwable) {
        // A timed-out wait is a failed check, not a reason to lose the report.
        failure = error
    } finally {
        // Leave the Host as it was found: nothing running, nothing listed.
        runCatching { sessionId?.let { outsider.cancel(it) } }
        delay(1_000)
        runCatching { sessionId?.let { outsider.archiveSession(it) } }
        holder.disconnect(quiet = true)
        outsider.stop()
        direct.stop()
        scope.cancel()
    }
    failure?.let { report.check("the run finished without timing out", false, it.message ?: it.toString()) }
    exitProcess(report.summary())
}
