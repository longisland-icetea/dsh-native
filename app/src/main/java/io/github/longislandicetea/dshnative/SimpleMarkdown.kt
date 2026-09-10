package io.github.longislandicetea.dshnative

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** A parsed block of assistant output: prose or a fenced code block. */
sealed interface MarkdownBlock {
    data class Prose(val lines: List<String>) : MarkdownBlock
    data class Code(val language: String?, val code: String) : MarkdownBlock
}

/**
 * Split assistant output into prose and fenced-code blocks.
 *
 * This is the subset of Markdown that actually dominates model output: fenced
 * code, inline code, bold and italic. Anything else renders as its literal text,
 * which is a truthful degradation rather than a wrong rendering.
 */
object SimpleMarkdown {
    fun parse(text: String): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val prose = mutableListOf<String>()
        val code = StringBuilder()
        var language: String? = null
        var inCode = false

        fun flushProse() {
            if (prose.isNotEmpty()) {
                blocks += MarkdownBlock.Prose(prose.toList())
                prose.clear()
            }
        }

        text.lines().forEach { line ->
            val trimmed = line.trimStart()
            when {
                !inCode && (trimmed.startsWith("```") || trimmed.startsWith("~~~")) -> {
                    flushProse()
                    inCode = true
                    language = trimmed.drop(3).trim().ifEmpty { null }
                    code.clear()
                }
                inCode && (trimmed.startsWith("```") || trimmed.startsWith("~~~")) -> {
                    inCode = false
                    blocks += MarkdownBlock.Code(language, code.toString().trimEnd('\n'))
                    code.clear()
                    language = null
                }
                inCode -> code.appendLine(line)
                else -> prose += line
            }
        }
        if (inCode) {
            // An unterminated fence is normal while a response is streaming.
            blocks += MarkdownBlock.Code(language, code.toString().trimEnd('\n'))
        } else {
            flushProse()
        }
        return blocks
    }

    /** Inline spans: `code`, **bold**, *italic*. */
    fun inline(line: String, codeColor: androidx.compose.ui.graphics.Color): AnnotatedString =
        buildAnnotatedString {
            var index = 0
            while (index < line.length) {
                when {
                    line.startsWith("**", index) -> {
                        val end = line.indexOf("**", index + 2)
                        if (end < 0) { append(line[index]); index++ } else {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                                append(line.substring(index + 2, end))
                            }
                            index = end + 2
                        }
                    }
                    line[index] == '`' -> {
                        val end = line.indexOf('`', index + 1)
                        if (end < 0) { append(line[index]); index++ } else {
                            withStyle(
                                SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor),
                            ) { append(line.substring(index + 1, end)) }
                            index = end + 1
                        }
                    }
                    line[index] == '*' -> {
                        val end = line.indexOf('*', index + 1)
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
}
