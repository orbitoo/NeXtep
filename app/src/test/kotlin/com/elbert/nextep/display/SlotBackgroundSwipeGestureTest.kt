package com.elbert.nextep.display

import com.elbert.nextep.workspace.SidebarSide
import org.junit.Assert.assertEquals
import org.junit.Test

class SlotBackgroundSwipeGestureTest {
    @Test fun rightSidebarOutwardDismissesAndInwardTogglesHibernation() {
        assertEquals(
            SlotBackgroundSwipeGesture.HorizontalSwipeOutcome.OUTWARD_DISMISS,
            SlotBackgroundSwipeGesture.classifyRelease(
                side = SidebarSide.RIGHT,
                outwardEnabled = true,
                inwardEnabled = false,
                dx = 90f,
                dy = 0f,
                velocityX = 0f,
                viewWidth = 200,
                touchSlop = 8f,
                minimumFlingVelocity = 1200f,
            ),
        )
        assertEquals(
            SlotBackgroundSwipeGesture.HorizontalSwipeOutcome.INWARD_HIBERNATE,
            SlotBackgroundSwipeGesture.classifyRelease(
                side = SidebarSide.RIGHT,
                outwardEnabled = false,
                inwardEnabled = true,
                dx = -90f,
                dy = 0f,
                velocityX = 0f,
                viewWidth = 200,
                touchSlop = 8f,
                minimumFlingVelocity = 1200f,
            ),
        )
    }

    @Test fun leftSidebarMirrorsOutwardAndInwardDirections() {
        assertEquals(
            SlotBackgroundSwipeGesture.HorizontalSwipeOutcome.OUTWARD_DISMISS,
            SlotBackgroundSwipeGesture.classifyRelease(
                side = SidebarSide.LEFT,
                outwardEnabled = true,
                inwardEnabled = false,
                dx = -90f,
                dy = 5f,
                velocityX = 0f,
                viewWidth = 200,
                touchSlop = 8f,
                minimumFlingVelocity = 1200f,
            ),
        )
        assertEquals(
            SlotBackgroundSwipeGesture.HorizontalSwipeOutcome.INWARD_HIBERNATE,
            SlotBackgroundSwipeGesture.classifyRelease(
                side = SidebarSide.LEFT,
                outwardEnabled = false,
                inwardEnabled = true,
                dx = 90f,
                dy = -5f,
                velocityX = 0f,
                viewWidth = 200,
                touchSlop = 8f,
                minimumFlingVelocity = 1200f,
            ),
        )
    }

    @Test fun inwardFlingAndDisabledOutwardStillRespectThresholds() {
        assertEquals(
            SlotBackgroundSwipeGesture.HorizontalSwipeOutcome.INWARD_HIBERNATE,
            SlotBackgroundSwipeGesture.classifyRelease(
                side = SidebarSide.RIGHT,
                outwardEnabled = false,
                inwardEnabled = true,
                dx = -35f,
                dy = 0f,
                velocityX = -1500f,
                viewWidth = 200,
                touchSlop = 8f,
                minimumFlingVelocity = 1200f,
            ),
        )
        assertEquals(
            SlotBackgroundSwipeGesture.HorizontalSwipeOutcome.NONE,
            SlotBackgroundSwipeGesture.classifyRelease(
                side = SidebarSide.RIGHT,
                outwardEnabled = false,
                inwardEnabled = false,
                dx = 120f,
                dy = 0f,
                velocityX = 2000f,
                viewWidth = 200,
                touchSlop = 8f,
                minimumFlingVelocity = 1200f,
            ),
        )
        assertEquals(
            SlotBackgroundSwipeGesture.HorizontalSwipeOutcome.NONE,
            SlotBackgroundSwipeGesture.classifyRelease(
                side = SidebarSide.RIGHT,
                outwardEnabled = false,
                inwardEnabled = true,
                dx = -90f,
                dy = 80f,
                velocityX = 0f,
                viewWidth = 200,
                touchSlop = 8f,
                minimumFlingVelocity = 1200f,
            ),
        )
    }
}
