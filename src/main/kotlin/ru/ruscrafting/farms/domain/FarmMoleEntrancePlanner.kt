package ru.ruscrafting.farms.domain

/** Keeps mole entrances in crop-dense pockets away from the indexed field boundary. */
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
        }.ifEmpty { beds }
        val safeMinX = boundarySafe.minOf(FarmPlotPosition::x)
        val safeMaxX = boundarySafe.maxOf(FarmPlotPosition::x)
        val safeMinZ = boundarySafe.minOf(FarmPlotPosition::z)
        val safeMaxZ = boundarySafe.maxOf(FarmPlotPosition::z)
        val centralMarginX = (safeMaxX - safeMinX) / 4
        val centralMarginZ = (safeMaxZ - safeMinZ) / 4
        val centralBeds = boundarySafe.filter { plot ->
            plot.x - safeMinX >= centralMarginX && safeMaxX - plot.x >= centralMarginX &&
                plot.z - safeMinZ >= centralMarginZ && safeMaxZ - plot.z >= centralMarginZ
        }.ifEmpty { boundarySafe }
        val bedSet = beds.toHashSet()
        val scores = centralBeds.associateWith { plot -> surroundingScore(plot, bedSet) }
        val weakestSideBest = scores.values.maxOf(FarmSurroundingScore::weakestSide)
        val weakestSideRequired = retainThreshold(weakestSideBest)
        val balancedBeds = centralBeds.filter { plot ->
            scores.getValue(plot).weakestSide >= weakestSideRequired
        }
        val totalBest = balancedBeds.maxOf { plot -> scores.getValue(plot).total }
        val totalRequired = retainThreshold(totalBest)
        return balancedBeds.filter { plot ->
            val score = scores.getValue(plot)
            score.total >= totalRequired
        }
    }

    private fun surroundingScore(plot: FarmPlotPosition, beds: Set<FarmPlotPosition>): FarmSurroundingScore {
        var left = 0
        var right = 0
        var back = 0
        var front = 0
        var total = 0
        for (dx in -SURROUNDING_RADIUS..SURROUNDING_RADIUS) {
            for (dz in -SURROUNDING_RADIUS..SURROUNDING_RADIUS) {
                if (dx == 0 && dz == 0) continue
                if (plot.copy(x = plot.x + dx, z = plot.z + dz) !in beds) continue
                total++
                if (dx < 0) left++ else if (dx > 0) right++
                if (dz < 0) back++ else if (dz > 0) front++
            }
        }
        return FarmSurroundingScore(minOf(left, right, back, front), total)
    }

    private fun retainThreshold(best: Int): Int = (best * DENSITY_RETENTION_NUMERATOR + DENSITY_RETENTION_DENOMINATOR - 1) /
        DENSITY_RETENTION_DENOMINATOR

    private data class FarmSurroundingScore(val weakestSide: Int, val total: Int)

    private const val SURROUNDING_RADIUS = 2
    private const val DENSITY_RETENTION_NUMERATOR = 4
    private const val DENSITY_RETENTION_DENOMINATOR = 5
}
