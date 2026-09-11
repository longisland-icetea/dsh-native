package io.github.longislandicetea.dshnative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Column weighting for markdown tables.
 *
 * These exist because the first proportional layout rendered an empty frame on a
 * device: inside `horizontalScroll` the width is infinite, `Row` skips weighing
 * under infinite constraints, and every column measured zero. The fix moved the
 * sizing decision here, where it can be asserted, and gave the grid a bounded
 * width. A test that only checked "does a table appear" would pass on a
 * two-column table and miss a collapsed one, so the assertions are about shares.
 */
class TableLayoutTest {
    private fun table(header: List<String>, vararg rows: List<String>) =
        MarkdownBlock.Table(header, rows.toList())

    @Test
    fun `weights are shares that sum to one`() {
        val weights = columnWeights(table(listOf("文件", "改动"), listOf("Parameters.jl", "新增 Q0_CELL")), 2)
        assertEquals(2, weights.size)
        // The stub's assertEquals has no float overload; compare within a tolerance.
        assertTrue("shares sum to one: ${weights.sum()}", kotlin.math.abs(weights.sum() - 1f) < 1e-5f)
    }

    @Test
    fun `no column is ever zero`() {
        // The empty-frame bug: a zero weight means an invisible column.
        val weights = columnWeights(
            table(listOf("a", "a very long header that dominates the row"), listOf("x", "y")),
            2,
        )
        assertTrue("every column keeps a share: $weights", weights.all { it > 0f })
    }

    @Test
    fun `a wide column gets more room than a narrow one`() {
        val weights = columnWeights(
            table(listOf("file", "description"), listOf("Parameters.jl", "a much longer description of the change")),
            2,
        )
        assertTrue("wide column outweighs the narrow one: $weights", weights[1] > weights[0])
    }

    @Test
    fun `one chatty column cannot flatten the others`() {
        // The ceiling: the longest cell is capped, so the other columns keep a
        // readable share instead of being squeezed to a sliver.
        val weights = columnWeights(
            table(listOf("a", "b", "c"), listOf("1", "2", "x".repeat(4000))),
            3,
        )
        assertTrue("capped column stays at the ceiling: $weights", weights[2] <= 0.71f)
        assertTrue("others keep a usable share: $weights", weights[0] >= 0.15f)
    }

    @Test
    fun `every column keeps a floor`() {
        // The floor: an empty column still gets enough width to be a column.
        val weights = columnWeights(
            table(listOf("", ""), listOf("", "x".repeat(200))),
            2,
        )
        assertTrue("empty column keeps the floor: $weights", weights[0] >= 0.15f)
    }

    @Test
    fun `a short row does not shift the columns`() {
        // Ragged rows happen; the weights come from the widest cell per column, so
        // a row with a missing cell cannot change the layout.
        val full = table(listOf("a", "bb"), listOf("cc", "dd"))
        val ragged = table(listOf("a", "bb"), listOf("cc"))
        assertEquals(columnWeights(full, 2), columnWeights(ragged, 2))
    }

    @Test
    fun `a header longer than every cell still sizes its column`() {
        val weights = columnWeights(table(listOf("a long header", "x"), listOf("a", "b")), 2)
        assertTrue("header length counts: $weights", weights[0] > weights[1])
    }
}
