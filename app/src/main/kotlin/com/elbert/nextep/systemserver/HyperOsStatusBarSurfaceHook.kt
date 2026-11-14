package com.elbert.nextep.systemserver

import android.content.Context
import android.view.Display
import android.view.SurfaceControl
import android.view.WindowInsets
import android.view.WindowManager
import com.elbert.nextep.framework.HyperOsEnvironment
import com.elbert.nextep.logging.NeXtepLog
import com.elbert.nextep.workspace.SystemServerWorkspaceBridge
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HyperOS 3 physical status-bar suppression in `system_server`:
 * 1. Forces `MiuiStatusBarController#updateHomeBarAlpha` to `0f` while workspace is active.
 * 2. Hides / reparents `TYPE_STATUS_BAR` surface during `DisplayContent#prepareSurfaces` on display 0,
 *    restoring it when the workspace exits.
 * 3. Forces `DisplayPolicy#topAppHidesSystemBar` false for status bars while active so fullscreen
 *    in-pane video/game apps do not race the workspace status bar surface state.
 */
object HyperOsStatusBarSurfaceHook {
    private data class SavedPlacement(
        val surface: SurfaceControl,
        val nativeParent: SurfaceControl?,
        val savedAlpha: Float,
    )

    private val installed = AtomicBoolean(false)
    private val hookFailureLogged = AtomicBoolean(false)
    private val savedPlacements = ConcurrentHashMap<Any, SavedPlacement>()
    private val fieldCache = ConcurrentHashMap<String, Optional<Field>>()
    private val methodCache = ConcurrentHashMap<String, Optional<Method>>()

    @Volatile
    private var hiddenByHook = false

    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (!HyperOsEnvironment.isHyperOs()) return
        if (!installed.compareAndSet(false, true)) return

        runCatching {
            installMiuiStatusBarControllerHooks(module, classLoader)
            installDisplayContentSurfaceHook(module, classLoader)
            installDisplayPolicyHooks(module, classLoader)
        }.onFailure { error ->
            NeXtepLog.warn("hyperos_statusbar", "HyperOS status bar surface hook skipped", error)
        }
    }

    private fun installMiuiStatusBarControllerHooks(module: XposedModule, classLoader: ClassLoader) {
        val controllerClass = runCatching {
            classLoader.loadClass("com.android.server.wm.MiuiStatusBarController")
        }.getOrNull() ?: return

        val updateAlpha = runCatching {
            controllerClass.getDeclaredMethod(
                "updateHomeBarAlpha",
                SurfaceControl.Transaction::class.java,
                Float::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        }.getOrNull() ?: return

        module.hook(updateAlpha).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                if (SystemServerWorkspaceBridge.isObservedWorkspaceActive()) {
                    val args = chain.args.toTypedArray()
                    if (args.size >= 2) {
                        args[1] = 0f
                        return chain.proceed(args)
                    }
                }
                return chain.proceed()
            }
        })
        NeXtepLog.info("hyperos_statusbar", "Installed MiuiStatusBarController#updateHomeBarAlpha hook")
    }

    private fun installDisplayContentSurfaceHook(module: XposedModule, classLoader: ClassLoader) {
        val dcClass = classLoader.loadClass("com.android.server.wm.DisplayContent")
        val prepareSurfaces = dcClass.getDeclaredMethod("prepareSurfaces").apply { isAccessible = true }
        val displayIdField = findField(dcClass, "mDisplayId") ?: return
        val wmServiceField = findField(dcClass, "mWmService") ?: return
        val contextField = findField(wmServiceField.type, "mContext") ?: return
        val pendingTxField = findField(dcClass, "mPendingTransaction")

        module.hook(prepareSurfaces).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                val dc = chain.thisObject ?: return result
                runCatching {
                    val displayId = displayIdField.getInt(dc)
                    if (displayId != Display.DEFAULT_DISPLAY) return@runCatching
                    val context = wmServiceField.get(dc)?.let { contextField.get(it) as? Context }
                    if (context != null && !SystemServerWorkspaceBridge.isGeometryObservationStarted()) {
                        SystemServerWorkspaceBridge.startGeometryObservation(context)
                    }
                    val active = SystemServerWorkspaceBridge.isObservedWorkspaceActive()
                    if (!active && !hiddenByHook && savedPlacements.isEmpty()) return@runCatching
                    val tx = pendingTxField?.get(dc) as? SurfaceControl.Transaction ?: return@runCatching
                    reconcileStatusBarPlacement(dc, active, tx)
                }.onFailure {
                    if (hookFailureLogged.compareAndSet(false, true)) {
                        NeXtepLog.warn("hyperos_statusbar", "Status bar surface sync failed open", it)
                    }
                }
                return result
            }
        })
        NeXtepLog.info("hyperos_statusbar", "Installed DisplayContent status bar surface placement hook")
    }

    private fun installDisplayPolicyHooks(module: XposedModule, classLoader: ClassLoader) {
        val displayPolicyClass = runCatching {
            classLoader.loadClass("com.android.server.wm.DisplayPolicy")
        }.getOrNull() ?: return

        displayPolicyClass.declaredMethods
            .filter { it.name == "topAppHidesSystemBar" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val barType = chain.args.firstOrNull() as? Int
                        if (barType == WindowInsets.Type.statusBars() &&
                            SystemServerWorkspaceBridge.isObservedWorkspaceActive()
                        ) {
                            return false
                        }
                        return chain.proceed()
                    }
                })
            }
    }

    private fun reconcileStatusBarPlacement(
        displayContent: Any,
        active: Boolean,
        tx: SurfaceControl.Transaction,
    ) {
        val statusBarWindows = findStatusBarWindowStates(displayContent)
        if (active) {
            for (win in statusBarWindows) {
                val sc = call(win, "getSurfaceControl") as? SurfaceControl ?: continue
                if (!sc.isValid) continue
                if (!savedPlacements.containsKey(win)) {
                    val parent = call(win, "getParentSurfaceControl") as? SurfaceControl
                    val alpha = (findField(win.javaClass, "mLastAlpha")?.let {
                        runCatching { it.getFloat(win) }.getOrNull()
                    }) ?: 1.0f
                    savedPlacements[win] = SavedPlacement(sc, parent, alpha)
                }
                tx.setAlpha(sc, 0.0f)
                hiddenByHook = true
            }
        } else {
            val entries = savedPlacements.entries.toList()
            for ((win, saved) in entries) {
                val currentSc = call(win, "getSurfaceControl") as? SurfaceControl
                if (currentSc == saved.surface && saved.surface.isValid) {
                    val parent = (call(win, "getParentSurfaceControl") as? SurfaceControl)?.takeIf { it.isValid }
                        ?: saved.nativeParent?.takeIf { it.isValid }
                    if (parent != null) {
                        tx.reparent(saved.surface, parent)
                    }
                    tx.setAlpha(saved.surface, saved.savedAlpha)
                }
                savedPlacements.remove(win)
            }
            hiddenByHook = false
        }
    }

    private fun findStatusBarWindowStates(displayContent: Any): List<Any> {
        val result = ArrayList<Any>(2)
        val callback = java.util.function.Consumer<Any> { windowState ->
            val attrs = findField(windowState.javaClass, "mAttrs")?.let {
                runCatching { it.get(windowState) as? WindowManager.LayoutParams }.getOrNull()
            }
            if (attrs?.type == WindowManager.LayoutParams.TYPE_STATUS_BAR) {
                result += windowState
            }
        }
        val method = methodCache.computeIfAbsent("${displayContent.javaClass.name}#forAllWindows#Consumer#Boolean") {
            val found = displayContent.javaClass.methods.firstOrNull {
                it.name == "forAllWindows" &&
                    it.parameterCount == 2 &&
                    java.util.function.Consumer::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                    it.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }?.apply { isAccessible = true }
            Optional.ofNullable(found)
        }.orElse(null) ?: return result

        runCatching { method.invoke(displayContent, callback, true) }
        return result
    }

    private fun findField(clazz: Class<*>, name: String): Field? {
        val key = "${clazz.name}#$name"
        return fieldCache.computeIfAbsent(key) {
            val found = generateSequence<Class<*>>(clazz) { it.superclass }
                .firstNotNullOfOrNull { current ->
                    runCatching { current.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
                }
            Optional.ofNullable(found)
        }.orElse(null)
    }

    private fun call(target: Any, name: String, vararg args: Any?): Any? {
        val clazz = target.javaClass
        val method = methodCache.computeIfAbsent("${clazz.name}#$name#${args.size}") {
            val found = generateSequence<Class<*>>(clazz) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull { it.name == name && it.parameterCount == args.size }
                ?.apply { isAccessible = true }
            Optional.ofNullable(found)
        }.orElse(null) ?: return null
        return runCatching { method.invoke(target, *args) }.getOrNull()
    }
}
