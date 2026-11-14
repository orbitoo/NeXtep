package com.elbert.nextep.systemui

import android.view.Display
import android.view.SurfaceControl
import android.view.WindowManager
import com.elbert.nextep.framework.TaskSurfaceCompat
import com.elbert.nextep.launcher.LauncherWindowPolicy
import com.elbert.nextep.systemserver.HyperOsImeHooks
import com.elbert.nextep.workspace.SidebarSide
import com.elbert.nextep.workspace.WorkspaceGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperOsBugFixesTest {
    private class FakeFreeformTaskInfo(val id: Int)

    private class FakeMiuiFreeformRepo {
        @Suppress("UNUSED_PARAMETER")
        fun onTaskAppeared(info: FakeFreeformTaskInfo) = Unit

        @Suppress("UNUSED_PARAMETER")
        fun onTaskModeChanged(info: FakeFreeformTaskInfo, fromMode: Int, toMode: Int) = Unit

        @Suppress("UNUSED_PARAMETER")
        fun onTaskModeChanged(info: FakeFreeformTaskInfo, fromMode: Int, toMode: Int, animate: Boolean) = Unit

        @Suppress("UNUSED_PARAMETER")
        fun onTaskStateChanged(taskId: Int, state: Int) = Unit
    }

    private class FakeDisplayContent {
        @Suppress("UNUSED_PARAMETER")
        fun assignRelativeLayerForIme(tx: SurfaceControl.Transaction, imeTarget: Any?) = Unit

        @Suppress("UNUSED_PARAMETER")
        fun assignRelativeLayerForIme(tx: SurfaceControl.Transaction) = Unit

        @Suppress("UNUSED_PARAMETER")
        fun assignRelativeLayerForIme(otherArg: String) = Unit
    }

    @Test
    fun screenCaptureFallbackIncludesAospScreenCaptureForHyperOs3() {
        assertEquals(
            listOf("android.window.ScreenCaptureInternal", "android.window.ScreenCapture"),
            TaskSurfaceCompat.screenCaptureClassCandidates(),
        )
        assertEquals(
            listOf(
                "android.window.ScreenCaptureInternal\$LayerCaptureArgs\$Builder",
                "android.window.ScreenCapture\$LayerCaptureArgs\$Builder",
            ),
            TaskSurfaceCompat.screenCaptureClassCandidates("LayerCaptureArgs\$Builder"),
        )
    }

    @Test
    fun imeInsetsAndHintCompensationMatchExactlyInPortraitWorkspace() {
        val portrait = WorkspaceGeometry(
            screenWidth = 1080,
            screenHeight = 2400,
            topHeight = 420,
            sidebarWidth = 240,
            sidebarSide = SidebarSide.RIGHT,
            displayRotation = 0,
        )
        val rawImeBottom = 900
        val rawImeTop = portrait.screenHeight - rawImeBottom // 1500
        val compTop = HyperOsImeHooks.computeCompensatedImeBottom(rawImeTop, portrait)
        val compHint = HyperOsImeHooks.computeCompensatedImeHintBottom(rawImeBottom, portrait)

        assertTrue(compTop < rawImeTop)
        assertEquals(portrait.screenHeight - compTop, compHint)
        // Mapped local Y * scale + translationY should align with the physical IME top within 1px.
        val mappedScreenY = portrait.contentTranslationY + compTop * portrait.contentScale
        assertEquals(rawImeTop.toFloat(), mappedScreenY, 1.5f)
    }

    @Test
    fun imeInsetsHookMatchersRecognizeWindowStateDispatchMethods() {
        assertTrue(
            HyperOsImeHooks.matchesFillInsetsStateSignature(
                "fillInsetsState",
                listOf("android.view.InsetsState", "boolean"),
            ),
        )
        assertFalse(
            HyperOsImeHooks.matchesFillInsetsStateSignature(
                "fillInsetsState",
                listOf("android.view.InsetsSourceControl\$Array", "boolean"),
            ),
        )
        assertTrue(
            HyperOsImeHooks.matchesFillInsetsSourceControlsSignature(
                "fillInsetsSourceControls",
                listOf("android.view.InsetsSourceControl\$Array", "boolean"),
            ),
        )
        assertFalse(
            HyperOsImeHooks.matchesFillInsetsSourceControlsSignature(
                "fillInsetsSourceControls",
                listOf("android.view.InsetsState", "boolean"),
            ),
        )
    }

    @Test
    fun imeLayerHookMatchesTwoArgAndOneArgHyperOsSignatures() {
        val methods = FakeDisplayContent::class.java.declaredMethods.filter { it.name == "assignRelativeLayerForIme" }
        val twoArg = methods.first { it.parameterCount == 2 }
        val oneArg = methods.first { it.parameterCount == 1 && it.parameterTypes[0] == SurfaceControl.Transaction::class.java }
        val nonTx = methods.first { it.parameterTypes[0] == String::class.java }

        assertTrue(HyperOsImeHooks.matchesAssignRelativeLayerForIme(twoArg))
        assertTrue(HyperOsImeHooks.matchesAssignRelativeLayerForIme(oneArg))
        assertFalse(HyperOsImeHooks.matchesAssignRelativeLayerForIme(nonTx))
    }

    @Test
    fun imeSurfaceFittingActivatesInLandscapeOrRotatedWorkspace() {
        val portrait = WorkspaceGeometry(
            screenWidth = 1080,
            screenHeight = 2400,
            topHeight = 420,
            sidebarWidth = 280,
            sidebarSide = SidebarSide.RIGHT,
            displayRotation = 0,
        )
        val landscape = WorkspaceGeometry(
            screenWidth = 2400,
            screenHeight = 1080,
            topHeight = 420,
            sidebarWidth = 280,
            sidebarSide = SidebarSide.RIGHT,
            displayRotation = 1,
        )
        assertFalse(HyperOsImeHooks.shouldFitImeSurface(portrait))
        assertTrue(HyperOsImeHooks.shouldFitImeSurface(landscape))
    }

    @Test
    fun freeformRepoMatcherAcceptsFirstParameterTaskInfoAcrossAllOverloads() {
        val methods = FakeMiuiFreeformRepo::class.java.declaredMethods
        val onAppeared = methods.first { it.name == "onTaskAppeared" }
        val onModeChanged3 = methods.first { it.name == "onTaskModeChanged" && it.parameterCount == 3 }
        val onModeChanged4 = methods.first { it.name == "onTaskModeChanged" && it.parameterCount == 4 }
        val onStateChanged = methods.first { it.name == "onTaskStateChanged" }

        assertTrue(
            HyperOsTransitionAndFreeformHooks.matchesMiuiFreeformRepoCallback(
                onAppeared.name,
                onAppeared.parameterTypes,
                FakeFreeformTaskInfo::class.java,
            ),
        )
        assertTrue(
            HyperOsTransitionAndFreeformHooks.matchesMiuiFreeformRepoCallback(
                onModeChanged3.name,
                onModeChanged3.parameterTypes,
                FakeFreeformTaskInfo::class.java,
            ),
        )
        assertTrue(
            HyperOsTransitionAndFreeformHooks.matchesMiuiFreeformRepoCallback(
                onModeChanged4.name,
                onModeChanged4.parameterTypes,
                FakeFreeformTaskInfo::class.java,
            ),
        )
        assertFalse(
            HyperOsTransitionAndFreeformHooks.matchesMiuiFreeformRepoCallback(
                onStateChanged.name,
                onStateChanged.parameterTypes,
                FakeFreeformTaskInfo::class.java,
            ),
        )

        val info = FakeFreeformTaskInfo(77)
        val extracted = HyperOsTransitionAndFreeformHooks.extractMiuiFreeformTaskInfoArg(
            listOf(info, 0, 1, true),
            FakeFreeformTaskInfo::class.java,
        )
        assertSame(info, extracted)
    }

    @Test
    fun touchPassThroughPolicyComputesPanelLocalCutoutsAndClipsProperly() {
        val floating = listOf(
            FloatingWindowTouchPassThroughPolicy.ScreenBounds(200, 300, 900, 1200),
            FloatingWindowTouchPassThroughPolicy.ScreenBounds(50, 1500, 180, 1800), // outside topbar & right sidebar
            FloatingWindowTouchPassThroughPolicy.ScreenBounds(100, 100, 100, 200), // empty
        )

        // TopBar window at (0, 0, 1080, 420)
        val topBarCutouts = FloatingWindowTouchPassThroughPolicy.screenBoundsToWindowLocalRects(
            windowX = 0,
            windowY = 0,
            windowWidth = 1080,
            windowHeight = 420,
            floatingScreenBounds = floating,
        )
        assertEquals(listOf(FloatingWindowTouchPassThroughPolicy.ScreenBounds(200, 300, 900, 420)), topBarCutouts)

        // Sidebar window on right at (800, 420, 280, 1980)
        val sidebarCutouts = FloatingWindowTouchPassThroughPolicy.screenBoundsToWindowLocalRects(
            windowX = 800,
            windowY = 420,
            windowWidth = 280,
            windowHeight = 1980,
            floatingScreenBounds = floating,
        )
        assertEquals(listOf(FloatingWindowTouchPassThroughPolicy.ScreenBounds(0, 0, 100, 780)), sidebarCutouts)
    }

    @Test
    fun touchPassThroughPolicyMergesTrackedBoundsAndPreservesColorOsCompat() {
        val tracked = mapOf(
            10 to FloatingWindowTouchPassThroughPolicy.ScreenBounds(120, 240, 720, 1240),
            11 to FloatingWindowTouchPassThroughPolicy.ScreenBounds(50, 50, 450, 650),
        )
        val candidates = listOf(
            // Visible freeform task 10: tracked scaled bounds take precedence over unscaled snapshot bounds
            FloatingWindowTouchPassThroughPolicy.FloatingTaskCandidate(
                taskId = 10,
                displayId = Display.DEFAULT_DISPLAY,
                bounds = FloatingWindowTouchPassThroughPolicy.ScreenBounds(0, 0, 1080, 2400),
                windowingMode = 1,
                vendorWindowed = true,
                isVisible = true,
            ),
            // Hidden task 11: must NOT punch an invisible cutout even if present in tracked map
            FloatingWindowTouchPassThroughPolicy.FloatingTaskCandidate(
                taskId = 11,
                displayId = Display.DEFAULT_DISPLAY,
                bounds = FloatingWindowTouchPassThroughPolicy.ScreenBounds(50, 50, 450, 650),
                windowingMode = 1,
                vendorWindowed = true,
                isVisible = false,
            ),
            // PiP task 12: standard pinned window included directly
            FloatingWindowTouchPassThroughPolicy.FloatingTaskCandidate(
                taskId = 12,
                displayId = Display.DEFAULT_DISPLAY,
                bounds = FloatingWindowTouchPassThroughPolicy.ScreenBounds(700, 1400, 1040, 1900),
                windowingMode = 2,
                vendorWindowed = false,
                isVisible = true,
            ),
            // Fullscreen main task 13: excluded
            FloatingWindowTouchPassThroughPolicy.FloatingTaskCandidate(
                taskId = 13,
                displayId = Display.DEFAULT_DISPLAY,
                bounds = FloatingWindowTouchPassThroughPolicy.ScreenBounds(0, 0, 1080, 2400),
                windowingMode = 1,
                vendorWindowed = false,
                isVisible = true,
            ),
        )

        val hyperOsMerged = FloatingWindowTouchPassThroughPolicy.mergeFloatingCandidates(
            trackedBounds = tracked,
            candidates = candidates,
            isHyperOs = true,
            isTaskTracked = { it == 10 },
        )
        assertEquals(
            listOf(
                FloatingWindowTouchPassThroughPolicy.ScreenBounds(120, 240, 720, 1240),
                FloatingWindowTouchPassThroughPolicy.ScreenBounds(700, 1400, 1040, 1900),
            ),
            hyperOsMerged,
        )

        // ColorOS scenario=1/2 flexible window keeps vendorWindowed=true without tracked map
        val colorOsCandidate = FloatingWindowTouchPassThroughPolicy.FloatingTaskCandidate(
            taskId = 20,
            displayId = Display.DEFAULT_DISPLAY,
            bounds = FloatingWindowTouchPassThroughPolicy.ScreenBounds(150, 300, 900, 1700),
            windowingMode = 1,
            vendorWindowed = true,
            isVisible = true,
        )
        val colorOsMerged = FloatingWindowTouchPassThroughPolicy.mergeFloatingCandidates(
            trackedBounds = emptyMap(),
            candidates = listOf(colorOsCandidate),
            isHyperOs = false,
            isTaskTracked = { false },
        )
        assertEquals(
            listOf(FloatingWindowTouchPassThroughPolicy.ScreenBounds(150, 300, 900, 1700)),
            colorOsMerged,
        )
    }

    @Test
    fun transitionRootTransformOmitsPreScaleCropAndAppliesContentTransform() {
        val leftSidebarGeom = WorkspaceGeometry(
            screenWidth = 1080,
            screenHeight = 2400,
            topHeight = 420,
            sidebarWidth = 280,
            sidebarSide = SidebarSide.LEFT,
        )
        val transform = HyperOsTransitionAndFreeformHooks.computeTransitionRootTransform(
            geom = leftSidebarGeom,
            offsetX = 10,
            offsetY = 20,
        )
        assertNull(transform.crop)
        assertEquals(leftSidebarGeom.contentScale, transform.scale, 1e-4f)
        assertEquals(
            leftSidebarGeom.contentTranslationX + leftSidebarGeom.contentScale * 10f,
            transform.tx,
            1e-4f,
        )
        assertEquals(
            leftSidebarGeom.contentTranslationY + leftSidebarGeom.contentScale * 20f,
            transform.ty,
            1e-4f,
        )
    }

    @Test
    fun transitionCounterScaledChildTransformCancelsRootTransform() {
        val geom = WorkspaceGeometry(
            screenWidth = 1440,
            screenHeight = 3200,
            topHeight = 804,
            sidebarWidth = 362,
            sidebarSide = SidebarSide.LEFT,
        )
        val root = HyperOsTransitionAndFreeformHooks.computeTransitionRootTransform(
            geom = geom,
            offsetX = 12,
            offsetY = 24,
        )
        val counter = HyperOsTransitionAndFreeformHooks.computeCounterScaledChildTransform(root)

        assertEquals(1f, root.scale * counter.scale, 1e-5f)
        assertEquals(0f, root.tx + root.scale * counter.tx, 1e-4f)
        assertEquals(0f, root.ty + root.scale * counter.ty, 1e-4f)
    }

    @Test
    fun assistantPanelMatcherSupportsHyperOsDrawnOverlayAndPreservesColorOs() {
        assertTrue(
            LauncherWindowPolicy.isAssistantPanelWindow(
                displayId = Display.DEFAULT_DISPLAY,
                type = WindowManager.LayoutParams.TYPE_DRAWN_APPLICATION,
                title = "LauncherOverlayWindow:com.miui.personalassistant",
                packageName = "com.miui.personalassistant",
            ),
        )
        assertTrue(
            LauncherWindowPolicy.isAssistantPanelWindow(
                displayId = Display.DEFAULT_DISPLAY,
                type = WindowManager.LayoutParams.TYPE_APPLICATION,
                title = "com.coloros.assistantscreen",
                packageName = "com.coloros.assistantscreen",
            ),
        )
        assertTrue(
            LauncherWindowPolicy.isAssistantPanelWindow(
                displayId = Display.DEFAULT_DISPLAY,
                type = WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW,
                title = "AnyOverlay",
                packageName = "com.miui.personalassistant",
            ),
        )
        assertFalse(
            LauncherWindowPolicy.isAssistantPanelWindow(
                displayId = Display.DEFAULT_DISPLAY,
                type = WindowManager.LayoutParams.TYPE_BASE_APPLICATION,
                title = "com.miui.personalassistant",
                packageName = "com.miui.personalassistant",
            ),
        )
        assertFalse(
            LauncherWindowPolicy.isAssistantPanelWindow(
                displayId = 2,
                type = WindowManager.LayoutParams.TYPE_DRAWN_APPLICATION,
                title = "LauncherOverlayWindow:com.miui.personalassistant",
                packageName = "com.miui.personalassistant",
            ),
        )
    }

    @Test
    fun launcherTouchOutermostDispatchGuardsNestedSuperCallsAndResetsOnFailure() {
        val depth = ThreadLocal.withInitial { 0 }
        var outerCount = 0
        var innerCount = 0

        val result = LauncherWindowPolicy.runOutermostDispatch(
            depth = depth,
            proceed = { "unexpected" },
            transformAndProceed = {
                outerCount += 1
                LauncherWindowPolicy.runOutermostDispatch(
                    depth = depth,
                    proceed = {
                        innerCount += 1
                        "handled"
                    },
                    transformAndProceed = {
                        outerCount += 1
                        "should-not-transform-twice"
                    },
                )
            },
        )
        assertEquals("handled", result)
        assertEquals(1, outerCount)
        assertEquals(1, innerCount)
        assertEquals(0, depth.get())

        val failed = runCatching {
            LauncherWindowPolicy.runOutermostDispatch(
                depth = depth,
                proceed = { Unit },
                transformAndProceed = { error("boom") },
            )
        }
        assertTrue(failed.isFailure)
        assertEquals(0, depth.get())
    }
}
