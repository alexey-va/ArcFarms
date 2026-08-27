package ru.ruscrafting.farms.domain

import kotlin.math.abs

object FarmPatchPlanner {
    fun select(
        candidates: Collection<FarmPlotPosition>,
        anchor: FarmPlotPosition,
        targetSize: Int,
        maxSize: Int = targetSize,
        selectionIndex: Long = 0,
    ): List<FarmPlotPosition> {
        require(targetSize in 1..MAX_FARM_PATCH_PLOTS) {
            "Farm patch target must be in 1..$MAX_FARM_PATCH_PLOTS"
        }
        require(maxSize in targetSize..MAX_FARM_PATCH_PLOTS) {
            "Farm patch maximum must be between target size and $MAX_FARM_PATCH_PLOTS"
        }
        val available = candidates.asSequence()
            .filter { it.world == anchor.world }
            .distinct()
            .toMutableSet()
        if (available.isEmpty()) return emptyList()

        val components = components(available)

        val eligible = components.filter { it.size >= targetSize }.ifEmpty {
            val largestSize = components.maxOf(Set<FarmPlotPosition>::size)
            components.filter { it.size == largestSize }
        }.sortedWith(
            compareBy<Set<FarmPlotPosition>> { component -> component.minWith(POSITION_ORDER).coordinateKey() }
                .thenBy { component -> component.minOf { distanceSquared(it, anchor) } },
        )
        val componentIndex = Math.floorMod(selectionIndex, eligible.size.toLong()).toInt()
        val selectedComponent = eligible[componentIndex]
        if (selectedComponent.size <= maxSize) return selectedComponent.sortedWith(POSITION_ORDER)

        val starts = dispersedStarts(selectedComponent, anchor)
        val startIndex = Math.floorMod(selectionIndex / eligible.size, starts.size.toLong()).toInt()
        val start = starts[startIndex]
        val remaining = selectedComponent.toMutableSet()
        val ordered = ArrayList<FarmPlotPosition>(maxSize)
        val queue = ArrayDeque<FarmPlotPosition>()
        remaining.remove(start)
        queue.add(start)
        while (queue.isNotEmpty() && ordered.size < maxSize) {
            val current = queue.removeFirst()
            ordered += current
            neighbors(current).filter(remaining::remove).sortedWith(POSITION_ORDER).forEach(queue::add)
        }
        return ordered
    }

    /**
     * Selects a large machine-friendly field without pretending that paths and
     * irrigation channels are beds. Nearby same-height components may be
     * combined, while the configured component and plot caps keep the route
     * bounded.
     */
    fun selectMechanized(
        candidates: Collection<FarmPlotPosition>,
        anchor: FarmPlotPosition,
        targetSize: Int,
        maxSize: Int,
        componentGap: Int,
        maxComponents: Int,
        selectionIndex: Long = 0,
    ): List<FarmPlotPosition> {
        require(targetSize in 1..MAX_FARM_PATCH_PLOTS) {
            "Farm machine patch target must be in 1..$MAX_FARM_PATCH_PLOTS"
        }
        require(maxSize in targetSize..MAX_FARM_PATCH_PLOTS) {
            "Farm machine patch maximum must be between target size and $MAX_FARM_PATCH_PLOTS"
        }
        require(componentGap in 0..32) { "Farm machine component gap must be in 0..32" }
        require(maxComponents in 1..16) { "Farm machine component limit must be in 1..16" }
        val available = candidates.asSequence()
            .filter { it.world == anchor.world }
            .distinct()
            .toMutableSet()
        if (available.isEmpty()) return emptyList()

        val allComponents = components(available)
        val seedCandidates = allComponents.sortedWith(
            compareBy<Set<FarmPlotPosition>> { component -> component.minOf { distanceSquared(it, anchor) } }
                .thenByDescending(Set<FarmPlotPosition>::size)
                .thenBy { component -> component.minWith(POSITION_ORDER).coordinateKey() },
        )
        val nearestDistance = seedCandidates.minOf { component -> component.minOf { distanceSquared(it, anchor) } }
        val nearbySeeds = seedCandidates.filter { component ->
            component.minOf { distanceSquared(it, anchor) } <= nearestDistance + MECHANIZED_SEED_DISTANCE_SLACK_SQUARED
        }
        val seed = nearbySeeds[Math.floorMod(selectionIndex, nearbySeeds.size.toLong()).toInt()]
        val selectedComponents = mutableListOf(seed)
        val remaining = allComponents.filterNot(seed::equals).toMutableList()
        val envelopes = allComponents.associateWith(::envelope)
        var selectedSize = seed.size
        while (selectedComponents.size < maxComponents && selectedSize < maxSize) {
            val selectedIndex = ComponentSpatialIndex(selectedComponents.flatten())
            val next = remaining.asSequence()
                .filter { candidate -> candidate.first().y == seed.first().y }
                .mapNotNull { candidate ->
                    selectedIndex.gapWithin(candidate, componentGap)?.let { gap -> candidate to gap }
                }
                .minWithOrNull(
                    compareBy<Pair<Set<FarmPlotPosition>, Int>> { (candidate) ->
                        val candidateEnvelope = envelopes.getValue(candidate)
                        selectedComponents.maxOf { chosen ->
                            envelopes.getValue(chosen).gapTo(candidateEnvelope)
                        }
                    }.thenBy { it.second }
                        .thenByDescending { it.first.size }
                        .thenBy { it.first.minWith(POSITION_ORDER).coordinateKey() },
                )?.first ?: break
            selectedComponents += next
            selectedSize += next.size
            remaining -= next
        }

        val combined = selectedComponents.flatten().distinct()
        if (combined.size <= maxSize) return combined.sortedWith(POSITION_ORDER)
        val start = combined.minWith(
            compareBy<FarmPlotPosition> { distanceSquared(it, anchor) }.then(POSITION_ORDER),
        )
        return boundedFlood(combined.toSet(), start, maxSize)
    }

    fun expand(
        candidates: Collection<FarmPlotPosition>,
        currentPatch: Collection<FarmPlotPosition>,
        maxSize: Int,
    ): List<FarmPlotPosition> {
        require(maxSize in 1..MAX_FARM_PATCH_PLOTS) {
            "Farm patch maximum must be in 1..$MAX_FARM_PATCH_PLOTS"
        }
        val current = currentPatch.distinct()
        if (current.isEmpty()) return emptyList()
        if (current.size >= maxSize) return current.sortedWith(POSITION_ORDER)
        val worlds = current.map(FarmPlotPosition::world).distinct()
        require(worlds.size == 1) { "Current farm patch crosses worlds" }
        val available = candidates.asSequence()
            .filter { it.world == worlds.single() }
            .distinct()
            .filterNot(current::contains)
            .toMutableSet()
        val expanded = current.toMutableSet()
        val queue = ArrayDeque(current.sortedWith(POSITION_ORDER))
        while (queue.isNotEmpty() && expanded.size < maxSize) {
            val position = queue.removeFirst()
            neighbors(position).filter(available::remove).sortedWith(POSITION_ORDER).forEach { neighbor ->
                if (expanded.size < maxSize && expanded.add(neighbor)) queue.add(neighbor)
            }
        }
        return expanded.sortedWith(POSITION_ORDER)
    }

    /** Keeps already-managed plots inside a newly selected patch without exceeding its configured cap. */
    fun retainCurrent(
        currentPatch: Collection<FarmPlotPosition>,
        selectedPatch: Collection<FarmPlotPosition>,
        maxSize: Int,
    ): List<FarmPlotPosition> {
        require(maxSize in 1..MAX_FARM_PATCH_PLOTS) {
            "Farm patch maximum must be in 1..$MAX_FARM_PATCH_PLOTS"
        }
        val retained = currentPatch.distinct()
        if (retained.size >= maxSize) return retained.sortedWith(POSITION_ORDER)
        return buildList(maxSize) {
            addAll(retained)
            selectedPatch.asSequence()
                .filterNot(retained::contains)
                .distinct()
                .take(maxSize - retained.size)
                .forEach(::add)
        }.sortedWith(POSITION_ORDER)
    }

    private fun neighbors(position: FarmPlotPosition): Sequence<FarmPlotPosition> = sequence {
        IRRIGATED_ROW_OFFSETS.forEach { (dx, dz) ->
            val x = position.x.toLong() + dx
            val z = position.z.toLong() + dz
            if (x in -30_000_000L..30_000_000L && z in -30_000_000L..30_000_000L) {
                yield(FarmPlotPosition(position.world, x.toInt(), position.y, z.toInt()))
            }
        }
    }

    private fun components(availablePositions: MutableSet<FarmPlotPosition>): List<Set<FarmPlotPosition>> {
        val components = mutableListOf<Set<FarmPlotPosition>>()
        while (availablePositions.isNotEmpty()) {
            val start = availablePositions.minWith(POSITION_ORDER)
            val component = linkedSetOf<FarmPlotPosition>()
            val queue = ArrayDeque<FarmPlotPosition>()
            availablePositions.remove(start)
            queue.add(start)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                component += current
                neighbors(current).forEach { neighbor ->
                    if (availablePositions.remove(neighbor)) queue.add(neighbor)
                }
            }
            components += component
        }
        return components
    }

    /**
     * Exact bounded Chebyshev lookup without comparing every candidate plot
     * with every already-selected plot. Farm beds can contain thousands of
     * blocks, so the old cross product caused avoidable main-thread spikes.
     */
    private class ComponentSpatialIndex(positions: Collection<FarmPlotPosition>) {
        private val zByX = positions.groupBy(FarmPlotPosition::x)
            .mapValues { (_, plots) -> plots.map(FarmPlotPosition::z).distinct().sorted() }

        fun gapWithin(candidate: Collection<FarmPlotPosition>, maximumGap: Int): Int? {
            val coordinateRadius = maximumGap + 1
            var bestDistance = coordinateRadius + 1
            candidate.forEach { plot ->
                val minX = (plot.x.toLong() - coordinateRadius).coerceAtLeast(-30_000_000L).toInt()
                val maxX = (plot.x.toLong() + coordinateRadius).coerceAtMost(30_000_000L).toInt()
                for (x in minX..maxX) {
                    val zValues = zByX[x] ?: continue
                    val zDistance = nearestDistance(zValues, plot.z)
                    val distance = maxOf(abs(x - plot.x), zDistance)
                    if (distance < bestDistance) bestDistance = distance
                    if (bestDistance <= 1) return 0
                }
            }
            return (bestDistance - 1).takeIf { it <= maximumGap }?.coerceAtLeast(0)
        }

        private fun nearestDistance(sortedValues: List<Int>, target: Int): Int {
            val index = sortedValues.binarySearch(target)
            if (index >= 0) return 0
            val insertion = -index - 1
            val below = sortedValues.getOrNull(insertion - 1)?.let { abs(target - it) } ?: Int.MAX_VALUE
            val above = sortedValues.getOrNull(insertion)?.let { abs(target - it) } ?: Int.MAX_VALUE
            return minOf(below, above)
        }
    }

    private fun boundedFlood(
        positions: Set<FarmPlotPosition>,
        start: FarmPlotPosition,
        limit: Int,
    ): List<FarmPlotPosition> {
        val remaining = positions.toMutableSet()
        val ordered = ArrayList<FarmPlotPosition>(limit)
        val queue = ArrayDeque<FarmPlotPosition>()
        remaining.remove(start)
        queue.add(start)
        while (queue.isNotEmpty() && ordered.size < limit) {
            val current = queue.removeFirst()
            ordered += current
            neighbors(current).filter(remaining::remove).sortedWith(POSITION_ORDER).forEach(queue::add)
            if (queue.isEmpty() && remaining.isNotEmpty() && ordered.size < limit) {
                val next = remaining.minWith(
                    compareBy<FarmPlotPosition> { candidate ->
                        ordered.minOf { chosen -> distanceSquared(candidate, chosen) }
                    }.then(POSITION_ORDER),
                )
                remaining.remove(next)
                queue.add(next)
            }
        }
        return ordered
    }

    private data class ComponentEnvelope(
        val minX: Int,
        val maxX: Int,
        val minZ: Int,
        val maxZ: Int,
    ) {
        fun gapTo(other: ComponentEnvelope): Int {
            val xGap = maxOf(minX - other.maxX - 1, other.minX - maxX - 1, 0)
            val zGap = maxOf(minZ - other.maxZ - 1, other.minZ - maxZ - 1, 0)
            return maxOf(xGap, zGap)
        }
    }

    private fun envelope(component: Set<FarmPlotPosition>) = ComponentEnvelope(
        minX = component.minOf(FarmPlotPosition::x),
        maxX = component.maxOf(FarmPlotPosition::x),
        minZ = component.minOf(FarmPlotPosition::z),
        maxZ = component.maxOf(FarmPlotPosition::z),
    )

    private fun dispersedStarts(
        component: Set<FarmPlotPosition>,
        anchor: FarmPlotPosition,
    ): List<FarmPlotPosition> {
        val limit = minOf(8, component.size)
        val selected = mutableListOf(
            component.maxWith(compareBy<FarmPlotPosition> { distanceSquared(it, anchor) }.then(POSITION_ORDER)),
        )
        while (selected.size < limit) {
            val next = component.asSequence().filterNot(selected::contains).maxWithOrNull(
                compareBy<FarmPlotPosition> { candidate ->
                    selected.minOf { chosen -> distanceSquared(candidate, chosen) }
                }.then(POSITION_ORDER),
            ) ?: break
            selected += next
        }
        return selected
    }

    private fun distanceSquared(left: FarmPlotPosition, right: FarmPlotPosition): Long {
        val dx = abs(left.x.toLong() - right.x)
        val dy = abs(left.y.toLong() - right.y)
        val dz = abs(left.z.toLong() - right.z)
        return dx * dx + dy * dy + dz * dz
    }

    private val POSITION_ORDER = compareBy<FarmPlotPosition>(
        FarmPlotPosition::y,
        FarmPlotPosition::x,
        FarmPlotPosition::z,
        FarmPlotPosition::world,
    )

    private val IRRIGATED_ROW_OFFSETS = listOf(
        1 to 0,
        -1 to 0,
        0 to 1,
        0 to -1,
        2 to 0,
        -2 to 0,
        0 to 2,
        0 to -2,
    )

    private fun FarmPlotPosition.coordinateKey(): String = "$world:$y:$x:$z"

    private const val MECHANIZED_SEED_DISTANCE_SLACK_SQUARED = 12L * 12L
}

object FarmDroughtPlanner {
    fun select(
        candidates: Collection<FarmPlotPosition>,
        firstCenter: FarmPlotPosition,
        targetSize: Int,
        patchCount: Int,
    ): List<List<FarmPlotPosition>> {
        require(targetSize in 1..64) { "Farm drought target must be in 1..64" }
        require(patchCount in 1..8) { "Farm drought patch count must be in 1..8" }
        val available = candidates.asSequence().filter { it.world == firstCenter.world }.distinct().toList()
        if (available.isEmpty()) return emptyList()
        val desired = targetSize.coerceAtMost(available.size)
        val centers = mutableListOf(firstCenter.takeIf(available::contains) ?: available.first())
        while (centers.size < minOf(patchCount, desired)) {
            val next = available.asSequence().filterNot(centers::contains).maxByOrNull { candidate ->
                centers.minOf { center -> horizontalDistanceSquared(candidate, center) }
            } ?: break
            centers += next
        }
        val selected = linkedSetOf<FarmPlotPosition>()
        return centers.mapIndexed { index, center ->
            val quota = desired / centers.size + if (index < desired % centers.size) 1 else 0
            available.asSequence()
                .filterNot(selected::contains)
                .sortedWith(compareBy<FarmPlotPosition> { horizontalDistanceSquared(it, center) }.then(POSITION_ORDER))
                .take(quota)
                .onEach(selected::add)
                .toList()
        }.filter { it.isNotEmpty() }
    }

    private fun horizontalDistanceSquared(first: FarmPlotPosition, second: FarmPlotPosition): Long {
        val dx = first.x.toLong() - second.x
        val dz = first.z.toLong() - second.z
        return dx * dx + dz * dz
    }

    private val POSITION_ORDER = compareBy<FarmPlotPosition>(
        FarmPlotPosition::y,
        FarmPlotPosition::x,
        FarmPlotPosition::z,
        FarmPlotPosition::world,
    )
}
