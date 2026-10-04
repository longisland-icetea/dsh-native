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
            is MarkdownBlock.Image -> null
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

    // ── delivered files ───────────────────────────────────────────────────────

    /**
     * The shape the harness asks for and the reply below actually used: a
     * figure alone on its line, then the documents as links in a bullet list.
     */
    private val DELIVERY = """
        图集做好了。

        ## 五张图

        **fig1 电子结构** — 能带 K–Γ–M

        ![fig1](analysis_out/figures/fig1_electronic_structure.png)

        读法：带 9 在 Γ 处低于 E_F。

        ![fig2](<analysis_out/figures/fig2 phonon dispersion.png>)

        ## 交付物

        - 图片：[analysis_out/figures/](analysis_out/figures/) — 5 张 PNG
        - 分析文档：[AAp_FIGURES_ANALYSIS.md](AAp_FIGURES_ANALYSIS.md) — 逐图读法
        - 脚本：[make_figures.py](scripts/elias_py/make_figures.py#L12-L40)
    """.trimIndent()

    @Test
    fun `a figure alone on its line is a block, and carries its path unwrapped`() {
        val figures = SimpleMarkdown.parse(DELIVERY).filterIsInstance<MarkdownBlock.Image>()
        assertEquals(2, figures.size)
        assertEquals("fig1", figures[0].alt)
        assertEquals("analysis_out/figures/fig1_electronic_structure.png", figures[0].destination)
        // Bracketed, because the path holds a space.
        assertEquals("fig2", figures[1].alt)
        assertEquals("analysis_out/figures/fig2 phonon dispersion.png", figures[1].destination)
    }

    @Test
    fun `the prose around a figure is not eaten by it`() {
        val blocks = SimpleMarkdown.parse(DELIVERY)
        val prose = blocks.filterIsInstance<MarkdownBlock.Prose>().map { it.lines.joinToString(" ") }
        assertTrue(prose.any { it.contains("能带 K–Γ–M") })
        assertTrue(prose.any { it.contains("读法") })
        assertTrue(prose.any { it.contains("交付物") })
    }

    @Test
    fun `a bulleted image is not a figure block`() {
        // A picture sharing its line with words has no layout here; it stays the
        // bullet it was written as, and its reference is still openable.
        val blocks = SimpleMarkdown.parse("- ![fig1](out/fig1.png) 见图")
        assertEquals(1, blocks.size)
        assertEquals(ProseKind.Bullet, (blocks[0] as MarkdownBlock.Prose).kind)
    }

    @Test
    fun `trailing spaces after a figure are not part of its path`() {
        val figure = SimpleMarkdown.parse("![fig1](out/fig1.png)   ").filterIsInstance<MarkdownBlock.Image>().single()
        assertEquals("out/fig1.png", figure.destination)
    }

    @Test
    fun `an image with no destination stays prose`() {
        val blocks = SimpleMarkdown.parse("![]()")
        assertTrue(blocks.single() is MarkdownBlock.Prose)
    }

    @Test
    fun `an image reference never prints its bang`() {
        // `![fig1](x.png)` inside a sentence is drawn as its reference, and a
        // reader shown `!fig1 (x.png)` reads a broken line where it is a good one.
        val text = SimpleMarkdown.inline("见 ![fig1](out/fig1.png) 所示", ACCENT_TEST, CODE_TEST).text
        assertEquals("见 fig1 (out/fig1.png) 所示", text)
    }

    @Test
    fun `a file link is annotated so a tap can open it`() {
        val opened = mutableListOf<String>()
        val text = SimpleMarkdown.inline(
            "见 [AAp_FINAL_RESULT.md](AAp_FINAL_RESULT.md)",
            ACCENT_TEST,
            CODE_TEST,
            onOpenLink = { opened += it },
        )
        assertEquals("见 AAp_FINAL_RESULT.md (AAp_FINAL_RESULT.md)", text.text)
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(1, links.size)
        // The destination the handler is given is what the reply wrote, anchor
        // and all: resolving it is the caller's business.
        val listener = (links.single().item as androidx.compose.ui.text.LinkAnnotation.Url).linkInteractionListener
        listener!!.onClick(links.single().item)
        assertEquals(listOf("AAp_FINAL_RESULT.md"), opened)
    }

    @Test
    fun `a link whose destination is nothing is not tappable`() {
        // A fragment or an empty destination would open a read that cannot
        // succeed; a coloured word that does nothing is the honest drawing.
        val text = SimpleMarkdown.inline("见 [这里](#L24)", ACCENT_TEST, CODE_TEST, onOpenLink = { })
        assertEquals(0, text.getLinkAnnotations(0, text.length).size)
    }

    @Test
    fun `with no handler every link is inert`() {
        val text = SimpleMarkdown.inline("[report](out/report.md)", ACCENT_TEST, CODE_TEST)
        assertEquals(0, text.getLinkAnnotations(0, text.length).size)
        assertEquals("report (out/report.md)", text.text)
    }

    @Test
    fun `a bracketed destination may hold parentheses and spaces`() {
        val line = "![a](<figures/fig (1).png>)"
        val reference = SimpleMarkdown.referenceAt(line, 0)
        // The destination is returned as written, brackets included: unwrapping
        // them is `inline`'s job, because that is where the path is classified.
        assertEquals("<figures/fig (1).png>", reference!!.destination)
        assertEquals(line.length, reference.end)
    }

    @Test
    fun `a figure inside a previewed document is a block too`() {
        // The document path parses with the same code, and a report's own
        // `![](figures/x.png)` is exactly this shape.
        val blocks = SimpleMarkdown.parse("| a | b |\n|---|---|\n| 1 | 2 |\n\n![](figures/fig3.png)\n")
        assertTrue(blocks.any { it is MarkdownBlock.Table })
        assertEquals("figures/fig3.png", blocks.filterIsInstance<MarkdownBlock.Image>().single().destination)
    }

    private companion object {
        val ACCENT_TEST = androidx.compose.ui.graphics.Color(0xFF82AAFF)
        val CODE_TEST = androidx.compose.ui.graphics.Color(0xFF8FD6FF)
    }
}
