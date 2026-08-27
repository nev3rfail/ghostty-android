package com.ghostty.android.renderer

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.animation.DecelerateInterpolator
import android.widget.EdgeEffect
import android.widget.OverScroller
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ghostty.android.R
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Microphone indicator state for always-on voice input.
 * Used by the host app to show voice recognition status in the terminal view.
 */
enum class MicIndicatorState {
    OFF,        // Hidden - no indicator shown
    IDLE,       // Blue - connected, waiting for speech
    ACTIVE,     // Green with pulse - speech detected, recording/sending audio
    ERROR,      // Red - error state
    PROCESSING  // Amber with pulse - waiting for transcription response
}

/**
 * Unified listener for terminal events.
 * Handles surface lifecycle, keyboard gestures, and other terminal events.
 */
interface TerminalEventListener {
    /**
     * Called when terminal surface is ready with valid grid size.
     * Fires after initial creation and after every resize (orientation change, etc.).
     *
     * Expected response: clear terminal, send resize to remote, fetch fresh content.
     *
     * @param cols Terminal columns
     * @param rows Terminal rows
     */
    fun onSurfaceReady(cols: Int, rows: Int)

    /**
     * Called with bytes the user typed, already encoded for a terminal.
     *
     * Keystrokes arrive from the soft keyboard and from hardware keys, and both
     * are delivered here as the byte sequence a program reading the pty expects.
     */
    fun onInput(bytes: ByteArray) {}

    /**
     * Called when the GL surface has been resumed after a pause.
     * Fires after onResumeView() completes and the surface is ready to render.
     *
     * Use this to refresh content that may have gone stale during the pause,
     * or to re-render content that needs to be displayed again.
     *
     * Note: This is NOT called on initial surface creation - use onSurfaceReady for that.
     * This is specifically for pause/resume cycles where the surface was already created.
     */
    fun onSurfaceResumed() {}

    /**
     * Called during drag/animation with current keyboard overlay offset progress.
     * Used to drive keyboard visibility animation.
     *
     * @param offset Current offset in pixels (0 to maxOffset)
     * @param maxOffset Maximum offset (keyboard height)
     */
    fun onKeyboardOverlayProgress(offset: Float, maxOffset: Float)

    /**
     * Called when keyboard overlay state changes (expanded/collapsed).
     * Used to finalize keyboard visibility animation.
     *
     * @param expanded true if keyboard area should be shown
     */
    fun onKeyboardOverlayStateChanged(expanded: Boolean)

    /**
     * Called when user performs a two-finger swipe up gesture.
     */
    fun onTwoFingerSwipeUp() {}

    /**
     * Called when user performs a two-finger swipe down gesture.
     */
    fun onTwoFingerSwipeDown() {}

    /**
     * Called when user performs a two-finger double-tap gesture.
     */
    fun onTwoFingerDoubleTap() {}

    /**
     * Called when user performs a single-finger double-tap gesture.
     */
    fun onDoubleTap() {}

    /**
     * Called when user completes a text selection.
     * Consumer should handle the selected text (copy to clipboard, show menu, etc.)
     *
     * @param text The selected text content
     */
    fun onTextSelected(text: String) {}

    /**
     * Called when user taps on a hyperlink (OSC 8).
     * Consumer should handle the link (open browser, show confirmation, etc.)
     *
     * @param uri The hyperlink URI
     */
    fun onHyperlinkClicked(uri: String) {}
}

/**
 * OpenGL ES surface view for Ghostty terminal rendering.
 *
 * This view manages the OpenGL ES context and the renderer lifecycle.
 * It handles:
 * - OpenGL ES 3.1 context creation
 * - Renderer thread management
 * - Surface lifecycle (pause/resume)
 * - Touch gestures (scrolling, double-tap, two-finger swipes)
 *
 * Font size can be changed programmatically via [setFontSize].
 *
 * @param context Android context
 * @param attrs XML attributes (optional, for XML inflation)
 * @param initialFontSize Initial font size in pixels (optional, for programmatic construction)
 * @param maxScrollbackBytes Terminal history budget in bytes, or zero to leave the
 *   terminal library its own. The page list enforces a floor of at least two of
 *   its standard pages, so a smaller budget changes nothing.
 */
class GhosttyGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    initialFontSize: Float = 0f,
    private val maxScrollbackBytes: Long = 0L
) : GLSurfaceView(context, attrs) {

    companion object {
        private const val TAG = "GhosttyGLSurfaceView"

        // Control bytes, written numerically so they survive any tooling that
        // rewrites escape sequences.
        private const val TAB: Byte = 0x09
        private const val CR: Byte = 0x0D
        private const val ESC: Byte = 0x1B
        private const val DEL: Byte = 0x7F
        private val CSI = byteArrayOf(0x1B, 0x5B)
        private val LINE_FEED_CHAR = 10.toChar()
        private val CARRIAGE_RETURN_CHAR = 13.toChar()

        // OpenGL ES version requirements
        private const val GLES_MAJOR_VERSION = 3
        private const val GLES_CONTEXT_CLIENT_VERSION = 3 // Request ES 3.x

        // Font size defaults (can be overridden per instance)
        private const val DEFAULT_MIN_FONT_SIZE = 8f
        private const val DEFAULT_MAX_FONT_SIZE = 96f
        private const val DEFAULT_FONT_SIZE = 20f  // More reasonable default

        // Bottom offset animation
        private const val SNAP_ANIMATION_DURATION_MS = 250L

        // Two-finger swipe thresholds (velocity-based detection)
        private const val TWO_FINGER_SWIPE_MIN_VELOCITY = 150f  // pixels/second - minimum velocity to trigger
        private const val TWO_FINGER_SWIPE_MAX_ANGLE = 30f      // degrees - max angle between fingers for parallel check
        private const val TWO_FINGER_DIRECTION_RATIO = 1.5f     // vertical/horizontal ratio for swipe direction
        private const val TWO_FINGER_SWIPE_COOLDOWN_MS = 200L   // cooldown between swipes in same gesture

        // Two-finger double-tap thresholds
        private const val TWO_FINGER_TAP_MAX_DISTANCE_DP = 20f
        private const val TWO_FINGER_TAP_MAX_TIME_MS = 200L
        private const val TWO_FINGER_DOUBLE_TAP_TIMEOUT_MS = 300L

        // Sweep direction constants (animation timing is managed in native renderer)
        private const val FOLLOW_UP_FRAME_MS = 300L

        // A fling reports travel relative to where it started, so it starts far
        // enough from either end of its own range to never reach one.
        private const val FLING_ORIGIN_PX = 1 shl 20

        private const val SWEEP_UP = 1
        private const val SWEEP_DOWN = 2
    }

    private val renderer: GhosttyRenderer
    private val gestureDetector: GestureDetector
    private val scroller: OverScroller
    private val edgeEffectTop: EdgeEffect
    private val edgeEffectBottom: EdgeEffect
    private var currentFontSize = DEFAULT_FONT_SIZE

    // Configurable font size bounds (can be set via XML or programmatically)
    private var minFontSize = DEFAULT_MIN_FONT_SIZE
    private var maxFontSize = DEFAULT_MAX_FONT_SIZE

    // Interactive mode - when false, touch events are not processed
    private var isInteractive = true

    // System gesture insets - touches in these zones pass through to system for back gesture
    private var systemGestureInsetLeft = 0
    private var systemGestureInsetRight = 0

    // Travel gathered from a gesture and not yet turned into rows. GL thread only.
    private var glScrollPixels = 0f

    // The sub-row shift the terminal is drawing with, published for the touch to
    // cell conversion that runs on the main thread.
    @Volatile
    private var visualScrollResidual = 0f

    // Where the gesture last touched, in view pixels. Written on the main
    // thread and read on the GL thread, the way the residual above is, and kept
    // after the finger leaves so a fling's reports name the same place. A
    // program that routes the wheel by pane needs the position.
    @Volatile
    private var lastTouchX = 0f

    @Volatile
    private var lastTouchY = 0f

    // Two-finger gesture state
    private var twoFingerGestureActive = false
    private var twoFingerStartX1 = 0f
    private var twoFingerStartY1 = 0f
    private var twoFingerStartX2 = 0f
    private var twoFingerStartY2 = 0f
    private var twoFingerStartTime = 0L
    private var lastTwoFingerTapTime = 0L
    private var twoFingerSwipeDetected = false
    private var lastSwipeTime = 0L  // For cooldown between swipes

    // Computed pixel thresholds (set in init)
    private var twoFingerTapMaxDistancePx = 0f

    // Scroller position at the last fling frame, so a frame can tell how far the
    // fling travelled since the one before it
    private var lastScrollerY = 0

    // Terminal event listener
    private var eventListener: TerminalEventListener? = null
    private var maxBottomOffset = 0f              // Configurable max offset (keyboard height)
    private var bottomOffset = 0f                 // Current animated offset (0 to maxBottomOffset)
    private var bottomOffsetDragStart = 0f        // Offset when drag gesture started
    private var accumulatedBottomDrag = 0f        // Accumulated drag distance for bottom offset
    private var isBottomOffsetAnimating = false
    private var bottomOffsetAnimationStartTime = 0L
    private var bottomOffsetAnimationStartValue = 0f
    private var bottomOffsetAnimationTargetValue = 0f
    private val bottomOffsetInterpolator = DecelerateInterpolator()
    private var lastBottomOffsetExpanded = false  // Track last state for callback
    // Written on the GL thread, which is where the content height it depends on
    // can be read, and consulted on both.
    @Volatile
    private var shouldScrollContentWithOverlay = true

    // Selection mode state
    private var isSelectionMode = false
    private var selectionStartX = 0f
    private var selectionStartY = 0f

    // Animation state is now managed entirely in the native renderer

    // VelocityTracker for reliable two-finger swipe detection
    private var velocityTracker: VelocityTracker? = null
    private val touchSlop: Int by lazy { ViewConfiguration.get(context).scaledTouchSlop }

    // Choreographer for driving fling animation at vsync
    private val choreographer = Choreographer.getInstance()
    private var isAnimating = false

    // Microphone indicator state for always-on voice input
    private var micIndicatorState: MicIndicatorState = MicIndicatorState.OFF

    // Frame callback for scroll animation
    private val scrollAnimationCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!scroller.computeScrollOffset()) {
                isAnimating = false
                queueEvent { settleScroll() }
                return
            }

            // Travel since the previous frame. The scroller's absolute position
            // means nothing to the terminal; only the difference does.
            val travel = (scroller.currY - lastScrollerY).toFloat()
            lastScrollerY = scroller.currY
            if (travel != 0f) {
                queueEvent { applyScrollPixels(travel) }
            }

            choreographer.postFrameCallback(this)
        }
    }

    /**
     * Applies finger travel to the viewport. GL thread only.
     *
     * The renderer is asked how far the viewport may travel before any of the
     * travel is kept. With no room in the direction the finger points, nothing
     * accumulates and no sub-row shift is drawn, so the picture holds still
     * instead of shearing by part of a row and returning.
     *
     * With room, travel accumulates until it crosses a row and the row delta
     * goes to the terminal, clamped to the room reported. What the viewport
     * actually moved still decides what survives: the capacity is read before
     * the movement it authorises, and a resize landing between the two would
     * otherwise go unnoticed.
     */
    private fun applyScrollPixels(pixels: Float) {
        // A program drawing its own screen keeps its own history and expects the
        // wheel. Ask for the bytes before touching the viewport: under such a
        // program the viewport holds nothing, so moving it is what makes the
        // gesture read as broken.
        val wheeled = scrollStep(glScrollPixels + pixels, renderer.getFontLineSpacing())
        val notches = wheelNotches(wheeled)
        if (notches != 0) {
            val report = renderer.encodeWheel(notches, lastTouchX.toInt(), lastTouchY.toInt())
            if (report != null) {
                val applied = afterWheel(wheeled)
                glScrollPixels = applied.residual
                visualScrollResidual = applied.residual
                post { eventListener?.onInput(report) }
                return
            }
        }

        val available = rowsAvailable(pixels, renderer.getScrollCapacity())
        if (available == 0) {
            // The offset channel is shared with the keyboard overlay's shift, so
            // a refusal leaves that shift standing rather than flattening it.
            glScrollPixels = 0f
            visualScrollResidual = 0f
            renderer.setScrollPixelOffset(if (shouldScrollContentWithOverlay) bottomOffset else 0f)
            requestRender()
            post { onScrollRefused(pixels) }
            return
        }

        glScrollPixels += pixels
        val step = scrollStep(glScrollPixels, renderer.getFontLineSpacing())

        val applied = if (step.rows == 0) {
            step
        } else {
            val asked = step.rows.coerceIn(-available, available)
            val before = renderer.getViewportOffset()
            renderer.scrollDelta(asked)
            afterMovement(ScrollStep(asked, step.residual), renderer.getViewportOffset() - before)
        }

        glScrollPixels = applied.residual
        visualScrollResidual = applied.residual
        renderer.setScrollPixelOffset(applied.residual)
        requestRender()

        if (applied.rows != step.rows) {
            post { onScrollRefused(pixels) }
        }
    }

    /**
     * Lands the viewport on a whole row once nothing is driving it. GL thread only.
     */
    private fun settleScroll() {
        glScrollPixels = 0f
        visualScrollResidual = 0f
        if (bottomOffset == 0f) {
            renderer.setScrollPixelOffset(0f)
            requestRender()
        }
    }

    /**
     * Drops travel gathered but not yet applied, keeping whatever shift the
     * keyboard overlay is showing. GL thread only.
     */
    private fun discardPendingScroll() {
        glScrollPixels = 0f
        visualScrollResidual = 0f
        renderer.setScrollPixelOffset(if (shouldScrollContentWithOverlay) bottomOffset else 0f)
        requestRender()
    }

    /**
     * The viewport travelled less than it was asked to, so the gesture has reached
     * an end of the buffer. Main thread.
     */
    private fun onScrollRefused(pixels: Float) {
        val effect = if (pixels < 0f) edgeEffectTop else edgeEffectBottom
        effect.setSize(width, height)
        if (isAnimating) {
            effect.onAbsorb(scroller.currVelocity.toInt())
            scroller.forceFinished(true)
            choreographer.removeFrameCallback(scrollAnimationCallback)
            isAnimating = false
        } else {
            effect.onPull(abs(pixels) / height.coerceAtLeast(1))
        }
    }

    /**
     * Whether the viewport follows the active area, for the keyboard overlay's
     * benefit. The one terminal read left on a gesture path, and reachable only
     * where a host sets a maximum bottom offset.
     */
    private fun isViewportAtBottomForOverlay(): Boolean = renderer.isViewportAtBottom()

    // Frame callback for bottom offset snap animation
    private val bottomOffsetAnimationCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isBottomOffsetAnimating) return

            val elapsed = System.currentTimeMillis() - bottomOffsetAnimationStartTime
            val progress = (elapsed.toFloat() / SNAP_ANIMATION_DURATION_MS).coerceIn(0f, 1f)
            val interpolatedProgress = bottomOffsetInterpolator.getInterpolation(progress)

            bottomOffset = bottomOffsetAnimationStartValue +
                (bottomOffsetAnimationTargetValue - bottomOffsetAnimationStartValue) * interpolatedProgress

            // Apply offset via renderer only if content needs to scroll
            queueEvent {
                val scrollOffset = if (shouldScrollContentWithOverlay) bottomOffset else 0f
                renderer.setScrollPixelOffset(scrollOffset)
                requestRender()
            }

            // Notify listener of offset change
            eventListener?.onKeyboardOverlayProgress(bottomOffset, maxBottomOffset)

            if (progress >= 1f) {
                // Animation complete
                isBottomOffsetAnimating = false
                bottomOffset = bottomOffsetAnimationTargetValue

                // Notify state change if it changed
                val isExpanded = bottomOffset >= maxBottomOffset
                if (isExpanded != lastBottomOffsetExpanded) {
                    lastBottomOffsetExpanded = isExpanded
                    eventListener?.onKeyboardOverlayStateChanged(isExpanded)
                }
            } else {
                // Continue animation
                choreographer.postFrameCallback(this)
            }
        }
    }

    init {
        Log.d(TAG, "Initializing Ghostty GL Surface View")

        // Required for the view to take focus and receive key events.
        isFocusable = true
        isFocusableInTouchMode = true

        // Listen for system gesture insets to allow back gesture to work at screen edges
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val gestureInsets = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
            systemGestureInsetLeft = gestureInsets.left
            systemGestureInsetRight = gestureInsets.right
            Log.d(TAG, "System gesture insets: left=$systemGestureInsetLeft, right=$systemGestureInsetRight")
            insets
        }

        // Parse XML attributes if present
        val resolvedFontSize = if (attrs != null) {
            val typedArray: TypedArray = context.obtainStyledAttributes(
                attrs,
                R.styleable.GhosttyGLSurfaceView
            )
            try {
                // getDimension returns pixels, or default value if not specified
                val xmlFontSize = typedArray.getDimension(
                    R.styleable.GhosttyGLSurfaceView_initialFontSize,
                    0f
                )

                // Parse min/max font size if specified
                val xmlMinFontSize = typedArray.getDimension(
                    R.styleable.GhosttyGLSurfaceView_minFontSize,
                    0f
                )
                val xmlMaxFontSize = typedArray.getDimension(
                    R.styleable.GhosttyGLSurfaceView_maxFontSize,
                    0f
                )

                // Parse interactive mode
                isInteractive = typedArray.getBoolean(
                    R.styleable.GhosttyGLSurfaceView_interactive,
                    true
                )

                // Apply min/max font size if specified
                if (xmlMinFontSize > 0f) {
                    minFontSize = xmlMinFontSize.coerceAtLeast(1f)
                }
                if (xmlMaxFontSize > 0f) {
                    maxFontSize = xmlMaxFontSize.coerceAtLeast(minFontSize)
                }

                // Use XML value if specified, otherwise use constructor parameter
                if (xmlFontSize > 0f) xmlFontSize else initialFontSize
            } finally {
                typedArray.recycle()
            }
        } else {
            // No XML attributes, use constructor parameter
            initialFontSize
        }

        // Set current font size: use resolved value if specified, otherwise use default
        // Clamp to configured min/max bounds
        currentFontSize = if (resolvedFontSize > 0f) {
            resolvedFontSize.coerceIn(minFontSize, maxFontSize)
        } else {
            DEFAULT_FONT_SIZE.coerceIn(minFontSize, maxFontSize)
        }
        Log.d(TAG, "Initial font size: $currentFontSize px (min: $minFontSize, max: $maxFontSize, interactive: $isInteractive)")

        // Request OpenGL ES 3.x context
        setEGLContextClientVersion(GLES_CONTEXT_CLIENT_VERSION)

        // Configure EGL
        // RGBA8888 format (8 bits per channel)
        // No depth buffer needed for 2D rendering
        // No stencil buffer needed
        setEGLConfigChooser(
            8,  // red
            8,  // green
            8,  // blue
            8,  // alpha
            0,  // depth
            0   // stencil
        )

        // Create and set the renderer (pass context for DPI access and initial font size)
        // Always pass a valid font size (use currentFontSize which defaults to DEFAULT_FONT_SIZE)
        renderer = GhosttyRenderer(context, currentFontSize.toInt(), maxScrollbackBytes)
        setRenderer(renderer)

        // Set up surface change callback to notify listener on main thread
        renderer.setOnSurfaceChangedCallback { cols, rows ->
            // Post to main thread since we're on GL thread
            post {
                eventListener?.onSurfaceReady(cols, rows)
            }
        }

        // Draw only when something changed. Everything that alters the screen asks
        // for a frame: terminal output through the renderer, gestures and resizes
        // here, and animations from the renderer while they run.
        // One frame is asked for again shortly after, because a program can end
        // its output in the middle of a synchronized update. The renderer holds
        // the previous picture for the length of one, and with no later frame to
        // draw, that picture is the last thing shown -- a screen that stops
        // where the program's final write began.
        renderer.renderRequester = {
            requestRender()
            removeCallbacks(followUpFrame)
            postDelayed(followUpFrame, FOLLOW_UP_FRAME_MS)
        }
        renderMode = RENDERMODE_WHEN_DIRTY

        // Initialize scroll gesture detector
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                // Abort any ongoing fling animation
                scroller.forceFinished(true)
                if (isAnimating) {
                    choreographer.removeFrameCallback(scrollAnimationCallback)
                    isAnimating = false
                }
                // Abort any ongoing bottom offset animation
                if (isBottomOffsetAnimating) {
                    choreographer.removeFrameCallback(bottomOffsetAnimationCallback)
                    isBottomOffsetAnimating = false
                }
                // Reset bottom offset drag tracking
                bottomOffsetDragStart = bottomOffset
                accumulatedBottomDrag = bottomOffset  // Start from current offset
                // A gesture starts from whole rows.
                queueEvent { discardPendingScroll() }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                // Handle bottom offset mode (for keyboard area)
                // Enter this mode when: at bottom and swiping up, OR already have offset
                if (maxBottomOffset > 0 &&
                    (bottomOffset > 0 || (distanceY > 0 && isViewportAtBottomForOverlay()))
                ) {
                    accumulatedBottomDrag += distanceY
                    val newOffset = accumulatedBottomDrag.coerceIn(0f, maxBottomOffset)
                    if (newOffset != bottomOffset) {
                        bottomOffset = newOffset

                        queueEvent {
                            // Whether the content travels with the overlay depends on
                            // how much content there is, which the terminal knows.
                            shouldScrollContentWithOverlay =
                                renderer.getContentHeight() > height - maxBottomOffset
                            renderer.setScrollPixelOffset(
                                if (shouldScrollContentWithOverlay) bottomOffset else 0f
                            )
                            requestRender()
                        }
                        eventListener?.onKeyboardOverlayProgress(bottomOffset, maxBottomOffset)
                    }
                    // Travel here belongs to the overlay, so scrolling starts from
                    // nothing when the drag leaves this branch.
                    queueEvent { discardPendingScroll() }
                    return true
                }

                // Positive travel is towards the active area at the bottom. The row
                // arithmetic, the boundary and the sub-row shift belong to the GL
                // thread, which is the only one that may read the terminal without
                // waiting behind a burst of output.
                queueEvent { applyScrollPixels(distanceY) }
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                // Don't fling if we're in bottom offset mode - let the snap animation handle it
                if (bottomOffset > 0 ||
                    (maxBottomOffset > 0 && velocityY < 0 && isViewportAtBottomForOverlay())
                ) {
                    return false
                }

                // The scroller runs in pixels from a local origin: where the
                // viewport sits and how far it may travel belong to the thread
                // that owns the terminal, and the fling ends when that thread
                // reports travel it could not apply.
                //
                // Android's convention is that a fling upwards carries negative
                // velocity and reveals content below, which is travel towards the
                // active area here, so the velocity is negated.
                lastScrollerY = FLING_ORIGIN_PX
                scroller.forceFinished(true)
                scroller.fling(
                    0, FLING_ORIGIN_PX,
                    0, -velocityY.toInt(),
                    0, 0,
                    0, FLING_ORIGIN_PX * 2,
                    0, height / 4
                )

                if (!isAnimating) {
                    isAnimating = true
                    choreographer.postFrameCallback(scrollAnimationCallback)
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                Log.d(TAG, "Single-finger double tap detected")
                startRippleEffect(e.x, e.y)
                eventListener?.onDoubleTap()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!isInteractive) return

                // Convert touch coordinates to cell coordinates
                val cell = pixelToCell(e.x, e.y) ?: return

                Log.d(TAG, "Long press at cell (${cell.first}, ${cell.second})")

                // Start selection mode
                isSelectionMode = true
                selectionStartX = e.x
                selectionStartY = e.y

                queueEvent {
                    renderer.startSelection(cell.first, cell.second)
                    requestRender()
                }

                // Haptic feedback
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!isInteractive) return false

                // Convert touch coordinates to cell coordinates
                val cell = pixelToCell(e.x, e.y) ?: return false

                // Check for hyperlink at this cell
                queueEvent {
                    val uri = renderer.getHyperlinkAtCell(cell.first, cell.second)
                    if (uri != null) {
                        Log.d(TAG, "Hyperlink tapped: $uri")
                        post {
                            eventListener?.onHyperlinkClicked(uri)
                        }
                    }
                }

                return true
            }
        })

        // Initialize OverScroller for fling physics
        scroller = OverScroller(context)

        // Initialize edge effects for overscroll feedback
        edgeEffectTop = EdgeEffect(context)
        edgeEffectBottom = EdgeEffect(context)

        // Compute pixel thresholds from DP values
        val density = context.resources.displayMetrics.density
        twoFingerTapMaxDistancePx = TWO_FINGER_TAP_MAX_DISTANCE_DP * density

        Log.d(TAG, "GL Surface View initialized")
    }

    /**
     * Called during draw - just triggers edge effect rendering if needed.
     * Scroll animation is handled by Choreographer callback.
     */
    override fun computeScroll() {
        super.computeScroll()

        // Keep edge effects animating
        if (!edgeEffectTop.isFinished || !edgeEffectBottom.isFinished) {
            postInvalidateOnAnimation()
        }
    }

    /**
     * Draw edge effects on top of the GL surface.
     * Note: Ripple effect is now rendered in OpenGL.
     */
    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        // Draw top edge effect
        if (!edgeEffectTop.isFinished) {
            val restoreCount = canvas.save()
            edgeEffectTop.setSize(width, height)
            if (edgeEffectTop.draw(canvas)) {
                postInvalidateOnAnimation()
            }
            canvas.restoreToCount(restoreCount)
        }

        // Draw bottom edge effect
        if (!edgeEffectBottom.isFinished) {
            val restoreCount = canvas.save()
            canvas.translate(0f, height.toFloat())
            canvas.rotate(180f, width / 2f, 0f)
            edgeEffectBottom.setSize(width, height)
            if (edgeEffectBottom.draw(canvas)) {
                postInvalidateOnAnimation()
            }
            canvas.restoreToCount(restoreCount)
        }
    }

    /**
     * Start a Material Design ripple effect at the specified coordinates.
     * The ripple is rendered in OpenGL; animation timing is driven by the GL render loop.
     *
     * @param x X coordinate of ripple center (touch location)
     * @param y Y coordinate of ripple center (touch location)
     */
    private fun startRippleEffect(x: Float, y: Float) {
        // Max radius = distance to farthest corner
        val corners = listOf(
            kotlin.math.hypot(x.toDouble(), y.toDouble()),
            kotlin.math.hypot((width - x).toDouble(), y.toDouble()),
            kotlin.math.hypot(x.toDouble(), (height - y).toDouble()),
            kotlin.math.hypot((width - x).toDouble(), (height - y).toDouble())
        )
        val maxRadius = corners.maxOrNull()?.toFloat() ?: 0f

        // Start ripple - animation is driven by the GL render loop
        queueEvent {
            renderer.startRipple(x, y, maxRadius)
        }
    }

    /**
     * Start a sweep effect in the given direction.
     * The sweep is a horizontal bar that moves across the screen for gesture feedback.
     * Animation timing is driven by the GL render loop.
     *
     * @param direction SWEEP_UP (1) for bottom-to-top, SWEEP_DOWN (2) for top-to-bottom
     */
    private fun startSweepEffect(direction: Int) {
        // Start sweep - animation is driven by the GL render loop
        queueEvent {
            renderer.startSweep(direction)
        }
    }

    /**
     * Request a frame to be rendered.
     *
     * Call this when terminal state changes and needs to be re-rendered.
     * This is safe to call from any thread.
     */
    override fun invalidate() {
        requestRender()
    }

    /**
     * Set the terminal size in character cells.
     *
     * @param cols Number of columns
     * @param rows Number of rows
     */
    fun setTerminalSize(cols: Int, rows: Int) {
        // Queue a runnable on the GL thread to avoid threading issues
        queueEvent {
            renderer.setTerminalSize(cols, rows)
            requestRender() // Re-render with new size
        }
    }

    /**
     * Get the renderer instance.
     *
     * This allows access to the renderer for direct operations like
     * processing input for testing.
     *
     * @return The GhosttyRenderer instance
     */
    fun getRenderer(): GhosttyRenderer {
        return renderer
    }

    /**
     * Called when the view is detached from the window.
     *
     * Clean up resources here.
     */
    override fun onDetachedFromWindow() {
        Log.d(TAG, "onDetachedFromWindow")

        // Queue cleanup on the GL thread
        queueEvent {
            renderer.destroy()
        }

        super.onDetachedFromWindow()
    }

    /**
     * Convert pixel coordinates to cell coordinates.
     *
     * @param pixelX X coordinate in pixels
     * @param pixelY Y coordinate in pixels
     * @return Pair of (col, row) or null if out of bounds
     */
    private fun pixelToCell(pixelX: Float, pixelY: Float): Pair<Int, Int>? {
        val cellSize = renderer.getCellSize()
        val cellWidth = cellSize[0]
        val cellHeight = cellSize[1]

        if (cellWidth <= 0 || cellHeight <= 0) return null

        val col = (pixelX / cellWidth).toInt()
        val row = ((pixelY + visualScrollResidual) / cellHeight).toInt()

        val gridSize = renderer.getGridSize()
        if (col < 0 || col >= gridSize[0] || row < 0) return null

        return Pair(col, row)
    }

    /**
     * Handle pause lifecycle event.
     *
     * Called by the parent activity/fragment.
     */
    fun onPauseView() {
        Log.d(TAG, "onPauseView")
        renderer.onPause()
        onPause() // Pause the GL thread
    }

    /**
     * Handle resume lifecycle event.
     *
     * Called by the parent activity/fragment.
     */
    fun onResumeView() {
        Log.d(TAG, "onResumeView")
        onResume() // Resume the GL thread
        renderer.onResume()

        // Notify listener that surface has resumed, using post() to ensure
        // the GL thread has had time to fully resume before the callback
        post {
            eventListener?.onSurfaceResumed()
        }
    }

    /**
     * Check if an X coordinate is within the system gesture zones (screen edges).
     * Touches in these zones should pass through to allow system back gesture.
     */
    private fun isInSystemGestureZone(x: Float): Boolean {
        return x < systemGestureInsetLeft || x > (width - systemGestureInsetRight)
    }

    /**
     * Handle touch events for scrolling and gestures.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // If not interactive, don't process touch events
        if (!isInteractive) {
            return false
        }

        // Pass through touches that start in system gesture zones
        // This allows the system back gesture to work reliably
        if (event.actionMasked == MotionEvent.ACTION_DOWN && isInSystemGestureZone(event.x)) {
            return false
        }

        // Handle selection mode drag
        if (isSelectionMode && event.actionMasked == MotionEvent.ACTION_MOVE) {
            val cell = pixelToCell(event.x, event.y)
            if (cell != null) {
                queueEvent {
                    renderer.updateSelection(cell.first, cell.second)
                    requestRender()
                }
            }
            return true
        }

        lastTouchX = event.x
        lastTouchY = event.y

        // Track two-finger gestures
        handleTwoFingerGesture(event)

        // Let scroll gesture detector handle the event
        // Only process scroll if not in a two-finger gesture and not in selection mode
        val scrollHandled = if (!twoFingerGestureActive && !isSelectionMode) {
            gestureDetector.onTouchEvent(event)
        } else if (!isSelectionMode) {
            false
        } else {
            // Still process gesture detector for selection mode (for onLongPress detection)
            gestureDetector.onTouchEvent(event)
        }

        // Handle edge effect release and bottom offset snap on ACTION_UP or ACTION_CANCEL
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Handle selection finalization
                if (isSelectionMode) {
                    finalizeSelection()
                    isSelectionMode = false
                }

                // Handle bottom offset snap animation
                if (maxBottomOffset > 0 && (bottomOffset > 0 || accumulatedBottomDrag != 0f)) {
                    // Snap to nearest: if past 50%, expand; otherwise collapse
                    val target = if (bottomOffset > maxBottomOffset / 2) maxBottomOffset else 0f

                    // Only scroll content if it won't fit in the visible area after keyboard
                    val contentHeight = renderer.getContentHeight()
                    val visibleHeightWithKeyboard = height - maxBottomOffset
                    shouldScrollContentWithOverlay = contentHeight > visibleHeightWithKeyboard

                    animateBottomOffsetTo(target)
                    accumulatedBottomDrag = 0f
                }

                edgeEffectTop.onRelease()
                edgeEffectBottom.onRelease()
                if (!edgeEffectTop.isFinished || !edgeEffectBottom.isFinished) {
                    postInvalidateOnAnimation()
                }

                // Reset two-finger gesture state
                resetTwoFingerGestureState()
            }
        }

        return scrollHandled || true // Consume the event
    }

    /**
     * Finalize the current selection and notify the listener.
     */
    private fun finalizeSelection() {
        queueEvent {
            val text = renderer.getSelectionText()
            if (!text.isNullOrEmpty()) {
                Log.d(TAG, "Selection finalized: ${text.length} chars")
                post {
                    eventListener?.onTextSelected(text)
                }
            }
            renderer.clearSelection()
            requestRender()
        }
    }

    /**
     * Trigger a re-render from outside the view.
     *
     * This is safe to call from any thread.
     */
    fun triggerRender() {
        requestRender()
    }

    /**
     * Enable or disable the FPS display overlay.
     *
     * When enabled, the current frames per second is rendered at the
     * top-right corner of the terminal.
     */
    var showFps: Boolean = false
        set(value) {
            field = value
            queueEvent {
                renderer.setShowFps(value)
                requestRender()
            }
        }

    /**
     * Show the FPS monitor overlay.
     */
    fun showFpsMonitor() {
        showFps = true
    }

    /**
     * Hide the FPS monitor overlay.
     */
    fun hideFpsMonitor() {
        showFps = false
    }

    /**
     * Set the microphone indicator state for always-on voice input.
     *
     * The indicator appears at the top-left corner of the terminal:
     * - OFF: Hidden
     * - IDLE: Blue mic icon (listening, waiting for speech)
     * - ACTIVE: Green pulsing mic icon (speech detected)
     * - ERROR: Red mic icon (error state)
     *
     * @param state The microphone indicator state
     */
    fun setMicIndicatorState(state: MicIndicatorState) {
        if (micIndicatorState != state) {
            micIndicatorState = state
            queueEvent {
                renderer.setMicIndicatorState(state.ordinal)
                requestRender()
            }
        }
    }

    /**
     * Get the current microphone indicator state.
     *
     * @return Current MicIndicatorState
     */
    fun getMicIndicatorState(): MicIndicatorState = micIndicatorState

    /**
     * Set a tint overlay color for session differentiation.
     *
     * The tint is rendered as a semi-transparent full-screen overlay on top of
     * all terminal content, allowing different sessions to have distinct visual
     * appearances.
     *
     * @param color ARGB color (e.g., 0xFF4CAF50 for green)
     * @param alpha Opacity from 0.0 (invisible) to 1.0 (fully opaque), typically 0.15 for subtle tint
     */
    fun setTintColor(color: Int, alpha: Float) {
        queueEvent {
            renderer.setTintColor(color, alpha)
            requestRender()
        }
    }

    /**
     * Set the maximum bottom offset (keyboard height).
     *
     * When set to a positive value, the user can swipe up at the bottom
     * of the terminal to reveal this offset area for the keyboard.
     *
     * @param height Maximum offset in pixels (typically keyboard height)
     */
    fun setMaxBottomOffset(height: Float) {
        this.maxBottomOffset = height.coerceAtLeast(0f)
    }

    /**
     * Set the listener for terminal events.
     *
     * The listener receives:
     * - onSurfaceReady: when terminal surface is ready or resized
     * - onKeyboardOverlayProgress: during keyboard gesture drag/animation
     * - onKeyboardOverlayStateChanged: when keyboard gesture state changes
     */
    // ========================================================================
    // Keyboard input
    // ========================================================================

    /**
     * Apply Control to the next character the user types.
     *
     * A soft keyboard has no Control key, so a host offers one as a sticky
     * modifier: set this, and the next committed character is folded to its
     * control code.
     */
    private val followUpFrame = Runnable { requestRender() }

    var ctrlPending: Boolean = false

    /** Apply Alt to the next character typed, sending it with a meta prefix. */
    var altPending: Boolean = false

    /** A keyboard has been asked for and the input method has yet to accept. */
    private var keyboardRequested: Boolean = false

    /**
     * Whether a pending request has had an input method's attention: this view
     * focused, in a focused window. A request survives its first such moment and
     * is dropped once that moment passes, so it cannot fire at an unrelated focus
     * change later.
     */
    private var keyboardRequestOffered: Boolean = false

    /**
     * Called once a sticky modifier has been consumed, so a host can drop
     * whatever it shows for it.
     */
    var onModifiersConsumed: (() -> Unit)? = null

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // TYPE_NULL stops the IME from keeping an editable buffer. A terminal has
        // no text field to edit, only a byte stream to feed, and a buffer the IME
        // can re-send is what makes keystrokes arrive twice.
        // Logged because a repeated restart here is what an IME storm looks like:
        // it should happen once per focus change, not continuously.
        Log.d(TAG, "onCreateInputConnection")

        outAttrs.inputType = EditorInfo.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        return TerminalInputConnection()
    }

    /**
     * Take focus and ask the system to raise the soft keyboard.
     *
     * The request is remembered rather than made once. An input method only
     * accepts it for the view it is currently serving, and it starts serving
     * this view a moment after the view takes focus in a focused window --
     * asking before that happens is ignored, which is what happens when the
     * keyboard is asked for as soon as the view is created.
     */
    fun showKeyboard() {
        keyboardRequested = true
        keyboardRequestOffered = false
        Log.d(TAG, "keyboard requested")
        requestFocus()
        raiseKeyboardIfServed()
    }

    /** Withdraw a pending request, so a later focus change does not honour it. */
    fun hideKeyboard() {
        if (keyboardRequested) Log.d(TAG, "keyboard request withdrawn")
        keyboardRequested = false
        keyboardRequestOffered = false
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun raiseKeyboardIfServed() {
        if (!keyboardRequested || !hasWindowFocus() || !isFocused) return
        keyboardRequestOffered = true
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (manager.showSoftInput(this, 0)) {
            keyboardRequested = false
            Log.d(TAG, "keyboard request accepted")
        }
    }

    /**
     * Drops a request that has already had an input method's attention.
     *
     * A request made before any input method served the view keeps waiting, which
     * is what makes a keyboard asked for at view creation arrive at all. One that
     * has been offered and not taken is spent, and keeping it means a keyboard
     * rising at whatever focus change comes next.
     */
    private fun expireKeyboardRequest() {
        if (keyboardRequested && keyboardRequestOffered) {
            keyboardRequested = false
            Log.d(TAG, "keyboard request expired unserved")
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        Log.d(TAG, "window focus $hasWindowFocus, keyboard requested $keyboardRequested")
        if (hasWindowFocus) raiseKeyboardIfServed() else expireKeyboardRequest()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        Log.d(TAG, "view focus $gainFocus, keyboard requested $keyboardRequested")
        if (gainFocus) raiseKeyboardIfServed() else expireKeyboardRequest()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val encoded = encodeKey(keyCode, event) ?: return super.onKeyDown(keyCode, event)
        eventListener?.onInput(encoded)
        return true
    }

    /**
     * Encode committed text, consuming any sticky modifier that is set.
     */
    private fun encodeCommitted(text: String): ByteArray {
        var bytes = text.toByteArray(Charsets.UTF_8)
        var consumed = false

        if (ctrlPending) {
            ctrlPending = false
            consumed = true
            val control = text.firstOrNull()?.let { controlByte(it) }
            if (control != null) {
                bytes = byteArrayOf(control) + text.substring(1).toByteArray(Charsets.UTF_8)
            }
        }

        if (altPending) {
            altPending = false
            consumed = true
            bytes = byteArrayOf(ESC) + bytes
        }

        if (consumed) onModifiersConsumed?.invoke()
        return bytes
    }

    /** The C0 code a character maps to when Control is held, if any. */
    private fun controlByte(character: Char): Byte? {
        val lowered = character.code or 0x20
        if (lowered in 0x61..0x7A) return (lowered - 0x60).toByte()

        return when (character.code) {
            0x5B -> 0x1B // [
            0x5C -> 0x1C // backslash
            0x5D -> 0x1D // ]
            0x3F -> DEL // ?
            0x20 -> 0x00 // space
            else -> null
        }
    }

    /**
     * Translate a key press into the bytes a terminal expects, or null for keys
     * that are not typed input and belong to the system.
     */
    private fun encodeKey(keyCode: Int, event: KeyEvent): ByteArray? {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> return byteArrayOf(CR)
            KeyEvent.KEYCODE_DEL -> return byteArrayOf(DEL)
            KeyEvent.KEYCODE_TAB -> return byteArrayOf(TAB)
            KeyEvent.KEYCODE_ESCAPE -> return byteArrayOf(ESC)
            KeyEvent.KEYCODE_DPAD_UP -> return CSI + 0x41.toByte()
            KeyEvent.KEYCODE_DPAD_DOWN -> return CSI + 0x42.toByte()
            KeyEvent.KEYCODE_DPAD_RIGHT -> return CSI + 0x43.toByte()
            KeyEvent.KEYCODE_DPAD_LEFT -> return CSI + 0x44.toByte()
            KeyEvent.KEYCODE_MOVE_END -> return CSI + 0x46.toByte()
            KeyEvent.KEYCODE_MOVE_HOME -> return CSI + 0x48.toByte()
            KeyEvent.KEYCODE_FORWARD_DEL -> return CSI + 0x33.toByte() + 0x7E.toByte()
        }

        val codePoint = event.unicodeChar
        if (codePoint == 0) return null

        // A sticky modifier counts the same as one held on a keyboard, and is
        // spent whether or not the key it lands on has a control code.
        val ctrl = event.isCtrlPressed || ctrlPending
        val alt = event.isAltPressed || altPending
        if (ctrlPending || altPending) {
            ctrlPending = false
            altPending = false
            onModifiersConsumed?.invoke()
        }

        // Control folds a character down to the matching C0 code.
        if (ctrl) {
            val control = controlByte(codePoint.toChar()) ?: return null
            return if (alt) byteArrayOf(ESC, control) else byteArrayOf(control)
        }

        val typed = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)

        // Alt is the conventional stand-in for a meta prefix.
        return if (alt) byteArrayOf(ESC) + typed else typed
    }

    /**
     * Feeds IME text straight to the terminal. BaseInputConnection supplies the
     * plumbing only; nothing is buffered behind it, so every commit is final.
     */
    private inner class TerminalInputConnection :
        BaseInputConnection(this@GhosttyGLSurfaceView, false) {

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val value = text?.toString() ?: return true
            if (value.isNotEmpty()) {
                // A terminal expects carriage return where a text field would
                // carry a line feed.
                val forTerminal = value.replace(LINE_FEED_CHAR, CARRIAGE_RETURN_CHAR)
                eventListener?.onInput(encodeCommitted(forTerminal))
            }
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            // There is nowhere to show a composition, so wait for the commit.
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) { eventListener?.onInput(byteArrayOf(DEL)) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) return onKeyDown(event.keyCode, event)
            return true
        }
    }

    fun setEventListener(listener: TerminalEventListener?) {
        this.eventListener = listener
    }

    /**
     * Get the current bottom offset value.
     *
     * @return Current offset in pixels (0 to maxBottomOffset)
     */
    fun getBottomOffset(): Float = bottomOffset

    /**
     * Check if the bottom offset is fully expanded.
     *
     * @return true if offset equals maxBottomOffset and maxBottomOffset > 0
     */
    fun isBottomOffsetExpanded(): Boolean = maxBottomOffset > 0 && bottomOffset >= maxBottomOffset

    /**
     * Sync the bottom offset to a specific value without firing callbacks.
     *
     * Use this when syncing with external keyboard animations (e.g., system back gesture).
     * This updates the terminal scroll position to match the keyboard height.
     *
     * @param offset The offset in pixels (typically keyboard height)
     */
    fun syncBottomOffset(offset: Float) {
        bottomOffset = offset.coerceIn(0f, maxBottomOffset)
        queueEvent {
            val scrollOffset = if (shouldScrollContentWithOverlay) bottomOffset else 0f
            renderer.setScrollPixelOffset(scrollOffset)
            requestRender()
        }
    }

    /**
     * Scroll to bottom if we're scrolled up while the keyboard overlay is active.
     *
     * Call this after content changes (e.g., after processInput) to ensure
     * new content is visible when the keyboard is shown. When the user is
     * scrolled up viewing history and new content arrives while the keyboard
     * is visible, this will snap the viewport to show the latest content.
     *
     * This is a no-op if:
     * - The viewport is already at the bottom
     * - The keyboard overlay is not active (bottomOffset == 0)
     */
    fun scrollToBottomIfScrolledWithOverlay() {
        // Only act if keyboard overlay is active
        if (bottomOffset <= 0) return

        queueEvent {
            // Check if we're scrolled up (not at bottom)
            if (!renderer.isViewportAtBottom()) {
                Log.d(TAG, "Scrolling to bottom: scrolled up with keyboard overlay active")
                renderer.scrollToBottom()
                // Preserve the bottom offset for keyboard visibility
                renderer.setScrollPixelOffset(if (shouldScrollContentWithOverlay) bottomOffset else 0f)
                requestRender()
            }
        }
    }

    // ==================== Font Size API ====================

    /**
     * Set the font size programmatically.
     *
     * The size will be clamped to the configured min/max bounds.
     * This triggers a re-render with the new font size.
     *
     * If called before the surface is ready, this also updates the pending font size
     * on the renderer, ensuring the correct size is used when the surface is created.
     *
     * @param fontSize Font size in pixels
     */
    fun setFontSize(fontSize: Float) {
        val clampedSize = fontSize.coerceIn(minFontSize, maxFontSize)
        if (kotlin.math.abs(clampedSize - currentFontSize) > 0.1f) {
            Log.i(TAG, "setFontSize: $currentFontSize -> $clampedSize px")
            currentFontSize = clampedSize

            // Update pending font size on renderer (for when surface is created)
            // This ensures correct grid size is used from the start
            renderer.setPendingFontSize(currentFontSize.toInt())

            // Also queue the font change for when surface is already ready
            queueEvent {
                renderer.setFontSize(currentFontSize.toInt())
                requestRender()
            }
        }
    }

    /**
     * Set the font size using scaled pixels (SP).
     *
     * SP values scale with the user's font size preference (accessibility).
     * The SP value is converted to pixels internally using the display metrics.
     * The resulting pixel size will be clamped to the configured min/max bounds.
     *
     * @param fontSizeSp Font size in scaled pixels (SP)
     */
    fun setFontSizeSp(fontSizeSp: Float) {
        val pixelSize = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            fontSizeSp,
            context.resources.displayMetrics
        )
        Log.d(TAG, "setFontSizeSp: ${fontSizeSp}sp -> ${pixelSize}px")
        setFontSize(pixelSize)
    }

    /**
     * Get the current font size.
     *
     * @return Current font size in pixels
     */
    fun getFontSize(): Float = currentFontSize

    /**
     * Set the minimum and maximum font size bounds.
     *
     * The current font size will be re-clamped if it falls outside the new bounds.
     *
     * @param min Minimum font size in pixels (will be coerced to at least 1)
     * @param max Maximum font size in pixels (will be coerced to at least min)
     */
    fun setFontSizeBounds(min: Float, max: Float) {
        minFontSize = min.coerceAtLeast(1f)
        maxFontSize = max.coerceAtLeast(minFontSize)
        Log.d(TAG, "Font size bounds set: min=$minFontSize, max=$maxFontSize")

        // Re-clamp current font size if needed
        val clampedSize = currentFontSize.coerceIn(minFontSize, maxFontSize)
        if (clampedSize != currentFontSize) {
            setFontSize(clampedSize)
        }
    }

    /**
     * Get the minimum font size bound.
     *
     * @return Minimum font size in pixels
     */
    fun getMinFontSize(): Float = minFontSize

    /**
     * Get the maximum font size bound.
     *
     * @return Maximum font size in pixels
     */
    fun getMaxFontSize(): Float = maxFontSize

    // ==================== Interactive Mode API ====================

    /**
     * Set whether the terminal is interactive.
     *
     * When interactive is false:
     * - Touch events are not processed (scrolling, pinch-to-zoom disabled)
     * - The terminal becomes a read-only display
     *
     * Use this for preview/thumbnail modes where user interaction is not desired.
     *
     * @param interactive true to enable interaction, false to disable
     */
    fun setInteractive(interactive: Boolean) {
        if (this.isInteractive != interactive) {
            Log.d(TAG, "setInteractive: $interactive")
            this.isInteractive = interactive
        }
    }

    /**
     * Check if the terminal is interactive.
     *
     * @return true if touch events are processed, false if disabled
     */
    fun isInteractive(): Boolean = isInteractive

    /**
     * Animate the bottom offset to a target value.
     * Used internally for snap animations after gesture end.
     */
    private fun animateBottomOffsetTo(target: Float) {
        if (isBottomOffsetAnimating) {
            choreographer.removeFrameCallback(bottomOffsetAnimationCallback)
        }

        bottomOffsetAnimationStartTime = System.currentTimeMillis()
        bottomOffsetAnimationStartValue = bottomOffset
        bottomOffsetAnimationTargetValue = target.coerceIn(0f, maxBottomOffset)
        isBottomOffsetAnimating = true

        choreographer.postFrameCallback(bottomOffsetAnimationCallback)
    }

    /**
     * Reset two-finger gesture tracking state.
     */
    private fun resetTwoFingerGestureState() {
        twoFingerGestureActive = false
        twoFingerSwipeDetected = false
    }

    /**
     * Handle two-finger gesture tracking.
     */
    private fun handleTwoFingerGesture(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger touched - start tracking two-finger gesture
                if (event.pointerCount == 2) {
                    // Reset state for new gesture (allows rapid consecutive swipes)
                    resetTwoFingerGestureState()
                    twoFingerGestureActive = true
                    twoFingerStartTime = System.currentTimeMillis()
                    twoFingerStartX1 = event.getX(0)
                    twoFingerStartY1 = event.getY(0)
                    twoFingerStartX2 = event.getX(1)
                    twoFingerStartY2 = event.getY(1)

                    // Initialize VelocityTracker for this gesture
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain()
                    velocityTracker?.addMovement(event)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (twoFingerGestureActive && event.pointerCount == 2 && !twoFingerSwipeDetected) {
                    // Add movement to velocity tracker
                    velocityTracker?.addMovement(event)
                    // Check for swipe gesture during movement
                    checkTwoFingerSwipe(event)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // When one finger lifts while we have 2 fingers, finalize two-finger gesture
                if (event.pointerCount == 2 && twoFingerGestureActive) {
                    finalizeTwoFingerGesture(event)
                }
                // Recycle velocity tracker
                velocityTracker?.recycle()
                velocityTracker = null
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Clean up velocity tracker on gesture end
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
    }

    /**
     * Check if the current movement constitutes a two-finger swipe.
     * Uses VelocityTracker for reliable velocity-based detection.
     */
    private fun checkTwoFingerSwipe(event: MotionEvent) {
        val tracker = velocityTracker ?: return

        // Calculate movement of both fingers
        val dx1 = event.getX(0) - twoFingerStartX1
        val dy1 = event.getY(0) - twoFingerStartY1
        val dx2 = event.getX(1) - twoFingerStartX2
        val dy2 = event.getY(1) - twoFingerStartY2

        // Check minimum movement (touch slop) before processing
        val avgDx = (dx1 + dx2) / 2f
        val avgDy = (dy1 + dy2) / 2f
        if (abs(avgDy) < touchSlop) {
            return  // Not enough movement yet - don't log, too noisy
        }

        // Check if fingers are roughly parallel (angle check like Almeros ShoveGestureDetector)
        // Calculate angle between the line connecting fingers
        val fingerDx = event.getX(1) - event.getX(0)
        val fingerDy = event.getY(1) - event.getY(0)
        val fingerAngle = Math.toDegrees(kotlin.math.atan2(fingerDy.toDouble(), fingerDx.toDouble()))
        val normalizedAngle = abs(fingerAngle) % 180
        // Fingers should be roughly horizontal (within MAX_ANGLE degrees of horizontal)
        val isParallel = normalizedAngle < TWO_FINGER_SWIPE_MAX_ANGLE || normalizedAngle > (180 - TWO_FINGER_SWIPE_MAX_ANGLE)
        if (!isParallel) {
            Log.d(TAG, "SWIPE REJECTED: Fingers not parallel. Angle: ${normalizedAngle.toInt()}° (need <$TWO_FINGER_SWIPE_MAX_ANGLE° or >${180 - TWO_FINGER_SWIPE_MAX_ANGLE}°)")
            return
        }

        // Check if both fingers are moving in the same vertical direction
        // Allow one finger to be stationary, but not moving opposite
        if (dy1 * dy2 < 0 && abs(dy1) > touchSlop && abs(dy2) > touchSlop) {
            Log.d(TAG, "SWIPE REJECTED: Opposite directions. dy1=${dy1.toInt()}, dy2=${dy2.toInt()}")
            return
        }

        // Check if movement is primarily vertical
        val absAvgDx = abs(avgDx)
        val absAvgDy = abs(avgDy)
        if (absAvgDx > 0 && absAvgDy / absAvgDx < TWO_FINGER_DIRECTION_RATIO) {
            Log.d(TAG, "SWIPE REJECTED: Not vertical enough. Ratio: ${(absAvgDy / absAvgDx).format(2)} (need >$TWO_FINGER_DIRECTION_RATIO)")
            return
        }

        // Compute velocity (pixels per second)
        tracker.computeCurrentVelocity(1000)
        val velocityY1 = tracker.getYVelocity(event.getPointerId(0))
        val velocityY2 = tracker.getYVelocity(event.getPointerId(1))
        val avgVelocityY = (velocityY1 + velocityY2) / 2f

        // Check if velocity exceeds threshold
        if (abs(avgVelocityY) < TWO_FINGER_SWIPE_MIN_VELOCITY) {
            Log.d(TAG, "SWIPE REJECTED: Too slow. Velocity: ${abs(avgVelocityY).toInt()} px/s (need >$TWO_FINGER_SWIPE_MIN_VELOCITY)")
            return
        }

        // Swipe detected!
        twoFingerSwipeDetected = true
        Log.d(TAG, "✓ SWIPE DETECTED: velocity=${avgVelocityY.toInt()} px/s, angle=${normalizedAngle.toInt()}°, ratio=${if (absAvgDx > 0) (absAvgDy / absAvgDx).format(2) else "∞"}")

        if (avgVelocityY < 0) {
            // Swipe up (negative velocity = up in Android coordinates)
            Log.d(TAG, "Two-finger swipe UP")
            startSweepEffect(SWEEP_UP)
            eventListener?.onTwoFingerSwipeUp()
        } else {
            // Swipe down (positive velocity = down)
            Log.d(TAG, "Two-finger swipe DOWN")
            startSweepEffect(SWEEP_DOWN)
            eventListener?.onTwoFingerSwipeDown()
        }
    }

    private fun Float.format(digits: Int) = "%.${digits}f".format(this)

    /**
     * Finalize two-finger gesture when one finger lifts.
     * Check for double-tap pattern.
     */
    private fun finalizeTwoFingerGesture(event: MotionEvent) {
        if (!twoFingerGestureActive) return

        val elapsedTime = System.currentTimeMillis() - twoFingerStartTime

        // If no swipe was detected and gesture was quick, check for tap
        if (!twoFingerSwipeDetected && elapsedTime < TWO_FINGER_TAP_MAX_TIME_MS) {
            // Calculate total movement for both fingers
            val dx1 = abs(event.getX(0) - twoFingerStartX1)
            val dy1 = abs(event.getY(0) - twoFingerStartY1)
            val dx2 = abs(event.getX(1) - twoFingerStartX2)
            val dy2 = abs(event.getY(1) - twoFingerStartY2)

            val maxMovement = maxOf(dx1, dy1, dx2, dy2)

            if (maxMovement < twoFingerTapMaxDistancePx) {
                // This is a valid two-finger tap
                val currentTime = System.currentTimeMillis()

                if (currentTime - lastTwoFingerTapTime < TWO_FINGER_DOUBLE_TAP_TIMEOUT_MS) {
                    // Double tap detected!
                    Log.d(TAG, "Two-finger DOUBLE TAP detected")
                    eventListener?.onTwoFingerDoubleTap()
                    lastTwoFingerTapTime = 0L  // Reset to avoid triple-tap detection
                } else {
                    // First tap, wait for second
                    lastTwoFingerTapTime = currentTime
                }
            }
        }

        resetTwoFingerGestureState()
    }

    // ==================== Screenshot API ====================

    /**
     * Capture a screenshot of the terminal surface.
     *
     * This uses PixelCopy (API 24+) to properly capture the hardware-accelerated
     * OpenGL surface content. This is necessary because GLSurfaceView renders
     * to a separate surface that cannot be captured by normal window-based
     * screenshot methods.
     *
     * @return Bitmap of the current terminal content, or null if capture failed
     */
    suspend fun captureScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "PixelCopy not available on API < 24")
            return null
        }

        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            val result = suspendCoroutine<Int> { continuation ->
                val srcRect = Rect(0, 0, width, height)

                PixelCopy.request(
                    this,  // SurfaceView (GLSurfaceView extends SurfaceView)
                    srcRect,
                    bitmap,
                    { copyResult -> continuation.resume(copyResult) },
                    Handler(Looper.getMainLooper())
                )
            }

            if (result == PixelCopy.SUCCESS) {
                Log.d(TAG, "Screenshot captured: ${width}x${height}")
                bitmap
            } else {
                Log.w(TAG, "PixelCopy failed with result: $result")
                bitmap.recycle()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture screenshot", e)
            null
        }
    }
}
