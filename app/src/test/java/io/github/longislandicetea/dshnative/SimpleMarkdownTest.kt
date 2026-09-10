package io.github.longislandicetea.dshnative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown parsing for the constructs that actually appear in assistant output.
 *
 * The cases come from reading transcripts rather than from the Markdown spec:
 * fenced code, tables, nested bullets, ordered steps, and headings are what the
 * models emit, and the value of this parser is that it does not corrupt any of
 * them. A construct it does not know must survive as its literal text.
 */
class SimpleMarkdownTest {
    private fun kinds(blocks: List<MarkdownBlock>) = blocks.map {
        when (it) {
            is MarkdownBlock.Prose -> it.kind
            is MarkdownBlock.Code -> null
            is MarkdownBlock.Table -> null
            MarkdownBlock.Rule -> null
        }
    }

    @Test
    fun `a paragraph stays one block`() {
        val blocks = SimpleMarkdown.parse("first line\nsecond line\n\nsecond paragraph")
        assertEquals(2, blocks.size)
        assertEquals(listOf("first line", "second line"), (blocks[0] as MarkdownBlock.Prose).lines)
        assertEquals(ProseKind.Paragraph, (blocks[0] as MarkdownBlock.Prose).kind)
    }

    @Test
    fun `headings keep their level and lose their hashes`() {
        val blocks = SimpleMarkdown.parse("# One\n\n## Two\n\n### Three")
        assertEquals(
            listOf(ProseKind.Heading1, ProseKind.Heading2, ProseKind.Heading3),
            kinds(blocks),
        )
        assertEquals(listOf("One"), (blocks[0] as MarkdownBlock.Prose).lines)
        assertEquals(listOf("Two"), (blocks[1] as MarkdownBlock.Prose).lines)
    }

    @Test
    fun `bullets group into one block and keep their text`() {
        val blocks = SimpleMarkdown.parse("- alpha\n- beta\n  - nested")
        assertEquals(1, blocks.size)
        val prose = blocks[0] as MarkdownBlock.Prose
        assertEquals(ProseKind.Bullet, prose.kind)
        assertEquals(listOf("alpha", "beta", "nested"), prose.lines)
    }

    @Test
    fun `ordered items keep their number`() {
        val blocks = SimpleMarkdown.parse("1. first\n2. second")
        assertEquals(ProseKind.Ordered, (blocks[0] as MarkdownBlock.Prose).kind)
        assertEquals(listOf("1. first", "2. second"), (blocks[0] as MarkdownBlock.Prose).lines)
    }

    @Test
    fun `a fenced block is code with its language`() {
        val blocks = SimpleMarkdown.parse("text\n\n```kotlin\nval x = 1\n```\n\nafter")
        assertEquals(3, blocks.size)
        val code = blocks[1] as MarkdownBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.code)
    }

    @Test
    fun `an unterminated fence is still code while streaming`() {
        val blocks = SimpleMarkdown.parse("here:\n\n```sh\napt-get install")
        assertTrue(blocks.last() is MarkdownBlock.Code)
        assertEquals("apt-get install", (blocks.last() as MarkdownBlock.Code).code)
    }

    @Test
    fun `a pipe table becomes a table and not prose`() {
        val blocks = SimpleMarkdown.parse(
            """
            | name | value |
            |------|-------|
            | a    | 1     |
            | b    | 2     |
            """.trimIndent(),
        )
        val table = blocks.single() as MarkdownBlock.Table
        assertEquals(listOf("name", "value"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("a", "1"), table.rows[0])
        assertEquals(listOf("b", "2"), table.rows[1])
    }

    @Test
    fun `pipes without a divider stay literal`() {
        // A line with pipes is not a table unless the next line is a divider;
        // treating it as one would swallow the following prose.
        val blocks = SimpleMarkdown.parse("a | b\nplain text")
        assertTrue(blocks.all { it is MarkdownBlock.Prose })
        assertEquals("a | b\nplain text", (blocks[0] as MarkdownBlock.Prose).lines.joinToString("\n"))
    }

    @Test
    fun `quotes group and drop their marker`() {
        val blocks = SimpleMarkdown.parse("> one\n> two")
        val prose = blocks[0] as MarkdownBlock.Prose
        assertEquals(ProseKind.Quote, prose.kind)
        assertEquals(listOf("one", "two"), prose.lines)
    }

    @Test
    fun `a rule is its own block`() {
        val blocks = SimpleMarkdown.parse("above\n\n---\n\nbelow")
        assertEquals(3, blocks.size)
        assertEquals(MarkdownBlock.Rule, blocks[1])
    }

    @Test
    fun `a status line with dashes is not a rule`() {
        // "--- " prefixed prose appears in model summaries; only a line of three
        // or more dashes with nothing else is a rule.
        val blocks = SimpleMarkdown.parse("--- pending items follow")
        assertTrue(blocks[0] is MarkdownBlock.Prose)
    }

    @Test
    fun `inline code loses its backticks`() {
        val text = SimpleMarkdown.inline("run `aapt2 link` now", ACCENT_TEST, CODE_TEST).text
        assertEquals("run aapt2 link now", text)
    }

    @Test
    fun `bold and strike markers are consumed`() {
        val text = SimpleMarkdown.inline("**bold** and ~~gone~~", ACCENT_TEST, CODE_TEST).text
        assertEquals("bold and gone", text)
    }

    @Test
    fun `an unmatched marker is kept literally`() {
        // Streaming output contains half-typed markers; dropping the character
        // would make the text flicker as it arrives.
        val text = SimpleMarkdown.inline("2 * 3 = 6 and a ** b", ACCENT_TEST, CODE_TEST).text
        assertEquals("2 * 3 = 6 and a ** b", text)
    }

    @Test
    fun `a markdown link keeps its label and shows its target`() {
        val text = SimpleMarkdown.inline("see [the docs](https://example.com/x)", ACCENT_TEST, CODE_TEST).text
        assertEquals("see the docs (https://example.com/x)", text)
    }

    @Test
    fun `a bare url is kept whole`() {
        val text = SimpleMarkdown.inline("at https://example.com/a/b?c=1 now", ACCENT_TEST, CODE_TEST).text
        assertEquals("at https://example.com/a/b?c=1 now", text)
    }

    @Test
    fun `chinese punctuation ends a bare url`() {
        // Assistant output mixes languages; a URL followed by a full-width period
        // must not swallow it.
        val text = SimpleMarkdown.inline("见 https://example.com/x。", ACCENT_TEST, CODE_TEST).text
        assertEquals("见 https://example.com/x。", text)
    }

    private companion object {
        val ACCENT_TEST = androidx.compose.ui.graphics.Color(0xFF82AAFF)
        val CODE_TEST = androidx.compose.ui.graphics.Color(0xFF8FD6FF)
    }
}
