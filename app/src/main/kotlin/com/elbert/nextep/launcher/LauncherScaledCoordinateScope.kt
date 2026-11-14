package com.elbert.nextep.launcher

import android.view.View
import com.elbert.nextep.logging.NeXtepLog
import com.elbert.nextep.xposed.HookGuard
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.math.roundToInt

internal object LauncherCoordinateTransformPolicy {
    /**
     * Convert screen coordinates produced by View.getLocationOnScreen back into the
     * DecorView's pre-scale, pre-translation local coordinate space.
     */
    fun inverseMapScreenPointToLocal(
        point: IntArray,
        scaleX: Float,
        scaleY: Float,
        translationX: Float,
        translationY: Float,
    ): Boolean {
        if (point.size < 2 || scaleX <= 0f || scaleY <= 0f) return false
        if (scaleX == 1f && scaleY == 1f && translationX == 0f && translationY == 0f) return false
        point[0] = ((point[0] - translationX) / scaleX).roundToInt()
        point[1] = ((point[1] - translationY) / scaleY).roundToInt()
        return true
    }

    fun canInverseMapForRoot(
        workspaceActive: Boolean,
        rootClassName: String?,
        pivotX: Float,
        pivotY: Float,
        scaleX: Float,
        scaleY: Float,
        translationX: Float,
        translationY: Float,
    ): Boolean {
        if (!workspaceActive) return false
        if (rootClassName == null || rootClassName.contains("Popup", ignoreCase = true)) return false
        if (pivotX != 0f || pivotY != 0f) return false
        if (scaleX <= 0f || scaleY <= 0f) return false
        return scaleX != 1f || scaleY != 1f || translationX != 0f || translationY != 0f
    }
}

/**
 * Maps View.getLocationOnScreen back into DecorView-local coordinates only while executing
 * intra-DecorView Launcher animations (folders, boom particles, highlight overlays, fold divider).
 * External WM/transition callers (PopupWindow, AppTransitionManager, Recents) stay in screen space.
 */
object LauncherScaledCoordinateScope {
    private val scopeDepth = ThreadLocal.withInitial { 0 }

    private val scopedClassMethods: List<Pair<String, Set<String>?>> = listOf(
        "com.miui.home.folder.Folder" to setOf("closeFolder", "openFolder"),
        "com.miui.home.folder.Folder\$openFolder\$2" to setOf("run"),
        "com.miui.home.folder.Folder\$openFolder\$2\$run\$1" to setOf("onGlobalLayout"),
        "com.miui.home.folder.FolderAnimController" to null,
        "com.miui.home.folder.FolderAnimProgressController" to setOf(
            "setViewLocation",
            "initAnimRate",
            "setListenerAnimParams",
        ),
        "com.miui.home.folder.FolderAnimProgressController\$1" to null,
        "com.miui.home.folder.FolderCling" to setOf(
            "calculateIconPosition",
            "setAnimFolderIconPositionAndScale",
            "applyIconPosition",
            "applyIconScale",
            "open",
            "close",
        ),
        "com.miui.home.launcher.SuperDraglayer" to setOf("highlightLocatedApp"),
        "com.miui.home.launcher.uninstall.BoomAnimHelper" to setOf(
            "widgetDoBoomAnim",
            "doBoomAnim",
            "iconBoomAnim",
        ),
        "com.miui.home.launcher.Workspace" to setOf("drawFoldDivideLine"),
    )

    private val deoptimizeHelpers: List<Pair<String, String>> = listOf(
        "com.miui.home.ItemIcon" to "getIconLocation",
        "com.miui.home.launcher.ShortcutIcon" to "getIconLocation",
        "com.miui.home.folder.FolderPreviewIconView" to "getIconLocation",
    )

    fun installMiuiHome(module: XposedModule, classLoader: ClassLoader) {
        HookGuard.run("launcher_scaled_coordinate_scope") {
            val getLocationOnScreen = View::class.java.getDeclaredMethod(
                "getLocationOnScreen",
                IntArray::class.java,
            )
            getLocationOnScreen.isAccessible = true
            runCatching { module.deoptimize(getLocationOnScreen) }
            module.hook(getLocationOnScreen).intercept(GetLocationOnScreenHooker())

            var hookedCount = 0
            scopedClassMethods.forEach { (className, methodNames) ->
                hookedCount += hookClassMethods(module, classLoader, className, methodNames)
            }
            deoptimizeHelpers.forEach { (className, methodName) ->
                runCatching {
                    val clazz = classLoader.loadClass(className)
                    clazz.declaredMethods.filter { it.name == methodName }.forEach { method ->
                        method.isAccessible = true
                        runCatching { module.deoptimize(method) }
                    }
                }
            }
            NeXtepLog.info(
                "launcher_scaled_coordinate_scope",
                "Installed scoped inverse coordinate hooks (methods=$hookedCount)",
            )
        }
    }

    private fun hookClassMethods(
        module: XposedModule,
        classLoader: ClassLoader,
        className: String,
        methodNames: Set<String>?,
    ): Int = runCatching {
        val clazz = classLoader.loadClass(className)
        var count = 0
        clazz.declaredMethods
            .filter { method ->
                !Modifier.isAbstract(method.modifiers) &&
                    !Modifier.isNative(method.modifiers) &&
                    (methodNames == null || methodNames.any { name ->
                        method.name == name || method.name.startsWith("$name\$")
                    })
            }
            .forEach { method ->
                hookMethodInScope(module, method)
                count++
            }
        count
    }.onFailure { error ->
        NeXtepLog.warn("launcher_scaled_coordinate_scope", "Skipped $className: ${error.message}")
    }.getOrDefault(0)

    private fun hookMethodInScope(module: XposedModule, method: Method) {
        method.isAccessible = true
        runCatching { module.deoptimize(method) }
        module.hook(method).intercept(ScopeEntryHooker())
    }

    private class ScopeEntryHooker : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val depth = scopeDepth.get() ?: 0
            scopeDepth.set(depth + 1)
            return try {
                chain.proceed()
            } finally {
                scopeDepth.set(depth)
            }
        }
    }

    private class GetLocationOnScreenHooker : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            if ((scopeDepth.get() ?: 0) <= 0) return result
            val view = chain.thisObject as? View ?: return result
            val out = chain.args.firstOrNull() as? IntArray ?: return result
            val root = view.rootView ?: return result
            val shouldMap = LauncherCoordinateTransformPolicy.canInverseMapForRoot(
                workspaceActive = LauncherTransformController.isActive(),
                rootClassName = root.javaClass.name,
                pivotX = root.pivotX,
                pivotY = root.pivotY,
                scaleX = root.scaleX,
                scaleY = root.scaleY,
                translationX = root.translationX,
                translationY = root.translationY,
            )
            if (shouldMap) {
                LauncherCoordinateTransformPolicy.inverseMapScreenPointToLocal(
                    point = out,
                    scaleX = root.scaleX,
                    scaleY = root.scaleY,
                    translationX = root.translationX,
                    translationY = root.translationY,
                )
            }
            return result
        }
    }
}
