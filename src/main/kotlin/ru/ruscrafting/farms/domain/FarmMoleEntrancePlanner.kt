package ru.ruscrafting.farms.domain

/** Pure first-pass filter that keeps mole entrances off the indexed field boundary. */
object FarmMoleEntrancePlanner {
    fun preferredBeds(
        candidates: Collection<FarmPlotPosition>,
        minimumBoundaryDistance: Int,
    ): List<FarmPlotPosition> {
        require(minimumBoundaryDistance in 0..64) { "Mole entrance boundary distance must be in 0..64" }
        val beds = candidates.distinct().sortedWith(
            compareBy(FarmPlotPosition::world, FarmPlotPosition::y, FarmPlotPosition::x, FarmPlotPosition::z),
        )
        if (beds.isEmpty() || minimumBoundaryDistance == 0) return beds
        val minX = beds.minOf(FarmPlotPosition::x)
        val maxX = beds.maxOf(FarmPlotPosition::x)
        val minZ = beds.minOf(FarmPlotPosition::z)
        val maxZ = beds.maxOf(FarmPlotPosition::z)
        val requiredX = minimumBoundaryDistance.coerceAtMost((maxX - minX) / 2)
        val requiredZ = minimumBoundaryDistance.coerceAtMost((maxZ - minZ) / 2)
        val boundarySafe = beds.filter { plot ->
            plot.x - minX >= requiredX && maxX - plot.x >= requiredX &&
                plot.z - minZ >= requiredZ && maxZ - plot.z >= requiredZ
        }
        if (boundarySafe.isEmpty()) return boundarySafe
        val safeMinX = boundarySafe.minOf(FarmPlotPosition::x)
        val safeMaxX = boundarySafe.maxOf(FarmPlotPosition::x)
        val safeMinZ = boundarySafe.minOf(FarmPlotPosition::z)
        val safeMaxZ = boundarySafe.maxOf(FarmPlotPosition::z)
        val centralMarginX = (safeMaxX - safeMinX) / 4
        val centralMarginZ = (safeMaxZ - safeMinZ) / 4
        return boundarySafe.filter { plot ->
            plot.x - safeMinX >= centralMarginX && safeMaxX - plot.x >= centralMarginX &&
                plot.z - safeMinZ >= centralMarginZ && safeMaxZ - plot.z >= centralMarginZ
        }.ifEmpty { boundarySafe }
    }
}
