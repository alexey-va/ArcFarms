package ru.ruscrafting.farms.domain

import java.lang.Math.floorMod

object FarmCarePlanner {
    fun orient(
        targets: List<FarmCareTarget>,
        anchor: FarmPointPosition?,
    ): List<FarmCareTarget> {
        if (anchor == null || targets.size <= 1) return targets
        val remaining = targets.toMutableList()
        val ordered = ArrayList<FarmCareTarget>(targets.size)
        var cursor = requireNotNull(anchor)
        while (remaining.isNotEmpty()) {
            val next = remaining.minWith(
                compareBy<FarmCareTarget> { target -> horizontalDistanceSquared(cursor, target.position) }
                    .thenBy(FarmCareTarget::id),
            )
            remaining.remove(next)
            ordered += next
            cursor = next.position
        }
        return ordered.mapIndexed { index, target -> target.copy(id = index) }
    }

    fun preserveProgress(
        previous: List<FarmCareTarget>,
        rebuilt: List<FarmCareTarget>,
    ): List<FarmCareTarget> {
        val previousByPosition = previous.associateBy { target -> target.role to target.position }
        val previousByRole = previous.groupBy(FarmCareTarget::role)
        return rebuilt.map { target ->
            val old = previousByPosition[target.role to target.position]
                ?: previousByRole[target.role]?.singleOrNull()
            target.copy(progress = (old?.progress ?: 0).coerceAtMost(target.required))
        }
    }

    fun spread(
        candidates: Collection<FarmPlotPosition>,
        targetCount: Int,
        selectionIndex: Long,
    ): List<FarmPlotPosition> {
        require(targetCount in 1..64) { "Farm care target count is invalid" }
        val unique = candidates.distinct().sortedWith(
            compareBy(FarmPlotPosition::world, FarmPlotPosition::y, FarmPlotPosition::x, FarmPlotPosition::z),
        )
        if (unique.size <= targetCount) return unique
        val selected = mutableListOf(unique[floorMod(selectionIndex, unique.size.toLong()).toInt()])
        while (selected.size < targetCount) {
            val next = unique.asSequence().filterNot(selected::contains).maxWithOrNull(
                compareBy<FarmPlotPosition> { candidate ->
                    selected.minOf { existing -> horizontalDistanceSquared(candidate, existing) }
                }.thenByDescending(FarmPlotPosition::x)
                    .thenByDescending(FarmPlotPosition::z),
            ) ?: break
            selected += next
        }
        return selected
    }

    fun corners(candidates: Collection<FarmPlotPosition>): List<FarmPlotPosition> {
        val unique = candidates.distinct()
        if (unique.size <= 4) return unique
        val minX = unique.minOf(FarmPlotPosition::x)
        val maxX = unique.maxOf(FarmPlotPosition::x)
        val minZ = unique.minOf(FarmPlotPosition::z)
        val maxZ = unique.maxOf(FarmPlotPosition::z)
        return listOf(minX to minZ, minX to maxZ, maxX to minZ, maxX to maxZ)
            .map { (x, z) ->
                unique.minWith(
                    compareBy<FarmPlotPosition> { candidate ->
                        val dx = candidate.x - x
                        val dz = candidate.z - z
                        dx * dx + dz * dz
                    }.thenBy(FarmPlotPosition::x).thenBy(FarmPlotPosition::z),
                )
            }
            .distinct()
    }

    fun relocate(
        candidates: Collection<FarmPlotPosition>,
        occupied: Collection<FarmPointPosition>,
        selectionIndex: Long,
    ): FarmPlotPosition? {
        val unique = candidates.distinct().sortedWith(
            compareBy(FarmPlotPosition::world, FarmPlotPosition::y, FarmPlotPosition::x, FarmPlotPosition::z),
        )
        if (unique.isEmpty()) return null
        if (occupied.isEmpty()) return unique[floorMod(selectionIndex, unique.size.toLong()).toInt()]
        val rotated = unique.drop(floorMod(selectionIndex, unique.size.toLong()).toInt()) +
            unique.take(floorMod(selectionIndex, unique.size.toLong()).toInt())
        return rotated.maxWithOrNull(
            compareBy<FarmPlotPosition> { candidate ->
                occupied.minOf { point ->
                    val dx = candidate.x + 0.5 - point.x
                    val dz = candidate.z + 0.5 - point.z
                    dx * dx + dz * dz
                }
            }.thenByDescending(FarmPlotPosition::x).thenByDescending(FarmPlotPosition::z),
        )
    }

    private fun horizontalDistanceSquared(first: FarmPlotPosition, second: FarmPlotPosition): Int {
        val dx = first.x - second.x
        val dz = first.z - second.z
        return dx * dx + dz * dz
    }

    private fun horizontalDistanceSquared(first: FarmPointPosition, second: FarmPointPosition): Double {
        if (first.world != second.world) return Double.POSITIVE_INFINITY
        val dx = first.x - second.x
        val dz = first.z - second.z
        return dx * dx + dz * dz
    }
}
