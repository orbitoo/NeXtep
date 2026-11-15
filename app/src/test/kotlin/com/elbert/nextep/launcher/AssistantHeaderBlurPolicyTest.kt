package com.elbert.nextep.launcher

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AssistantHeaderBlurPolicyTest {
    class FakeGlobalSearchView {
        var viewBlurEnabled: Boolean = false
        var backgroundToken: String? = null
    }

    object FakeDeviceUtils {
        var systemBlurSwitchEnabled: Boolean = true

        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun B(context: Context?): Boolean {
            if (MiuiHomeAndAssistantHooks.shouldSuppressAssistantChildViewBlurCall()) {
                return false
            }
            return systemBlurSwitchEnabled
        }

        @JvmStatic
        fun w(): Boolean = true
    }

    abstract class FakeHeaderBaseManager(
        @JvmField val a: Context?,
    ) {
        @JvmField var e: Int = 1
        abstract fun f(columns: Int)
        abstract fun g()
    }

    class FakeTimeHeaderManager(context: Context?) : FakeHeaderBaseManager(context) {
        override fun f(columns: Int) = Unit
        override fun g() = Unit
    }

    class FakeSearchHeaderManager(context: Context?) : FakeHeaderBaseManager(context) {
        @JvmField val k = FakeGlobalSearchView()
        @JvmField var plusBackgroundToken: String? = null
        @JvmField var m: Boolean = true
        @JvmField var n: Boolean = false
        @JvmField var applyCalls: Int = 0

        override fun f(columns: Int) = Unit

        override fun g() {
            val viewBlur = FakeDeviceUtils.B(a)
            if (!m && viewBlur == n) {
                return
            }
            n = viewBlur
            m = false
            applyCalls++
            if (FakeDeviceUtils.B(a)) {
                k.viewBlurEnabled = true
                k.backgroundToken = null
                plusBackgroundToken = null
            } else {
                k.viewBlurEnabled = false
                k.backgroundToken = "pa_global_search_bg_light"
                plusBackgroundToken = "pa_picker_enter_background_light"
            }
        }
    }

    class FakeHeaderController(context: Context?) {
        @JvmField val b = FakeTimeHeaderManager(context)
        @JvmField val c = FakeSearchHeaderManager(context)
        @JvmField var d: FakeHeaderBaseManager = c
    }

    class FakeAssistContentView(context: Context?) {
        @JvmField val mHeadController = FakeHeaderController(context)
    }

    class FakeWidgetMenu {
        fun onAttachedToWindow() = Unit
    }

    open class FakeDeviceAdapter(
        @JvmField val mContentView: Any?,
    )

    class FakeDeviceBlurBlendAdapter(contentView: Any?) : FakeDeviceAdapter(contentView)

    private lateinit var targets: MiuiHomeAndAssistantHooks.AssistantHeaderBlurTargets

    @Before
    fun setUp() {
        MiuiHomeAndAssistantHooks.resetAssistantHeaderBlurStateForTests()
        FakeDeviceUtils.systemBlurSwitchEnabled = true
        val resolved = MiuiHomeAndAssistantHooks.resolveAssistantHeaderBlurTargets(
            classLoader = javaClass.classLoader!!,
            assistContentViewClassName = FakeAssistContentView::class.java.name,
            globalSearchViewClassName = FakeGlobalSearchView::class.java.name,
            deviceUtilsClassName = FakeDeviceUtils::class.java.name,
            widgetMenuClassName = FakeWidgetMenu::class.java.name,
        )
        assertNotNull(resolved)
        targets = resolved!!
    }

    @After
    fun tearDown() {
        MiuiHomeAndAssistantHooks.resetAssistantHeaderBlurStateForTests()
    }

    @Test
    fun structuralResolutionFindsSearchHeaderManagerAndAbstractRefreshAndBlurCapability() {
        assertEquals("mHeadController", targets.headControllerField.name)
        assertEquals(FakeSearchHeaderManager::class.java, targets.searchManagerField.type)
        assertEquals("a", targets.managerContextField?.name)
        assertEquals("g", targets.updateHeaderStyleMethod.name)
        assertEquals("B", targets.deviceBlurSupportMethod.name)
        assertEquals("onAttachedToWindow", targets.widgetMenuAttachMethod?.name)
    }

    @Test
    fun workspaceWindowBlurSwitchesHeaderToTranslucentDrawablesWithoutDisablingWidgetBlur() {
        val contentView = FakeAssistContentView(null)
        val searchManager = contentView.mHeadController.c
        // Stock cold start in normal mode: ViewBlur active (COLOR_DODGE MaterialToken, null drawable).
        searchManager.g()
        assertTrue(searchManager.k.viewBlurEnabled)
        assertEquals(null, searchManager.k.backgroundToken)
        assertEquals(1, searchManager.applyCalls)

        val adapter = FakeDeviceBlurBlendAdapter(contentView)
        val contentViewField = FakeDeviceAdapter::class.java.getDeclaredField("mContentView").apply {
            isAccessible = true
        }

        // Enter workspace minus-one: header switches to BLUR_WINDOW drawables, while widget blur query stays true.
        MiuiHomeAndAssistantHooks.syncAssistantHeaderBlur(
            adapter = adapter,
            contentViewField = contentViewField,
            headerTargets = targets,
            suppressViewBlur = true,
        )
        assertFalse(searchManager.k.viewBlurEnabled)
        assertEquals("pa_global_search_bg_light", searchManager.k.backgroundToken)
        assertEquals("pa_picker_enter_background_light", searchManager.plusBackgroundToken)
        assertEquals(2, searchManager.applyCalls)
        // WidgetBackgroundBlurManager queries FakeDeviceUtils.B outside header scope and must stay enabled.
        assertTrue(FakeDeviceUtils.B(null))

        // Leaving minus-one restores native BLUR_VIEW cleanly even if workspace mode is still active.
        MiuiHomeAndAssistantHooks.syncAssistantHeaderBlur(
            adapter = adapter,
            contentViewField = contentViewField,
            headerTargets = targets,
            suppressViewBlur = false,
        )
        assertTrue(searchManager.k.viewBlurEnabled)
        assertEquals(null, searchManager.k.backgroundToken)
        assertEquals(null, searchManager.plusBackgroundToken)
        assertEquals(3, searchManager.applyCalls)

        // Subsequent normal-mode syncs are pure no-ops when nothing was suppressed.
        MiuiHomeAndAssistantHooks.syncAssistantHeaderBlur(
            adapter = adapter,
            contentViewField = contentViewField,
            headerTargets = targets,
            suppressViewBlur = false,
        )
        assertEquals(3, searchManager.applyCalls)
    }

    @Test
    fun coldStartOrWallpaperUpdateInWorkspaceRestoresCleanlyWhenWorkspaceExits() {
        val contentView = FakeAssistContentView(null)
        val searchManager = contentView.mHeadController.c

        // Cold start in workspace mode invokes g() directly via AssistContentView.<init> before initBlurParams().
        MiuiHomeAndAssistantHooks.interceptAssistantHeaderStyleUpdate(
            shouldSuppress = { true },
        ) {
            searchManager.g()
        }
        assertFalse(searchManager.k.viewBlurEnabled)
        assertEquals("pa_global_search_bg_light", searchManager.k.backgroundToken)

        // User exits workspace mode and opens minus-one in normal mode: initBlurParams(forceWindowBlur = false) restores ViewBlur.
        val adapter = FakeDeviceBlurBlendAdapter(contentView)
        val contentViewField = FakeDeviceAdapter::class.java.getDeclaredField("mContentView").apply {
            isAccessible = true
        }
        MiuiHomeAndAssistantHooks.syncAssistantHeaderBlur(
            adapter = adapter,
            contentViewField = contentViewField,
            headerTargets = targets,
            suppressViewBlur = false,
        )
        assertTrue(searchManager.k.viewBlurEnabled)
        assertEquals(null, searchManager.k.backgroundToken)
    }
}
