package com.elbert.nextep.framework

import com.elbert.nextep.systemserver.HyperOsImeHooks
import com.elbert.nextep.trigger.TriggerBroadcastContract
import com.elbert.nextep.workspace.SidebarSide
import com.elbert.nextep.workspace.WorkspaceGeometry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperOsWorkspaceCompatTest {
    @After
    fun tearDown() {
        HyperOsFreeformCompat.clear()
    }

    @Test
    fun trackedFreeformTaskIdsRoundTripAndFilterVendorWindows() {
        assertFalse(HyperOsFreeformCompat.isVendorWindowedOrFreeform(1, 42))
        assertTrue(HyperOsFreeformCompat.trackFreeformTask(42))
        assertTrue(HyperOsFreeformCompat.isTrackedFreeformTask(42))
        assertTrue(HyperOsFreeformCompat.isVendorWindowedOrFreeform(1, 42))
        assertEquals("42", HyperOsFreeformCompat.encodeTrackedTaskIds())

        HyperOsFreeformCompat.applyEncodedTrackedTaskIds("108, 42, -1")
        assertTrue(HyperOsFreeformCompat.isTrackedFreeformTask(42))
        assertTrue(HyperOsFreeformCompat.isTrackedFreeformTask(108))
        assertFalse(HyperOsFreeformCompat.isTrackedFreeformTask(999))
        assertEquals("42,108", HyperOsFreeformCompat.encodeTrackedTaskIds())

        assertTrue(HyperOsFreeformCompat.untrackFreeformTask(42))
        assertFalse(HyperOsFreeformCompat.isTrackedFreeformTask(42))
        assertTrue(HyperOsFreeformCompat.isVendorWindowedOrFreeform(5, 7))
        assertTrue(HyperOsFreeformCompat.isVendorWindowedOrFreeform(2, 7))
    }

    @Test
    fun claimedChromePointMatchesControlAndSidebarEdgesInPortraitAndLandscape() {
        val portraitRight = WorkspaceGeometry(
            screenWidth = 1080,
            screenHeight = 2400,
            topHeight = 500,
            sidebarWidth = 280,
            sidebarSide = SidebarSide.RIGHT,
        )
        assertTrue(portraitRight.isPointInClaimedChrome(500f, 100f))
        assertTrue(portraitRight.isPointInClaimedChrome(950f, 1200f))
        assertFalse(portraitRight.isPointInClaimedChrome(12f, 1200f))
        assertFalse(portraitRight.isPointInClaimedChrome(400f, 1200f))

        val portraitLeft = portraitRight.copy(sidebarSide = SidebarSide.LEFT)
        assertTrue(portraitLeft.isPointInClaimedChrome(12f, 1200f))
        assertFalse(portraitLeft.isPointInClaimedChrome(1070f, 1200f))

        val landscape = WorkspaceGeometry(
            screenWidth = 2400,
            screenHeight = 1080,
            topHeight = 500,
            sidebarWidth = 280,
            sidebarSide = SidebarSide.RIGHT,
            displayRotation = 1,
        )
        assertTrue(landscape.isPointInClaimedChrome(100f, 500f))
        assertTrue(landscape.isPointInClaimedChrome(1200f, 100f))
        assertFalse(landscape.isPointInClaimedChrome(1200f, 600f))
    }

    @Test
    fun imeBottomCompensationUnscalesPaneCoordinates() {
        val geometry = WorkspaceGeometry(
            screenWidth = 1080,
            screenHeight = 2400,
            topHeight = 500,
            sidebarWidth = 280,
            sidebarSide = SidebarSide.RIGHT,
        )
        val compensated = HyperOsImeHooks.computeCompensatedImeBottom(1600, geometry)
        assertTrue(compensated in 0 until geometry.screenHeight)
        assertEquals(2400, HyperOsImeHooks.computeCompensatedImeBottom(2400, geometry))
    }

    @Test
    fun assistantScreenPackagesIncludeMiuiPersonalAssistant() {
        assertTrue(TriggerBroadcastContract.ASSISTANT_SCREEN_PACKAGE in TriggerBroadcastContract.ASSISTANT_SCREEN_PACKAGES)
        assertTrue(TriggerBroadcastContract.MIUI_ASSISTANT_SCREEN_PACKAGE in TriggerBroadcastContract.ASSISTANT_SCREEN_PACKAGES)
    }
}
