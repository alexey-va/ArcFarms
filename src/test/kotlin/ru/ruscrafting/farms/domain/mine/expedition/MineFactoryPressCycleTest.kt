package ru.ruscrafting.farms.domain.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.PI

class MineFactoryPressCycleTest : FunSpec({
    test("billet reaches the press before the stroke starts") {
        MineFactoryPressCycle.transferPhase(2_000L) shouldBe PI
        MineFactoryPressCycle.strokePhase(2_000L) shouldBe 0.0
        MineFactoryPressCycle.transferPhase(4_000L) shouldBe PI * 2
        MineFactoryPressCycle.strokePhase(4_000L) shouldBe 0.0
        MineFactoryPressCycle.transferPhase(5_200L) shouldBe PI * 2
        MineFactoryPressCycle.strokePhase(5_200L) shouldBe PI
        MineFactoryPressCycle.strokePhase(6_400L) shouldBe PI * 2
        MineFactoryPressCycle.TOTAL_MILLIS shouldBe 6_400L
    }
})
