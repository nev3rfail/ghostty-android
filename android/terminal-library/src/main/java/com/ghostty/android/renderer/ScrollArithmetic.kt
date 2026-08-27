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
 * The rows available in the direction travel points.
 *
 * [capacity] is the pair the renderer reports: rows above the viewport, then
 * rows below it. Travel towards the active area at the bottom is positive, the
 * same convention [scrollStep] uses.
 *
 * Travel of zero has no direction and finds no room. A capacity that is not a
 * pair finds none either, because a reading that failed must refuse a gesture
 * rather than invite one.
 */
fun rowsAvailable(pixels: Float, capacity: IntArray): Int {
    if (capacity.size != 2) return 0
    return when {
        pixels > 0f -> capacity[1]
        pixels < 0f -> capacity[0]
        else -> 0
    }.coerceAtLeast(0)
}

/**
 * The wheel notches a step asks the program for.
 *
 * A notch is one cell row, so the row delta is the notch count: negative is
 * wheel-up, matching travel towards the older end of the buffer.
 */
fun wheelNotches(step: ScrollStep): Int = step.rows

/**
 * Splits accumulated travel into whole notches and the remainder to carry.
 *
 * The split is towards zero and the remainder keeps its sign, so the first notch
 * waits for a whole row of travel whichever way the finger goes. [scrollStep]
 * rounds down instead, because the viewport draws its remainder as a shift in
 * one direction only; a notch is drawn as nothing, so rounding down there would
 * fire on the first pixel travelled towards the history and wait a full row in
 * the other direction.
 */
fun wheelStep(accumulatedPixels: Float, cellHeight: Float): ScrollStep {
    if (cellHeight <= 0f) return ScrollStep.None
    val rows = (accumulatedPixels / cellHeight).toInt()
    return ScrollStep(rows, accumulatedPixels - rows * cellHeight)
}

/**
 * What a frame's travel does when the program is holding the wheel.
 *
 * Bytes went to the program, so the viewport does not move and the remainder
 * stays: travel smaller than a row is not lost, it accumulates until it is
 * worth a notch. Nothing is drawn as a sub-row shift, because an offset
 * without a viewport movement shears the frame.
 */
fun afterWheel(step: ScrollStep): ScrollStep = ScrollStep(0, step.residual)

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
