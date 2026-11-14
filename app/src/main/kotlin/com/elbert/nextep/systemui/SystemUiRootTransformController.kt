package com.elbert.nextep.systemui

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.elbert.nextep.launcher.TouchCoordinateMapper
import com.elbert.nextep.logging.NeXtepLog
import com.elbert.nextep.workspace.WorkspaceGeometry
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

object SystemUiRootTransformController {
    private data class OriginalTransform(
        val pivotX: Float,
        val pivotY: Float,
        val scaleX: Float,
        val scaleY: Float,
        val translationX: Float,
        val translationY: Float,
        val clipBounds: Rect?,
    )

    private val roots = WeakHashMap<View, OriginalTransform>()
    private val transformedRoots = WeakHashMap<View, Boolean>()
    private val shadeMonitors = WeakHashMap<View, Runnable>()
    private val monitorHandler = Handler(Looper.getMainLooper())
    private val shadeAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit

        override fun onViewDetachedFromWindow(view: View) {
            restore(view)
        }
    }
    private var active = false
    private var geometry: WorkspaceGeometry? = null

    @Volatile
    private var touchDragInProgress = false

    fun observeRoot(view: View) {
        if (!isNotificationShadeRoot(view)) {
            if (view.width == 0 && view.rootView === view &&
                (windowTitle(view).contains("NotificationShade", ignoreCase = true) ||
                    view.javaClass.name.contains("NotificationShadeWindowView"))
            ) {
                view.post { observeRoot(view) }
            }
            return
        }
        roots.getOrPut(view) {
            NeXtepLog.info(
                "systemui_root_transform",
                "Observed shade root title=${windowTitle(view)} class=${view.javaClass.name}",
            )
            OriginalTransform(
                pivotX = view.pivotX,
                pivotY = view.pivotY,
                scaleX = view.scaleX,
                scaleY = view.scaleY,
                translationX = view.translationX,
                translationY = view.translationY,
                clipBounds = view.clipBounds?.let(::Rect),
            )
        }
    }

    fun setActive(requestedActive: Boolean, fitExistingShade: Boolean = true) {
        active = requestedActive
        if (active) {
            discoverExistingRoots()
            // A top-corner workspace gesture can be confirmed while the shade is already open.
            // In that path the root was observed by the same touch dispatch, but there is no
            // subsequent downward shade gesture to start its transform. Apply all live observed
            // shade roots as part of activation so an already-rendered control centre moves into
            // the content viewport immediately.
            if (fitExistingShade) beginObservedShadeTransform()
        } else {
            shadeMonitors.keys.toList().forEach(::stopMonitoring)
            roots.keys.toList().forEach(::restore)
            transformedRoots.clear()
        }
        NeXtepLog.info("systemui_root_transform", "active=$active roots=${roots.size}")
    }

    fun isActive(): Boolean = active

    fun setTouchDragInProgress(inProgress: Boolean) {
        touchDragInProgress = inProgress
    }

    fun discoverExistingRoots() {
        HyperOsShadeTransformHooks.discoverWindowManagerRoots(::observeRoot)
    }

    fun currentShadeCropRect(): Rect? {
        val currentGeometry = geometry ?: return null
        return SystemUiShadeTransformPolicy.resolveShadeSurfaceCropRect(
            contentLeft = currentGeometry.contentLeft,
            contentTop = currentGeometry.contentTop,
            contentRight = currentGeometry.contentRight,
            contentBottom = currentGeometry.contentBottom,
        )
    }

    fun onHyperOsShadeExpanded(expanded: Boolean, forceRestore: Boolean = false) {
        if (expanded && !forceRestore) {
            if (!active || geometry == null) return
            discoverExistingRoots()
            beginObservedShadeTransform()
        } else if (forceRestore || !touchDragInProgress) {
            transformedRoots.keys.toList().forEach(::restore)
        }
    }

    fun setGeometry(value: WorkspaceGeometry) {
        geometry = value
        if (active) {
            transformedRoots.keys.toList().forEach { view ->
                if (view.parent != null) apply(view)
            }
        }
        NeXtepLog.info("systemui_root_transform", "geometry=$value")
    }

    fun mapToContent(view: View, event: MotionEvent?): TouchCoordinateMapper.Transform? {
        observeRoot(view)
        if (!active || transformedRoots[view] != true) return null
        val transform = transformFor()
        return transform.takeIf { TouchCoordinateMapper.toContent(event, it) }
    }

    fun mapToScreen(event: MotionEvent?, transform: TouchCoordinateMapper.Transform?) {
        TouchCoordinateMapper.toScreen(event, transform)
    }

    fun activeTransformForTransformedRoot(view: View?): TouchCoordinateMapper.Transform? {
        val root = view?.rootView ?: return null
        if (!active || transformedRoots[root] != true || root.width <= 1 || root.height <= 1) {
            return null
        }
        return transformFor()
    }

    fun mapLocationToContent(view: View, location: IntArray) {
        if (location.size < 2) return
        val transform = activeTransformForTransformedRoot(view) ?: return
        location[0] = SystemUiShadeTransformPolicy.inverseMapRoundedCoordinate(
            location[0],
            transform.scaleX,
            transform.translationX,
        )
        location[1] = SystemUiShadeTransformPolicy.inverseMapRoundedCoordinate(
            location[1],
            transform.scaleY,
            transform.translationY,
        )
    }

    fun mapBoundsToContent(view: View, bounds: Rect) {
        val transform = activeTransformForTransformedRoot(view) ?: return
        SystemUiShadeTransformPolicy.inverseMapRect(
            bounds = bounds,
            scaleX = transform.scaleX,
            scaleY = transform.scaleY,
            translationX = transform.translationX,
            translationY = transform.translationY,
        )
    }

    fun mapWindowLocationToContent(view: View, location: IntArray) {
        mapLocationToContent(view, location)
    }

    fun beginShadeTransform(view: View): Boolean {
        observeRoot(view)
        if (!active || geometry == null || !roots.containsKey(view)) return false
        apply(view)
        transformedRoots[view] = true
        monitorUntilCollapsed(view)
        return true
    }

    fun beginObservedShadeTransform(): Boolean {
        if (!active || geometry == null) return false
        var transformed = false
        roots.keys.toList().forEach { view ->
            if (view.parent != null && isNotificationShadeRoot(view)) {
                apply(view)
                transformedRoots[view] = true
                monitorUntilCollapsed(view)
                transformed = true
            }
        }
        return transformed
    }

    fun isNotificationShadeRoot(view: View): Boolean = isFullScreenRoot(view) &&
        (windowTitle(view).contains("NotificationShade", ignoreCase = true) ||
            view.javaClass.name.contains("NotificationShadeWindowView"))

    private fun apply(view: View) {
        val transform = transformFor()
        view.pivotX = 0f
        view.pivotY = 0f
        view.scaleX = transform.scaleX
        view.scaleY = transform.scaleY
        view.translationX = transform.translationX
        view.translationY = transform.translationY
        geometry?.let {
            view.clipBounds = Rect(it.availableLeft, it.availableTop, it.availableRight, it.availableBottom)
            SystemUiShadeInputController.apply(view, it)
        }
        if (isNotificationShadeRoot(view)) {
            HyperOsShadeTransformHooks.syncNotificationShadeSurfaceCrop(view, currentShadeCropRect())
        }
    }

    private fun restore(view: View) {
        stopMonitoring(view)
        transformedRoots.remove(view)
        SystemUiShadeInputController.restore(view)
        if (isNotificationShadeRoot(view)) {
            HyperOsShadeTransformHooks.syncNotificationShadeSurfaceCrop(view, null)
        }
        val original = roots[view] ?: return
        view.pivotX = original.pivotX
        view.pivotY = original.pivotY
        view.scaleX = original.scaleX
        view.scaleY = original.scaleY
        view.translationX = original.translationX
        view.translationY = original.translationY
        view.clipBounds = original.clipBounds
    }

    private fun monitorUntilCollapsed(view: View) {
        if (shadeMonitors.containsKey(view)) return
        // The runnable must not strongly retain the WeakHashMap's View key.
        val rootReference = WeakReference(view)
        val monitor = object : Runnable {
            override fun run() {
                val root = rootReference.get() ?: return
                if (shadeMonitors[root] !== this) return
                if (!active || !root.isAttachedToWindow || root.parent == null ||
                    root.windowVisibility != View.VISIBLE || !root.isShown ||
                    transformedRoots[root] != true
                ) {
                    restore(root)
                    return
                }
                monitorHandler.postDelayed(this, SHADE_VISIBILITY_POLL_MS)
            }
        }
        shadeMonitors[view] = monitor
        view.addOnAttachStateChangeListener(shadeAttachListener)
        monitorHandler.postDelayed(monitor, SHADE_VISIBILITY_POLL_MS)
    }

    private fun stopMonitoring(view: View) {
        shadeMonitors.remove(view)?.let(monitorHandler::removeCallbacks)
        view.removeOnAttachStateChangeListener(shadeAttachListener)
    }

    private fun transformFor(): TouchCoordinateMapper.Transform {
        val currentGeometry = checkNotNull(geometry) { "Workspace geometry must precede shade activation" }
        return TouchCoordinateMapper.Transform(
            scaleX = currentGeometry.contentScale,
            scaleY = currentGeometry.contentScale,
            translationX = currentGeometry.contentTranslationX,
            translationY = currentGeometry.contentTranslationY,
        )
    }

    private fun isFullScreenRoot(view: View): Boolean {
        val metrics = view.resources.displayMetrics
        return view.rootView === view &&
            view.width >= metrics.widthPixels - 2 &&
            view.height >= metrics.heightPixels - 2
    }

    private fun windowTitle(view: View): String =
        ((view.layoutParams as? WindowManager.LayoutParams)?.title ?: "").toString()

    private const val SHADE_VISIBILITY_POLL_MS = 120L
}
