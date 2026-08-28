package ru.ruscrafting.farms.paper

import ru.ruscrafting.farms.config.FarmCropLayoutSettings
import ru.ruscrafting.farms.domain.FarmPlotPosition
import kotlin.math.max

/**
 * Produces a stable, spatially coherent crop layout for an explicit farm reindex.
 * Runtime incidents never call this planner; they restore the indexed result instead.
 */
internal object FarmCropLayoutPlanner {
    fun plan(
        beds: Collection<FarmPlotPosition>,
        settings: FarmCropLayoutSettings,
    ): Map<FarmPlotPosition, String> {
        if (!settings.enabled || beds.isEmpty()) return emptyMap()
        val components = connectedComponents(beds.toSet())
        if (components.isEmpty()) return emptyMap()

        val large = components.filter { it.positions.size > settings.smallComponentMaxSize }
        val groups = linkedMapOf<Component, MutableList<Component>>()
        large.forEach { groups[it] = mutableListOf(it) }
        components.filterNot(large::contains).forEach { small ->
            val nearest = large.asSequence()
                .map { candidate -> candidate to small.bounds.distanceSquared(candidate.bounds) }
                .filter { (_, distance) ->
                    distance <= settings.smallComponentMergeDistance.toLong() * settings.smallComponentMergeDistance
                }
                .minWithOrNull(
                    compareBy<Pair<Component, Long>> { it.second }
                        .thenComparator { left, right -> POSITION_ORDER.compare(left.first.anchor, right.first.anchor) },
                )
                ?.first
            if (nearest == null) groups[small] = mutableListOf(small)
            else groups.getValue(nearest) += small
        }

        val crops = settings.weights.keys.sorted()
        val totalWeight = settings.weights.values.sum().toLong()
        val assigned = crops.associateWithTo(linkedMapOf()) { 0L }
        var assignedTotal = 0L
        val result = linkedMapOf<FarmPlotPosition, String>()
        groups.entries
            .sortedWith(
                compareByDescending<Map.Entry<Component, MutableList<Component>>> { entry ->
                    entry.value.sumOf { it.positions.size }
                }.thenComparator { left, right -> POSITION_ORDER.compare(left.key.anchor, right.key.anchor) },
            )
            .forEach { (_, members) ->
                val groupSize = members.sumOf { it.positions.size }.toLong()
                val totalAfter = assignedTotal + groupSize
                val crop = crops.maxWith(
                    compareBy<String> { candidate ->
                        totalAfter * settings.weights.getValue(candidate) - assigned.getValue(candidate) * totalWeight
                    }.thenBy { it },
                )
                members.flatMapTo(linkedSetOf(), Component::positions).forEach { result[it] = crop }
                assigned[crop] = assigned.getValue(crop) + groupSize
                assignedTotal = totalAfter
            }
        return result
    }

    private fun connectedComponents(remainingBeds: Set<FarmPlotPosition>): List<Component> {
        val remaining = remainingBeds.toMutableSet()
        val components = mutableListOf<Component>()
        while (remaining.isNotEmpty()) {
            val start = remaining.minWithOrNull(POSITION_ORDER) ?: break
            val queue = ArrayDeque<FarmPlotPosition>()
            val positions = mutableListOf<FarmPlotPosition>()
            remaining.remove(start)
            queue += start
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                positions += current
                neighbors(current).forEach { neighbor ->
                    if (remaining.remove(neighbor)) queue += neighbor
                }
            }
            components += Component(positions.sortedWith(POSITION_ORDER))
        }
        return components
    }

    private fun neighbors(position: FarmPlotPosition): List<FarmPlotPosition> = listOf(
        position.copy(x = position.x + 1),
        position.copy(x = position.x - 1),
        position.copy(z = position.z + 1),
        position.copy(z = position.z - 1),
    )

    private data class Component(val positions: List<FarmPlotPosition>) {
        val anchor: FarmPlotPosition = positions.first()
        val bounds = Bounds(
            minX = positions.minOf(FarmPlotPosition::x),
            minY = positions.minOf(FarmPlotPosition::y),
            minZ = positions.minOf(FarmPlotPosition::z),
            maxX = positions.maxOf(FarmPlotPosition::x),
            maxY = positions.maxOf(FarmPlotPosition::y),
            maxZ = positions.maxOf(FarmPlotPosition::z),
        )
    }

    private data class Bounds(
        val minX: Int,
        val minY: Int,
        val minZ: Int,
        val maxX: Int,
        val maxY: Int,
        val maxZ: Int,
    ) {
        fun distanceSquared(other: Bounds): Long {
            val dx = axisGap(minX, maxX, other.minX, other.maxX).toLong()
            val dy = axisGap(minY, maxY, other.minY, other.maxY).toLong()
            val dz = axisGap(minZ, maxZ, other.minZ, other.maxZ).toLong()
            return dx * dx + dy * dy + dz * dz
        }

        private fun axisGap(min: Int, max: Int, otherMin: Int, otherMax: Int): Int =
            max(0, maxOf(min, otherMin) - minOf(max, otherMax))
    }

    private val POSITION_ORDER = compareBy<FarmPlotPosition>(
        FarmPlotPosition::world,
        FarmPlotPosition::y,
        FarmPlotPosition::x,
        FarmPlotPosition::z,
    )
}
