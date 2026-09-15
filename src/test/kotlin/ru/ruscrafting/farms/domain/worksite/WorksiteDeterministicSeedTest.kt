package ru.ruscrafting.farms.domain.worksite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import ru.ruscrafting.farms.domain.placement.WorksitePlacementPoint

class WorksiteDeterministicSeedTest : FunSpec({
    test("nearby lifecycle sequences produce distinct derived seeds") {
        val values = (1L..64L).map { WorksiteDeterministicSeed.derive(it, 101L) }

        values.toSet() shouldHaveSize values.size
        values.zipWithNext().forEach { (left, right) -> left shouldNotBe right }
    }

    test("semantic salt changes the derived seed") {
        WorksiteDeterministicSeed.derive(42L, 101L) shouldNotBe
            WorksiteDeterministicSeed.derive(42L, 118L)
    }

    test("ordering salt changes a replayable order score") {
        WorksiteDeterministicSeed.orderScore(42L, 101L) shouldBe
            WorksiteDeterministicSeed.orderScore(42L, 101L)
        WorksiteDeterministicSeed.orderScore(42L, 101L) shouldNotBe
            WorksiteDeterministicSeed.orderScore(42L, 118L)
    }

    test("position ranking is replayable and sensitive to every coordinate") {
        val point = WorksitePlacementPoint("world", 12.5, 64.0, -3.5)
        val score = WorksiteDeterministicSeed.positionScore(17L, point)

        WorksiteDeterministicSeed.positionScore(17L, point) shouldBe score
        WorksiteDeterministicSeed.positionScore(18L, point) shouldNotBe score
        WorksiteDeterministicSeed.positionScore(17L, point.copy(world = "other")) shouldNotBe score
        WorksiteDeterministicSeed.positionScore(17L, point.copy(x = 13.5)) shouldNotBe score
        WorksiteDeterministicSeed.positionScore(17L, point.copy(y = 65.0)) shouldNotBe score
        WorksiteDeterministicSeed.positionScore(17L, point.copy(z = -2.5)) shouldNotBe score
    }

    test("grid ranking is replayable and varies by cell") {
        WorksiteDeterministicSeed.gridScore(91L, 4, -7) shouldBe
            WorksiteDeterministicSeed.gridScore(91L, 4, -7)
        WorksiteDeterministicSeed.gridScore(91L, 4, -7) shouldNotBe
            WorksiteDeterministicSeed.gridScore(91L, 5, -7)
    }
})
