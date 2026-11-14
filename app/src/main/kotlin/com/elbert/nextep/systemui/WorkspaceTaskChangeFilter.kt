package com.elbert.nextep.systemui

import java.util.concurrent.ConcurrentHashMap

/**
 * Filters non-structural WM Shell task updates (such as media progress, titles,
 * or timestamps) so idle backoff is not continuously reset by unchanged tasks.
 */
internal class WorkspaceTaskChangeFilter {
    data class TaskSignature(
        val displayId: Int,
        val boundsLeft: Int,
        val boundsTop: Int,
        val boundsRight: Int,
        val boundsBottom: Int,
        val windowingMode: Int,
        val resizeMode: Int?,
        val densityDpi: Int,
        val tokenIdentity: Int,
        val vendorWindowed: Boolean,
        val isVisible: Boolean,
        val isFocused: Boolean,
        val activityType: Int,
        val topComponent: String?,
    )

    private val knownSignatures = ConcurrentHashMap<Int, TaskSignature>()

    fun onTaskAppeared(taskId: Int, signature: TaskSignature?): Boolean {
        if (signature != null) knownSignatures[taskId] = signature
        return true
    }

    fun onTaskInfoChanged(taskId: Int, signature: TaskSignature?): Boolean =
        onTaskInfoChangedReason(taskId, signature) != null

    fun onTaskInfoChangedReason(taskId: Int, signature: TaskSignature?): String? {
        if (signature == null) return "unreadable"
        val previous = knownSignatures.put(taskId, signature) ?: return "initial"
        if (previous == signature) return null
        return buildList {
            if (previous.displayId != signature.displayId) add("display:${previous.displayId}->${signature.displayId}")
            if (previous.boundsLeft != signature.boundsLeft ||
                previous.boundsTop != signature.boundsTop ||
                previous.boundsRight != signature.boundsRight ||
                previous.boundsBottom != signature.boundsBottom
            ) add("bounds")
            if (previous.windowingMode != signature.windowingMode) add("mode:${previous.windowingMode}->${signature.windowingMode}")
            if (previous.resizeMode != signature.resizeMode) add("resize:${previous.resizeMode}->${signature.resizeMode}")
            if (previous.densityDpi != signature.densityDpi) add("dpi:${previous.densityDpi}->${signature.densityDpi}")
            if (previous.tokenIdentity != signature.tokenIdentity) add("token")
            if (previous.vendorWindowed != signature.vendorWindowed) add("vendor:${previous.vendorWindowed}->${signature.vendorWindowed}")
            if (previous.isVisible != signature.isVisible) add("visible:${previous.isVisible}->${signature.isVisible}")
            if (previous.isFocused != signature.isFocused) add("focused:${previous.isFocused}->${signature.isFocused}")
            if (previous.activityType != signature.activityType) add("type:${previous.activityType}->${signature.activityType}")
            if (previous.topComponent != signature.topComponent) add("top:${previous.topComponent}->${signature.topComponent}")
        }.joinToString(",")
    }

    fun onTaskVanished(taskId: Int): Boolean {
        knownSignatures.remove(taskId)
        return true
    }
}
