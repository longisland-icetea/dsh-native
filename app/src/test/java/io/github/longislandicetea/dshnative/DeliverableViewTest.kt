package io.github.longislandicetea.dshnative

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which delivered files open as documents, and what a document shows.
 *
 * The rule decides whether a reader sees a report or a wall of `##` and `**`,
 * which is exactly the difference this feature exists to fix, so it is tested
 * against the paths a turn actually delivers rather than against the two
 * extensions that were easiest to think of.
 */
class DeliverableViewTest {
    @Test
    fun `a markdown deliverable opens as a document`() {
        assertEquals(DeliverableView.Document, deliverableViewFor("/home/example/report.md"))
        assertEquals(DeliverableView.Document, deliverableViewFor("notes.markdown"))
        // Case is folded: the machine that wrote it may spell it either way.
        assertEquals(DeliverableView.Document, deliverableViewFor("/tmp/DELIVERABLES.MD"))
        assertEquals(DeliverableView.Document, deliverableViewFor("/tmp/Report.Markdown"))
    }

    @Test
    fun `source files stay source`() {
        assertEquals(DeliverableView.Source, deliverableViewFor("/home/example/DshClient.kt"))
        assertEquals(DeliverableView.Source, deliverableViewFor("/tmp/plot.py"))
        assertEquals(DeliverableView.Source, deliverableViewFor("/tmp/data.json"))
        assertEquals(DeliverableView.Source, deliverableViewFor("/tmp/paper.tex"))
        assertEquals(DeliverableView.Source, deliverableViewFor("/tmp/table.csv"))
    }

    @Test
    fun `an extension that merely starts with md is not markdown`() {
        // `mdx` is JSX-with-Markdown and `md5` is a checksum; both would be
        // rendered as prose by a `startsWith("md")` test.
        assertEquals(DeliverableView.Source, deliverableViewFor("/tmp/page.mdx"))
        assertEquals(DeliverableView.Source, deliverableViewFor("/tmp/checksum.md5"))
    }

    @Test
    fun `a path with no extension has nothing to go on`() {
        assertEquals(DeliverableView.Source, deliverableViewFor("/tmp/README"))
        assertEquals(DeliverableView.Source, deliverableViewFor("/tmp/Makefile"))
        // A dotfile's name is not an extension: `.md` here is the whole name.
        assertEquals(DeliverableView.Source, deliverableViewFor("/home/example/.md"))
    }

    @Test
    fun `a dot in a directory name does not make the file markdown`() {
        assertEquals(DeliverableView.Source, deliverableViewFor("/home/example/v1.2/notes"))
        assertEquals(DeliverableView.Source, deliverableViewFor("/home/example/v1.2/report.txt"))
        assertEquals(DeliverableView.Document, deliverableViewFor("/home/example/v1.2/report.md"))
    }

    @Test
    fun `a windows-separated path is read the same way`() {
        // The Host may be a Windows machine, and the delivered path carries its
        // separators through the protocol untouched.
        assertEquals(DeliverableView.Document, deliverableViewFor("C:\\work\\deliverables\\report.md"))
        assertEquals(DeliverableView.Source, deliverableViewFor("C:\\work\\deliverables\\report.kt"))
    }

    @Test
    fun `a relative path is read the same way as an absolute one`() {
        // `deliverables/presented` carries absolute paths today, but the preview
        // resolves relative ones against the session root and both reach here.
        assertEquals(DeliverableView.Document, deliverableViewFor("docs/report.md"))
        assertEquals(DeliverableView.Source, deliverableViewFor("docs/report.md.bak"))
    }

    @Test
    fun `front matter is not part of the document`() {
        val body = deliverableDocumentText(
            """
            ---
            title: Weekly audit
            author: the agent
            ---
            # Findings

            Nothing burned down.
            """.trimIndent(),
        )
        assertEquals("# Findings\n\nNothing burned down.", body)
    }

    @Test
    fun `front matter closed by three dots is stripped too`() {
        // YAML accepts `...` as the closing delimiter, and models emit it.
        val body = deliverableDocumentText("---\ntitle: x\n...\n# Body")
        assertEquals("# Body", body)
    }

    @Test
    fun `a document that starts with a rule keeps its rule`() {
        // An unclosed `---` is a horizontal rule, which is content. Stripping it
        // would delete a line to satisfy a guess about metadata that is not there.
        val body = deliverableDocumentText("---\n# Findings\n\nText.")
        assertEquals("---\n# Findings\n\nText.", body)
    }

    @Test
    fun `a rule, a blank line, and a later rule do not become front matter`() {
        // The bug a line-based scan has: the first `---` pairs with the second
        // one *anywhere* below it, and the paragraph between them is deleted.
        // Front matter is a contiguous block, so the blank line ends the search.
        val text = "---\n\n# Findings\n\n---\n\nMore text."
        assertEquals(text, deliverableDocumentText(text))
    }

    @Test
    fun `front matter is not allowed to reach past a blank line`() {
        // Same shape, with metadata-looking lines inside the span: whatever this
        // is, it is not a front-matter block, and nothing may be dropped.
        val text = "---\ntitle: x\n\n# Findings\n\n---\n"
        assertEquals(text, deliverableDocumentText(text))
    }

    @Test
    fun `a rule later in the document is left alone`() {
        val text = "# Findings\n\n---\n\nMore text."
        assertEquals(text, deliverableDocumentText(text))
    }

    @Test
    fun `a byte order mark does not defeat the front matter test`() {
        val body = deliverableDocumentText("\uFEFF---\ntitle: x\n---\n# Body")
        assertEquals("# Body", body)
    }

    @Test
    fun `a byte order mark on an ordinary document is dropped`() {
        assertEquals("# Body", deliverableDocumentText("\uFEFF# Body"))
    }

    @Test
    fun `an empty body is unchanged`() {
        assertEquals("", deliverableDocumentText(""))
    }

    @Test
    fun `a document that is only front matter becomes empty`() {
        // Nothing is left to render, and the sheet already says "(empty file)"
        // for exactly that -- better than a lone horizontal rule.
        assertEquals("", deliverableDocumentText("---\ntitle: x\n---\n"))
    }

    @Test
    fun `front matter with indented markers is still front matter`() {
        assertEquals("# Body", deliverableDocumentText("  ---\ntitle: x\n---\n# Body"))
    }

    @Test
    fun `the document renders through the same parser a message does`() {
        // The point of routing a file through `SimpleMarkdown` is that a table in
        // a report is a table, not three lines of pipes.
        val blocks = SimpleMarkdown.parse(deliverableDocumentText("---\ntitle: x\n---\n## Findings\n\n| a | b |\n|---|---|\n| 1 | 2 |\n"))
        val heading = blocks.filterIsInstance<MarkdownBlock.Prose>().first()
        assertEquals(ProseKind.Heading2, heading.kind)
        assertEquals("Findings", heading.lines.single())
        val table = blocks.filterIsInstance<MarkdownBlock.Table>().single()
        assertEquals(listOf("a", "b"), table.header)
        assertEquals(listOf(listOf("1", "2")), table.rows)
    }
}
