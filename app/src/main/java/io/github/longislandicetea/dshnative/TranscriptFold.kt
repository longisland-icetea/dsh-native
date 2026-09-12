package io.github.longislandicetea.dshnative

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The transcript's own state machine, with no client and no Android in it.
 *
 * It used to live inside `AppStateHolder`, where the only way to find out what a
 * run of frames renders as was to point the app at a live Host and look at the
 * screen. That is exactly the wrong place for the part that keeps going wrong:
 * a steered message that never appeared, a usage row that never appeared, and a
 * reordered transcript were all *this* code, and all three were invisible until
 * someone held a phone. Folding is a pure function of the frames, so it belongs
 * where a captured run can be replayed against it.
 */
data class TranscriptFold(
    val conversation: Conversation,
    /** Usage accumulated per open turn; becomes a row when the turn closes. */
    val pendingTurnUsage: Map<Int, TokenUsage> = emptyMap(),
    /** Assistant messages already counted, so a replayed snapshot cannot double-count. */
    val countedUsageSeqs: Set<Long> = emptySet(),
    /**
     * Prompt identities the Host has logged, so a submission echo can be retired
     * by the message it became.
     */
    val deliveredRpcIds: Set<String> = emptySet(),
    /** What the agent has not read yet, from the durable inbox splices. */
    val inbox: Inbox = Inbox(),
)

/** Fold one `session/follow` frame into the transcript. */
internal fun foldFollowFrame(state: TranscriptFold, frame: FollowFrame): TranscriptFold {
    val conversation = state.conversation
    // Decoded once and handed to both: a reconnect replays the same assistant
    // messages, and accumulating them twice would inflate every turn's cost.
    val (usage, counted) = accumulateTurnUsage(frame, state.pendingTurnUsage, state.countedUsageSeqs)
    // The snapshot's projection bag is the inbox's authoritative starting point;
    // the splices that follow are deltas against it.
    val seeded = (frame as? FollowFrame.Snapshot)?.let { state.inbox.seed(it.inbox) } ?: state.inbox
    val stepped = seeded.apply(spliceOf(frame))
    val delivered = state.deliveredRpcIds + deliveredRpcIds(frame)
    val merged = merge(conversation, frame, usage)
    // A snapshot replaces the inbox wholesale, so it is also evidence: a row the
    // Host's inbox no longer lists, with no durable message to show for itself,
    // has left it for good. Without this the discard was invisible whenever the
    // splice that reported it was missed -- which is exactly when a snapshot
    // arrives instead, since that is what a reconnect gets.
    val settled = settle(merged.items, stepped.inbox, stepped.discarded || frame is FollowFrame.Snapshot, delivered)
    return TranscriptFold(
        conversation = merged.copy(items = settled),
        pendingTurnUsage = usage,
        countedUsageSeqs = counted,
        deliveredRpcIds = delivered,
        inbox = stepped.inbox,
    )
}

/** The `agent/inbox/spliced` payload of one frame, when that is what it is. */
private fun spliceOf(frame: FollowFrame): SessionEvent? =
    (frame as? FollowFrame.Event)?.event?.takeIf { it.type == "agent/inbox/spliced" }

/**
 * Retire, or give up on, the messages this client sent.
 *
 * Three end states, and the reason each is decided here rather than by a timer:
 * a message the Host logged leaves the transcript because the message itself is
 * now there; one still in the inbox is on its way; one the Host *discarded* --
 * `outcome: "canceled"`, which a claim into a turn does not carry -- is never
 * coming, and saying so is the difference between a queue that lies and a
 * reader who knows to send it again.
 */
internal fun settle(
    items: List<TranscriptItem>,
    inbox: Inbox,
    discarded: Boolean,
    delivered: Set<String>,
): List<TranscriptItem> = items.mapNotNull { item ->
    if (item !is TranscriptItem.Pending) return@mapNotNull item
    when {
        item.rpcId in delivered -> null
        inbox.holds(item.rpcId) -> item.copy(admitted = true, failure = null)
        discarded && item.admitted -> item.copy(failure = TranscriptItem.Pending.DROPPED)
        else -> item
    }
}

/** Prompt identities the durable material in one frame carries. */
private fun deliveredRpcIds(frame: FollowFrame): Set<String> {
    val events = when (frame) {
        is FollowFrame.Event -> listOf(frame.event)
        is FollowFrame.Snapshot -> frame.records
        else -> emptyList()
    }
    return events.mapNotNullTo(mutableSetOf()) { event ->
        if (event.type != "user/message") return@mapNotNullTo null
        val source = (event.data as? JsonObject)?.get("source") as? JsonObject ?: return@mapNotNullTo null
        if ((source["kind"] as? JsonPrimitive)?.contentOrNull != "user") return@mapNotNullTo null
        (source["rpcId"] as? JsonPrimitive)?.contentOrNull
    }
}

/**
 * The agent's pending input, as the Host's own durable events describe it.
 *
 * `agent/inbox/spliced` is a normalized splice against the inbox projection --
 * the same event the Host folds to reconstruct what an agent has not read -- so
 * mirroring it here is what makes "is my message still coming?" a fact rather
 * than a guess from whichever control frame happened to arrive.
 */
data class Inbox(
    val nextTurn: List<InboxMessage> = emptyList(),
    val nextStep: List<InboxMessage> = emptyList(),
) {
    fun holds(rpcId: String): Boolean =
        nextTurn.any { it.rpcId == rpcId } || nextStep.any { it.rpcId == rpcId }

    /** Adopt the snapshot's inbox projection; a snapshot is a full replacement. */
    fun seed(projection: InboxProjection?): Inbox = projection?.let {
        Inbox(
            nextTurn = it.nextTurn.map { message -> InboxMessage(message.id, message.rpcId) },
            nextStep = it.nextStep.map { message -> InboxMessage(message.id, message.rpcId) },
        )
    } ?: this

    /** Apply one durable splice, reporting whether it *discarded* pending input. */
    fun apply(event: SessionEvent?): InboxUpdate {
        if (event == null) return InboxUpdate(this, discarded = false)
        val data = event.data as? JsonObject ?: return InboxUpdate(this, discarded = false)
        val target = (data["target"] as? JsonPrimitive)?.contentOrNull
        val list = when (target) {
            "next-turn" -> nextTurn
            "next-step" -> nextStep
            else -> return InboxUpdate(this, discarded = false)
        }
        val start = (data["start"] as? JsonPrimitive)?.intOrNull ?: return InboxUpdate(this, discarded = false)
        val removed = (data["removedCount"] as? JsonPrimitive)?.intOrNull ?: 0
        val inserted = (data["inserted"] as? JsonArray).orEmpty().mapNotNull { element ->
            val message = element as? JsonObject ?: return@mapNotNull null
            val id = (message["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val source = message["source"] as? JsonObject
            InboxMessage(id, (source?.get("rpcId") as? JsonPrimitive)?.contentOrNull)
        }
        // A splice that *discarded* pending input says so on the splice itself,
        // and that fact does not depend on this mirror being able to apply it:
        // a frame missed while the stream was down leaves the mirror the wrong
        // shape, and the discard would then be swallowed exactly when it matters
        // -- the device showed the row waiting forever for this reason.
        val discarded = removed > 0 && (data["outcome"] as? JsonPrimitive)?.contentOrNull == "canceled"
        // The Host's own bounds check, so a splice this build misreads cannot
        // corrupt the mirror: an out-of-range splice is ignored, not clamped.
        if (start < 0 || start > list.size || start + removed > list.size) {
            return InboxUpdate(this, discarded = discarded)
        }
        val next = list.toMutableList().apply { subList(start, start + removed).clear(); addAll(start, inserted) }
        return InboxUpdate(
            if (target == "next-turn") copy(nextTurn = next) else copy(nextStep = next),
            discarded = discarded,
        )
    }
}

data class InboxUpdate(val inbox: Inbox, val discarded: Boolean)

private fun merge(
    conversation: Conversation,
    frame: FollowFrame,
    pendingTurnUsage: Map<Int, TokenUsage> = emptyMap(),
): Conversation = when (frame) {
    is FollowFrame.Snapshot -> {
        // The snapshot is the newest window, not a delta: rebuild by seq so a
        // reconnect cannot duplicate or reorder what is already on screen.
        val merged = pairToolResults(
            (conversation.items + usageAwareItems(frame.records, conversation.workspaceRoot))
                .associateBy { it.key }
                .values
                .sortedBy(::sequenceOf),
        )
        conversation.copy(
            title = conversation.title.ifEmpty { conversation.sessionId.takeLast(8) },
            items = merged,
            lastSeq = maxOf(conversation.lastSeq, frame.records.maxOfOrNull { it.seq } ?: 0L),
            throughSeq = frame.cursor,
            hasMore = frame.hasMore,
            error = null,
            liveText = "",
        )
    }

    is FollowFrame.Event -> {
        val event = frame.event
        val turn = (event.data as? JsonObject)?.get("turn")?.jsonPrimitive?.intOrNull
        // The turn's usage accumulated as its assistant messages arrived;
        // `turn/end` is where it becomes a row of its own. The reducer knows
        // both facts, which a renderer reading keys at draw time did not.
        val closed = if (event.type == "turn/end" && turn != null) {
            pendingTurnUsage[turn]?.let { used ->
                TranscriptItem.Usage(key = "usage:$turn:${event.seq}", usage = used, turn = turn)
            }
        } else {
            null
        }
        conversation.copy(
            // The deltas this attempt streamed are the message that just
            // committed, so the live bubble has to let go of them: it is the
            // same text, and keeping it would show every reply twice -- once
            // where it belongs and once stuck at the bottom.
            liveText = if (event.type == "assistant/message") "" else conversation.liveText,
            // A message this client sent has no seq, so it would sink above
            // whatever the turn logs next. It belongs where the reader is
            // looking -- under their thumb, at the bottom -- until the message
            // it becomes arrives and takes its own place in the order.
            items = last(
                pairToolResults(
                    (conversation.items + listOfNotNull(toItem(event, conversation.workspaceRoot)) + listOfNotNull(closed))
                        .distinctBy { it.key },
                ),
            ),
            lastSeq = maxOf(conversation.lastSeq, event.seq),
            // Deliberately not derived from the event type. The Host reports
            // whether a turn is running on `api-session/status`, and an inferred
            // flag disagrees with it whenever a turn ends with a row that still
            // says "running" -- which is what left the composer showing Stop after
            // the turn was over. `api-session/status` is authoritative; this keeps
            // whatever it last said.
            running = conversation.running,
        )
    }

    is FollowFrame.AssistantChunk -> {
        val delta = frame.text ?: return conversation
        conversation.copy(liveText = conversation.liveText + delta)
    }

    FollowFrame.Unknown -> conversation
}

/**
 * Rows in seq order, with the rows that have no seq -- this client's own
 * unsent-but-sent messages -- at the end, where they were typed.
 */
private fun last(items: List<TranscriptItem>): List<TranscriptItem> {
    val pending = items.filterIsInstance<TranscriptItem.Pending>()
    return if (pending.isEmpty()) items else items.filterNot { it is TranscriptItem.Pending } + pending
}

/** A row that carries no seq sorts last rather than throwing. */
internal fun sequenceOf(item: TranscriptItem): Long = seqOfKey(item.key) ?: Long.MAX_VALUE

/**
 * Fold every `tool/result` row into the `tool/call` row it answers.
 *
 * Pairing is done over the whole window rather than as events arrive,
 * because paging and reconnects can deliver the halves in either order and
 * the reducer itself must stay free of side effects. A result with no
 * matching call in this window keeps its own row instead of disappearing.
 */
internal fun pairToolResults(items: List<TranscriptItem>): List<TranscriptItem> {
    val results = items.filterIsInstance<TranscriptItem.ToolResultRow>()
    if (results.isEmpty()) return items
    val byCallId = results.mapNotNull { row -> row.toolCallId?.let { it to row } }.toMap()
    if (byCallId.isEmpty()) return items
    // Calls consume their result in order, so a repeated callId across turns
    // still pairs with the nearest unconsumed call.
    val consumed = mutableSetOf<String>()
    return items.mapNotNull { item ->
        when {
            item is TranscriptItem.ToolCall -> {
                val row = byCallId[item.callIdOrNull()]?.takeIf { it.key !in consumed }
                if (row == null) {
                    item
                } else {
                    consumed += row.key
                    item.copy(
                        result = row.text,
                        status = if (row.failed) TranscriptItem.ToolCall.Status.FAILED
                        else TranscriptItem.ToolCall.Status.DONE,
                    )
                }
            }
            // Every result row is dropped: a paired one has been folded into
            // its call, and an unpaired one (paging landed mid-pair) would
            // otherwise surface as a bare result line.
            item is TranscriptItem.ToolResultRow -> null
            else -> item
        }
    }
}

/**
 * Accumulate one frame's assistant usage into the open turn.
 *
 * One event contributes once, however many times a reconnect replays it, which
 * is what `counted` remembers.
 */
internal fun accumulateTurnUsage(
    frame: FollowFrame,
    current: Map<Int, TokenUsage>,
    counted: Set<Long>,
): Pair<Map<Int, TokenUsage>, Set<Long>> {
    val unchanged = current to counted
    val event = (frame as? FollowFrame.Event)?.event ?: return unchanged
    if (event.type != "assistant/message") return unchanged
    if (event.seq in counted) return unchanged
    val data = event.data as? JsonObject ?: return unchanged
    val turn = (data["turn"] as? JsonPrimitive)?.intOrNull ?: return unchanged
    val usage = data["usage"] ?: return unchanged
    val fresh = runCatching {
        DshWire.json.decodeFromJsonElement(TokenUsage.serializer(), usage)
    }.getOrNull() ?: return unchanged
    val previous = current[turn] ?: TokenUsage()
    val sum = TokenUsage(
        uncachedInputTokens = previous.uncachedInputTokens + fresh.uncachedInputTokens,
        outputTokens = previous.outputTokens + fresh.outputTokens,
        cacheReadTokens = previous.cacheReadTokens + fresh.cacheReadTokens,
        cacheWriteTokens = previous.cacheWriteTokens + fresh.cacheWriteTokens,
        reasoningTokens = previous.reasoningTokens + fresh.reasoningTokens,
    )
    return (current + (turn to sum)) to (counted + event.seq)
}
