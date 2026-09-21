package ru.ruscrafting.farms.domain.mine.expedition

import kotlin.math.PI

/** The crane places the billet first; transport and the press stroke never overlap. */
internal object MineFactoryPressCycle {
    const val TRANSFER_MILLIS = 4_000L
    const val STROKE_MILLIS = 2_400L
    const val TOTAL_MILLIS = TRANSFER_MILLIS + STROKE_MILLIS

    fun transferPhase(elapsed: Long): Double =
        (elapsed.toDouble() / TRANSFER_MILLIS).coerceIn(0.0, 1.0) * PI * 2

    fun strokePhase(elapsed: Long): Double =
        ((elapsed - TRANSFER_MILLIS).toDouble() / STROKE_MILLIS).coerceIn(0.0, 1.0) * PI * 2
}
