package ru.ruscrafting.farms.paper.mine.incident.rescue

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.domain.worksite.WorksitePosition

class MineLostMinerMazePlannerTest : FunSpec({
    test("large rescues vary by seed and keep sparse separated lights") {
        val layouts = (1L..16L).map { seed ->
            val layout = MineLostMinerMazePlanner.plan(18, seed)
            val cave = layout.copy(passages = MineLostMinerMazePlanner.chamberCells(layout, seed))
            MineLostMinerMazePlanner.path(cave).size shouldBeGreaterThan 60
            val lamps = MineLostMinerMazePlanner.lampCells(layout).toList()
            (lamps.size <= 6) shouldBe true
            lamps.all { it in layout.passages } shouldBe true
            layout
        }
        (layouts.map { it.passages }.distinct().size >= 12) shouldBe true
    }

    test("rescue cave keeps a long walk after rooms are widened") {
        val layout = MineLostMinerMazePlanner.plan(cells = 18, seed = 42L)
        val cave = layout.copy(passages = MineLostMinerMazePlanner.chamberCells(layout, 42L))
        MineLostMinerMazePlanner.path(cave).size shouldBeGreaterThan 70
    }

    test("same sequence and target produce a replayable perfect maze") {
        val first = MineLostMinerMazePlanner.plan(cells = 6, seed = 42L)
        val second = MineLostMinerMazePlanner.plan(cells = 6, seed = 42L)

        first shouldBe second
        first.passages shouldHaveSize (2 * 6 * 6 - 1)
        first.passages shouldContain first.start
        first.passages shouldContain first.target
        MineLostMinerMazePlanner.path(first).size shouldBeGreaterThan 1
    }

    test("site candidates stay outside the mine footprint and use its bounds") {
        val bounds = CuboidBounds(0, 50, 0, 20, 90, 20)
        val target = WorksitePosition("world", 10, 63, 10)

        val candidates = MineLostMinerMazeSitePlanner.candidates(bounds, target, cells = 5, margin = 2)

        candidates shouldHaveSize 4
        candidates.all { it.y == target.y } shouldBe true
        candidates.all { it.x !in bounds.minX..bounds.maxX || it.z !in bounds.minZ..bounds.maxZ } shouldBe true
    }
})
