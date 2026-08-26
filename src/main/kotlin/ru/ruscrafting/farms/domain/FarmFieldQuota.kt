package ru.ruscrafting.farms.domain

import kotlin.math.ceil

/** Keeps field work tolerant to a few unreachable or visually hidden plots. */
object FarmFieldQuota {
    fun required(totalPlots: Int, completionPercent: Int): Int {
        require(totalPlots > 0) { "Farm field must contain at least one plot" }
        require(completionPercent in 50..100) { "Farm field completion percent must be in 50..100" }
        return ceil(totalPlots * completionPercent / 100.0).toInt().coerceIn(1, totalPlots)
    }
}
