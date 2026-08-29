package ru.ruscrafting.farms.domain

object FarmIncidentPlanner {
    fun sequence(
        configured: Collection<FarmIncidentType>,
        count: Int,
        selectionIndex: Long,
    ): List<FarmIncidentType> {
        require(count in 1..8) { "Farm incident sequence count must be in 1..8" }
        // Food delivery is the closing leg after the harvest, not a random
        // interruption while players are still gathering the order.
        val unique = configured.distinct().filterNot { it == FarmIncidentType.FOOD_DELIVERY }
        require(unique.isNotEmpty()) { "Farm incident sequence has no configured types" }
        val special = rotate(unique.filter(SPECIAL_TYPES::contains), selectionIndex)
        val field = rotate(unique.filterNot(SPECIAL_TYPES::contains), selectionIndex xor 0x51A7L)
        val selected = mutableListOf<FarmIncidentType>()
        // Long orders must still contain one field incident even when the special
        // catalogue grows beyond the configured incident count.
        val specialLimit = if (count >= 5 && field.isNotEmpty()) count - 1 else count
        selected += special.take(minOf(specialLimit, special.size))
        if (selected.size < count) selected += field.take(1)
        if (selected.size < count) {
            selected += rotate(unique.filterNot(selected::contains), selectionIndex xor 0x2D35L)
                .take(count - selected.size)
        }
        return selected.take(minOf(count, unique.size))
    }

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

    fun growDroughtPatches(
        candidates: Collection<FarmPlotPosition>,
        existing: Collection<FarmPlotPosition>,
        targetSize: Int,
        patchCount: Int,
        selectionIndex: Long,
    ): Set<FarmPlotPosition> {
        require(targetSize in 1..64) { "Farm drought target must be in 1..64" }
        require(patchCount in 1..8) { "Farm drought patch count must be in 1..8" }
        val available = candidates.distinct()
        if (available.isEmpty()) return emptySet()
        val retained = existing.filterTo(linkedSetOf(), available::contains)
        if (retained.isEmpty()) {
            return droughtPatches(available, targetSize, patchCount, selectionIndex).flatten().toSet()
        }
        if (retained.size >= targetSize) return retained.take(targetSize).toSet()
        available.asSequence()
            .filterNot(retained::contains)
            .sortedWith(
                compareBy<FarmPlotPosition> { candidate ->
                    retained.minOf { selected -> horizontalDistanceSquared(candidate, selected) }
                }.then(POSITION_ORDER),
            )
            .take(targetSize - retained.size)
            .forEach(retained::add)
        return retained
    }

    fun droughtSpawnLimit(
        required: Int,
        initial: Int,
        growthStep: Int,
        growthIntervalMillis: Long,
        startedAt: Long,
        now: Long,
    ): Int {
        require(required in 1..64) { "Farm drought quota must be in 1..64" }
        require(initial in 1..64) { "Farm drought initial size must be in 1..64" }
        require(growthStep in 1..64) { "Farm drought growth step must be in 1..64" }
        require(growthIntervalMillis in 1_000..600_000) { "Farm drought growth interval is invalid" }
        val elapsed = (now - startedAt).coerceAtLeast(0)
        val growthRounds = elapsed / growthIntervalMillis
        return (initial.toLong() + growthRounds * growthStep).coerceAtMost(required.toLong()).toInt()
    }

    private fun mix(value: Long): Long {
        var mixed = value xor (value ushr 33)
        mixed *= -49064778989728563L
        mixed = mixed xor (mixed ushr 33)
        mixed *= -4265267296055464877L
        return mixed xor (mixed ushr 33)
    }

    private fun <T> rotate(values: List<T>, selectionIndex: Long): List<T> {
        if (values.isEmpty()) return emptyList()
        val offset = Math.floorMod(mix(selectionIndex), values.size.toLong()).toInt()
        return values.drop(offset) + values.take(offset)
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

    private val SPECIAL_TYPES = setOf(
        FarmIncidentType.GIANT_CROP,
        FarmIncidentType.CHANNELS,
        FarmIncidentType.NIGHT_SHIFT,
        FarmIncidentType.MARKET,
        FarmIncidentType.BIRDS,
        FarmIncidentType.FOOD_DELIVERY,
        FarmIncidentType.PROCESSING,
        FarmIncidentType.BARN_FIRE,
    )
}
