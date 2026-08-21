package ru.ruscrafting.farms.domain

import kotlin.math.abs

object FarmPatchPlanner {
    fun select(
        candidates: Collection<FarmPlotPosition>,
        anchor: FarmPlotPosition,
        targetSize: Int,
    ): List<FarmPlotPosition> {
        require(targetSize in 1..512) { "Farm patch target must be in 1..512" }
        val available = candidates.asSequence()
            .filter { it.world == anchor.world }
            .distinct()
            .toMutableSet()
        if (available.isEmpty()) return emptyList()

        val components = mutableListOf<Set<FarmPlotPosition>>()
        while (available.isNotEmpty()) {
            val start = available.minWith(POSITION_ORDER)
            val component = linkedSetOf<FarmPlotPosition>()
            val queue = ArrayDeque<FarmPlotPosition>()
            available.remove(start)
            queue.add(start)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                component += current
                neighbors(current).forEach { neighbor ->
                    if (available.remove(neighbor)) queue.add(neighbor)
                }
            }
            components += component
        }

        val selectedComponent = components.maxWith(
            compareBy<Set<FarmPlotPosition>> { minOf(it.size, targetSize) }
                .thenByDescending { component -> component.minOf { distanceSquared(it, anchor) } }
                .thenByDescending { component -> component.minWith(POSITION_ORDER).coordinateKey() },
        )
        val start = selectedComponent.minWith(
            compareBy<FarmPlotPosition> { distanceSquared(it, anchor) }.then(POSITION_ORDER),
        )
        val remaining = selectedComponent.toMutableSet()
        val ordered = ArrayList<FarmPlotPosition>(minOf(targetSize, selectedComponent.size))
        val queue = ArrayDeque<FarmPlotPosition>()
        remaining.remove(start)
        queue.add(start)
        while (queue.isNotEmpty() && ordered.size < targetSize) {
            val current = queue.removeFirst()
            ordered += current
            neighbors(current).filter(remaining::remove).sortedWith(POSITION_ORDER).forEach(queue::add)
        }
        return ordered
    }

    private fun neighbors(position: FarmPlotPosition): Sequence<FarmPlotPosition> = sequence {
        for (dy in -1..1) {
            for (dx in -2..2) {
                for (dz in -2..2) {
                    if (dx == 0 && dy == 0 && dz == 0) continue
                    val x = position.x.toLong() + dx
                    val y = position.y.toLong() + dy
                    val z = position.z.toLong() + dz
                    if (x !in -30_000_000L..30_000_000L || y !in -4_096L..4_096L || z !in -30_000_000L..30_000_000L) {
                        continue
                    }
                    yield(FarmPlotPosition(position.world, x.toInt(), y.toInt(), z.toInt()))
                }
            }
        }
    }

    private fun distanceSquared(left: FarmPlotPosition, right: FarmPlotPosition): Long {
        val dx = abs(left.x.toLong() - right.x)
        val dy = abs(left.y.toLong() - right.y)
        val dz = abs(left.z.toLong() - right.z)
        return dx * dx + dy * dy + dz * dz
    }

    private val POSITION_ORDER = compareBy<FarmPlotPosition>(
        FarmPlotPosition::y,
        FarmPlotPosition::x,
        FarmPlotPosition::z,
        FarmPlotPosition::world,
    )

    private fun FarmPlotPosition.coordinateKey(): String = "$world:$y:$x:$z"
}
