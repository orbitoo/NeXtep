package com.elbert.nextep.systemui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspacePowerPolicyTest {
    @Test fun eventDrivenLockAndDisplayObserversDisablePeriodicPolling() {
        assertNull(
            WorkspaceLockObservationPolicy.lockPollingIntervalMs(
                workspaceActive = true,
                screenReady = true,
                keyguardObserverRegistered = true,
                displayObserverRegistered = true,
            ),
        )
    }

    @Test fun screenOffNeverPollsEvenWhenSystemListenersUnavailable() {
        assertNull(
            WorkspaceLockObservationPolicy.lockPollingIntervalMs(
                workspaceActive = true,
                screenReady = false,
                keyguardObserverRegistered = false,
                displayObserverRegistered = false,
            ),
        )
    }

    @Test fun missingSystemListenerRetainsFallbackPollingOnlyWhenScreenAwake() {
        assertEquals(
            500L,
            WorkspaceLockObservationPolicy.lockPollingIntervalMs(
                workspaceActive = true,
                screenReady = true,
                keyguardObserverRegistered = false,
                displayObserverRegistered = true,
            ),
        )
        assertNull(
            WorkspaceLockObservationPolicy.lockPollingIntervalMs(
                workspaceActive = false,
                screenReady = true,
                keyguardObserverRegistered = false,
                displayObserverRegistered = false,
            ),
        )
    }

    @Test fun unlockRetryStopsWhileLockedWithKeyguardListenerAndCapsDisplayWait() {
        assertNull(
            WorkspaceLockObservationPolicy.unlockRetryDelayMs(
                locked = true,
                screenReady = true,
                keyguardObserverRegistered = true,
                retryCount = 0,
            ),
        )
        assertEquals(
            500L,
            WorkspaceLockObservationPolicy.unlockRetryDelayMs(
                locked = true,
                screenReady = true,
                keyguardObserverRegistered = false,
                retryCount = 0,
            ),
        )
        assertEquals(
            100L,
            WorkspaceLockObservationPolicy.unlockRetryDelayMs(
                locked = false,
                screenReady = false,
                keyguardObserverRegistered = true,
                retryCount = 0,
            ),
        )
        assertNull(
            WorkspaceLockObservationPolicy.unlockRetryDelayMs(
                locked = false,
                screenReady = false,
                keyguardObserverRegistered = true,
                retryCount = 20,
            ),
        )
    }
}
