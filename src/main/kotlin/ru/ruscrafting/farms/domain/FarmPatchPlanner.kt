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
        require(targetSize in 1..512) { "Farm patch target must be in 1..512" }
        require(maxSize in targetSize..512) { "Farm patch maximum must be between target size and 512" }
        val available = candidates.asSequence()
            .filter { it.world == anchor.world }
            .distinct()
            .toMutableSet()
        if (available.isEmpty()) return emptyList()

        val components = mutableListOf<Set<FarmPlotPosition>>()
        while (available.isNotEmpty()) {
            val start = available.minWith(POSITION_ORDER)
            val component = linkedSetOf<FarmPlotPosition>()
            val queue = ArrayDeque<FarmPlotPosition>()
            available.remove(start)
            queue.add(start)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                component += current
                neighbors(current).forEach { neighbor ->
                    if (available.remove(neighbor)) queue.add(neighbor)
                }
            }
            components += component
        }

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

    fun expand(
        candidates: Collection<FarmPlotPosition>,
        currentPatch: Collection<FarmPlotPosition>,
        maxSize: Int,
    ): List<FarmPlotPosition> {
        require(maxSize in 1..512) { "Farm patch maximum must be in 1..512" }
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

    private fun neighbors(position: FarmPlotPosition): Sequence<FarmPlotPosition> = sequence {
        IRRIGATED_ROW_OFFSETS.forEach { (dx, dz) ->
            val x = position.x.toLong() + dx
            val z = position.z.toLong() + dz
            if (x in -30_000_000L..30_000_000L && z in -30_000_000L..30_000_000L) {
                yield(FarmPlotPosition(position.world, x.toInt(), position.y, z.toInt()))
            }
        }
    }

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
