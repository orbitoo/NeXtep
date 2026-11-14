package com.elbert.nextep.workspace

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSceneAndRotatedPaneTest {
    @Test
    fun landscapeVideoModeWireRoundtripAndCycle() {
        assertEquals(LandscapeVideoMode.TURNED, LandscapeVideoMode.DEFAULT)
        assertEquals(LandscapeVideoMode.TURNED, LandscapeVideoMode.fromWireValue(null))
        assertEquals(LandscapeVideoMode.TURNED, LandscapeVideoMode.fromWireValue("unknown"))
        assertEquals(LandscapeVideoMode.TOP, LandscapeVideoMode.fromWireValue(" TOP "))
        assertEquals(LandscapeVideoMode.FULLSCREEN, LandscapeVideoMode.fromWireValue("fullscreen"))

        assertEquals(LandscapeVideoMode.TOP, LandscapeVideoMode.TURNED.next())
        assertEquals(LandscapeVideoMode.FULLSCREEN, LandscapeVideoMode.TOP.next())
        assertEquals(LandscapeVideoMode.TURNED, LandscapeVideoMode.FULLSCREEN.next())
    }

    @Test
    fun sceneRequestRoundtripOrientationAndFreshness() {
        val original = WorkspaceSceneRequest(
            taskId = 42,
            orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            timestampMs = 10_000L,
            canHold = true,
            fullscreen = true,
        )
        val encoded = original.encode()
        assertEquals("42,6,10000,1,1", encoded)
        assertEquals(original, WorkspaceSceneRequest.decode(encoded))

        assertNull(WorkspaceSceneRequest.decode(null))
        assertNull(WorkspaceSceneRequest.decode("0,0,100,1,0"))
        assertNull(WorkspaceSceneRequest.decode("42,0,100"))

        assertTrue(original.isLandscapeOrientation)
        assertFalse(original.isPortraitOrientation)
        assertTrue(WorkspaceSceneRequest.isLandscapeRequest(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE))
        assertTrue(WorkspaceSceneRequest.isLandscapeRequest(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE))
        assertTrue(WorkspaceSceneRequest.isLandscapeRequest(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE))
        assertTrue(WorkspaceSceneRequest.isPortraitRequest(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT))
        assertTrue(WorkspaceSceneRequest.isPortraitRequest(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT))
        assertFalse(WorkspaceSceneRequest.isLandscapeRequest(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED))

        assertTrue(original.isFresh(nowMs = 12_500L))
        assertFalse(original.isFresh(nowMs = 13_001L))
        assertTrue(original.canHoldRotation(nowMs = 11_000L))
        assertFalse(original.canHoldRotation(nowMs = 11_501L))
    }

    @Test
    fun rotatedBackRegionCodecAndHitTest() {
        val region = RotatedBackRegion(120, 300, 960, 2100)
        assertEquals("120,300,960,2100", region.encode())
        assertEquals(region, RotatedBackRegion.decode("120,300,960,2100"))
        assertNull(RotatedBackRegion.decode("500,100,500,900"))
        assertNull(RotatedBackRegion.decode("100,900,500,200"))

        assertTrue(region.contains(120f, 300f))
        assertTrue(region.contains(500f, 1200f))
        assertFalse(region.contains(119f, 1200f))
        assertFalse(region.contains(500f, 2101f))
    }

    @Test
    fun sensorDegreeHysteresisAndInitialOrientation() {
        assertEquals(
            90,
            RotatedPaneTransformPolicy.initialDegreeForOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),
        )
        assertEquals(
            -90,
            RotatedPaneTransformPolicy.initialDegreeForOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE),
        )

        assertEquals(90, RotatedPaneTransformPolicy.resolveSensorDegree(-1, 90))
        assertEquals(90, RotatedPaneTransformPolicy.resolveSensorDegree(270, -90))
        assertEquals(-90, RotatedPaneTransformPolicy.resolveSensorDegree(90, 90))
        // Hysteresis zone stays on previous degree instead of flapping
        assertEquals(90, RotatedPaneTransformPolicy.resolveSensorDegree(180, 90))
        assertEquals(-90, RotatedPaneTransformPolicy.resolveSensorDegree(180, -90))
    }

    @Test
    fun rotatedTouchMappingIn90AndMinus90() {
        // Host pane is 800x2000 (portrait), virtual display buffer is 2000x800 (landscape).
        val (vxTopLeft90, vyTopLeft90) = RotatedPaneTransformPolicy.mapLocalTouchToVirtualDisplay(
            localX = 0f,
            localY = 0f,
            hostWidth = 800,
            hostHeight = 2000,
            displayWidth = 2000,
            displayHeight = 800,
            degree = 90,
        )
        assertEquals(0f, vxTopLeft90, 0.5f)
        assertEquals(799f, vyTopLeft90, 1.5f)

        val (vxCenter90, vyCenter90) = RotatedPaneTransformPolicy.mapLocalTouchToVirtualDisplay(
            localX = 400f,
            localY = 1000f,
            hostWidth = 800,
            hostHeight = 2000,
            displayWidth = 2000,
            displayHeight = 800,
            degree = 90,
        )
        assertEquals(1000f, vxCenter90, 0.5f)
        assertEquals(400f, vyCenter90, 0.5f)

        val (vxTopLeftNeg90, vyTopLeftNeg90) = RotatedPaneTransformPolicy.mapLocalTouchToVirtualDisplay(
            localX = 0f,
            localY = 0f,
            hostWidth = 800,
            hostHeight = 2000,
            displayWidth = 2000,
            displayHeight = 800,
            degree = -90,
        )
        assertEquals(1999f, vxTopLeftNeg90, 1.5f)
        assertEquals(0f, vyTopLeftNeg90, 0.5f)
    }

    @Test
    fun edgeBackDetectionDistinguishesInwardSwipeFromScroll() {
        val hostW = 800
        val slop = 60f
        val trigger = 120f

        assertTrue(
            RotatedPaneTransformPolicy.shouldTriggerEdgeBack(
                startX = 20f,
                startY = 500f,
                currentX = 160f,
                currentY = 515f,
                hostWidth = hostW,
                edgeSlopPx = slop,
                triggerDistancePx = trigger,
            ),
        )
        assertTrue(
            RotatedPaneTransformPolicy.shouldTriggerEdgeBack(
                startX = 780f,
                startY = 500f,
                currentX = 630f,
                currentY = 510f,
                hostWidth = hostW,
                edgeSlopPx = slop,
                triggerDistancePx = trigger,
            ),
        )
        // Mostly vertical drag near edge must not hijack scrolling
        assertFalse(
            RotatedPaneTransformPolicy.shouldTriggerEdgeBack(
                startX = 20f,
                startY = 500f,
                currentX = 150f,
                currentY = 660f,
                hostWidth = hostW,
                edgeSlopPx = slop,
                triggerDistancePx = trigger,
            ),
        )
        // Center touch is not an edge gesture
        assertFalse(
            RotatedPaneTransformPolicy.shouldTriggerEdgeBack(
                startX = 400f,
                startY = 500f,
                currentX = 600f,
                currentY = 500f,
                hostWidth = hostW,
                edgeSlopPx = slop,
                triggerDistancePx = trigger,
            ),
        )
    }

    @Test
    fun observerPolicyAndVirtualDisplayClassification() {
        assertTrue(WorkspaceSceneObserverPolicy.isStatusBarHidden(requestedVisibleTypes = 0b10, windowLayoutFlags = 0))
        assertFalse(WorkspaceSceneObserverPolicy.isStatusBarHidden(requestedVisibleTypes = 0b11, windowLayoutFlags = 0))
        assertTrue(
            WorkspaceSceneObserverPolicy.isStatusBarHidden(
                requestedVisibleTypes = 0b11,
                windowLayoutFlags = WorkspaceSceneObserverPolicy.FLAG_FULLSCREEN,
            ),
        )

        val landscapeReq = WorkspaceSceneRequest(
            taskId = 12,
            orientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            timestampMs = 5_000L,
            canHold = true,
            fullscreen = true,
        )
        assertTrue(
            WorkspaceSceneObserverPolicy.shouldHoldDisplayRotation(
                LandscapeVideoMode.TURNED,
                landscapeReq,
                5_500L,
            ),
        )
        assertTrue(
            WorkspaceSceneObserverPolicy.shouldHoldDisplayRotation(
                LandscapeVideoMode.TOP,
                landscapeReq,
                5_500L,
            ),
        )
        assertFalse(
            WorkspaceSceneObserverPolicy.shouldHoldDisplayRotation(
                LandscapeVideoMode.FULLSCREEN,
                landscapeReq,
                5_500L,
            ),
        )
        assertFalse(
            WorkspaceSceneObserverPolicy.shouldHoldDisplayRotation(
                LandscapeVideoMode.TURNED,
                landscapeReq,
                7_000L,
            ),
        )
        // Only hold when Display 0 is actually portrait and its last orientation is landscape
        assertTrue(
            WorkspaceSceneObserverPolicy.shouldHoldDisplayRotation(
                mode = LandscapeVideoMode.TURNED,
                request = landscapeReq,
                nowMs = 5_500L,
                lastOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                currentRotation = 0,
                portraitRotation = 0,
            ),
        )
        assertFalse(
            WorkspaceSceneObserverPolicy.shouldHoldDisplayRotation(
                mode = LandscapeVideoMode.TURNED,
                request = landscapeReq,
                nowMs = 5_500L,
                lastOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                currentRotation = 0,
                portraitRotation = 0,
            ),
        )
        assertFalse(
            WorkspaceSceneObserverPolicy.shouldHoldDisplayRotation(
                mode = LandscapeVideoMode.TURNED,
                request = landscapeReq,
                nowMs = 5_500L,
                lastOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                currentRotation = 1,
                portraitRotation = 0,
            ),
        )

        assertTrue(WorkspaceVirtualDisplayNames.isSlotDisplay("NeXtep-slot-0"))
        assertFalse(WorkspaceVirtualDisplayNames.isSlotDisplay(WorkspaceVirtualDisplayNames.ROTATED_MAIN))
        assertTrue(WorkspaceVirtualDisplayNames.isRotatedMainDisplay("NeXtep-rotated-main"))
        assertTrue(WorkspaceVirtualDisplayNames.isTopPinnedDisplay("NeXtep-top-pinned"))
        assertTrue(WorkspaceVirtualDisplayNames.isSceneObservedVirtualDisplay("NeXtep-rotated-main"))
        assertTrue(WorkspaceVirtualDisplayNames.isSceneObservedVirtualDisplay("NeXtep-top-pinned"))
        assertFalse(WorkspaceVirtualDisplayNames.isSceneObservedVirtualDisplay("NeXtep-slot-0"))
        assertTrue(WorkspaceVirtualDisplayNames.isWorkspaceVirtualDisplay("NeXtep-slot-3"))
        assertTrue(WorkspaceVirtualDisplayNames.isWorkspaceVirtualDisplay("NeXtep-rotated-main"))
        assertTrue(WorkspaceVirtualDisplayNames.isWorkspaceVirtualDisplay("NeXtep-top-pinned"))
        assertFalse(WorkspaceVirtualDisplayNames.isWorkspaceVirtualDisplay("Built-in Screen"))
    }

    @Test
    fun topPinnedVirtualDisplaySizeTouchMappingAndEdgeBack() {
        val size = TopPinnedTransformPolicy.computeVirtualDisplaySize(
            zoneWidthPx = 1440,
            zoneHeightPx = 664,
            targetShortSidePx = 1078,
        )
        assertEquals(0, size.width % 2)
        assertEquals(0, size.height % 2)
        assertEquals(1078, size.height)
        assertTrue(size.width > size.height)
        val zoneRatio = 1440f / 664f
        val vdRatio = size.width.toFloat() / size.height.toFloat()
        assertTrue(kotlin.math.abs(zoneRatio - vdRatio) < 0.01f)

        val clampedWide = TopPinnedTransformPolicy.computeVirtualDisplaySize(
            zoneWidthPx = 3000,
            zoneHeightPx = 600,
            targetShortSidePx = 1200,
        )
        assertEquals(4096, clampedWide.width)
        assertEquals(0, clampedWide.height % 2)
        assertTrue(clampedWide.height in TopPinnedTransformPolicy.MIN_VIRTUAL_DISPLAY_SIDE_PX..TopPinnedTransformPolicy.MAX_VIRTUAL_DISPLAY_SIDE_PX)

        val (centerX, centerY) = TopPinnedTransformPolicy.mapLocalTouchToVirtualDisplay(
            localX = 720f,
            localY = 332f,
            hostWidth = 1440,
            hostHeight = 664,
            displayWidth = size.width,
            displayHeight = size.height,
        )
        assertEquals(size.width / 2f, centerX, 1f)
        assertEquals(size.height / 2f, centerY, 1f)

        val (clampedX, clampedY) = TopPinnedTransformPolicy.mapLocalTouchToVirtualDisplay(
            localX = 9999f,
            localY = -50f,
            hostWidth = 1440,
            hostHeight = 664,
            displayWidth = size.width,
            displayHeight = size.height,
        )
        assertEquals((size.width - 1).toFloat(), clampedX, 0.01f)
        assertEquals(0f, clampedY, 0.01f)

        assertTrue(TopPinnedTransformPolicy.isEdgeDown(localX = 20f, hostWidth = 1440, edgeSlopPx = 84f))
        assertTrue(TopPinnedTransformPolicy.isEdgeDown(localX = 1420f, hostWidth = 1440, edgeSlopPx = 84f))
        assertFalse(TopPinnedTransformPolicy.isEdgeDown(localX = 400f, hostWidth = 1440, edgeSlopPx = 84f))
        assertTrue(
            TopPinnedTransformPolicy.shouldTriggerEdgeBack(
                startX = 20f,
                startY = 300f,
                currentX = 210f,
                currentY = 310f,
                hostWidth = 1440,
                edgeSlopPx = 84f,
                triggerDistancePx = 168f,
            ),
        )
    }
}
