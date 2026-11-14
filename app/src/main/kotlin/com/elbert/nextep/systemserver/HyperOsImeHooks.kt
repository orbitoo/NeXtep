package com.elbert.nextep.systemserver

import android.content.Context
import android.content.res.Resources
import android.graphics.Insets
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
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * HyperOS 3 IME compatibility in `system_server`:
 * 1. Keeps the IME attached to DisplayContent's placeholder instead of reparenting inside a
 *    scaled app task.
 * 2. Neutralizes MIUI/AOSP relative IME layering on DisplayContent when display-parented so IME
 *    stays above NeXtep panels and native freeform windows.
 * 3. Synchronizes IME parent and surface transforms on `DisplayContent#prepareSurfaces` when
 *    entering/exiting workspace or in landscape.
 * 4. Compensates IME inset bounds on display 0 when the IME stays unscaled on screen.
 */
object HyperOsImeHooks {
    private val installed = AtomicBoolean(false)
    private val attachFailureLogged = AtomicBoolean(false)
    private val layerFailureLogged = AtomicBoolean(false)
    private val surfaceFailureLogged = AtomicBoolean(false)
    private val reparentFailureLogged = AtomicBoolean(false)
    private val insetsFailureLogged = AtomicBoolean(false)
    private val hintFailureLogged = AtomicBoolean(false)
    private val insetsAdjustedLogs = AtomicInteger(0)
    private val hintAdjustedLogs = AtomicInteger(0)
    private val fieldCache = ConcurrentHashMap<String, Optional<Field>>()
    private val methodCache = ConcurrentHashMap<String, Optional<Method>>()

    @Volatile
    private var wasWorkspaceActiveForIme = false

    @Volatile
    private var lastFittedImeSurface: SurfaceControl? = null

    private val setMatrixMethod: Method? by lazy {
        runCatching {
            SurfaceControl.Transaction::class.java.getDeclaredMethod(
                "setMatrix",
                SurfaceControl::class.java,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        }.getOrNull()
    }

    private val setCornerRadiusMethod: Method? by lazy {
        runCatching {
            SurfaceControl.Transaction::class.java.methods
                .firstOrNull { it.name == "setCornerRadius" && it.parameterCount == 2 }
                ?.apply { isAccessible = true }
        }.getOrNull()
    }

    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (!HyperOsEnvironment.isHyperOs()) return
        if (!installed.compareAndSet(false, true)) return

        runCatching {
            installAttachmentAndParentHooks(module, classLoader)
            installRelativeLayerHook(module, classLoader)
            installSurfaceFittingHook(module, classLoader)
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
                    "assignChildLayers",
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
        val method = displayContentClass.declaredMethods.firstOrNull(::matchesAssignRelativeLayerForIme)
            ?: return
        method.isAccessible = true
        runCatching { module.deoptimize(method) }

        val displayIdField = findField(displayContentClass, "mDisplayId") ?: return
        val wmServiceField = findField(displayContentClass, "mWmService") ?: return
        val contextField = findField(wmServiceField.type, "mContext") ?: return
        val imeContainerField = findField(displayContentClass, "mImeWindowsContainer")
            ?: findField(displayContentClass, "mImeContainer")

        module.hook(method).intercept(
            AssignRelativeLayerHooker(displayIdField, wmServiceField, contextField, imeContainerField),
        )
        NeXtepLog.info(
            "hyperos_ime",
            "Installed DisplayContent#assignRelativeLayerForIme(${method.parameterTypes.joinToString(",") { it.simpleName }}) hook",
        )
    }

    private fun installSurfaceFittingHook(module: XposedModule, classLoader: ClassLoader) {
        val displayContentClass = classLoader.loadClass("com.android.server.wm.DisplayContent")
        val prepareSurfacesMethod = displayContentClass.declaredMethods.firstOrNull {
            it.name == "prepareSurfaces" && it.parameterCount == 0
        } ?: return
        prepareSurfacesMethod.isAccessible = true

        val displayIdField = findField(displayContentClass, "mDisplayId") ?: return
        val wmServiceField = findField(displayContentClass, "mWmService") ?: return
        val contextField = findField(wmServiceField.type, "mContext") ?: return
        val imeContainerField = findField(displayContentClass, "mImeWindowsContainer")
            ?: findField(displayContentClass, "mImeContainer")

        module.hook(prepareSurfacesMethod).intercept(
            PrepareSurfacesImeHooker(displayIdField, wmServiceField, contextField, imeContainerField),
        )
        NeXtepLog.info("hyperos_ime", "Installed DisplayContent#prepareSurfaces IME sync/fitting hook")
    }

    private fun installInsetsHintCompensation(module: XposedModule, classLoader: ClassLoader) {
        val windowStateClass = classLoader.loadClass("com.android.server.wm.WindowState")
        val displayIdMethod = findMethod(windowStateClass, "getDisplayId")
        val taskMethod = findMethod(windowStateClass, "getTask")
        val attrsField = findField(windowStateClass, "mAttrs")
        val wmServiceField = findField(windowStateClass, "mWmService")
        val wmContextField = wmServiceField?.let { findField(it.type, "mContext") }
        val directContextField = findField(windowStateClass, "mContext")
        if (displayIdMethod == null || taskMethod == null || attrsField == null ||
            ((wmServiceField == null || wmContextField == null) && directContextField == null)
        ) {
            NeXtepLog.warn("hyperos_ime", "WindowState IME insets compensation skipped: missing fields/methods")
            return
        }

        // ART inlines fillInsetsState / fillInsetsSourceControls inside WindowState dispatchers.
        runCatching {
            windowStateClass.declaredMethods
                .filter {
                    it.name in setOf(
                        "reportResized",
                        "notifyInsetsControlChanged",
                        "notifyInsetsChanged",
                        "getInsetsState",
                    )
                }
                .forEach { runCatching { module.deoptimize(it) } }
        }

        val fillInsetsState = windowStateClass.declaredMethods.firstOrNull(::matchesFillInsetsState)
        if (fillInsetsState != null) {
            fillInsetsState.isAccessible = true
            val insetsStateClass = fillInsetsState.parameterTypes[0]
            val sourceSize = findMethod(insetsStateClass, "sourceSize")
            val sourceAt = findMethod(insetsStateClass, "sourceAt", Int::class.javaPrimitiveType!!)
            val sourceClass = sourceAt?.returnType
            val addSource = sourceClass?.let { findMethod(insetsStateClass, "addSource", it) }
            val getType = sourceClass?.let { findMethod(it, "getType") }
            val isVisible = sourceClass?.let { findMethod(it, "isVisible") }
            val getFrameNoArg = sourceClass?.let { findMethod(it, "getFrame") }
            val setFrame4 = sourceClass?.let {
                findMethod(
                    it,
                    "setFrame",
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                )
            }
            val copySourceCtor = sourceClass?.let {
                runCatching { it.getDeclaredConstructor(it).apply { isAccessible = true } }.getOrNull()
            }
            if (sourceSize != null && sourceAt != null && addSource != null &&
                getType != null && isVisible != null && getFrameNoArg != null &&
                setFrame4 != null && copySourceCtor != null
            ) {
                module.hook(fillInsetsState).intercept(
                    FillInsetsStateHooker(
                        displayIdMethod = displayIdMethod,
                        taskMethod = taskMethod,
                        attrsField = attrsField,
                        wmServiceField = wmServiceField,
                        wmContextField = wmContextField,
                        directContextField = directContextField,
                        sourceSizeMethod = sourceSize,
                        sourceAtMethod = sourceAt,
                        addSourceMethod = addSource,
                        getTypeMethod = getType,
                        isVisibleMethod = isVisible,
                        getFrameMethod = getFrameNoArg,
                        setFrame4Method = setFrame4,
                        copySourceCtor = copySourceCtor,
                    ),
                )
                NeXtepLog.info("hyperos_ime", "Installed WindowState#fillInsetsState IME compensation hook")
            }
        }

        val fillControls = windowStateClass.declaredMethods.firstOrNull(::matchesFillInsetsSourceControls)
        if (fillControls != null) {
            fillControls.isAccessible = true
            val arrayClass = fillControls.parameterTypes[0]
            val getArray = findMethod(arrayClass, "get")
            val setArray = getArray?.returnType?.let { arrayType ->
                runCatching {
                    arrayClass.getDeclaredMethod("set", arrayType, Boolean::class.javaPrimitiveType)
                        .apply { isAccessible = true }
                }.getOrNull()
            }
            val ctrlClass = runCatching { classLoader.loadClass("android.view.InsetsSourceControl") }.getOrNull()
            val getType = ctrlClass?.let { findMethod(it, "getType") }
            val getInsetsHint = ctrlClass?.let { findMethod(it, "getInsetsHint") }
            val copyCtrlCtor = ctrlClass?.let {
                runCatching { it.getDeclaredConstructor(it).apply { isAccessible = true } }.getOrNull()
            }
            val hintField = ctrlClass?.let { findField(it, "mInsetsHint") }
            if (getArray != null && setArray != null && getType != null &&
                getInsetsHint != null && copyCtrlCtor != null && hintField != null
            ) {
                module.hook(fillControls).intercept(
                    FillInsetsSourceControlsHooker(
                        displayIdMethod = displayIdMethod,
                        taskMethod = taskMethod,
                        attrsField = attrsField,
                        wmServiceField = wmServiceField,
                        wmContextField = wmContextField,
                        directContextField = directContextField,
                        getArrayMethod = getArray,
                        setArrayMethod = setArray,
                        getTypeMethod = getType,
                        getInsetsHintMethod = getInsetsHint,
                        copyCtrlCtor = copyCtrlCtor,
                        hintField = hintField,
                    ),
                )
                NeXtepLog.info("hyperos_ime", "Installed WindowState#fillInsetsSourceControls IME hint hook")
            }
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

    internal fun matchesAssignRelativeLayerForIme(method: Method): Boolean {
        val paramTypes = method.parameterTypes
        return method.name == "assignRelativeLayerForIme" &&
            paramTypes.isNotEmpty() &&
            SurfaceControl.Transaction::class.java.isAssignableFrom(paramTypes[0])
    }

    internal fun matchesFillInsetsState(method: Method): Boolean =
        matchesFillInsetsStateSignature(method.name, method.parameterTypes.map { it.name })

    internal fun matchesFillInsetsStateSignature(
        methodName: String,
        parameterTypeNames: List<String>,
    ): Boolean = methodName == "fillInsetsState" &&
        parameterTypeNames.size >= 2 &&
        parameterTypeNames[0] == "android.view.InsetsState" &&
        parameterTypeNames[1] == "boolean"

    internal fun matchesFillInsetsSourceControls(method: Method): Boolean =
        matchesFillInsetsSourceControlsSignature(method.name, method.parameterTypes.map { it.name })

    internal fun matchesFillInsetsSourceControlsSignature(
        methodName: String,
        parameterTypeNames: List<String>,
    ): Boolean = methodName == "fillInsetsSourceControls" &&
        parameterTypeNames.size >= 2 &&
        parameterTypeNames[0] == "android.view.InsetsSourceControl\$Array" &&
        parameterTypeNames[1] == "boolean"

    internal fun shouldFitImeSurface(geometry: WorkspaceGeometry): Boolean =
        geometry.contentScale > 0f && (geometry.isLandscape || geometry.displayRotation > 0)

    internal fun computeImeCropRect(geometry: WorkspaceGeometry): Rect =
        Rect(0, 0, geometry.screenWidth, minOf(geometry.availableBottom, geometry.screenHeight))

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

    internal fun computeCompensatedImeHintBottom(
        imeHintBottomPx: Int,
        geometry: WorkspaceGeometry,
    ): Int {
        if (imeHintBottomPx <= 0 || geometry.contentScale <= 0f || geometry.contentScale >= 0.999f) {
            return imeHintBottomPx
        }
        val imeTopScreen = (geometry.screenHeight - imeHintBottomPx).coerceIn(0, geometry.screenHeight)
        val compensatedTop = computeCompensatedImeBottom(imeTopScreen, geometry)
        return (geometry.screenHeight - compensatedTop).coerceIn(imeHintBottomPx, geometry.screenHeight)
    }

    private fun resolveWindowContext(
        window: Any,
        wmServiceField: Field?,
        wmContextField: Field?,
        directContextField: Field?,
    ): Context? {
        if (wmServiceField != null && wmContextField != null) {
            val ctx = runCatching { wmServiceField.get(window)?.let { wmContextField.get(it) as? Context } }.getOrNull()
            if (ctx != null) return ctx
        }
        return directContextField?.let { runCatching { it.get(window) as? Context }.getOrNull() }
    }

    private fun resolveEligibleScaledMainGeometry(
        window: Any,
        displayIdMethod: Method,
        taskMethod: Method,
        attrsField: Field,
        wmServiceField: Field?,
        wmContextField: Field?,
        directContextField: Field?,
    ): WorkspaceGeometry? {
        val displayId = displayIdMethod.invoke(window) as? Int ?: return null
        if (displayId != Display.DEFAULT_DISPLAY) return null
        val context = resolveWindowContext(window, wmServiceField, wmContextField, directContextField) ?: return null
        if (!SystemServerWorkspaceBridge.isGeometryObservationStarted()) {
            SystemServerWorkspaceBridge.startGeometryObservation(context)
        }
        if (!SystemServerWorkspaceBridge.isWorkspaceActive(context)) return null
        val geometry = SystemServerWorkspaceBridge.observedActiveGeometry() ?: return null
        if (HyperOsEnvironment.isMagnificationFallbackActive()) return null
        if (shouldFitImeSurface(geometry)) return null
        if (geometry.contentScale <= 0f || geometry.contentScale >= 0.999f) return null
        val attrs = attrsField.get(window) as? WindowManager.LayoutParams ?: return null
        if (attrs.type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW) return null
        val task = taskMethod.invoke(window) ?: return null
        val windowingMode = call(task, "getWindowingMode") as? Int
        if (windowingMode != 1 || HyperOsFreeformCompat.isMiuiFreeformTaskContainer(task)) return null
        val taskBounds = call(task, "getBounds") as? Rect
        if (taskBounds != null && (taskBounds.width() != geometry.screenWidth || taskBounds.height() != geometry.screenHeight)) {
            return null
        }
        return geometry
    }

    private fun resolveImeContainer(dc: Any, imeContainerField: Field?): Any? {
        return call(dc, "getImeContainer")
            ?: imeContainerField?.let { runCatching { it.get(dc) }.getOrNull() }
            ?: getFieldValue(dc, "mImeContainer")
    }

    private fun getFieldValue(target: Any, name: String): Any? =
        findField(target.javaClass, name)?.let { runCatching { it.get(target) }.getOrNull() }

    /**
     * Clears WM relative layering on `mImeWindowsContainer` so the IME remains in `mImePlaceholder`
     * above both NeXtep panels (`TYPE_PHONE`) and native freeform tasks. Returns `true` if our
     * override owns the layer and the ROM method must be skipped.
     */
    private fun neutralizeRelativeLayer(imeContainer: Any, tx: SurfaceControl.Transaction): Boolean {
        val isOrganized = call(imeContainer, "isOrganized") as? Boolean ?: false
        if (isOrganized) return false
        val parent = call(imeContainer, "getParent") ?: getFieldValue(imeContainer, "mParent")
        if (isTaskLikeClassName(parent?.javaClass?.name)) return false
        val imeSc = call(imeContainer, "getSurfaceControl") as? SurfaceControl ?: return false
        if (!imeSc.isValid) return true
        tx.setLayer(imeSc, 0)
        setField(imeContainer, "mLastRelativeToLayer", null)
        setField(imeContainer, "mLastLayer", 0)
        return true
    }

    private fun applyImeSurfaceFitting(
        imeSc: SurfaceControl,
        tx: SurfaceControl.Transaction,
        geometry: WorkspaceGeometry,
        cornerRadiusPx: Float,
    ) {
        val setMatrix = setMatrixMethod ?: return
        if (lastFittedImeSurface != imeSc) {
            releaseImeSurfaceFitting(tx)
        }
        val scale = geometry.contentScale
        setMatrix.invoke(tx, imeSc, scale, 0f, 0f, scale)
        tx.setPosition(imeSc, geometry.contentTranslationX, geometry.contentTranslationY)
        tx.setCrop(imeSc, computeImeCropRect(geometry))
        if (cornerRadiusPx > 0f && scale > 0f) {
            runCatching { setCornerRadiusMethod?.invoke(tx, imeSc, cornerRadiusPx / scale) }
        }
        lastFittedImeSurface = imeSc
    }

    private fun releaseImeSurfaceFitting(tx: SurfaceControl.Transaction) {
        val imeSc = lastFittedImeSurface ?: return
        if (imeSc.isValid) {
            runCatching {
                setMatrixMethod?.invoke(tx, imeSc, 1f, 0f, 0f, 1f)
                tx.setPosition(imeSc, 0f, 0f)
                tx.setCrop(imeSc, null)
                setCornerRadiusMethod?.invoke(tx, imeSc, 0f)
            }
        }
        lastFittedImeSurface = null
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
        private val imeContainerField: Field?,
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val dc = chain.thisObject ?: return chain.proceed()
            val tx = chain.args.firstOrNull() as? SurfaceControl.Transaction ?: return chain.proceed()
            val handled = runCatching {
                if (!ensureObservedActive(dc, displayIdField, wmServiceField, contextField)) return@runCatching false
                if (HyperOsEnvironment.isMagnificationFallbackActive()) return@runCatching false
                val imeContainer = resolveImeContainer(dc, imeContainerField) ?: return@runCatching false
                neutralizeRelativeLayer(imeContainer, tx)
            }.onFailure {
                if (layerFailureLogged.compareAndSet(false, true)) {
                    NeXtepLog.warn("hyperos_ime", "assignRelativeLayerForIme override failed open", it)
                }
            }.getOrDefault(false)
            return if (handled) null else chain.proceed()
        }
    }

    private class PrepareSurfacesImeHooker(
        private val displayIdField: Field,
        private val wmServiceField: Field,
        private val contextField: Field,
        private val imeContainerField: Field?,
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            val dc = chain.thisObject ?: return result
            runCatching {
                val active = ensureObservedActive(dc, displayIdField, wmServiceField, contextField)
                val displayId = runCatching { displayIdField.getInt(dc) }.getOrNull()
                if (displayId != Display.DEFAULT_DISPLAY) return@runCatching

                val geometry = if (active) SystemServerWorkspaceBridge.observedActiveGeometry() else null
                val isWorkspaceNow = geometry != null
                val pendingTx = call(dc, "getPendingTransaction") as? SurfaceControl.Transaction

                if (isWorkspaceNow && pendingTx != null && !HyperOsEnvironment.isMagnificationFallbackActive()) {
                    resolveImeContainer(dc, imeContainerField)?.let { imeContainer ->
                        neutralizeRelativeLayer(imeContainer, pendingTx)
                    }
                }

                if (wasWorkspaceActiveForIme != isWorkspaceNow) {
                    wasWorkspaceActiveForIme = isWorkspaceNow
                    runCatching {
                        call(dc, "updateImeParent")
                        val insetsController = call(dc, "getInsetsStateController")
                            ?: getFieldValue(dc, "mInsetsStateController")
                        if (insetsController != null) {
                            call(insetsController, "notifyInsetsChanged")
                        }
                    }.onFailure { error ->
                        if (reparentFailureLogged.compareAndSet(false, true)) {
                            NeXtepLog.warn("hyperos_ime", "IME reparent on workspace edge failed", error)
                        }
                    }
                }

                if (pendingTx == null) return@runCatching
                if (geometry == null || HyperOsEnvironment.isMagnificationFallbackActive() || !shouldFitImeSurface(geometry)) {
                    releaseImeSurfaceFitting(pendingTx)
                    return@runCatching
                }

                val imeContainer = resolveImeContainer(dc, imeContainerField)
                val imeParent = imeContainer?.let {
                    call(it, "getParent") ?: getFieldValue(it, "mParent")
                }
                if (imeContainer == null || isTaskLikeClassName(imeParent?.javaClass?.name)) {
                    releaseImeSurfaceFitting(pendingTx)
                    return@runCatching
                }
                val imeSc = call(imeContainer, "getSurfaceControl") as? SurfaceControl
                if (imeSc == null || !imeSc.isValid) {
                    releaseImeSurfaceFitting(pendingTx)
                    return@runCatching
                }

                val density = runCatching {
                    val wm = wmServiceField.get(dc)
                    val ctx = wm?.let { contextField.get(it) as? Context }
                    ctx?.resources?.displayMetrics?.density ?: Resources.getSystem().displayMetrics.density
                }.getOrDefault(Resources.getSystem().displayMetrics.density)
                applyImeSurfaceFitting(imeSc, pendingTx, geometry, 20f * density)
            }.onFailure { error ->
                if (surfaceFailureLogged.compareAndSet(false, true)) {
                    NeXtepLog.warn("hyperos_ime", "prepareSurfaces IME sync/fitting failed open", error)
                }
            }
            return result
        }
    }

    private class FillInsetsStateHooker(
        private val displayIdMethod: Method,
        private val taskMethod: Method,
        private val attrsField: Field,
        private val wmServiceField: Field?,
        private val wmContextField: Field?,
        private val directContextField: Field?,
        private val sourceSizeMethod: Method,
        private val sourceAtMethod: Method,
        private val addSourceMethod: Method,
        private val getTypeMethod: Method,
        private val isVisibleMethod: Method,
        private val getFrameMethod: Method,
        private val setFrame4Method: Method,
        private val copySourceCtor: Constructor<*>,
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val outState = chain.args.firstOrNull()
            val result = chain.proceed()
            val window = chain.thisObject ?: return result
            if (outState == null) return result
            runCatching {
                val geometry = resolveEligibleScaledMainGeometry(
                    window = window,
                    displayIdMethod = displayIdMethod,
                    taskMethod = taskMethod,
                    attrsField = attrsField,
                    wmServiceField = wmServiceField,
                    wmContextField = wmContextField,
                    directContextField = directContextField,
                ) ?: return@runCatching
                val sourceCount = sourceSizeMethod.invoke(outState) as? Int ?: return@runCatching
                val imeType = WindowInsets.Type.ime()
                for (index in 0 until sourceCount) {
                    val source = sourceAtMethod.invoke(outState, index) ?: continue
                    val type = getTypeMethod.invoke(source) as? Int ?: continue
                    if (type != imeType) continue
                    val visible = isVisibleMethod.invoke(source) as? Boolean ?: false
                    if (!visible) continue
                    val rawFrame = getFrameMethod.invoke(source) as? Rect ?: continue
                    val frame = Rect(rawFrame)
                    if (frame.width() <= 0 || frame.height() <= 0 || frame.top <= 0) continue
                    val newTop = computeCompensatedImeBottom(frame.top, geometry)
                    if (newTop < frame.top && newTop < frame.bottom) {
                        val copy = copySourceCtor.newInstance(source)
                        setFrame4Method.invoke(copy, frame.left, newTop, frame.right, frame.bottom)
                        addSourceMethod.invoke(outState, copy)
                        if (insetsAdjustedLogs.getAndIncrement() < 3) {
                            NeXtepLog.info(
                                "hyperos_ime",
                                "Adjusted main IME inset bottom ${frame.height()}px -> ${frame.bottom - newTop}px at scale=${geometry.contentScale}",
                            )
                        }
                    }
                }
            }.onFailure {
                if (insetsFailureLogged.compareAndSet(false, true)) {
                    NeXtepLog.warn("hyperos_ime", "IME insets compensation failed open", it)
                }
            }
            return result
        }
    }

    private class FillInsetsSourceControlsHooker(
        private val displayIdMethod: Method,
        private val taskMethod: Method,
        private val attrsField: Field,
        private val wmServiceField: Field?,
        private val wmContextField: Field?,
        private val directContextField: Field?,
        private val getArrayMethod: Method,
        private val setArrayMethod: Method,
        private val getTypeMethod: Method,
        private val getInsetsHintMethod: Method,
        private val copyCtrlCtor: Constructor<*>,
        private val hintField: Field,
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val arrayArg = chain.args.firstOrNull()
            val result = chain.proceed()
            val window = chain.thisObject ?: return result
            if (arrayArg == null) return result
            runCatching {
                val geometry = resolveEligibleScaledMainGeometry(
                    window = window,
                    displayIdMethod = displayIdMethod,
                    taskMethod = taskMethod,
                    attrsField = attrsField,
                    wmServiceField = wmServiceField,
                    wmContextField = wmContextField,
                    directContextField = directContextField,
                ) ?: return@runCatching
                @Suppress("UNCHECKED_CAST")
                val controls = getArrayMethod.invoke(arrayArg) as? Array<Any?> ?: return@runCatching
                val imeType = WindowInsets.Type.ime()
                val imeIdx = controls.indexOfFirst { it != null && getTypeMethod.invoke(it) == imeType }
                if (imeIdx < 0) return@runCatching
                val imeControl = controls[imeIdx] ?: return@runCatching
                val hint = getInsetsHintMethod.invoke(imeControl) as? Insets ?: return@runCatching
                if (hint.bottom <= 0) return@runCatching
                val compBottom = computeCompensatedImeHintBottom(hint.bottom, geometry)
                if (compBottom > hint.bottom) {
                    val patched = copyCtrlCtor.newInstance(imeControl)
                    hintField.set(patched, Insets.of(hint.left, hint.top, hint.right, compBottom))
                    val componentType = controls.javaClass.componentType ?: return@runCatching
                    val outArray = ReflectArray.newInstance(componentType, controls.size)
                    for (i in controls.indices) {
                        ReflectArray.set(outArray, i, if (i == imeIdx) patched else controls[i])
                    }
                    setArrayMethod.invoke(arrayArg, outArray, false)
                    if (hintAdjustedLogs.getAndIncrement() < 3) {
                        NeXtepLog.info(
                            "hyperos_ime",
                            "Adjusted main IME hint ${hint.bottom}px -> ${compBottom}px at scale=${geometry.contentScale}",
                        )
                    }
                }
            }.onFailure {
                if (hintFailureLogged.compareAndSet(false, true)) {
                    NeXtepLog.warn("hyperos_ime", "IME hint compensation failed open", it)
                }
            }
            return result
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
