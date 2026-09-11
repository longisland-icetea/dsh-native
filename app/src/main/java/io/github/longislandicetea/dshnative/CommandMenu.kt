package io.github.longislandicetea.dshnative

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One slash command the Host offers.
 *
 * From `commands/list {agentId}`: a name without the slash, a description for the
 * discovery list, and an optional input hint that says whether the command wants
 * arguments. The app used to hardcode `/compact`; the Host ships six of these and
 * a deployment can add more, which is why the menu is built from this list rather
 * than from a constant.
 */

@Serializable
data class CommandInfo(
    val name: String,
    val description: String = "",
    val input: CommandInput? = null,
) {
    val hint: String? get() = input?.hint

    /**
     * Whether picking this command should send it immediately.
     *
     * A command with no input descriptor takes no arguments (`compact`, `export`),
     * so making the reader press send again would be a wasted step. One that
     * declares a hint may or may not need an argument, so its name is inserted and
     * the reader completes the line.
     */
    val runsOnPick: Boolean get() = input?.hint == null
}

@Serializable
data class CommandInput(
    val hint: String? = null,
    val attachments: Boolean? = null,
)

object CommandCodec {
    /** The `commands/list` value: a bare array, or an object wrapping one. */
    fun parse(value: JsonElement?): List<CommandInfo> {
        val array = when (value) {
            is JsonArray -> value
            is JsonObject -> (value["commands"] ?: value["items"]) as? JsonArray
            else -> null
        } ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching {
                DshWire.json.decodeFromJsonElement(CommandInfo.serializer(), element)
            }.getOrNull()?.takeIf { it.name.isNotBlank() }
        }
    }
}

/** One row of the command menu, or the composer's own state while typing one. */
data class CommandSuggestion(val command: CommandInfo, val line: String)

/**
 * When to offer the command menu, and what to offer in it.
 *
 * The web client triggers on `/` anywhere in the composer and ranks by name. On a
 * phone the useful case is narrower: the draft *starts* with a slash, because a
 * slash in the middle of a sentence is punctuation. That is the one deliberate
 * difference from the web trigger, and it is why this is a named rule rather than
 * an inline condition.
 */
object CommandMenu {
    /** The token being typed, or null when the draft is not a command line. */
    fun query(draft: String): String? {
        val trimmed = draft.trimStart()
        if (!trimmed.startsWith("/")) return null
        val token = trimmed.drop(1)
        // A space ends the name: `/permission workspace-write` is an argument, and
        // the menu has nothing left to suggest.
        if (token.any { it.isWhitespace() }) return null
        return token
    }

    /** Whether the menu should be on screen for this draft. */
    fun isOpen(draft: String): Boolean = query(draft) != null

    /**
     * Commands matching what has been typed.
     *
     * Name-prefix matches are the answer when there are any, and a description
     * match is only consulted when there are none. Mixing them was the first
     * attempt and it read badly: `/co` offered `feedback`, because its description
     * ("record feedback...") happens to contain the letters, and a menu whose
     * second row has nothing to do with what was typed is worse than a short one.
     */
    fun suggestions(draft: String, commands: List<CommandInfo>): List<CommandSuggestion> {
        val query = query(draft) ?: return emptyList()
        val needle = query.lowercase()
        val byName = commands.filter { it.name.lowercase().startsWith(needle) }
        val matched = if (byName.isNotEmpty() || needle.isEmpty()) {
            byName
        } else {
            commands.filter { it.description.lowercase().contains(needle) }
        }
        return matched.map { CommandSuggestion(it, "/${it.name}") }
    }

    /**
     * The draft after picking a command.
     *
     * A no-argument command still gets a trailing space so the reader can see the
     * line is complete and the send control is ready; a command that takes input
     * needs that space to start typing the argument.
     */
    fun lineFor(command: CommandInfo): String = "/${command.name} "

    /** A command line, ready to execute: the name plus whatever follows it. */
    fun commandLine(draft: String): String? {
        val trimmed = draft.trim()
        if (!trimmed.startsWith("/")) return null
        if (trimmed.length < 2) return null
        val name = trimmed.drop(1).substringBefore(' ')
        if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return null
        return trimmed
    }
}

/**
 * Whether a draft that was just submitted may be cleared, and why not when it
 * may not.
 *
 * The composer used to clear the field first and let the send path decide what to
 * do with the text, which meant every refusal -- no client yet, no open
 * conversation, a missing waterfall id, a blank line -- destroyed what the reader
 * wrote and said nothing. A message that is not sent has to stay in the box.
 *
 * Pure so the rule is one place rather than repeated at each of the four actions
 * that submit from the composer.
 */
internal enum class DraftOutcome {
    /** The text was handed to something that will send it; clear the field. */
    Accepted,

    /** Keep the field, with this reason shown to the reader. */
    Refused;

    val clearsDraft: Boolean get() = this == Accepted
}

/**
 * Decide whether the draft leaves the composer.
 *
 * `ready` is the caller's own precondition -- a client and an open conversation
 * for a prompt, the same plus a loaded command list for a command -- and `text`
 * the draft, because an empty one is not a message worth keeping.
 */
internal fun draftOutcome(ready: Boolean, text: String): DraftOutcome = when {
    text.isBlank() -> DraftOutcome.Refused
    !ready -> DraftOutcome.Refused
    else -> DraftOutcome.Accepted
}

/** The line shown when a draft could not be sent, so the refusal is not silent. */
internal const val DRAFT_REFUSED = "Not sent: the connection is not ready. Your message is still here."
