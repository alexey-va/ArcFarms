package ru.ruscrafting.farms.paper.farm.incident.pest

import ru.ruscrafting.farms.domain.FarmPlotPosition
import java.util.UUID
import kotlin.math.abs

internal data class PestCropCandidatePlan(
    val pestId: UUID,
    val candidates: List<FarmPlotPosition>,
)

/** Pure bounded planning for pest crop pulses; Bukkit eligibility is supplied after main-thread revalidation. */
internal object FarmPestCropPlanner {
    fun candidates(
        pests: List<Triple<UUID, Int, Int>>,
        indexedBeds: List<FarmPlotPosition>,
        radius: Int,
    ): List<PestCropCandidatePlan> {
        require(radius in 0..32) { "Pest crop radius is invalid" }
        return pests.map { pest ->
            PestCropCandidatePlan(
                pest.first,
                indexedBeds.asSequence().filter { plot ->
                    abs(plot.x - pest.second) <= radius && abs(plot.z - pest.third) <= radius
                }.sortedWith(
                    compareBy<FarmPlotPosition> { plot ->
                        val dx = plot.x - pest.second
                        val dz = plot.z - pest.third
                        dx * dx + dz * dz
                    }.thenBy(FarmPlotPosition::world)
                        .thenBy(FarmPlotPosition::y)
                        .thenBy(FarmPlotPosition::x)
                        .thenBy(FarmPlotPosition::z),
                ).toList(),
            )
        }
    }

    fun select(
        plans: List<PestCropCandidatePlan>,
        eligible: Set<FarmPlotPosition>,
        alreadyDamaged: Set<FarmPlotPosition>,
        totalLimit: Int,
        perPestLimit: Int,
    ): List<Pair<UUID, FarmPlotPosition>> {
        require(totalLimit >= 0) { "Pest crop total limit must not be negative" }
        require(perPestLimit >= 0) { "Pest crop per-pest limit must not be negative" }
        if (totalLimit == 0 || perPestLimit == 0 || eligible.isEmpty()) return emptyList()
        val claimed = alreadyDamaged.toMutableSet()
        val selected = mutableListOf<Pair<UUID, FarmPlotPosition>>()
        plans.forEach { plan ->
            var selectedForPest = 0
            for (position in plan.candidates) {
                if (selected.size >= totalLimit || selectedForPest >= perPestLimit) break
                if (position !in eligible || !claimed.add(position)) continue
                selected += plan.pestId to position
                selectedForPest++
            }
        }
        return selected
    }
}
