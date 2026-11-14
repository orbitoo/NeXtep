package com.elbert.nextep.systemui

import android.view.MotionEvent
import com.elbert.nextep.trigger.TriggerGesture

/**
 * Recognizes inward-and-upward diagonal swipes starting inside the bottom-left or bottom-right
 * corner band without consuming vertical home/recents or horizontal edge gestures.
 */
internal class BottomCornerGestureDetector(
    private val density: Float,
    private val preferSmallerEdgeBand: Boolean = true,
) {
    enum class Phase {
        IDLE,
        OBSERVING,
        ABANDONED,
        CONFIRMED,
    }

    private val edgeBandPx = (if (preferSmallerEdgeBand) 48f else 64f) * density
    private val backtrackLimitPx = 12f * density
    private val minTravelPx = 24f * density
    private val maxVerticalToHorizontalRatio = if (preferSmallerEdgeBand) 1.88f else 3.0f
    private val maxHorizontalToVerticalRatio = 2.75f

    private var tracking = false
    private var isLeftCorner = false
    private var startX = 0f
    private var startY = 0f
    private var startTimeMs = 0L
    private var trackedScreenWidth = 0
    private var trackedScreenHeight = 0
    private var trackedRotation = 0
    private var currentPhase = Phase.IDLE

    fun phase(): Phase = currentPhase

    fun reset() {
        tracking = false
        currentPhase = Phase.IDLE
    }

    fun observe(
        event: MotionEvent,
        screenWidth: Int,
        screenHeight: Int,
        displayRotation: Int,
        leftEnabled: Boolean,
        rightEnabled: Boolean,
    ): TriggerGesture? = observe(
        actionMasked = event.actionMasked,
        rawX = event.rawX,
        rawY = event.rawY,
        pointerCount = event.pointerCount,
        eventTimeMs = event.eventTime,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        displayRotation = displayRotation,
        leftEnabled = leftEnabled,
        rightEnabled = rightEnabled,
    )

    fun observe(
        actionMasked: Int,
        rawX: Float,
        rawY: Float,
        pointerCount: Int,
        eventTimeMs: Long,
        screenWidth: Int,
        screenHeight: Int,
        displayRotation: Int,
        leftEnabled: Boolean,
        rightEnabled: Boolean,
    ): TriggerGesture? {
        if ((!leftEnabled && !rightEnabled) ||
            pointerCount != 1 ||
            !rawX.isFinite() ||
            !rawY.isFinite() ||
            screenWidth <= 0 ||
            screenHeight <= 0 ||
            rawX < 0f ||
            rawX > screenWidth ||
            rawY < 0f ||
            rawY > screenHeight
        ) {
            return finish(abandoned = tracking)
        }

        if (actionMasked == MotionEvent.ACTION_DOWN) {
            val cornerXBand = minOf(edgeBandPx, screenWidth / 4f)
            val cornerYBand = minOf(edgeBandPx, screenHeight / 4f)
            val inBottomBand = rawY >= screenHeight - cornerYBand
            val hitsLeft = leftEnabled && rawX <= cornerXBand
            val hitsRight = rightEnabled && rawX >= screenWidth - cornerXBand
            if (!inBottomBand || (!hitsLeft && !hitsRight)) {
                tracking = false
                currentPhase = Phase.IDLE
                return null
            }
            tracking = true
            isLeftCorner = hitsLeft && (!hitsRight || rawX <= screenWidth / 2f)
            startX = rawX
            startY = rawY
            startTimeMs = eventTimeMs
            trackedScreenWidth = screenWidth
            trackedScreenHeight = screenHeight
            trackedRotation = displayRotation
            currentPhase = Phase.OBSERVING
            return null
        }

        if (!tracking) {
            currentPhase = Phase.IDLE
            return null
        }

        if (screenWidth != trackedScreenWidth ||
            screenHeight != trackedScreenHeight ||
            displayRotation != trackedRotation
        ) {
            return finish(abandoned = true)
        }

        val elapsed = eventTimeMs - startTimeMs
        if (elapsed !in 0L..MAX_DURATION_MS) {
            return finish(abandoned = true)
        }

        if (actionMasked != MotionEvent.ACTION_MOVE && actionMasked != MotionEvent.ACTION_UP) {
            return finish(abandoned = true)
        }

        val inwardDx = if (isLeftCorner) rawX - startX else startX - rawX
        val upwardDy = startY - rawY
        if (inwardDx < -backtrackLimitPx || upwardDy < -backtrackLimitPx) {
            return finish(abandoned = true)
        }

        if (inwardDx >= minTravelPx &&
            upwardDy >= minTravelPx &&
            inwardDx <= maxHorizontalToVerticalRatio * upwardDy &&
            upwardDy <= maxVerticalToHorizontalRatio * inwardDx
        ) {
            tracking = false
            currentPhase = Phase.CONFIRMED
            return if (isLeftCorner) TriggerGesture.BOTTOM_LEFT else TriggerGesture.BOTTOM_RIGHT
        }

        if (actionMasked == MotionEvent.ACTION_UP) {
            return finish(abandoned = true)
        }

        return null
    }

    private fun finish(abandoned: Boolean): TriggerGesture? {
        tracking = false
        currentPhase = if (abandoned) Phase.ABANDONED else Phase.IDLE
        return null
    }

    private companion object {
        const val MAX_DURATION_MS = 1200L
    }
}
