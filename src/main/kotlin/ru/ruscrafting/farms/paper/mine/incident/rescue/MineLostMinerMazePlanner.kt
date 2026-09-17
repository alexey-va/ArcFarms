package ru.ruscrafting.farms.paper.mine.incident.rescue

import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.domain.worksite.WorksiteDeterministicSeed
import java.util.ArrayDeque

/** Integral cell used by the temporary lost-miner maze. */
internal data class MineLostMinerMazePoint(val x: Int, val z: Int)

/** A perfect maze represented by walkable floor cells and one deterministic target. */
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
 * Deterministic DFS maze. The two-cell lattice deliberately keeps the owner
 * independent from Bukkit, so a restart reconstructs exactly the same route.
 */
internal object MineLostMinerMazePlanner {
    private val DIRECTIONS = listOf(
        MineLostMinerMazePoint(1, 0),
        MineLostMinerMazePoint(-1, 0),
        MineLostMinerMazePoint(0, 1),
        MineLostMinerMazePoint(0, -1),
    )

    fun plan(cells: Int, seed: Long): MineLostMinerMazeLayout {
        require(cells in 3..11) { "Lost-miner maze cell count must be in 3..11" }
        val startCell = MineLostMinerMazePoint(0, 0)
        val visited = linkedSetOf(startCell)
        val passages = linkedSetOf(center(startCell))
        val stack = ArrayDeque<MineLostMinerMazePoint>().apply { addLast(startCell) }
        while (stack.isNotEmpty()) {
            val current = stack.last()
            val next = DIRECTIONS
                .asSequence()
                .map { direction ->
                    val candidate = MineLostMinerMazePoint(current.x + direction.x, current.z + direction.z)
                    candidate to WorksiteDeterministicSeed.gridScore(seed, candidate.x, candidate.z)
                }
                .filter { (candidate, _) -> candidate.x in 0 until cells && candidate.z in 0 until cells && candidate !in visited }
                .sortedByDescending { (_, score) -> score }
                .map { (candidate, _) -> candidate }
                .firstOrNull()
            if (next == null) {
                stack.removeLast()
                continue
            }
            visited += next
            passages += center(next)
            passages += MineLostMinerMazePoint(
                center(current).x + (next.x - current.x),
                center(current).z + (next.z - current.z),
            )
            stack.addLast(next)
        }
        val start = center(startCell)
        val distances = distances(passages, start)
        val target = passages
            .asSequence()
            .filter { it.x % 2 == 1 && it.z % 2 == 1 }
            .maxWithOrNull(compareBy<MineLostMinerMazePoint> { distances[it] ?: -1 }.thenBy { it.x }.thenBy { it.z })
            ?: start
        return MineLostMinerMazeLayout(cells, passages, start, target)
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

    private fun center(cell: MineLostMinerMazePoint): MineLostMinerMazePoint =
        MineLostMinerMazePoint(cell.x * 2 + 1, cell.z * 2 + 1)

    private fun distances(
        passages: Set<MineLostMinerMazePoint>,
        origin: MineLostMinerMazePoint,
    ): Map<MineLostMinerMazePoint, Int> {
        val result = linkedMapOf(origin to 0)
        val queue = ArrayDeque<MineLostMinerMazePoint>().apply { addLast(origin) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val distance = result.getValue(current) + 1
            DIRECTIONS.map { MineLostMinerMazePoint(current.x + it.x, current.z + it.z) }
                .filter { it in passages && it !in result }
                .forEach { result[it] = distance; queue.addLast(it) }
        }
        return result
    }
}

/**
 * Four perimeter anchors are derived from the configured mine bounds. The
 * anchor denotes the maze entrance; the whole footprint is kept outside the
 * mine by a deterministic clearance, so no production coordinate is baked in.
 */
internal object MineLostMinerMazeSitePlanner {
    fun candidates(
        bounds: CuboidBounds,
        target: WorksitePosition,
        cells: Int,
        margin: Int = 2,
    ): List<WorksitePosition> {
        require(cells in 3..11)
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
