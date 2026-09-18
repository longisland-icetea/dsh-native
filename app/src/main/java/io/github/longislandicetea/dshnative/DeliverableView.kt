package io.github.longislandicetea.dshnative

/**
 * How a delivered file should be shown when the reader taps it.
 *
 * A separate decision from the read itself, and a pure one, because it is the
 * part that was wrong before: every text deliverable was drawn as monospace
 * source on the argument that "markdown rendering would hide the source the
 * model wrote". That is true of a source file and false of a document. A turn
 * whose deliverable is a Markdown report delivers a *document*, and rendering
 * it as source shows the reader `## Heading` and `**bold**` where the harness's
 * own message path -- and the web client's document preview -- would show a
 * heading and bold text. This is the same file, arriving by the same protocol;
 * only the renderer differed.
 *
 * The rule is the extension, which is what every other renderer of this surface
 * keys on, including the harness's own document-preview registry
 * (`extensions: ["md", "markdown"]`).
 */
internal enum class DeliverableView {
    /** Markdown: laid out the way a transcript message is. */
    Document,

    /** Anything else textual: selectable monospace source, as it was written. */
    Source,
}

/**
 * Markdown extensions, exactly the harness's own document-preview registry
 * (`markdownDefinition`: `extensions: ["md", "markdown"]`).
 *
 * Two entries rather than a longer guess at "everything that is markdown":
 * `mdown` and `mkd` are real Markdown extensions, but this app's claim is to
 * agree with the server it is a client of, and a file the harness would show as
 * plain text should not be the one the phone lays out as a document.
 */
private val MARKDOWN_EXTENSIONS = setOf("md", "markdown")

/**
 * Which view a path opens in.
 *
 * The test is on the path as delivered -- including one naming a file that is
 * not there -- because the view is decided before the read answers, and a file
 * that fails to read should not change how it *would* have been drawn. A path
 * with no extension, or one whose extension merely starts with the letters
 * (`mdx`, `md5`), is not Markdown: the first has nothing to go on, and the
 * second is a different format that happens to share a prefix.
 *
 * Case is folded because a deliverable written as `REPORT.MD` is still a
 * Markdown document, and on the machine that produced it that may well be the
 * only spelling.
 */
internal fun deliverableViewFor(path: String): DeliverableView {
    val name = path.substringAfterLast('/').substringAfterLast('\\')
    val dot = name.lastIndexOf('.')
    // A name with no dot, or one that *starts* with it, has no extension: `.md`
    // is a filename, and reading it as "the extension is md" would render a
    // dotfile as a document.
    if (dot <= 0) return DeliverableView.Source
    val extension = name.substring(dot + 1)
    return if (extension.lowercase() in MARKDOWN_EXTENSIONS) DeliverableView.Document
    else DeliverableView.Source
}

/**
 * What a rendered document shows, given a file read through the Host.
 *
 * The one removal, and it is the difference between "a rendered document" and
 * "a rendered transcript of harness machinery":
 *
 * **Front matter.** Model-written reports routinely open with a YAML block
 * (`---\ntitle: …\n---`). Rendered as Markdown, the opening `---` becomes a
 * horizontal rule and the fields below it become a paragraph of keys, so the
 * document starts with its own metadata instead of its content. The harness's
 * message path never sees this — a transcript message is not a file — so it has
 * no rule for it; a file does.
 *
 * The scan for the closing delimiter stops at the first blank line, because
 * front matter is a block and a block ends at a blank line. Without that bound,
 * a document opening with a rule (`---`) and later containing another rule would
 * have everything between them deleted — documents are allowed to contain a
 * `---` line, and a header-strip that eats half a report is worse than one that
 * leaves a stray `title:` at the top.
 *
 * Only a block that *closes* is stripped. A document whose first line is `---`
 * and which never closes it is a document starting with a rule, and eating it
 * would silently delete content to satisfy a guess.
 */
internal fun deliverableDocumentText(text: String): String {
    // A leading BOM is invisible and would defeat the delimiter test by putting
    // one character in front of it, so it goes first and unconditionally.
    val body = text.removePrefix("\uFEFF")
    val lines = body.lines()
    if (lines.firstOrNull()?.trim() != "---") return body
    // Walk to the closing delimiter, stopping at a blank line: front matter is a
    // contiguous block, so a gap before any `---` means the first line was a
    // horizontal rule rather than the start of metadata.
    var closing = -1
    for (index in 1 until lines.size) {
        val line = lines[index].trim()
        if (line.isEmpty()) break
        if (line == "---" || line == "...") {
            closing = index
            break
        }
    }
    if (closing < 0) return body
    // The blank line the removed block leaves behind is dropped too, so the
    // document starts at its first real line rather than at a gap where
    // metadata used to be.
    return lines.drop(closing + 1).dropWhile { it.isBlank() }.joinToString("\n")
}
