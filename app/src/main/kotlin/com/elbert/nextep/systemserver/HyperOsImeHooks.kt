package com.elbert.nextep.systemserver

import android.content.Context
import android.graphics.Rect
import android.view.Display
import android.view.SurfaceControl
import android.view.WindowInsets
import android.view.WindowManager
import com.elbert.nextep.framework.HyperOsEnvironment
import com.elbert.nextep.framework.HyperOsFreeformCompat
import com.elbert.nextep.logging.NeXtepLog
import com.elbert.nextep.workspace.SystemServerWorkspaceBridge
import com.elbert.nextep.workspace.WorkspaceGeometry
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * HyperOS 3 IME compatibility in `system_server`:
 * 1. Keeps the IME attached to DisplayContent's placeholder instead of reparenting inside a
 *    scaled app task.
 * 2. Neutralizes MIUI relative IME layering on DisplayContent when not parented to a Task/Activity.
 * 3. Compensates IME inset bottom hints on display 0 so apps shrink/pan sensibly inside the pane.
 */
object HyperOsImeHooks {
    private val installed = AtomicBoolean(false)
    private val attachFailureLogged = AtomicBoolean(false)
    private val layerFailureLogged = AtomicBoolean(false)
    private val insetsFailureLogged = AtomicBoolean(false)
    private val fieldCache = ConcurrentHashMap<String, Optional<Field>>()
    private val methodCache = ConcurrentHashMap<String, Optional<Method>>()

    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (!HyperOsEnvironment.isHyperOs()) return
        if (!installed.compareAndSet(false, true)) return

        runCatching {
            installAttachmentAndParentHooks(module, classLoader)
            installRelativeLayerHook(module, classLoader)
            installInsetsHintCompensation(module, classLoader)
        }.onFailure { error ->
            NeXtepLog.warn("hyperos_ime", "HyperOS IME hook installation skipped", error)
        }
    }

    private fun installAttachmentAndParentHooks(module: XposedModule, classLoader: ClassLoader) {
        val displayContentClass = classLoader.loadClass("com.android.server.wm.DisplayContent")
        val displayIdField = findField(displayContentClass, "mDisplayId") ?: return
        val wmServiceField = findField(displayContentClass, "mWmService") ?: return
        val contextField = findField(wmServiceField.type, "mContext") ?: return

        displayContentClass.declaredMethods
            .filter {
                it.name in setOf(
                    "computeImeParent",
                    "updateImeParent",
                    "isImeAttachedToApp",
                    "attachImeScreenshotOnTargetIfNeeded",
                )
            }
            .forEach { runCatching { module.deoptimize(it) } }

        runCatching {
            classLoader.loadClass("com.android.server.wm.WindowState").declaredMethods
                .filter { it.name == "needsRelativeLayeringToIme" }
                .forEach { runCatching { module.deoptimize(it) } }
        }

        val shouldAttachMethod = findMethod(displayContentClass, "shouldImeAttachedToApp")
        if (shouldAttachMethod != null) {
            runCatching { module.deoptimize(shouldAttachMethod) }
            module.hook(shouldAttachMethod).intercept(
                ShouldAttachHooker(displayIdField, wmServiceField, contextField),
            )
            NeXtepLog.info("hyperos_ime", "Installed DisplayContent#shouldImeAttachedToApp hook")
        }

        val computeParentMethod = findMethod(displayContentClass, "computeImeParent")
        val imePlaceholderField = findField(displayContentClass, "mImePlaceholder")
        if (computeParentMethod != null && imePlaceholderField != null) {
            runCatching { module.deoptimize(computeParentMethod) }
            module.hook(computeParentMethod).intercept(
                ComputeImeParentHooker(displayIdField, wmServiceField, contextField, imePlaceholderField),
            )
            NeXtepLog.info("hyperos_ime", "Installed DisplayContent#computeImeParent hook")
        }
    }

    private fun installRelativeLayerHook(module: XposedModule, classLoader: ClassLoader) {
        val displayContentClass = classLoader.loadClass("com.android.server.wm.DisplayContent")
        val method = displayContentClass.declaredMethods.firstOrNull {
            it.name == "assignRelativeLayerForIme" &&
                it.parameterTypes.size == 1 &&
                SurfaceControl.Transaction::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: return
        method.isAccessible = true
        runCatching { module.deoptimize(method) }

        val displayIdField = findField(displayContentClass, "mDisplayId") ?: return
        val wmServiceField = findField(displayContentClass, "mWmService") ?: return
        val contextField = findField(wmServiceField.type, "mContext") ?: return
        val imeContainerField = findField(displayContentClass, "mImeWindowsContainer") ?: return

        module.hook(method).intercept(
            AssignRelativeLayerHooker(displayIdField, wmServiceField, contextField, imeContainerField),
        )
        NeXtepLog.info("hyperos_ime", "Installed DisplayContent#assignRelativeLayerForIme hook")
    }

    private fun installInsetsHintCompensation(module: XposedModule, classLoader: ClassLoader) {
        val windowStateClass = classLoader.loadClass("com.android.server.wm.WindowState")
        val displayIdMethod = findMethod(windowStateClass, "getDisplayId")
        val taskMethod = findMethod(windowStateClass, "getTask")
        val attrsField = findField(windowStateClass, "mAttrs")
        val contextField = findField(windowStateClass, "mContext")
        if (displayIdMethod == null || taskMethod == null || attrsField == null || contextField == null) return

        var hookedCount = 0
        windowStateClass.declaredMethods
            .filter { it.name == "fillInsetsState" || it.name == "fillInsetsSourceControls" }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept(
                    InsetsCompensationHooker(displayIdMethod, taskMethod, attrsField, contextField),
                )
                hookedCount++
            }
        if (hookedCount > 0) {
            NeXtepLog.info("hyperos_ime", "Installed WindowState IME insets compensation ($hookedCount methods)")
        }
    }

    private fun ensureObservedActive(
        displayContent: Any,
        displayIdField: Field,
        wmServiceField: Field,
        contextField: Field,
    ): Boolean {
        val displayId = runCatching { displayIdField.getInt(displayContent) }.getOrNull() ?: return false
        if (displayId != Display.DEFAULT_DISPLAY) return false
        val context = runCatching {
            val wm = wmServiceField.get(displayContent) ?: return false
            contextField.get(wm) as? Context
        }.getOrNull() ?: return false
        if (!SystemServerWorkspaceBridge.isGeometryObservationStarted()) {
            SystemServerWorkspaceBridge.startGeometryObservation(context)
            return SystemServerWorkspaceBridge.isWorkspaceActive(context)
        }
        return SystemServerWorkspaceBridge.isObservedWorkspaceActive()
    }

    private fun isTaskLikeClassName(className: String?): Boolean {
        val simple = className?.substringAfterLast('.') ?: return false
        return simple == "Task" ||
            simple == "TaskFragment" ||
            simple == "ActivityRecord" ||
            simple.startsWith("Task") ||
            simple.startsWith("TaskFragment") ||
            simple.startsWith("ActivityRecord")
    }

    internal fun computeCompensatedImeBottom(
        visibleFrameBottom: Int,
        geometry: WorkspaceGeometry,
    ): Int {
        if (geometry.contentScale <= 0f || geometry.contentBottom <= 0) return visibleFrameBottom
        val contentHeight = geometry.screenHeight - visibleFrameBottom
        if (contentHeight <= 0) return visibleFrameBottom
        val mapped = ((visibleFrameBottom - geometry.contentTranslationY) / geometry.contentScale)
            .roundToInt()
            .coerceIn(0, geometry.screenHeight)
        return mapped
    }

    private class ShouldAttachHooker(
        private val displayIdField: Field,
        private val wmServiceField: Field,
        private val contextField: Field,
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val dc = chain.thisObject ?: return chain.proceed()
            val shouldOverride = runCatching {
                ensureObservedActive(dc, displayIdField, wmServiceField, contextField)
            }.onFailure {
                if (attachFailureLogged.compareAndSet(false, true)) {
                    NeXtepLog.warn("hyperos_ime", "shouldImeAttachedToApp failed open", it)
                }
            }.getOrDefault(false)
            return if (shouldOverride) false else chain.proceed()
        }
    }

    private class ComputeImeParentHooker(
        private val displayIdField: Field,
        private val wmServiceField: Field,
        private val contextField: Field,
        private val imePlaceholderField: Field,
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val dc = chain.thisObject ?: return chain.proceed()
            val placeholder = runCatching {
                if (!ensureObservedActive(dc, displayIdField, wmServiceField, contextField)) return@runCatching null
                imePlaceholderField.get(dc)
            }.onFailure {
                if (attachFailureLogged.compareAndSet(false, true)) {
                    NeXtepLog.warn("hyperos_ime", "computeImeParent failed open", it)
                }
            }.getOrNull()
            return placeholder ?: chain.proceed()
        }
    }

    private class AssignRelativeLayerHooker(
        private val displayIdField: Field,
        private val wmServiceField: Field,
        private val contextField: Field,
        private val imeContainerField: Field,
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            val dc = chain.thisObject ?: return result
            val tx = chain.args.firstOrNull() as? SurfaceControl.Transaction ?: return result
            runCatching {
                if (!ensureObservedActive(dc, displayIdField, wmServiceField, contextField)) return@runCatching
                if (HyperOsEnvironment.isMagnificationFallbackActive()) return@runCatching
                val imeContainer = imeContainerField.get(dc) ?: return@runCatching
                val isOrganized = call(imeContainer, "isOrganized") as? Boolean ?: false
                if (isOrganized) return@runCatching
                val parent = call(imeContainer, "getParent")
                val imeTargetParent = call(dc, "getImeTarget", 0)?.let { call(it, "getParent") }
                if (isTaskLikeClassName(parent?.javaClass?.name) ||
                    isTaskLikeClassName(imeTargetParent?.javaClass?.name)
                ) {
                    return@runCatching
                }
                val imeSc = call(imeContainer, "getSurfaceControl") as? SurfaceControl ?: return@runCatching
                if (!imeSc.isValid) return@runCatching
                tx.setLayer(imeSc, 0)
                setField(imeContainer, "mLastRelativeToLayer", null)
                setField(imeContainer, "mLastLayer", 0)
            }.onFailure {
                if (layerFailureLogged.compareAndSet(false, true)) {
                    NeXtepLog.warn("hyperos_ime", "assignRelativeLayerForIme cleanup failed open", it)
                }
            }
            return result
        }
    }

    private class InsetsCompensationHooker(
        private val displayIdMethod: Method,
        private val taskMethod: Method,
        private val attrsField: Field,
        private val contextField: Field,
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            val window = chain.thisObject ?: return result
            runCatching {
                val displayId = displayIdMethod.invoke(window) as? Int ?: return@runCatching
                if (displayId != Display.DEFAULT_DISPLAY) return@runCatching
                val context = contextField.get(window) as? Context ?: return@runCatching
                if (!SystemServerWorkspaceBridge.isGeometryObservationStarted()) {
                    SystemServerWorkspaceBridge.startGeometryObservation(context)
                }
                val geometry = SystemServerWorkspaceBridge.observedActiveGeometry() ?: return@runCatching
                if (geometry.contentScale <= 0f || geometry.contentScale >= 0.999f) return@runCatching
                val attrs = attrsField.get(window) as? WindowManager.LayoutParams ?: return@runCatching
                if (attrs.type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW) return@runCatching
                val task = taskMethod.invoke(window) ?: return@runCatching
                val windowingMode = call(task, "getWindowingMode") as? Int
                if (windowingMode != 1 || HyperOsFreeformCompat.isMiuiFreeformTaskContainer(task)) return@runCatching
                val outState = chain.args.firstOrNull {
                    it?.javaClass?.name == "android.view.InsetsState"
                } ?: return@runCatching
                adjustImeSourceFrameIfNeeded(outState, geometry)
            }.onFailure {
                if (insetsFailureLogged.compareAndSet(false, true)) {
                    NeXtepLog.warn("hyperos_ime", "IME insets compensation failed open", it)
                }
            }
            return result
        }

        private fun adjustImeSourceFrameIfNeeded(insetsState: Any, geometry: WorkspaceGeometry) {
            val sourceCount = call(insetsState, "sourceSize") as? Int ?: return
            val imeType = WindowInsets.Type.ime()
            for (index in 0 until sourceCount) {
                val source = call(insetsState, "sourceAt", index) ?: continue
                val type = call(source, "getType") as? Int ?: continue
                if (type != imeType) continue
                val visible = call(source, "isVisible") as? Boolean ?: false
                if (!visible) continue
                val frame = call(source, "getFrame") as? Rect ?: continue
                val newTop = computeCompensatedImeBottom(frame.top, geometry)
                if (newTop != frame.top && newTop < frame.bottom) {
                    call(source, "setFrame", frame.left, newTop, frame.right, frame.bottom)
                }
            }
        }
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

    private fun setField(target: Any, name: String, value: Any?) {
        val field = findField(target.javaClass, name) ?: return
        runCatching { field.set(target, value) }
    }

    private fun findMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
        val key = "${clazz.name}#$name(${paramTypes.joinToString(",") { it.name }})"
        return methodCache.computeIfAbsent(key) {
            val found = generateSequence<Class<*>>(clazz) { it.superclass }
                .firstNotNullOfOrNull { current ->
                    runCatching {
                        current.getDeclaredMethod(name, *paramTypes).apply { isAccessible = true }
                    }.getOrNull()
                }
            Optional.ofNullable(found)
        }.orElse(null)
    }

    private fun call(target: Any, name: String, vararg args: Any?): Any? {
        val clazz = target.javaClass
        val method = methodCache.computeIfAbsent("${clazz.name}#$name#${args.size}") {
            val found = generateSequence<Class<*>>(clazz) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull { m ->
                    m.name == name && m.parameterCount == args.size
                }?.apply { isAccessible = true }
            Optional.ofNullable(found)
        }.orElse(null) ?: return null
        return runCatching { method.invoke(target, *args) }.getOrNull()
    }
}
