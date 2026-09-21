package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.worksite.WorksitePosition

class MineWorkshopHeatTest : FunSpec({
    test("idle furnace does not advance before the start click") {
        val heat = MineWorkshopHeat()
        heat.tick(MineWorkshopHeat.REQUIRED_MILLIS) shouldBe heat
        heat.ready shouldBe false
        heat.progress shouldBe 0.0
    }

    test("automatic run reaches 100 percent and repeated starts do not reset it") {
        var heat = MineWorkshopHeat().start()
        heat.running shouldBe true
        heat.start() shouldBe heat

        heat = heat.tick(2_000)
        heat.progress shouldBe (1.0 / 6.0)
        val elapsed = heat.elapsedMillis
        heat = heat.start()
        heat.elapsedMillis shouldBe elapsed

        repeat(5) { heat = heat.tick(1_000) }
        heat.ready shouldBe true
        heat.running shouldBe false
        heat.progress shouldBe 1.0
        heat.start() shouldBe heat
        heat.tick(60_000) shouldBe heat
    }

    test("a stalled tick is capped to one second but ordinary ticks complete after six seconds") {
        val stalled = MineWorkshopHeat().start().tick(300_000)
        stalled.elapsedMillis shouldBe 1_000L
        stalled.ready shouldBe false

        var ordinary = MineWorkshopHeat().start()
        repeat(6) { ordinary = ordinary.tick(1_000) }
        ordinary.ready shouldBe true
    }

    test("controlled smelt completion rejects premature taps and advances exactly once") {
        val working = MineWorkingState(
            MineWorkingPlacement(WorksitePosition("mine", 0, 1, 0), 0, "workshop"),
            MineWorkingStage.HEAT,
        )
        MineWorkingEngine.completeWorkshopHeat(working, MineWorkshopHeat(), 30_000).accepted shouldBe false
        val ready = MineWorkshopHeat(elapsedMillis = MineWorkshopHeat.REQUIRED_MILLIS)
        val result = MineWorkingEngine.completeWorkshopHeat(working, ready, 30_000)
        result.accepted shouldBe true
        result.state.stage shouldBe MineWorkingStage.SHIP
        MineWorkingEngine.completeWorkshopHeat(result.state, ready, 30_001).accepted shouldBe false
    }
})
