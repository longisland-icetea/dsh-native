package io.github.longislandicetea.dshnative

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/**
 * One block of assistant output.
 *
 * The set is the syntax that actually dominates model output. Anything else is
 * kept as its literal text, which is a truthful degradation: showing `|---|`
 * tells the reader the model emitted a table this build cannot lay out, while
 * dropping it would hide content.
 */
sealed interface MarkdownBlock {
    /** A paragraph, a heading, a list, or a quote: text plus how to treat it. */
    data class Prose(
        val lines: List<String>,
        val kind: ProseKind = ProseKind.Paragraph,
    ) : MarkdownBlock

    data class Code(val language: String?, val code: String) : MarkdownBlock

    /** A pipe table; the first row is the header. */
    data class Table(val header: List<String>, val rows: List<List<String>>) : MarkdownBlock

    /**
     * A delivered figure, alone on its line: `![alt](<path/to/figure.png>)`.
     *
     * A block rather than inline text because that is how the harness asks for a
     * figure to be delivered, and because a figure is the one thing in a reply
     * that is not readable as words. [destination] is the path as written --
     * still relative, still bracketed-free -- since resolving it needs to know
     * what it is relative to, and the parser does not.
     */
    data class Image(val alt: String, val destination: String) : MarkdownBlock

    /** A horizontal rule. */
    data object Rule : MarkdownBlock
}

enum class ProseKind { Paragraph, Heading1, Heading2, Heading3, Bullet, Ordered, Quote }

/**
 * Split assistant output into renderable blocks, and render inline spans.
 *
 * Written by hand rather than pulled in as a dependency: the app has no Markdown
 * library, and the Gradle-free build resolves its dependency set from the app's
 * own graph. The cost is that this covers a subset -- but a subset that is
 * tested against real transcripts (`SimpleMarkdownTest`), not against this
 * file's idea of Markdown.
 */
object SimpleMarkdown {
    private val BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
    private val ORDERED = Regex("^\\s*(\\d{1,3})[.)]\\s+(.*)$")
    private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val TABLE_DIVIDER = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val FENCE = Regex("^\\s*(```|~~~)")

    /**
     * One image reference and nothing else, which is what makes it a figure.
     *
     * The destination is everything to the last bracket on the line, so a
     * bracketed path may hold the spaces and parentheses the harness warns about
     * (`<figures/fig (1).png>`) instead of being cut at the first `)`.
     */
    private val IMAGE = Regex("^!\\[([^\\]]*)\\]\\((.+)\\)$")

    fun parse(text: String): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val prose = mutableListOf<String>()
        var proseKind = ProseKind.Paragraph
        val code = StringBuilder()
        var language: String? = null
        var inCode = false
        val lines = text.lines()
        var index = 0

        fun flush() {
            if (prose.isNotEmpty()) {
                blocks += MarkdownBlock.Prose(prose.toList(), proseKind)
                prose.clear()
            }
            proseKind = ProseKind.Paragraph
        }

        fun add(line: String, kind: ProseKind) {
            // A blank line ends a block, and so does a change of kind. Without
            // the first rule two paragraphs merge into one; without the second a
            // heading absorbs the paragraph under it.
            if (line.isBlank() || (prose.isNotEmpty() && kind != proseKind)) flush()
            if (line.isBlank()) return
            proseKind = kind
            prose += line
        }

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trimStart()
            when {
                inCode -> {
                    if (FENCE.containsMatchIn(trimmed)) {
                        inCode = false
                        blocks += MarkdownBlock.Code(language, code.toString().trimEnd('\n'))
                        code.clear()
                        language = null
                    } else {
                        code.appendLine(line)
                    }
                    index++
                }
                FENCE.containsMatchIn(trimmed) -> {
                    flush()
                    inCode = true
                    language = trimmed.drop(3).trim().ifEmpty { null }
                    index++
                }
                RULE.matches(line) -> {
                    flush()
                    blocks += MarkdownBlock.Rule
                    index++
                }
                // A figure stands where it is written. An image that shares a
                // line with words is not one of these: it stays in the prose and
                // is drawn as its own reference, because a paragraph and a
                // full-width picture interleaving is a layout this renderer does
                // not attempt (see `inline`).
                IMAGE.matchEntire(line.trim())?.takeIf { it.groupValues[2].isNotBlank() } != null -> {
                    flush()
                    val match = IMAGE.matchEntire(line.trim())!!
                    blocks += MarkdownBlock.Image(
                        alt = match.groupValues[1].trim(),
                        destination = unwrapDestination(match.groupValues[2]),
                    )
                    index++
                }
                HEADING.matches(line) -> {
                    flush()
                    val level = line.trimStart().takeWhile { it == '#' }.length
                    val body = HEADING.matchEntire(line.trimStart())!!.groupValues[2]
                    blocks += MarkdownBlock.Prose(
                        listOf(body),
                        when (level) {
                            1 -> ProseKind.Heading1
                            2 -> ProseKind.Heading2
                            else -> ProseKind.Heading3
                        },
                    )
                    index++
                }
                // A table needs its divider on the next line; without one this is
                // just a line containing pipes.
                isTableRow(line) && index + 1 < lines.size && TABLE_DIVIDER.matches(lines[index + 1]) -> {
                    flush()
                    val rows = mutableListOf<List<String>>()
                    index += 2
                    while (index < lines.size && isTableRow(lines[index]) && lines[index].isNotBlank()) {
                        rows += splitRow(lines[index])
                        index++
                    }
                    val (header, padded) = normalizeRows(splitRow(line), rows)
                    blocks += MarkdownBlock.Table(header, padded)
                }
                trimmed.startsWith(">") -> {
                    add(trimmed.removePrefix(">").trimStart(), ProseKind.Quote)
                    index++
                }
                BULLET.matches(line) -> {
                    add(BULLET.matchEntire(line)!!.groupValues[1], ProseKind.Bullet)
                    index++
                }
                ORDERED.matches(line) -> {
                    val match = ORDERED.matchEntire(line)!!
                    add("${match.groupValues[1]}. ${match.groupValues[2]}", ProseKind.Ordered)
                    index++
                }
                else -> {
                    add(line, ProseKind.Paragraph)
                    index++
                }
            }
        }
        if (inCode) {
            // An unterminated fence is normal while a response is streaming.
            blocks += MarkdownBlock.Code(language, code.toString().trimEnd('\n'))
        } else {
            flush()
        }
        return blocks
    }

    private fun isTableRow(line: String): Boolean = line.contains('|') && line.trim().isNotEmpty()

    /**
     * Split one table row into cells.
     *
     * Two escapes have to be honoured, because models emit them and splitting on
     * every pipe corrupts the row rather than the cell:
     *
     *  - `\|` is a literal pipe, which is how LaTeX absolute values arrive
     *    (`\left| x \right|` becomes `\left\| x \right\|` inside a cell);
     *  - a pipe inside `` `code` `` is part of the literal text, not a separator.
     *
     * Getting this wrong produced rows with more cells than the header, and a row
     * with extra cells lays its content out shifted by one column -- which reads as
     * "the columns are not aligned" rather than as a parsing bug.
     */
    internal fun splitRow(line: String): List<String> {
        val trimmed = line.trim()
        val body = trimmed.removePrefix("|").removeSuffix("|")
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inCode = false
        var index = 0
        while (index < body.length) {
            val ch = body[index]
            when {
                ch == '\\' && index + 1 < body.length && body[index + 1] == '|' -> {
                    current.append('|')
                    index += 2
                }
                ch == '`' -> {
                    inCode = !inCode
                    current.append(ch)
                    index++
                }
                ch == '|' && !inCode -> {
                    cells += current.toString().trim()
                    current.setLength(0)
                    index++
                }
                else -> {
                    current.append(ch)
                    index++
                }
            }
        }
        cells += current.toString().trim()
        return cells
    }

    /**
     * Pad every row out to the header's column count.
     *
     * The header is the authority on how many columns a table has. A short row is
     * common and harmless once padded; a long one is a parsing artefact, but
     * trimming it is still better than shifting the row's cells sideways.
     */
    internal fun normalizeRows(
        header: List<String>,
        rows: List<List<String>>,
    ): Pair<List<String>, List<List<String>>> {
        val columns = header.size
        if (columns == 0) return header to rows
        return header to rows.map { row ->
            when {
                row.size == columns -> row
                row.size < columns -> row + List(columns - row.size) { "" }
                else -> row.take(columns)
            }
        }
    }

    /**
     * Inline spans: `code`, **bold**, *italic*, ~~strike~~, [text](url), and
     * bare URLs.
     *
     * A link keeps its text and prints its destination after it: a phone cannot
     * hover, and the path is often the thing the model wants read. It is also
     * *tappable* now -- that is how a reply delivers a file (`[Report](<out/report.md>)`,
     * `![figure](<out/fig1.png>)`), and a delivery the reader cannot open is not
     * a delivery.
     *
     * [onOpenLink] receives the destination *as written*, not a resolved path:
     * what it is relative to is a fact about the surface the text is drawn on --
     * the session's workspace for a message, the file's own folder for a
     * previewed document -- and this function has neither. When it is null every
     * link stays inert, which is what a caller with nowhere to open one wants.
     *
     * An image that shares its line with prose is not laid out (there is no
     * layout here for a picture between two words). It is drawn as its own
     * reference instead, and it is still openable.
     */
    fun inline(
        line: String,
        accent: Color,
        codeColor: Color,
        onOpenLink: ((String) -> Unit)? = null,
    ): AnnotatedString = buildAnnotatedString {
        var index = 0
        while (index < line.length) {
            when {
                line.startsWith("**", index) || line.startsWith("__", index) -> {
                    val marker = line.substring(index, index + 2)
                    val end = line.indexOf(marker, index + 2)
                    if (end < 0) { append(line[index]); index++ } else {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append(line.substring(index + 2, end))
                        }
                        index = end + 2
                    }
                }
                line.startsWith("~~", index) -> {
                    val end = line.indexOf("~~", index + 2)
                    if (end < 0) { append(line[index]); index++ } else {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            append(line.substring(index + 2, end))
                        }
                        index = end + 2
                    }
                }
                line[index] == '`' -> {
                    val end = line.indexOf('`', index + 1)
                    if (end < 0) { append(line[index]); index++ } else {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor)) {
                            append(line.substring(index + 1, end))
                        }
                        index = end + 1
                    }
                }
                line[index] == '[' || (line[index] == '!' && line.getOrNull(index + 1) == '[') -> {
                    val link = referenceAt(line, index)
                    if (link == null) {
                        append(line[index]); index++
                    } else {
                        val destination = unwrapDestination(link.destination)
                        val target = linkTargetOf(destination)
                        val open = onOpenLink?.takeIf { target !is LinkTarget.Inert }
                        if (open == null) {
                            withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) {
                                append(link.label)
                            }
                        } else {
                            withLink(
                                LinkAnnotation.Url(
                                    // Only a payload for the listener below; a
                                    // file destination gets a scheme of its own
                                    // so that a tap which somehow missed the
                                    // listener opens nothing rather than a URL.
                                    url = if (target is LinkTarget.External) target.url else FILE_LINK_SCHEME + destination,
                                    styles = TextLinkStyles(
                                        style = SpanStyle(color = accent, textDecoration = TextDecoration.Underline),
                                    ),
                                    linkInteractionListener = LinkInteractionListener { onOpenLink(destination) },
                                ),
                            ) { append(link.label) }
                        }
                        // The `!` is syntax and is not printed: a reader shown
                        // `!fig1 (out/fig1.png)` reads a broken reference where
                        // the line is a perfectly good one.
                        withStyle(SpanStyle(color = MUTED_LINK)) { append(" ($destination)") }
                        index = link.end
                    }
                }
                isBareUrlStart(line, index) -> {
                    val end = line.indexOfFirstFrom(index) { it == ' ' || it == ')' || it == '，' || it == '。' }
                    val url = if (end < 0) line.substring(index) else line.substring(index, end)
                    withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) {
                        append(url)
                    }
                    index += url.length
                }
                // Emphasis only opens where it reads as emphasis: `2 * 3` and
                // `a_b_c` are arithmetic and identifiers, and eating their
                // separators corrupts the text.
                line[index] == '*' || line[index] == '_' -> {
                    val marker = line[index]
                    val before = line.getOrNull(index - 1)
                    val after = line.getOrNull(index + 1)
                    val opensEmphasis = after != null && !after.isWhitespace() &&
                        (marker == '*' || before == null || before.isWhitespace() || before in "([{")
                    val end = if (opensEmphasis) line.indexOf(marker, index + 1) else -1
                    if (end < 0) { append(line[index]); index++ } else {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(line.substring(index + 1, end))
                        }
                        index = end + 1
                    }
                }
                else -> {
                    append(line[index])
                    index++
                }
            }
        }
    }

    /** One `[label](destination)` found in a line, and where it ends. */
    internal class Reference(val label: String, val destination: String, val end: Int)

    /**
     * The reference starting at [index], which points at `[` or at the `!` of
     * `![`.
     *
     * A bracketed destination ends at its `>` rather than at the first `)`, so a
     * path the harness told the model to bracket for its spaces or parentheses
     * (`<figures/fig (1).png>`) survives being read back. An unbracketed one
     * ends at the first `)`, which is all Markdown allows it to contain.
     */
    internal fun referenceAt(line: String, index: Int): Reference? {
        val open = if (line.getOrNull(index) == '!') index + 1 else index
        if (line.getOrNull(open) != '[') return null
        val labelEnd = line.indexOf(']', open + 1)
        if (labelEnd < 0 || line.getOrNull(labelEnd + 1) != '(') return null
        val start = labelEnd + 2
        val close = if (line.getOrNull(start) == '<') {
            val bracket = line.indexOf('>', start + 1)
            if (bracket < 0 || line.getOrNull(bracket + 1) != ')') -1 else bracket + 1
        } else {
            line.indexOf(')', start)
        }
        if (close < 0) return null
        return Reference(line.substring(open + 1, labelEnd), line.substring(start, close), close + 1)
    }

    private val MUTED_LINK = Color(0xFF6C7484)

    private fun isBareUrlStart(line: String, index: Int): Boolean {
        if (index > 0 && (line[index - 1].isLetterOrDigit() || line[index - 1] == '/')) return false
        return line.startsWith("http://", index) || line.startsWith("https://", index)
    }

    private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
        for (i in start until length) if (predicate(this[i])) return i
        return -1
    }
}
