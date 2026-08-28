package ru.ruscrafting.farms.domain

import kotlin.math.floor

/** Pure safety policy shared by incidents that temporarily destroy crops. */
object FarmDamageBudget {
    fun maximum(totalCrops: Int, percent: Int, minimumRemaining: Int, absoluteLimit: Int): Int {
        require(totalCrops >= 0)
        require(percent in 0..100)
        require(minimumRemaining >= 0)
        require(absoluteLimit >= 0)
        val proportional = floor(totalCrops * percent / 100.0).toInt()
        val preservingReserve = (totalCrops - minimumRemaining).coerceAtLeast(0)
        return minOf(proportional, preservingReserve, absoluteLimit)
    }

    fun remaining(totalCrops: Int, alreadyDamaged: Int, percent: Int, minimumRemaining: Int, absoluteLimit: Int): Int =
        (maximum(totalCrops, percent, minimumRemaining, absoluteLimit) - alreadyDamaged).coerceAtLeast(0)
}
