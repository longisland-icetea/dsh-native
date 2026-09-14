package io.github.longislandicetea.dshnative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When prose is mathematics.
 *
 * A false positive costs a WebView where a `Text` would have done; a false
 * negative shows the reader raw TeX. `$` is the delimiter that makes this hard,
 * because it is also a currency symbol.
 */
class MathTextTest {
    @Test
    fun display_delimiters_always_mean_mathematics() {
        assertTrue(MathText.hasMath("The result is $$\\int_0^1 x^2 dx$$ exactly."))
        assertTrue(MathText.hasMath("So \\[E = mc^2\\] follows."))
    }

    @Test
    fun inline_mathematics_is_recognised() {
        assertTrue(MathText.hasMath("where \$x^2 + y^2 = 1\$ holds"))
        assertTrue(MathText.hasMath("the term \$\\alpha_i\$ is small"))
        assertTrue(MathText.hasMath("see \\(\\frac{a}{b}\\) for that"))
        assertTrue(MathText.hasMath("at \$n=5\$ nodes"))
    }

    /** Money is not mathematics, and a dollar sign alone is not either. */
    @Test
    fun prices_are_left_alone() {
        assertFalse(MathText.hasMath("It costs \$5 and \$10 in total."))
        assertFalse(MathText.hasMath("budget: \$1,200"))
        assertFalse(MathText.hasMath("pay \$5 now or \$7 later"))
    }

    @Test
    fun a_lone_dollar_is_not_an_opening_delimiter() {
        assertFalse(MathText.hasMath("costs \$5"))
        assertFalse("an unclosed delimiter renders as text in KaTeX too", MathText.hasMath("costs \$5 and x^2"))
    }

    /** The spans are the delimiters, which is what KaTeX's parser expects. */
    @Test
    fun the_spans_are_reported_where_the_delimiters_are() {
        val line = "a \$x^2\$ b"
        val span = MathText.inlineSpans(line).single()
        assertEquals("\$x^2\$", line.substring(span.first, span.last + 1))
    }

    @Test
    fun a_paragraph_with_no_mathematics_is_not_one() {
        assertFalse(MathText.hasMath(listOf("Just words.", "And more words.")))
        assertTrue(MathText.hasMath(listOf("Just words.", "and \$a=b\$ here")))
    }

    /** The document handed to the WebView: escaped text, maths untouched. */
    @Test
    fun the_generated_document_escapes_text_but_not_formulas() {
        val html = mathHtml(listOf("if a < b & \$x^2\$ then"), ProseKind.Paragraph, 0xDDE2EC)
        assertTrue("markup is escaped: $html", html.contains("a &lt; b &amp;"))
        assertTrue("the formula survives for KaTeX", html.contains("\$x^2\$"))
        assertTrue("the bundled assets are referenced", html.contains("katex.min.js"))
        assertTrue("auto-render runs with the app's delimiters", html.contains("renderMathInElement"))
        assertTrue("nothing is fetched from the network", !html.contains("http://") && !html.contains("https://"))
    }

    @Test
    fun a_bullet_keeps_its_marker_in_the_rendered_block() {
        val html = mathHtml(listOf("\$E=mc^2\$ is famous"), ProseKind.Bullet, 0xDDE2EC)
        assertTrue("the marker the Compose renderer would draw is drawn here too", html.contains("• "))
    }
}
