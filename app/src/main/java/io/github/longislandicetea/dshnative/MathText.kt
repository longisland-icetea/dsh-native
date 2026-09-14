package io.github.longislandicetea.dshnative

/**
 * Whether a line of prose carries mathematics.
 *
 * The rendering itself is KaTeX's job -- it runs in a WebView with the app's own
 * bundled copy, so nothing is fetched at runtime, and its `auto-render` extension
 * decides what is a formula by exactly the rules the web client uses. This only
 * answers the cheaper question of whether a block is worth handing to a WebView
 * at all, and it is deliberately conservative: a false positive costs a WebView
 * where a `Text` would have done, but a false negative shows the reader raw TeX.
 *
 * `$` is the delimiter that needs the care. It is also a currency symbol, so an
 * inline span only counts when it is shaped like mathematics rather than like
 * money: no whitespace just inside the delimiters, a closing delimiter on the
 * same line, and something mathematical in between.
 */
object MathText {
    /** Display delimiters, which are unambiguous and always mean mathematics. */
    private val DISPLAY = listOf("$$", "\\[", "\\]")

    /**
     * Something that makes `$…$` mathematics rather than money: a backslash (a
     * command), a `^` or `_` (a script), an operator, or a letter next to a digit.
     */
    private val MATHEMATICAL = Regex("""[\\^_{}]|=|\\frac|\\sum|\\int|[a-zA-Z]\s*[0-9]|[0-9]\s*[a-zA-Z]""")

    fun hasMath(lines: List<String>): Boolean = lines.any { hasMath(it) }

    fun hasMath(line: String): Boolean {
        if (DISPLAY.any { line.contains(it) }) return true
        if (line.contains("\\(") && line.contains("\\)")) return true
        return inlineSpans(line).isNotEmpty()
    }

    /**
     * The `$…$` spans on one line that are plausibly mathematics.
     *
     * Returned so a caller can tell "there is maths here" from "there is a dollar
     * sign here" without re-implementing the rule; the span bounds are the
     * delimiters themselves, as KaTeX's parser expects to see them.
     */
    fun inlineSpans(line: String): List<IntRange> {
        val spans = mutableListOf<IntRange>()
        var index = 0
        while (index < line.length) {
            val open = line.indexOf('$', index)
            if (open < 0) break
            // `$$` is display math, handled above; a lone `$` needs a partner.
            if (open + 1 < line.length && line[open + 1] == '$') {
                index = open + 2
                continue
            }
            val close = line.indexOf('$', open + 1)
            if (close < 0) break
            val body = line.substring(open + 1, close)
            val shaped = body.isNotEmpty() &&
                !body.first().isWhitespace() &&
                !body.last().isWhitespace() &&
                // A blank line ends a paragraph, and KaTeX will not span one.
                !body.contains("\n") &&
                MATHEMATICAL.containsMatchIn(body)
            if (shaped) {
                spans += open..close
                index = close + 1
            } else {
                // Not mathematics: keep looking after this `$` rather than
                // skipping the pair, since the real formula may start later.
                index = open + 1
            }
        }
        return spans
    }
}
