package com.elbert.nextep.workspace

import android.content.pm.ActivityInfo

/**
 * Wire payload published by system_server when the main-pane task changes its requested
 * orientation or fullscreen status-bar visibility.
 */
data class WorkspaceSceneRequest(
    val taskId: Int,
    val orientation: Int,
    val timestampMs: Long,
    val canHold: Boolean,
    val fullscreen: Boolean,
) {
    val isLandscapeOrientation: Boolean
        get() = isLandscapeRequest(orientation)

    val isPortraitOrientation: Boolean
        get() = isPortraitRequest(orientation)

    fun isFresh(nowMs: Long, maxAgeMs: Long = DEFAULT_MAX_AGE_MS): Boolean =
        timestampMs > 0L && (nowMs - timestampMs) <= maxAgeMs

    fun canHoldRotation(nowMs: Long, holdWindowMs: Long = DEFAULT_HOLD_WINDOW_MS): Boolean =
        canHold && (nowMs - timestampMs) in 0L..holdWindowMs

    fun encode(): String = buildString {
        append(taskId)
        append(',')
        append(orientation)
        append(',')
        append(timestampMs)
        append(',')
        append(if (canHold) 1 else 0)
        append(',')
        append(if (fullscreen) 1 else 0)
    }

    companion object {
        const val DEFAULT_MAX_AGE_MS: Long = 3_000L
        const val DEFAULT_HOLD_WINDOW_MS: Long = 1_500L

        fun decode(raw: String?): WorkspaceSceneRequest? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.trim().split(',')
            if (parts.size < 5) return null
            val taskId = parts[0].trim().toIntOrNull() ?: return null
            if (taskId <= 0) return null
            val orientation = parts[1].trim().toIntOrNull() ?: return null
            val timestampMs = parts[2].trim().toLongOrNull() ?: return null
            val canHold = parts[3].trim() == "1"
            val fullscreen = parts[4].trim() == "1"
            return WorkspaceSceneRequest(
                taskId = taskId,
                orientation = orientation,
                timestampMs = timestampMs,
                canHold = canHold,
                fullscreen = fullscreen,
            )
        }

        fun isLandscapeRequest(orientation: Int): Boolean =
            orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
                orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE ||
                orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE ||
                orientation == ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE

        fun isPortraitRequest(orientation: Int): Boolean =
            orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ||
                orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT ||
                orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT ||
                orientation == ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
    }
}
