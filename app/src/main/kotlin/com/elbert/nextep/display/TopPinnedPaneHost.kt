package com.elbert.nextep.display

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.elbert.nextep.framework.ActivityTaskManagerCompat
import com.elbert.nextep.logging.NeXtepLog
import com.elbert.nextep.systemui.ControlAreaPager
import com.elbert.nextep.task.MainTaskPresentationCoordinator
import com.elbert.nextep.task.TaskRepository
import com.elbert.nextep.workspace.TopPinnedTransformPolicy
import com.elbert.nextep.workspace.WorkspaceGeometry

/**
 * Hosts a landscape-requesting task inside a dedicated virtual display pinned in [ControlAreaPager]
 * below the status-bar pass-through strip for [com.elbert.nextep.workspace.LandscapeVideoMode.TOP].
 */
internal class TopPinnedPaneHost(
    context: Context,
    private val mainTaskPresenter: MainTaskPresentationCoordinator,
    private val geometryProvider: () -> WorkspaceGeometry,
    private val pagerProvider: () -> ControlAreaPager?,
) {
    private val appContext = context.applicationContext ?: context
    private val handler = Handler(Looper.getMainLooper())
    private val taskRepository = TaskRepository(appContext)
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)
    private val inputBridge = VirtualDisplayPaneSupport.InputBridge(appContext, LOG_TAG)

    private var rootContainer: FrameLayout? = null
    private var textureView: TextureView? = null
    private var surface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var virtualDisplayId: Int? = null

    private var activeTaskId: Int? = null
    private var pendingTaskId: Int? = null
    private var pendingMoveGeneration: Int = 0
    private var displayWidth: Int = 0
    private var displayHeight: Int = 0

    private var downStartedInEdge = false
    private var edgeBackTriggered = false
    private var edgeDownX = 0f
    private var edgeDownY = 0f

    fun isActive(): Boolean = activeTaskId != null || pendingTaskId != null

    fun activeTaskId(): Int? = activeTaskId ?: pendingTaskId

    fun matchesPackage(packageName: String?, userId: Int? = null): Boolean {
        val tid = activeTaskId() ?: return false
        val pkg = packageName ?: return false
        val snapshot = taskRepository.findTask(tid) ?: return false
        if (snapshot.component.packageName != pkg) return false
        return userId == null || snapshot.userId == userId
    }

    fun enter(taskId: Int, reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { enter(taskId, reason) }
            return
        }
        val pager = pagerProvider()
        if (pager == null) {
            NeXtepLog.warn(LOG_TAG, "Cannot enter top-pinned mode without ControlAreaPager taskId=$taskId")
            return
        }
        if (activeTaskId == taskId || pendingTaskId == taskId) {
            NeXtepLog.debug(LOG_TAG, "TopPinnedPaneHost already hosts taskId=$taskId ($reason)")
            return
        }
        if (isActive()) {
            exitToPrimaryDisplay(presentInMain = false)
        }

        val geometry = geometryProvider()
        val (zoneW, zoneH) = pager.pinnedZoneSize(geometry)
        val size = computeVirtualSize(geometry, zoneW, zoneH)
        displayWidth = size.width
        displayHeight = size.height

        val generation = ++pendingMoveGeneration
        pendingTaskId = taskId

        val container = FrameLayout(appContext).apply {
            setBackgroundColor(Color.BLACK)
        }
        val tex = TextureView(appContext).apply {
            isOpaque = true
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
            setOnTouchListener { view, event -> handleTouch(view, event) }
        }
        container.addView(tex, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        rootContainer = container
        textureView = tex
        pager.setPinnedTopView(container) { newZoneW, newZoneH ->
            onZoneResized(newZoneW, newZoneH)
        }
        NeXtepLog.info(
            LOG_TAG,
            "Entering top-pinned mode taskId=$taskId zone=${zoneW}x$zoneH vd=${displayWidth}x$displayHeight reason=$reason",
        )
    }

    fun exitToPrimaryDisplay(presentInMain: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { exitToPrimaryDisplay(presentInMain) }
            return
        }
        val taskToRestore = activeTaskId ?: pendingTaskId
        pendingMoveGeneration++
        pendingTaskId = null
        activeTaskId = null

        pagerProvider()?.setPinnedTopView(null)

        if (taskToRestore != null) {
            val snapshot = taskRepository.findTask(taskToRestore)
            if (snapshot != null) {
                runCatching {
                    if (snapshot.displayId != Display.DEFAULT_DISPLAY) {
                        VirtualDisplayPaneSupport.moveTaskToDisplayAndWait(
                            taskRepository,
                            geometryProvider(),
                            taskToRestore,
                            Display.DEFAULT_DISPLAY,
                        )
                    }
                    ActivityTaskManagerCompat.resizeTask(taskToRestore, Rect())
                    if (presentInMain) {
                        taskRepository.bringTaskToFront(taskToRestore)
                        mainTaskPresenter.presentTask(taskToRestore, geometryProvider())
                    }
                }.onFailure { error ->
                    NeXtepLog.warn(LOG_TAG, "Failed restoring taskId=$taskToRestore to primary", error)
                }
            }
        }

        releaseResources()
        NeXtepLog.info(LOG_TAG, "Exited top-pinned mode restoredTask=$taskToRestore presentInMain=$presentInMain")
    }

    fun verifyTaskAlive() {
        val tid = activeTaskId ?: return
        val vdId = virtualDisplayId ?: return
        val snapshot = taskRepository.findTask(tid)
        if (snapshot == null || snapshot.displayId != vdId) {
            NeXtepLog.info(LOG_TAG, "Top-pinned task taskId=$tid left vd=$vdId (now=${snapshot?.displayId}); cleaning up")
            exitToPrimaryDisplay(presentInMain = snapshot != null && snapshot.displayId == Display.DEFAULT_DISPLAY)
        }
    }

    private fun onZoneResized(zoneW: Int, zoneH: Int) {
        val tid = activeTaskId ?: return
        val vd = virtualDisplay ?: return
        val geometry = geometryProvider()
        val next = computeVirtualSize(geometry, zoneW, zoneH)
        if (next.width == displayWidth && next.height == displayHeight) return

        displayWidth = next.width
        displayHeight = next.height
        textureView?.surfaceTexture?.setDefaultBufferSize(displayWidth, displayHeight)
        vd.resize(displayWidth, displayHeight, appContext.resources.displayMetrics.densityDpi)
        ActivityTaskManagerCompat.resizeTask(tid, Rect(0, 0, displayWidth, displayHeight))
        NeXtepLog.debug(LOG_TAG, "Resized top-pinned virtual display vd=${displayWidth}x$displayHeight")
    }

    private fun attachSurfaceAndMigrateTask(taskId: Int, generation: Int, targetSurface: Surface) {
        if (generation != pendingMoveGeneration) {
            targetSurface.release()
            return
        }
        surface = targetSurface
        val vd = VirtualDisplayPaneSupport.createVirtualDisplay(
            displayManager = displayManager,
            name = TopPinnedTransformPolicy.TOP_PINNED_DISPLAY_NAME,
            width = displayWidth,
            height = displayHeight,
            densityDpi = appContext.resources.displayMetrics.densityDpi,
            surface = targetSurface,
        )
        if (vd == null) {
            NeXtepLog.error(LOG_TAG, "Failed creating VirtualDisplay for taskId=$taskId")
            exitToPrimaryDisplay(presentInMain = true)
            return
        }
        virtualDisplay = vd
        val vdId = vd.display.displayId
        virtualDisplayId = vdId
        VirtualDisplayPaneSupport.suppressSystemDecors(vdId, LOG_TAG)

        val migrated = runCatching {
            mainTaskPresenter.restoreForMove(taskId)
            val snapshot = taskRepository.findTask(taskId)
                ?: error("Task $taskId vanished before migration")
            if (snapshot.resizeMode != VirtualDisplayPaneSupport.TASK_RESIZE_MODE_RESIZEABLE) {
                ActivityTaskManagerCompat.setTaskResizeable(
                    taskId,
                    VirtualDisplayPaneSupport.TASK_RESIZE_MODE_RESIZEABLE,
                )
            }
            VirtualDisplayPaneSupport.moveTaskToDisplayAndWait(taskRepository, geometryProvider(), taskId, vdId)
            ActivityTaskManagerCompat.resizeTask(taskId, Rect(0, 0, displayWidth, displayHeight))
            taskRepository.bringTaskToFront(taskId).getOrThrow()
            true
        }.onFailure { error ->
            NeXtepLog.warn(LOG_TAG, "Task migration to top-pinned display failed taskId=$taskId", error)
        }.getOrDefault(false)

        if (!migrated || generation != pendingMoveGeneration) {
            exitToPrimaryDisplay(presentInMain = true)
            return
        }

        pendingTaskId = null
        activeTaskId = taskId
        // Fit whatever task or HOME revealed underneath on Display 0 cleanly into the main pane.
        handler.post {
            if (activeTaskId == taskId) {
                mainTaskPresenter.reconcileForeground(geometryProvider()).onFailure { error ->
                    NeXtepLog.debug(LOG_TAG, "Main pane reconcile after top pin deferred: ${error.message}")
                }
            }
        }
        NeXtepLog.info(LOG_TAG, "Pinned taskId=$taskId into top vd=$vdId (${displayWidth}x$displayHeight)")
    }

    private fun handleTouch(view: android.view.View, event: MotionEvent): Boolean {
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
                downStartedInEdge = TopPinnedTransformPolicy.isEdgeDown(event.x, w, edgeSlopPx)
                edgeBackTriggered = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (downStartedInEdge && !edgeBackTriggered && event.pointerCount == 1) {
                    if (TopPinnedTransformPolicy.shouldTriggerEdgeBack(
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
                        forwardTouch(event, targetDisplayId, w, h, overrideAction = MotionEvent.ACTION_CANCEL)
                        inputBridge.injectBackKey(targetDisplayId)
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
        return forwardTouch(event, targetDisplayId, w, h)
    }

    private fun forwardTouch(
        event: MotionEvent,
        targetDisplayId: Int,
        viewWidth: Int,
        viewHeight: Int,
        overrideAction: Int? = null,
    ): Boolean = inputBridge.forwardMappedMotionEvent(
        source = event,
        targetDisplayId = targetDisplayId,
        overrideAction = overrideAction,
    ) { lx, ly ->
        TopPinnedTransformPolicy.mapLocalTouchToVirtualDisplay(
            localX = lx,
            localY = ly,
            hostWidth = viewWidth,
            hostHeight = viewHeight,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
        )
    }

    private fun computeVirtualSize(
        geometry: WorkspaceGeometry,
        zoneW: Int,
        zoneH: Int,
    ): TopPinnedTransformPolicy.VirtualDisplaySize {
        val targetShortSide = minOf(geometry.contentWidth, geometry.contentHeight).coerceAtLeast(720)
        return TopPinnedTransformPolicy.computeVirtualDisplaySize(zoneW, zoneH, targetShortSide)
    }

    private fun releaseResources() {
        rootContainer = null
        textureView = null
        virtualDisplay?.release()
        virtualDisplay = null
        virtualDisplayId = null
        surface?.release()
        surface = null
    }

    private companion object {
        const val LOG_TAG = "top_pinned"
    }
}
