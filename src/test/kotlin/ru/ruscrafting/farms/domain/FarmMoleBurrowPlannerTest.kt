package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.util.ArrayDeque

class FarmMoleBurrowPlannerTest : FunSpec({
    test("planner creates a deterministic connected maze with a distant lair") {
        val first = FarmMoleBurrowPlanner.plan(cells = 7, seed = 42L, lightSpacing = 5)
        val second = FarmMoleBurrowPlanner.plan(cells = 7, seed = 42L, lightSpacing = 5)

        first shouldBe second
        first.passages.size shouldBe 97
        reachable(first.passages, first.start) shouldBe first.passages
        (manhattan(first.start, first.lair) >= first.sideLength / 2) shouldBe true
        first.chambers.size shouldBe 3
        first.chambers.none { manhattan(first.start, it) < 4 || manhattan(first.lair, it) < 4 } shouldBe true
        first.lights shouldContain first.start
        first.lights shouldContain first.lair
    }

    test("quarter turns preserve every passage and keep the entrance on an edge") {
        val layout = FarmMoleBurrowPlanner.plan(cells = 5, seed = 7L, lightSpacing = 4)

        (0..3).forEach { turns ->
            val rotated = FarmMoleBurrowPlanner.rotate(layout, turns)
            rotated.passages.size shouldBe layout.passages.size
            reachable(rotated.passages, rotated.start) shouldBe rotated.passages
            rotated.chambers.size shouldBe layout.chambers.size
            val last = rotated.sideLength - 1
            (rotated.start.x == 0 || rotated.start.x == last || rotated.start.z == 0 || rotated.start.z == last) shouldBe true
        }
        FarmMoleBurrowPlanner.rotate(layout, 4) shouldBe layout
    }

    test("widening creates two-block corridors without merging maze walls") {
        val logical = FarmMoleBurrowPlanner.plan(cells = 7, seed = 42L, lightSpacing = 5)
        val wide = FarmMoleBurrowPlanner.widen(logical, width = 2)

        wide.sideLength shouldBe logical.sideLength * 2
        wide.passages.size shouldBe logical.passages.size * 4
        reachable(wide.passages, wide.start) shouldBe wide.passages
        logical.passages.forEach { point ->
            val expanded = buildSet {
                repeat(2) { dx -> repeat(2) { dz -> add(FarmMolePassage(point.x * 2 + dx, point.z * 2 + dz)) } }
            }
            wide.passages.containsAll(expanded) shouldBe true
        }
        // This logical wall remains two blocks thick instead of being swallowed
        // by widening both neighboring corridors around their old coordinates.
        val wall = (0 until logical.sideLength).asSequence().flatMap { x ->
            (0 until logical.sideLength).asSequence().map { z -> FarmMolePassage(x, z) }
        }.first { candidate -> candidate !in logical.passages }
        repeat(2) { dx ->
            repeat(2) { dz ->
                (FarmMolePassage(wall.x * 2 + dx, wall.z * 2 + dz) !in wide.passages) shouldBe true
            }
        }
    }

    test("designer decoration layers the cave without blocking entrance or lair") {
        val layout = FarmMoleBurrowPlanner.plan(cells = 7, seed = 91L, lightSpacing = 5)
        val open = buildSet {
            addAll(layout.passages)
            layout.chambers.forEach { center ->
                for (dx in -1..1) for (dz in -1..1) add(FarmMolePassage(center.x + dx, center.z + dz))
            }
            for (dx in -2..2) for (dz in -2..2) add(FarmMolePassage(layout.lair.x + dx, layout.lair.z + dz))
        }
        val first = FarmMoleBurrowDecorationPlanner.plan(
            open, layout.start, layout.lair, layout.chambers, tunnelHeight = 3, seed = 91L, accentPercent = 35,
        )
        val second = FarmMoleBurrowDecorationPlanner.plan(
            open, layout.start, layout.lair, layout.chambers, tunnelHeight = 3, seed = 91L, accentPercent = 35,
        )

        first shouldBe second
        open.forEach { point ->
            first.any { it.position == point && it.yOffset == -1 } shouldBe true
            first.any { it.position == point && it.yOffset == 3 } shouldBe true
        }
        first.none { decoration ->
            decoration.position in open && decoration.yOffset in 0..1 &&
                decoration.material in setOf("AMETHYST_BLOCK", "CALCITE", "POINTED_DRIPSTONE")
        } shouldBe true
        first.count { it.material == "SPORE_BLOSSOM" } shouldBe layout.chambers.size
        first.any { it.material == "AMETHYST_BLOCK" || it.material == "CALCITE" } shouldBe true
        (first.size < 2_500) shouldBe true
    }


    test("mole entrances prefer the indexed field interior and adapt to narrow fields") {
        val square = buildList {
            for (x in 0..40) for (z in 0..40) add(FarmPlotPosition("sp11", x, 64, z))
        }
        val interior = FarmMoleEntrancePlanner.preferredBeds(square, minimumBoundaryDistance = 10)

        interior.minOf(FarmPlotPosition::x) shouldBe 10
        interior.maxOf(FarmPlotPosition::x) shouldBe 30
        interior.minOf(FarmPlotPosition::z) shouldBe 10
        interior.maxOf(FarmPlotPosition::z) shouldBe 30

        val narrow = buildList {
            for (x in 0..40) for (z in 0..6) add(FarmPlotPosition("sp11", x, 64, z))
        }
        val narrowInterior = FarmMoleEntrancePlanner.preferredBeds(narrow, minimumBoundaryDistance = 10)
        narrowInterior.minOf(FarmPlotPosition::x) shouldBe 10
        narrowInterior.maxOf(FarmPlotPosition::x) shouldBe 30
        narrowInterior.map(FarmPlotPosition::z).toSet() shouldBe setOf(3)

        val forcedAdminStarts = (1L..8L).map { placementSequence ->
            val salt = FarmSpatialSeed.mix(placementSequence, FarmCareType.MOLES.ordinal * 17L + 101L)
            FarmCarePlanner.spread(interior, 12, salt xor 0x4D4F4C45L).first()
        }
        forcedAdminStarts.distinct().size shouldBeGreaterThan 1
    }
})

private fun manhattan(first: FarmMolePassage, second: FarmMolePassage): Int =
    kotlin.math.abs(first.x - second.x) + kotlin.math.abs(first.z - second.z)

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
