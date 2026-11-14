package com.elbert.nextep.systemserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.InputEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.elbert.nextep.logging.NeXtepLog
import com.elbert.nextep.systemui.BottomCornerGestureDetector
import com.elbert.nextep.systemui.FourFingerGestureDetector
import com.elbert.nextep.systemui.TwoFingerSwipeGestureDetector
import com.elbert.nextep.trigger.TriggerBroadcastContract
import com.elbert.nextep.trigger.TriggerGesture
import com.elbert.nextep.workspace.SystemServerWorkspaceBridge
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean

/** Observe WM's non-exclusive stream, including when an OEM screenshot monitor pilfers. */
internal object FourFingerGestureHook {
    private val initialized = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var context: Context? = null
    @Volatile private var detector: FourFingerGestureDetector? = null
    @Volatile private var bottomCornerDetector: BottomCornerGestureDetector? = null
    @Volatile private var twoFingerDetector: TwoFingerSwipeGestureDetector? = null
    @Volatile private var displayWidth: Int = 0
    @Volatile private var displayHeight: Int = 0
    @Volatile private var displayRotation: Int = 0
    // Accessed only on PointerEventDispatcher's input looper.
    private var contactDownTime = -1L
    private var contactActive = false

    @Suppress("DEPRECATION")
    private fun refreshDisplayMetrics(systemContext: Context, displayManager: DisplayManager?) {
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        runCatching { display?.getRealSize(size) }
        val metrics = systemContext.resources.displayMetrics
        val width = if (size.x > 0) size.x else metrics.widthPixels
        val height = if (size.y > 0) size.y else metrics.heightPixels
        val rotation = display?.rotation ?: 0
        val density = metrics.density
        val changed = width != displayWidth || height != displayHeight || rotation != displayRotation
        displayWidth = width
        displayHeight = height
        displayRotation = rotation
        if (changed || bottomCornerDetector == null || twoFingerDetector == null) {
            bottomCornerDetector = BottomCornerGestureDetector(density = density)
            val statusId = systemContext.resources.getIdentifier("status_bar_height", "dimen", "android")
            val statusHeight = if (statusId != 0) systemContext.resources.getDimension(statusId) else 0f
            twoFingerDetector = TwoFingerSwipeGestureDetector(density = density, statusBarHeightPx = statusHeight)
        }
    }

    fun install(module: XposedModule, loader: ClassLoader) {
        val wmClass = loader.loadClass("com.android.server.wm.WindowManagerService")
        val contextField = wmClass.getDeclaredField("mContext").apply { isAccessible = true }
        val systemReady = wmClass.getDeclaredMethod("systemReady").apply { isAccessible = true }
        val dispatch = loader.loadClass("com.android.server.wm.PointerEventDispatcher")
            .getDeclaredMethod("onInputEvent", InputEvent::class.java).apply { isAccessible = true }
        val displayId = InputEvent::class.java.getMethod("getDisplayId")
        val managerClass = loader.loadClass("android.hardware.input.InputManagerGlobal")
        // Hooks are installed before InputManagerService exists during system boot.
        val manager by lazy { checkNotNull(managerClass.getMethod("getInstance").invoke(null)) }
        val cancelTouch = managerClass.getMethod("cancelCurrentTouch")

        module.hook(systemReady).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                if (initialized.compareAndSet(false, true)) {
                    val systemContext = runCatching { contextField.get(chain.thisObject) as Context }
                        .getOrElse { reportFailure(it); return result }
                    mainHandler.post {
                        runCatching {
                            SystemServerWorkspaceBridge.startGeometryObservation(systemContext)
                            val minimumSpan = maxOf(24f * systemContext.resources.displayMetrics.density,
                                ViewConfiguration.get(systemContext).scaledTouchSlop * 2f)
                            detector = FourFingerGestureDetector(minimumSpan)
                            val dm = systemContext.getSystemService(DisplayManager::class.java)
                            refreshDisplayMetrics(systemContext, dm)
                            dm?.registerDisplayListener(object : DisplayManager.DisplayListener {
                                override fun onDisplayAdded(displayId: Int) = Unit
                                override fun onDisplayRemoved(displayId: Int) = Unit
                                override fun onDisplayChanged(displayId: Int) {
                                    if (displayId == Display.DEFAULT_DISPLAY) {
                                        refreshDisplayMetrics(systemContext, dm)
                                    }
                                }
                            }, mainHandler)
                            context = systemContext
                            NeXtepLog.info("four_finger_gesture", "Default-display WM touch observation ready")
                        }.onFailure {
                            NeXtepLog.warn("four_finger_gesture", "WM touch observation unavailable", it)
                        }
                    }
                }
                return result
            }
        })
        module.hook(dispatch).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                // The original receiver must always run and finish its native event.
                runCatching {
                    val current = context ?: return@runCatching
                    val recognizer = detector ?: return@runCatching
                    val event = chain.getArg(0) as? MotionEvent ?: return@runCatching
                    if (displayId.invoke(event) != Display.DEFAULT_DISPLAY) return@runCatching
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            contactDownTime = event.downTime
                            contactActive = true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> contactActive = false
                    }
                    val enabledGestures = SystemServerWorkspaceBridge.observedTriggerGestures()
                    val cornerMatch = bottomCornerDetector?.observe(
                        event = event,
                        screenWidth = displayWidth,
                        screenHeight = displayHeight,
                        displayRotation = displayRotation,
                        leftEnabled = TriggerGesture.BOTTOM_LEFT in enabledGestures,
                        rightEnabled = TriggerGesture.BOTTOM_RIGHT in enabledGestures,
                    )
                    val twoFingerMatch = twoFingerDetector?.observe(
                        event = event,
                        screenWidth = displayWidth,
                        screenHeight = displayHeight,
                        allowed = TriggerGesture.TWO_FINGER in enabledGestures,
                    )
                    val matchedTrigger = cornerMatch ?: if (twoFingerMatch != null) TriggerGesture.TWO_FINGER else null
                    if (matchedTrigger != null) {
                        NeXtepLog.debug("trigger_gesture", "Recognized ${matchedTrigger.key}")
                        val downTime = event.downTime
                        val eventTime = event.eventTime
                        val inputHandler = Handler(checkNotNull(Looper.myLooper()))
                        mainHandler.post {
                            runCatching {
                                val request = Intent(TriggerBroadcastContract.ACTION_TRIGGER_GESTURE)
                                    .setPackage(TriggerBroadcastContract.SYSTEM_UI_PACKAGE)
                                    .putExtra(TriggerBroadcastContract.EXTRA_TRIGGER_GESTURE, matchedTrigger.key)
                                    .putExtra(TriggerBroadcastContract.EXTRA_GESTURE_EVENT_TIME, eventTime)
                                current.sendOrderedBroadcastAsUser(request, android.os.Process.myUserHandle(),
                                    null, object : BroadcastReceiver() {
                                        override fun onReceive(context: Context, intent: Intent) {
                                            if (resultCode != TriggerBroadcastContract.RESULT_HANDLED) return
                                            inputHandler.post {
                                                if (contactActive && contactDownTime == downTime &&
                                                    SystemClock.uptimeMillis() - eventTime in 0L..400L
                                                ) runCatching { cancelTouch.invoke(manager) }.onFailure(::reportFailure)
                                            }
                                        }
                                    }, mainHandler, TriggerBroadcastContract.RESULT_NOT_READY, null, null)
                            }.onFailure(::reportFailure)
                        }
                    }
                    val action = recognizer.observe(event, allowed = true)
                        ?: return@runCatching
                    NeXtepLog.debug("four_finger_gesture", "Recognized targetActive=${action.active}")
                    val downTime = event.downTime
                    val eventTime = event.eventTime
                    val inputHandler = Handler(checkNotNull(Looper.myLooper()))
                    mainHandler.post {
                        runCatching {
                            val request = Intent(TriggerBroadcastContract.ACTION_FOUR_FINGER_WORKSPACE)
                                .setPackage(TriggerBroadcastContract.SYSTEM_UI_PACKAGE)
                                .putExtra(TriggerBroadcastContract.EXTRA_REQUESTED_ACTIVE, action.active)
                                .putExtra(TriggerBroadcastContract.EXTRA_GESTURE_EVENT_TIME, eventTime)
                            // A disabled, locked, blacklisted, or game-blocked request is
                            // rejected without changing the application's touch stream.
                            current.sendOrderedBroadcastAsUser(request, android.os.Process.myUserHandle(),
                                null, object : BroadcastReceiver() {
                                    override fun onReceive(context: Context, intent: Intent) {
                                        if (resultCode != TriggerBroadcastContract.RESULT_HANDLED) return
                                        inputHandler.post {
                                            if (contactActive && contactDownTime == downTime &&
                                                SystemClock.uptimeMillis() - eventTime in 0L..400L
                                            ) runCatching { cancelTouch.invoke(manager) }.onFailure(::reportFailure)
                                        }
                                    }
                                }, mainHandler, TriggerBroadcastContract.RESULT_NOT_READY, null, null)
                        }.onFailure(::reportFailure)
                    }
                }.onFailure {
                    detector?.abandon()
                    bottomCornerDetector?.reset()
                    twoFingerDetector?.reset()
                    reportFailure(it)
                }
                return chain.proceed()
            }
        })
    }

    private fun reportFailure(error: Throwable) {
        if (failed.compareAndSet(false, true)) {
            NeXtepLog.warn("four_finger_gesture", "WM gesture observation failed open", error)
        }
    }
}
