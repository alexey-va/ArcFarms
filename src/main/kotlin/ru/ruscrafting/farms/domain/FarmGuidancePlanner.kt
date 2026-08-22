package ru.ruscrafting.farms.domain

import java.lang.Math.floorMod

object FarmGuidancePlanner {
    fun individualMissingPlots(
        remaining: Collection<FarmPlotPosition>,
        threshold: Int,
    ): List<FarmPlotPosition> {
        require(threshold > 0) { "Missing plot threshold must be positive" }
        if (remaining.isEmpty() || remaining.size > threshold) return emptyList()
        return remaining.sortedWith(compareBy(FarmPlotPosition::world, FarmPlotPosition::x, FarmPlotPosition::y, FarmPlotPosition::z))
    }
}

object FarmDeliveryPlanner {
    private const val NEAREST_CANDIDATE_LIMIT = 24

    fun selectAnchor(
        candidates: Collection<FarmDeliveryPosition>,
        anchorX: Double,
        anchorZ: Double,
        selectionIndex: Int,
    ): FarmDeliveryPosition? {
        val nearest = candidates
            .sortedWith(
                compareBy<FarmDeliveryPosition> { position ->
                    val dx = position.x - anchorX
                    val dz = position.z - anchorZ
                    dx * dx + dz * dz
                }.thenBy(FarmDeliveryPosition::x)
                    .thenBy(FarmDeliveryPosition::y)
                    .thenBy(FarmDeliveryPosition::z),
            )
            .take(NEAREST_CANDIDATE_LIMIT)
        if (nearest.isEmpty()) return null
        return nearest[floorMod(selectionIndex, nearest.size)]
    }
}
