package io.github.longislandicetea.dshnative

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The arithmetic behind pinch-zoom and pan.
 *
 * A multi-touch gesture cannot be injected from a shell, so the gesture wiring is
 * verified by hand on a device while the bounds it depends on are verified here.
 * That split matters: the failure modes that ruin a zoom viewer are arithmetic --
 * a scale that runs away, or a figure dragged out of view with no way back -- and
 * both are decidable without any fingers.
 */
class ImageZoomTest {
    @Test
    fun `scale never drops below fit`() {
        // A pinch inward past the fit size would leave the figure floating in
        // empty space with no gesture that obviously undoes it.
        assertEquals(1f, clampScale(0.4f))
        assertEquals(1f, clampScale(1f))
        assertEquals(1f, clampScale(-3f))
    }

    @Test
    fun `scale never runs away`() {
        assertEquals(8f, clampScale(8f))
        assertEquals(8f, clampScale(40f))
        assertEquals(2f, clampScale(2f))
    }

    @Test
    fun `a zoom step is applied to the current scale`() {
        // transformable reports a cumulative zoom factor for the gesture; folding
        // it in by multiplication is what makes a slow pinch track the fingers.
        assertEquals(2f, clampScale(1f * 2f))
        assertEquals(3f, clampScale(1.5f * 2f))
        // ...and the clamp still applies to the product, not the factor.
        assertEquals(8f, clampScale(6f * 2f))
    }

    @Test
    fun `pan is clamped to the scaled bounds`() {
        val clamped = clampOffset(Offset(500f, -500f), scale = 2f, width = 400, height = 200)
        assertEquals(200f, clamped.x)
        assertEquals(-100f, clamped.y)
    }

    @Test
    fun `at fit scale there is nowhere to pan`() {
        // The image exactly fits, so any translation would only expose background.
        assertEquals(Offset.Zero, clampOffset(Offset(120f, -80f), scale = 1f, width = 400, height = 200))
    }

    @Test
    fun `a pan inside the bounds is left alone`() {
        val inside = Offset(50f, -40f)
        assertEquals(inside, clampOffset(inside, scale = 2f, width = 400, height = 200))
    }

    @Test
    fun `an unmeasured viewport yields no offset`() {
        // onSizeChanged has not run yet on the first composition; dividing by a
        // zero size would produce NaN and blank the image.
        assertEquals(Offset.Zero, clampOffset(Offset(10f, 10f), scale = 3f, width = 0, height = 0))
    }

    @Test
    fun `the double tap target is inside the allowed range`() {
        assertEquals(IMAGE_DOUBLE_TAP_SCALE, clampScale(IMAGE_DOUBLE_TAP_SCALE))
        assertEquals(true, IMAGE_DOUBLE_TAP_SCALE > IMAGE_MIN_SCALE)
        assertEquals(true, IMAGE_DOUBLE_TAP_SCALE < IMAGE_MAX_SCALE)
    }
}
