package com.elbert.nextep.systemui

import android.view.MotionEvent
import com.elbert.nextep.trigger.TriggerGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerGesturesTest {
    @Test
    fun triggerGestureDefaultsReplaceStatusBarSwipeAndRoundTripCanonically() {
        assertEquals(setOf(TriggerGesture.BOTTOM_LEFT, TriggerGesture.BOTTOM_RIGHT), TriggerGesture.DEFAULT_SET)
        assertFalse(TriggerGesture.STATUS_BAR_SWIPE in TriggerGesture.DEFAULT_SET)

        val encoded = TriggerGesture.encode(
            setOf(TriggerGesture.STATUS_BAR_SWIPE, TriggerGesture.BOTTOM_LEFT, TriggerGesture.TWO_FINGER),
        )
        assertEquals("bottom_left,two_finger,status_bar_swipe", encoded)
        assertEquals(
            setOf(TriggerGesture.BOTTOM_LEFT, TriggerGesture.TWO_FINGER, TriggerGesture.STATUS_BAR_SWIPE),
            TriggerGesture.decode(encoded),
        )
        assertEquals(TriggerGesture.DEFAULT_SET, TriggerGesture.decode(null))
        assertTrue(TriggerGesture.decode("").isEmpty())
    }

    @Test
    fun bottomCornerDetectorRecognizesDiagonalInwardSwipesAndIgnoresStraightHomeSwipe() {
        val density = 3f
        val width = 1080
        val height = 2400
        val detector = BottomCornerGestureDetector(density)

        assertNull(
            detector.observe(
                actionMasked = MotionEvent.ACTION_DOWN,
                rawX = 24f,
                rawY = height - 20f,
                pointerCount = 1,
                eventTimeMs = 100L,
                screenWidth = width,
                screenHeight = height,
                displayRotation = 0,
                leftEnabled = true,
                rightEnabled = true,
            ),
        )
        assertEquals(BottomCornerGestureDetector.Phase.OBSERVING, detector.phase())

        val matchedLeft = detector.observe(
            actionMasked = MotionEvent.ACTION_MOVE,
            rawX = 24f + 30f * density,
            rawY = height - 20f - 36f * density,
            pointerCount = 1,
            eventTimeMs = 180L,
            screenWidth = width,
            screenHeight = height,
            displayRotation = 0,
            leftEnabled = true,
            rightEnabled = true,
        )
        assertEquals(TriggerGesture.BOTTOM_LEFT, matchedLeft)
        assertEquals(BottomCornerGestureDetector.Phase.CONFIRMED, detector.phase())

        // Right corner diagonal swipe
        detector.reset()
        assertNull(
            detector.observe(
                actionMasked = MotionEvent.ACTION_DOWN,
                rawX = width - 24f,
                rawY = height - 20f,
                pointerCount = 1,
                eventTimeMs = 300L,
                screenWidth = width,
                screenHeight = height,
                displayRotation = 0,
                leftEnabled = false,
                rightEnabled = true,
            ),
        )
        val matchedRight = detector.observe(
            actionMasked = MotionEvent.ACTION_MOVE,
            rawX = width - 24f - 32f * density,
            rawY = height - 20f - 34f * density,
            pointerCount = 1,
            eventTimeMs = 370L,
            screenWidth = width,
            screenHeight = height,
            displayRotation = 0,
            leftEnabled = false,
            rightEnabled = true,
        )
        assertEquals(TriggerGesture.BOTTOM_RIGHT, matchedRight)

        // Straight upward HyperOS home swipe near bottom-left should not confirm
        detector.reset()
        assertNull(
            detector.observe(
                actionMasked = MotionEvent.ACTION_DOWN,
                rawX = 24f,
                rawY = height - 20f,
                pointerCount = 1,
                eventTimeMs = 500L,
                screenWidth = width,
                screenHeight = height,
                displayRotation = 0,
                leftEnabled = true,
                rightEnabled = true,
            ),
        )
        assertNull(
            detector.observe(
                actionMasked = MotionEvent.ACTION_MOVE,
                rawX = 28f,
                rawY = height - 20f - 90f * density,
                pointerCount = 1,
                eventTimeMs = 600L,
                screenWidth = width,
                screenHeight = height,
                displayRotation = 0,
                leftEnabled = true,
                rightEnabled = true,
            ),
        )
    }

    @Test
    fun twoFingerSwipeDetectorRecognizesVerticalSwipesAndIgnoresStatusBandAndPinch() {
        val density = 3f
        val width = 1080
        val height = 2400
        val detector = TwoFingerSwipeGestureDetector(density, statusBarHeightPx = 120f)

        // Two-finger swipe up in middle safe band
        assertNull(detector.observe(MotionEvent.ACTION_DOWN, 360f, 1300f, Float.NaN, Float.NaN, 1, 100L, width, height))
        assertNull(detector.observe(MotionEvent.ACTION_POINTER_DOWN, 360f, 1300f, 700f, 1320f, 2, 160L, width, height))
        val up = detector.observe(
            MotionEvent.ACTION_MOVE,
            365f, 1300f - 70f * density,
            705f, 1320f - 70f * density,
            2, 240L, width, height,
        )
        assertEquals(TwoFingerSwipeGestureDetector.Direction.UP, up)

        // Touch in top Dynamic Island / status band must be ignored
        assertNull(detector.observe(MotionEvent.ACTION_DOWN, 360f, 80f, Float.NaN, Float.NaN, 1, 400L, width, height))
        assertNull(detector.observe(MotionEvent.ACTION_POINTER_DOWN, 360f, 80f, 700f, 80f, 2, 430L, width, height))
        assertNull(
            detector.observe(
                MotionEvent.ACTION_MOVE,
                360f, 80f + 80f * density,
                700f, 80f + 80f * density,
                2, 500L, width, height,
            ),
        )

        // Pinching fingers together instead of moving in parallel must not trigger
        assertNull(detector.observe(MotionEvent.ACTION_DOWN, 200f, 1200f, Float.NaN, Float.NaN, 1, 700L, width, height))
        assertNull(detector.observe(MotionEvent.ACTION_POINTER_DOWN, 200f, 1200f, 900f, 1200f, 2, 740L, width, height))
        assertNull(
            detector.observe(
                MotionEvent.ACTION_MOVE,
                420f, 1200f + 65f * density,
                680f, 1200f + 65f * density,
                2, 820L, width, height,
            ),
        )
    }
}
