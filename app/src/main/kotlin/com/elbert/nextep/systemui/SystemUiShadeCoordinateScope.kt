package com.elbert.nextep.systemui

import android.annotation.SuppressLint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import com.elbert.nextep.launcher.TouchCoordinateMapper
import com.elbert.nextep.logging.NeXtepLog
import com.elbert.nextep.xposed.HookGuard
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Inverse-maps raw screen MotionEvent coordinates and View screen/window coordinates
 * for callers inside a transformed NotificationShadeWindowView so HyperOS Notification Center
 * and Control Center touch hit-testing, scrolling, and animations remain aligned.
 */
object SystemUiShadeCoordinateScope {
    private val rawTouchTransform = ThreadLocal<TouchCoordinateMapper.Transform?>()
    private val rawDepth = ThreadLocal<Int>()
    private val locationDepth = ThreadLocal<Int>()

    private val shadeCallerClasses = listOf(
        "com.android.systemui.SwipeHelper",
        "com.android.systemui.SwipeHelper\$1",
        "com.android.systemui.controlcenter.shade.ControlCenterHeaderExpandController",
        "com.android.systemui.controlcenter.shade.NotificationHeaderExpandController",
        "com.android.systemui.qs.brightness.MiuiBrightnessMirrorController",
        "com.android.systemui.shade.NotificationPanelViewController",
        "com.android.systemui.shade.NotificationPanelViewController\$TouchHandler",
        "com.android.systemui.shade.NotificationShadeWindowView",
        "com.android.systemui.shade.NotificationShadeWindowViewController",
        "com.android.systemui.statusbar.notification.modal.ModalWindowView",
        "com.android.systemui.statusbar.notification.stack.MiuiNotificationSwipeHelper",
        "com.android.systemui.statusbar.notification.stack.MiuiNotificationSwipeHelper\$1",
        "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout",
        "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController\$9",
        "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutInjector",
        "com.android.systemui.statusbar.notification.stack.NotificationSwipeHelper",
        "com.android.systemui.statusbar.notification.stack.NotificationSwipeHelper\$1",
        "com.miui.systemui.controlcenter.container.ControlCenterEventHandlerImpl",
    )

    fun <T> withRawTouchTransform(
        transform: TouchCoordinateMapper.Transform?,
        block: () -> T,
    ): T {
        if (transform == null) return block()
        val previous = rawTouchTransform.get()
        rawTouchTransform.set(transform)
        return try {
            block()
        } finally {
            if (previous == null) rawTouchTransform.remove() else rawTouchTransform.set(previous)
        }
    }

    fun install(module: XposedModule, classLoader: ClassLoader) {
        installMotionEventRawHooks(module)
        installViewCoordinateHooks(module)
        deoptimizeShadeCallers(module, classLoader)
    }

    private fun installMotionEventRawHooks(module: XposedModule) {
        val rawTargets = listOf(
            Triple("getRawX", emptyArray<Class<*>>(), true),
            Triple("getRawX", arrayOf<Class<*>>(Int::class.javaPrimitiveType!!), true),
            Triple("getRawY", emptyArray<Class<*>>(), false),
            Triple("getRawY", arrayOf<Class<*>>(Int::class.javaPrimitiveType!!), false),
        )
        var installed = 0
        for ((name, params, isXAxis) in rawTargets) {
            HookGuard.run("shade_raw_coord_$name") {
                val method = runCatching {
                    MotionEvent::class.java.getDeclaredMethod(name, *params)
                }.getOrNull() ?: return@run
                method.isAccessible = true
                runCatching { module.deoptimize(method) }
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? =
                        interceptRawGetter(chain, isXAxis)
                })
                installed++
            }
        }
        NeXtepLog.info("shade_raw_coord", "Installed MotionEvent raw coordinate hooks=$installed")
    }

    @SuppressLint("PrivateApi")
    private fun installViewCoordinateHooks(module: XposedModule) {
        val viewTargets = listOf(
            Triple("getLocationOnScreen", arrayOf<Class<*>>(IntArray::class.java), false),
            Triple("getLocationInWindow", arrayOf<Class<*>>(IntArray::class.java), false),
            Triple("getBoundsOnScreen", arrayOf<Class<*>>(Rect::class.java), true),
            Triple(
                "getBoundsOnScreen",
                arrayOf<Class<*>>(Rect::class.java, Boolean::class.javaPrimitiveType!!),
                true,
            ),
        )
        var installed = 0
        for ((name, params, isBounds) in viewTargets) {
            HookGuard.run("shade_view_coord_$name") {
                val method = runCatching {
                    View::class.java.getDeclaredMethod(name, *params)
                }.getOrNull() ?: return@run
                method.isAccessible = true
                runCatching { module.deoptimize(method) }
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? =
                        interceptViewLocationOrBounds(chain, isBounds)
                })
                installed++
            }
        }
        NeXtepLog.info("shade_view_coord", "Installed View screen/window coordinate hooks=$installed")
    }

    private fun deoptimizeShadeCallers(module: XposedModule, classLoader: ClassLoader) {
        HookGuard.run("shade_deoptimize_callers") {
            var methodCount = 0
            for (className in shadeCallerClasses) {
                val clazz = runCatching { classLoader.loadClass(className) }.getOrNull() ?: continue
                for (method in clazz.declaredMethods) {
                    if (Modifier.isAbstract(method.modifiers)) continue
                    if (deoptimizeMethod(module, method)) {
                        methodCount++
                    }
                }
            }
            NeXtepLog.info("shade_deoptimize_callers", "Deoptimized SystemUI shade methods=$methodCount")
        }
    }

    private fun deoptimizeMethod(module: XposedModule, method: Method): Boolean =
        runCatching {
            method.isAccessible = true
            module.deoptimize(method)
            true
        }.getOrDefault(false)

    private fun interceptRawGetter(chain: XposedInterface.Chain, isXAxis: Boolean): Any? {
        val nextDepth = (rawDepth.get() ?: 0) + 1
        rawDepth.set(nextDepth)
        return try {
            val result = chain.proceed()
            if (nextDepth != 1) return result
            val transform = rawTouchTransform.get() ?: return result
            val raw = result as? Float ?: return result
            val scale = if (isXAxis) transform.scaleX else transform.scaleY
            val translation = if (isXAxis) transform.translationX else transform.translationY
            SystemUiShadeTransformPolicy.inverseMapRawCoordinate(raw, scale, translation)
        } finally {
            if (nextDepth <= 1) rawDepth.remove() else rawDepth.set(nextDepth - 1)
        }
    }

    private fun interceptViewLocationOrBounds(chain: XposedInterface.Chain, isBounds: Boolean): Any? {
        val nextDepth = (locationDepth.get() ?: 0) + 1
        locationDepth.set(nextDepth)
        return try {
            val result = chain.proceed()
            if (nextDepth != 1) return result
            val view = chain.thisObject as? View ?: return result
            if (isBounds) {
                val bounds = chain.getArg(0) as? Rect ?: return result
                HookGuard.run("shade_bounds_on_screen_apply") {
                    SystemUiRootTransformController.mapBoundsToContent(view, bounds)
                }
            } else {
                val location = chain.getArg(0) as? IntArray ?: return result
                HookGuard.run("shade_location_apply") {
                    SystemUiRootTransformController.mapLocationToContent(view, location)
                }
            }
            result
        } finally {
            if (nextDepth <= 1) locationDepth.remove() else locationDepth.set(nextDepth - 1)
        }
    }
}
