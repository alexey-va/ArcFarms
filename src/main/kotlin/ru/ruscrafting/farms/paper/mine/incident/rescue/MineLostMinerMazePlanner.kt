package ru.ruscrafting.farms.paper.mine.incident.rescue

import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.domain.worksite.WorksiteCoherentNoise
import ru.ruscrafting.farms.domain.worksite.WorksitePosition

/** Integral cell used by the temporary lost-miner cave. */
internal data class MineLostMinerMazePoint(val x: Int, val z: Int)

/** A connected, noise-widened cave route with one deterministic target. */
internal data class MineLostMinerMazeLayout(
    val cells: Int,
    val passages: Set<MineLostMinerMazePoint>,
    val start: MineLostMinerMazePoint,
    val target: MineLostMinerMazePoint,
) {
    val width: Int = cells * 2 + 1
    val height: Int = width
}

/**
 * Organic cave planner. The public type remains MazeLayout for persisted
 * compatibility, but its passages are a connected widened route and chambers,
 * rather than a cobblestone perfect-maze grid.
 */
internal object MineLostMinerMazePlanner {
    private val DIRECTIONS = listOf(
        MineLostMinerMazePoint(1, 0),
        MineLostMinerMazePoint(-1, 0),
        MineLostMinerMazePoint(0, 1),
        MineLostMinerMazePoint(0, -1),
    )

    fun plan(cells: Int, seed: Long): MineLostMinerMazeLayout {
        require(cells in 3..18) { "Lost-miner cave cell count must be in 3..18" }
        if (cells > 11) return windingCave(cells, seed)
        val width = cells * 2 + 1
        val start = MineLostMinerMazePoint(1, 1)
        val target = MineLostMinerMazePoint(width - 2, width - 2)
        val core = mutableListOf(start)
        var current = start
        var step = 0
        while (current != target) {
            val moveX = current.x != target.x
            val moveZ = current.z != target.z
            val chooseX = when {
                !moveX -> false
                !moveZ -> true
                else -> score(seed, step, 17) > 0.5
            }
            current = if (chooseX) {
                current.copy(x = current.x + if (target.x > current.x) 1 else -1)
            } else {
                current.copy(z = current.z + if (target.z > current.z) 1 else -1)
            }
            core += current
            step++
        }

        // Grow a connected, irregular chamber field from the route. Keep the
        // old bounded cell budget for journal and recovery compatibility while
        // using noise-ranked boundary cells instead of a perfect maze.
        val passages = linkedSetOf<MineLostMinerMazePoint>().apply { addAll(core) }
        val targetSize = 2 * cells * cells - 1
        while (passages.size < targetSize) {
            val next = (1 until width - 1).flatMap { x ->
                (1 until width - 1).mapNotNull { z ->
                    val candidate = MineLostMinerMazePoint(x, z)
                    if (candidate in passages || DIRECTIONS.none {
                            MineLostMinerMazePoint(candidate.x + it.x, candidate.z + it.z) in passages
                        }) null
                    else candidate to scoreCell(seed, candidate.x, candidate.z)
                }
            }.maxByOrNull { (_, value) -> value }?.first ?: break
            passages += next
        }
        passages += start
        passages += target
        return MineLostMinerMazeLayout(cells, passages, start, target)
    }

    private fun windingCave(cells: Int, seed: Long): MineLostMinerMazeLayout {
        val last = cells * 2 - 1
        val start = MineLostMinerMazePoint(3, 3)
        val far = last - 3
        val bends = listOf(start, MineLostMinerMazePoint(far, 3),
            MineLostMinerMazePoint(far, last / 2), MineLostMinerMazePoint(3, last / 2),
            MineLostMinerMazePoint(3, far), MineLostMinerMazePoint(far, far))
        val passages = linkedSetOf(start)
        bends.zipWithNext().forEach { (from, to) ->
            var point = from
            while (point != to) {
                point = if (point.x != to.x) point.copy(x = point.x + (to.x - point.x).compareTo(0))
                    else point.copy(z = point.z + (to.z - point.z).compareTo(0))
                passages += point
            }
        }
        // Short dead ends branch into separate pockets; the rock between switchbacks prevents shortcuts.
        listOf(10, 22).forEach { x ->
            val depth = 3 + (score(seed, x, 41) * 2).toInt()
            for (z in 4..3 + depth) passages += MineLostMinerMazePoint(x, z)
        }
        return MineLostMinerMazeLayout(cells, passages, start, bends.last())
    }

    fun path(layout: MineLostMinerMazeLayout): List<MineLostMinerMazePoint> {
        val parents = linkedMapOf<MineLostMinerMazePoint, MineLostMinerMazePoint?>().also { it[layout.start] = null }
        val queue = ArrayDeque<MineLostMinerMazePoint>().apply { addLast(layout.start) }
        while (queue.isNotEmpty() && layout.target !in parents) {
            val current = queue.removeFirst()
            DIRECTIONS.map { MineLostMinerMazePoint(current.x + it.x, current.z + it.z) }
                .filter { it in layout.passages && it !in parents }
                .forEach { next -> parents[next] = current; queue.addLast(next) }
        }
        if (layout.target !in parents) return emptyList()
        return buildList {
            var current: MineLostMinerMazePoint? = layout.target
            while (current != null) {
                add(current)
                current = parents[current]
            }
        }.asReversed()
    }

    fun translated(layout: MineLostMinerMazeLayout, start: MineLostMinerMazePoint): Set<MineLostMinerMazePoint> =
        layout.passages.mapTo(linkedSetOf()) { point ->
            MineLostMinerMazePoint(start.x + point.x - layout.start.x, start.z + point.z - layout.start.z)
        }

    /**
     * Projects the compact recovery journal into a connected cave footprint.
     * The journal keeps its bounded passage budget, while the world gets a
     * route skeleton, a few broad rooms and coherent irregular edges. Expanding
     * every one of the 71 logical cells made the old 13x13 footprint almost
     * solid, which read as a box rather than a cave.
     */
    fun chamberCells(layout: MineLostMinerMazeLayout, seed: Long): Set<MineLostMinerMazePoint> {
        val last = layout.width - 2
        val route = path(layout)
        val expanded = linkedSetOf<MineLostMinerMazePoint>()

        fun inside(point: MineLostMinerMazePoint): Boolean = point.x in 1..last && point.z in 1..last
        fun add(point: MineLostMinerMazePoint) {
            if (inside(point)) expanded += point
        }
        fun neighbours(point: MineLostMinerMazePoint) = DIRECTIONS.map {
            MineLostMinerMazePoint(point.x + it.x, point.z + it.z)
        }

        // The target route is always present. A cardinal shoulder on each
        // route cell keeps the travel lane three blocks wide without filling
        // the whole rectangular candidate footprint.
        route.forEach { point ->
            add(point)
            add(MineLostMinerMazePoint(point.x - 1, point.z))
            add(MineLostMinerMazePoint(point.x + 1, point.z))
            add(MineLostMinerMazePoint(point.x, point.z - 1))
            add(MineLostMinerMazePoint(point.x, point.z + 1))
        }

        // Re-introduce only a bounded connected subset of logical branches.
        // This retains alternate rescue approaches without turning every
        // logical passage into a room.
        val branchBudget = (layout.cells * 3).coerceAtMost(layout.passages.size)
        repeat(branchBudget) {
            val next = layout.passages.asSequence()
                .filter { it !in expanded }
                .filter { candidate -> neighbours(candidate).any { it in expanded } }
                .maxByOrNull { coherent01(seed, it.x * 0.42, 0.0, it.z * 0.42) }
                ?: return@repeat
            add(next)
        }

        // Room centers are spread along the route so adjacent radii do not
        // blur into one giant square. The coherent contour keeps chambers
        // organic while every center remains attached to the route skeleton.
        val centers = route.filterIndexed { index, _ -> index % (if (layout.cells > 11) 16 else 4) == 0 }.take(7)
        centers.forEachIndexed { index, center ->
            val radius = 1 + (coherent01(seed, center.x * 0.31, 1.7, center.z * 0.31) * 3.0).toInt()
            addRoom(expanded, center, radius, seed + index * 31L, last)
        }
        return expanded
    }

    private fun addRoom(
        cells: MutableSet<MineLostMinerMazePoint>,
        center: MineLostMinerMazePoint,
        radius: Int,
        seed: Long,
        last: Int,
    ) {
        for (dx in -radius..radius) for (dz in -radius..radius) {
            val distance = (dx * dx + dz * dz).toDouble()
            val ellipse = radius * radius + 0.75
            val mandatory = kotlin.math.abs(dx) + kotlin.math.abs(dz) <= 1
            val contour = coherent01(seed, (center.x + dx) * 0.37, 2.4, (center.z + dz) * 0.37)
            if (distance <= ellipse && (mandatory || contour > 0.36)) {
                val point = MineLostMinerMazePoint(center.x + dx, center.z + dz)
                if (point.x in 1..last && point.z in 1..last) cells += point
            }
        }
    }

    /** Air height per chamber, inclusive of the player block, in [3, 5]. */
    fun chamberCeiling(seed: Long, point: MineLostMinerMazePoint): Int =
        3 + (coherent01(seed, point.x * 0.34, 4.1, point.z * 0.34) * 3.0).toInt()

    private fun score(seed: Long, index: Int, salt: Int): Double =
        coherent01(seed, index * 0.57, salt * 0.11, 0.0)

    private fun scoreCell(seed: Long, x: Int, z: Int): Double =
        coherent01(seed, x * 0.42, 0.0, z * 0.42)

    private fun coherent01(seed: Long, x: Double, y: Double, z: Double): Double =
        (WorksiteCoherentNoise.sample(seed, x, y, z) + 1.0) * 0.5

}

/**
 * Four perimeter anchors are derived from the configured mine bounds. The
 * anchor denotes the cave entrance; the whole footprint is kept outside the
 * mine by a deterministic clearance, so no production coordinate is baked in.
 */
internal object MineLostMinerMazeSitePlanner {
    fun candidates(
        bounds: CuboidBounds,
        target: WorksitePosition,
        cells: Int,
        margin: Int = 2,
    ): List<WorksitePosition> {
        require(cells in 3..18)
        require(margin >= 1)
        val width = cells * 2 + 1
        val clearance = width + margin
        val centerX = (bounds.minX + bounds.maxX) / 2
        val centerZ = (bounds.minZ + bounds.maxZ) / 2
        return listOf(
            WorksitePosition(target.world, bounds.minX - clearance, target.y, centerZ),
            WorksitePosition(target.world, bounds.maxX + clearance, target.y, centerZ),
            WorksitePosition(target.world, centerX, target.y, bounds.minZ - clearance),
            WorksitePosition(target.world, centerX, target.y, bounds.maxZ + clearance),
        )
    }
}
