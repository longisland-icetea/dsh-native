package io.github.longislandicetea.dshnative

/**
 * A file a reply named, in the spelling the reply used.
 *
 * The harness tells the model how to deliver a file, and what it says is to
 * *link* it: `[Description](<path/to/file.md>` for a document,
 * `![Description](<path/to/figure.png>)` for a figure, the destination "relative
 * to the working directory or absolute", with a line anchor (`#L24`,
 * `#L24-L30`) when the point is a few lines rather than a whole file. So a
 * delivery arrives as text inside a message, and opening it means turning that
 * text back into something the Host can be asked for.
 *
 * [path] is kept exactly as written -- a relative path stays relative -- because
 * only the caller knows what it is relative *to*. In a message that is the
 * session's working directory; inside a previewed document it is that file's own
 * folder, which is a different answer to the same question and the reason this
 * type does not resolve anything by itself.
 */
internal data class FileRef(
    val path: String,
    /** The lines the reference pointed at (`#L24`, `#L24-L30`), when it named any. */
    val lines: IntRange? = null,
)

/**
 * What a link destination in a reply names.
 *
 * Three answers rather than a nullable path, because the three are handled
 * differently and "not a file" is not one thing: an `http` link belongs to the
 * browser, and a fragment or a scheme this client does not speak belongs to
 * nobody -- drawing either as a file reference would send the reader to a read
 * that cannot succeed.
 */
internal sealed interface LinkTarget {
    /** A file the Host can be asked for. */
    data class File(val ref: FileRef) : LinkTarget

    /** Something for the browser. */
    data class External(val url: String) : LinkTarget

    /** Not something this client opens: a bare fragment, a foreign scheme, an empty destination. */
    data object Inert : LinkTarget
}

/**
 * The scheme a file link's annotation carries.
 *
 * A tapped link is handled by the app (it opens the preview sheet), so its URL
 * is only ever a payload. Giving a file destination a scheme of its own means
 * that if the tap ever escapes to the platform's URL handler it opens *nothing*
 * -- a made-up scheme has no activity -- rather than mistaking `out/report.md`
 * for something a browser should fetch.
 */
internal const val FILE_LINK_SCHEME = "dsh-file://"

/**
 * Whether a path is absolute in either spelling the Host accepts: POSIX (`/a/b`),
 * a Windows drive (`C:\a`), or a UNC share (`\\server\share`).
 *
 * A drive letter only counts when a separator follows it. `c:notes.md` is a
 * relative name on that drive's current directory rather than a path, and the
 * web client's own rule (`/^[A-Za-z]:[/\\]/`) draws the line in the same place.
 */
internal fun isAbsoluteWorkspacePath(path: String): Boolean {
    if (path.startsWith("/") || path.startsWith("\\\\")) return true
    return path.length >= 3 && path[0].isLetter() && path[1] == ':' && (path[2] == '/' || path[2] == '\\')
}

/** The folder part of a path, separator included: `/a/b/report.md` -> `/a/b/`, `report.md` -> `""`. */
internal fun directoryOf(path: String): String {
    val cut = path.indexOfLast { it == '/' || it == '\\' }
    return if (cut < 0) "" else path.substring(0, cut + 1)
}

/**
 * Resolve a path against the folder it was written in.
 *
 * This is the Host's rule as the web client states it: an absolute path is
 * itself, and a relative one is joined to the base. The separator is chosen from
 * the base rather than assumed, because the two spellings are not
 * interchangeable on the machine that produced them -- `C:\work` with a `/`
 * appended reads as one long file name there, and the read fails with a
 * not-found that says nothing about why.
 *
 * A blank base resolves nothing: better to hand the Host the relative path it
 * may still be able to place than to invent a root for it.
 */
internal fun resolveWorkspacePath(base: String?, path: String): String {
    if (isAbsoluteWorkspacePath(path)) return path
    val root = base?.takeIf { it.isNotBlank() } ?: return path
    val windows = root.contains('\\') && !root.contains('/')
    val separator = if (windows) "\\" else "/"
    // The web client joins with the base's separator and leaves the rest of the
    // destination as written, which on Windows yields `C:\work\out/a.md`. That
    // resolves there, but only because Windows treats both separators as
    // separators -- so normalizing the rest costs nothing and removes a spelling
    // that reads as a mistake in the one place it is looked at.
    val rest = path.trimStart('/', '\\').let { if (windows) it.replace('/', '\\') else it }
    return root.trimEnd('/', '\\') + separator + rest
}

/**
 * Strip the `<...>` a Markdown destination may be wrapped in.
 *
 * The harness asks for them ("Enclose Markdown file destinations in angle
 * brackets, especially paths containing spaces"), and they are the only way a
 * destination may contain a space at all -- so the brackets are syntax, and what
 * is inside them is the path.
 */
internal fun unwrapDestination(destination: String): String {
    val trimmed = destination.trim()
    return if (trimmed.length >= 2 && trimmed.startsWith("<") && trimmed.endsWith(">")) {
        trimmed.substring(1, trimmed.length - 1).trim()
    } else {
        trimmed
    }
}

/**
 * Percent-escapes decoded, `%XX` at a time, with everything else left alone.
 *
 * A model writing a path with a space usually writes the space (`My Report.md`)
 * rather than `My%20Report.md`, but both arrive, and the web client decodes the
 * second (`decodeURIComponent`) -- so a link it can follow must not be one this
 * client cannot. Malformed escapes are kept verbatim rather than dropped: `%` is
 * a legal character in a file name, and eating one would corrupt a path the
 * model wrote correctly.
 *
 * The bytes are decoded as UTF-8, because that is what a percent-escape of a
 * non-ASCII path (`%E4%B8%AD`) is.
 */
internal fun percentDecoded(value: String): String {
    if (!value.contains('%')) return value
    val out = java.io.ByteArrayOutputStream(value.length)
    var index = 0
    while (index < value.length) {
        val character = value[index]
        val high = if (character == '%' && index + 2 < value.length) hexDigit(value[index + 1]) else -1
        val low = if (high >= 0) hexDigit(value[index + 2]) else -1
        if (high >= 0 && low >= 0) {
            out.write((high shl 4) or low)
            index += 3
        } else {
            out.write(character.toString().toByteArray(Charsets.UTF_8))
            index += 1
        }
    }
    return String(out.toByteArray(), Charsets.UTF_8)
}

private fun hexDigit(character: Char): Int = when (character) {
    in '0'..'9' -> character - '0'
    in 'a'..'f' -> character - 'a' + 10
    in 'A'..'F' -> character - 'A' + 10
    else -> -1
}

/**
 * The scheme a destination starts with, when it starts with one.
 *
 * The test is the web client's (`/^[a-z][a-z\d+.-]*:/i`), and a Windows drive is
 * excluded first because `C:` is spelled like a scheme and is not one.
 */
internal fun schemeOf(path: String): String? {
    if (isAbsoluteWorkspacePath(path)) return null
    if (path.isEmpty() || !path[0].isLetter()) return null
    var index = 1
    while (index < path.length) {
        val character = path[index]
        when {
            character == ':' -> return path.substring(0, index).lowercase()
            character.isLetterOrDigit() || character == '+' || character == '.' || character == '-' -> index++
            else -> return null
        }
    }
    return null
}

/**
 * The line range a fragment names, when it names one.
 *
 * `#L24` and `#L24-L30` are the harness's own spelling. The en dash is accepted
 * too, and the `L` is case-folded: the same instruction that asks for `#L24`
 * asks for a `24–30` suffix elsewhere in prose, and a model writing Chinese
 * punctuation-adjacent ranges emits `–` often enough that rejecting it would
 * lose the anchor rather than the link.
 *
 * Anything else in the fragment (`#section`, `#L`, `#L0`, a reversed range)
 * yields null: the reference still names a file, and inventing a line for it
 * would be worse than opening at the top.
 */
internal fun lineRangeOf(fragment: String): IntRange? {
    val text = fragment.removePrefix("#")
    if (text.length < 2 || text[0].uppercaseChar() != 'L') return null
    // The `L` is repeated after the dash (`#L24-L30`), so the end of the range is
    // stripped of it as well; `#L24-30` is accepted because it means the same.
    val body = text.substring(1).replace('\u2013', '-').replace('\u2014', '-')
    fun line(value: String): Int? = value.trim().removePrefix("L").removePrefix("l").trim().toIntOrNull()
    val split = body.indexOf('-')
    val first = if (split < 0) body else body.substring(0, split)
    val last = if (split < 0) body else body.substring(split + 1)
    val start = line(first) ?: return null
    val end = (if (last.isBlank()) line(first) else line(last)) ?: return null
    if (start < 1 || end < start) return null
    return start..end
}

/**
 * Classify one link or image destination.
 *
 * The fragment and the query are not part of the name -- `report.md#L24` is a
 * file called `report.md` -- which is the rule the web client's image path
 * resolution states in the same words ("query and fragment are not filename
 * components"). The fragment is kept as a line anchor because that is what the
 * harness puts in it.
 */
internal fun linkTargetOf(destination: String): LinkTarget {
    val text = unwrapDestination(destination)
    if (text.isEmpty() || text.startsWith("#")) return LinkTarget.Inert
    // The scheme is read off the whole destination, because a URL's query *is*
    // part of the address -- cutting it off would send the browser somewhere
    // else. Only a path has a fragment that is not part of the name.
    schemeOf(text)?.let { scheme ->
        return if (scheme == "http" || scheme == "https") LinkTarget.External(text) else LinkTarget.Inert
    }
    val cut = text.indexOfFirst { it == '#' || it == '?' }
    val rawPath = if (cut < 0) text else text.substring(0, cut)
    val fragment = if (cut < 0) "" else text.substring(cut)
    val path = percentDecoded(rawPath)
    if (path.isEmpty()) return LinkTarget.Inert
    return LinkTarget.File(FileRef(path, lineRangeOf(fragment)))
}
