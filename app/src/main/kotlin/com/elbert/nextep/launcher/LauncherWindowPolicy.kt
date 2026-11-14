package com.elbert.nextep.launcher

import android.view.Display
import android.view.WindowManager

/** Pure window classification and dispatch-guard helpers for launcher and assistant hooks. */
internal object LauncherWindowPolicy {
    /** Identifies overlay panels belonging to the assistant screen across ColorOS and HyperOS. */
    fun isAssistantPanelWindow(
        displayId: Int?,
        type: Int?,
        title: String?,
        packageName: String?,
    ): Boolean {
        if (displayId != Display.DEFAULT_DISPLAY || type == null) return false
        if (type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW) return true
        // ColorOS 17 panels use TYPE_APPLICATION with the package name as title;
        // HyperOS 3 minus-one panels use TYPE_DRAWN_APPLICATION with "LauncherOverlayWindow:<pkg>".
        val isAppWindow = type == WindowManager.LayoutParams.TYPE_APPLICATION ||
            type == WindowManager.LayoutParams.TYPE_DRAWN_APPLICATION
        if (!isAppWindow || packageName.isNullOrEmpty()) return false
        return title == packageName || title == "LauncherOverlayWindow:$packageName"
    }

    /** Guards nested super.dispatchTouchEvent calls so touch coordinates map once per event. */
    inline fun <T> runOutermostDispatch(
        depth: ThreadLocal<Int>,
        proceed: () -> T,
        transformAndProceed: () -> T,
    ): T {
        val currentDepth = depth.get() ?: 0
        if (currentDepth > 0) {
            return proceed()
        }
        depth.set(currentDepth + 1)
        return try {
            transformAndProceed()
        } finally {
            depth.set(currentDepth)
        }
    }
}
