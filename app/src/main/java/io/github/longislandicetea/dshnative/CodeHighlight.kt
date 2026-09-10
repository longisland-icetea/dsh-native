package io.github.longislandicetea.dshnative

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle

/**
 * A deliberately small syntax highlighter.
 *
 * Every library candidate was either missing from Maven Central or pulled in a
 * WebView / grammar assets, which is exactly the weight this client exists to
 * avoid. Chat output is dominated by fenced blocks in a handful of languages,
 * so a keyword/string/comment/number tokenizer covers the useful majority; a
 * language it does not know still renders as plain monospace rather than
 * failing.
 */
object CodeHighlight {
    private val KEYWORDS = setOf(
        // shared
        "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue",
        "return", "try", "catch", "finally", "throw", "throws", "new", "class", "interface",
        "enum", "extends", "implements", "import", "package", "public", "private", "protected",
        "static", "final", "abstract", "void", "this", "super", "null", "true", "false",
        // types and modifiers across the languages that show up in chat
        "fun", "val", "var", "when", "object", "data", "suspend", "override", "open", "sealed",
        "const", "let", "async", "await", "yield", "def", "lambda", "None", "True", "False",
        "self", "from", "as", "with", "in", "is", "not", "and", "or", "pass", "raise",
        "function", "typeof", "instanceof", "export", "require", "module", "struct", "impl",
        "trait", "pub", "fn", "mut", "use", "match", "loop", "int", "long", "double", "float",
        "char", "bool", "boolean", "string", "String", "List", "Map", "Set", "Integer",
        "type", "namespace", "template", "typename", "virtual", "inline", "extern", "goto",
    )

    private val THEME = mapOf(
        "keyword" to Color(0xFFC678DD),
        "string" to Color(0xFF98C379),
        "comment" to Color(0xFF7F848E),
        "number" to Color(0xFFD19A66),
        "type" to Color(0xFFE5C07B),
        "plain" to Color(0xFFD7DAE0),
    )

    private val LINE_COMMENT = mapOf(
        "py" to "#", "python" to "#", "sh" to "#", "bash" to "#", "zsh" to "#",
        "yaml" to "#", "yml" to "#", "toml" to "#", "ini" to "#", "conf" to "#",
        "rb" to "#", "ruby" to "#", "ps1" to "#", "powershell" to "#",
    )

    val palette: Map<String, Color> get() = THEME

    /** Tokenize [code] as [language] (an info string from a fence, may be blank). */
    fun highlight(code: String, language: String?): AnnotatedString {
        val lang = language?.trim()?.lowercase().orEmpty()
        val lineComment = LINE_COMMENT[lang] ?: "//"
        val blockComments = lang !in setOf("py", "python", "sh", "bash", "yaml", "yml", "rb", "ruby")

        return buildAnnotatedString {
            var index = 0
            while (index < code.length) {
                val rest = code.substring(index)
                when {
                    blockComments && rest.startsWith("/*") -> {
                        val end = code.indexOf("*/", index + 2).let { if (it < 0) code.length else it + 2 }
                        push(code.substring(index, end), "comment")
                        index = end
                    }
                    rest.startsWith(lineComment) -> {
                        val end = code.indexOf('\n', index).let { if (it < 0) code.length else it }
                        push(code.substring(index, end), "comment")
                        index = end
                    }
                    code[index] == '"' || code[index] == '\'' || code[index] == '`' -> {
                        val quote = code[index]
                        var end = index + 1
                        while (end < code.length && code[end] != quote) {
                            if (code[end] == '\\') end++
                            end++
                        }
                        end = minOf(end + 1, code.length)
                        push(code.substring(index, end), "string")
                        index = end
                    }
                    code[index].isDigit() -> {
                        var end = index
                        while (end < code.length && (code[end].isLetterOrDigit() || code[end] == '.' || code[end] == '_')) end++
                        push(code.substring(index, end), "number")
                        index = end
                    }
                    code[index].isLetter() || code[index] == '_' -> {
                        var end = index
                        while (end < code.length && (code[end].isLetterOrDigit() || code[end] == '_')) end++
                        val word = code.substring(index, end)
                        when {
                            word in KEYWORDS -> push(word, "keyword")
                            word.firstOrNull()?.isUpperCase() == true -> push(word, "type")
                            else -> push(word, "plain")
                        }
                        index = end
                    }
                    else -> {
                        push(code[index].toString(), "plain")
                        index++
                    }
                }
            }
        }
    }

    private fun AnnotatedString.Builder.push(text: String, kind: String) {
        withStyle(SpanStyle(color = THEME[kind] ?: THEME.getValue("plain"))) { append(text) }
    }

    val mono: FontFamily = FontFamily.Monospace
}
