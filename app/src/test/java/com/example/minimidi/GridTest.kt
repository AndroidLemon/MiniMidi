package com.example.minimidi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GridTest {
    @Test fun layoutSelection() {
        assertEquals(FULL_GRID, layoutFor(folded = false, bigPadsWhenFolded = false))
        assertEquals(FULL_GRID, layoutFor(folded = false, bigPadsWhenFolded = true)) // setting only applies folded
        assertEquals(FULL_GRID, layoutFor(folded = true, bigPadsWhenFolded = false))
        assertEquals(BIG_PADS_GRID, layoutFor(folded = true, bigPadsWhenFolded = true))
        assertEquals(16, FULL_GRID.padCount)
        assertEquals(8, BIG_PADS_GRID.padCount)
    }

    @Test fun padOneIsBottomLeftMpcStyle() {
        assertEquals(0, padIndex(visualRow = 3, column = 0, layout = FULL_GRID))
        assertEquals(3, padIndex(visualRow = 3, column = 3, layout = FULL_GRID))
        assertEquals(12, padIndex(visualRow = 0, column = 0, layout = FULL_GRID))
        assertEquals(15, padIndex(visualRow = 0, column = 3, layout = FULL_GRID))
    }

    @Test fun bigPadsShowPadsOneToEight() {
        assertEquals(0, padIndex(visualRow = 3, column = 0, layout = BIG_PADS_GRID))
        assertEquals(1, padIndex(visualRow = 3, column = 1, layout = BIG_PADS_GRID))
        assertEquals(7, padIndex(visualRow = 0, column = 1, layout = BIG_PADS_GRID))
    }

    @Test fun hitTestFindsCells() {
        // 400×400 px grid, 100 px cells
        assertEquals(0, hitTest(10f, 390f, 400f, 400f, FULL_GRID))    // bottom-left
        assertEquals(15, hitTest(390f, 10f, 400f, 400f, FULL_GRID))   // top-right
        assertEquals(5, hitTest(150f, 250f, 400f, 400f, FULL_GRID))   // row 2 from bottom, col 1
    }

    @Test fun hitTestBigPads() {
        assertEquals(0, hitTest(10f, 790f, 200f, 800f, BIG_PADS_GRID))
        assertEquals(7, hitTest(190f, 10f, 200f, 800f, BIG_PADS_GRID))
    }

    // Review Focus 5
    @Test fun hitOutsideIsNull() {
        assertNull(hitTest(-1f, 10f, 400f, 400f, FULL_GRID))
        assertNull(hitTest(10f, -1f, 400f, 400f, FULL_GRID))
        assertNull(hitTest(400.5f, 10f, 400f, 400f, FULL_GRID))
        assertNull(hitTest(10f, 400.5f, 400f, 400f, FULL_GRID))
    }

    // Digitizers can report exactly x == width / y == height on the last pixel; that's still the edge pad.
    @Test fun hitOnFarEdgeClampsToEdgePad() {
        assertEquals(15, hitTest(400f, 0f, 400f, 400f, FULL_GRID))
        assertEquals(0, hitTest(0f, 400f, 400f, 400f, FULL_GRID))
    }

    @Test fun zeroSizeGridHitsNothing() = assertNull(hitTest(0f, 0f, 0f, 0f, FULL_GRID))

    @Test fun colourGroupsFollowFullKitRows() {
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3), (0 until 16).map(::colourGroup))
    }
}
