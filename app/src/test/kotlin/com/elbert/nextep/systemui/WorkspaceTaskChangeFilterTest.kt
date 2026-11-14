package com.elbert.nextep.systemui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTaskChangeFilterTest {
    private fun signature(
        displayId: Int = 0,
        boundsLeft: Int = 0,
        boundsTop: Int = 0,
        boundsRight: Int = 1440,
        boundsBottom: Int = 3200,
        windowingMode: Int = 1,
        resizeMode: Int? = 2,
        densityDpi: Int = 560,
        tokenIdentity: Int = 101,
        vendorWindowed: Boolean = false,
        isVisible: Boolean = true,
        isFocused: Boolean = true,
        activityType: Int = 1,
        topComponent: String? = "dev.t1m3.qplayer/.MainActivity",
    ) = WorkspaceTaskChangeFilter.TaskSignature(
        displayId = displayId,
        boundsLeft = boundsLeft,
        boundsTop = boundsTop,
        boundsRight = boundsRight,
        boundsBottom = boundsBottom,
        windowingMode = windowingMode,
        resizeMode = resizeMode,
        densityDpi = densityDpi,
        tokenIdentity = tokenIdentity,
        vendorWindowed = vendorWindowed,
        isVisible = isVisible,
        isFocused = isFocused,
        activityType = activityType,
        topComponent = topComponent,
    )

    @Test
    fun `non structural onTaskInfoChanged updates are suppressed`() {
        val filter = WorkspaceTaskChangeFilter()
        val initial = signature()

        assertTrue(filter.onTaskAppeared(42, initial))
        assertFalse(filter.onTaskInfoChanged(42, initial.copy()))
        assertFalse(filter.onTaskInfoChanged(42, initial.copy()))
    }

    @Test
    fun `first observation and unreadable signature fail open`() {
        val filter = WorkspaceTaskChangeFilter()
        val initial = signature()

        assertTrue(filter.onTaskInfoChanged(99, null))
        assertTrue(filter.onTaskInfoChanged(99, initial))
        assertFalse(filter.onTaskInfoChanged(99, initial.copy()))
        assertTrue(filter.onTaskInfoChanged(99, null))
    }

    @Test
    fun `structural or focus changes always request refresh`() {
        val filter = WorkspaceTaskChangeFilter()
        val base = signature()
        assertTrue(filter.onTaskAppeared(7, base))

        assertTrue(filter.onTaskInfoChanged(7, base.copy(displayId = 2)))
        assertTrue(filter.onTaskInfoChanged(7, base.copy(displayId = 2, boundsRight = 720)))
        assertTrue(filter.onTaskInfoChanged(7, base.copy(displayId = 2, boundsRight = 720, vendorWindowed = true)))
        assertTrue(filter.onTaskInfoChanged(7, base.copy(displayId = 2, boundsRight = 720, vendorWindowed = true, isFocused = false)))
        assertTrue(filter.onTaskInfoChanged(7, base.copy(displayId = 2, boundsRight = 720, vendorWindowed = true, isFocused = false, topComponent = "tv.danmaku.bili/.MainActivityV2")))
    }

    @Test
    fun `vanish always refreshes and forgets previous signature`() {
        val filter = WorkspaceTaskChangeFilter()
        val base = signature()
        assertTrue(filter.onTaskAppeared(11, base))
        assertFalse(filter.onTaskInfoChanged(11, base.copy()))

        assertTrue(filter.onTaskVanished(11))
        assertEquals("initial", filter.onTaskInfoChangedReason(11, base.copy()))
        assertNull(filter.onTaskInfoChangedReason(11, base.copy()))
        assertEquals("display:0->2,bounds", filter.onTaskInfoChangedReason(11, base.copy(displayId = 2, boundsRight = 720)))
    }
}
