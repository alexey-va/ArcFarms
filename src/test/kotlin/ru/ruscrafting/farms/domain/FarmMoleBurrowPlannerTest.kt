package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.util.ArrayDeque

class FarmMoleBurrowPlannerTest : FunSpec({
    test("planner creates a deterministic connected maze with a distant lair") {
        val first = FarmMoleBurrowPlanner.plan(cells = 7, seed = 42L, lightSpacing = 5)
        val second = FarmMoleBurrowPlanner.plan(cells = 7, seed = 42L, lightSpacing = 5)

        first shouldBe second
        first.passages.size shouldBe 97
        reachable(first.passages, first.start) shouldBe first.passages
        farthest(first.passages, first.start) shouldBe first.lair
        first.lights shouldContain first.start
        first.lights shouldContain first.lair
    }

    test("quarter turns preserve every passage and keep the entrance on an edge") {
        val layout = FarmMoleBurrowPlanner.plan(cells = 5, seed = 7L, lightSpacing = 4)

        (0..3).forEach { turns ->
            val rotated = FarmMoleBurrowPlanner.rotate(layout, turns)
            rotated.passages.size shouldBe layout.passages.size
            reachable(rotated.passages, rotated.start) shouldBe rotated.passages
            val last = rotated.sideLength - 1
            (rotated.start.x == 0 || rotated.start.x == last || rotated.start.z == 0 || rotated.start.z == last) shouldBe true
        }
        FarmMoleBurrowPlanner.rotate(layout, 4) shouldBe layout
    }
})

private fun reachable(passages: Set<FarmMolePassage>, start: FarmMolePassage): Set<FarmMolePassage> {
    val found = linkedSetOf(start)
    val queue = ArrayDeque<FarmMolePassage>().also { it.add(start) }
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).forEach { (dx, dz) ->
            val next = FarmMolePassage(current.x + dx, current.z + dz)
            if (next in passages && found.add(next)) queue.addLast(next)
        }
    }
    return found
}

private fun farthest(passages: Set<FarmMolePassage>, start: FarmMolePassage): FarmMolePassage {
    val distances = linkedMapOf(start to 0)
    val queue = ArrayDeque<FarmMolePassage>().also { it.add(start) }
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).forEach { (dx, dz) ->
            val next = FarmMolePassage(current.x + dx, current.z + dz)
            if (next in passages && next !in distances) {
                distances[next] = distances.getValue(current) + 1
                queue.addLast(next)
            }
        }
    }
    return distances.keys.maxWith(compareBy<FarmMolePassage> { distances.getValue(it) }.thenBy { it.x }.thenBy { it.z })
}
