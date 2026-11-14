package com.elbert.nextep.workspace

import android.content.pm.ActivityInfo
import kotlin.math.abs

/**
 * Axis-aligned screen region in physical Display 0 coordinates where HyperOS/ColorOS system
 * back-gesture interception should yield to the rotated/top landscape pane.
 */
data class RotatedBackRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val isEmpty: Boolean
        get() = right <= left || bottom <= top

    fun contains(x: Float, y: Float): Boolean =
        !isEmpty && x >= left.toFloat() && x <= right.toFloat() && y >= top.toFloat() && y <= bottom.toFloat()

    fun encode(): String = "$left,$top,$right,$bottom"

    companion object {
        fun decode(raw: String?): RotatedBackRegion? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.trim().split(',')
            if (parts.size < 4) return null
            val left = parts[0].trim().toIntOrNull() ?: return null
            val top = parts[1].trim().toIntOrNull() ?: return null
            val right = parts[2].trim().toIntOrNull() ?: return null
            val bottom = parts[3].trim().toIntOrNull() ?: return null
            val region = RotatedBackRegion(left, top, right, bottom)
            return if (region.isEmpty) null else region
        }
    }
}

/**
 * Pure geometry, orientation hysteresis, touch coordinate mapping, and edge-back detection policy
 * for [RotatedMainPaneHost].
 */
object RotatedPaneTransformPolicy {
    const val ROTATED_MAIN_DISPLAY_NAME: String = "NeXtep-rotated-main"
    private const val DEGREE_LEFT = 90
    private const val DEGREE_RIGHT = -90

    /**
     * Computes initial TextureView rotation degree from the requested activity orientation.
     * Reverse landscape prefers `-90` (bottom-edge left); standard/sensor landscape defaults to `90`.
     */
    fun initialDegreeForOrientation(requestedOrientation: Int): Int =
        if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
            DEGREE_RIGHT
        } else {
            DEGREE_LEFT
        }

    /**
     * Resolves updated rotation degree from an Android sensor orientation (`0..359` or `-1`
     * when unknown), applying angular hysteresis around horizontal tilt thresholds so slight
     * hand jitter does not flicker the surface.
     */
    fun resolveSensorDegree(sensorOrientationDeg: Int, currentDegree: Int): Int {
        if (sensorOrientationDeg < 0) return currentDegree
        val normalized = ((sensorOrientationDeg % 360) + 360) % 360
        return when {
            normalized in 215..325 -> DEGREE_LEFT
            normalized in 35..145 -> DEGREE_RIGHT
            else -> currentDegree
        }
    }

    /**
     * Maps local host view coordinates `(localX, localY)` in a pane of `(hostWidth, hostHeight)`
     * into virtual display coordinates `(virtualX, virtualY)` where the virtual display has buffer
     * `(displayWidth, displayHeight)` = `(hostHeight, hostWidth)` (or scaled proportionately).
     */
    fun mapLocalTouchToVirtualDisplay(
        localX: Float,
        localY: Float,
        hostWidth: Int,
        hostHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
        degree: Int,
    ): Pair<Float, Float> {
        if (hostWidth <= 0 || hostHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) {
            return 0f to 0f
        }
        val (rotatedX, rotatedY) = if (degree >= 0) {
            // 90° clockwise visual rotation: host top-left maps to virtual bottom-left
            localY to (hostWidth.toFloat() - localX)
        } else {
            // -90° counter-clockwise visual rotation: host top-left maps to virtual top-right
            (hostHeight.toFloat() - localY) to localX
        }
        val scaleX = displayWidth.toFloat() / hostHeight.toFloat()
        val scaleY = displayHeight.toFloat() / hostWidth.toFloat()
        val mappedX = (rotatedX * scaleX).coerceIn(0f, (displayWidth - 1).coerceAtLeast(0).toFloat())
        val mappedY = (rotatedY * scaleY).coerceIn(0f, (displayHeight - 1).coerceAtLeast(0).toFloat())
        return mappedX to mappedY
    }

    /**
     * Returns whether an initial `ACTION_DOWN` inside the host pane begins within the horizontal
     * edge zone eligible for rotated in-pane back gesture capture.
     */
    fun isEdgeDown(localX: Float, hostWidth: Int, edgeSlopPx: Float): Boolean {
        if (hostWidth <= 0 || edgeSlopPx <= 0f) return false
        return localX <= edgeSlopPx || localX >= (hostWidth.toFloat() - edgeSlopPx)
    }

    /**
     * Evaluates whether an edge-originated drag has moved far enough inward to trigger a back
     * key injection without accidentally swallowing vertical scrolling.
     */
    fun shouldTriggerEdgeBack(
        startX: Float,
        startY: Float,
        currentX: Float,
        currentY: Float,
        hostWidth: Int,
        edgeSlopPx: Float,
        triggerDistancePx: Float,
    ): Boolean {
        if (!isEdgeDown(startX, hostWidth, edgeSlopPx)) return false
        val startedLeft = startX <= edgeSlopPx
        val inwardDx = if (startedLeft) currentX - startX else startX - currentX
        val dy = abs(currentY - startY)
        return inwardDx >= triggerDistancePx && inwardDx > dy * 1.15f
    }

    /**
     * Computes physical Display 0 region for the rotated main pane with optional edge padding.
     */
    fun computeScreenBackRegion(
        paneLeftOnScreen: Int,
        paneTopOnScreen: Int,
        paneWidth: Int,
        paneHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): RotatedBackRegion? {
        if (paneWidth <= 0 || paneHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) return null
        val left = paneLeftOnScreen.coerceIn(0, screenWidth)
        val top = paneTopOnScreen.coerceIn(0, screenHeight)
        val right = (paneLeftOnScreen + paneWidth).coerceIn(left, screenWidth)
        val bottom = (paneTopOnScreen + paneHeight).coerceIn(top, screenHeight)
        val region = RotatedBackRegion(left, top, right, bottom)
        return if (region.isEmpty) null else region
    }
}
