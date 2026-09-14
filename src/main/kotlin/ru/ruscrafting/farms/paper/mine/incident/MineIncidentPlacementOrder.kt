package ru.ruscrafting.farms.paper.mine.incident

import ru.ruscrafting.farms.domain.placement.WorksitePlacementPlanner
import ru.ruscrafting.farms.domain.placement.WorksitePlacementPoint
import ru.ruscrafting.farms.domain.placement.WorksitePlacementProfiles
import ru.ruscrafting.farms.domain.placement.WorksitePlacementRequest
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime

/** Puts the visible objective and its immediate reserves far apart without losing fallback candidates. */
internal fun orderMineIncidentPositions(
    runtime: MineRuntime,
    positions: Collection<WorksitePosition>,
    visibleCount: Int,
    salt: Long,
): List<WorksitePosition> {
    val available = positions.distinct()
    if (available.size <= 1 || visibleCount <= 0) return available
    val selected = WorksitePlacementPlanner.select(
        available,
        WorksitePlacementRequest(minOf(visibleCount, available.size), runtime.state.sequence xor salt),
        WorksitePlacementProfiles.evenSpread(minimumSpacing = 8.0),
    ) { position ->
        WorksitePlacementPoint(
            position.world,
            position.x + 0.5,
            position.y.toDouble(),
            position.z + 0.5,
        )
    }
    val chosen = selected.toHashSet()
    return selected + available.filterNot(chosen::contains)
}
