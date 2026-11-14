package com.elbert.nextep.workspace

import kotlin.math.roundToInt

/**
 * Pure geometry and touch-mapping policy for [LandscapeVideoMode.TOP].
 *
 * The pinned top window owns a horizontal virtual display rendered upright inside
 * the top control strip below the status-bar pass-through band.
 */
object TopPinnedTransformPolicy {
    const val TOP_PINNED_DISPLAY_NAME: String = "NeXtep-top-pinned"
    const val MIN_VIRTUAL_DISPLAY_SIDE_PX: Int = 320
    const val MAX_VIRTUAL_DISPLAY_SIDE_PX: Int = 4096

    data class VirtualDisplaySize(
        val width: Int,
        val height: Int,
    )

    fun computeVirtualDisplaySize(
        zoneWidthPx: Int,
        zoneHeightPx: Int,
        targetShortSidePx: Int,
    ): VirtualDisplaySize {
        val safeW = zoneWidthPx.coerceAtLeast(1)
        val safeH = zoneHeightPx.coerceAtLeast(1)
        val ratio = safeW.toFloat() / safeH.toFloat()

        var height = roundToEven(targetShortSidePx).coerceIn(MIN_VIRTUAL_DISPLAY_SIDE_PX, MAX_VIRTUAL_DISPLAY_SIDE_PX)
        var width = roundToEven((height * ratio).roundToInt()).coerceAtLeast(MIN_VIRTUAL_DISPLAY_SIDE_PX)
        if (width > MAX_VIRTUAL_DISPLAY_SIDE_PX) {
            width = MAX_VIRTUAL_DISPLAY_SIDE_PX
            height = roundToEven((width / ratio).roundToInt()).coerceIn(MIN_VIRTUAL_DISPLAY_SIDE_PX, MAX_VIRTUAL_DISPLAY_SIDE_PX)
        }
        return VirtualDisplaySize(width = width, height = height)
    }

    fun mapLocalTouchToVirtualDisplay(
        localX: Float,
        localY: Float,
        hostWidth: Int,
        hostHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
    ): Pair<Float, Float> {
        if (hostWidth <= 0 || hostHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) {
            return 0f to 0f
        }
        val vx = ((localX / hostWidth.toFloat()) * displayWidth.toFloat()).coerceIn(0f, (displayWidth - 1).toFloat())
        val vy = ((localY / hostHeight.toFloat()) * displayHeight.toFloat()).coerceIn(0f, (displayHeight - 1).toFloat())
        return vx to vy
    }

    fun isEdgeDown(localX: Float, hostWidth: Int, edgeSlopPx: Float): Boolean =
        RotatedPaneTransformPolicy.isEdgeDown(localX, hostWidth, edgeSlopPx)

    fun shouldTriggerEdgeBack(
        startX: Float,
        startY: Float,
        currentX: Float,
        currentY: Float,
        hostWidth: Int,
        edgeSlopPx: Float,
        triggerDistancePx: Float,
    ): Boolean = RotatedPaneTransformPolicy.shouldTriggerEdgeBack(
        startX = startX,
        startY = startY,
        currentX = currentX,
        currentY = currentY,
        hostWidth = hostWidth,
        edgeSlopPx = edgeSlopPx,
        triggerDistancePx = triggerDistancePx,
    )

    private fun roundToEven(value: Int): Int {
        val clamped = value.coerceAtLeast(2)
        return if (clamped % 2 == 0) clamped else clamped + 1
    }
}
