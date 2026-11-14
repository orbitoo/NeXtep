package com.elbert.nextep.systemui

import android.app.ActivityManager
import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import android.view.SurfaceControl
import android.view.View
import android.view.WindowManager
import com.elbert.nextep.framework.HyperOsEnvironment
import com.elbert.nextep.framework.HyperOsFreeformCompat
import com.elbert.nextep.framework.TaskInfoCompat
import com.elbert.nextep.framework.TaskSurfaceCompat
import com.elbert.nextep.logging.NeXtepLog
import com.elbert.nextep.workspace.SystemServerWorkspaceBridge
import com.elbert.nextep.workspace.WorkspaceGeometry
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.Optional
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * HyperOS 3 SystemUI Shell & freeform compatibility hooks:
 * 1. Fits MIUI `Transitions#playTransition` root leashes to NeXtep's main content pane at
 *    frame 0 and restores root/child leashes on `Transitions#onFinish` or workspace exit.
 * 2. Tracks `MiuiFreeformModeTaskRepository` small windows (`common.taskmanager.MiuiFreeformModeTaskInfo`),
 *    bridges task IDs into `HyperOsFreeformCompat` + `Settings.Global`, and keeps `MiuiDecorationDot`
 *    above freeform leashes.
 * 3. Toggles `TYPE_STATUS_BAR` click-through & alpha when NeXtep is visible over the status bar.
 */
object HyperOsTransitionAndFreeformHooks {
    private data class TrackedTransitionRoot(
        val token: IBinder,
        val rootLeash: SurfaceControl,
        val originalOffset: Point,
        val childLeashes: List<SurfaceControl>,
        val taskIds: List<Int>,
    )

    private data class SavedStatusBarState(
        val view: View,
        val savedFlags: Int,
        val savedAlpha: Float,
    )

    private const val TRANSIT_OPEN = 1
    private const val TRANSIT_CLOSE = 2
    private const val TRANSIT_TO_FRONT = 3
    private const val TRANSIT_TO_BACK = 4
    private const val FLAG_IS_WALLPAPER = 1
    private const val DECOR_DOT_RELATIVE_Z = 1_000_000
    private val REASSERT_DELAYS_MS = longArrayOf(400L, 1500L)

    private val installed = AtomicBoolean(false)
    private val transitionGeneration = AtomicInteger(0)
    private val transitionsLock = Any()
    private val activeRoots = HashMap<IBinder, TrackedTransitionRoot>()
    private val freeformTaskInfos = ConcurrentHashMap<Int, Any>()
    private val savedStatusBars: MutableMap<View, SavedStatusBarState> =
        Collections.synchronizedMap(WeakHashMap())
    private val fieldCache = ConcurrentHashMap<String, Optional<Field>>()
    private val methodCache = ConcurrentHashMap<String, Optional<Method>>()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val pendingReasserts = ArrayList<Runnable>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var activeGeometry: WorkspaceGeometry? = null

    @Volatile
    private var statusBarHiddenRequested = false

    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (!HyperOsEnvironment.isHyperOs()) return
        if (!installed.compareAndSet(false, true)) return

        runCatching { installTransitionsRootHooks(module, classLoader) }
            .onFailure { NeXtepLog.warn("hyperos_transitions", "HyperOS transition root hooks unavailable", it) }

        runCatching { installMiuiFreeformHooks(module, classLoader) }
            .onFailure { NeXtepLog.warn("hyperos_freeform", "HyperOS freeform tracking unavailable", it) }
    }

    fun onWorkspaceStateChanged(context: Context, active: Boolean, geometry: WorkspaceGeometry?) {
        appContext = context.applicationContext ?: context
        if (!HyperOsEnvironment.isHyperOs()) return
        activeGeometry = if (active) geometry else null
        if (!active) {
            releaseAllTransitionRoots()
        }
        mainHandler.post {
            setPinnedStatusBarHidden(active)
        }
    }

    fun releaseAllTransitionRoots() {
        transitionGeneration.incrementAndGet()
        val drained = synchronized(transitionsLock) {
            activeRoots.values.toList().also { activeRoots.clear() }
        }
        if (drained.isEmpty()) return
        val tx = SurfaceControl.Transaction()
        try {
            for (entry in drained) {
                resetTransitionRoot(entry, tx)
            }
            tx.apply()
        } finally {
            tx.close()
        }
    }

    private fun installTransitionsRootHooks(module: XposedModule, classLoader: ClassLoader) {
        val transitionsClass = classLoader.loadClass("com.android.wm.shell.transition.Transitions")
        transitionsClass.declaredMethods
            .filter { it.name == "playTransitionWithTracing" }
            .forEach { runCatching { module.deoptimize(it) } }

        val playTransition = transitionsClass.declaredMethods.firstOrNull {
            it.name == "playTransition" && it.parameterCount == 1
        }?.apply { isAccessible = true } ?: return
        val activeTransitionClass = playTransition.parameterTypes[0]
        val tokenField = findField(activeTransitionClass, "mToken") ?: return
        val infoField = findField(activeTransitionClass, "mInfo") ?: return
        val startTField = findField(activeTransitionClass, "mStartT") ?: return
        val finishTField = findField(activeTransitionClass, "mFinishT")

        val activeByToken = ConcurrentHashMap<IBinder, Any>()

        module.hook(playTransition).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val activeTransition = chain.args.firstOrNull()
                if (activeTransition != null && !HyperOsEnvironment.isMagnificationFallbackActive()) {
                    val genBefore = transitionGeneration.get()
                    val geom = activeGeometry
                    val token = runCatching { tokenField.get(activeTransition) as? IBinder }.getOrNull()
                    val info = runCatching { infoField.get(activeTransition) }.getOrNull()
                    val startT = runCatching { startTField.get(activeTransition) as? SurfaceControl.Transaction }.getOrNull()
                    if (geom != null && token != null && info != null && startT != null && isMainPaneTransition(info)) {
                        fitTransitionRoot(token, info, startT, geom, genBefore)
                        activeByToken[token] = activeTransition
                    }
                }
                return chain.proceed()
            }
        })

        val onFinish = transitionsClass.declaredMethods.firstOrNull {
            it.name == "onFinish" && it.parameterTypes.firstOrNull() == IBinder::class.java
        }?.apply { isAccessible = true }
        if (onFinish != null) {
            module.hook(onFinish).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val token = chain.args.firstOrNull() as? IBinder
                    if (token != null) {
                        val activeTransition = activeByToken.remove(token)
                        val finishT = if (activeTransition != null && finishTField != null) {
                            runCatching { finishTField.get(activeTransition) as? SurfaceControl.Transaction }.getOrNull()
                        } else null
                        handleTransitionFinish(token, finishT)
                    }
                    return chain.proceed()
                }
            })
        }
        NeXtepLog.info("hyperos_transitions", "Installed Shell Transitions root fitting hooks")
    }

    private fun isMainPaneTransition(info: Any): Boolean {
        val type = call(info, "getType") as? Int ?: return false
        if (type !in TRANSIT_OPEN..TRANSIT_TO_BACK) return false
        val changes = call(info, "getChanges") as? List<*> ?: return false
        if (changes.isEmpty()) return false
        var hasTaskChange = false
        for (change in changes) {
            if (change == null) return false
            val endDisplay = call(change, "getEndDisplayId") as? Int ?: return false
            val startDisplay = call(change, "getStartDisplayId") as? Int ?: return false
            if (endDisplay != Display.DEFAULT_DISPLAY || startDisplay !in -1..Display.DEFAULT_DISPLAY) return false
            val startRot = call(change, "getStartRotation") as? Int ?: -1
            val endRot = call(change, "getEndRotation") as? Int ?: -1
            if (startRot >= 0 && endRot >= 0 && startRot != endRot) return false
            val task = call(change, "getTaskInfo") as? ActivityManager.RunningTaskInfo
            if (task != null) {
                hasTaskChange = true
                val state = TaskInfoCompat.readWindowState(task) ?: return false
                if (state.displayId != Display.DEFAULT_DISPLAY ||
                    state.windowingMode != 1 ||
                    state.vendorWindowed ||
                    HyperOsFreeformCompat.isTrackedFreeformTask(task.taskId)
                ) {
                    return false
                }
            }
        }
        return hasTaskChange
    }

    private fun fitTransitionRoot(
        token: IBinder,
        info: Any,
        startT: SurfaceControl.Transaction,
        geom: WorkspaceGeometry,
        expectedGeneration: Int,
    ) {
        val rootCount = call(info, "getRootCount") as? Int ?: return
        var targetRoot: Any? = null
        for (i in 0 until rootCount) {
            val candidate = call(info, "getRoot", i) ?: continue
            if (call(candidate, "getDisplayId") as? Int == Display.DEFAULT_DISPLAY) {
                targetRoot = candidate
                break
            }
        }
        if (targetRoot == null) return
        val rootLeash = call(targetRoot, "getLeash") as? SurfaceControl ?: return
        if (!rootLeash.isValid) return
        val offset = (call(targetRoot, "getOffset") as? Point)?.let(::Point) ?: Point(0, 0)

        val scale = geom.contentScale
        val tx = geom.contentTranslationX + scale * offset.x
        val ty = geom.contentTranslationY + scale * offset.y
        val crop = Rect(
            geom.availableLeft - offset.x,
            geom.availableTop - offset.y,
            geom.availableRight - offset.x,
            geom.availableBottom - offset.y,
        )

        val changes = call(info, "getChanges") as? List<*> ?: emptyList<Any>()
        val childLeashes = ArrayList<SurfaceControl>()
        val taskIds = ArrayList<Int>()
        for (change in changes) {
            if (change == null) continue
            val sc = call(change, "getLeash") as? SurfaceControl
            if (sc != null && sc.isValid) {
                childLeashes += sc
                val flags = call(change, "getFlags") as? Int ?: 0
                val task = call(change, "getTaskInfo") as? ActivityManager.RunningTaskInfo
                if (task != null) taskIds += task.taskId
                // Reset child task transforms so root scaling doesn't double-transform children.
                startT.setScale(sc, 1f, 1f)
                startT.setPosition(sc, 0f, 0f)
                startT.setCrop(sc, null)
                if (flags and FLAG_IS_WALLPAPER != 0) {
                    clearBlurBestEffort(startT, sc)
                }
            }
        }

        startT.setCrop(rootLeash, crop)
        startT.setScale(rootLeash, scale, scale)
        startT.setPosition(rootLeash, tx, ty)

        // Apply immediately at frame 0 before WM renders the first transition frame.
        val immediate = SurfaceControl.Transaction()
        try {
            immediate.setCrop(rootLeash, crop)
            immediate.setScale(rootLeash, scale, scale)
            immediate.setPosition(rootLeash, tx, ty)
            immediate.apply()
        } finally {
            immediate.close()
        }

        synchronized(transitionsLock) {
            if (transitionGeneration.get() == expectedGeneration && activeGeometry != null) {
                activeRoots[token] = TrackedTransitionRoot(token, rootLeash, offset, childLeashes, taskIds)
            }
        }
    }

    private fun handleTransitionFinish(token: IBinder, finishT: SurfaceControl.Transaction?) {
        val tracked = synchronized(transitionsLock) { activeRoots.remove(token) } ?: return
        if (finishT != null) {
            resetTransitionRoot(tracked, finishT)
        } else {
            val tx = SurfaceControl.Transaction()
            try {
                resetTransitionRoot(tracked, tx)
                tx.apply()
            } finally {
                tx.close()
            }
        }
        val geom = activeGeometry
        if (geom != null) {
            for (taskId in tracked.taskIds) {
                if (TaskSurfaceCompat.ownsMainTask(taskId)) {
                    TaskSurfaceCompat.reapply(taskId, geom)
                }
            }
        }
    }

    private fun resetTransitionRoot(entry: TrackedTransitionRoot, tx: SurfaceControl.Transaction) {
        if (entry.rootLeash.isValid) {
            tx.setScale(entry.rootLeash, 1f, 1f)
            tx.setPosition(entry.rootLeash, entry.originalOffset.x.toFloat(), entry.originalOffset.y.toFloat())
            tx.setCrop(entry.rootLeash, null)
        }
        for (sc in entry.childLeashes) {
            if (sc.isValid) {
                tx.setScale(sc, 1f, 1f)
                tx.setPosition(sc, 0f, 0f)
            }
        }
    }

    private fun installMiuiFreeformHooks(module: XposedModule, classLoader: ClassLoader) {
        val repoClass = runCatching {
            classLoader.loadClass("com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeTaskRepository")
        }.getOrNull() ?: return

        val taskInfoClass = runCatching {
            classLoader.loadClass("com.android.wm.shell.multitasking.common.taskmanager.MiuiFreeformModeTaskInfo")
        }.getOrNull() ?: runCatching {
            classLoader.loadClass("com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeTaskInfo")
        }.getOrNull() ?: return

        repoClass.declaredMethods
            .filter {
                (it.name == "onTaskAppeared" || it.name == "onTaskModeChanged") &&
                    it.parameterTypes.lastOrNull() == taskInfoClass
            }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        val info = chain.args.lastOrNull()
                        if (info != null) {
                            val taskId = readTaskId(info)
                            if (taskId != null && taskId >= 0) {
                                freeformTaskInfos[taskId] = info
                                if (HyperOsFreeformCompat.trackFreeformTask(taskId)) {
                                    publishTrackedFreeformTasks()
                                }
                            }
                        }
                        return result
                    }
                })
            }

        repoClass.declaredMethods
            .filter { it.name == "onTaskVanished" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        val taskId = chain.args.firstOrNull() as? Int
                        if (taskId != null && taskId >= 0) {
                            freeformTaskInfos.remove(taskId)
                            if (HyperOsFreeformCompat.untrackFreeformTask(taskId)) {
                                publishTrackedFreeformTasks()
                            }
                        }
                        return result
                    }
                })
            }

        runCatching {
            val dotClass = classLoader.loadClass(
                "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationDot",
            )
            dotClass.declaredMethods
                .filter { it.name == "addWindow" }
                .forEach { method ->
                    method.isAccessible = true
                    module.hook(method).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val dotWindow = chain.proceed() ?: return null
                            if (activeGeometry == null) return dotWindow
                            val dotHost = chain.thisObject ?: return dotWindow
                            raiseDotWindowAboveFreeformTask(dotHost, dotWindow)
                            return dotWindow
                        }
                    })
                }
        }

        NeXtepLog.info("hyperos_freeform", "Installed MiuiFreeformModeTaskRepository & MiuiDecorationDot hooks")
    }

    private fun raiseDotWindowAboveFreeformTask(dotHost: Any, dotWindow: Any) {
        runCatching {
            val runningTask = findField(dotHost.javaClass, "mRunningTaskInfo")
                ?.get(dotHost) as? ActivityManager.RunningTaskInfo ?: return
            val taskInfo = freeformTaskInfos[runningTask.taskId] ?: return
            val leash = call(taskInfo, "getLeash") as? SurfaceControl ?: return
            val winSc = call(dotWindow, "getWindowSurface") as? SurfaceControl ?: return
            if (!leash.isValid || !winSc.isValid) return
            val tx = SurfaceControl.Transaction()
            try {
                val method = methodCache.computeIfAbsent("Transaction#setRelativeLayer") {
                    val found = runCatching {
                        SurfaceControl.Transaction::class.java.getMethod(
                            "setRelativeLayer",
                            SurfaceControl::class.java,
                            SurfaceControl::class.java,
                            Int::class.javaPrimitiveType,
                        )
                    }.getOrNull()
                    Optional.ofNullable(found)
                }.orElse(null) ?: return
                method.invoke(tx, winSc, leash, DECOR_DOT_RELATIVE_Z)
                tx.apply()
            } finally {
                tx.close()
            }
        }
    }

    private fun publishTrackedFreeformTasks() {
        val ctx = appContext ?: return
        SystemServerWorkspaceBridge.publishMiuiFreeformTasks(ctx)
    }

    /**
     * Toggles physical `TYPE_STATUS_BAR` view touch-through & alpha on SystemUI's UI thread while
     * NeXtep renders its top control bar over the status bar region.
     */
    fun setPinnedStatusBarHidden(hidden: Boolean) {
        statusBarHiddenRequested = hidden
        cancelPendingStatusBarReasserts()
        val statusBarViews = findStatusBarWindowViews()
        if (hidden) {
            val target = statusBarViews.firstOrNull { it.isShown || it.visibility == View.VISIBLE }
                ?: statusBarViews.firstOrNull() ?: return
            applyHiddenStatusBar(target)
            for (delay in REASSERT_DELAYS_MS) {
                val runnable = Runnable {
                    if (statusBarHiddenRequested) {
                        findStatusBarWindowViews().forEach(::applyHiddenStatusBar)
                    }
                }
                pendingReasserts += runnable
                mainHandler.postDelayed(runnable, delay)
            }
        } else {
            val savedEntries = synchronized(savedStatusBars) {
                savedStatusBars.values.toList().also { savedStatusBars.clear() }
            }
            for (saved in savedEntries) {
                val lp = saved.view.layoutParams as? WindowManager.LayoutParams ?: continue
                val wm = saved.view.context?.getSystemService(WindowManager::class.java) ?: continue
                if (lp.flags != saved.savedFlags) {
                    lp.flags = saved.savedFlags
                    runCatching { wm.updateViewLayout(saved.view, lp) }
                }
                saved.view.animate().cancel()
                saved.view.alpha = saved.savedAlpha
            }
        }
    }

    private fun applyHiddenStatusBar(target: View) {
        val wm = target.context?.getSystemService(WindowManager::class.java) ?: return
        val lp = target.layoutParams as? WindowManager.LayoutParams ?: return
        synchronized(savedStatusBars) {
            if (!savedStatusBars.containsKey(target)) {
                savedStatusBars[target] = SavedStatusBarState(target, lp.flags, target.alpha)
            }
        }
        val newFlags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (lp.flags != newFlags) {
            lp.flags = newFlags
            runCatching { wm.updateViewLayout(target, lp) }
        }
        target.animate().cancel()
        target.alpha = 0.0f
    }

    private fun cancelPendingStatusBarReasserts() {
        pendingReasserts.forEach(mainHandler::removeCallbacks)
        pendingReasserts.clear()
    }

    private fun findStatusBarWindowViews(): List<View> = runCatching {
        val wmGlobalClass = Class.forName("android.view.WindowManagerGlobal")
        val instance = wmGlobalClass.getDeclaredMethod("getInstance").invoke(null)
        val viewsField = wmGlobalClass.getDeclaredField("mViews").apply { isAccessible = true }
        val views = (viewsField.get(instance) as? List<*>)?.filterIsInstance<View>() ?: return emptyList()
        views.filter {
            (it.layoutParams as? WindowManager.LayoutParams)?.type == WindowManager.LayoutParams.TYPE_STATUS_BAR
        }
    }.getOrDefault(emptyList())

    private fun clearBlurBestEffort(tx: SurfaceControl.Transaction, sc: SurfaceControl) {
        runCatching {
            val method = methodCache.computeIfAbsent("Transaction#setBackgroundBlurRadius") {
                val found = runCatching {
                    SurfaceControl.Transaction::class.java.getMethod(
                        "setBackgroundBlurRadius",
                        SurfaceControl::class.java,
                        Int::class.javaPrimitiveType,
                    )
                }.getOrNull()
                Optional.ofNullable(found)
            }.orElse(null)
            method?.invoke(tx, sc, 0)
        }
    }

    private fun readTaskId(taskInfo: Any): Int? {
        (taskInfo as? ActivityManager.RunningTaskInfo)?.taskId?.let { return it }
        (call(taskInfo, "getTaskId") as? Int)?.let { return it }
        val taskIdField = findField(taskInfo.javaClass, "taskId") ?: findField(taskInfo.javaClass, "mTaskId")
        val direct = taskIdField?.let { runCatching { it.getInt(taskInfo) }.getOrNull() }
        if (direct != null) return direct
        val nested = (findField(taskInfo.javaClass, "mTaskInfo")?.get(taskInfo) as? ActivityManager.RunningTaskInfo)
            ?: (call(taskInfo, "getTaskInfo") as? ActivityManager.RunningTaskInfo)
        return nested?.taskId
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
