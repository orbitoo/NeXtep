package com.elbert.nextep.systemserver

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.MotionEvent
import com.elbert.nextep.framework.HyperOsEnvironment
import com.elbert.nextep.logging.NeXtepLog
import com.elbert.nextep.trigger.TriggerGesture
import com.elbert.nextep.workspace.SystemServerWorkspaceBridge
import com.elbert.nextep.workspace.WorkspaceGeometry
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Proxy
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HyperOS 3 edge-gesture & launch pre-animation guard in `system_server`:
 * 1. Suppresses `WindowManagerService#startPreStartupAnimation` while workspace is active so home
 *    app launches route cleanly into NeXtep without MIUI pre-animation artifacts.
 * 2. Registers a display-0 pointer listener via `WindowManagerService#systemReady` to track
 *    down timestamp/coordinates and bottom-corner swipe intent.
 * 3. Blocks `MiuiInputManagerService#requestRedirect` only for recent touches (`0..300ms`) whose
 *    origin falls inside NeXtep's claimed sidebar/control edge chrome.
 * 4. Holds and replays or discards `MiuiHome` corner `pilferPointers` calls when a bottom-corner
 *    swipe is still ambiguous or owned by NeXtep.
 */
object HyperOsInputEdgeGuardHook {
    private enum class CornerState {
        IGNORED,
        OBSERVING,
        ABANDONED,
        CONFIRMED,
    }

    private const val REDIRECT_BLOCK_WINDOW_MS = 300L
    private const val CORNER_CONFIRMED_GRACE_MS = 120L
    private const val PILFER_HOLD_TIMEOUT_MS = 3000L

    private val installed = AtomicBoolean(false)
    private val pointerListenerRegistered = AtomicBoolean(false)
    private val fieldCache = ConcurrentHashMap<String, Optional<Field>>()

    @Volatile
    private var wmContext: Context? = null

    @Volatile
    private var miuiHomeUid: Int = -1

    @Volatile
    private var lastDownUptimeMs: Long = Long.MIN_VALUE

    @Volatile
    private var lastDownRawX: Float = 0f

    @Volatile
    private var lastDownRawY: Float = 0f

    @Volatile
    private var cornerState: CornerState = CornerState.IGNORED

    @Volatile
    private var cornerConfirmedUntilMs: Long = Long.MIN_VALUE

    @Volatile
    private var pendingPilferReplay: (() -> Unit)? = null

    @Volatile
    private var pendingPilferTimeout: Runnable? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val pilferLock = Any()

    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (!HyperOsEnvironment.isHyperOs()) return
        if (!installed.compareAndSet(false, true)) return

        runCatching {
            val wmsClass = classLoader.loadClass("com.android.server.wm.WindowManagerService")
            val contextField = findField(wmsClass, "mContext")
            installSystemReadyObserver(module, wmsClass, contextField)
            installPreStartupHook(module, wmsClass)
            installMiuiRequestRedirectHook(module, classLoader, contextField)
            installPilferPointersHooks(module, classLoader)
        }.onFailure { error ->
            NeXtepLog.warn("hyperos_input_guard", "HyperOS input guard skipped", error)
        }
    }

    private fun installSystemReadyObserver(
        module: XposedModule,
        wmsClass: Class<*>,
        contextField: Field?,
    ) {
        val systemReady = runCatching {
            wmsClass.getDeclaredMethod("systemReady").apply { isAccessible = true }
        }.getOrNull() ?: return

        module.hook(systemReady).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                val wms = chain.thisObject ?: return result
                val context = contextField?.let { runCatching { it.get(wms) as? Context }.getOrNull() }
                    ?: return result
                onSystemReady(context, wms)
                return result
            }
        })
    }

    private fun onSystemReady(context: Context, windowManagerService: Any) {
        wmContext = context
        SystemServerWorkspaceBridge.startGeometryObservation(context)
        SystemServerWorkspaceBridge.startSceneObservation(context)
        resolveMiuiHomeUid(context)

        mainHandler.post {
            runCatching {
                val observer = object : ContentObserver(mainHandler) {
                    override fun onChange(selfChange: Boolean) {
                        if (!SystemServerWorkspaceBridge.isWorkspaceActive(context)) {
                            flushHeldPilferOnWorkspaceExit()
                        }
                    }
                }
                context.contentResolver.registerContentObserver(
                    Settings.Global.getUriFor("nextep_workspace_active_v1"),
                    false,
                    observer,
                )
            }
        }

        if (!pointerListenerRegistered.compareAndSet(false, true)) return
        runCatching {
            val listenerInterface = windowManagerService.javaClass.classLoader
                ?.loadClass("android.view.WindowManagerPolicyConstants\$PointerEventListener")
                ?: return@runCatching
            val registerMethod = windowManagerService.javaClass.methods.firstOrNull {
                it.name == "registerPointerEventListener" &&
                    it.parameterCount == 2 &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType
            } ?: return@runCatching
            val proxy = Proxy.newProxyInstance(
                listenerInterface.classLoader,
                arrayOf(listenerInterface),
            ) { _, method, args ->
                if (method.name == "onPointerEvent") {
                    (args?.firstOrNull() as? MotionEvent)?.let(::onPointerEvent)
                }
                null
            }
            registerMethod.invoke(windowManagerService, proxy, Display.DEFAULT_DISPLAY)
            NeXtepLog.info("hyperos_input_guard", "Registered display-0 pointer listener")
        }.onFailure { error ->
            pointerListenerRegistered.set(false)
            NeXtepLog.warn("hyperos_input_guard", "PointerEventListener registration failed", error)
        }
    }

    private fun resolveMiuiHomeUid(context: Context) {
        if (miuiHomeUid >= 0) return
        runCatching {
            val uid = context.packageManager.getPackageUid(
                "com.miui.home",
                PackageManager.PackageInfoFlags.of(0L),
            )
            miuiHomeUid = uid
        }
    }

    private fun onPointerEvent(event: MotionEvent) {
        if (HyperOsEnvironment.isMagnificationFallbackActive()) {
            cornerState = CornerState.IGNORED
            return
        }
        val geometry = SystemServerWorkspaceBridge.observedActiveGeometry()
        val triggerGestures = SystemServerWorkspaceBridge.observedTriggerGestures()
        val hasCornerTrigger = TriggerGesture.BOTTOM_LEFT in triggerGestures ||
            TriggerGesture.BOTTOM_RIGHT in triggerGestures
        if (geometry == null && !hasCornerTrigger) {
            cornerState = CornerState.IGNORED
            return
        }
        val metrics = wmContext?.resources?.displayMetrics
        val screenW = geometry?.screenWidth ?: metrics?.widthPixels ?: 0
        val screenH = geometry?.screenHeight ?: metrics?.heightPixels ?: 0
        if (screenW <= 0 || screenH <= 0) {
            cornerState = CornerState.IGNORED
            return
        }
        val rawX = event.rawX
        val rawY = event.rawY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastDownUptimeMs = SystemClock.uptimeMillis()
                lastDownRawX = rawX
                lastDownRawY = rawY
                val hitCorner = if (geometry != null) {
                    isInBottomCornerZone(rawX, rawY, screenW, screenH, leftEnabled = true, rightEnabled = true)
                } else {
                    isInBottomCornerZone(
                        rawX, rawY, screenW, screenH,
                        leftEnabled = TriggerGesture.BOTTOM_LEFT in triggerGestures,
                        rightEnabled = TriggerGesture.BOTTOM_RIGHT in triggerGestures,
                    )
                }
                cornerState = if (hitCorner) CornerState.OBSERVING else CornerState.IGNORED
            }
            MotionEvent.ACTION_MOVE -> {
                if (cornerState == CornerState.OBSERVING) {
                    val density = metrics?.density ?: 3f
                    val dx = kotlin.math.abs(rawX - lastDownRawX)
                    val dy = lastDownRawY - rawY
                    val threshold = 24f * density
                    if (dy >= threshold && dx >= threshold * 0.4f) {
                        cornerState = CornerState.CONFIRMED
                        cornerConfirmedUntilMs = SystemClock.uptimeMillis() + CORNER_CONFIRMED_GRACE_MS
                        discardHeldPilfer()
                    } else if ((geometry == null && dy >= threshold && dx < threshold * 0.35f) ||
                        (kotlin.math.abs(dy) > threshold * 2f && dy < 0f)
                    ) {
                        cornerState = CornerState.ABANDONED
                        replayHeldPilfer()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (cornerState == CornerState.OBSERVING) {
                    cornerState = CornerState.ABANDONED
                    replayHeldPilfer()
                } else if (cornerState != CornerState.CONFIRMED) {
                    cornerState = CornerState.IGNORED
                }
            }
        }
    }

    private fun isInBottomCornerZone(
        x: Float,
        y: Float,
        screenWidth: Int,
        screenHeight: Int,
        leftEnabled: Boolean,
        rightEnabled: Boolean,
    ): Boolean {
        val density = wmContext?.resources?.displayMetrics?.density ?: 3f
        val cornerBand = 48f * density
        if (y < screenHeight - cornerBand) return false
        return (leftEnabled && x <= cornerBand) || (rightEnabled && x >= screenWidth - cornerBand)
    }

    private fun installPreStartupHook(module: XposedModule, wmsClass: Class<*>) {
        wmsClass.declaredMethods
            .filter { it.name == "startPreStartupAnimation" }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        if (SystemServerWorkspaceBridge.isObservedWorkspaceActive() &&
                            !HyperOsEnvironment.isMagnificationFallbackActive()
                        ) {
                            return 1
                        }
                        return chain.proceed()
                    }
                })
            }
    }

    private fun installMiuiRequestRedirectHook(
        module: XposedModule,
        classLoader: ClassLoader,
        wmsContextField: Field?,
    ) {
        val miuiInputClass = runCatching {
            classLoader.loadClass("com.android.server.input.MiuiInputManagerService")
        }.getOrNull() ?: return
        val contextField = findField(miuiInputClass, "mContext") ?: wmsContextField

        miuiInputClass.declaredMethods
            .filter { it.name == "requestRedirect" }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val geometry = SystemServerWorkspaceBridge.observedActiveGeometry()
                            ?: return chain.proceed()
                        val context = wmContext ?: contextField?.let { f ->
                            runCatching { f.get(chain.thisObject) as? Context }.getOrNull()
                        }?.also { wmContext = it }
                        val elapsed = SystemClock.uptimeMillis() - lastDownUptimeMs
                        val inRotatedBackRegion = context != null &&
                            SystemServerWorkspaceBridge.isPointInRotatedBackRegion(context, lastDownRawX, lastDownRawY)
                        if (elapsed in 0..REDIRECT_BLOCK_WINDOW_MS &&
                            (geometry.isPointInClaimedChrome(lastDownRawX, lastDownRawY) || inRotatedBackRegion)
                        ) {
                            NeXtepLog.debug(
                                "hyperos_edge_redirect",
                                "Blocked MIUI edge redirect inside NeXtep chrome",
                            )
                            return null
                        }
                        // Ensure context initialized lazily if systemReady hook didn't capture first.
                        if (wmContext == null) {
                            contextField?.let { f ->
                                runCatching { (f.get(chain.thisObject) as? Context)?.let { wmContext = it } }
                            }
                        }
                        return chain.proceed()
                    }
                })
            }
        NeXtepLog.info("hyperos_input_guard", "Installed MiuiInputManagerService#requestRedirect guard")
    }

    private fun installPilferPointersHooks(module: XposedModule, classLoader: ClassLoader) {
        val candidates = listOf(
            "com.android.server.input.InputManagerService\$InputMonitorHost",
            "com.android.server.input.InputManagerService",
        )
        candidates.forEach { className ->
            val clazz = runCatching { classLoader.loadClass(className) }.getOrNull() ?: return@forEach
            clazz.declaredMethods
                .filter { it.name == "pilferPointers" && it.parameterCount <= 1 }
                .forEach { method ->
                    method.isAccessible = true
                    val originInvoker = runCatching {
                        module.getInvoker(method).setType(XposedInterface.Invoker.Type.ORIGIN)
                    }.getOrNull() ?: return@forEach

                    module.hook(method).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val callerUid = Binder.getCallingUid()
                            val thisObject = chain.thisObject
                            val argsCopy = chain.args.toTypedArray()
                            if (shouldHoldPilfer(callerUid)) {
                                holdPilfer {
                                    runCatching { originInvoker.invoke(thisObject, *argsCopy) }
                                }
                                return null
                            }
                            return chain.proceed()
                        }
                    })
                }
        }
    }

    private fun shouldHoldPilfer(callingUid: Int): Boolean {
        if (HyperOsEnvironment.isMagnificationFallbackActive()) return false
        if (miuiHomeUid >= 0 && callingUid != miuiHomeUid) return false
        val now = SystemClock.uptimeMillis()
        if (cornerState == CornerState.OBSERVING) return true
        if (cornerState == CornerState.CONFIRMED && now <= cornerConfirmedUntilMs) return true
        val geometry = SystemServerWorkspaceBridge.observedActiveGeometry() ?: return false
        val elapsed = now - lastDownUptimeMs
        return elapsed in 0..REDIRECT_BLOCK_WINDOW_MS &&
            geometry.isPointInClaimedChrome(lastDownRawX, lastDownRawY)
    }

    private fun holdPilfer(replay: () -> Unit) {
        synchronized(pilferLock) {
            pendingPilferTimeout?.let(mainHandler::removeCallbacks)
            pendingPilferReplay = replay
            val timeout = Runnable {
                replayHeldPilfer()
            }
            pendingPilferTimeout = timeout
            mainHandler.postDelayed(timeout, PILFER_HOLD_TIMEOUT_MS)
        }
    }

    private fun replayHeldPilfer() {
        val replay = synchronized(pilferLock) {
            pendingPilferTimeout?.let(mainHandler::removeCallbacks)
            pendingPilferTimeout = null
            pendingPilferReplay.also { pendingPilferReplay = null }
        }
        replay?.invoke()
    }

    private fun discardHeldPilfer() {
        synchronized(pilferLock) {
            pendingPilferTimeout?.let(mainHandler::removeCallbacks)
            pendingPilferTimeout = null
            pendingPilferReplay = null
        }
    }

    private fun flushHeldPilferOnWorkspaceExit() {
        if (cornerState == CornerState.CONFIRMED) {
            discardHeldPilfer()
        } else {
            replayHeldPilfer()
        }
        cornerState = CornerState.IGNORED
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
}
