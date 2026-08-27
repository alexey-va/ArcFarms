package ru.ruscrafting.farms.domain

import kotlin.math.ceil

data class FarmPlayerTimeStep(
    val time: Long,
    val reachedTarget: Boolean,
)

/** Pure circular-time stepping used by the per-player night-shift presentation. */
object FarmPlayerTimeTransition {
    fun normalize(time: Long): Long = Math.floorMod(time, DAY_TICKS)

    fun step(
        current: Long,
        target: Long,
        maximumStep: Long,
    ): FarmPlayerTimeStep {
        require(maximumStep in 1..HALF_DAY_TICKS) { "Farm player-time step is invalid" }
        val from = normalize(current)
        val to = normalize(target)
        val forward = Math.floorMod(to - from, DAY_TICKS)
        val backward = Math.floorMod(from - to, DAY_TICKS)
        val next = when {
            forward == 0L -> from
            forward <= backward -> normalize(from + minOf(forward, maximumStep))
            else -> normalize(from - minOf(backward, maximumStep))
        }
        return FarmPlayerTimeStep(next, next == to)
    }

    fun maximumStep(transitionSeconds: Int, updatesPerSecond: Int = 4): Long {
        require(transitionSeconds in 1..60) { "Farm player-time transition duration is invalid" }
        require(updatesPerSecond in 1..20) { "Farm player-time update frequency is invalid" }
        return ceil(HALF_DAY_TICKS.toDouble() / (transitionSeconds * updatesPerSecond)).toLong().coerceAtLeast(1L)
    }

    private const val DAY_TICKS = 24_000L
    private const val HALF_DAY_TICKS = DAY_TICKS / 2L
}
