package com.elbert.nextep.systemserver

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import com.elbert.nextep.logging.NeXtepLog
import com.elbert.nextep.workspace.SystemServerWorkspaceBridge
import com.elbert.nextep.workspace.WorkspaceSceneObserverPolicy
import com.elbert.nextep.workspace.WorkspaceSceneRequest
import com.elbert.nextep.workspace.WorkspaceVirtualDisplayNames
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Observes main-pane Activity orientation and fullscreen status-bar changes inside
 * `system_server`, publishes [WorkspaceSceneRequest] to [SystemServerWorkspaceBridge], and
 * briefly holds primary Display 0 rotation while TURNED / TOP modes migrate the task.
 */
object WorkspaceSceneObserverHook {
    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val WINDOWING_MODE_PINNED = 2
    private const val WINDOWING_MODE_FREEFORM = 5
    private const val ACTIVITY_TYPE_STANDARD = 1
    private const val DUPLICATE_REQUEST_DEBOUNCE_MS = 350L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val holdPending = AtomicBoolean(false)
    private val bypassNextRotationUpdate = ThreadLocal<Boolean>()
    private var pendingHoldRunnable: Runnable? = null

    fun install(module: XposedModule, classLoader: ClassLoader) {
        installActivityOrientationHook(module, classLoader)
        installWindowVisibilityHooks(module, classLoader)
        installDisplayOrientationHook(module, classLoader)
        installDisplayRotationHoldHook(module, classLoader)
    }

    private fun installActivityOrientationHook(module: XposedModule, classLoader: ClassLoader) {
        val activityRecordClass = runCatching {
            classLoader.loadClass("com.android.server.wm.ActivityRecord")
        }.getOrNull() ?: return

        val setRequestedOrientation = activityRecordClass.declaredMethods.firstOrNull {
            it.name == "setRequestedOrientation" &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: return

        setRequestedOrientation.isAccessible = true
        module.hook(setRequestedOrientation).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val activityRecord = chain.thisObject
                val requested = chain.getArg(0) as? Int
                var publishedBeforeProceed = false
                if (activityRecord != null && requested != null &&
                    WorkspaceSceneRequest.isLandscapeRequest(requested)
                ) {
                    // Publish synchronously in memory before proceed() because ActivityRecord#setRequestedOrientation
                    // triggers DisplayRotation synchronously inside proceed().
                    publishedBeforeProceed = runCatching {
                        onActivityOrientationChanging(activityRecord, requested, beforeProceed = true)
                    }.onFailure { error ->
                        NeXtepLog.warn("workspace_scene_observer", "Pre-orientation hook error", error)
                    }.getOrDefault(false)
                }

                val result = chain.proceed()

                if (activityRecord != null && requested != null && !publishedBeforeProceed) {
                    runCatching {
                        onActivityOrientationChanging(activityRecord, requested, beforeProceed = false)
                    }.onFailure { error ->
                        NeXtepLog.warn("workspace_scene_observer", "Post-orientation hook error", error)
                    }
                }
                return result
            }
        })
        NeXtepLog.info("workspace_scene_observer", "Installed ActivityRecord#setRequestedOrientation observer")
    }

    private fun installWindowVisibilityHooks(module: XposedModule, classLoader: ClassLoader) {
        val wmsClass = runCatching {
            classLoader.loadClass("com.android.server.wm.WindowManagerService")
        }.getOrNull()
        wmsClass?.declaredMethods
            ?.filter { it.name == "updateRequestedVisibleTypes" }
            ?.forEach { method -> runCatching { module.deoptimize(method) } }

        val windowStateClass = runCatching {
            classLoader.loadClass("com.android.server.wm.WindowState")
        }.getOrNull() ?: return

        val hooksInstalled = windowStateClass.declaredMethods
            .filter { method ->
                method.name == "setRequestedVisibleTypes" &&
                    method.parameterCount in 1..2 &&
                    method.parameterTypes.all { it == Int::class.javaPrimitiveType }
            }
            .onEach { method ->
                method.isAccessible = true
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        val windowState = chain.thisObject ?: return result
                        val visibleTypes = chain.getArg(0) as? Int
                        runCatching {
                            onWindowVisibilityChanged(windowState, visibleTypes)
                        }.onFailure { error ->
                            NeXtepLog.warn("workspace_scene_observer", "WindowState visibility hook error", error)
                        }
                        return result
                    }
                })
            }
            .size

        if (hooksInstalled > 0) {
            NeXtepLog.info("workspace_scene_observer", "Installed $hooksInstalled WindowState#setRequestedVisibleTypes observers")
        }
    }

    private fun installDisplayOrientationHook(module: XposedModule, classLoader: ClassLoader) {
        val displayRotationClass = runCatching {
            classLoader.loadClass("com.android.server.wm.DisplayRotation")
        }.getOrNull() ?: return

        val updateOrientationMethod = displayRotationClass.declaredMethods.firstOrNull { method ->
            method.name == "updateOrientation" &&
                method.parameterCount == 2 &&
                method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                method.parameterTypes[1] == Boolean::class.javaPrimitiveType
        } ?: return

        updateOrientationMethod.isAccessible = true
        module.hook(updateOrientationMethod).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val displayRotation = chain.thisObject
                val newOrientation = chain.getArg(0) as? Int
                if (displayRotation != null && newOrientation != null &&
                    WorkspaceSceneRequest.isLandscapeRequest(newOrientation)
                ) {
                    runCatching {
                        onDisplayOrientationUpdating(displayRotation, newOrientation)
                    }.onFailure { error ->
                        NeXtepLog.warn("workspace_scene_observer", "Display orientation hook error", error)
                    }
                }
                return chain.proceed()
            }
        })
        NeXtepLog.info("workspace_scene_observer", "Installed DisplayRotation#updateOrientation observer")
    }

    private fun installDisplayRotationHoldHook(module: XposedModule, classLoader: ClassLoader) {
        val displayRotationClass = runCatching {
            classLoader.loadClass("com.android.server.wm.DisplayRotation")
        }.getOrNull() ?: return

        val updateMethod = displayRotationClass.declaredMethods.firstOrNull { method ->
            method.name == "updateRotationUnchecked" &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == Boolean::class.javaPrimitiveType
        } ?: displayRotationClass.declaredMethods.firstOrNull { method ->
            method.name == "updateRotation" &&
                method.parameterCount == 2 &&
                method.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                method.parameterTypes[1] == Boolean::class.javaPrimitiveType
        } ?: run {
            NeXtepLog.warn("workspace_scene_observer", "No compatible DisplayRotation update method found")
            return
        }

        updateMethod.isAccessible = true
        module.hook(updateMethod).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                if (bypassNextRotationUpdate.get() == true) {
                    return chain.proceed()
                }
                val displayRotation = chain.thisObject ?: return chain.proceed()
                val shouldHold = runCatching {
                    shouldHoldRotation(displayRotation)
                }.getOrDefault(false)
                if (shouldHold) {
                    return false
                }
                return chain.proceed()
            }
        })
        NeXtepLog.info("workspace_scene_observer", "Installed DisplayRotation#${updateMethod.name} hold hook")
    }

    private fun onActivityOrientationChanging(
        activityRecord: Any,
        requestedOrientation: Int,
        beforeProceed: Boolean,
    ): Boolean {
        val context = resolveWmContext(activityRecord) ?: return false
        SystemServerWorkspaceBridge.startGeometryObservation(context)
        SystemServerWorkspaceBridge.startSceneObservation(context)
        if (!SystemServerWorkspaceBridge.isFastWorkspaceActive(context)) return false
        if (!isActivityRelevant(activityRecord)) return false

        val displayName = resolveDisplayName(activityRecord)
        val onObservedPane = WorkspaceVirtualDisplayNames.isSceneObservedVirtualDisplay(displayName)
        if (beforeProceed && onObservedPane) return false
        if (!beforeProceed && !onObservedPane && WorkspaceSceneRequest.isLandscapeRequest(requestedOrientation)) {
            return false
        }
        if (!beforeProceed && !onObservedPane) return false

        val task = call(activityRecord, "getTask") ?: return false
        val taskId = field(task, "mTaskId") as? Int ?: return false
        if (taskId <= 0) return false

        val fullscreen = isMainWindowStatusBarHidden(activityRecord)
        val canHold = !onObservedPane && WorkspaceSceneRequest.isLandscapeRequest(requestedOrientation)
        val request = WorkspaceSceneRequest(
            taskId = taskId,
            orientation = requestedOrientation,
            timestampMs = SystemClock.elapsedRealtime(),
            canHold = canHold,
            fullscreen = fullscreen,
        )
        SystemServerWorkspaceBridge.publishSceneRequestAsync(context, request)
        return true
    }

    private fun onDisplayOrientationUpdating(displayRotation: Any, newOrientation: Int) {
        val display = field(displayRotation, "mDisplayContent") ?: return
        val displayId = field(display, "mDisplayId") as? Int ?: return
        if (displayId != Display.DEFAULT_DISPLAY) return

        val context = field(displayRotation, "mContext") as? Context ?: return
        SystemServerWorkspaceBridge.startGeometryObservation(context)
        SystemServerWorkspaceBridge.startSceneObservation(context)
        if (!SystemServerWorkspaceBridge.isFastWorkspaceActive(context)) return

        val mode = SystemServerWorkspaceBridge.landscapeVideoMode(context)
        if (mode != com.elbert.nextep.workspace.LandscapeVideoMode.TURNED &&
            mode != com.elbert.nextep.workspace.LandscapeVideoMode.TOP
        ) {
            return
        }

        val sourceApp = call(display, "getLastOrientationSourceApp")
            ?: call(display, "topRunningActivity")
            ?: return
        if (!isActivityRelevant(sourceApp)) return

        val task = call(sourceApp, "getTask") ?: return
        val taskId = field(task, "mTaskId") as? Int ?: return
        if (taskId <= 0) return

        val nowMs = SystemClock.elapsedRealtime()
        val existing = SystemServerWorkspaceBridge.latestSceneRequest(context)
        if (existing != null &&
            existing.taskId == taskId &&
            existing.orientation == newOrientation &&
            existing.canHoldRotation(nowMs, DUPLICATE_REQUEST_DEBOUNCE_MS)
        ) {
            return
        }

        val fullscreen = isMainWindowStatusBarHidden(sourceApp)
        val request = WorkspaceSceneRequest(
            taskId = taskId,
            orientation = newOrientation,
            timestampMs = nowMs,
            canHold = true,
            fullscreen = fullscreen,
        )
        SystemServerWorkspaceBridge.publishSceneRequestAsync(context, request)
    }

    private fun onWindowVisibilityChanged(windowState: Any, visibleTypes: Int?) {
        val lp = field(windowState, "mAttrs") as? WindowManager.LayoutParams ?: return
        if (lp.type < WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW ||
            lp.type > WindowManager.LayoutParams.LAST_APPLICATION_WINDOW
        ) {
            return
        }
        val activityRecord = field(windowState, "mActivityRecord") ?: return
        val context = resolveWmContext(activityRecord) ?: return
        SystemServerWorkspaceBridge.startGeometryObservation(context)
        SystemServerWorkspaceBridge.startSceneObservation(context)
        if (!SystemServerWorkspaceBridge.isFastWorkspaceActive(context)) return
        if (!isActivityRelevant(activityRecord)) return

        val task = call(activityRecord, "getTask") ?: return
        val taskId = field(task, "mTaskId") as? Int ?: return
        if (taskId <= 0) return

        val fullscreen = WorkspaceSceneObserverPolicy.isStatusBarHidden(visibleTypes, lp.flags)
        val orientation = (call(activityRecord, "getRequestedOrientation") as? Int)
            ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        // Avoid spuriously treating portrait edge-to-edge apps as landscape scene requests, unless
        // the activity is already inside the rotated pane or actively reporting a landscape mode.
        val displayName = resolveDisplayName(activityRecord)
        val onObservedPane = WorkspaceVirtualDisplayNames.isSceneObservedVirtualDisplay(displayName)
        if (!onObservedPane && !WorkspaceSceneRequest.isLandscapeRequest(orientation)) {
            return
        }

        val nowMs = SystemClock.elapsedRealtime()
        val existing = SystemServerWorkspaceBridge.latestSceneRequest(context)
        val canHold = !onObservedPane && WorkspaceSceneRequest.isLandscapeRequest(orientation)
        if (existing != null &&
            existing.taskId == taskId &&
            existing.orientation == orientation &&
            existing.fullscreen == fullscreen &&
            existing.canHold == canHold &&
            existing.canHoldRotation(nowMs, DUPLICATE_REQUEST_DEBOUNCE_MS)
        ) {
            return
        }

        val request = WorkspaceSceneRequest(
            taskId = taskId,
            orientation = orientation,
            timestampMs = nowMs,
            canHold = canHold,
            fullscreen = fullscreen,
        )
        SystemServerWorkspaceBridge.publishSceneRequestAsync(context, request)
    }

    private fun shouldHoldRotation(displayRotation: Any): Boolean {
        val display = field(displayRotation, "mDisplayContent") ?: return false
        val displayId = field(display, "mDisplayId") as? Int ?: return false
        if (displayId != Display.DEFAULT_DISPLAY) return false

        val context = field(displayRotation, "mContext") as? Context ?: return false
        SystemServerWorkspaceBridge.startGeometryObservation(context)
        SystemServerWorkspaceBridge.startSceneObservation(context)
        if (!SystemServerWorkspaceBridge.isFastWorkspaceActive(context)) {
            cancelPendingHold()
            return false
        }

        val mode = SystemServerWorkspaceBridge.landscapeVideoMode(context)
        val request = SystemServerWorkspaceBridge.latestSceneRequest(context)
        val lastOrientation = field(displayRotation, "mLastOrientation") as? Int
        val currentRotation = field(displayRotation, "mRotation") as? Int
        val portraitRotation = field(displayRotation, "mPortraitRotation") as? Int
        val nowMs = SystemClock.elapsedRealtime()
        if (!WorkspaceSceneObserverPolicy.shouldHoldDisplayRotation(
                mode = mode,
                request = request,
                nowMs = nowMs,
                lastOrientation = lastOrientation,
                currentRotation = currentRotation,
                portraitRotation = portraitRotation,
            )
        ) {
            cancelPendingHold()
            return false
        }

        val geom = SystemServerWorkspaceBridge.observedActiveGeometry()
        val isWideViewport = geom != null && geom.screenWidth > geom.screenHeight
        val delayMs = WorkspaceSceneObserverPolicy.rotationHoldDelayMs(isWideViewport)

        val wms = field(displayRotation, "mService")
        val wmsUpdateRotation = wms?.javaClass?.methods?.firstOrNull { method ->
            method.name == "updateRotation" &&
                method.parameterCount == 2 &&
                method.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                method.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }?.apply { isAccessible = true }

        pendingHoldRunnable?.let(mainHandler::removeCallbacks)
        val runnable = Runnable {
            holdPending.set(false)
            runCatching {
                bypassNextRotationUpdate.set(true)
                if (wms != null && wmsUpdateRotation != null) {
                    wmsUpdateRotation.invoke(wms, false, false)
                }
            }.also {
                bypassNextRotationUpdate.remove()
            }
        }
        pendingHoldRunnable = runnable
        holdPending.set(true)
        mainHandler.postDelayed(runnable, delayMs)
        return true
    }

    private fun cancelPendingHold() {
        pendingHoldRunnable?.let(mainHandler::removeCallbacks)
        pendingHoldRunnable = null
        holdPending.set(false)
    }

    private fun isActivityRelevant(activityRecord: Any): Boolean {
        val activityType = (call(activityRecord, "getActivityType") as? Int)
            ?: ACTIVITY_TYPE_STANDARD
        if (activityType != ACTIVITY_TYPE_STANDARD) {
            return false
        }
        val pkg = field(activityRecord, "packageName") as? String
        if (pkg == "com.android.systemui") {
            return false
        }
        val windowingMode = (call(activityRecord, "getWindowingMode") as? Int)
            ?: WINDOWING_MODE_FULLSCREEN
        if (windowingMode == WINDOWING_MODE_FREEFORM ||
            windowingMode == WINDOWING_MODE_PINNED
        ) {
            return false
        }

        val displayId = (call(activityRecord, "getDisplayId") as? Int) ?: Display.INVALID_DISPLAY
        val displayName = resolveDisplayName(activityRecord)
        if (displayId != Display.DEFAULT_DISPLAY &&
            !WorkspaceVirtualDisplayNames.isSceneObservedVirtualDisplay(displayName)
        ) {
            return false
        }

        val visible = (call(activityRecord, "isVisibleRequested") as? Boolean)
            ?: (call(activityRecord, "isVisible") as? Boolean)
            ?: (field(activityRecord, "mVisibleRequested") as? Boolean)
            ?: true
        return visible
    }

    private fun isMainWindowStatusBarHidden(activityRecord: Any): Boolean {
        val mainWindow = call(activityRecord, "findMainWindow") ?: return false
        val lp = field(mainWindow, "mAttrs") as? WindowManager.LayoutParams ?: return false
        val visibleTypes = (call(mainWindow, "getRequestedVisibleTypes") as? Int)
        return WorkspaceSceneObserverPolicy.isStatusBarHidden(visibleTypes, lp.flags)
    }

    private fun resolveDisplayName(activityRecord: Any): String? {
        val display = call(activityRecord, "getDisplayContent")
            ?: field(activityRecord, "mDisplayContent")
            ?: return null
        val displayInfo = call(display, "getDisplayInfo")
            ?: field(display, "mDisplayInfo")
            ?: return null
        return field(displayInfo, "name") as? String
    }

    private fun resolveWmContext(activityRecord: Any): Context? {
        val wms = field(activityRecord, "mWmService") ?: return null
        return field(wms, "mContext") as? Context
    }

    private fun field(target: Any, name: String): Any? =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { type -> runCatching { type.getDeclaredField(name) }.getOrNull() }
            .firstOrNull()
            ?.apply { isAccessible = true }
            ?.get(target)

    private fun call(target: Any, name: String): Any? =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { type -> runCatching { type.getDeclaredMethod(name) }.getOrNull() }
            .firstOrNull()
            ?.apply { isAccessible = true }
            ?.invoke(target)
}
