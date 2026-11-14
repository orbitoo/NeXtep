package com.elbert.nextep.display

import com.elbert.nextep.config.WorkspaceConfigContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotHibernateIdlePolicyTest {
    @Test fun disabledAutoHibernateNeverTriggers() {
        assertEquals(
            SlotHibernateIdlePolicy.IdleDecision.Disabled,
            SlotHibernateIdlePolicy.evaluate(
                enabled = false,
                timeoutSeconds = 30,
                lastActivityUptimeMs = 1_000L,
                nowUptimeMs = 100_000L,
            ),
        )
        assertEquals(
            SlotHibernateIdlePolicy.IdleDecision.Disabled,
            SlotHibernateIdlePolicy.evaluate(
                enabled = true,
                timeoutSeconds = 0,
                lastActivityUptimeMs = 1_000L,
                nowUptimeMs = 100_000L,
            ),
        )
    }

    @Test fun idleDecisionTracksRemainingTimeUntilExactDeadline() {
        assertEquals(
            SlotHibernateIdlePolicy.IdleDecision.Reschedule(1L),
            SlotHibernateIdlePolicy.evaluate(
                enabled = true,
                timeoutSeconds = 30,
                lastActivityUptimeMs = 10_000L,
                nowUptimeMs = 39_999L,
            ),
        )
        assertEquals(
            SlotHibernateIdlePolicy.IdleDecision.HibernateNow,
            SlotHibernateIdlePolicy.evaluate(
                enabled = true,
                timeoutSeconds = 30,
                lastActivityUptimeMs = 10_000L,
                nowUptimeMs = 40_000L,
            ),
        )
    }

    @Test fun clockSkewBeforeLastActivityReschedulesFullTimeout() {
        assertEquals(
            SlotHibernateIdlePolicy.IdleDecision.Reschedule(45_000L),
            SlotHibernateIdlePolicy.evaluate(
                enabled = true,
                timeoutSeconds = 45,
                lastActivityUptimeMs = 50_000L,
                nowUptimeMs = 49_000L,
            ),
        )
    }

    @Test fun orthogonalLockAndHibernationKeepHibernatedSlotsDisconnectedOnUnlockAndWakeWarmup() {
        assertTrue(SlotHibernateIdlePolicy.shouldConnectOutput(suspended = false, hibernated = false))
        assertFalse(SlotHibernateIdlePolicy.shouldConnectOutput(suspended = true, hibernated = false))
        assertFalse(SlotHibernateIdlePolicy.shouldConnectOutput(suspended = false, hibernated = true))
        assertFalse(SlotHibernateIdlePolicy.shouldConnectOutput(suspended = true, hibernated = true))

        assertTrue(
            SlotHibernateIdlePolicy.shouldWarmSuspendedOutput(
                active = true,
                suspended = true,
                hibernated = false,
            ),
        )
        assertFalse(
            SlotHibernateIdlePolicy.shouldWarmSuspendedOutput(
                active = true,
                suspended = true,
                hibernated = true,
            ),
        )
    }

    @Test fun defaultAndNormalizedAutoHibernateTimeoutsMatchContract() {
        assertTrue(WorkspaceConfigContract.DEFAULT_SLOT_AUTO_HIBERNATE_ENABLED)
        assertEquals(30, WorkspaceConfigContract.DEFAULT_SLOT_AUTO_HIBERNATE_TIMEOUT_SECONDS)
        assertEquals(15, WorkspaceConfigContract.normalizeSlotAutoHibernateTimeoutSeconds(5))
        assertEquals(30, WorkspaceConfigContract.normalizeSlotAutoHibernateTimeoutSeconds(30))
        assertEquals(45, WorkspaceConfigContract.normalizeSlotAutoHibernateTimeoutSeconds(40))
        assertEquals(120, WorkspaceConfigContract.normalizeSlotAutoHibernateTimeoutSeconds(200))
    }
}
