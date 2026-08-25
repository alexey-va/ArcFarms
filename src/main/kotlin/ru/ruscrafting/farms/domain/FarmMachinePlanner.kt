package ru.ruscrafting.farms.domain

import kotlin.math.abs

/** Selects the bounded patch directly reached by horse-drawn machinery. */
object FarmMachinePlanner {
    fun plotsInWorkingRadius(
        candidates: Collection<FarmPlotPosition>,
        world: String,
        machineX: Double,
        machineY: Double,
        machineZ: Double,
        radius: Double,
    ): Set<FarmPlotPosition> {
        require(world.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Farm machine world is invalid" }
        require(machineX.isFinite() && machineY.isFinite() && machineZ.isFinite()) {
            "Farm machine position is invalid"
        }
        require(radius.isFinite() && radius in 1.0..16.0) { "Farm machine working radius must be in 1..16" }
        require(candidates.size <= MAX_FARM_PATCH_PLOTS) { "Farm machine patch is unbounded" }
        val radiusSquared = radius * radius
        return candidates.asSequence()
            .filter { plot ->
                if (plot.world != world || abs(plot.y + 1.0 - machineY) > MAX_VERTICAL_REACH) return@filter false
                val dx = plot.x + 0.5 - machineX
                val dz = plot.z + 0.5 - machineZ
                dx * dx + dz * dz <= radiusSquared
            }
            .sortedWith(
                compareBy<FarmPlotPosition> { plot ->
                    val dx = plot.x + 0.5 - machineX
                    val dz = plot.z + 0.5 - machineZ
                    dx * dx + dz * dz
                }.thenBy(FarmPlotPosition::y)
                    .thenBy(FarmPlotPosition::x)
                    .thenBy(FarmPlotPosition::z),
            )
            .toCollection(linkedSetOf())
    }

    private const val MAX_VERTICAL_REACH = 1.75
}
