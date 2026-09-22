package ru.ruscrafting.farms.domain.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.MineIncidentType

class MineLastDescentEngineV4Test : FunSpec({
    val placement = MineExpeditionPlacement("rc_arcfarms_expeditions", 0, 0, 0, 73L, geometryVersion = 4)

    test("modern descent completes twelve credits with one power transfer and a return ride") {
        val plan = MineExpeditionGenerator.plan(MineExpeditionKind.LAST_DESCENT, placement.seed, 4)
        var state = MineExpeditionEngine.initial(MineIncidentType.LAST_DESCENT, placement)

        state = MineExpeditionEngine.advanceMotion(state, MineExpeditionMotion.steps(plan, state), 64).state
        state.stage shouldBe MineExpeditionStage.DESCENT_COUNTERWEIGHTS
        (0..2).forEach { target -> state = MineExpeditionEngine.completeTarget(state, target, 100L).state }
        state.stage shouldBe MineExpeditionStage.DESCENT_POWER_CELLS

        val power = MineExpeditionEngine.completeTarget(state, 0, 101L)
        power.accepted shouldBe true
        MineExpeditionEngine.progressDelta(MineIncidentType.LAST_DESCENT, state, power.state) shouldBe 3
        state = power.state
        state.stage shouldBe MineExpeditionStage.DESCENT_BOTTOM

        state = MineExpeditionEngine.advanceMotion(state, MineExpeditionMotion.steps(plan, state), 64).state
        state.stage shouldBe MineExpeditionStage.DESCENT_CORE_VALVES
        (0..2).forEach { target -> state = MineExpeditionEngine.completeTarget(state, target, 102L).state }
        state.stage shouldBe MineExpeditionStage.DESCENT_ENGINE
        MineExpeditionEngine.action(state) shouldBe MineExpeditionAction.MOTION

        val finished = MineExpeditionEngine.advanceMotion(state, MineExpeditionMotion.steps(plan, state), 64)
        finished.accepted shouldBe true
        finished.finished shouldBe true
        finished.state.stage shouldBe MineExpeditionStage.COMPLETE
        MineExpeditionEngine.progress(MineIncidentType.LAST_DESCENT, finished.state) shouldBe 12
    }

    test("modern objectives are ordered and legacy engine keeps its target action") {
        val plan = MineExpeditionGenerator.plan(MineExpeditionKind.LAST_DESCENT, placement.seed, 4)
        val modern = MineExpeditionEngine.initial(MineIncidentType.LAST_DESCENT, placement)
            .copy(stage = MineExpeditionStage.DESCENT_COUNTERWEIGHTS)
        MineExpeditionObjectives.targets(plan, modern, plan.stations.getValue("lift_middle"))
            .map { it.id to it.interaction } shouldBe listOf(
                "counterweight_0" to MineExpeditionInteraction.BREAK,
            )
        MineExpeditionEngine.completeTarget(modern, 1, 100L).accepted shouldBe false
        val afterBrake = MineExpeditionEngine.completeTarget(modern, 0, 100L).state
        MineExpeditionObjectives.targets(plan, afterBrake, plan.stations.getValue("lift_middle"))
            .map { it.id to it.interaction } shouldBe listOf("counterweight_1" to MineExpeditionInteraction.CRANK)

        val legacyPlacement = placement.copy(geometryVersion = 3)
        val legacy = MineExpeditionEngine.initial(MineIncidentType.LAST_DESCENT, legacyPlacement)
            .copy(stage = MineExpeditionStage.DESCENT_ENGINE)
        MineExpeditionEngine.action(legacy) shouldBe MineExpeditionAction.TARGET
        MineExpeditionEngine.targetCount(legacy) shouldBe 1
    }

    test("modern pump start uses the six second operation owner") {
        MineFactoryOperation(MineExpeditionStage.DESCENT_CORE_VALVES, 0L).duration shouldBe 6_000L
    }
})
