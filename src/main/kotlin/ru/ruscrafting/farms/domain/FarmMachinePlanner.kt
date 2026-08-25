package ru.ruscrafting.farms.domain

import kotlin.math.abs

/** Selects the bounded patch directly reached by livestock-drawn machinery. */
object FarmMachinePlanner {
    fun plotsInWorkingRadius(
        candidates: Collection<FarmPlotPosition>,
        world: String,
        machineX: Double,
        machineY: Double,
        machineZ: Double,
        radius: Double,
    ): Set<FarmPlotPosition> = plotsInWorkingRadius(
        candidates = candidates,
        machines = listOf(FarmMachinePosition(world, machineX, machineY, machineZ)),
        radius = radius,
    )

    fun plotsInWorkingRadius(
        candidates: Collection<FarmPlotPosition>,
        machines: Collection<FarmMachinePosition>,
        radius: Double,
    ): Set<FarmPlotPosition> {
        require(machines.size in 1..5) { "Farm machine must have 1..5 working animals" }
        require(radius.isFinite() && radius in 1.0..16.0) { "Farm machine working radius must be in 1..16" }
        require(candidates.size <= MAX_FARM_PATCH_PLOTS) { "Farm machine patch is unbounded" }
        val radiusSquared = radius * radius
        return candidates.asSequence()
            .filter { plot ->
                machines.any { machine ->
                    if (plot.world != machine.world || abs(plot.y + 1.0 - machine.y) > MAX_VERTICAL_REACH) {
                        return@any false
                    }
                    val dx = plot.x + 0.5 - machine.x
                    val dz = plot.z + 0.5 - machine.z
                    dx * dx + dz * dz <= radiusSquared
                }
            }
            .sortedWith(
                compareBy<FarmPlotPosition> { plot ->
                    machines.minOf { machine ->
                        val dx = plot.x + 0.5 - machine.x
                        val dz = plot.z + 0.5 - machine.z
                        dx * dx + dz * dz
                    }
                }.thenBy(FarmPlotPosition::y)
                    .thenBy(FarmPlotPosition::x)
                    .thenBy(FarmPlotPosition::z),
            )
            .toCollection(linkedSetOf())
    }

    private const val MAX_VERTICAL_REACH = 1.75
}
