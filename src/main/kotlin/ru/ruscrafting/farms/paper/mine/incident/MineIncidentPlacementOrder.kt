package ru.ruscrafting.farms.paper.mine.incident

import ru.ruscrafting.farms.domain.placement.WorksitePlacementPlanner
import ru.ruscrafting.farms.domain.placement.WorksitePlacementPoint
import ru.ruscrafting.farms.domain.placement.WorksitePlacementProfiles
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
    val ordered = WorksitePlacementPlanner.seededOrder(available, runtime.state.sequence xor salt) { position ->
        position.placementPoint()
    }
    val localPool = ordered.take(poolSize)
    val selected = WorksitePlacementPlanner.select(
        localPool,
        WorksitePlacementRequest(minOf(visibleCount, localPool.size), runtime.state.sequence xor salt),
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
    return selected + localPool.filterNot(chosen::contains) + ordered.drop(poolSize)
}

private fun WorksitePosition.placementPoint() = WorksitePlacementPoint(world, x + 0.5, y.toDouble(), z + 0.5)

private const val MIN_CANDIDATE_POOL = 256
private const val CANDIDATE_POOL_PER_TARGET = 64
