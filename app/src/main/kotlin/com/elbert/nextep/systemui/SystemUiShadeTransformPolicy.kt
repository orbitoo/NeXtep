package com.elbert.nextep.systemui

import android.graphics.Rect
import kotlin.math.roundToInt

internal object SystemUiShadeTransformPolicy {
    /**
     * Only scale NotificationShadeWindowView for user-visible shade or Control Center panels
     * while the device is unlocked and awake; lockscreen/bouncer/AOD must stay full-screen.
     */
    fun resolveHyperOsShadeExpanded(
        keyguardShowing: Boolean,
        bouncerShowing: Boolean,
        dozing: Boolean,
        shadeOrQsExpanded: Boolean,
        qsExpanded: Boolean,
        controlCenterExpanded: Boolean,
        panelVisible: Boolean,
    ): Boolean {
        if (keyguardShowing || bouncerShowing || dozing) return false
        return shadeOrQsExpanded || qsExpanded || controlCenterExpanded || panelVisible
    }

    /**
     * Compute the screen-space SurfaceControl crop matching the active workspace main pane,
     * keeping fullscreen window blur and dim strictly inside the content area.
     */
    fun resolveShadeSurfaceCropRect(
        contentLeft: Int,
        contentTop: Int,
        contentRight: Int,
        contentBottom: Int,
    ): Rect? {
        if (contentRight <= contentLeft || contentBottom <= contentTop) return null
        return Rect(contentLeft, contentTop, contentRight, contentBottom)
    }

    /**
     * Inverse-maps a physical screen coordinate back into the unscaled shade root's local space.
     */
    fun inverseMapRawCoordinate(value: Float, scale: Float, translation: Float): Float {
        if (!value.isFinite() || !scale.isFinite() || scale <= 0f || !translation.isFinite()) {
            return value
        }
        return (value - translation) / scale
    }

    /**
     * Inverse-maps an integer screen/window coordinate back into unscaled shade root space.
     */
    fun inverseMapRoundedCoordinate(value: Int, scale: Float, translation: Float): Int {
        if (!scale.isFinite() || scale <= 0f || !translation.isFinite()) {
            return value
        }
        return ((value - translation) / scale).roundToInt()
    }

    /**
     * Inverse-maps screen-space rect bounds into `[left, top, right, bottom]` in unscaled shade space.
     */
    fun inverseMapScreenRect(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        scaleX: Float,
        scaleY: Float,
        translationX: Float,
        translationY: Float,
    ): IntArray {
        return intArrayOf(
            inverseMapRoundedCoordinate(left, scaleX, translationX),
            inverseMapRoundedCoordinate(top, scaleY, translationY),
            inverseMapRoundedCoordinate(right, scaleX, translationX),
            inverseMapRoundedCoordinate(bottom, scaleY, translationY),
        )
    }

    /**
     * Mutates [bounds] in-place from screen space into unscaled shade root space.
     */
    fun inverseMapRect(
        bounds: Rect,
        scaleX: Float,
        scaleY: Float,
        translationX: Float,
        translationY: Float,
    ) {
        val mapped = inverseMapScreenRect(
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            scaleX = scaleX,
            scaleY = scaleY,
            translationX = translationX,
            translationY = translationY,
        )
        bounds.set(mapped[0], mapped[1], mapped[2], mapped[3])
    }
}
