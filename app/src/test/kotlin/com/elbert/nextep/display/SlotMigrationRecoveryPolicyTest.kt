package com.elbert.nextep.display

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotMigrationRecoveryPolicyTest {
    @Test
    fun migrationRecoveryIsActiveOnlyWithinDeadlineAndAttemptBudget() {
        assertTrue(
            SlotTaskCoordinator.isMigrationRecoveryActive(
                hasTask = true,
                attempts = 0,
                nowMs = 10_000L,
                deadlineMs = 15_000L,
            ),
        )
        assertTrue(
            SlotTaskCoordinator.isMigrationRecoveryActive(
                hasTask = true,
                attempts = 0,
                nowMs = 15_000L,
                deadlineMs = 15_000L,
            ),
        )
        assertFalse(
            SlotTaskCoordinator.isMigrationRecoveryActive(
                hasTask = true,
                attempts = 0,
                nowMs = 15_001L,
                deadlineMs = 15_000L,
            ),
        )
        assertFalse(
            SlotTaskCoordinator.isMigrationRecoveryActive(
                hasTask = true,
                attempts = SlotTaskCoordinator.MAX_MIGRATION_RECOVERY_ATTEMPTS,
                nowMs = 12_000L,
                deadlineMs = 15_000L,
            ),
        )
        assertFalse(
            SlotTaskCoordinator.isMigrationRecoveryActive(
                hasTask = false,
                attempts = 0,
                nowMs = 12_000L,
                deadlineMs = 15_000L,
            ),
        )
    }

    @Test
    fun migrationRecoveryExpiresAfterDeadlineEvenWhenSlotTaskIsHealthy() {
        assertFalse(
            SlotTaskCoordinator.isMigrationRecoveryExpired(
                hasTask = true,
                attempts = 0,
                hasOccupiedDisplayTask = true,
                nowMs = 14_000L,
                deadlineMs = 15_000L,
            ),
        )
        assertTrue(
            SlotTaskCoordinator.isMigrationRecoveryExpired(
                hasTask = true,
                attempts = 0,
                hasOccupiedDisplayTask = true,
                nowMs = 15_001L,
                deadlineMs = 15_000L,
            ),
        )
    }

    @Test
    fun relaunchedMigrationRecoveryKeepsTaskUntilOccupiedOrDeadline() {
        assertFalse(
            SlotTaskCoordinator.isMigrationRecoveryExpired(
                hasTask = true,
                attempts = SlotTaskCoordinator.MAX_MIGRATION_RECOVERY_ATTEMPTS,
                hasOccupiedDisplayTask = false,
                nowMs = 13_000L,
                deadlineMs = 15_000L,
            ),
        )
        assertTrue(
            SlotTaskCoordinator.isMigrationRecoveryExpired(
                hasTask = true,
                attempts = SlotTaskCoordinator.MAX_MIGRATION_RECOVERY_ATTEMPTS,
                hasOccupiedDisplayTask = true,
                nowMs = 13_000L,
                deadlineMs = 15_000L,
            ),
        )
        assertTrue(
            SlotTaskCoordinator.isMigrationRecoveryExpired(
                hasTask = true,
                attempts = SlotTaskCoordinator.MAX_MIGRATION_RECOVERY_ATTEMPTS,
                hasOccupiedDisplayTask = false,
                nowMs = 15_001L,
                deadlineMs = 15_000L,
            ),
        )
    }
}
