package ru.ruscrafting.farms.domain.mine.expedition

import com.google.gson.Gson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MineFactoryProgramTest : FunSpec({
    val placement = MineExpeditionPlacement("world", 0, 60, 0, 73)

    test("each crusher stays running after its startup objective disappears and state is restored") {
        for ((program, side) in listOf(1 to "left", 2 to "right")) {
            val initial = MineExpeditionState(placement, MineExpeditionStage.FACTORY_WATER, factoryProgram = program)
            val control = "control_crusher_$side"
            val crusher = "decor_crusher_$side"
            MineFactoryProgram.runningMachines(initial, emptySet()) shouldBe emptySet()
            MineFactoryProgram.runningMachines(initial, setOf(control)) shouldBe setOf(crusher)
            val started = MineExpeditionEngine.completeTarget(initial, 2, 3_000).state
            val plan = MineExpeditionGenerator.plan(MineExpeditionKind.DEAD_FACTORY, 73)
            MineFactoryProgram.targets(plan, started).none { it.id == control } shouldBe true
            val restored = Gson().fromJson(Gson().toJson(started), MineExpeditionState::class.java)
            MineFactoryProgram.runningMachines(restored, emptySet()) shouldBe setOf(crusher)
        }
    }

    test("factory machines keep running through every later task and stop at completion") {
        for (program in 0..2) {
            var state = MineExpeditionState(placement, MineExpeditionStage.FACTORY_WATER, factoryProgram = program)
            state = (0..2).fold(state) { current, target -> MineExpeditionEngine.completeTarget(current, target, 100).state }
            val stages = mutableListOf<MineExpeditionStage>()
            while (state.stage != MineExpeditionStage.COMPLETE) {
                stages += state.stage
                MineFactoryProgram.runningMachines(state, emptySet()) shouldBe MineFactoryProgram.machines.keys
                val now = if (state.stage == MineExpeditionStage.FACTORY_HEAT) state.heatStartedAt + 4_000 else 100L
                state = MineExpeditionEngine.completeTarget(state, MineExpeditionEngine.currentTarget(state)!!, now).state
            }
            stages.toSet() shouldBe setOf(MineExpeditionStage.FACTORY_COAL, MineExpeditionStage.FACTORY_HEAT,
                MineExpeditionStage.FACTORY_POUR, MineExpeditionStage.FACTORY_CRANE, MineExpeditionStage.FACTORY_INSTALL)
            MineFactoryProgram.runningMachines(state, setOf("control_crusher_left")) shouldBe emptySet()
        }
    }

    test("an unrelated lever or a cancelled startup does not commission a crusher") {
        val state = MineExpeditionState(placement, MineExpeditionStage.FACTORY_WATER, completed = setOf(0), factoryProgram = 1)
        MineFactoryProgram.runningMachines(state, setOf("control_crusher_right")) shouldBe emptySet()
        MineFactoryProgram.runningMachines(state, emptySet()) shouldBe emptySet()
        MineFactoryProgram.runningMachines(state.copy(stage = MineExpeditionStage.ARK_FUEL), setOf("control_crusher_left")) shouldBe emptySet()
    }
})
