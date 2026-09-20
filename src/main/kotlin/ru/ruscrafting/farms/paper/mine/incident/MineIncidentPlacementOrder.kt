package ru.ruscrafting.farms.paper.mine.incident

import ru.ruscrafting.farms.domain.placement.WorksitePlacementPlanner
import ru.ruscrafting.farms.domain.placement.WorksitePlacementProfiles
import ru.ruscrafting.farms.domain.placement.toPlacementPoint
import ru.ruscrafting.farms.domain.placement.WorksitePlacementRequest
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime

/** Puts objectives far apart and varies the mine-wide candidate pool between shifts. */
internal fun orderMineIncidentPositions(
    runtime: MineRuntime,
    positions: Collection<WorksitePosition>,
    visibleCount: Int,
    salt: Long,
): List<WorksitePosition> {
    val available = positions.distinct()
    if (available.size <= 1 || visibleCount <= 0) return available
    val poolSize = minOf(available.size, maxOf(MIN_CANDIDATE_POOL, visibleCount * CANDIDATE_POOL_PER_TARGET))
    val ordered = WorksitePlacementPlanner.seededOrder(
        available,
        runtime.state.sequence xor salt,
        WorksitePosition::toPlacementPoint,
    )
    val localPool = ordered.take(poolSize)
    val selected = WorksitePlacementPlanner.select(
        localPool,
        WorksitePlacementRequest(minOf(visibleCount, localPool.size), runtime.state.sequence xor salt),
        WorksitePlacementProfiles.evenSpread(minimumSpacing = 8.0),
        positionOf = WorksitePosition::toPlacementPoint,
    )
    val chosen = selected.toHashSet()
    return selected + localPool.filterNot(chosen::contains) + ordered.drop(poolSize)
}
private const val MIN_CANDIDATE_POOL = 256
private const val CANDIDATE_POOL_PER_TARGET = 64

/** Erode each floor independently, including inner holes in ring-shaped mine floors. */
internal fun interiorMineIncidentPositions(positions: Collection<WorksitePosition>, radius: Int = 2): List<WorksitePosition> {
    require(radius in 1..4)
    val floors = positions.toHashSet()
    val offsets = (-radius..radius).flatMap { x -> (-radius..radius).map { z -> x to z } }
        .filter { (x, z) -> x * x + z * z <= radius * radius }
    return positions.distinct().filter { point ->
        offsets.all { (x, z) -> point.copy(x = point.x + x, z = point.z + z) in floors }
    }
}
