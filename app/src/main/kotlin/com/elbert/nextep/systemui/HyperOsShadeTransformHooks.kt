package com.elbert.nextep.systemui

import android.annotation.SuppressLint
import android.graphics.Rect
import android.view.SurfaceControl
import android.view.View
import com.elbert.nextep.logging.NeXtepLog
import com.elbert.nextep.xposed.HookGuard
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * Integrates HyperOS 3 SystemUI shade/Control Center state transitions and hardware
 * SurfaceControl background blur cropping with [SystemUiRootTransformController].
 */
object HyperOsShadeTransformHooks {
    private val croppedSurfaces = WeakHashMap<SurfaceControl, Rect>()

    @Volatile
    private var lastKeyguardOrDozing = false

    @Volatile
    private var viewRootImplMethod: Method? = null

    @Volatile
    private var surfaceControlMethod: Method? = null

    @Volatile
    private var viewRootGetViewMethod: Method? = null

    @Volatile
    private var transactionCropMethod: Method? = null

    @SuppressLint("PrivateApi")
    fun discoverWindowManagerRoots(observe: (View) -> Unit) {
        HookGuard.run("hyperos_shade_discover_roots") {
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            val instance = wmgClass.getMethod("getInstance").invoke(null) ?: return@run
            val viewsField = wmgClass.getDeclaredField("mViews").apply { isAccessible = true }
            val lockField = runCatching {
                wmgClass.getDeclaredField("mLock").apply { isAccessible = true }
            }.getOrNull()
            val snapshot = lockField?.get(instance)?.let { lock ->
                synchronized(lock) {
                    (viewsField.get(instance) as? List<*>)?.filterIsInstance<View>()?.toList().orEmpty()
                }
            } ?: (viewsField.get(instance) as? List<*>)?.filterIsInstance<View>()?.toList().orEmpty()
            snapshot.forEach(observe)
        }
    }

    fun syncNotificationShadeSurfaceCrop(view: View, cropRect: Rect?) {
        HookGuard.run("hyperos_shade_surface_crop") {
            val viewRootImpl = resolveViewRootImpl(view) ?: return@run
            applySurfaceCrop(viewRootImpl, null, cropRect)
        }
    }

    fun applySurfaceCrop(viewRootImpl: Any?, surfaceControl: SurfaceControl?, cropRect: Rect?) {
        val sc = surfaceControl ?: resolveSurfaceControl(viewRootImpl) ?: return
        if (!sc.isValid) return
        if (cropRect == null) {
            val hadCrop = synchronized(croppedSurfaces) { croppedSurfaces.remove(sc) != null }
            if (!hadCrop) return
            SurfaceControl.Transaction().use { tx ->
                if (invokeSetCrop(tx, sc, Rect())) tx.apply()
            }
            return
        }
        synchronized(croppedSurfaces) {
            croppedSurfaces[sc] = Rect(cropRect)
        }
        SurfaceControl.Transaction().use { tx ->
            if (invokeSetCrop(tx, sc, cropRect)) tx.apply()
        }
    }

    fun install(module: XposedModule, classLoader: ClassLoader) {
        installNotificationShadeStateHook(module, classLoader)
        installControlCenterExpandHook(module, classLoader)
        installNotificationShadeWrapperHook(module, classLoader)
        installBlurUtilsHook(module, classLoader)
        SystemUiShadeCoordinateScope.install(module, classLoader)
    }

    private fun installNotificationShadeStateHook(module: XposedModule, classLoader: ClassLoader) {
        HookGuard.run("hyperos_shade_window_state") {
            val controllerClass = runCatching {
                classLoader.loadClass("com.android.systemui.shade.NotificationShadeWindowControllerImpl")
            }.getOrNull() ?: return@run
            val stateClass = runCatching {
                classLoader.loadClass("com.android.systemui.shade.NotificationShadeWindowState")
            }.getOrNull() ?: return@run
            val applyMethod = controllerClass.getDeclaredMethod("apply", stateClass).apply {
                isAccessible = true
            }
            val keyguardField = stateField(stateClass, "keyguardShowing")
            val bouncerField = stateField(stateClass, "bouncerShowing")
            val dozingField = stateField(stateClass, "dozing")
            val shadeOrQsField = stateField(stateClass, "shadeOrQsExpanded")
            val qsExpandedField = stateField(stateClass, "qsExpanded")
            val ccExpandedField = stateField(stateClass, "controlCenterExpanded")
            val panelVisibleField = stateField(stateClass, "panelVisible")

            runCatching { module.deoptimize(applyMethod) }
            module.hook(applyMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    HookGuard.run("hyperos_shade_window_state_after") {
                        val state = chain.args.firstOrNull() ?: return@run
                        val keyguardShowing = readBoolean(keyguardField, state)
                        val bouncerShowing = readBoolean(bouncerField, state)
                        val dozing = readBoolean(dozingField, state)
                        lastKeyguardOrDozing = keyguardShowing || bouncerShowing || dozing
                        val expanded = SystemUiShadeTransformPolicy.resolveHyperOsShadeExpanded(
                            keyguardShowing = keyguardShowing,
                            bouncerShowing = bouncerShowing,
                            dozing = dozing,
                            shadeOrQsExpanded = readBoolean(shadeOrQsField, state),
                            qsExpanded = readBoolean(qsExpandedField, state),
                            controlCenterExpanded = readBoolean(ccExpandedField, state),
                            panelVisible = readBoolean(panelVisibleField, state),
                        )
                        SystemUiRootTransformController.onHyperOsShadeExpanded(
                            expanded = expanded,
                            forceRestore = lastKeyguardOrDozing,
                        )
                    }
                    return result
                }
            })
            NeXtepLog.info("hyperos_shade_window_state", "Installed NotificationShadeWindowControllerImpl#apply hook")
        }
    }

    private fun installControlCenterExpandHook(module: XposedModule, classLoader: ClassLoader) {
        HookGuard.run("hyperos_control_center_expand") {
            val ccClass = runCatching {
                classLoader.loadClass("com.miui.systemui.controlcenter.ControlCenterImpl")
            }.getOrNull() ?: return@run
            val expandMethod = ccClass.getDeclaredMethod("expand", Boolean::class.javaPrimitiveType).apply {
                isAccessible = true
            }
            runCatching { module.deoptimize(expandMethod) }
            module.hook(expandMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    HookGuard.run("hyperos_control_center_expand_after") {
                        val expanded = (chain.args.firstOrNull() as? Boolean) ?: return@run
                        if (expanded && !lastKeyguardOrDozing) {
                            SystemUiRootTransformController.onHyperOsShadeExpanded(true)
                        }
                    }
                    return result
                }
            })
            NeXtepLog.info("hyperos_control_center_expand", "Installed ControlCenterImpl#expand hook")
        }
    }

    private fun installNotificationShadeWrapperHook(module: XposedModule, classLoader: ClassLoader) {
        HookGuard.run("hyperos_shade_wrapper_expand") {
            val wrapperClass = runCatching {
                classLoader.loadClass("com.miui.systemui.shade.NotificationShadeWrapper")
            }.getOrNull() ?: return@run
            val updateMethod = wrapperClass.getDeclaredMethod("updateExpanded").apply {
                isAccessible = true
            }
            val expandedField = stateField(wrapperClass, "expanded")
            runCatching { module.deoptimize(updateMethod) }
            module.hook(updateMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    HookGuard.run("hyperos_shade_wrapper_expand_after") {
                        val target = chain.thisObject ?: return@run
                        val expanded = readBoolean(expandedField, target)
                        if (expanded && !lastKeyguardOrDozing) {
                            SystemUiRootTransformController.onHyperOsShadeExpanded(true)
                        }
                    }
                    return result
                }
            })
            NeXtepLog.info("hyperos_shade_wrapper_expand", "Installed NotificationShadeWrapper#updateExpanded hook")
        }
    }

    private fun installBlurUtilsHook(module: XposedModule, classLoader: ClassLoader) {
        HookGuard.run("hyperos_shade_blur_crop") {
            val blurUtilsClass = runCatching {
                classLoader.loadClass("com.android.systemui.statusbar.BlurUtils")
            }.getOrNull() ?: return@run
            val viewRootImplClass = Class.forName("android.view.ViewRootImpl")
            val applyBlurMethod = blurUtilsClass.getDeclaredMethod(
                "applyBlur",
                viewRootImplClass,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            runCatching { module.deoptimize(applyBlurMethod) }
            module.hook(applyBlurMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    HookGuard.run("hyperos_shade_blur_crop_after") {
                        val viewRootImpl = chain.args.firstOrNull() ?: return@run
                        val rootView = resolveViewFromViewRootImpl(viewRootImpl) ?: return@run
                        if (!SystemUiRootTransformController.isNotificationShadeRoot(rootView)) return@run
                        val cropRect = if (SystemUiRootTransformController.isActive()) {
                            SystemUiRootTransformController.currentShadeCropRect()
                        } else {
                            null
                        }
                        applySurfaceCrop(viewRootImpl, null, cropRect)
                    }
                    return result
                }
            })
            NeXtepLog.info("hyperos_shade_blur_crop", "Installed BlurUtils#applyBlur hook")
        }
    }

    private fun resolveViewRootImpl(view: View): Any? {
        val method = viewRootImplMethod ?: runCatching {
            View::class.java.getMethod("getViewRootImpl").apply { isAccessible = true }
        }.getOrNull()?.also { viewRootImplMethod = it }
        return method?.invoke(view)
    }

    private fun resolveSurfaceControl(viewRootImpl: Any?): SurfaceControl? {
        if (viewRootImpl == null) return null
        val method = surfaceControlMethod ?: runCatching {
            viewRootImpl.javaClass.getMethod("getSurfaceControl").apply { isAccessible = true }
        }.getOrNull()?.also { surfaceControlMethod = it }
        return method?.invoke(viewRootImpl) as? SurfaceControl
    }

    private fun resolveViewFromViewRootImpl(viewRootImpl: Any?): View? {
        if (viewRootImpl == null) return null
        val method = viewRootGetViewMethod ?: runCatching {
            viewRootImpl.javaClass.getMethod("getView").apply { isAccessible = true }
        }.getOrNull()?.also { viewRootGetViewMethod = it }
        return method?.invoke(viewRootImpl) as? View
    }

    private fun stateField(clazz: Class<*>, name: String): Field? =
        runCatching { clazz.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()

    private fun readBoolean(field: Field?, target: Any): Boolean =
        field?.let { runCatching { it.getBoolean(target) }.getOrNull() } ?: false

    private fun invokeSetCrop(
        transaction: SurfaceControl.Transaction,
        surfaceControl: SurfaceControl,
        cropRect: Rect,
    ): Boolean {
        val method = transactionCropMethod ?: SurfaceControl.Transaction::class.java.methods
            .firstOrNull { candidate ->
                candidate.name in setOf("setCrop", "setWindowCrop") &&
                    candidate.parameterTypes.size == 2 &&
                    candidate.parameterTypes[0] == SurfaceControl::class.java &&
                    candidate.parameterTypes[1] == Rect::class.java
            }?.apply { isAccessible = true }
            ?.also { transactionCropMethod = it }
            ?: return false
        return runCatching {
            method.invoke(transaction, surfaceControl, cropRect)
            true
        }.getOrDefault(false)
    }
}
