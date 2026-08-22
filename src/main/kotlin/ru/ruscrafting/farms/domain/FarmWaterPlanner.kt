package ru.ruscrafting.farms.domain

import kotlin.math.abs

object FarmWaterPlanner {
    fun canPlace(
        source: FarmPlotPosition,
        droughtPlots: Collection<FarmPlotPosition>,
        radius: Int,
    ): Boolean {
        require(radius in 1..16) { "Farm water radius must be in 1..16" }
        val radiusSquared = radius.toLong() * radius
        return droughtPlots.any { plot ->
            plot.world == source.world &&
                abs(source.y - plot.y) <= 2 &&
                horizontalDistanceSquared(source, plot) <= radiusSquared
        }
    }

    fun reachedPlots(
        droughtPlots: Collection<FarmPlotPosition>,
        waterBlocks: Collection<FarmPlotPosition>,
    ): Set<FarmPlotPosition> {
        if (droughtPlots.isEmpty() || waterBlocks.isEmpty()) return emptySet()
        val waterColumns = waterBlocks.mapTo(hashSetOf()) { water ->
            WaterColumn(water.world, water.x, water.y, water.z)
        }
        return droughtPlots.filterTo(linkedSetOf()) { plot ->
            WaterColumn(plot.world, plot.x, plot.y + 1, plot.z) in waterColumns
        }
    }

    fun reachedPlotsWithinRadius(
        source: FarmPlotPosition,
        droughtPlots: Collection<FarmPlotPosition>,
        radius: Int,
    ): Set<FarmPlotPosition> {
        require(radius in 1..16) { "Farm water radius must be in 1..16" }
        val radiusSquared = radius.toLong() * radius
        return droughtPlots.filterTo(linkedSetOf()) { plot ->
            plot.world == source.world &&
                abs(source.y - plot.y) <= 2 &&
                horizontalDistanceSquared(source, plot) <= radiusSquared
        }
    }

    fun completedPatchCount(
        droughtPlots: Collection<FarmPlotPosition>,
        reachedPlots: Collection<FarmPlotPosition>,
    ): Int {
        if (droughtPlots.isEmpty() || reachedPlots.isEmpty()) return 0
        val remaining = droughtPlots.toMutableSet()
        val reached = reachedPlots.toSet()
        var completed = 0
        while (remaining.isNotEmpty()) {
            val component = linkedSetOf<FarmPlotPosition>()
            val queue = ArrayDeque<FarmPlotPosition>()
            queue += remaining.first()
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (!remaining.remove(current)) continue
                component += current
                neighbours(current).filter(remaining::contains).forEach(queue::addLast)
            }
            if (component.all(reached::contains)) completed++
        }
        return completed
    }

    private fun horizontalDistanceSquared(first: FarmPlotPosition, second: FarmPlotPosition): Long {
        val dx = first.x.toLong() - second.x
        val dz = first.z.toLong() - second.z
        return dx * dx + dz * dz
    }

    private fun neighbours(position: FarmPlotPosition): List<FarmPlotPosition> = listOf(
        position.copy(x = position.x + 1),
        position.copy(x = position.x - 1),
        position.copy(z = position.z + 1),
        position.copy(z = position.z - 1),
    )

    private data class WaterColumn(val world: String, val x: Int, val y: Int, val z: Int)
}
