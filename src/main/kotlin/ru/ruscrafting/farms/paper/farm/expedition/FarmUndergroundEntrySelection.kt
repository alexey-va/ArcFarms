package ru.ruscrafting.farms.paper.farm.expedition

import ru.ruscrafting.farms.domain.FarmCarePlanner
import ru.ruscrafting.farms.domain.FarmMoleEntrancePlanner
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmPointPosition

/** Shared surface candidate policy for underground activities. */
internal object FarmUndergroundEntrySelection {
    fun candidates(
        beds: Collection<FarmPlotPosition>,
        minimumBoundaryDistance: Int,
        candidateAttempts: Int,
        salt: Long,
        hasRegionClearance: (FarmPlotPosition, Int) -> Boolean = { _, _ -> true },
    ): List<FarmPointPosition> {
        val preferred = FarmMoleEntrancePlanner.preferredBeds(beds, minimumBoundaryDistance)
        val clear = preferred.filter { hasRegionClearance(it, minimumBoundaryDistance) }.ifEmpty { preferred }
        return FarmCarePlanner.spread(
            clear,
            candidateAttempts.coerceAtMost(clear.size),
            salt,
        ).map { plot -> FarmPointPosition(plot.world, plot.x + 0.5, plot.y + 1.05, plot.z + 0.5) }
    }
}
