package ru.ruscrafting.farms.paper.mine.incident

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.*

class MineIncidentCadenceTest : FunSpec({
    test("incidents are spread through the order instead of draining at half quota") {
        (0..2).map { MineIncidentScheduler.threshold(100, it, 3) } shouldBe listOf(25, 50, 75)
        MineIncidentScheduler.threshold(3, 0, 1) shouldBe 2
    }

    test("replayed order retains selection while different shifts vary event order") {
        val order = MineOrder("test", MineIncidentType.entries)
        val rules = MineRules(100, 50, 2, 0, incidentCountMin = 3, incidentCountMax = 3)
        val schedules = (1L..12L).map { sequence ->
            val state = MineShiftState(sequence = sequence)
            val a = MineShiftEngine.start(state, order, rules, 100).state.incidentSchedule
            a shouldBe MineShiftEngine.start(state, order, rules, 100).state.incidentSchedule
            a.size shouldBe 3
            a.distinct().size shouldBe 3
            a
        }
        (schedules.toSet().size > 1) shouldBe true
    }
})
