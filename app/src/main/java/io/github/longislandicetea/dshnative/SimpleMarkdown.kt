package io.github.longislandicetea.dshnative

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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
                    val header = splitRow(line)
                    val rows = mutableListOf<List<String>>()
                    index += 2
                    while (index < lines.size && isTableRow(lines[index]) && lines[index].isNotBlank()) {
                        rows += splitRow(lines[index])
                        index++
                    }
                    blocks += MarkdownBlock.Table(header, rows)
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

    private fun splitRow(line: String): List<String> =
        line.trim().trim('|').split('|').map { it.trim() }

    /**
     * Inline spans: `code`, **bold**, *italic*, ~~strike~~, [text](url), and
     * bare URLs.
     *
     * Audio is not emitted and images are not laid out, so a link keeps its text
     * and prints its target after it: a phone cannot hover, and the URL is often
     * the thing the model wants read.
     */
    fun inline(
        line: String,
        accent: Color,
        codeColor: Color,
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
                line[index] == '[' -> {
                    val labelEnd = line.indexOf(']', index + 1)
                    val urlStart = if (labelEnd >= 0 && labelEnd + 1 < line.length && line[labelEnd + 1] == '(') labelEnd + 2 else -1
                    val urlEnd = if (urlStart >= 0) line.indexOf(')', urlStart) else -1
                    if (urlStart < 0 || urlEnd < 0) {
                        append(line[index]); index++
                    } else {
                        val label = line.substring(index + 1, labelEnd)
                        val url = line.substring(urlStart, urlEnd)
                        // A link is styled, not clickable: opening one needs a
                        // UriHandler, and the label plus its target is what the
                        // reader acts on. Printing the target also survives being
                        // copied out of the transcript.
                        withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) {
                            append(label)
                        }
                        withStyle(SpanStyle(color = MUTED_LINK)) { append(" ($url)") }
                        index = urlEnd + 1
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
