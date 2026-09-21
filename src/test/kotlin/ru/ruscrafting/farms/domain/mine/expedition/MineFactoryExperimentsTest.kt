package ru.ruscrafting.farms.domain.mine.expedition

import com.google.gson.Gson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MineWorkshopHeat

class MineFactoryExperimentsTest : FunSpec({
    val placement = MineExpeditionPlacement("world", 0, 60, 0, 73)
    val all = MineFactoryExperiment.entries.toSet()

    test("normal selection is deterministic, varied, and bounded while forced selection is exact") {
        val first = MineFactoryExperiments.select(73L)
        MineFactoryExperiments.select(73L) shouldBe first
        val selections = (0L..128L).map { MineFactoryExperiments.select(it) }
        selections.any { it.selected.isEmpty() } shouldBe true
        selections.any { it.selected.isNotEmpty() } shouldBe true
        selections.map { it.selected }.toSet().size shouldNotBe 1
        selections.all { it.selected.size <= 2 && it.product in 0..2 } shouldBe true

        MineFactoryExperiments.select(73L, all).selected shouldBe all
    }

    test("pending jobs follow their persisted factory stage and checkpoint") {
        val plan = MineFactoryExperiments.select(73L, all)
        MineFactoryExperiments.pending(
            factoryState(plan, MineExpeditionStage.FACTORY_COAL, completed = setOf(0)),
        ) shouldContainExactly setOf(MineFactoryExperiment.ROCK_JAM)
        MineFactoryExperiments.pending(
            factoryState(plan, MineExpeditionStage.FACTORY_COAL, completed = setOf(0, 1)),
        ) shouldContainExactly setOf(MineFactoryExperiment.ROUTING)
        MineFactoryExperiments.pending(
            factoryState(plan, MineExpeditionStage.FACTORY_HEAT),
        ) shouldContainExactly setOf(MineFactoryExperiment.COOLING)
        MineFactoryExperiments.pending(
            factoryState(plan, MineExpeditionStage.FACTORY_CRANE),
        ) shouldContainExactly setOf(MineFactoryExperiment.MANUAL_CRANE)
        MineFactoryExperiments.pending(
            factoryState(plan, MineExpeditionStage.FACTORY_INSTALL),
        ) shouldContainExactly setOf(MineFactoryExperiment.MOULD)
    }

    test("side resolution is durable, duplicate resolution is rejected, and blocked checkpoints stay blocked") {
        val plan = MineFactoryExperiments.select(73L, all)
        val beforeFuel = factoryState(plan, MineExpeditionStage.FACTORY_COAL)
        MineExpeditionEngine.completeTarget(beforeFuel, 1, 99L).accepted shouldBe false
        MineExpeditionEngine.completeTarget(beforeFuel, 2, 99L).accepted shouldBe false
        val jam = factoryState(plan, MineExpeditionStage.FACTORY_COAL, completed = setOf(0))
        MineExpeditionEngine.completeTarget(jam, 1, 100L).accepted shouldBe false
        val resolved = MineFactoryExperiments.resolve(jam, MineFactoryExperiment.ROCK_JAM, 100L)
        resolved.accepted shouldBe true
        resolved.state.factoryExperiments!!.resolved shouldContain MineFactoryExperiment.ROCK_JAM
        MineFactoryExperiments.resolve(resolved.state, MineFactoryExperiment.ROCK_JAM, 101L).accepted shouldBe false
        MineExpeditionEngine.completeTarget(resolved.state, 1, 101L).accepted shouldBe true

        val cases = listOf(
            MineFactoryExperiment.ROUTING to factoryState(plan, MineExpeditionStage.FACTORY_COAL, setOf(0, 1)),
            MineFactoryExperiment.COOLING to factoryState(plan, MineExpeditionStage.FACTORY_HEAT),
            MineFactoryExperiment.MOULD to factoryState(plan, MineExpeditionStage.FACTORY_INSTALL),
        )
        cases.forEach { (_, state) -> MineExpeditionEngine.completeTarget(state, 0, 100L).accepted shouldBe false }
        MineExpeditionEngine.completeTarget(cases.first().second, 2, 100L).accepted shouldBe false
        cases.forEach { (experiment, state) ->
            val opened = MineFactoryExperiments.resolve(state, experiment, 100L)
            opened.accepted shouldBe true
            val completed = when (experiment) {
                MineFactoryExperiment.ROUTING -> {
                    opened.state.stage shouldBe MineExpeditionStage.FACTORY_HEAT
                    MineExpeditionEngine.progressDelta(MineIncidentType.DEAD_FACTORY, state, opened.state) shouldBe 1
                    opened
                }
                MineFactoryExperiment.COOLING -> MineExpeditionEngine.completeFactoryHeat(
                    opened.state,
                    MineWorkshopHeat(elapsedMillis = MineWorkshopHeat.REQUIRED_MILLIS),
                    4_000L,
                )
                MineFactoryExperiment.MOULD -> MineExpeditionEngine.completeTarget(opened.state, 0, 101L)
                else -> error("Unexpected experiment in gate test")
            }
            completed.accepted shouldBe true
        }
    }

    test("manual crane resolution owns the normal landing checkpoint") {
        val plan = MineFactoryExperiments.select(73L, all)
        val state = factoryState(plan, MineExpeditionStage.FACTORY_CRANE)
        val step = MineFactoryExperiments.resolve(state, MineFactoryExperiment.MANUAL_CRANE, 100L)
        step.accepted shouldBe true
        step.state.stage shouldBe MineExpeditionStage.FACTORY_INSTALL
        step.state.completed shouldBe emptySet()
        step.state.factoryExperiments!!.resolved shouldContain MineFactoryExperiment.MANUAL_CRANE
    }

    test("the existing gear loop records drive repair, while omitted gear credits valve 1 atomically") {
        val withGear = factoryState(
            MineFactoryExperiments.select(73L, setOf(MineFactoryExperiment.DRIVE_REPAIR)),
            MineExpeditionStage.FACTORY_WATER,
        )
        val gear = MineExpeditionEngine.completeTarget(withGear, 0, 100L)
        gear.accepted shouldBe true
        gear.state.completed shouldBe setOf(0)
        gear.state.factoryExperiments!!.resolved shouldContain MineFactoryExperiment.DRIVE_REPAIR

        val base = MineExpeditionGenerator.plan(MineExpeditionKind.DEAD_FACTORY, 73L)
        val connected = base.copy(stations = base.stations + mapOf("crusher_feed" to ExpeditionPoint(-4, 5, 6)))
        val withoutGear = factoryState(
            MineFactoryExperiments.select(73L, emptySet()),
            MineExpeditionStage.FACTORY_WATER,
        )
        MineFactoryProgram.targets(connected, withoutGear).single().let {
            it.id shouldBe "water_valve_1"
            it.target shouldBe 1
        }
        MineExpeditionEngine.completeTarget(withoutGear, 2, 99L).accepted shouldBe false
        val valve = MineExpeditionEngine.completeTarget(withoutGear, 1, 100L)
        valve.accepted shouldBe true
        valve.state.completed shouldBe setOf(0, 1)
        MineExpeditionEngine.progressDelta(MineIncidentType.DEAD_FACTORY, withoutGear, valve.state) shouldBe 2
        MineExpeditionEngine.required(MineIncidentType.DEAD_FACTORY) shouldBe 10
    }

    test("all six experiments gate the ordinary flow without changing its ten credits") {
        var state = factoryState(
            MineFactoryExperiments.select(73L, all),
            MineExpeditionStage.FACTORY_WATER,
        )
        var credits = 0

        fun accept(step: MineExpeditionStep) {
            step.accepted shouldBe true
            credits += MineExpeditionEngine.progressDelta(MineIncidentType.DEAD_FACTORY, state, step.state)
            state = step.state
        }

        accept(MineExpeditionEngine.completeTarget(state, 0, 100L))
        accept(MineExpeditionEngine.completeTarget(state, 1, 101L))
        accept(MineExpeditionEngine.completeTarget(state, 2, 102L))
        accept(MineExpeditionEngine.completeTarget(state, 0, 103L))

        MineExpeditionEngine.completeTarget(state, 1, 104L).accepted shouldBe false
        accept(MineFactoryExperiments.resolve(state, MineFactoryExperiment.ROCK_JAM, 105L))
        accept(MineExpeditionEngine.completeTarget(state, 1, 106L))
        MineExpeditionEngine.completeTarget(state, 2, 107L).accepted shouldBe false
        accept(MineFactoryExperiments.resolve(state, MineFactoryExperiment.ROUTING, 108L))
        state.stage shouldBe MineExpeditionStage.FACTORY_HEAT

        MineExpeditionEngine.completeFactoryHeat(
            state,
            MineWorkshopHeat(elapsedMillis = MineWorkshopHeat.REQUIRED_MILLIS),
            4_000L,
        ).accepted shouldBe false
        accept(MineFactoryExperiments.resolve(state, MineFactoryExperiment.COOLING, 110L))
        accept(MineExpeditionEngine.completeFactoryHeat(
            state,
            MineWorkshopHeat(elapsedMillis = MineWorkshopHeat.REQUIRED_MILLIS),
            4_000L,
        ))
        accept(MineExpeditionEngine.completeTarget(state, 0, 112L))

        MineExpeditionEngine.completeTarget(state, 0, 113L).accepted shouldBe false
        accept(MineFactoryExperiments.resolve(state, MineFactoryExperiment.MANUAL_CRANE, 114L))
        MineExpeditionEngine.completeTarget(state, 0, 115L).accepted shouldBe false
        accept(MineFactoryExperiments.resolve(state, MineFactoryExperiment.MOULD, 116L))
        accept(MineExpeditionEngine.completeTarget(state, 0, 117L))

        state.stage shouldBe MineExpeditionStage.COMPLETE
        state.factoryExperiments!!.resolved shouldContainExactly all
        credits shouldBe 10
        MineExpeditionEngine.progress(MineIncidentType.DEAD_FACTORY, state) shouldBe 10
    }

    test("initial keeps experiments only for new dead-factory geometry") {
        val plan = MineFactoryExperiments.select(73L, all)
        MineExpeditionEngine.initial(
            MineIncidentType.DEAD_FACTORY,
            placement,
            factoryExperiments = plan,
        ).factoryExperiments shouldBe plan
        MineExpeditionEngine.initial(
            MineIncidentType.DEAD_FACTORY,
            placement.copy(geometryVersion = 2),
            factoryExperiments = plan,
        ).factoryExperiments shouldBe null
        MineExpeditionEngine.initial(
            MineIncidentType.DRILLING_ARK,
            placement,
            factoryExperiments = plan,
        ).factoryExperiments shouldBe null
    }

    test("experiment state survives JSON and missing legacy field stays null") {
        val plan = MineFactoryExperiments.select(
            73L,
            setOf(MineFactoryExperiment.ROCK_JAM, MineFactoryExperiment.COOLING),
        ).copy(resolved = setOf(MineFactoryExperiment.ROCK_JAM))
        val state = factoryState(plan, MineExpeditionStage.FACTORY_COAL, completed = setOf(0))
        val restored = Gson().fromJson(Gson().toJson(state), MineExpeditionState::class.java)
        restored.validate()
        restored.factoryExperiments shouldBe plan

        val legacy = """{"placement":{"world":"world","originX":0,"originY":60,"originZ":0,"seed":73,"geometryVersion":3},"stage":"FACTORY_WATER","completed":[],"motionStep":0,"branch":0,"heatStartedAt":0,"factoryProgram":0}"""
        val old = Gson().fromJson(legacy, MineExpeditionState::class.java)
        old.validate()
        old.factoryExperiments shouldBe null
        MineExpeditionEngine.completeTarget(old, 0, 100L).accepted shouldBe true
    }
})

private fun factoryState(
    plan: MineFactoryExperimentPlan,
    stage: MineExpeditionStage,
    completed: Set<Int> = emptySet(),
): MineExpeditionState = MineExpeditionState(
    placement = MineExpeditionPlacement("world", 0, 60, 0, 73),
    stage = stage,
    completed = completed,
    heatStartedAt = if (stage == MineExpeditionStage.FACTORY_HEAT) 0L else 0L,
    factoryExperiments = plan,
)
