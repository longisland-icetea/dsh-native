package io.github.longislandicetea.dshnative

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

/**
 * The context window, as a ring beside the model chip.
 *
 * The number that changes behaviour on a phone: a reader who cannot see the
 * window filling has no warning before the harness compacts their conversation.
 * It stays a ring rather than a percentage because the useful signal is
 * "nearly full", which a glance reads from an arc and not from two digits.
 *
 * Renders nothing until a provider reports both a window and some pressure --
 * the Host's own rule, and the honest one: an empty ring would claim a
 * measurement nobody made.
 */
@Composable
internal fun ContextMeter(metrics: Metrics) {
    val fraction = metrics.usedFraction() ?: return
    var showBreakdown by remember { mutableStateOf(false) }

    val colour = when {
        fraction >= 0.9 -> WARN
        fraction >= 0.7 -> Color(0xFFD8B26B)
        else -> ACCENT
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { showBreakdown = true }
            .padding(horizontal = 3.dp, vertical = 1.dp),
    ) {
        Canvas(Modifier.size(13.dp)) {
            val stroke = 2.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = Color(0xFF3A4150),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = colour,
                startAngle = -90f,
                sweepAngle = (fraction * 360).toFloat(),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = "${(fraction * 100).toInt()}%",
            color = colour,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
    }

    if (showBreakdown) {
        ContextDialog(metrics, onDismiss = { showBreakdown = false })
    }
}

/** What is in the window, and what the session has spent getting there. */
@Composable
private fun ContextDialog(metrics: Metrics, onDismiss: () -> Unit) {
    val rows = breakdownRows(metrics.breakdown)
    val window = metrics.contextWindow
    val used = metrics.usedTokens

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = true),
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", fontSize = 13.sp) } },
        title = { Text("Context", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = compactTokens(used),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = window?.let { "of ${compactTokens(it)}" } ?: "of the window",
                        color = MUTED,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))

                // Stacked bar rather than three numbers: the question is which
                // part dominates, and that is a shape before it is a figure.
                if (rows.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .background(Color(0xFF2A2F38), RoundedCornerShape(4.dp)),
                    ) {
                        val total = rows.sumOf { it.tokens }.coerceAtLeast(1)
                        rows.forEachIndexed { index, row ->
                            val weight = row.tokens.toFloat() / total
                            if (weight > 0f) {
                                Spacer(
                                    Modifier
                                        .weight(weight)
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .background(breakdownColour(index), RoundedCornerShape(4.dp)),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    rows.forEachIndexed { index, row ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(
                                Modifier
                                    .size(8.dp)
                                    .background(breakdownColour(index), RoundedCornerShape(2.dp)),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(row.label, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(
                                text = compactTokens(row.tokens),
                                color = MUTED,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }

                metrics.stats?.let { stats ->
                    Spacer(Modifier.height(12.dp))
                    Text("THIS SESSION", color = MUTED, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    StatLine("Turns", "${stats.turns}  ·  ${stats.steps} steps")
                    StatLine("Sent", compactTokens(metrics.totalTokens))
                    StatLine("Received", compactTokens(metrics.outputTokens))
                    stats.meanTtftMs()?.let { StatLine("First token", "~${compactDuration(it)}") }
                    stats.tokensPerSecond()?.let { speed ->
                        StatLine("Speed", "${(speed * 10).toInt() / 10.0} tok/s")
                    }
                    StatLine("Model time", compactDuration(stats.llmMs))
                    StatLine("Tool time", compactDuration(stats.toolMs))
                }
            }
        },
    )
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = MUTED, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

/** Distinct hues in a fixed order, so a row keeps its colour between openings. */
private fun breakdownColour(index: Int): Color = when (index) {
    0 -> Color(0xFF6E9BF7)
    1 -> Color(0xFF7FB6A4)
    else -> Color(0xFFB79BE0)
}

/**
 * One turn's cost, under the turn's closing line.
 *
 * A single tappable figure by default: a phone transcript that printed four
 * numbers after every turn would be unreadable, and the breakdown is the same
 * dialog the ring opens. Kept in the transcript rather than only in the session
 * totals because "which turn was expensive" is the question a reader actually
 * asks, and per-turn usage is the only place it can be answered from.
 */
@Composable
internal fun TurnUsageRow(usage: TokenUsage, stats: SessionStats?, onOpen: () -> Unit) {
    val total = usage.uncachedInputTokens + usage.cacheReadTokens + usage.cacheWriteTokens + usage.outputTokens
    if (total <= 0) return
    val cached = usage.cacheReadTokens > 0
    Text(
        text = buildString {
            append("${compactTokens(total)} tokens")
            append(" · out ${compactTokens(usage.outputTokens)}")
            if (cached) append(" · cached ${compactTokens(usage.cacheReadTokens)}")
            stats?.tokensPerSecond()?.let { append(" · ${(it * 10).toInt() / 10.0} tok/s") }
        },
        color = MUTED,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .padding(start = 4.dp, top = 1.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpen,
            ),
    )
}

/**
 * One turn's numbers, opened from its usage row.
 *
 * Same rows as the context dialog's session section, but scoped to the turn --
 * which is the difference that makes both worth having: the ring answers "how
 * full is the window", this answers "what did that turn cost".
 */
@Composable
internal fun TurnUsageDialog(usage: TokenUsage, stats: SessionStats?, onDismiss: () -> Unit) {
    val total = usage.uncachedInputTokens + usage.cacheReadTokens + usage.cacheWriteTokens + usage.outputTokens
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", fontSize = 13.sp) } },
        title = { Text("This turn", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(compactTokens(total), fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    Text("tokens", color = MUTED, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                }
                Spacer(Modifier.height(12.dp))
                StatLine("Uncached input", compactTokens(usage.uncachedInputTokens))
                if (usage.cacheReadTokens > 0) StatLine("Cached input", compactTokens(usage.cacheReadTokens))
                if (usage.cacheWriteTokens > 0) StatLine("Cache write", compactTokens(usage.cacheWriteTokens))
                StatLine("Output", compactTokens(usage.outputTokens))
                if (usage.reasoningTokens > 0) StatLine("  of which reasoning", compactTokens(usage.reasoningTokens))
                // The cache-hit share is the number that explains why a long
                // session is cheaper than its totals look.
                val readShare = if (total > 0) usage.cacheReadTokens.toDouble() / total else 0.0
                if (readShare > 0) {
                    Spacer(Modifier.height(8.dp))
                    StatLine("Served from cache", "${(readShare * 100).toInt()}%")
                }
                stats?.tokensPerSecond()?.let {
                    Spacer(Modifier.height(8.dp))
                    StatLine("Session speed", "${(it * 10).toInt() / 10.0} tok/s")
                }
            }
        },
    )
}
