package com.elbert.nextep.display

/** Fast recovery around task changes, then progressive backoff when idle. */
internal class WorkspaceRefreshPolicy {
    private var settleUntil = 0L
    private var quietAt = 0L

    fun onTaskChanged(now: Long) {
        settleUntil = now + SETTLE_WINDOW_MS
        quietAt = now + QUIET_AFTER_MS
    }

    fun delayMs(now: Long, pendingRecovery: Boolean = false): Long = when {
        pendingRecovery || now < settleUntil -> FAST_INTERVAL_MS
        now < quietAt -> IDLE_INTERVAL_MS
        else -> QUIET_INTERVAL_MS
    }

    companion object {
        const val FAST_INTERVAL_MS = 120L
        const val IDLE_INTERVAL_MS = 1_000L
        const val QUIET_INTERVAL_MS = 5_000L
        private const val SETTLE_WINDOW_MS = 2_000L
        private const val QUIET_AFTER_MS = 6_000L
    }
}
