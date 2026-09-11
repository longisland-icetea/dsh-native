package io.github.longislandicetea.dshnative

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Table cell splitting and row normalisation.
 *
 * These exist because a census of 56 real assistant messages found 13% of their
 * tables parsing into ragged rows -- 6 columns of header against 8 columns of
 * body, 3 against 5. A row with extra cells lays its content out shifted by one
 * column, which reads as "the columns are not aligned" rather than as a parsing
 * bug, and equal-width layout cannot fix it.
 *
 * The shapes below are copied from those messages rather than invented.
 */
class TableParseTest {
    @Test
    fun `an escaped pipe stays inside its cell`() {
        // LaTeX absolute values arrive escaped: `\left\| x \right\|`.
        val cells = SimpleMarkdown.splitRow("""| max\, \Delta E\, （拟合） | 0.82 | 2.75 |""")
        assertEquals(listOf("""max\, \Delta E\, （拟合）""", "0.82", "2.75"), cells)
    }

    @Test
    fun `the real ragged row now has the header's column count`() {
        // Verbatim from a session: this row used to split into 8 cells against a
        // 6-cell header.
        val header = SimpleMarkdown.splitRow("| | 带1 | 带2 | 带3 | Σ | RMS |")
        val row = SimpleMarkdown.splitRow("""| max\, \Delta E\, （拟合） | 0.82 | 0.73 | 2.75 | **4.299** meV | 0.42 / 0.47 / 1.21 meV |""")
        assertEquals(6, header.size)
        assertEquals(6, row.size)
    }

    @Test
    fun `a pipe inside inline code is not a separator`() {
        val cells = SimpleMarkdown.splitRow("| `a | b` | second |")
        assertEquals(listOf("`a | b`", "second"), cells)
    }

    @Test
    fun `an escaped pipe is restored as a literal`() {
        assertEquals(listOf("a|b", "c"), SimpleMarkdown.splitRow("""| a\|b | c |"""))
    }

    @Test
    fun `a leading and trailing pipe do not create empty cells`() {
        assertEquals(listOf("a", "b"), SimpleMarkdown.splitRow("| a | b |"))
        assertEquals(listOf("a", "b"), SimpleMarkdown.splitRow("a | b"))
    }

    @Test
    fun `cells are trimmed`() {
        assertEquals(listOf("a", "b"), SimpleMarkdown.splitRow("|   a   |   b   |"))
    }

    @Test
    fun `a short row is padded to the header width`() {
        // Common and harmless: a cell left empty is still a column.
        val (header, rows) = SimpleMarkdown.normalizeRows(
            listOf("a", "b", "c"),
            listOf(listOf("1", "2")),
        )
        assertEquals(3, header.size)
        assertEquals(listOf("1", "2", ""), rows.single())
    }

    @Test
    fun `a long row is trimmed to the header width`() {
        // A parsing artefact: trimming keeps every row's content in its own column
        // instead of shifting the row sideways.
        val (_, rows) = SimpleMarkdown.normalizeRows(
            listOf("a", "b"),
            listOf(listOf("1", "2", "3", "4")),
        )
        assertEquals(listOf("1", "2"), rows.single())
    }

    @Test
    fun `a table with an empty first header still counts its columns`() {
        // The header may legitimately start with an empty cell, which is why the
        // column count comes from the header and not from the longest row.
        val (header, rows) = SimpleMarkdown.normalizeRows(
            listOf("", "带1", "带2"),
            listOf(listOf("x", "y", "z")),
        )
        assertEquals(3, header.size)
        assertEquals(3, rows.single().size)
    }

    @Test
    fun `every row of a parsed table has the same width`() {
        val blocks = SimpleMarkdown.parse(
            """
            | 空穴谷顶带 | 旧（圆盘） | 新（六边形） |
            |---|---|---|
            | 纹波 std | 0.4163 meV | **0.0509 meV** |
            | max\, \Delta E\, | 0.7786 meV | 0.1009 meV |
            | 自相关周期 | √3·h | 无周期性 |
            """.trimIndent(),
        )
        val table = blocks.single() as MarkdownBlock.Table
        val widths = (listOf(table.header) + table.rows).map { it.size }.distinct()
        assertEquals("one column count for the whole table: $widths", 1, widths.size)
        assertEquals(3, widths.single())
    }
}
