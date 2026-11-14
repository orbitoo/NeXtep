package com.elbert.nextep.systemui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperOsShadeTransformPolicyTest {
    @Test
    fun unlockedExpandedShadeOrControlCenterScalesToWorkspace() {
        assertTrue(
            SystemUiShadeTransformPolicy.resolveHyperOsShadeExpanded(
                keyguardShowing = false,
                bouncerShowing = false,
                dozing = false,
                shadeOrQsExpanded = true,
                qsExpanded = false,
                controlCenterExpanded = false,
                panelVisible = false,
            ),
        )
        assertTrue(
            SystemUiShadeTransformPolicy.resolveHyperOsShadeExpanded(
                keyguardShowing = false,
                bouncerShowing = false,
                dozing = false,
                shadeOrQsExpanded = false,
                qsExpanded = false,
                controlCenterExpanded = true,
                panelVisible = false,
            ),
        )
        assertTrue(
            SystemUiShadeTransformPolicy.resolveHyperOsShadeExpanded(
                keyguardShowing = false,
                bouncerShowing = false,
                dozing = false,
                shadeOrQsExpanded = false,
                qsExpanded = true,
                controlCenterExpanded = false,
                panelVisible = true,
            ),
        )
    }

    @Test
    fun lockscreenBouncerDozingAndCollapsedStayFullScreen() {
        assertFalse(
            SystemUiShadeTransformPolicy.resolveHyperOsShadeExpanded(
                keyguardShowing = true,
                bouncerShowing = false,
                dozing = false,
                shadeOrQsExpanded = true,
                qsExpanded = true,
                controlCenterExpanded = true,
                panelVisible = true,
            ),
        )
        assertFalse(
            SystemUiShadeTransformPolicy.resolveHyperOsShadeExpanded(
                keyguardShowing = false,
                bouncerShowing = true,
                dozing = false,
                shadeOrQsExpanded = true,
                qsExpanded = false,
                controlCenterExpanded = true,
                panelVisible = true,
            ),
        )
        assertFalse(
            SystemUiShadeTransformPolicy.resolveHyperOsShadeExpanded(
                keyguardShowing = false,
                bouncerShowing = false,
                dozing = true,
                shadeOrQsExpanded = false,
                qsExpanded = false,
                controlCenterExpanded = false,
                panelVisible = true,
            ),
        )
        assertFalse(
            SystemUiShadeTransformPolicy.resolveHyperOsShadeExpanded(
                keyguardShowing = false,
                bouncerShowing = false,
                dozing = false,
                shadeOrQsExpanded = false,
                qsExpanded = false,
                controlCenterExpanded = false,
                panelVisible = false,
            ),
        )
    }

    @Test
    fun surfaceCropRectRejectsEmptyOrInvertedPaneBounds() {
        assertNull(SystemUiShadeTransformPolicy.resolveShadeSurfaceCropRect(100, 100, 100, 500))
        assertNull(SystemUiShadeTransformPolicy.resolveShadeSurfaceCropRect(200, 100, 150, 500))
        assertNull(SystemUiShadeTransformPolicy.resolveShadeSurfaceCropRect(100, 500, 800, 500))
        assertNull(SystemUiShadeTransformPolicy.resolveShadeSurfaceCropRect(100, 600, 800, 500))
        assertNotNull(SystemUiShadeTransformPolicy.resolveShadeSurfaceCropRect(240, 180, 1440, 3000))
    }

    @Test
    fun inverseMapRawCoordinateRestoresLocalSpaceAndHandlesDegenerateScale() {
        val mapped = SystemUiShadeTransformPolicy.inverseMapRawCoordinate(
            value = 390f,
            scale = 0.75f,
            translation = 240f,
        )
        assertEquals(200f, mapped, 0.001f)

        val identity = SystemUiShadeTransformPolicy.inverseMapRawCoordinate(
            value = 512.5f,
            scale = 1f,
            translation = 0f,
        )
        assertEquals(512.5f, identity, 0.001f)

        val zeroScale = SystemUiShadeTransformPolicy.inverseMapRawCoordinate(
            value = 512.5f,
            scale = 0f,
            translation = 240f,
        )
        assertEquals(512.5f, zeroScale, 0.001f)

        val nanInput = SystemUiShadeTransformPolicy.inverseMapRawCoordinate(
            value = Float.NaN,
            scale = 0.75f,
            translation = 240f,
        )
        assertTrue(nanInput.isNaN())
    }

    @Test
    fun inverseMapScreenRectRestoresUnscaledBoundsAndGuardsInvalidScale() {
        val unscaled = SystemUiShadeTransformPolicy.inverseMapScreenRect(
            left = 440,
            top = 800,
            right = 1240,
            bottom = 1600,
            scaleX = 0.8f,
            scaleY = 0.8f,
            translationX = 200f,
            translationY = 640f,
        )
        assertArrayEquals(intArrayOf(300, 200, 1300, 1200), unscaled)

        val invalidScale = SystemUiShadeTransformPolicy.inverseMapScreenRect(
            left = 440,
            top = 800,
            right = 1240,
            bottom = 1600,
            scaleX = -0.5f,
            scaleY = 0f,
            translationX = 200f,
            translationY = 640f,
        )
        assertArrayEquals(intArrayOf(440, 800, 1240, 1600), invalidScale)
    }
}
