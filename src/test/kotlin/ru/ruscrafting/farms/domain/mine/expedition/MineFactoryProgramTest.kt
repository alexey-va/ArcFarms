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

    test("connected line variants share the left machine pair and keep water checkpoints ordered") {
        val base = MineExpeditionGenerator.plan(MineExpeditionKind.DEAD_FACTORY, 73)
        val plan = base.copy(stations = base.stations + connectedStations())
        for (program in 0..2) {
            var state = MineExpeditionState(placement, MineExpeditionStage.FACTORY_WATER, factoryProgram = program)
            MineFactoryProgram.targets(plan, state).map { it.id } shouldBe listOf(
                "repair_supply_$program", "crusher_repair",
            )
            MineFactoryProgram.runningMachines(state, emptySet(), plan) shouldBe emptySet()

            state = state.copy(completed = setOf(0))
            MineFactoryProgram.targets(plan, state).single().id shouldBe "water_valve_1"
            MineFactoryProgram.runningMachines(state, emptySet(), plan) shouldBe emptySet()

            state = state.copy(completed = setOf(0, 1))
            MineFactoryProgram.targets(plan, state).single().id shouldBe "generator_flywheel"
            MineFactoryProgram.runningMachines(state, emptySet(), plan) shouldBe setOf("decor_pump_left")
            MineFactoryProgram.runningMachines(state, setOf("control_crusher_left", "generator_flywheel"), plan) shouldBe
                setOf("decor_pump_left")

            state = state.copy(stage = MineExpeditionStage.FACTORY_COAL, completed = emptySet())
            MineFactoryProgram.runningMachines(state, emptySet(), plan) shouldBe
                setOf("decor_pump_left", "decor_crusher_left")
        }
    }

    test("connected charge targets hand the processed checkpoint to the belt") {
        val base = MineExpeditionGenerator.plan(MineExpeditionKind.DEAD_FACTORY, 73)
        val plan = base.copy(stations = base.stations + connectedStations())
        val initial = MineExpeditionState(placement, MineExpeditionStage.FACTORY_COAL)
        MineExpeditionObjectives.targets(plan, initial, null).map { it.id to it.material } shouldBe listOf(
            "fuel_supply" to "RAW_IRON_BLOCK", "crusher_feed" to "RAW_IRON_BLOCK",
        )
        MineExpeditionObjectives.targets(plan, initial.copy(completed = setOf(0)), null)
            .single().id shouldBe "control_crusher_left"
        val transfer = initial.copy(completed = setOf(0, 1))
        MineExpeditionObjectives.targets(plan, transfer, null) shouldBe emptyList()
        MineFactoryProgram.chargeTransferPending(transfer) shouldBe true
        MineFactoryProgram.pressTransferPending(
            plan, transfer.copy(stage = MineExpeditionStage.FACTORY_INSTALL, completed = emptySet()),
        ) shouldBe true
    }

    test("legacy factory geometry keeps the mirrored commissioning and fuel contract") {
        val plan = MineExpeditionGenerator.plan(MineExpeditionKind.DEAD_FACTORY, 73, geometryVersion = 1)
        val water = MineExpeditionState(placement, MineExpeditionStage.FACTORY_WATER, factoryProgram = 1)
        MineFactoryProgram.targets(plan, water).map { it.id } shouldBe listOf(
            "water_valve_0", "control_pump_left", "control_crusher_left",
        )
        MineExpeditionObjectives.targets(plan, water.copy(stage = MineExpeditionStage.FACTORY_COAL), null)
            .map { it.id to it.material } shouldBe listOf(
                "fuel_supply" to "COAL_BLOCK", "furnace_input" to "COAL_BLOCK",
            )
    }
})

private fun connectedStations(): Map<String, ExpeditionPoint> = mapOf(
    "crusher_feed" to ExpeditionPoint(-4, 5, 6),
    "crushed_output" to ExpeditionPoint(4, 5, 6),
    "crusher_repair" to ExpeditionPoint(-4, 5, 8),
    "repair_supply_0" to ExpeditionPoint(-10, 5, 8),
    "repair_supply_1" to ExpeditionPoint(0, 5, 8),
    "repair_supply_2" to ExpeditionPoint(10, 5, 8),
    "control_crusher_left" to ExpeditionPoint(-4, 5, 10),
)
