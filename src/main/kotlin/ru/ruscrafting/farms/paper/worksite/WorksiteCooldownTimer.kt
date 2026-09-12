package ru.ruscrafting.farms.paper.worksite

import kotlin.math.ceil

/** Shared countdown semantics for every worksite's persisted cooldown state. */
internal object WorksiteCooldownTimer {
    fun remainingSeconds(deadline: Long, now: Long): Long =
        ceil((deadline - now).coerceAtLeast(0L) / 1_000.0).toLong()

    fun progress(deadline: Long, now: Long, durationMillis: Long): Float {
        val duration = durationMillis.coerceAtLeast(1L)
        val remaining = (deadline - now).coerceAtLeast(0L)
        return (1.0 - remaining.toDouble() / duration).toFloat().coerceIn(0f, 1f)
    }
}
