package io.github.longislandicetea.dshnative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Column sizing for markdown tables.
 *
 * The rule is CSS 2.1 automatic table layout aiming at equal columns: share the
 * width equally, pin a column that cannot shrink that far, share the rest again.
 * These are pure functions with the font measurements supplied by the caller,
 * because the first two attempts at this rendered an empty frame and then an
 * 83%/8%/8% table on a device, and a device check cannot say which part of a
 * layout is wrong.
 */
class TableLayoutTest {
    private fun layout(columns: Int, available: Float = 1000f, padding: Float = 0f, max: Float = 10_000f) =
        equalColumnWidths(columns, available, padding, max)

    @Test
    fun `columns are equal`() {
        val (widths, _) = layout(3)
        assertEquals(3, widths.size)
        assertEquals(widths[0], widths[1], 0.01f)
        assertEquals(widths[1], widths[2], 0.01f)
    }

    @Test
    fun `the grid never exceeds the viewport`() {
        // The regression: a grid laid out wider than the screen showed an empty
        // frame until the reader scrolled, which reads as "the table is broken".
        for (columns in 1..8) {
            val (widths, grid) = layout(columns, available = 700f, padding = 24f)
            assertTrue("$columns columns stay inside the viewport: $grid", grid <= 700f + 0.5f)
            assertTrue("$columns columns are all positive: $widths", widths.all { it > 0f })
        }
    }

    @Test
    fun `the grid fills the viewport`() {
        // Equal shares are the whole width, not a guess at it: this is what stopped
        // a two-column table from hugging the left edge or overflowing the right.
        val (_, grid) = layout(2, available = 1000f, padding = 8f)
        assertEquals(1000f, grid, 0.5f)
    }

    @Test
    fun `cell padding is part of each column`() {
        // Padding is not negotiable, so the text budget is the viewport minus all of
        // it -- otherwise the padded grid is wider than the viewport by construction.
        val (widths, grid) = layout(2, available = 1000f, padding = 50f)
        assertEquals(1000f, grid, 0.5f)
        assertEquals(450f, widths[0], 0.5f)
    }

    @Test
    fun `no column is ever zero`() {
        // A zero width is an invisible column, which is the bug that started this.
        val (widths, _) = layout(8, available = 200f)
        assertTrue("every column is visible: $widths", widths.all { it > 0f })
    }

    @Test
    fun `a single column fills the table`() {
        val (widths, grid) = layout(1, available = 600f, padding = 20f)
        assertEquals(1, widths.size)
        assertEquals(600f, grid, 0.5f)
    }

    @Test
    fun `the readability cap only shrinks columns`() {
        // A cap meant to keep a line length readable must never make the grid wider
        // than the viewport: a narrow viewport wins.
        val (wide, wideGrid) = layout(2, available = 4000f, padding = 0f, max = 320f)
        assertTrue("capped at the readability limit: $wide", wide.all { it <= 320f })
        assertTrue("grid follows the cap: $wideGrid", wideGrid <= 640f + 0.5f)

        val (narrow, narrowGrid) = layout(2, available = 300f, padding = 0f, max = 320f)
        assertEquals("a narrow viewport still fits: $narrowGrid", 300f, narrowGrid, 0.5f)
        assertTrue("columns shrink to fit: $narrow", narrow.all { it <= 150f + 0.5f })
    }

    @Test
    fun `an unmeasured viewport yields no columns`() {
        // Before the first layout there is no width to divide; producing widths here
        // would be inventing a viewport.
        val (widths, grid) = layout(0, available = 0f)
        assertEquals(0, widths.size)
        assertEquals(0f, grid, 0.01f)
    }

    // ── min-content width ─────────────────────────────────────────────────────

    private val oneCharPerUnit: (String) -> Float = { it.length.toFloat() }

    @Test
    fun `min-content width is the longest word`() {
        // "Parameters.jl" is the longest token at 13 characters.
        assertEquals(13f, minContentWidth("Parameters.jl and more", oneCharPerUnit), 0.01f)
    }

    @Test
    fun `cjk breaks between characters`() {
        // A run of Han characters is not one token: treating it as one would lock a
        // Chinese column at the width of its longest phrase and force scrolling.
        assertEquals(1f, minContentWidth("中文标题", oneCharPerUnit), 0.01f)
    }

    @Test
    fun `cjk punctuation breaks too`() {
        assertEquals(1f, minContentWidth("（中文）", oneCharPerUnit), 0.01f)
    }

    @Test
    fun `empty text needs no width`() {
        assertEquals(0f, minContentWidth("", oneCharPerUnit), 0.01f)
        assertEquals(0f, minContentWidth("   ", oneCharPerUnit), 0.01f)
    }

    @Test
    fun `a word boundary resets the token`() {
        assertEquals(4f, minContentWidth("ab cd efgh i", oneCharPerUnit), 0.01f)
    }
}
