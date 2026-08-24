package ru.ruscrafting.farms.domain

object FarmContractPlanner {
    fun select(
        orders: List<FarmOrder>,
        rareChancePercent: Int,
        rareRoll: Int,
        selectionIndex: Long,
    ): FarmOrder {
        require(orders.isNotEmpty()) { "Farm contract pool must not be empty" }
        require(rareChancePercent in 0..100) { "Rare contract chance must be in 0..100" }
        require(rareRoll in 0..99) { "Rare contract roll must be in 0..99" }

        val common = orders.filter { it.rarity == FarmContractRarity.COMMON }
        val rare = orders.filter { it.rarity == FarmContractRarity.RARE }
        val pool = when {
            rare.isNotEmpty() && rareRoll < rareChancePercent -> rare
            common.isNotEmpty() -> common
            else -> rare
        }
        return pool[java.lang.Math.floorMod(selectionIndex, pool.size.toLong()).toInt()]
    }

    fun harvestMilestone(completed: Int, total: Int): Int {
        require(total > 0) { "Farm contract total must be positive" }
        return ((completed.coerceIn(0, total).toLong() * 4L) / total.toLong()).toInt().coerceIn(0, 4)
    }

    fun harvestCheckpoint(completed: Int, total: Int): Int {
        require(total > 0) { "Farm contract total must be positive" }
        return ((completed.coerceIn(0, total).toLong() * 10L) / total.toLong()).toInt().coerceIn(0, 10)
    }
}
