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
    val active = MineFactoryExperiments.supported
    val retired = setOf(
        MineFactoryExperiment.MOULD,
        MineFactoryExperiment.ROUTING,
        MineFactoryExperiment.DRIVE_REPAIR,
    )

    test("selection is deterministic and forced values are restricted to the active policy") {
        val first = MineFactoryExperiments.select(73L)
        MineFactoryExperiments.select(73L) shouldBe first
        val selections = (0L..128L).map { MineFactoryExperiments.select(it) }
        selections.any { it.selected.isEmpty() } shouldBe true
        selections.any { it.selected.isNotEmpty() } shouldBe true
        selections.map { it.selected }.toSet().size shouldNotBe 1
        selections.all { plan ->
            plan.selected.all { experiment -> MineFactoryExperiments.isSupported(experiment) } && plan.selected.size <= 2
        } shouldBe true

        MineFactoryExperiments.select(73L, MineFactoryExperiment.entries.toSet()).selected shouldBe active
        MineFactoryExperiments.select(73L, retired).selected shouldBe emptySet()
    }

    test("pending only exposes current experiments even when an old plan contains retired values") {
        val oldPlan = MineFactoryExperimentPlan(
            selected = MineFactoryExperiment.entries.toSet(),
            resolved = retired,
        )
        MineFactoryExperiments.pending(factoryState(oldPlan, MineExpeditionStage.FACTORY_COAL, setOf(0))) shouldContainExactly setOf(MineFactoryExperiment.ROCK_JAM)
        MineFactoryExperiments.pending(factoryState(oldPlan, MineExpeditionStage.FACTORY_COAL, setOf(0, 1))) shouldContainExactly emptySet()
        MineFactoryExperiments.pending(factoryState(oldPlan, MineExpeditionStage.FACTORY_HEAT)) shouldContainExactly setOf(MineFactoryExperiment.COOLING)
        MineFactoryExperiments.pending(factoryState(oldPlan, MineExpeditionStage.FACTORY_CRANE)) shouldContainExactly setOf(MineFactoryExperiment.MANUAL_CRANE)
        MineFactoryExperiments.pending(factoryState(oldPlan, MineExpeditionStage.FACTORY_INSTALL)) shouldContainExactly emptySet()
    }

    test("normalization filters retired values without changing product or ordinary progress") {
        val oldPlan = MineFactoryExperimentPlan(
            selected = setOf(
                MineFactoryExperiment.ROCK_JAM,
                MineFactoryExperiment.MOULD,
                MineFactoryExperiment.DRIVE_REPAIR,
            ),
            resolved = setOf(MineFactoryExperiment.ROCK_JAM, MineFactoryExperiment.MOULD),
            product = 2,
        )
        val state = factoryState(oldPlan, MineExpeditionStage.FACTORY_COAL, setOf(0, 1))
        val normalized = MineFactoryExperiments.normalizeConnected(state)

        normalized.completed shouldBe state.completed
        normalized.factoryExperiments!!.selected shouldBe setOf(MineFactoryExperiment.ROCK_JAM)
        normalized.factoryExperiments!!.resolved shouldBe setOf(MineFactoryExperiment.ROCK_JAM)
        normalized.factoryExperiments!!.product shouldBe 2
        MineFactoryExperiments.normalizeConnected(normalized) shouldBe normalized

        val missing = MineFactoryExperiments.normalizeConnected(
            state.copy(factoryExperiments = null),
        )
        missing.completed shouldBe state.completed
        missing.factoryExperiments shouldBe MineFactoryExperimentPlan()
    }

    test("active side jobs resolve once and keep their ordinary checkpoint ordering") {
        val plan = MineFactoryExperiments.select(73L, active)
        val beforeFuel = factoryState(plan, MineExpeditionStage.FACTORY_COAL)
        MineExpeditionEngine.completeTarget(beforeFuel, 1, 99L).accepted shouldBe false
        MineExpeditionEngine.completeTarget(beforeFuel, 2, 99L).accepted shouldBe false

        val jam = factoryState(plan, MineExpeditionStage.FACTORY_COAL, setOf(0))
        MineExpeditionEngine.completeTarget(jam, 1, 100L).accepted shouldBe false
        val resolvedRock = MineFactoryExperiments.resolve(jam, MineFactoryExperiment.ROCK_JAM, 100L)
        resolvedRock.accepted shouldBe true
        resolvedRock.state.factoryExperiments!!.resolved shouldContain MineFactoryExperiment.ROCK_JAM
        MineFactoryExperiments.resolve(resolvedRock.state, MineFactoryExperiment.ROCK_JAM, 101L).accepted shouldBe false
        MineExpeditionEngine.completeTarget(resolvedRock.state, 1, 101L).accepted shouldBe true

        val heat = factoryState(plan, MineExpeditionStage.FACTORY_HEAT)
        MineExpeditionEngine.completeTarget(heat, 0, 100L).accepted shouldBe false
        val resolvedCooling = MineFactoryExperiments.resolve(heat, MineFactoryExperiment.COOLING, 100L)
        resolvedCooling.accepted shouldBe true
        MineExpeditionEngine.completeFactoryHeat(
            resolvedCooling.state,
            MineWorkshopHeat(elapsedMillis = MineWorkshopHeat.REQUIRED_MILLIS),
            4_000L,
        ).accepted shouldBe true

        val crane = factoryState(plan, MineExpeditionStage.FACTORY_CRANE)
        val resolvedCrane = MineFactoryExperiments.resolve(crane, MineFactoryExperiment.MANUAL_CRANE, 100L)
        resolvedCrane.accepted shouldBe true
        resolvedCrane.state.stage shouldBe MineExpeditionStage.FACTORY_INSTALL
        resolvedCrane.state.factoryExperiments!!.resolved shouldContain MineFactoryExperiment.MANUAL_CRANE
    }

    test("retired saved jobs no longer block their ordinary checkpoints") {
        val oldPlan = MineFactoryExperimentPlan(selected = MineFactoryExperiment.entries.toSet())
        val route = factoryState(oldPlan, MineExpeditionStage.FACTORY_COAL, setOf(0, 1))
        MineFactoryExperiments.pending(route) shouldBe emptySet()
        MineExpeditionEngine.completeTarget(route, 2, 100L).accepted shouldBe true

        val mould = factoryState(oldPlan, MineExpeditionStage.FACTORY_INSTALL)
        MineFactoryExperiments.pending(mould) shouldBe emptySet()
        MineExpeditionEngine.completeTarget(mould, 0, 100L).accepted shouldBe true

        val oldGear = factoryState(
            MineFactoryExperimentPlan(selected = setOf(MineFactoryExperiment.DRIVE_REPAIR)),
            MineExpeditionStage.FACTORY_WATER,
        )
        MineExpeditionEngine.completeTarget(oldGear, 0, 100L).accepted shouldBe false

        val normalizedGear = MineFactoryExperiments.normalizeConnected(oldGear)
        MineFactoryProgram.targets(connectedPlan(), normalizedGear).single().id shouldBe "water_valve_1"
        val valve = MineExpeditionEngine.completeTarget(normalizedGear, 1, 100L)
        valve.accepted shouldBe true
        valve.state.completed shouldBe setOf(0, 1)
        MineExpeditionEngine.progressDelta(MineIncidentType.DEAD_FACTORY, normalizedGear, valve.state) shouldBe 2
    }

    test("active policy keeps the ten-credit ordinary flow") {
        var state = factoryState(
            MineFactoryExperiments.select(73L, active),
            MineExpeditionStage.FACTORY_WATER,
        )
        var credits = 0

        fun accept(step: MineExpeditionStep) {
            step.accepted shouldBe true
            credits += MineExpeditionEngine.progressDelta(MineIncidentType.DEAD_FACTORY, state, step.state)
            state = step.state
        }

        // The removed gear checkpoint is credited together with valve 1.
        accept(MineExpeditionEngine.completeTarget(state, 1, 100L))
        accept(MineExpeditionEngine.completeTarget(state, 2, 101L))
        accept(MineExpeditionEngine.completeTarget(state, 0, 102L))
        accept(MineFactoryExperiments.resolve(state, MineFactoryExperiment.ROCK_JAM, 103L))
        accept(MineExpeditionEngine.completeTarget(state, 1, 104L))
        accept(MineExpeditionEngine.completeTarget(state, 2, 105L))
        accept(MineFactoryExperiments.resolve(state, MineFactoryExperiment.COOLING, 106L))
        accept(MineExpeditionEngine.completeFactoryHeat(
            state,
            MineWorkshopHeat(elapsedMillis = MineWorkshopHeat.REQUIRED_MILLIS),
            4_000L,
        ))
        accept(MineExpeditionEngine.completeTarget(state, 0, 107L))
        accept(MineFactoryExperiments.resolve(state, MineFactoryExperiment.MANUAL_CRANE, 108L))
        accept(MineExpeditionEngine.completeTarget(state, 0, 109L))

        state.stage shouldBe MineExpeditionStage.COMPLETE
        state.factoryExperiments!!.resolved shouldContainExactly active
        credits shouldBe 10
        MineExpeditionEngine.progress(MineIncidentType.DEAD_FACTORY, state) shouldBe 10
    }

    test("retired enum values still deserialize and normalize safely") {
        val oldPlan = MineFactoryExperimentPlan(
            selected = MineFactoryExperiment.entries.toSet(),
            resolved = setOf(MineFactoryExperiment.MOULD, MineFactoryExperiment.ROCK_JAM),
            product = 1,
        )
        val oldState = factoryState(oldPlan, MineExpeditionStage.FACTORY_COAL, setOf(0))
        val restored = Gson().fromJson(Gson().toJson(oldState), MineExpeditionState::class.java)
        restored.validate()
        restored.factoryExperiments!!.selected shouldBe MineFactoryExperiment.entries.toSet()
        MineFactoryExperiments.normalizeConnected(restored).factoryExperiments!!.selected shouldBe active
    }

    test("initial keeps experiment policy only for new dead-factory geometry") {
        val plan = MineFactoryExperiments.select(73L, active)
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
})

private fun factoryState(
    plan: MineFactoryExperimentPlan,
    stage: MineExpeditionStage,
    completed: Set<Int> = emptySet(),
): MineExpeditionState = MineExpeditionState(
    placement = MineExpeditionPlacement("world", 0, 60, 0, 73),
    stage = stage,
    completed = completed,
    factoryExperiments = plan,
)

private fun connectedPlan(): MineExpeditionPlan {
    val base = MineExpeditionGenerator.plan(MineExpeditionKind.DEAD_FACTORY, 73L)
    return base.copy(stations = base.stations + mapOf("crusher_feed" to ExpeditionPoint(-4, 5, 6)))
}
