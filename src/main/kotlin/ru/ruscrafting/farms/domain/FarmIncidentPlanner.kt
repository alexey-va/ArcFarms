package ru.ruscrafting.farms.domain

object FarmIncidentPlanner {
    fun dispersedCenters(
        candidates: Collection<FarmPlotPosition>,
        count: Int,
        selectionIndex: Long,
    ): List<FarmPlotPosition> {
        require(count in 1..16) { "Farm incident center count must be in 1..16" }
        val available = candidates.distinct().sortedWith(POSITION_ORDER)
        if (available.isEmpty()) return emptyList()
        val firstIndex = Math.floorMod(mix(selectionIndex), available.size.toLong()).toInt()
        val centers = mutableListOf(available[firstIndex])
        while (centers.size < minOf(count, available.size)) {
            val next = available.asSequence().filterNot(centers::contains).maxWithOrNull(
                compareBy<FarmPlotPosition> { candidate ->
                    centers.minOf { center -> horizontalDistanceSquared(candidate, center) }
                }.then(POSITION_ORDER),
            ) ?: break
            centers += next
        }
        return centers
    }

    fun droughtPatches(
        candidates: Collection<FarmPlotPosition>,
        targetSize: Int,
        patchCount: Int,
        selectionIndex: Long,
    ): List<List<FarmPlotPosition>> {
        require(targetSize in 1..64) { "Farm drought target must be in 1..64" }
        require(patchCount in 1..8) { "Farm drought patch count must be in 1..8" }
        val available = candidates.distinct()
        if (available.isEmpty()) return emptyList()
        val centers = dispersedCenters(available, minOf(patchCount, targetSize), selectionIndex)
        val desired = targetSize.coerceAtMost(available.size)
        val selected = linkedSetOf<FarmPlotPosition>()
        return centers.mapIndexed { index, center ->
            val quota = desired / centers.size + if (index < desired % centers.size) 1 else 0
            available.asSequence()
                .filterNot(selected::contains)
                .sortedWith(compareBy<FarmPlotPosition> { horizontalDistanceSquared(it, center) }.then(POSITION_ORDER))
                .take(quota)
                .onEach(selected::add)
                .toList()
        }.filter(List<FarmPlotPosition>::isNotEmpty)
    }

    private fun mix(value: Long): Long {
        var mixed = value xor (value ushr 33)
        mixed *= -49064778989728563L
        mixed = mixed xor (mixed ushr 33)
        mixed *= -4265267296055464877L
        return mixed xor (mixed ushr 33)
    }

    private fun horizontalDistanceSquared(first: FarmPlotPosition, second: FarmPlotPosition): Long {
        val dx = first.x.toLong() - second.x
        val dz = first.z.toLong() - second.z
        return dx * dx + dz * dz
    }

    private val POSITION_ORDER = compareBy<FarmPlotPosition>(
        FarmPlotPosition::world,
        FarmPlotPosition::y,
        FarmPlotPosition::x,
        FarmPlotPosition::z,
    )
}
