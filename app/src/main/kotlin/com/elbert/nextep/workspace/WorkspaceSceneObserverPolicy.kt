package com.elbert.nextep.workspace

import android.view.WindowManager

/**
 * Pure policy helpers for [com.elbert.nextep.systemserver.WorkspaceSceneObserverHook].
 */
object WorkspaceSceneObserverPolicy {
    // WindowInsets.Type.statusBars() == 1 shl 0 in Android API 30+.
    const val STATUS_BAR_TYPE_MASK: Int = 1

    @Suppress("DEPRECATION")
    const val FLAG_FULLSCREEN: Int = WindowManager.LayoutParams.FLAG_FULLSCREEN

    fun isStatusBarHidden(requestedVisibleTypes: Int?, windowLayoutFlags: Int): Boolean {
        if (requestedVisibleTypes != null && (requestedVisibleTypes and STATUS_BAR_TYPE_MASK) == 0) {
            return true
        }
        return (windowLayoutFlags and FLAG_FULLSCREEN) != 0
    }

    fun shouldHoldDisplayRotation(
        mode: LandscapeVideoMode,
        request: WorkspaceSceneRequest?,
        nowMs: Long,
        lastOrientation: Int? = null,
        currentRotation: Int? = null,
        portraitRotation: Int? = null,
    ): Boolean {
        if (mode != LandscapeVideoMode.TURNED && mode != LandscapeVideoMode.TOP) return false
        if (lastOrientation != null && !WorkspaceSceneRequest.isLandscapeRequest(lastOrientation)) {
            return false
        }
        if (currentRotation != null && portraitRotation != null && currentRotation != portraitRotation) {
            return false
        }
        val current = request ?: return false
        return current.isLandscapeOrientation && current.canHoldRotation(nowMs)
    }

    fun rotationHoldDelayMs(isWideViewport: Boolean): Long =
        if (isWideViewport) 1_800L else 3_200L
}
