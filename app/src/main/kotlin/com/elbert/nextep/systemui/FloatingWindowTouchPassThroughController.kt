package com.elbert.nextep.systemui

import android.graphics.Rect
import android.graphics.Region
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.ViewTreeObserver
import com.elbert.nextep.framework.HyperOsEnvironment
import com.elbert.nextep.framework.HyperOsFreeformCompat
import com.elbert.nextep.logging.NeXtepLog
import com.elbert.nextep.task.TaskRepository
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-Kotlin geometry & filtering policy for computing window-local touch pass-through cutouts
 * around visible native floating windows (HyperOS freeform, PiP, ColorOS flexible windows).
 */
object FloatingWindowTouchPassThroughPolicy {
    private const val WINDOWING_MODE_PINNED = 2
    private const val WINDOWING_MODE_FREEFORM = 5

    data class ScreenBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val isNonEmpty: Boolean
            get() = right > left && bottom > top
    }

    data class FloatingTaskCandidate(
        val taskId: Int,
        val displayId: Int,
        val bounds: ScreenBounds,
        val windowingMode: Int,
        val vendorWindowed: Boolean,
        val isVisible: Boolean,
    )

    fun screenBoundsToWindowLocalRects(
        windowX: Int,
        windowY: Int,
        windowWidth: Int,
        windowHeight: Int,
        floatingScreenBounds: List<ScreenBounds>,
    ): List<ScreenBounds> {
        if (windowWidth <= 0 || windowHeight <= 0 || floatingScreenBounds.isEmpty()) return emptyList()
        val out = ArrayList<ScreenBounds>(floatingScreenBounds.size)
        for (bounds in floatingScreenBounds) {
            if (!bounds.isNonEmpty) continue
            val localLeft = max(0, bounds.left - windowX)
            val localTop = max(0, bounds.top - windowY)
            val localRight = min(windowWidth, bounds.right - windowX)
            val localBottom = min(windowHeight, bounds.bottom - windowY)
            if (localRight > localLeft && localBottom > localTop) {
                out += ScreenBounds(localLeft, localTop, localRight, localBottom)
            }
        }
        return out
    }

    fun mergeFloatingScreenBounds(
        trackedBounds: Map<Int, ScreenBounds>,
        taskSnapshots: List<TaskRepository.TaskSnapshot>,
        isHyperOs: Boolean = HyperOsEnvironment.isHyperOs(),
        isTaskTracked: (Int) -> Boolean = HyperOsFreeformCompat::isTrackedFreeformTask,
    ): List<ScreenBounds> = mergeFloatingCandidates(
        trackedBounds = trackedBounds,
        candidates = taskSnapshots.map { snap ->
            FloatingTaskCandidate(
                taskId = snap.taskId,
                displayId = snap.displayId,
                bounds = ScreenBounds(snap.bounds.left, snap.bounds.top, snap.bounds.right, snap.bounds.bottom),
                windowingMode = snap.windowingMode,
                vendorWindowed = snap.vendorWindowed,
                isVisible = snap.isVisible,
            )
        },
        isHyperOs = isHyperOs,
        isTaskTracked = isTaskTracked,
    )

    fun mergeFloatingCandidates(
        trackedBounds: Map<Int, ScreenBounds>,
        candidates: List<FloatingTaskCandidate>,
        isHyperOs: Boolean = HyperOsEnvironment.isHyperOs(),
        isTaskTracked: (Int) -> Boolean = HyperOsFreeformCompat::isTrackedFreeformTask,
    ): List<ScreenBounds> {
        val byId = if (candidates.isEmpty()) emptyMap() else candidates.associateBy { it.taskId }
        val merged = LinkedHashMap<Int, ScreenBounds>()

        for ((taskId, bounds) in trackedBounds) {
            val candidate = byId[taskId]
            if (candidate != null && !candidate.isVisible) continue
            if (bounds.isNonEmpty) {
                merged[taskId] = bounds
            }
        }

        for (candidate in candidates) {
            if (candidate.displayId != Display.DEFAULT_DISPLAY || !candidate.isVisible) continue
            val isStandardFloating = candidate.windowingMode == WINDOWING_MODE_PINNED ||
                candidate.windowingMode == WINDOWING_MODE_FREEFORM
            val isVendorFloating = candidate.vendorWindowed && (!isHyperOs || isTaskTracked(candidate.taskId))
            if (!isStandardFloating && !isVendorFloating) continue
            if (merged.containsKey(candidate.taskId)) continue
            if (candidate.bounds.isNonEmpty) {
                merged[candidate.taskId] = candidate.bounds
            }
        }

        return merged.values.toList()
    }
}

/**
 * Coordinates touch pass-through cutouts across NeXtep SystemUI overlay windows (`NeXtepTopBar`
 * and `NeXtepSidebar`) so native freeform/PiP windows and their drag handles stay interactive
 * even when overlapping a `TYPE_PHONE` NeXtep panel.
 */
object FloatingWindowTouchPassThroughController {
    private const val TOUCHABLE_INSETS_FRAME = 0
    private const val TOUCHABLE_INSETS_REGION = 3
    private const val SETTLE_REFRESH_DELAY_MS = 400L

    private data class SidebarInsetsBinding(
        val observer: ViewTreeObserver,
        val listener: Any,
    )

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val refreshExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NeXtep-FloatingBounds").apply { isDaemon = true }
    }
    private val refreshGeneration = AtomicInteger(0)
    private val registeredHosts = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))
    private val sidebarBindings = Collections.synchronizedMap(WeakHashMap<View, SidebarInsetsBinding>())
    private val settledRefreshRunnable = Runnable { requestHostLayout() }

    @Volatile
    private var taskRepository: TaskRepository? = null

    @Volatile
    private var cachedSnapshots: List<TaskRepository.TaskSnapshot> = emptyList()

    fun attachTaskRepository(repository: TaskRepository) {
        taskRepository = repository
        notifyFloatingWindowsChanged()
    }

    fun registerHost(view: View) {
        registeredHosts.add(view)
    }

    fun unregisterHost(view: View) {
        registeredHosts.remove(view)
    }

    fun installSidebarPassThrough(view: View) {
        registerHost(view)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                registerHost(v)
                attachSidebarInsetsListener(v)
            }

            override fun onViewDetachedFromWindow(v: View) {
                detachSidebarInsetsListener(v)
                unregisterHost(v)
            }
        })
        if (view.isAttachedToWindow) {
            attachSidebarInsetsListener(view)
        }
    }

    fun subtractFloatingRegions(view: View, region: Region): Boolean {
        val floatingBounds = currentFloatingScreenBounds()
        if (floatingBounds.isEmpty()) return false
        val root = view.rootView ?: view
        val rootWidth = root.width
        val rootHeight = root.height
        if (rootWidth <= 0 || rootHeight <= 0) return false
        val windowLoc = IntArray(2)
        root.getLocationOnScreen(windowLoc)
        val localRects = FloatingWindowTouchPassThroughPolicy.screenBoundsToWindowLocalRects(
            windowX = windowLoc[0],
            windowY = windowLoc[1],
            windowWidth = rootWidth,
            windowHeight = rootHeight,
            floatingScreenBounds = floatingBounds,
        )
        if (localRects.isEmpty()) return false
        val rect = Rect()
        for (local in localRects) {
            rect.set(local.left, local.top, local.right, local.bottom)
            region.op(rect, Region.Op.DIFFERENCE)
        }
        return true
    }

    fun notifyFloatingWindowsChanged() {
        mainHandler.post {
            requestHostLayout()
            mainHandler.removeCallbacks(settledRefreshRunnable)
            mainHandler.postDelayed(settledRefreshRunnable, SETTLE_REFRESH_DELAY_MS)
        }
        val repo = taskRepository ?: return
        val generation = refreshGeneration.incrementAndGet()
        refreshExecutor.execute {
            val snapshots = runCatching { repo.runningTasks() }.getOrDefault(emptyList())
            if (refreshGeneration.get() != generation) return@execute
            cachedSnapshots = snapshots
            mainHandler.post { requestHostLayout() }
        }
    }

    private fun currentFloatingScreenBounds(): List<FloatingWindowTouchPassThroughPolicy.ScreenBounds> {
        val tracked = HyperOsTransitionAndFreeformHooks.trackedFloatingBounds()
            .mapValues { (_, r) ->
                FloatingWindowTouchPassThroughPolicy.ScreenBounds(r.left, r.top, r.right, r.bottom)
            }
        return FloatingWindowTouchPassThroughPolicy.mergeFloatingScreenBounds(
            trackedBounds = tracked,
            taskSnapshots = cachedSnapshots,
        )
    }

    private fun requestHostLayout() {
        val hosts = synchronized(registeredHosts) { registeredHosts.toList() }
        for (host in hosts) {
            if (host.isAttachedToWindow) {
                host.requestLayout()
            }
        }
    }

    private fun attachSidebarInsetsListener(view: View) {
        if (sidebarBindings.containsKey(view)) return
        runCatching {
            val listenerClass = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
            val listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, arguments ->
                when (method.name) {
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                    "equals" -> return@newProxyInstance proxy === arguments?.firstOrNull()
                    "toString" -> return@newProxyInstance "NeXtepSidebarFloatingInsets"
                }
                if (method.name == "onComputeInternalInsets") {
                    val info = arguments?.firstOrNull() ?: return@newProxyInstance null
                    val floatingBounds = currentFloatingScreenBounds()
                    val root = view.rootView ?: view
                    val rootWidth = root.width
                    val rootHeight = root.height
                    val windowLoc = IntArray(2)
                    root.getLocationOnScreen(windowLoc)
                    val localRects = FloatingWindowTouchPassThroughPolicy.screenBoundsToWindowLocalRects(
                        windowX = windowLoc[0],
                        windowY = windowLoc[1],
                        windowWidth = rootWidth,
                        windowHeight = rootHeight,
                        floatingScreenBounds = floatingBounds,
                    )
                    val setTouchableInsets = info.javaClass.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
                    if (localRects.isEmpty() || rootWidth <= 0 || rootHeight <= 0) {
                        setTouchableInsets.invoke(info, TOUCHABLE_INSETS_FRAME)
                    } else {
                        setTouchableInsets.invoke(info, TOUCHABLE_INSETS_REGION)
                        val region = info.javaClass.getField("touchableRegion").get(info) as Region
                        region.set(0, 0, rootWidth, rootHeight)
                        val rect = Rect()
                        for (local in localRects) {
                            rect.set(local.left, local.top, local.right, local.bottom)
                            region.op(rect, Region.Op.DIFFERENCE)
                        }
                    }
                }
                null
            }
            val observer = view.viewTreeObserver
            observer.javaClass.getMethod("addOnComputeInternalInsetsListener", listenerClass).invoke(observer, listener)
            sidebarBindings[view] = SidebarInsetsBinding(observer, listener)
        }.onFailure {
            NeXtepLog.warn("floating_passthrough", "Unable to install sidebar touch pass-through", it)
        }
    }

    private fun detachSidebarInsetsListener(view: View) {
        val binding = sidebarBindings.remove(view) ?: return
        if (binding.observer.isAlive) {
            runCatching {
                val listenerClass = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
                binding.observer.javaClass.getMethod("removeOnComputeInternalInsetsListener", listenerClass)
                    .invoke(binding.observer, binding.listener)
            }
        }
    }
}
