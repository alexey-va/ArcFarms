package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.plusOrMinus
import ru.ruscrafting.farms.domain.worksite.WorksitePosition

class MineWorkshopHeatTest : FunSpec({
    test("unattended open furnace overheats but cannot finish a batch") {
        var heat = MineWorkshopHeat()
        repeat(20) { heat = heat.tick(1_000) }
        heat.temperature shouldBe 100.0
        heat.ready shouldBe false
    }
    test("air control keeps the melt in green until ready without a timed click deadline") {
        var heat = MineWorkshopHeat()
        repeat(200) {
            if (heat.temperature > 76 && heat.airOpen || heat.temperature < 62 && !heat.airOpen) heat = heat.toggleAir()
            heat = heat.tick(50)
        }
        heat.ready shouldBe true
        heat.progress shouldBe 1.0
        heat.tick(60_000) shouldBe heat
    }
    test("thermal simulation is consistent across ordinary visual tick intervals") {
        var fast = MineWorkshopHeat()
        var slow = MineWorkshopHeat()
        repeat(80) { fast = fast.tick(50) }
        repeat(8) { slow = slow.tick(500) }
        fast.temperature shouldBe (slow.temperature plusOrMinus .0001)
        fast.stableMillis shouldBe slow.stableMillis
        MineWorkshopHeat().tick(300_000).temperature shouldBe (36.0 plusOrMinus .0001)
    }
    test("controlled smelt completion rejects premature taps and advances exactly once") {
        val working = MineWorkingState(
            MineWorkingPlacement(WorksitePosition("mine", 0, 1, 0), 0, "workshop"),
            MineWorkingStage.HEAT,
        )
        MineWorkingEngine.completeWorkshopHeat(working, MineWorkshopHeat(), 30_000).accepted shouldBe false
        val ready = MineWorkshopHeat(stableMillis = MineWorkshopHeat.REQUIRED_MILLIS)
        val result = MineWorkingEngine.completeWorkshopHeat(working, ready, 30_000)
        result.accepted shouldBe true
        result.state.stage shouldBe MineWorkingStage.SHIP
        MineWorkingEngine.completeWorkshopHeat(result.state, ready, 30_001).accepted shouldBe false
    }
})
