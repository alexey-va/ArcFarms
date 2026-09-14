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
    val participants = runtime.region.world.players.filter { runtime.region.contains(it.location) }
    val localPool = if (participants.isEmpty()) available else available.sortedBy { position ->
        participants.minOf { player ->
            val dx = position.x + 0.5 - player.location.x
            val dy = position.y + 1.0 - player.location.y
            val dz = position.z + 0.5 - player.location.z
            kotlin.math.abs(dx * dx + dz * dz - IDEAL_PLAYER_DISTANCE_SQUARED) + dy * dy * 4.0
        }
    }.take(minOf(available.size, maxOf(MIN_LOCAL_POOL, visibleCount * LOCAL_POOL_PER_TARGET)))
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

private const val IDEAL_PLAYER_DISTANCE_SQUARED = 196.0
private const val MIN_LOCAL_POOL = 128
private const val LOCAL_POOL_PER_TARGET = 32
