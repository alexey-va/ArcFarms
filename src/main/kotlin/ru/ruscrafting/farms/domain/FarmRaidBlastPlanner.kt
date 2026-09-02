package ru.ruscrafting.farms.domain

object FarmRaidBlastPlanner {
    fun select(
        fieldPlots: Collection<FarmPlotPosition>,
        center: FarmPointPosition,
        radius: Double,
        limit: Int,
    ): List<FarmPlotPosition> {
        require(radius.isFinite() && radius > 0.0)
        require(limit >= 1)
        val radiusSquared = radius * radius
        return fieldPlots.asSequence()
            .distinct()
            .filter { it.world == center.world }
            .map { plot ->
                val dx = plot.x + 0.5 - center.x
                val dz = plot.z + 0.5 - center.z
                plot to dx * dx + dz * dz
            }
            .filter { (_, distanceSquared) -> distanceSquared <= radiusSquared }
            .sortedWith(compareBy<Pair<FarmPlotPosition, Double>> { it.second }
                .thenBy { it.first.x }.thenBy { it.first.z }.thenBy { it.first.y })
            .take(limit)
            .map(Pair<FarmPlotPosition, Double>::first)
            .toList()
    }
}
