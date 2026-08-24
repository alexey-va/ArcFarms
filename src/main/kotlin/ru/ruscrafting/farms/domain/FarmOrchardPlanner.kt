package ru.ruscrafting.farms.domain

import java.lang.Math.floorMod

object FarmOrchardPlanner {
    fun select(
        candidates: Collection<FarmPlotPosition>,
        targetCount: Int,
        minimumSpacing: Double,
        selectionIndex: Long,
    ): List<FarmPlotPosition> {
        require(targetCount in 1..64) { "Apple target count is invalid" }
        require(minimumSpacing.isFinite() && minimumSpacing >= 0.0) { "Apple target spacing is invalid" }
        val unique = candidates.distinct().sortedWith(
            compareBy(FarmPlotPosition::world, FarmPlotPosition::y, FarmPlotPosition::x, FarmPlotPosition::z),
        )
        if (unique.isEmpty()) return emptyList()
        val start = floorMod(selectionIndex, unique.size.toLong()).toInt()
        val rotated = unique.drop(start) + unique.take(start)
        val selected = mutableListOf(rotated.first())
        val minimumSquared = minimumSpacing * minimumSpacing
        while (selected.size < targetCount) {
            val next = rotated.asSequence().filterNot(selected::contains).filter { candidate ->
                selected.all { existing -> horizontalDistanceSquared(candidate, existing) >= minimumSquared }
            }.maxWithOrNull(
                compareBy<FarmPlotPosition> { candidate ->
                    selected.minOf { existing -> horizontalDistanceSquared(candidate, existing) }
                }.thenByDescending(FarmPlotPosition::x).thenByDescending(FarmPlotPosition::z),
            ) ?: break
            selected += next
        }
        return selected
    }

    private fun horizontalDistanceSquared(first: FarmPlotPosition, second: FarmPlotPosition): Double {
        if (first.world != second.world) return Double.POSITIVE_INFINITY
        val dx = (first.x - second.x).toDouble()
        val dz = (first.z - second.z).toDouble()
        return dx * dx + dz * dz
    }
}
