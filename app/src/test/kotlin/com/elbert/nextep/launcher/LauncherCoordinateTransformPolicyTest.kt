package com.elbert.nextep.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherCoordinateTransformPolicyTest {
    @Test
    fun inverseMapRestoresDecorLocalPointFromScaledScreenPoint() {
        val point = intArrayOf(580, 1180)
        val mapped = LauncherCoordinateTransformPolicy.inverseMapScreenPointToLocal(
            point = point,
            scaleX = 0.75f,
            scaleY = 0.75f,
            translationX = 100f,
            translationY = 205f,
        )
        assertTrue(mapped)
        assertArrayEquals(intArrayOf(640, 1300), point)
    }

    @Test
    fun inverseMapRejectsIdentityAndInvalidTransforms() {
        val identityPoint = intArrayOf(640, 1300)
        assertFalse(
            LauncherCoordinateTransformPolicy.inverseMapScreenPointToLocal(
                point = identityPoint,
                scaleX = 1f,
                scaleY = 1f,
                translationX = 0f,
                translationY = 0f,
            ),
        )
        assertArrayEquals(intArrayOf(640, 1300), identityPoint)

        assertFalse(
            LauncherCoordinateTransformPolicy.inverseMapScreenPointToLocal(
                point = identityPoint,
                scaleX = 0f,
                scaleY = 1f,
                translationX = 100f,
                translationY = 200f,
            ),
        )
        assertFalse(
            LauncherCoordinateTransformPolicy.inverseMapScreenPointToLocal(
                point = intArrayOf(100),
                scaleX = 0.8f,
                scaleY = 0.8f,
                translationX = 50f,
                translationY = 50f,
            ),
        )
    }

    @Test
    fun canInverseMapRequiresActiveNonPopupDecorRootWithZeroPivotAndNonIdentityTransform() {
        assertTrue(
            LauncherCoordinateTransformPolicy.canInverseMapForRoot(
                workspaceActive = true,
                rootClassName = "com.android.internal.policy.DecorView",
                pivotX = 0f,
                pivotY = 0f,
                scaleX = 0.8f,
                scaleY = 0.8f,
                translationX = 200f,
                translationY = 160f,
            ),
        )
        assertFalse(
            LauncherCoordinateTransformPolicy.canInverseMapForRoot(
                workspaceActive = false,
                rootClassName = "com.android.internal.policy.DecorView",
                pivotX = 0f,
                pivotY = 0f,
                scaleX = 0.8f,
                scaleY = 0.8f,
                translationX = 200f,
                translationY = 160f,
            ),
        )
        assertFalse(
            LauncherCoordinateTransformPolicy.canInverseMapForRoot(
                workspaceActive = true,
                rootClassName = "android.widget.PopupWindow\$PopupDecorView",
                pivotX = 0f,
                pivotY = 0f,
                scaleX = 0.8f,
                scaleY = 0.8f,
                translationX = 200f,
                translationY = 160f,
            ),
        )
        assertFalse(
            LauncherCoordinateTransformPolicy.canInverseMapForRoot(
                workspaceActive = true,
                rootClassName = "com.android.internal.policy.DecorView",
                pivotX = 540f,
                pivotY = 0f,
                scaleX = 0.8f,
                scaleY = 0.8f,
                translationX = 200f,
                translationY = 160f,
            ),
        )
        assertFalse(
            LauncherCoordinateTransformPolicy.canInverseMapForRoot(
                workspaceActive = true,
                rootClassName = "com.android.internal.policy.DecorView",
                pivotX = 0f,
                pivotY = 0f,
                scaleX = 1f,
                scaleY = 1f,
                translationX = 0f,
                translationY = 0f,
            ),
        )
    }
}
