package com.example.minimidi

data class GridLayout(val columns: Int, val rows: Int) {
    val padCount get() = columns * rows
}

val FULL_GRID = GridLayout(columns = 4, rows = 4)
val BIG_PADS_GRID = GridLayout(columns = 2, rows = 4)

fun layoutFor(folded: Boolean, bigPadsWhenFolded: Boolean) =
    if (folded && bigPadsWhenFolded) BIG_PADS_GRID else FULL_GRID

/** 0-based pad index for a cell; visual row 0 is the top. Pad 0 sits bottom-left, MPC style. */
fun padIndex(visualRow: Int, column: Int, layout: GridLayout) =
    (layout.rows - 1 - visualRow) * layout.columns + column

/** Pad under (x, y) in a [width]×[height] px grid of equal cells, or null outside it. Gutters belong to their cell. */
fun hitTest(x: Float, y: Float, width: Float, height: Float, layout: GridLayout): Int? {
    if (width <= 0f || height <= 0f || x < 0f || y < 0f || x > width || y > height) return null
    // Clamp: x == width (last pixel) belongs to the last column, not outside.
    val column = (x / width * layout.columns).toInt().coerceAtMost(layout.columns - 1)
    val visualRow = (y / height * layout.rows).toInt().coerceAtMost(layout.rows - 1)
    return padIndex(visualRow, column, layout)
}

/** Colour group 0..3 (red, orange, yellow, cream): the pad's row in the full 4×4 kit, so colours don't shift in big-pads mode. */
fun colourGroup(padIndex: Int) = padIndex / 4
