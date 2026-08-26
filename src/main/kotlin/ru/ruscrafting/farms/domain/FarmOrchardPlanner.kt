package ru.ruscrafting.farms.domain

object FarmOrchardPlanner {
    fun select(
        candidates: Collection<FarmPlotPosition>,
        placementCount: Int,
        minimumSpacing: Double,
        selectionIndex: Long,
    ): List<FarmPlotPosition> {
        require(placementCount in 1..512) { "Apple placement count is invalid" }
        require(minimumSpacing.isFinite() && minimumSpacing >= 0.0) { "Apple target spacing is invalid" }
        val unique = candidates.distinct().sortedWith(compareBy<FarmPlotPosition> { candidate ->
            randomScore(candidate, selectionIndex)
        }.thenBy(FarmPlotPosition::world).thenBy(FarmPlotPosition::x).thenBy(FarmPlotPosition::y).thenBy(FarmPlotPosition::z))
        if (unique.isEmpty()) return emptyList()
        val limit = minOf(placementCount, unique.size)
        val selected = ArrayList<FarmPlotPosition>(limit)
        val selectedSet = hashSetOf<FarmPlotPosition>()
        val minimumSquared = minimumSpacing * minimumSpacing
        unique.forEach { candidate ->
            if (selected.size >= limit) return@forEach
            if (selected.all { existing -> horizontalDistanceSquared(candidate, existing) >= minimumSquared }) {
                selected += candidate
                selectedSet += candidate
            }
        }
        if (selected.size < limit) {
            unique.asSequence().filterNot(selectedSet::contains).take(limit - selected.size).forEach { candidate ->
                selected += candidate
            }
        }
        return selected
    }

    private fun randomScore(position: FarmPlotPosition, selectionIndex: Long): Long {
        var value = selectionIndex xor position.world.hashCode().toLong()
        value = mix(value xor (position.x.toLong() * -7046029254386353131L))
        value = mix(value xor (position.y.toLong() * -4658895280553007687L))
        return mix(value xor (position.z.toLong() * -7723592293110705685L))
    }

    private fun mix(input: Long): Long {
        var value = input
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        return value xor (value ushr 31)
    }

    private fun horizontalDistanceSquared(first: FarmPlotPosition, second: FarmPlotPosition): Double {
        if (first.world != second.world) return Double.POSITIVE_INFINITY
        val dx = (first.x - second.x).toDouble()
        val dz = (first.z - second.z).toDouble()
        return dx * dx + dz * dz
    }
}
