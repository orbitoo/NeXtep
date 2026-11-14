package com.elbert.nextep.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.elbert.nextep.framework.ActivityTaskManagerCompat
import com.elbert.nextep.framework.TaskSurfaceCompat
import com.elbert.nextep.framework.VirtualDisplayFlagsCompat
import com.elbert.nextep.logging.NeXtepLog
import com.elbert.nextep.task.TaskRepository
import com.elbert.nextep.workspace.WorkspaceGeometry
import java.lang.reflect.Method

internal object VirtualDisplayPaneSupport {
    private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
    private const val DISPLAY_MOVE_TIMEOUT_MS = 500L
    private const val DISPLAY_MOVE_POLL_MS = 10L
    const val TASK_RESIZE_MODE_RESIZEABLE = 2

    fun createVirtualDisplay(
        displayManager: DisplayManager?,
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
    ): VirtualDisplay? {
        val dm = displayManager ?: return null
        for (flags in VirtualDisplayFlagsCompat.resolveCandidates()) {
            val config = VirtualDisplayConfig.Builder(name, width, height, densityDpi)
                .setSurface(surface)
                .setFlags(flags.value)
                .build()
            val vd = runCatching { dm.createVirtualDisplay(config) }.getOrNull()
            if (vd != null) return vd
        }
        return null
    }

    fun suppressSystemDecors(displayId: Int, logTag: String) {
        runCatching {
            val wmGlobal = Class.forName("android.view.WindowManagerGlobal")
            val wms = wmGlobal.getMethod("getWindowManagerService").invoke(null) ?: return@runCatching
            wms.javaClass.getMethod(
                "setShouldShowSystemDecors",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ).invoke(wms, displayId, false)
        }.onFailure { error ->
            NeXtepLog.debug(logTag, "Could not disable system decors for displayId=$displayId: ${error.javaClass.simpleName}")
        }
    }

    fun moveTaskToDisplayAndWait(
        taskRepository: TaskRepository,
        geometry: WorkspaceGeometry,
        taskId: Int,
        displayId: Int,
    ) {
        TaskSurfaceCompat.markDisplayExchange(taskId, displayId)
        if (displayId == Display.DEFAULT_DISPLAY) {
            TaskSurfaceCompat.preparePromotion(taskId, geometry).onFailure {
                NeXtepLog.warn("virtual_pane", "Early promotion prep unavailable taskId=$taskId", it)
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

    class InputBridge(appContext: Context, private val logTag: String) {
        private val inputManager: InputManager? = appContext.getSystemService(InputManager::class.java)
        private val setDisplayIdMethod: Method? = runCatching {
            InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).apply { isAccessible = true }
        }.getOrNull()
        private val injectInputEventMethod: Method? = runCatching {
            InputManager::class.java.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
        }.getOrNull()

        fun injectBackKey(displayId: Int) {
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(
                now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD,
            )
            val up = KeyEvent(
                now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD,
            )
            injectEvent(down, displayId)
            injectEvent(up, displayId)
        }

        fun forwardMappedMotionEvent(
            source: MotionEvent,
            targetDisplayId: Int,
            orientationOffsetRad: Float = 0f,
            overrideAction: Int? = null,
            mapPoint: (localX: Float, localY: Float) -> Pair<Float, Float>,
        ): Boolean {
            val count = source.pointerCount
            if (count <= 0) return false
            val properties = Array(count) { index ->
                MotionEvent.PointerProperties().also { source.getPointerProperties(index, it) }
            }
            val coords = Array(count) { index ->
                MotionEvent.PointerCoords().also { c ->
                    source.getPointerCoords(index, c)
                    val (vx, vy) = mapPoint(c.x, c.y)
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
                injectEvent(mapped, targetDisplayId)
            } finally {
                mapped.recycle()
            }
        }

        private fun injectEvent(event: InputEvent, displayId: Int): Boolean {
            val manager = inputManager ?: return false
            val setDisplay = setDisplayIdMethod ?: return false
            val inject = injectInputEventMethod ?: return false
            return runCatching {
                setDisplay.invoke(event, displayId)
                val injected = inject.invoke(manager, event, INJECT_INPUT_EVENT_MODE_ASYNC) as? Boolean ?: false
                if (!injected && event is MotionEvent && event.actionMasked == MotionEvent.ACTION_DOWN) {
                    NeXtepLog.warn(logTag, "Touch injection returned false for displayId=$displayId")
                }
                injected
            }.onFailure { error ->
                NeXtepLog.warn(logTag, "Input injection failed displayId=$displayId", error)
            }.getOrDefault(false)
        }
    }
}
