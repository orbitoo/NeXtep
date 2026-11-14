package com.elbert.nextep.display

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import com.elbert.nextep.framework.ActivityTaskManagerCompat
import com.elbert.nextep.framework.TaskSurfaceCompat
import com.elbert.nextep.framework.VirtualDisplayFlagsCompat
import com.elbert.nextep.logging.NeXtepLog
import com.elbert.nextep.task.MainTaskPresentationCoordinator
import com.elbert.nextep.task.TaskRepository
import com.elbert.nextep.workspace.RotatedPaneTransformPolicy
import com.elbert.nextep.workspace.SystemServerWorkspaceBridge
import com.elbert.nextep.workspace.WorkspaceGeometry
import kotlin.math.PI

/**
 * Hosts a single landscape task inside `"NeXtep-rotated-main"` virtual display and renders
 * its buffer rotated ±90° over the workspace main pane with multi-touch & edge-back forwarding.
 */
class RotatedMainPaneHost(
    private val context: Context,
    private val windowManager: WindowManager,
    private val windowTypeProvider: () -> Int?,
    private val taskRepository: TaskRepository,
    private val mainTaskPresenter: MainTaskPresentationCoordinator,
    private val geometryProvider: () -> WorkspaceGeometry,
) {
    private val appContext = context.applicationContext ?: context
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)
    private val inputManager = appContext.getSystemService(InputManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val setDisplayIdMethod by lazy {
        runCatching {
            InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
        }.getOrNull()
    }
    private val injectInputEventMethod by lazy {
        runCatching {
            InputManager::class.java.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        }.getOrNull()
    }
    private val privateFlagsField by lazy {
        runCatching { WindowManager.LayoutParams::class.java.getField("privateFlags") }.getOrNull()
    }

    private var activeTaskId: Int? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var virtualDisplayId: Int? = null
    private var surface: Surface? = null
    private var overlayContainer: RotatedPaneContainer? = null
    private var textureView: TextureView? = null
    private var orientationListener: OrientationEventListener? = null

    private var hostWidth: Int = 0
    private var hostHeight: Int = 0
    private var displayWidth: Int = 0
    private var displayHeight: Int = 0
    private var currentDegree: Int = 90
    private var lastOrientationUpdateMs: Long = 0L

    private var pendingTaskId: Int? = null
    private var pendingMoveGeneration: Int = 0

    // Edge-back gesture tracking in local host coordinates
    private var edgeDownX: Float = 0f
    private var edgeDownY: Float = 0f
    private var downStartedInEdge: Boolean = false
    private var edgeBackTriggered: Boolean = false

    fun activeTaskId(): Int? = activeTaskId

    fun virtualDisplayId(): Int? = virtualDisplayId

    fun isActive(): Boolean = activeTaskId != null || pendingTaskId != null

    fun enter(taskId: Int, requestedOrientation: Int, geometry: WorkspaceGeometry): Boolean {
        if (taskId <= 0) return false
        if (geometry.isLandscape || geometry.contentWidth >= geometry.contentHeight) {
            return false
        }
        if (activeTaskId == taskId || pendingTaskId == taskId) {
            updateGeometry(geometry)
            return true
        }
        if (activeTaskId != null || pendingTaskId != null) {
            exitToPrimaryDisplay(presentInMain = false)
        }
        val windowType = windowTypeProvider() ?: return false
        if (geometry.contentWidth <= 0 || geometry.contentHeight <= 0) return false

        hostWidth = geometry.contentWidth
        hostHeight = geometry.contentHeight
        displayWidth = hostHeight
        displayHeight = hostWidth
        currentDegree = RotatedPaneTransformPolicy.initialDegreeForOrientation(requestedOrientation)
        pendingTaskId = taskId
        val generation = ++pendingMoveGeneration

        val container = createOverlayContainer(geometry)
        val params = createLayoutParams(windowType, geometry)
        val tex = TextureView(appContext).apply {
            isOpaque = false
            rotation = currentDegree.toFloat()
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    if (generation != pendingMoveGeneration) return
                    st.setDefaultBufferSize(displayWidth, displayHeight)
                    attachSurfaceAndMigrateTask(taskId, generation, Surface(st))
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                    st.setDefaultBufferSize(displayWidth, displayHeight)
                }

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
        }
        container.addView(tex)
        container.setTextureSize(displayWidth, displayHeight)
        container.setOnTouchListener { view, event -> handleHostTouch(view, event) }

        return runCatching {
            windowManager.addView(container, params)
            overlayContainer = container
            textureView = tex
            publishBackRegion(geometry)
            startOrientationObserver()
            true
        }.onFailure { error ->
            NeXtepLog.warn("rotated_main", "Failed to add rotated main overlay", error)
            pendingTaskId = null
            releaseResources()
        }.getOrDefault(false)
    }

    fun updateGeometry(geometry: WorkspaceGeometry) {
        val container = overlayContainer ?: return
        if (geometry.contentWidth <= 0 || geometry.contentHeight <= 0) return
        if (geometry.isLandscape || geometry.contentWidth >= geometry.contentHeight) {
            exitToPrimaryDisplay(presentInMain = true)
            return
        }
        val newHostW = geometry.contentWidth
        val newHostH = geometry.contentHeight
        val sizeChanged = newHostW != hostWidth || newHostH != hostHeight
        hostWidth = newHostW
        hostHeight = newHostH
        if (sizeChanged) {
            displayWidth = hostHeight
            displayHeight = hostWidth
            container.setTextureSize(displayWidth, displayHeight)
            textureView?.surfaceTexture?.setDefaultBufferSize(displayWidth, displayHeight)
            virtualDisplay?.resize(
                displayWidth,
                displayHeight,
                appContext.resources.displayMetrics.densityDpi,
            )
            activeTaskId?.let { tid ->
                ActivityTaskManagerCompat.resizeTask(tid, Rect(0, 0, displayWidth, displayHeight))
            }
        }
        val params = (container.layoutParams as? WindowManager.LayoutParams) ?: return
        params.x = geometry.contentLeft
        params.y = geometry.contentTop
        params.width = hostWidth
        params.height = hostHeight
        runCatching { windowManager.updateViewLayout(container, params) }
        publishBackRegion(geometry)
    }

    /**
     * Moves any active rotated task back onto Display 0 and tears down the rotated virtual display.
     * Returns the restored taskId if a task was hosted, or null otherwise.
     */
    fun exitToPrimaryDisplay(presentInMain: Boolean = true): Int? {
        pendingMoveGeneration += 1
        val taskToRestore = activeTaskId ?: pendingTaskId
        activeTaskId = null
        pendingTaskId = null
        SystemServerWorkspaceBridge.publishRotatedBackRegion(appContext, null)

        if (taskToRestore != null) {
            val snapshot = taskRepository.findTask(taskToRestore)
            if (snapshot != null) {
                runCatching {
                    if (snapshot.displayId != Display.DEFAULT_DISPLAY) {
                        moveTaskToDisplayAndWait(taskToRestore, Display.DEFAULT_DISPLAY)
                    }
                    ActivityTaskManagerCompat.resizeTask(taskToRestore, Rect())
                    taskRepository.bringTaskToFront(taskToRestore)
                    if (presentInMain) {
                        mainTaskPresenter.presentTask(taskToRestore, geometryProvider())
                    }
                }.onFailure { error ->
                    NeXtepLog.warn("rotated_main", "Failed restoring taskId=$taskToRestore to primary", error)
                }
            }
        }
        releaseResources()
        NeXtepLog.info("rotated_main", "Exited rotated main task=$taskToRestore presentInMain=$presentInMain")
        return taskToRestore
    }

    fun release() {
        exitToPrimaryDisplay(presentInMain = false)
    }

    private fun attachSurfaceAndMigrateTask(taskId: Int, generation: Int, newSurface: Surface) {
        surface?.release()
        surface = newSurface
        val vd = createRotatedVirtualDisplay(newSurface)
        if (vd == null) {
            newSurface.release()
            surface = null
            exitToPrimaryDisplay(presentInMain = true)
            return
        }
        virtualDisplay = vd
        virtualDisplayId = vd.display.displayId

        val vdId = vd.display.displayId
        suppressSystemDecors(vdId)
        val migrated = runCatching {
            mainTaskPresenter.restoreForMove(taskId)
            val snapshot = taskRepository.findTask(taskId)
                ?: error("Task $taskId vanished before migration")
            if (snapshot.resizeMode != TASK_RESIZE_MODE_RESIZEABLE) {
                ActivityTaskManagerCompat.setTaskResizeable(taskId, TASK_RESIZE_MODE_RESIZEABLE)
            }
            moveTaskToDisplayAndWait(taskId, vdId)
            ActivityTaskManagerCompat.resizeTask(taskId, Rect(0, 0, displayWidth, displayHeight))
            taskRepository.bringTaskToFront(taskId).getOrThrow()
            true
        }.onFailure { error ->
            NeXtepLog.warn("rotated_main", "Task migration to rotated display failed taskId=$taskId", error)
        }.getOrDefault(false)

        if (generation != pendingMoveGeneration) return
        if (!migrated) {
            exitToPrimaryDisplay(presentInMain = true)
            return
        }
        pendingTaskId = null
        activeTaskId = taskId
        NeXtepLog.info(
            "rotated_main",
            "Migrated taskId=$taskId into $vdId (${displayWidth}x$displayHeight, rot=$currentDegree)",
        )
    }

    private fun createRotatedVirtualDisplay(targetSurface: Surface): VirtualDisplay? {
        val dm = displayManager ?: return null
        val densityDpi = appContext.resources.displayMetrics.densityDpi
        for (flags in VirtualDisplayFlagsCompat.resolveCandidates()) {
            val config = VirtualDisplayConfig.Builder(
                RotatedPaneTransformPolicy.ROTATED_MAIN_DISPLAY_NAME,
                displayWidth,
                displayHeight,
                densityDpi,
            )
                .setSurface(targetSurface)
                .setFlags(flags.value)
                .build()
            val vd = runCatching { dm.createVirtualDisplay(config) }.getOrNull()
            if (vd != null) return vd
        }
        return null
    }

    private fun createOverlayContainer(geometry: WorkspaceGeometry): RotatedPaneContainer {
        val radiusPx = 12f * appContext.resources.displayMetrics.density
        return RotatedPaneContainer(appContext).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = false
            clipToPadding = false
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                }
            }
            clipToOutline = true
        }
    }

    @Suppress("DEPRECATION")
    private fun createLayoutParams(windowType: Int, geometry: WorkspaceGeometry): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        return WindowManager.LayoutParams(
            geometry.contentWidth,
            geometry.contentHeight,
            geometry.contentLeft,
            geometry.contentTop,
            windowType,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            title = "NeXtepRotatedMain"
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            runCatching {
                privateFlagsField?.let { f -> f.setInt(this, f.getInt(this) or PRIVATE_FLAG_NO_MOVE_ANIMATION) }
            }
        }
    }

    private fun publishBackRegion(geometry: WorkspaceGeometry) {
        val region = RotatedPaneTransformPolicy.computeScreenBackRegion(
            paneLeftOnScreen = geometry.contentLeft,
            paneTopOnScreen = geometry.contentTop,
            paneWidth = geometry.contentWidth,
            paneHeight = geometry.contentHeight,
            screenWidth = geometry.screenWidth,
            screenHeight = geometry.screenHeight,
        )
        SystemServerWorkspaceBridge.publishRotatedBackRegion(appContext, region)
    }

    private fun startOrientationObserver() {
        orientationListener?.disable()
        val listener = object : OrientationEventListener(appContext) {
            override fun onOrientationChanged(orientation: Int) {
                val now = SystemClock.uptimeMillis()
                if (now - lastOrientationUpdateMs < ORIENTATION_THROTTLE_MS) return
                val nextDegree = RotatedPaneTransformPolicy.resolveSensorDegree(orientation, currentDegree)
                if (nextDegree == currentDegree) return
                lastOrientationUpdateMs = now
                currentDegree = nextDegree
                mainHandler.post {
                    textureView?.animate()?.rotation(nextDegree.toFloat())?.setDuration(200L)?.start()
                }
            }
        }
        if (listener.canDetectOrientation()) {
            listener.enable()
            orientationListener = listener
        }
    }

    private fun handleHostTouch(view: View, event: MotionEvent): Boolean {
        val targetDisplayId = virtualDisplayId ?: return false
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return false

        val density = appContext.resources.displayMetrics.density
        val edgeSlopPx = maxOf(ViewConfiguration.get(appContext).scaledEdgeSlop.toFloat(), 28f * density)
        val triggerDistancePx = 56f * density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeDownX = event.x
                edgeDownY = event.y
                downStartedInEdge = RotatedPaneTransformPolicy.isEdgeDown(event.x, w, edgeSlopPx)
                edgeBackTriggered = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (downStartedInEdge && !edgeBackTriggered && event.pointerCount == 1) {
                    if (RotatedPaneTransformPolicy.shouldTriggerEdgeBack(
                            startX = edgeDownX,
                            startY = edgeDownY,
                            currentX = event.x,
                            currentY = event.y,
                            hostWidth = w,
                            edgeSlopPx = edgeSlopPx,
                            triggerDistancePx = triggerDistancePx,
                        )
                    ) {
                        edgeBackTriggered = true
                        forwardMappedMotionEvent(event, targetDisplayId, w, h, overrideAction = MotionEvent.ACTION_CANCEL)
                        injectBackKey(targetDisplayId)
                        return true
                    }
                }
                if (edgeBackTriggered) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (edgeBackTriggered) {
                    edgeBackTriggered = false
                    downStartedInEdge = false
                    return true
                }
                downStartedInEdge = false
            }
        }

        return forwardMappedMotionEvent(event, targetDisplayId, w, h)
    }

    private fun forwardMappedMotionEvent(
        source: MotionEvent,
        targetDisplayId: Int,
        viewWidth: Int,
        viewHeight: Int,
        overrideAction: Int? = null,
    ): Boolean {
        val count = source.pointerCount
        if (count <= 0) return false
        val properties = Array(count) { index ->
            MotionEvent.PointerProperties().also { source.getPointerProperties(index, it) }
        }
        val orientationOffsetRad = (currentDegree * PI / 180.0).toFloat()
        val coords = Array(count) { index ->
            MotionEvent.PointerCoords().also { c ->
                source.getPointerCoords(index, c)
                val (vx, vy) = RotatedPaneTransformPolicy.mapLocalTouchToVirtualDisplay(
                    localX = c.x,
                    localY = c.y,
                    hostWidth = viewWidth,
                    hostHeight = viewHeight,
                    displayWidth = displayWidth,
                    displayHeight = displayHeight,
                    degree = currentDegree,
                )
                c.x = vx
                c.y = vy
                c.orientation = c.orientation + orientationOffsetRad
            }
        }

        val mapped = MotionEvent.obtain(
            source.downTime,
            source.eventTime,
            overrideAction ?: source.action,
            count,
            properties,
            coords,
            source.metaState,
            source.buttonState,
            source.xPrecision,
            source.yPrecision,
            source.deviceId,
            source.edgeFlags,
            InputDevice.SOURCE_TOUCHSCREEN,
            source.flags,
        )
        return try {
            injectForDisplay(mapped, targetDisplayId)
        } finally {
            mapped.recycle()
        }
    }

    private fun injectBackKey(targetDisplayId: Int) {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(
            now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD,
        )
        val up = KeyEvent(
            now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD,
        )
        injectForDisplay(down, targetDisplayId)
        injectForDisplay(up, targetDisplayId)
    }

    private fun injectForDisplay(event: InputEvent, displayId: Int): Boolean {
        val manager = inputManager ?: return false
        val setDisplay = setDisplayIdMethod ?: return false
        val inject = injectInputEventMethod ?: return false
        return runCatching {
            setDisplay.invoke(event, displayId)
            val injected = inject.invoke(manager, event, INJECT_INPUT_EVENT_MODE_ASYNC) as? Boolean ?: false
            if (!injected && event is MotionEvent && event.actionMasked == MotionEvent.ACTION_DOWN) {
                NeXtepLog.warn("rotated_main", "Touch injection returned false for displayId=$displayId")
            }
            injected
        }.onFailure { error ->
            NeXtepLog.warn("rotated_main", "Input injection failed displayId=$displayId", error)
        }.getOrDefault(false)
    }

    private fun suppressSystemDecors(displayId: Int) {
        runCatching {
            val wmGlobal = Class.forName("android.view.WindowManagerGlobal")
            val wms = wmGlobal.getMethod("getWindowManagerService").invoke(null) ?: return@runCatching
            wms.javaClass.getMethod("setShouldShowSystemDecors", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .invoke(wms, displayId, false)
        }.onFailure { error ->
            NeXtepLog.debug("rotated_main", "Could not disable system decors for displayId=$displayId: ${error.javaClass.simpleName}")
        }
    }

    private fun moveTaskToDisplayAndWait(taskId: Int, displayId: Int) {
        TaskSurfaceCompat.markDisplayExchange(taskId, displayId)
        if (displayId == Display.DEFAULT_DISPLAY) {
            TaskSurfaceCompat.preparePromotion(taskId, geometryProvider()).onFailure {
                NeXtepLog.warn("rotated_main", "Early promotion prep unavailable taskId=$taskId", it)
            }
        } else {
            TaskSurfaceCompat.cancelPreparedPromotion(taskId)
        }
        try {
            ActivityTaskManagerCompat.moveTaskToDisplay(taskId, displayId).getOrThrow()
        } catch (error: Throwable) {
            TaskSurfaceCompat.cancelDisplayExchange(taskId)
            throw error
        }
        val deadline = SystemClock.uptimeMillis() + DISPLAY_MOVE_TIMEOUT_MS
        var observed = taskRepository.findTask(taskId)?.displayId
        while (observed != displayId && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(DISPLAY_MOVE_POLL_MS)
            observed = taskRepository.findTask(taskId)?.displayId
        }
    }

    private fun releaseResources() {
        orientationListener?.disable()
        orientationListener = null

        overlayContainer?.let { container ->
            runCatching { windowManager.removeViewImmediate(container) }
        }
        overlayContainer = null
        textureView = null
        virtualDisplay?.release()
        virtualDisplay = null
        virtualDisplayId = null
        surface?.release()
        surface = null
    }

    private class RotatedPaneContainer(context: Context) : FrameLayout(context) {
        private var childW: Int = 0
        private var childH: Int = 0

        fun setTextureSize(width: Int, height: Int) {
            childW = width
            childH = height
            requestLayout()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(w, h)
            val cw = if (childW > 0) childW else h
            val ch = if (childH > 0) childH else w
            val cwSpec = MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY)
            val chSpec = MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY)
            for (i in 0 until childCount) {
                getChildAt(i).measure(cwSpec, chSpec)
            }
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val w = right - left
            val h = bottom - top
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                val cw = child.measuredWidth
                val ch = child.measuredHeight
                val cl = (w - cw) / 2
                val ct = (h - ch) / 2
                child.layout(cl, ct, cl + cw, ct + ch)
            }
        }
    }

    private companion object {
        const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
        const val PRIVATE_FLAG_NO_MOVE_ANIMATION = 0x40
        const val TASK_RESIZE_MODE_RESIZEABLE = 2
        const val ORIENTATION_THROTTLE_MS = 150L
        const val DISPLAY_MOVE_TIMEOUT_MS = 500L
        const val DISPLAY_MOVE_POLL_MS = 10L
    }
}
