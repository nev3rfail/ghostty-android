package com.ghostty.android.renderer

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

    private companion object {
        const val CELL = 24f
        const val EPSILON = 0.001f
    }
}
