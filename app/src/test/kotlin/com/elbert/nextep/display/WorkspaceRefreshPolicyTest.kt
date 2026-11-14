package com.elbert.nextep.display

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceRefreshPolicyTest {
    @Test fun idleWorkspaceUsesQuietBackoffAfterSettling() {
        assertEquals(WorkspaceRefreshPolicy.QUIET_INTERVAL_MS, WorkspaceRefreshPolicy().delayMs(10_000L))
    }

    @Test fun taskChangeKeepsFastRecoveryForTwoSecondsThenBacksOff() {
        val policy = WorkspaceRefreshPolicy()
        policy.onTaskChanged(10_000L)
        assertEquals(120L, policy.delayMs(10_000L))
        assertEquals(120L, policy.delayMs(11_999L))
        assertEquals(1_000L, policy.delayMs(12_000L))
        assertEquals(1_000L, policy.delayMs(15_999L))
        assertEquals(5_000L, policy.delayMs(16_000L))
    }

    @Test fun laterTaskChangeExtendsSettlingAndQuietWindows() {
        val policy = WorkspaceRefreshPolicy()
        policy.onTaskChanged(10_000L)
        policy.onTaskChanged(11_500L)
        assertEquals(120L, policy.delayMs(13_000L))
        assertEquals(1_000L, policy.delayMs(13_500L))
        assertEquals(1_000L, policy.delayMs(17_499L))
        assertEquals(5_000L, policy.delayMs(17_500L))
    }

    @Test fun pendingLaunchOrOutputRecoveryStaysFastEvenAfterQuietWindow() {
        val policy = WorkspaceRefreshPolicy()
        policy.onTaskChanged(10_000L)
        assertEquals(120L, policy.delayMs(20_000L, pendingRecovery = true))
        assertEquals(5_000L, policy.delayMs(20_000L, pendingRecovery = false))
    }
}
