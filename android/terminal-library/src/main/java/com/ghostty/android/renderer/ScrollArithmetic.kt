package com.ghostty.android.renderer

import kotlin.math.floor

/**
 * The arithmetic behind a scrolling gesture, kept apart from the view that
 * gathers the gesture and the renderer that answers it.
 *
 * Finger travel arrives in pixels and the terminal scrolls in whole rows, so
 * every drag carries a remainder. The remainder is drawn as a sub-row shift and
 * kept for the next event, which is what makes a slow drag move smoothly
 * instead of in row-sized jumps.
 *
 * Positive pixels are travel towards the active area at the bottom.
 */

/** A row delta to apply to the viewport, and the remainder to draw with. */
data class ScrollStep(val rows: Int, val residual: Float) {
    companion object {
        /** Consumes nothing and draws no shift. */
        val None = ScrollStep(0, 0f)
    }
}

/**
 * Splits accumulated travel into whole rows and the remainder inside a row.
 *
 * The split rounds down rather than towards zero, which keeps the remainder in
 * `[0, cellHeight)` for travel in either direction. The renderer draws it as a
 * shift in one direction only, so a signed remainder would move cells the wrong
 * way; the cost is that travel of a single pixel upwards already crosses a row
 * boundary, and the remainder it leaves behind is most of a row.
 */
fun scrollStep(accumulatedPixels: Float, cellHeight: Float): ScrollStep {
    if (cellHeight <= 0f) return ScrollStep.None
    val rows = floor(accumulatedPixels / cellHeight).toInt()
    return ScrollStep(rows, accumulatedPixels - rows * cellHeight)
}

/**
 * What survives of a step the viewport refused.
 *
 * [rowsMoved] is the movement measured either side of applying the delta, which
 * is the only trustworthy account of a limit: a viewport can report an offset of
 * zero for reasons that have nothing to do with having reached the top, and a
 * boundary derived from such a report latches there permanently.
 *
 * A step that moved as asked passes through. One that moved nothing keeps no
 * remainder either, so the accumulator cannot build a delta against a limit it
 * has already reached. A step that moved partway keeps the rows it achieved and
 * drops the remainder, because the remainder belongs to travel that had nowhere
 * to go.
 */
fun afterMovement(step: ScrollStep, rowsMoved: Int): ScrollStep = when (rowsMoved) {
    step.rows -> step
    0 -> ScrollStep.None
    else -> ScrollStep(rowsMoved, 0f)
}
