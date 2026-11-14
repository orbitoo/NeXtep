package com.elbert.nextep.systemserver

import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.view.SurfaceControl
import android.view.WindowManager
import com.elbert.nextep.framework.HyperOsEnvironment
import com.elbert.nextep.framework.HyperOsFreeformCompat
import com.elbert.nextep.logging.NeXtepLog
import com.elbert.nextep.workspace.SystemServerWorkspaceBridge
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/** Keep panels in their stable system layer and place native floating tasks above them. */
class NativeFloatingWindowLayerHooker : XposedInterface.Hooker {
    private data class TaskLayer(
        val surface: SurfaceControl,
        val originalLayer: Int,
        val originalRelativeTo: SurfaceControl?,
        var anchor: SurfaceControl,
        var layer: Int,
    )

    private val taskLayers = WeakHashMap<Any, TaskLayer>()
    private val methods = ConcurrentHashMap<String, Method>()
    private val fields = ConcurrentHashMap<String, Field>()
    private var reportedFailure = false
    private val observationRequested = AtomicBoolean(false)
    private val miuiStubInitialized = AtomicBoolean(false)
    @Volatile private var miuiStubGetInstance: Method? = null
    @Volatile private var miuiStubGetStack: Method? = null
    @Volatile private var miuiStubGetMode: Method? = null
    private val observationHandler by lazy { Handler(Looper.getMainLooper()) }
    private var workspaceObserver: ContentObserver? = null
    @Volatile private var workspaceActive: Boolean? = null

    override fun intercept(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        // prepareSurfaces runs for unrelated apps too. This is an in-memory read;
        // settings IPC and subscription work run outside the WM traversal/lock.
        runCatching {
            val display = chain.thisObject ?: return@runCatching
            if (call(display, "getDisplayId") != 0) return@runCatching
            val transaction = call(display, "getPendingTransaction") as? SurfaceControl.Transaction ?: return@runCatching
            try {
                if (workspaceActive != false || taskLayers.isNotEmpty()) {
                    adjustDisplayLayers(display, transaction)
                }
            } finally {
                // The host bridge must initialize and restore even with the workspace closed.
                ControlAreaVideoController.prepare(display, transaction)
            }
        }.onFailure {
            if (!reportedFailure) {
                reportedFailure = true
                NeXtepLog.warn("native_floating_layer", "Native layer adjustment unavailable", it)
            }
        }
        return result
    }

    internal fun adjustDisplayLayers(
        display: Any,
        transaction: SurfaceControl.Transaction,
        workspaceConfirmedActive: Boolean = false,
    ) {
        startWorkspaceObservation(display)
        if (workspaceActive == false && !workspaceConfirmedActive) {
            restoreTaskLayers(transaction)
            return
        }
        val panels = mutableListOf<Any>()
        call(display, "forAllWindows", Consumer<Any> { window ->
            val attrs = field(window, "mAttrs") as? WindowManager.LayoutParams
            if (attrs?.packageName == "com.android.systemui" &&
                attrs.title?.toString() in PANEL_TITLES && call(window, "isVisible") == true) {
                val token = field(window, "mToken") ?: return@Consumer
                panels.add(token)
            }
        }, false)
        // Panel tokens retain their native absolute layers throughout the animation.
        // A relative link to a task that Shell temporarily hides/reparents also
        // takes the panel out of the stable system-window drawing order.
        val anchor = panels.lastOrNull()?.let { call(it, "getSurfaceControl") as? SurfaceControl }
        if (anchor?.isValid != true) {
            restoreTaskLayers(transaction)
            return
        }
        val roots = mutableListOf<Any>()
        call(display, "forAllTasks", Consumer<Any> { task ->
            // A cold notification launch has a requested-visible task before
            // its first application/splash buffer is ready.
            if ((call(task, "isVisible") != true && call(task, "isVisibleRequested") != true) ||
                !isFloating(task)) return@Consumer
            val candidate = call(task, "getRootTask") ?: task
            // Notification animations can temporarily put the floating task
            // under HOME. Never lift the whole fullscreen background with it.
            val root = if (isFloating(candidate)) candidate else task
            if (ControlAreaVideoController.ownsTask(root)) return@Consumer
            if (roots.none { it === root }) roots.add(root)
        }, false)
        restoreTaskLayers(transaction) { task -> roots.none { it === task } }
        roots.forEachIndexed { index, task ->
            val surface = call(task, "getSurfaceControl") as? SurfaceControl ?: return@forEachIndexed
            if (!surface.isValid) return@forEachIndexed
            val existing = taskLayers[task]?.takeIf { ownsTaskLayer(task, it) }
            val layer = index + 1
            val saved = existing ?: TaskLayer(surface,
                field(task, "mLastLayer") as? Int ?: return@forEachIndexed,
                field(task, "mLastRelativeToLayer") as? SurfaceControl, anchor, layer)
            saved.anchor = anchor
            saved.layer = layer
            taskLayers[task] = saved
            call(task, "assignRelativeLayer", transaction, anchor, layer, true)
        }
    }

    private fun ownsTaskLayer(task: Any, saved: TaskLayer): Boolean =
        call(task, "getSurfaceControl") === saved.surface &&
            field(task, "mLastRelativeToLayer") === saved.anchor &&
            field(task, "mLastLayer") == saved.layer

    private fun restoreTaskLayers(
        transaction: SurfaceControl.Transaction,
        shouldRestore: (Any) -> Boolean = { true },
    ) {
        taskLayers.entries.toList().forEach { (task, saved) ->
            if (!shouldRestore(task)) return@forEach
            if (!saved.surface.isValid || !ownsTaskLayer(task, saved)) {
                // WM may already have assigned a newer native layer or replaced the surface.
                taskLayers.remove(task)
                return@forEach
            }
            val relativeTo = saved.originalRelativeTo?.takeIf { it.isValid }
            if (relativeTo != null) {
                call(task, "assignRelativeLayer", transaction, relativeTo, saved.originalLayer, true)
            } else {
                call(task, "assignLayer", transaction, saved.originalLayer)
            }
            // assignLayer can be deferred by TransitionController. Keep ownership
            // until a later native prepareSurfaces pass actually restores the layer.
            if (!ownsTaskLayer(task, saved)) taskLayers.remove(task)
        }
    }

    private fun startWorkspaceObservation(display: Any) {
        if (observationRequested.get()) return
        val service = field(display, "mWmService") ?: field(display, "mAtmService") ?: return
        val context = field(service, "mContext") as? Context ?: return
        if (!observationRequested.compareAndSet(false, true)) return
        observationHandler.post {
            runCatching {
                workspaceObserver = SystemServerWorkspaceBridge.observeWorkspaceActive(
                    context, observationHandler,
                ) { workspaceActive = it }
            }.onFailure {
                // Unknown state keeps the path gated by visible workspace panels.
                workspaceActive = null
                NeXtepLog.warn("native_floating_layer", "Activity observer unavailable; keeping fallback", it)
            }
        }
    }

    private fun isFloating(task: Any): Boolean {
        val mode = call(task, "getWindowingMode") as? Int
        if (mode == 5 || mode == 2) return true // Freeform and picture-in-picture.
        if (HyperOsFreeformCompat.isMiuiFreeformTaskContainer(task)) return true
        if (HyperOsEnvironment.isHyperOs()) {
            val taskId = HyperOsFreeformCompat.readTaskId(task)
            if (taskId != null && isMiuiFreeFormTaskSync(taskId)) return true
        }
        val config = call(task, "getConfiguration") as? Configuration ?: return false
        val extra = field(config, "mOplusExtraConfiguration") ?: return false
        return call(extra, "getScenario") in setOf(1, 2)
    }

    private fun isMiuiFreeFormTaskSync(taskId: Int): Boolean {
        if (taskId < 0) return false
        if (miuiStubInitialized.compareAndSet(false, true)) {
            runCatching {
                val stubClass = Class.forName("com.android.server.wm.MiuiFreeFormManagerServiceStub")
                miuiStubGetInstance = stubClass.getMethod("getInstance").apply { isAccessible = true }
                miuiStubGetStack = runCatching {
                    stubClass.getMethod("getMiuiFreeFormActivityStack", Int::class.javaPrimitiveType)
                        .apply { isAccessible = true }
                }.getOrNull()
                miuiStubGetMode = runCatching {
                    stubClass.getMethod("getFreeFormWindowMode", Int::class.javaPrimitiveType)
                        .apply { isAccessible = true }
                }.getOrNull()
            }
        }
        val instance = miuiStubGetInstance?.let { runCatching { it.invoke(null) }.getOrNull() } ?: return false
        val hasStack = miuiStubGetStack?.let { runCatching { it.invoke(instance, taskId) != null }.getOrDefault(false) } == true
        if (hasStack) return true
        val mode = miuiStubGetMode?.let { runCatching { it.invoke(instance, taskId) as? Int }.getOrNull() } ?: 0
        return mode > 0
    }

    private fun field(target: Any, name: String): Any? {
        val key = "${target.javaClass.name}#$name"
        val member = fields[key] ?: generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }
            .firstOrNull()?.apply { isAccessible = true; fields[key] = this } ?: return null
        return member.get(target)
    }

    private fun call(target: Any, name: String, vararg args: Any): Any? {
        val key = "${target.javaClass.name}#$name/${args.joinToString { it.javaClass.name }}"
        val method = methods[key] ?: generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }.firstOrNull { method ->
                method.name == name && method.parameterTypes.size == args.size &&
                    method.parameterTypes.indices.all { i ->
                        val type = method.parameterTypes[i]
                        type.isInstance(args[i]) ||
                            (type == Int::class.javaPrimitiveType && args[i] is Int) ||
                            (type == Boolean::class.javaPrimitiveType && args[i] is Boolean)
                    }
            }?.apply { isAccessible = true; methods[key] = this } ?: return null
        return method.invoke(target, *args)
    }

    private companion object {
        val PANEL_TITLES = setOf("NeXtepTopBar", "NeXtepSidebar", "NeXtepContentPanel")
    }
}
