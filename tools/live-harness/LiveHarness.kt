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

/**
 * A real 8x8 PNG, spelled out because the harness writes a figure of its own.
 *
 * The bytes matter twice over: the byte read has to return this file *exactly*,
 * and its IHDR and IDAT chunks contain CRLFs -- the sequence a multipart parser
 * mistakes for the start of the next part if it is scanning for the boundary by
 * hand rather than parsing the body.
 */
private val FIGURE_PNG: ByteArray = java.util.Base64.getDecoder().decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAIAQMAAAD+wSzIAAAABlBMVEX///+/v7+jQ3Y5AAAADklEQVQI12P4AIX8EAgALgAD/aNpbtEAAAAASUVORK5CYII=",
)

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

    // The deliverable read path, end to end: this is the same `DshClient` call
    // the preview sheet makes, so it covers the wire, the page decode and the
    // document rule together. It reads only files this harness made itself.
    suspend fun checkDeliverableReads(sessionId: String) {
        val scratch = java.nio.file.Files.createTempDirectory("dsh-deliverable")
        val document = scratch.resolve("audit.md")
        val source = scratch.resolve("client.kt")
        // Long enough to overrun the Host's page cap, which is the case the
        // reader cannot otherwise tell from a complete document.
        val long = scratch.resolve("long.md")
        java.nio.file.Files.write(
            document,
            "---\ntitle: Weekly audit\n---\n\n# Findings\n\n| a | b |\n|---|---|\n| 1 | 2 |\n".toByteArray(),
        )
        java.nio.file.Files.write(source, "package example\n\nfun main() {}\n".toByteArray())
        java.nio.file.Files.write(
            long,
            StringBuilder().also { out -> repeat(6_000) { out.append("line ").append(it).append('\n') } }.toString().toByteArray(),
        )

        val markdown = runCatching { direct.readWorkspaceFile(sessionId, document.toString()) }
        val page = markdown.getOrNull()
        run {
            if (page == null) {
                report.check(
                    "a delivered markdown file reads as one page",
                    false,
                    markdown.exceptionOrNull()?.message.orEmpty(),
                )
                return@run
            }
            val blocks = SimpleMarkdown.parse(deliverableDocumentText(page.text))
            report.check(
                "a delivered markdown file reads as one page",
                DeliverableView.Document == deliverableViewFor(document.toString()) && page.eof && !page.truncated,
                "eof=${page.eof} truncated=${page.truncated}",
            )
            // The front matter is gone *and* what is left is laid out: a
            // heading and a table, not a paragraph of `#` and pipes.
            report.check(
                "a markdown deliverable is laid out, not shown as source",
                blocks.any { it is MarkdownBlock.Prose && it.kind == ProseKind.Heading1 } &&
                    blocks.any { it is MarkdownBlock.Table },
                blocks.joinToString(",") { it::class.simpleName.orEmpty() },
            )
        }
        val code = runCatching { direct.readWorkspaceFile(sessionId, source.toString()) }
        report.check(
            "a source deliverable stays source",
            code.getOrNull()?.text?.startsWith("package example") == true &&
                DeliverableView.Source == deliverableViewFor(source.toString()),
        )
        val cut = runCatching { direct.readWorkspaceFile(sessionId, long.toString()) }
        // The whole point: the Host answers a request for a whole file with a
        // page, and this client can now say so instead of drawing it as the file.
        report.check(
            "a page cut at the host's cap is reported as truncated",
            cut.getOrNull()?.truncated == true && cut.getOrNull()?.eof == false,
            "lines=${cut.getOrNull()?.lines} truncated=${cut.getOrNull()?.truncated}",
        )

        // A figure is the one deliverable that takes *two* calls, and the sheet
        // makes both: the text read refuses it as not-text, and the byte read is
        // what actually draws it. Both halves are checked here because the
        // interesting failures are silent -- the refusal is what routes the sheet
        // to the byte endpoint at all, and a window of the wrong file decodes to
        // a broken image rather than to an error.
        val figure = scratch.resolve("figure.png")
        val figureBytes = FIGURE_PNG
        java.nio.file.Files.write(figure, figureBytes)
        val asText = runCatching { direct.readWorkspaceFile(sessionId, figure.toString()) }
        report.check(
            "a figure is refused by the text read, which is what sends the sheet to the bytes",
            asText.exceptionOrNull()?.message?.contains("not-text") == true,
            asText.exceptionOrNull()?.message ?: "it answered text instead: ${asText.getOrNull()?.text?.take(20)}",
        )
        val asBytes = runCatching { direct.readWorkspaceBytes(sessionId, figure.toString()) }
        report.check(
            "a figure's bytes come back whole, byte for byte",
            asBytes.getOrNull()?.contentEquals(figureBytes) == true,
            "${asBytes.getOrNull()?.size ?: 0} of ${figureBytes.size} bytes; " +
                asBytes.exceptionOrNull()?.message.orEmpty(),
        )

        java.nio.file.Files.walk(scratch).sorted(Comparator.reverseOrder()).forEach { java.nio.file.Files.deleteIfExists(it) }
    }

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
     * The Host's own pending input for one session, read on a fresh subscriber.
     *
     * A fresh `session/control` connection starts with a baseline, which is the
     * authoritative answer -- "the app's dock is empty" is the wrong question,
     * because a message can legitimately still be pending. Since 0.1.7 the pending
     * input is the session's `inbox` projection rather than a `queues` map beside
     * it, so the same decode the app uses is what this reads.
     */
    suspend fun hostQueueNow(id: String): List<QueuedItem> =
        withTimeoutOrNull(10_000) {
            val answer = CompletableDeferred<List<QueuedItem>>()
            val job = scope.launch(Dispatchers.IO) {
                outsider.control().collect { frame ->
                    val value = (frame as? MuxFrame.Item)?.value as? JsonObject ?: return@collect
                    if (value["type"]?.jsonPrimitive?.contentOrNull != "baseline") return@collect
                    val sessions = (value["value"] as? JsonObject)?.get("projections") as? JsonObject
                    val bag = (sessions?.get(id) as? JsonObject)?.get("values") as? JsonObject
                    answer.complete(inboxProjection(bag?.get("inbox")).queueRows())
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

        // Runs before the turn, so a failure here does not cost the model quota
        // the rest of the run spends.
        checkDeliverableReads(session)

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

        // ── a turn that ends while this client is not listening ───────────────
        //
        // The composer's Stop/Send button is drawn from the open conversation's
        // `running` flag, and the only thing that ever pushes that flag down is
        // `api-session/status` -- an emit the Host sends once and never replays.
        // A turn that finishes while the socket is down therefore leaves the
        // button on Stop with the agent idle, which is what a phone in a pocket
        // produces for real. The session list is the repair: `session/list`
        // carries `running`, it is read rather than streamed, and `resync`
        // re-reads it on every new socket generation.
        //
        // The turn is started here, the link is cut for real, and the Host is
        // left to finish it alone. What this asserts is the flag the *composer*
        // reads, not the drawer's: the two are separate copies of one fact, and
        // the drawer's was already right while the composer's was stuck.
        val switchForRunning = linkSwitch()
        if (switchForRunning == null) {
            report.check("a link that can be cut is available to test with", false, "DSH_LINK_SWITCH unset")
        } else {
            holder.send("HARNESS-STUCK run bash: sleep 15 — then reply STUCK-DONE")
            until("the turn to be running before the link goes", timeoutMs = 60_000) {
                holder.state.value.conversation?.takeIf { it.running }
            }
            java.io.File(switchForRunning).delete()
            until("the app's link to be cut", timeoutMs = 15_000) {
                holder.state.value.connected.not().takeIf { it }
            }
            // The Host's own view, read on a client that is not behind the cut: the
            // app's copy cannot move while the link is down, so waiting on it
            // would wait forever.
            var hostSaysRunning = true
            val hostSettledAt = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < hostSettledAt) {
                hostSaysRunning = runCatching {
                    direct.listSessions().firstOrNull { it.sessionId == session }?.running ?: false
                }.getOrDefault(true)
                if (!hostSaysRunning) break
                delay(1_000)
            }
            report.check("the turn finished while the app could not hear about it",
                !hostSaysRunning && holder.state.value.conversation?.running == true,
                "host=$hostSaysRunning app=${holder.state.value.conversation?.running}")
            // The flag the composer would draw from, before the link is back: this
            // is the bug, stated as state rather than as a screenshot.
            report.check("and the composer was left offering Stop",
                composerAction(holder.state.value.conversation?.running == true, "") == ComposerAction.Stop)

            java.io.File(switchForRunning).createNewFile()
            until("the link to come back", timeoutMs = 30_000) { holder.state.value.connected.takeIf { it } }
            // The re-read is asynchronous; wait for the flag itself, which is what
            // the button is drawn from.
            val repaired = until("the composer's flag to come back down", timeoutMs = 60_000) {
                holder.state.value.conversation?.takeIf { !it.running }
            }
            report.check("a turn that ended off-screen gives the Send button back", !repaired.running)
            report.check("and the composer agrees", composerAction(repaired.running, "") != ComposerAction.Stop)
        }

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

        // ── a slash command runs, and says what came of it ────────────────────
        //
        // `/compact` did nothing on the phone: the gateway requires
        // `submittedAttachments` and it was not sent, so every command was
        // refused. `/goal` is used here because it is harmless and always
        // answers with its usage line.
        val before = holder.state.value.conversation?.items?.size ?: 0
        holder.runCommand("/goal")
        val answered = until("the command's outcome to be logged", timeoutMs = 60_000) {
            holder.state.value.conversation?.items
                ?.filterIsInstance<TranscriptItem.Note>()
                ?.firstOrNull { it.text.contains("Usage: /goal") }
        }
        report.check("a slash command runs and reports what came of it", answered.text.isNotBlank(), answered.text)
        val ran = holder.state.value.conversation?.items
            ?.filterIsInstance<TranscriptItem.Note>()
            ?.any { it.text == "/goal" } == true
        report.check("and the command itself is on screen", ran)
        report.check("and no message was invented for it",
            holder.state.value.outbox.none { it.text == "/goal" } &&
                (holder.state.value.conversation?.items?.size ?: 0) > before)

        // A line the Host does not know is a *message*, not a command: the web
        // client settles that the same way, and it is what keeps a stale command
        // list from swallowing prose.
        //
        // Watched through its whole life, for the reason spelled out at the steer
        // above: the echo is retired by the durable message the instant the Host
        // logs it, and on a Host that answers in milliseconds that can happen
        // inside a single poll -- so a check that waits only for the pending row
        // times out on a run where nothing is wrong. It did, once the reconnect
        // earlier in this run made the message arrive in a snapshot rather than as
        // the live event the old version of this check was written against.
        //
        // The Host's inbox is evidence too, and it is the evidence that does not
        // depend on the model: this line is sent while the turn the check above
        // started is still running, so the message waits in the queue until that
        // turn reaches a step boundary. A prompt in the inbox is a message this
        // client sent -- a command would never be there -- and waiting for the
        // durable row instead made the check a measurement of the model's latency.
        var sawProseEcho = false
        var sawProseInbox = false
        holder.runCommand("/definitely-not-a-command HARNESS-PROSE")
        val prose = until("an unrecognised line to be sent as a message", timeoutMs = 60_000) {
            val rows = holder.state.value.conversation?.items ?: return@until null
            rows.filterIsInstance<TranscriptItem.Pending>()
                .firstOrNull { it.text.contains("HARNESS-PROSE") }
                ?.let { sawProseEcho = true }
            holder.state.value.queues[session]
                ?.firstOrNull { it.label.contains("HARNESS-PROSE") }
                ?.let { sawProseInbox = true; return@until it.label }
            rows.filterIsInstance<TranscriptItem.User>().firstOrNull { it.text.contains("HARNESS-PROSE") }?.text
        }
        // The row it became is the reader's own message, which is what "sent as a
        // message" means -- and it is **not** a command outcome: a line the Host
        // ran would have produced the note row `/goal` produced above, and a line
        // it refused would have produced "command not sent".
        report.check("a line that is not a command is sent as a message", prose.contains("HARNESS-PROSE"),
            when {
                sawProseEcho -> "seen pending first"
                sawProseInbox -> "waiting in the Host's inbox"
                else -> "arrived already durable"
            })
        report.check("and it is not reported as a command that ran",
            holder.state.value.conversation?.items
                ?.filterIsInstance<TranscriptItem.Note>()
                ?.none { it.text.contains("definitely-not-a-command") } == true)

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
            // The archive set is what this client draws, so archiving elsewhere is
            // the direction that can be checked end to end. An archived session
            // also may not run a turn on 0.1.7 -- the controller's archived-session
            // gate ends a proposed step as `blocked` -- so it is restored before the
            // checks below prompt it, and restoring it is a check of its own: the
            // app adopts the Host's set wholesale, so an unarchive has to reach it
            // the same way an archive does.
            //
            // The Host refuses to archive a session that still has a turn running
            // (`workspace/session-active`), and the reply above *is* a turn: wait
            // for it to settle rather than racing it.
            withTimeoutOrNull(60_000) {
                while (true) {
                    val row = direct.listSessions().firstOrNull { it.sessionId == other }
                    if (row != null && !row.running) break
                    delay(500)
                }
            }
            outsider.archiveSession(other)
            until("the archive set to reach this client") {
                holder.state.value.archived.contains(other).takeIf { it }
            }
            report.check("a session archived elsewhere leaves the list", !listed(other))
            outsider.unarchiveSession(other)
            until("the unarchive to reach this client") {
                holder.state.value.archived.contains(other).not().takeIf { it }
            }
            report.check("and one unarchived elsewhere comes back", listed(other))

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
                // And it has to have *started* before the link comes back. The app
                // re-reads the list once, on the reconnect, so a prompt the Host had
                // not picked up yet would leave the app's list saying "not running"
                // and this check waiting for a second re-read that never comes --
                // which measures the Host's latency rather than the re-read. What
                // the check needs is a turn that was running while this client could
                // not hear about it.
                withTimeoutOrNull(60_000) {
                    while (true) {
                        val row = direct.listSessions().firstOrNull { it.sessionId == other }
                        if (row?.running == true) break
                        delay(250)
                    }
                }
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
            // Unarchived earlier in this run, so being in step now means the
            // re-read kept the *removal* -- an archive set adopted wholesale has to
            // follow the Host in both directions.
            report.check("with the archive set still in step",
                !holder.state.value.archived.contains(other))
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
        // Cancelling is not instant and an active session cannot be archived, so
        // the archive is retried until it takes -- a session left behind is litter
        // on someone's Host, and a teardown that gives up quietly leaves it there.
        runCatching { sessionId?.let { outsider.cancel(it) } }
        sessionId?.let { id ->
            withTimeoutOrNull(30_000) {
                while (!runCatching { outsider.archiveSession(id) }.isSuccess) delay(500)
            }
        }
        holder.disconnect(quiet = true)
        outsider.stop()
        direct.stop()
        scope.cancel()
    }
    failure?.let { report.check("the run finished without timing out", false, it.message ?: it.toString()) }
    exitProcess(report.summary())
}
