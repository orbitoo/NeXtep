package com.elbert.nextep.framework

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskSurfaceObservedStateTest {
    @Test
    fun freshObservedStateReusesRecentSnapshotUntilMaxAgeOrForget() {
        val taskId = 987_654
        val state = TaskInfoCompat.WindowState(
            displayId = 0,
            bounds = Rect(0, 0, 1440, 3200),
            windowingMode = 1,
            resizeMode = 2,
            densityDpi = 560,
            token = null,
        )
        try {
            val beforeNs = System.nanoTime()
            TaskSurfaceCompat.observeTaskState(taskId, state)
            val afterNs = System.nanoTime()

            assertEquals(state, TaskSurfaceCompat.freshObservedState(taskId, nowNs = afterNs))
            assertNull(
                TaskSurfaceCompat.freshObservedState(
                    taskId,
                    nowNs = beforeNs + TaskSurfaceCompat.OBSERVED_STATE_FRESH_NS + 10_000_000L,
                ),
            )

            TaskSurfaceCompat.forgetTaskSurface(taskId)
            assertNull(TaskSurfaceCompat.freshObservedState(taskId, nowNs = afterNs))
        } finally {
            TaskSurfaceCompat.forgetTaskSurface(taskId)
        }
    }
}
