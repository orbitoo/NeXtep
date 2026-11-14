package com.elbert.nextep.display

internal object SlotHibernateIdlePolicy {
    sealed interface IdleDecision {
        data object Disabled : IdleDecision
        data object HibernateNow : IdleDecision
        data class Reschedule(val delayMs: Long) : IdleDecision
    }

    fun evaluate(
        enabled: Boolean,
        timeoutSeconds: Int,
        lastActivityUptimeMs: Long,
        nowUptimeMs: Long,
    ): IdleDecision {
        if (!enabled || timeoutSeconds <= 0) return IdleDecision.Disabled
        val timeoutMs = timeoutSeconds * 1_000L
        val elapsedMs = (nowUptimeMs - lastActivityUptimeMs).coerceAtLeast(0L)
        return if (elapsedMs >= timeoutMs) {
            IdleDecision.HibernateNow
        } else {
            IdleDecision.Reschedule(timeoutMs - elapsedMs)
        }
    }

    fun shouldConnectOutput(suspended: Boolean, hibernated: Boolean): Boolean =
        !suspended && !hibernated

    fun shouldWarmSuspendedOutput(active: Boolean, suspended: Boolean, hibernated: Boolean): Boolean =
        active && suspended && !hibernated
}
