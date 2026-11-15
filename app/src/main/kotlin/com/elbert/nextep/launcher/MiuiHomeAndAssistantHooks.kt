package com.elbert.nextep.launcher

import android.content.ContentResolver
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import com.elbert.nextep.framework.HyperOsEnvironment
import com.elbert.nextep.logging.NeXtepLog
import com.elbert.nextep.workspace.SystemServerWorkspaceBridge
import com.elbert.nextep.xposed.HookGuard
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexFile
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HyperOS 3 launcher (`com.miui.home`) and minus-one (`com.miui.personalassistant`) hooks.
 *
 * Installed alongside `ColorOsLauncherHook` (which handles generic `Activity`, dialog layout,
 * night icon tinting, and `Instrumentation.execStartActivity` redirection).
 */
object MiuiHomeAndAssistantHooks {
    private const val SECURE_BACKGROUND_BLUR_KEY = "background_blur_enable"
    private const val LAYER_SKIP_WINDOW_MS = 600L
    private const val ACTIVE_CACHE_TTL_MS = 500L

    private val miuiHomeInstalled = AtomicBoolean(false)
    private val assistantInstalled = AtomicBoolean(false)
    private val dexScanStarted = AtomicBoolean(false)
    private val layerHookedClasses = ConcurrentHashMap.newKeySet<String>()

    private val blurAdapterActiveCache: MutableMap<Any, Boolean> =
        Collections.synchronizedMap(WeakHashMap())
    private val contentViewBlurConfigured: MutableMap<Any, Boolean> =
        Collections.synchronizedMap(WeakHashMap())
    private val assistantHeaderBlurSuppressed = AtomicBoolean(false)
    private val assistantChildViewBlurOverride = ThreadLocal<Boolean?>()

    @Volatile
    private var skipLayerAnimUntilUptimeMs: Long = Long.MIN_VALUE

    @Volatile
    private var cachedSecureBlurActiveAtMs: Long = Long.MIN_VALUE

    @Volatile
    private var cachedSecureBlurActive: Boolean = false

    fun installMiuiHome(module: XposedModule, classLoader: ClassLoader) {
        if (!HyperOsEnvironment.isHyperOs()) return
        if (!miuiHomeInstalled.compareAndSet(false, true)) return

        HookGuard.run("miui_home_blur") {
            installSecureBlurHook(module)
            installRecentBlurTargetHook(module, classLoader)
        }
        HookGuard.run("miui_home_edge_back") {
            installGestureStubHook(module, classLoader)
        }
        HookGuard.run("miui_home_animations") {
            installSpringAndLayerHooks(module, classLoader)
        }
    }

    fun installPersonalAssistant(module: XposedModule, classLoader: ClassLoader) {
        if (!HyperOsEnvironment.isHyperOs()) return
        if (!assistantInstalled.compareAndSet(false, true)) return

        HookGuard.run("miui_assistant_blur") {
            installAssistantBlurHook(module, classLoader)
        }
    }

    private fun installSecureBlurHook(module: XposedModule) {
        val secureClass = Settings.Secure::class.java
        secureClass.declaredMethods
            .filter {
                (it.name == "getInt" || it.name == "getIntForUser") &&
                    it.parameterTypes.size >= 2 &&
                    it.parameterTypes[0] == ContentResolver::class.java &&
                    it.parameterTypes[1] == String::class.java
            }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val key = chain.args.getOrNull(1) as? String
                        if (key == SECURE_BACKGROUND_BLUR_KEY && isBlurSuppressionActive()) {
                            return 0
                        }
                        return chain.proceed()
                    }
                })
            }
        NeXtepLog.info("miui_home_blur", "Installed Settings.Secure background blur hook")
    }

    private fun installRecentBlurTargetHook(module: XposedModule, classLoader: ClassLoader) {
        val blurClass = runCatching {
            classLoader.loadClass("com.miui.home.recents.anim.RecentBlurViewElement")
        }.getOrNull() ?: return
        val updateMethod = runCatching {
            blurClass.getDeclaredMethod("updateTargetParams", Float::class.javaObjectType, Float::class.javaObjectType)
                .apply { isAccessible = true }
        }.getOrNull() ?: return

        module.hook(updateMethod).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                if (isBlurSuppressionActive()) {
                    return chain.proceed(arrayOf(0f, 0f))
                }
                return chain.proceed()
            }
        })
        NeXtepLog.info("miui_home_blur", "Installed RecentBlurViewElement#updateTargetParams hook")
    }

    private fun installGestureStubHook(module: XposedModule, classLoader: ClassLoader) {
        val stubClass = runCatching {
            classLoader.loadClass("com.miui.home.recents.GestureStubView")
        }.getOrNull() ?: return

        val shouldRedirectField = runCatching {
            stubClass.getDeclaredField("mShouldRedirectMotionEvent").apply { isAccessible = true }
        }.getOrNull()

        val shouldRedirectMethod = runCatching {
            stubClass.getDeclaredMethod("shouldRedirectEvent", MotionEvent::class.java)
                .apply { isAccessible = true }
        }.getOrNull() ?: return

        module.hook(shouldRedirectMethod).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                val event = chain.args.firstOrNull() as? MotionEvent
                if (result == true && event != null && event.actionMasked == MotionEvent.ACTION_DOWN) {
                    val view = chain.thisObject as? View ?: return result
                    val context = view.context ?: return result
                    if (!SystemServerWorkspaceBridge.isGeometryObservationStarted()) {
                        SystemServerWorkspaceBridge.startGeometryObservation(context)
                    }
                    val geom = SystemServerWorkspaceBridge.activeGeometry(context) ?: return result
                    if (geom.isPointInClaimedChrome(event.rawX, event.rawY) ||
                        SystemServerWorkspaceBridge.isPointInRotatedBackRegion(context, event.rawX, event.rawY)
                    ) {
                        runCatching { shouldRedirectField?.setBoolean(view, false) }
                        NeXtepLog.debug(
                            "miui_edge_back",
                            "Blocked GestureStubView edge redirect at (${event.rawX.toInt()}, ${event.rawY.toInt()})",
                        )
                        return false
                    }
                }
                return result
            }
        })
        NeXtepLog.info("miui_home_edge_back", "Installed GestureStubView#shouldRedirectEvent hook")
    }

    private fun installSpringAndLayerHooks(module: XposedModule, classLoader: ClassLoader) {
        var missingAny = false

        val springClass = runCatching {
            classLoader.loadClass("com.miui.home.recents.util.RectFSpringAnim")
        }.getOrNull()
        if (springClass != null) {
            hookRectFSpringAnimClass(module, springClass)
        } else {
            missingAny = true
        }

        val layerNames = listOf(
            "com.miui.home.recents.anim.ShortcutMenuLayerElement",
            "com.miui.home.recents.anim.RecentBlurViewElement",
            "com.miui.home.recents.anim.HomeLayerElement",
            "com.miui.home.recents.anim.LocalWallpaperElement",
        )
        for (name in layerNames) {
            val cls = runCatching { classLoader.loadClass(name) }.getOrNull()
            if (cls != null) {
                hookLayerElementClass(module, cls)
            } else {
                missingAny = true
            }
        }

        if (missingAny) {
            startAsyncDexScanForHomeAnims(module, classLoader)
        }
    }

    private fun hookRectFSpringAnimClass(module: XposedModule, springClass: Class<*>): Boolean {
        if (!layerHookedClasses.add(springClass.name)) return true
        val endMethod = runCatching {
            springClass.getDeclaredMethod("endAndDoFrame").apply { isAccessible = true }
        }.getOrNull() ?: return false

        val startMethods = springClass.declaredMethods.filter {
            it.name.startsWith("startIn") && it.parameterCount == 0
        }
        if (startMethods.isEmpty()) return false

        val hooker = object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                if (isWorkspaceActiveCached()) {
                    runCatching {
                        endMethod.invoke(chain.thisObject)
                        skipLayerAnimUntilUptimeMs = SystemClock.uptimeMillis() + LAYER_SKIP_WINDOW_MS
                    }
                }
                return result
            }
        }
        startMethods.forEach { method ->
            method.isAccessible = true
            module.hook(method).intercept(hooker)
        }
        NeXtepLog.info("miui_home_animations", "Installed RectFSpringAnim skip hooks (${startMethods.size})")
        return true
    }

    private fun hookLayerElementClass(module: XposedModule, layerClass: Class<*>): Boolean {
        if (!layerHookedClasses.add(layerClass.name)) return true
        val animToMethod = layerClass.declaredMethods.firstOrNull {
            it.name == "animTo" && it.parameterCount == 1
        }?.apply { isAccessible = true } ?: return false

        val skipTargets = findSkipToEndFields(layerClass)
        if (skipTargets.isEmpty()) return false

        module.hook(animToMethod).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                if (SystemClock.uptimeMillis() < skipLayerAnimUntilUptimeMs && isWorkspaceActiveCached()) {
                    val host = chain.thisObject ?: return result
                    for ((field, skipMethod) in skipTargets) {
                        runCatching {
                            val animObj = field.get(host) ?: return@runCatching
                            skipMethod.invoke(animObj)
                        }
                    }
                }
                return result
            }
        })
        NeXtepLog.info("miui_home_animations", "Installed ${layerClass.simpleName}#animTo skip hook")
        return true
    }

    private fun findSkipToEndFields(layerClass: Class<*>): List<Pair<Field, Method>> {
        val result = ArrayList<Pair<Field, Method>>()
        var current: Class<*>? = layerClass
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                val skipMethod = generateSequence<Class<*>>(field.type) { it.superclass }
                    .firstNotNullOfOrNull { type ->
                        runCatching {
                            type.getDeclaredMethod("skipToEnd").apply { isAccessible = true }
                        }.getOrNull()
                    }
                if (skipMethod != null) {
                    field.isAccessible = true
                    result += field to skipMethod
                }
            }
            current = current.superclass
        }
        return result
    }

    private fun startAsyncDexScanForHomeAnims(module: XposedModule, classLoader: ClassLoader) {
        if (!dexScanStarted.compareAndSet(false, true)) return
        if (classLoader !is BaseDexClassLoader) return
        Thread({
            runCatching {
                val pathListField = generateSequence<Class<*>>(classLoader.javaClass) { it.superclass }
                    .firstNotNullOfOrNull { runCatching { it.getDeclaredField("pathList") }.getOrNull() }
                    ?.apply { isAccessible = true } ?: return@Thread
                val pathList = pathListField.get(classLoader) ?: return@Thread
                val dexElementsField = runCatching {
                    pathList.javaClass.getDeclaredField("dexElements").apply { isAccessible = true }
                }.getOrNull() ?: return@Thread
                val elements = dexElementsField.get(pathList) as? Array<*> ?: return@Thread

                for (element in elements) {
                    if (element == null) continue
                    val dexFileField = runCatching {
                        element.javaClass.getDeclaredField("dexFile").apply { isAccessible = true }
                    }.getOrNull() ?: continue
                    @Suppress("DEPRECATION")
                    val dexFile = dexFileField.get(element) as? DexFile ?: continue
                    @Suppress("DEPRECATION")
                    val entries = dexFile.entries()
                    while (entries.hasMoreElements()) {
                        val name = entries.nextElement()
                        if (!name.startsWith("com.miui.home.") || name.contains('$')) continue
                        if (layerHookedClasses.contains(name)) continue
                        val cls = runCatching { classLoader.loadClass(name) }.getOrNull() ?: continue
                        val hasEnd = cls.declaredMethods.any { it.name == "endAndDoFrame" && it.parameterCount == 0 }
                        if (hasEnd) {
                            hookRectFSpringAnimClass(module, cls)
                        } else if (cls.declaredMethods.any { it.name == "animTo" && it.parameterCount == 1 }) {
                            if (findSkipToEndFields(cls).isNotEmpty()) {
                                hookLayerElementClass(module, cls)
                            }
                        }
                    }
                }
            }
        }, "NeXtep-MiuiHomeScan").apply { isDaemon = true }.start()
    }

    internal data class AssistantHeaderBlurTargets(
        val headControllerField: Field,
        val searchManagerField: Field,
        val managerContextField: Field?,
        val updateHeaderStyleMethod: Method,
        val deviceBlurSupportMethod: Method,
        val widgetMenuAttachMethod: Method?,
    )

    private fun installAssistantBlurHook(module: XposedModule, classLoader: ClassLoader) {
        val adapterClass = runCatching {
            classLoader.loadClass("com.miui.personalassistant.device.DeviceBlurBlendAdapter")
        }.getOrNull() ?: return

        val superclass = adapterClass.superclass ?: return
        val blurStateField = runCatching {
            superclass.getDeclaredField("mBlurState").apply { isAccessible = true }
        }.getOrNull() ?: return
        val overlayField = runCatching {
            superclass.getDeclaredField("mOverlay").apply { isAccessible = true }
        }.getOrNull() ?: return
        val contentViewField = runCatching {
            superclass.getDeclaredField("mContentView").apply { isAccessible = true }
        }.getOrNull()
        val widgetBlurManagerClass = runCatching {
            classLoader.loadClass("com.miui.personalassistant.core.blur.WidgetBackgroundBlurManager")
        }.getOrNull()
        val enableContainerBlurMethod = widgetBlurManagerClass?.let { cls ->
            runCatching { cls.getDeclaredMethod("e", View::class.java).apply { isAccessible = true } }.getOrNull()
        }
        val clearContainerBlurMethod = widgetBlurManagerClass?.let { cls ->
            runCatching { cls.getDeclaredMethod("b", View::class.java).apply { isAccessible = true } }.getOrNull()
        }
        val headerTargets = resolveAssistantHeaderBlurTargets(classLoader)
        if (headerTargets != null) {
            installAssistantChildViewBlurHooks(module, headerTargets)
        }

        val isViewBlur = runCatching {
            adapterClass.getDeclaredMethod("isViewBlur").apply { isAccessible = true }
        }.getOrNull()
        if (isViewBlur != null) {
            module.hook(isViewBlur).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val host = chain.thisObject ?: return chain.proceed()
                    return if (shouldForceWindowBlur(blurStateField, overlayField, host)) {
                        false
                    } else {
                        chain.proceed()
                    }
                }
            })
        }

        val isWindowBlur = runCatching {
            adapterClass.getDeclaredMethod("isWindowBlur").apply { isAccessible = true }
        }.getOrNull()
        if (isWindowBlur != null) {
            module.hook(isWindowBlur).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val host = chain.thisObject ?: return chain.proceed()
                    return if (shouldForceWindowBlur(blurStateField, overlayField, host)) {
                        true
                    } else {
                        chain.proceed()
                    }
                }
            })
        }

        runCatching {
            adapterClass.getDeclaredMethod("initBlurParams").apply { isAccessible = true }
        }.getOrNull()?.let { initMethod ->
            module.hook(initMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val host = chain.thisObject
                    if (host != null) {
                        blurAdapterActiveCache.remove(host)
                    }
                    val result = chain.proceed()
                    if (host != null) {
                        val forceWindowBlur = shouldForceWindowBlur(blurStateField, overlayField, host)
                        syncAssistantContentBlurContainer(
                            adapter = host,
                            contentViewField = contentViewField,
                            enableContainerBlurMethod = enableContainerBlurMethod,
                            clearContainerBlurMethod = clearContainerBlurMethod,
                            enable = forceWindowBlur,
                        )
                        syncAssistantHeaderBlur(
                            adapter = host,
                            contentViewField = contentViewField,
                            headerTargets = headerTargets,
                            suppressViewBlur = forceWindowBlur,
                        )
                    }
                    return result
                }
            })
        }

        runCatching {
            adapterClass.getDeclaredMethod("onEnter", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        }.getOrNull()?.let { enterMethod ->
            module.hook(enterMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val host = chain.thisObject
                    if (host != null && shouldForceWindowBlur(blurStateField, overlayField, host)) {
                        syncAssistantContentBlurContainer(
                            adapter = host,
                            contentViewField = contentViewField,
                            enableContainerBlurMethod = enableContainerBlurMethod,
                            clearContainerBlurMethod = clearContainerBlurMethod,
                            enable = true,
                        )
                        syncAssistantHeaderBlur(
                            adapter = host,
                            contentViewField = contentViewField,
                            headerTargets = headerTargets,
                            suppressViewBlur = true,
                        )
                    }
                    return result
                }
            })
        }

        runCatching {
            adapterClass.getDeclaredMethod("onLeave").apply { isAccessible = true }
        }.getOrNull()?.let { leaveMethod ->
            module.hook(leaveMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val host = chain.thisObject
                    if (host != null) {
                        syncAssistantContentBlurContainer(
                            adapter = host,
                            contentViewField = contentViewField,
                            enableContainerBlurMethod = enableContainerBlurMethod,
                            clearContainerBlurMethod = clearContainerBlurMethod,
                            enable = false,
                        )
                        syncAssistantHeaderBlur(
                            adapter = host,
                            contentViewField = contentViewField,
                            headerTargets = headerTargets,
                            suppressViewBlur = false,
                        )
                        blurAdapterActiveCache.remove(host)
                    }
                    return result
                }
            })
        }
        NeXtepLog.info("miui_assistant_blur", "Installed DeviceBlurBlendAdapter hooks")
    }

    /**
     * In native `BLUR_VIEW` mode, `HomeHeaderWithSearchManager` (`Lm5/m`) applies `GlobalSearchView`'s
     * `"button-thick"` `MaterialToken` (`COLOR_DODGE #99525252`), expecting `DecorView` to have already
     * darkened the blur buffer via `LUMINOSITY #a81f1f1f` + `OVERLAY #ff9a9a9a`. When NeXtep switches the
     * minus-one overlay to `BLUR_WINDOW` in workspace mode, `mContentView` is an un-tinted pass-through
     * blur container (and MIUI's `PassWindowBlur` ignores the scaled workspace task transform), causing
     * `COLOR_DODGE` to blow out the search box and `+` button to solid cream-white.
     *
     * Scoping `q.B(Context) -> false` strictly to `HomeHeaderWithSearchManager` and `WidgetMenu` makes
     * MIUI execute its own native `BLUR_WINDOW` branch (clearing `HyperMaterialUtils` view blur and
     * applying translucent `#33ffffff` drawables directly over SurfaceFlinger's window blur) without
     * starving `WidgetBackgroundBlurManager` widget card blur.
     */
    private fun installAssistantChildViewBlurHooks(
        module: XposedModule,
        targets: AssistantHeaderBlurTargets,
    ) {
        module.hook(targets.deviceBlurSupportMethod).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                if (shouldSuppressAssistantChildViewBlurCall()) {
                    return false
                }
                return chain.proceed()
            }
        })

        module.hook(targets.updateHeaderStyleMethod).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val manager = chain.thisObject ?: return chain.proceed()
                val context = runCatching { targets.managerContextField?.get(manager) as? Context }.getOrNull()
                return interceptAssistantHeaderStyleUpdate(
                    shouldSuppress = { shouldSuppressAssistantChildViewBlurForContext(context) },
                ) {
                    chain.proceed()
                }
            }
        })

        targets.widgetMenuAttachMethod?.let { attachMethod ->
            module.hook(attachMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val view = chain.thisObject as? View ?: return chain.proceed()
                    return if (shouldSuppressAssistantChildViewBlurForContext(view.context)) {
                        withAssistantChildViewBlurOverride(allowViewBlur = false) {
                            chain.proceed()
                        }
                    } else {
                        chain.proceed()
                    }
                }
            })
        }
        NeXtepLog.info("miui_assistant_blur", "Installed minus-one header/menu window-blur adaptation hooks")
    }

    internal fun resolveAssistantHeaderBlurTargets(
        classLoader: ClassLoader,
        assistContentViewClassName: String = "com.miui.personalassistant.core.view.AssistContentView",
        globalSearchViewClassName: String = "com.miui.personalassistant.homepage.header.GlobalSearchView",
        deviceUtilsClassName: String = "com.miui.personalassistant.utils.q",
        widgetMenuClassName: String = "com.miui.personalassistant.homepage.shortcut.WidgetMenu",
    ): AssistantHeaderBlurTargets? {
        val assistContentViewClass = runCatching { classLoader.loadClass(assistContentViewClassName) }.getOrNull()
            ?: return null
        val globalSearchViewClass = runCatching { classLoader.loadClass(globalSearchViewClassName) }.getOrNull()
            ?: return null
        val deviceUtilsClass = runCatching { classLoader.loadClass(deviceUtilsClassName) }.getOrNull()
            ?: return null

        val headControllerField = runCatching {
            assistContentViewClass.getDeclaredField("mHeadController").apply { isAccessible = true }
        }.getOrNull() ?: return null

        val searchManagerField = headControllerField.type.declaredFields.firstOrNull { field ->
            field.type.declaredFields.any { it.type == globalSearchViewClass }
        }?.apply { isAccessible = true } ?: return null

        val searchManagerClass = searchManagerField.type
        val managerContextField = generateSequence<Class<*>>(searchManagerClass) { it.superclass }
            .firstNotNullOfOrNull { cls ->
                cls.declaredFields.firstOrNull { Context::class.java.isAssignableFrom(it.type) }
            }?.apply { isAccessible = true }

        val abstractRefreshName = searchManagerClass.superclass?.declaredMethods?.firstOrNull {
            Modifier.isAbstract(it.modifiers) && it.parameterCount == 0 && it.returnType == Void.TYPE
        }?.name ?: "g"

        val updateHeaderStyleMethod = runCatching {
            searchManagerClass.getDeclaredMethod(abstractRefreshName).apply { isAccessible = true }
        }.getOrNull() ?: return null

        val deviceBlurSupportMethod = resolveDeviceBlurSupportMethod(deviceUtilsClass) ?: return null

        val widgetMenuAttachMethod = runCatching {
            classLoader.loadClass(widgetMenuClassName)
                .getDeclaredMethod("onAttachedToWindow")
                .apply { isAccessible = true }
        }.getOrNull()

        return AssistantHeaderBlurTargets(
            headControllerField = headControllerField,
            searchManagerField = searchManagerField,
            managerContextField = managerContextField,
            updateHeaderStyleMethod = updateHeaderStyleMethod,
            deviceBlurSupportMethod = deviceBlurSupportMethod,
            widgetMenuAttachMethod = widgetMenuAttachMethod,
        )
    }

    internal fun resolveDeviceBlurSupportMethod(deviceUtilsClass: Class<*>): Method? {
        val uniqueStatic = deviceUtilsClass.declaredMethods.singleOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.returnType == Boolean::class.javaPrimitiveType &&
                method.parameterTypes.contentEquals(arrayOf(Context::class.java))
        }
        return (uniqueStatic ?: runCatching {
            deviceUtilsClass.getDeclaredMethod("B", Context::class.java)
        }.getOrNull())?.apply { isAccessible = true }
    }

    internal fun shouldSuppressAssistantChildViewBlurCall(): Boolean =
        assistantChildViewBlurOverride.get() == false

    internal fun <T> withAssistantChildViewBlurOverride(allowViewBlur: Boolean, block: () -> T): T {
        val previous = assistantChildViewBlurOverride.get()
        assistantChildViewBlurOverride.set(allowViewBlur)
        return try {
            block()
        } finally {
            if (previous == null) {
                assistantChildViewBlurOverride.remove()
            } else {
                assistantChildViewBlurOverride.set(previous)
            }
        }
    }

    internal fun <T> interceptAssistantHeaderStyleUpdate(
        shouldSuppress: () -> Boolean,
        proceed: () -> T,
    ): T {
        val explicitOverride = assistantChildViewBlurOverride.get()
        if (explicitOverride != null) {
            if (!explicitOverride) {
                assistantHeaderBlurSuppressed.set(true)
            }
            return proceed()
        }
        return if (shouldSuppress()) {
            assistantHeaderBlurSuppressed.set(true)
            withAssistantChildViewBlurOverride(allowViewBlur = false) {
                proceed()
            }
        } else {
            proceed()
        }
    }

    internal fun syncAssistantHeaderBlur(
        adapter: Any,
        contentViewField: Field?,
        headerTargets: AssistantHeaderBlurTargets?,
        suppressViewBlur: Boolean,
    ) {
        if (headerTargets == null) return
        if (!suppressViewBlur && !assistantHeaderBlurSuppressed.get()) return
        val contentView = contentViewField?.let { field ->
            runCatching { field.get(adapter) }.getOrNull()
        } ?: return
        val headController = runCatching { headerTargets.headControllerField.get(contentView) }.getOrNull() ?: return
        val searchManager = runCatching { headerTargets.searchManagerField.get(headController) }.getOrNull() ?: return
        if (suppressViewBlur) {
            assistantHeaderBlurSuppressed.set(true)
            runCatching {
                withAssistantChildViewBlurOverride(allowViewBlur = false) {
                    headerTargets.updateHeaderStyleMethod.invoke(searchManager)
                }
            }.onFailure { NeXtepLog.warn("miui_assistant_blur", "Failed to apply window-blur header style", it) }
        } else {
            runCatching {
                withAssistantChildViewBlurOverride(allowViewBlur = true) {
                    headerTargets.updateHeaderStyleMethod.invoke(searchManager)
                }
                assistantHeaderBlurSuppressed.set(false)
            }.onFailure { NeXtepLog.warn("miui_assistant_blur", "Failed to restore view-blur header style", it) }
        }
    }

    internal fun resetAssistantHeaderBlurStateForTests() {
        assistantHeaderBlurSuppressed.set(false)
        assistantChildViewBlurOverride.remove()
    }

    private fun shouldSuppressAssistantChildViewBlurForContext(context: Context?): Boolean {
        if (HyperOsEnvironment.isMagnificationFallbackActive()) return false
        if (context == null) return isWorkspaceActiveCached()
        if (!SystemServerWorkspaceBridge.isGeometryObservationStarted()) {
            SystemServerWorkspaceBridge.startGeometryObservation(context)
        }
        return SystemServerWorkspaceBridge.isWorkspaceActive(context)
    }

    /**
     * When forcing window-level blur on a BLUR_VIEW device during workspace mode,
     * `DeviceBlurBlendAdapter.initBlurParams()` clears pass-through blur on `DecorView`,
     * starving child `setMiViewBlurMode(1)` widget cards unless `mContentView` acts as
     * the blur container (matching MIUI's native `!isViewBlur()` `updateBlurState()` path).
     * Clean up only adapters we configured so native mode exits with zero residual state.
     */
    private fun syncAssistantContentBlurContainer(
        adapter: Any,
        contentViewField: Field?,
        enableContainerBlurMethod: Method?,
        clearContainerBlurMethod: Method?,
        enable: Boolean,
    ) {
        val contentView = contentViewField?.let { field ->
            runCatching { field.get(adapter) as? View }.getOrNull()
        } ?: return
        if (enable) {
            if (enableContainerBlurMethod == null) return
            contentViewBlurConfigured[adapter] = true
            runCatching { enableContainerBlurMethod.invoke(null, contentView) }
                .onFailure { NeXtepLog.warn("miui_assistant_blur", "Failed to enable content container blur", it) }
        } else if (contentViewBlurConfigured.remove(adapter) == true) {
            if (clearContainerBlurMethod == null) return
            runCatching { clearContainerBlurMethod.invoke(null, contentView) }
                .onFailure { NeXtepLog.warn("miui_assistant_blur", "Failed to clear content container blur", it) }
        }
    }

    private fun shouldForceWindowBlur(
        blurStateField: Field,
        overlayField: Field,
        adapter: Any,
    ): Boolean {
        val state = runCatching { blurStateField.get(adapter) as? Enum<*> }.getOrNull() ?: return false
        if (state.name != "BLUR_VIEW" || HyperOsEnvironment.isMagnificationFallbackActive()) return false
        synchronized(blurAdapterActiveCache) {
            blurAdapterActiveCache[adapter]?.let { return it }
            val context = runCatching { overlayField.get(adapter) as? Context }.getOrNull()
            val active = if (context != null) {
                if (!SystemServerWorkspaceBridge.isGeometryObservationStarted()) {
                    SystemServerWorkspaceBridge.startGeometryObservation(context)
                }
                SystemServerWorkspaceBridge.isWorkspaceActive(context)
            } else {
                false
            }
            blurAdapterActiveCache[adapter] = active
            return active
        }
    }

    private fun isBlurSuppressionActive(): Boolean =
        isWorkspaceActiveCached() && !HyperOsEnvironment.isMagnificationFallbackActive()

    private fun isWorkspaceActiveCached(): Boolean {
        if (SystemServerWorkspaceBridge.isGeometryObservationStarted()) {
            return SystemServerWorkspaceBridge.isObservedWorkspaceActive()
        }
        val now = SystemClock.uptimeMillis()
        if (now - cachedSecureBlurActiveAtMs < ACTIVE_CACHE_TTL_MS) {
            return cachedSecureBlurActive
        }
        val ctx = currentApplicationContext() ?: return false
        SystemServerWorkspaceBridge.startGeometryObservation(ctx)
        val active = SystemServerWorkspaceBridge.isWorkspaceActive(ctx)
        cachedSecureBlurActive = active
        cachedSecureBlurActiveAtMs = now
        return active
    }

    private fun currentApplicationContext(): Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()
}
