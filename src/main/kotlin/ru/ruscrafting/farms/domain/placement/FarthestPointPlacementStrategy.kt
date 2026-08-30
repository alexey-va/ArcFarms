package ru.ruscrafting.farms.domain.placement

/** Deterministic farthest-point sampling for events that want full-field spread. */
object FarthestPointPlacementStrategy : WorksitePlacementStrategy {
    override val id: String = "farthest_point"

    override fun select(
        candidates: List<WorksitePlacementPoint>,
        request: WorksitePlacementRequest,
        minimumSpacing: Double,
    ): List<WorksitePlacementPoint> {
        val available = candidates.distinct().sortedWith(WorksitePlacementPlanner.POINT_ORDER)
        if (request.count == 0 || available.isEmpty()) return emptyList()
        if (available.size <= request.count) return available
        val target = minOf(request.count, available.size)
        val first = Math.floorMod(request.seed, available.size.toLong()).toInt()
        val selected = mutableListOf(available[first])
        val remaining = available.toMutableList().also { it.removeAt(first) }
        val minimumSpacingSquared = minimumSpacing * minimumSpacing

        while (selected.size < target && remaining.isNotEmpty()) {
            val spaced = remaining.filter { minimumDistanceSquared(it, selected) >= minimumSpacingSquared }
            val pool = spaced.ifEmpty { remaining }
            val next = pool.maxWith(
                compareBy<WorksitePlacementPoint> { minimumDistanceSquared(it, selected) }
                    .thenBy { tieRank(it, request.seed xor selected.size.toLong()) }
                    .then(WorksitePlacementPlanner.POINT_ORDER),
            )
            selected += next
            remaining.remove(next)
        }
        return selected
    }

    private fun minimumDistanceSquared(
        candidate: WorksitePlacementPoint,
        selected: List<WorksitePlacementPoint>,
    ): Double = selected.minOf { existing ->
        val dx = candidate.x - existing.x
        val dz = candidate.z - existing.z
        dx * dx + dz * dz
    }

    private fun tieRank(point: WorksitePlacementPoint, seed: Long): Long =
        WorksitePlacementMix.mix(
            seed xor java.lang.Double.doubleToLongBits(point.x) xor
                java.lang.Long.rotateLeft(java.lang.Double.doubleToLongBits(point.z), 29),
        )
}

private object WorksitePlacementMix {
    fun mix(value: Long): Long {
        var mixed = value xor (value ushr 33)
        mixed *= -49064778989728563L
        mixed = mixed xor (mixed ushr 33)
        mixed *= -4265267296055464877L
        return mixed xor (mixed ushr 33)
    }
}
