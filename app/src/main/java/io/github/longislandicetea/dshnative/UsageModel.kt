package io.github.longislandicetea.dshnative

/**
 * Numbers a reader needs to judge a conversation: what it has cost and how close
 * it is to being compacted.
 *
 * The Host already sends all of this. A session summary carries `tokenUsage`,
 * `contextPressure`, `contextBreakdown` and `sessionStats` in its projections, and
 * the `session/control` stream repeats them as they change -- so none of this is
 * measured here, only formatted. That is why [Metrics.from] takes the projection
 * bag rather than a session: the same numbers arrive from the list, from a follow
 * snapshot and from the control stream, and all three should read the same.
 */
data class Metrics(
    val usage: TokenUsage? = null,
    val pressure: ContextPressure? = null,
    val breakdown: ContextBreakdown? = null,
    val stats: SessionStats? = null,
) {
    /** Nothing to show: no provider has reported usage for this session yet. */
    val isEmpty: Boolean get() = usage == null && pressure == null

    /**
     * Tokens this session's requests have handled.
     *
     * Cache reads are counted: a cached token was still sent and still occupies
     * the conversation, and leaving it out would make a long session look cheaper
     * the more it reused its own context.
     */
    val totalTokens: Long
        get() = usage?.let {
            it.uncachedInputTokens + it.outputTokens + it.cacheReadTokens + it.cacheWriteTokens
        } ?: 0

    /** The output share, which is what a reader pays most attention to. */
    val outputTokens: Long get() = usage?.outputTokens ?: 0

    /**
     * Share of the context window in use, 0..1, or null when the provider has not
     * reported a window.
     *
     * Uses `pressureTokens` and not `projectedTokens`: the projected figure
     * includes the next request's estimate, which makes the meter jump ahead of
     * what has actually been stored.
     */
    fun usedFraction(): Double? {
        val p = pressure ?: return null
        val window = p.contextWindow
        if (window == null || window <= 0) return null
        return (p.pressureTokens.toDouble() / window).coerceIn(0.0, 1.0)
    }

    /** Tokens occupying the window right now. */
    val usedTokens: Long get() = pressure?.pressureTokens ?: 0

    /** The window, when the provider reported one. */
    val contextWindow: Long? get() = pressure?.contextWindow

    companion object {
        /** Build from one projection bag; every field is optional. */
        fun from(values: ProjectionValues?): Metrics = Metrics(
            usage = values?.tokenUsage,
            pressure = values?.contextPressure,
            breakdown = values?.contextBreakdown,
            stats = values?.sessionStats,
        )
    }
}

/**
 * `12400` -> `12.4K`, `1262612` -> `1.3M`.
 *
 * One decimal below ten units and none above, because the difference between
 * 12.4K and 12.9K can matter to a reader watching a window fill while 1.3M
 * against 1.4M does not. Mirrors the web client's `number.thousand` /
 * `number.million` formatting, including the truncation rather than rounding of
 * the fractional digit: a count that reads 1.9M must not have been 1.86M.
 */
fun compactTokens(value: Long): String {
    val million = 1_000_000L
    val thousand = 1_000L
    return when {
        value >= million -> {
            val tenths = value * 10 / million
            "${tenths / 10}.${tenths % 10}M"
        }
        value >= thousand -> {
            val tenths = value * 10 / thousand
            "${tenths / 10}.${tenths % 10}K"
        }
        else -> value.toString()
    }
}

/** `6598` -> `6.6s`, `3153677` -> `52m 34s`. Durations a reader can hold in mind. */
fun compactDuration(millis: Long): String {
    val seconds = millis / 1000
    return when {
        seconds < 60 -> "${millis / 1000}.${(millis % 1000) / 100}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}

/** One labelled row of the context breakdown, in the order the web shows them. */
data class BreakdownRow(val label: String, val tokens: Long)

/**
 * The context window's contents, largest first.
 *
 * Ordered by size rather than by the web client's fixed order, because on a phone
 * the question is "what is eating the window", and the answer should be the first
 * thing read.
 */
fun breakdownRows(breakdown: ContextBreakdown?): List<BreakdownRow> {
    val b = breakdown ?: return emptyList()
    return listOf(
        BreakdownRow("Conversation", b.messageTokens),
        BreakdownRow("Tool definitions", b.toolsTokens),
        BreakdownRow("System prompt", b.systemTokens),
    ).filter { it.tokens > 0 }.sortedByDescending { it.tokens }
}
