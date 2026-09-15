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
    val localPool = available.sortedBy { position -> spreadHash(runtime.state.sequence xor salt, position) }.take(poolSize)
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
    val local = localPool.toHashSet()
    return selected + localPool.filterNot(chosen::contains) + available.filterNot(local::contains)
}

private fun spreadHash(seed: Long, position: WorksitePosition): Long {
    var hash = seed xor -7046029254386353131L
    hash = (hash xor position.x.toLong()) * -4658895280553007687L
    hash = (hash xor position.y.toLong()) * -7723592293110705685L
    hash = (hash xor position.z.toLong()) * -4658895280553007687L
    return hash xor (hash ushr 33)
}

private const val MIN_CANDIDATE_POOL = 256
private const val CANDIDATE_POOL_PER_TARGET = 64
