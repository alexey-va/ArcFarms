package ru.ruscrafting.farms.domain

/** Deterministically spreads a small flock over the full indexed field. */
object FarmBirdPlanner {
    fun select(
        beds: Collection<FarmPlotPosition>,
        count: Int,
        sequence: Long,
    ): List<FarmPlotPosition> {
        require(count in 0..32) { "Farm bird count is invalid" }
        val available = beds.distinct().sortedWith(
            compareBy<FarmPlotPosition> { it.world }.thenBy { it.x }.thenBy { it.z }.thenBy { it.y },
        ).toMutableList()
        if (available.isEmpty() || count == 0) return emptyList()
        val first = java.lang.Math.floorMod((sequence xor (sequence ushr 32)).toInt(), available.size)
        val selected = mutableListOf(available.removeAt(first))
        while (selected.size < count && available.isNotEmpty()) {
            val next = available.maxWithOrNull(
                compareBy<FarmPlotPosition> { candidate ->
                    selected.minOf { chosen -> distanceSquared(candidate, chosen) }
                }.thenByDescending { it.x }.thenByDescending { it.z }.thenByDescending { it.y },
            ) ?: break
            available.remove(next)
            selected += next
        }
        return selected
    }

    private fun distanceSquared(left: FarmPlotPosition, right: FarmPlotPosition): Long {
        if (left.world != right.world) return Long.MAX_VALUE
        val dx = left.x.toLong() - right.x
        val dz = left.z.toLong() - right.z
        return dx * dx + dz * dz
    }
}
