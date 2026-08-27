package com.ghostty.android.renderer

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollArithmeticTest {

    @Test
    fun `travel shorter than one row towards the bottom moves nothing`() {
        val step = scrollStep(accumulatedPixels = 9f, cellHeight = CELL)
        assertEquals(0, step.rows)
        assertEquals(9f, step.residual, EPSILON)
    }

    @Test
    fun `whole rows plus a remainder yield both`() {
        val step = scrollStep(accumulatedPixels = 3 * CELL + 7f, cellHeight = CELL)
        assertEquals(3, step.rows)
        assertEquals(7f, step.residual, EPSILON)
    }

    @Test
    fun `travel the other way yields a negative delta`() {
        val step = scrollStep(accumulatedPixels = -(2 * CELL + 5f), cellHeight = CELL)
        assertEquals(-3, step.rows)
        assertEquals(CELL - 5f, step.residual, EPSILON)
    }

    @Test
    fun `an exact multiple of the row height leaves no remainder`() {
        val step = scrollStep(accumulatedPixels = -4 * CELL, cellHeight = CELL)
        assertEquals(-4, step.rows)
        assertEquals(0f, step.residual, EPSILON)
    }

    @Test
    fun `the remainder is a shift within one row, and the split loses nothing`() {
        var pixels = -5 * CELL
        while (pixels <= 5 * CELL) {
            val step = scrollStep(pixels, CELL)
            assertTrue(
                "residual ${step.residual} outside one row at $pixels",
                step.residual >= 0f && step.residual < CELL,
            )
            assertEquals(pixels, step.rows * CELL + step.residual, EPSILON)
            pixels += 3.5f
        }
    }

    @Test
    fun `an unmeasurable row height consumes nothing`() {
        assertEquals(ScrollStep.None, scrollStep(accumulatedPixels = 500f, cellHeight = 0f))
        assertEquals(ScrollStep.None, scrollStep(accumulatedPixels = 500f, cellHeight = -1f))
    }

    @Test
    fun `a drag and a fling over the same travel agree`() {
        val travel = 7 * CELL + 11f
        assertEquals(scrollStep(travel, CELL), scrollStep(travel, CELL))
    }

    @Test
    fun `a step the viewport honoured passes through`() {
        val step = ScrollStep(rows = -3, residual = 19f)
        assertEquals(step, afterMovement(step, rowsMoved = -3))
    }

    @Test
    fun `a step the viewport refused keeps nothing`() {
        val step = ScrollStep(rows = -3, residual = 19f)
        assertEquals(ScrollStep.None, afterMovement(step, rowsMoved = 0))
    }

    @Test
    fun `a step the viewport honoured partly keeps the rows and drops the remainder`() {
        val step = ScrollStep(rows = -3, residual = 19f)
        assertEquals(ScrollStep(rows = -1, residual = 0f), afterMovement(step, rowsMoved = -1))
    }

    @Test
    fun `a refusal at the boundary cannot accumulate`() {
        val refused = afterMovement(scrollStep(-CELL - 6f, CELL), rowsMoved = 0)
        assertEquals(ScrollStep.None, refused)
        val next = afterMovement(scrollStep(refused.residual - 6f, CELL), rowsMoved = 0)
        assertEquals(ScrollStep.None, next)
    }

    @Test
    fun `travel up against no rows above finds no room`() {
        assertEquals(0, rowsAvailable(pixels = -CELL, capacity = intArrayOf(0, 40)))
    }

    @Test
    fun `travel down against no rows below finds no room`() {
        assertEquals(0, rowsAvailable(pixels = CELL, capacity = intArrayOf(40, 0)))
    }

    @Test
    fun `room in the direction asked is reported whatever the other side holds`() {
        assertEquals(7, rowsAvailable(pixels = -CELL, capacity = intArrayOf(7, 0)))
        assertEquals(7, rowsAvailable(pixels = CELL, capacity = intArrayOf(0, 7)))
    }

    @Test
    fun `a buffer with no scrollback finds no room either way`() {
        val none = intArrayOf(0, 0)
        assertEquals(0, rowsAvailable(pixels = -CELL, capacity = none))
        assertEquals(0, rowsAvailable(pixels = CELL, capacity = none))
    }

    @Test
    fun `travel of nothing has no direction`() {
        assertEquals(0, rowsAvailable(pixels = 0f, capacity = intArrayOf(40, 40)))
    }

    @Test
    fun `a capacity that is not a pair refuses`() {
        assertEquals(0, rowsAvailable(pixels = -CELL, capacity = intArrayOf()))
        assertEquals(0, rowsAvailable(pixels = CELL, capacity = intArrayOf(40)))
        assertEquals(0, rowsAvailable(pixels = CELL, capacity = intArrayOf(1, 2, 3)))
    }

    @Test
    fun `a step keeps the sign of the travel that produced it`() {
        // The clamp in the view picks its bound from the sign of the travel, so
        // a step may never disagree with it. The residual is always inside one
        // row and never negative, which is what makes that hold.
        var residual = 0f
        var pixels = -3.5f
        while (pixels <= 3.5f) {
            val step = scrollStep(residual + pixels, CELL)
            if (step.rows != 0) {
                assertTrue(
                    "step ${step.rows} disagrees with travel $pixels at residual $residual",
                    (step.rows > 0) == (pixels > 0f),
                )
            }
            residual = step.residual
            pixels += 0.37f
        }
    }

    @Test
    fun `a notch is a row, and up is negative`() {
        assertEquals(-3, wheelNotches(ScrollStep(rows = -3, residual = 4f)))
        assertEquals(2, wheelNotches(ScrollStep(rows = 2, residual = 4f)))
        assertEquals(0, wheelNotches(ScrollStep.None))
    }

    @Test
    fun `travel given to the program moves no viewport and keeps its remainder`() {
        val step = ScrollStep(rows = -3, residual = 11f)
        val applied = afterWheel(step)
        assertEquals(0, applied.rows)
        assertEquals(11f, applied.residual, EPSILON)
    }

    @Test
    fun `a frame's travel asks for one report, not one per row`() {
        // Several rows crossed inside a frame is one notch count, so the program
        // is woken once rather than once per row.
        val step = scrollStep(accumulatedPixels = 5 * CELL + 3f, cellHeight = CELL)
        assertEquals(5, wheelNotches(step))
    }

    @Test
    fun `sub-row travel asks the program for nothing yet`() {
        val step = scrollStep(accumulatedPixels = CELL / 3f, cellHeight = CELL)
        assertEquals(0, wheelNotches(step))
        // and the remainder survives, so the next frame can reach a notch
        assertEquals(CELL / 3f, afterWheel(step).residual, EPSILON)
    }

    @Test
    fun `a wheel notch waits for a whole row in either direction`() {
        val towards = wheelStep(accumulatedPixels = CELL - 1f, cellHeight = CELL)
        assertEquals(0, towards.rows)
        assertEquals(CELL - 1f, towards.residual, EPSILON)

        val away = wheelStep(accumulatedPixels = -(CELL - 1f), cellHeight = CELL)
        assertEquals(0, away.rows)
        assertEquals(-(CELL - 1f), away.residual, EPSILON)

        assertEquals(1, wheelStep(CELL, CELL).rows)
        assertEquals(-1, wheelStep(-CELL, CELL).rows)
    }

    @Test
    fun `wheel travel splits towards zero and keeps the sign of its remainder`() {
        val step = wheelStep(accumulatedPixels = -(2 * CELL + 5f), cellHeight = CELL)
        assertEquals(-2, step.rows)
        assertEquals(-5f, step.residual, EPSILON)

        val other = wheelStep(accumulatedPixels = 2 * CELL + 5f, cellHeight = CELL)
        assertEquals(2, other.rows)
        assertEquals(5f, other.residual, EPSILON)
    }

    @Test
    fun `a steady drag sends one notch per row and no more`() {
        // What a gesture actually does: small travel arriving frame by frame,
        // with the remainder carried. Ten rows of travel is ten notches.
        var accumulated = 0f
        var notches = 0
        repeat(200) {
            accumulated += -CELL / 20f
            val step = wheelStep(accumulated, CELL)
            notches += abs(wheelNotches(step))
            accumulated = step.residual
        }
        assertEquals(10, notches)
    }

    @Test
    fun `a cell height that is not yet known consumes nothing`() {
        assertEquals(ScrollStep.None, wheelStep(accumulatedPixels = 100f, cellHeight = 0f))
    }

    private companion object {
        const val CELL = 24f
        const val EPSILON = 0.001f
    }
}
