package com.elbert.nextep.systemui

import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Recognizes deliberate two-finger vertical swipes (up or down) outside the top status/Dynamic
 * Island band and bottom system navigation band.
 */
internal class TwoFingerSwipeGestureDetector(
    private val density: Float,
    statusBarHeightPx: Float = 0f,
) {
    enum class Direction {
        UP,
        DOWN,
    }

    private val topExcludePx = maxOf(statusBarHeightPx, 64f * density)
    private val bottomExcludePx = 48f * density
    private val minTravelPx = 56f * density
    private val oppositeDriftTolerancePx = 12f * density
    private val spanDriftMinPx = 32f * density

    private var waitingSecondFinger = false
    private var trackingPair = false
    private var downTimeMs = 0L
    private var initX0 = 0f
    private var initY0 = 0f
    private var initX1 = 0f
    private var initY1 = 0f
    private var initSpan = 0f

    fun reset() {
        waitingSecondFinger = false
        trackingPair = false
    }

    fun observe(
        event: MotionEvent,
        screenWidth: Int,
        screenHeight: Int,
        allowed: Boolean = true,
    ): Direction? = observe(
        actionMasked = event.actionMasked,
        rawX0 = event.rawX,
        rawY0 = event.rawY,
        rawX1 = if (event.pointerCount > 1) event.getRawX(1) else Float.NaN,
        rawY1 = if (event.pointerCount > 1) event.getRawY(1) else Float.NaN,
        pointerCount = event.pointerCount,
        eventTimeMs = event.eventTime,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        allowed = allowed,
    )

    fun observe(
        actionMasked: Int,
        rawX0: Float,
        rawY0: Float,
        rawX1: Float,
        rawY1: Float,
        pointerCount: Int,
        eventTimeMs: Long,
        screenWidth: Int,
        screenHeight: Int,
        allowed: Boolean = true,
    ): Direction? {
        if (!allowed ||
            !rawX0.isFinite() ||
            !rawY0.isFinite() ||
            screenWidth <= 0 ||
            screenHeight <= 0 ||
            pointerCount !in 1..2
        ) {
            reset()
            return null
        }

        if (actionMasked == MotionEvent.ACTION_DOWN) {
            reset()
            val validStart = pointerCount == 1 && isInVerticalSafeBand(rawY0, screenHeight)
            waitingSecondFinger = validStart
            downTimeMs = eventTimeMs
            return null
        }

        if (!waitingSecondFinger) return null

        val elapsed = eventTimeMs - downTimeMs
        if (elapsed !in 0L..MAX_GESTURE_MS) {
            reset()
            return null
        }

        when (actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (trackingPair ||
                    pointerCount != 2 ||
                    elapsed > SECOND_FINGER_TIMEOUT_MS ||
                    !rawX1.isFinite() ||
                    !rawY1.isFinite() ||
                    !isInVerticalSafeBand(rawY1, screenHeight)
                ) {
                    reset()
                    return null
                }
                trackingPair = true
                initX0 = rawX0
                initY0 = rawY0
                initX1 = rawX1
                initY1 = rawY1
                initSpan = hypot(rawX1 - rawX0, rawY1 - rawY0)
                return null
            }

            MotionEvent.ACTION_MOVE -> {
                if (!trackingPair) {
                    if (pointerCount != 1 || elapsed > SECOND_FINGER_TIMEOUT_MS) {
                        reset()
                    }
                    return null
                }
                if (pointerCount != 2 || !rawX1.isFinite() || !rawY1.isFinite()) {
                    reset()
                    return null
                }
                val dy0 = rawY0 - initY0
                val dy1 = rawY1 - initY1
                if ((dy0 > oppositeDriftTolerancePx && dy1 < -oppositeDriftTolerancePx) ||
                    (dy0 < -oppositeDriftTolerancePx && dy1 > oppositeDriftTolerancePx)
                ) {
                    reset()
                    return null
                }
                val currentSpan = hypot(rawX1 - rawX0, rawY1 - rawY0)
                val maxSpanChange = maxOf(spanDriftMinPx, initSpan * 0.35f)
                if (abs(currentSpan - initSpan) > maxSpanChange) {
                    reset()
                    return null
                }
                if (minOf(abs(dy0), abs(dy1)) >= minTravelPx &&
                    (dy0 > 0f) == (dy1 > 0f) &&
                    abs(rawX0 - initX0) < abs(dy0) &&
                    abs(rawX1 - initX1) < abs(dy1)
                ) {
                    reset()
                    return if (dy0 > 0f) Direction.DOWN else Direction.UP
                }
                return null
            }

            else -> {
                reset()
                return null
            }
        }
    }

    private fun isInVerticalSafeBand(y: Float, screenHeight: Int): Boolean {
        val height = screenHeight.toFloat()
        if (height <= topExcludePx + bottomExcludePx) return false
        return y >= topExcludePx && y <= height - bottomExcludePx
    }

    private companion object {
        const val SECOND_FINGER_TIMEOUT_MS = 250L
        const val MAX_GESTURE_MS = 1000L
    }
}
