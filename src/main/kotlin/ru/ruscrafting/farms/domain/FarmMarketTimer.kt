package ru.ruscrafting.farms.domain

import kotlin.math.ceil

object FarmMarketTimer {
    fun durationMillis(
        required: Int,
        baseSeconds: Int,
        secondsPerCrop: Double,
        minimumSeconds: Int,
        maximumSeconds: Int,
    ): Long {
        require(required in 1..1_024) { "Market crop quota is invalid" }
        require(baseSeconds in 0..3_600) { "Market base duration is invalid" }
        require(secondsPerCrop.isFinite() && secondsPerCrop in 0.0..60.0) {
            "Market crop duration is invalid"
        }
        require(minimumSeconds in 10..3_600 && maximumSeconds in minimumSeconds..3_600) {
            "Market duration bounds are invalid"
        }
        val seconds = ceil(baseSeconds + required * secondsPerCrop).toLong()
            .coerceIn(minimumSeconds.toLong(), maximumSeconds.toLong())
        return seconds * 1_000L
    }

    fun remainingSeconds(deadlineAt: Long, now: Long): Long {
        require(deadlineAt >= 0 && now >= 0) { "Market clock is invalid" }
        return ceil((deadlineAt - now).coerceAtLeast(0L) / 1_000.0).toLong()
    }
}
