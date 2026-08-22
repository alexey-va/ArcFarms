package ru.ruscrafting.farms.domain

import java.lang.Math.floorMod

object FarmCarePlanner {
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

    fun route(
        candidates: Collection<FarmPlotPosition>,
        targetCount: Int,
        selectionIndex: Long,
        originX: Double,
        originZ: Double,
    ): List<FarmPlotPosition> {
        val points = spread(candidates, targetCount, selectionIndex).toMutableList()
        if (points.size <= 1) return points
        val ordered = mutableListOf<FarmPlotPosition>()
        var x = originX
        var z = originZ
        while (points.isNotEmpty()) {
            val next = points.minWith(
                compareBy<FarmPlotPosition> { candidate ->
                    val dx = candidate.x + 0.5 - x
                    val dz = candidate.z + 0.5 - z
                    dx * dx + dz * dz
                }.thenBy(FarmPlotPosition::x).thenBy(FarmPlotPosition::z),
            )
            ordered += next
            points -= next
            x = next.x + 0.5
            z = next.z + 0.5
        }
        return ordered
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
}
