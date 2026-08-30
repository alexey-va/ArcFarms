package ru.ruscrafting.farms.paper.mine.extraction

import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import java.util.ArrayDeque
import kotlin.math.abs

internal class MineExtractionRoute(val samples: List<WorksitePosition>) {
    init {
        require(samples.size >= 2) { "Mine extraction route needs at least two samples" }
        require(samples.map(WorksitePosition::world).distinct().size == 1) { "Mine extraction route crosses worlds" }
        samples.zipWithNext().forEach { (from, to) ->
            require(adjacent(from, to)) { "Mine extraction route contains an unsafe step: $from -> $to" }
        }
    }

    val finalIndex: Int get() = samples.lastIndex
    fun sample(index: Int): WorksitePosition = samples[index.coerceIn(samples.indices)]

    companion object {
        fun fromAnchors(anchors: Collection<WorksitePosition>, maxSamples: Int = 128): MineExtractionRoute? {
            require(maxSamples in 2..1_024)
            val unique = anchors.distinct().sortedWith(compareBy(WorksitePosition::world, WorksitePosition::x, WorksitePosition::y, WorksitePosition::z))
            if (unique.size < 2 || unique.map(WorksitePosition::world).distinct().size != 1) return null
            val positions = unique.toHashSet()
            val largest = components(unique, positions).maxByOrNull { it.size }.orEmpty()
            if (largest.size < 2) return null
            val first = farthest(largest.first(), largest.toHashSet()).first
            val (last, parents) = farthest(first, largest.toHashSet())
            val path = generateSequence(last) { parents[it] }.toList().asReversed()
            if (path.size < 2) return null
            return MineExtractionRoute(path.take(maxSamples))
        }

        private fun components(all: List<WorksitePosition>, remaining: MutableSet<WorksitePosition>): List<List<WorksitePosition>> = buildList {
            all.forEach { seed ->
                if (!remaining.remove(seed)) return@forEach
                val component = mutableListOf<WorksitePosition>()
                val queue = ArrayDeque<WorksitePosition>().also { it += seed }
                while (queue.isNotEmpty()) {
                    val current = queue.removeFirst()
                    component += current
                    neighbours(current).forEach { next -> if (remaining.remove(next)) queue += next }
                }
                add(component)
            }
        }

        private fun farthest(
            start: WorksitePosition,
            allowed: Set<WorksitePosition>,
        ): Pair<WorksitePosition, Map<WorksitePosition, WorksitePosition>> {
            val queue = ArrayDeque<WorksitePosition>().also { it += start }
            val distance = mutableMapOf(start to 0)
            val parents = mutableMapOf<WorksitePosition, WorksitePosition>()
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                neighbours(current).filter { it in allowed && it !in distance }.forEach { next ->
                    distance[next] = distance.getValue(current) + 1
                    parents[next] = current
                    queue += next
                }
            }
            return distance.maxWith(compareBy<Map.Entry<WorksitePosition, Int>>({ it.value }, { it.key.x }, { it.key.y }, { it.key.z })).key to parents
        }

        private fun neighbours(position: WorksitePosition): Sequence<WorksitePosition> = sequence {
            for (dy in -1..1) for (dx in -2..2) for (dz in -2..2) {
                if (dx == 0 && dy == 0 && dz == 0) continue
                val next = position.copy(x = position.x + dx, y = position.y + dy, z = position.z + dz)
                if (adjacent(position, next)) yield(next)
            }
        }

        private fun adjacent(from: WorksitePosition, to: WorksitePosition): Boolean {
            if (from.world != to.world || abs(from.y - to.y) > 1) return false
            val dx = abs(from.x - to.x)
            val dz = abs(from.z - to.z)
            return dx <= 2 && dz <= 2 && dx * dx + dz * dz <= 4
        }
    }
}
