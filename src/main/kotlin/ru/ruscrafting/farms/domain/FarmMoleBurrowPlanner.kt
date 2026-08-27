package ru.ruscrafting.farms.domain

import java.util.ArrayDeque
import java.util.Random

data class FarmMolePassage(
    val x: Int,
    val z: Int,
)

data class FarmMoleBurrowLayout(
    val passages: Set<FarmMolePassage>,
    val start: FarmMolePassage,
    val lair: FarmMolePassage,
    val chambers: Set<FarmMolePassage>,
    val lights: Set<FarmMolePassage>,
    val sideLength: Int,
)

/** Pure, deterministic perfect-maze planner used by the temporary mole burrow. */
object FarmMoleBurrowPlanner {
    fun plan(cells: Int, seed: Long, lightSpacing: Int, chamberCount: Int = 3): FarmMoleBurrowLayout {
        require(cells in 3..11) { "Mole burrow cell count must be in 3..11" }
        require(lightSpacing in 2..16) { "Mole burrow light spacing must be in 2..16" }
        require(chamberCount in 0..8) { "Mole burrow chamber count must be in 0..8" }
        val side = cells * 2 - 1
        val start = FarmMolePassage(0, (cells / 2) * 2)
        val random = Random(seed)
        val visited = linkedSetOf(start)
        val passages = linkedSetOf(start)
        val stack = ArrayDeque<FarmMolePassage>().also { it.addLast(start) }

        while (stack.isNotEmpty()) {
            val current = stack.last()
            val candidates = CELL_STEPS.mapNotNull { (dx, dz) ->
                FarmMolePassage(current.x + dx, current.z + dz).takeIf { next ->
                    next.x in 0 until side && next.z in 0 until side && next.x % 2 == 0 && next.z % 2 == 0 && next !in visited
                }
            }.toMutableList()
            if (candidates.isEmpty()) {
                stack.removeLast()
                continue
            }
            val next = candidates[random.nextInt(candidates.size)]
            visited += next
            passages += FarmMolePassage((current.x + next.x) / 2, (current.z + next.z) / 2)
            passages += next
            stack.addLast(next)
        }

        val distances = distances(passages, start)
        val physicallyDistant = visited.filter { manhattan(start, it) >= side / 2 }.ifEmpty { visited.toList() }
        val lair = physicallyDistant.maxWithOrNull(
            compareBy<FarmMolePassage> { distances[it] ?: -1 }
                .thenBy(FarmMolePassage::x)
                .thenBy(FarmMolePassage::z),
        ) ?: start
        val chamberCandidates = visited.asSequence()
            .filter { it != start && it != lair }
            .filter { manhattan(start, it) >= 4 && manhattan(lair, it) >= 4 }
            .toMutableList()
            .also { java.util.Collections.shuffle(it, random) }
        val chambers = linkedSetOf<FarmMolePassage>()
        chamberCandidates.forEach { candidate ->
            if (chambers.size < chamberCount && chambers.all { manhattan(it, candidate) >= 4 }) chambers += candidate
        }
        if (chambers.size < chamberCount) {
            chamberCandidates.forEach { candidate -> if (chambers.size < chamberCount) chambers += candidate }
        }
        val lights = distances.entries.asSequence()
            .filter { (passage, distance) -> passage == start || passage == lair || distance % lightSpacing == 0 }
            .map(Map.Entry<FarmMolePassage, Int>::key)
            .toCollection(linkedSetOf())
        return FarmMoleBurrowLayout(passages, start, lair, chambers, lights, side)
    }

    fun rotate(layout: FarmMoleBurrowLayout, quarterTurns: Int): FarmMoleBurrowLayout {
        val turns = Math.floorMod(quarterTurns, 4)
        fun rotate(point: FarmMolePassage): FarmMolePassage {
            var x = point.x
            var z = point.z
            repeat(turns) {
                val previousX = x
                x = layout.sideLength - 1 - z
                z = previousX
            }
            return FarmMolePassage(x, z)
        }
        return FarmMoleBurrowLayout(
            passages = layout.passages.mapTo(linkedSetOf(), ::rotate),
            start = rotate(layout.start),
            lair = rotate(layout.lair),
            chambers = layout.chambers.mapTo(linkedSetOf(), ::rotate),
            lights = layout.lights.mapTo(linkedSetOf(), ::rotate),
            sideLength = layout.sideLength,
        )
    }

    private fun distances(
        passages: Set<FarmMolePassage>,
        start: FarmMolePassage,
    ): Map<FarmMolePassage, Int> {
        val result = linkedMapOf(start to 0)
        val queue = ArrayDeque<FarmMolePassage>().also { it.addLast(start) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            CARDINAL_STEPS.forEach { (dx, dz) ->
                val next = FarmMolePassage(current.x + dx, current.z + dz)
                if (next in passages && next !in result) {
                    result[next] = result.getValue(current) + 1
                    queue.addLast(next)
                }
            }
        }
        return result
    }

    private fun manhattan(first: FarmMolePassage, second: FarmMolePassage): Int =
        kotlin.math.abs(first.x - second.x) + kotlin.math.abs(first.z - second.z)

    private val CELL_STEPS = listOf(2 to 0, -2 to 0, 0 to 2, 0 to -2)
    private val CARDINAL_STEPS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
}
