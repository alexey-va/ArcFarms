package ru.ruscrafting.farms.domain

/** Shared deterministic geometry for field, incident and guidance planners. */
object FarmPlotGeometry {
    fun center(plots: Collection<FarmPlotPosition>): FarmPlotPosition? {
        if (plots.isEmpty()) return null
        val centerX = plots.sumOf(FarmPlotPosition::x).toDouble() / plots.size
        val centerZ = plots.sumOf(FarmPlotPosition::z).toDouble() / plots.size
        return plots.minByOrNull { plot ->
            val dx = plot.x - centerX
            val dz = plot.z - centerZ
            dx * dx + dz * dz
        }
    }

    fun horizontalDistanceSquared(first: FarmPlotPosition, second: FarmPlotPosition): Long {
        val dx = (first.x - second.x).toLong()
        val dz = (first.z - second.z).toLong()
        return dx * dx + dz * dz
    }
}
