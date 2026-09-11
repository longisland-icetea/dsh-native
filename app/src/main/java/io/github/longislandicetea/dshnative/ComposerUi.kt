package io.github.longislandicetea.dshnative

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Messages waiting their turn, above the composer.
 *
 * The queue is the Host's, streamed as a complete snapshot, so this panel never
 * guesses: a row leaves when the Host says it left, which is also how a row that
 * the agent just claimed disappears at the right moment.
 *
 * Three actions, matching the Host's `QueueAction`: steer it into the running
 * turn, edit it, or drop it. Being able to see the queue matters more than any of
 * them -- a message that missed the steer window used to vanish into a turn the
 * reader thought they had already corrected.
 */
@Composable
internal fun QueueDock(
    items: List<QueuedItem>,
    running: Boolean,
    editing: QueuedItem?,
    error: String?,
    onSteer: (QueuedItem) -> Unit,
    onEdit: (QueuedItem) -> Unit,
    onRemove: (QueuedItem) -> Unit,
    onCancelEdit: () -> Unit,
) {
    if (items.isEmpty() && error == null) return
    var expanded by remember { mutableStateOf(true) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .background(Color(0xFF20242E), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "Collapse queue" else "Expand queue",
                tint = MUTED,
                modifier = Modifier.size(16.dp).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { expanded = !expanded },
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = QueueView.countLabel(items) ?: "Queue",
                color = MUTED,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { expanded = !expanded },
            )
            Spacer(Modifier.weight(1f))
            if (editing != null) {
                TextButton(onClick = onCancelEdit) { Text("Cancel edit", fontSize = 11.sp, color = MUTED) }
            }
        }

        if (expanded) {
            items.forEach { item ->
                QueueRow(
                    item = item,
                    running = running,
                    isEditing = editing?.id == item.id,
                    onSteer = { onSteer(item) },
                    onEdit = { onEdit(item) },
                    onRemove = { onRemove(item) },
                )
            }
        }

        error?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = WARN, fontSize = 11.sp)
        }
    }
}

@Composable
private fun QueueRow(
    item: QueuedItem,
    running: Boolean,
    isEditing: Boolean,
    onSteer: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.steering) {
                Text(
                    text = "STEERING",
                    color = ACCENT,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = item.label.ifBlank { "(no text)" },
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (isEditing) ACCENT else Color(0xFFD6DBE6),
                modifier = Modifier.weight(1f),
            )
        }
        // One scrolling row rather than a menu: three actions with words on them
        // are faster to hit than an overflow, and a phone row has room when the
        // label is allowed to wrap above them.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.End,
        ) {
            if (QueueView.canSteer(running, item)) {
                TextButton(onClick = onSteer, contentPadding = Padding0) { Text("Steer", fontSize = 11.sp) }
            }
            if (item.editable) {
                TextButton(onClick = onEdit, contentPadding = Padding0) {
                    Text(if (isEditing) "Editing…" else "Edit", fontSize = 11.sp, color = MUTED)
                }
            }
            TextButton(onClick = onRemove, contentPadding = Padding0) {
                Text("Remove", fontSize = 11.sp, color = MUTED)
            }
        }
    }
}

/**
 * The `/` command menu.
 *
 * Built from the Host's `commands/list` rather than a constant, because the list
 * is per session and a deployment can add commands. Each row says what the
 * command does and, when it takes arguments, what they look like -- the
 * descriptor's own `input.hint`.
 */
@Composable
internal fun CommandMenuPanel(
    suggestions: List<CommandSuggestion>,
    onPick: (CommandSuggestion) -> Unit,
) {
    if (suggestions.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .heightIn(max = 200.dp)
            .background(Color(0xFF20242E), RoundedCornerShape(10.dp))
            .padding(vertical = 4.dp),
    ) {
        suggestions.forEach { suggestion ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onPick(suggestion) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                Text(
                    text = "/${suggestion.command.name}",
                    color = ACCENT,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                // The hint is what tells a reader whether picking it will send or
                // wait for an argument, so it sits beside the name and not in a
                // second line that a narrow row would truncate away.
                suggestion.command.hint?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(it, color = MUTED, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = suggestion.command.description,
                    fontSize = 11.sp,
                    color = MUTED,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Zero padding, so a text button's own minimum does not spread the action row. */
private val Padding0 = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)
