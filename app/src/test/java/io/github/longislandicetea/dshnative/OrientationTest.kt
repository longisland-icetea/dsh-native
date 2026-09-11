package io.github.longislandicetea.dshnative

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which screens are held to portrait.
 *
 * A phone's layouts are built for one narrow column and rotating it costs half
 * the vertical space the transcript needs; a tablet's extra width is the point.
 * The boundary is Android's own 600dp of smallest width -- the same line the
 * platform draws between a phone and a tablet -- and smallest width rather than
 * current width so a phone held sideways is still a phone.
 */
class OrientationTest {

    @Test
    fun phones_are_held_to_portrait() {
        // Smallest widths of devices that are phones: a small phone, a typical
        // one, a large one, and a foldable's outer screen.
        assertTrue(locksToPortrait(320))
        assertTrue(locksToPortrait(360))
        assertTrue(locksToPortrait(411))
        assertTrue(locksToPortrait(480))
        assertTrue(locksToPortrait(599))
    }

    @Test
    fun tablets_rotate() {
        assertFalse("7in tablet", locksToPortrait(600))
        assertFalse("10in tablet", locksToPortrait(800))
        assertFalse(locksToPortrait(1024))
    }

    @Test
    fun the_boundary_is_the_platforms_own() {
        // 600 is where sw600dp and SCREENLAYOUT_SIZE_LARGE begin: one below is a
        // phone, and the boundary itself is not.
        assertTrue(locksToPortrait(599))
        assertFalse(locksToPortrait(600))
    }
}
