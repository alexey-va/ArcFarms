package ru.ruscrafting.farms.domain.mine.expedition

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MineWorkshopHeat
import kotlin.math.PI

class MineFactoryGeneratorCycleTest : FunSpec({
    val placement = MineExpeditionPlacement("world", 0, 60, 0, 73)

    test("ignition ramps smoothly and integrates crank phase continuously") {
        val state = MineExpeditionState(
            placement,
            MineExpeditionStage.FACTORY_COAL,
            factoryGeneratorStartedAt = 10_000L,
        )

        MineFactoryGeneratorCycle.ready(state, 15_999L) shouldBe false
        MineFactoryGeneratorCycle.ready(state, 16_000L) shouldBe true
        MineFactoryGeneratorCycle.progress(state, 10_000L) shouldBe 0.0
        MineFactoryGeneratorCycle.progress(state, 13_000L) shouldBe 0.5
        MineFactoryGeneratorCycle.progress(state, 16_000L) shouldBe 1.0
        MineFactoryGeneratorCycle.speed(state, 10_000L) shouldBe 0.28
        MineFactoryGeneratorCycle.speed(state, 16_000L) shouldBe 1.0
        MineFactoryGeneratorCycle.phase(state, 10_000L) shouldBe 2.0 * PI

        val samples = (0..60).map { offset -> 10_000L + offset * 100L }
        samples.zipWithNext().forEach { (before, after) ->
            (MineFactoryGeneratorCycle.speed(state, after) >= MineFactoryGeneratorCycle.speed(state, before)) shouldBe true
            (MineFactoryGeneratorCycle.phase(state, after) > MineFactoryGeneratorCycle.phase(state, before)) shouldBe true
        }
        val endPhase = MineFactoryGeneratorCycle.phase(state, 16_000L)
        (kotlin.math.abs(endPhase - 4.56 * PI) < 1.0e-12) shouldBe true
        (kotlin.math.abs(MineFactoryGeneratorCycle.phase(state, 17_000L) - endPhase - 2.0 * PI / 3.0) < 1.0e-12) shouldBe true
    }

    test("legacy JSON stays commissioned and new timestamps survive Gson") {
        val gson = Gson()
        val started = MineExpeditionState(
            placement,
            MineExpeditionStage.FACTORY_COAL,
            factoryGeneratorStartedAt = 12_345L,
        )
        val restored = gson.fromJson(gson.toJson(started), MineExpeditionState::class.java).apply { validate() }
        restored.factoryGeneratorStartedAt shouldBe 12_345L

        val legacyJson = JsonParser.parseString(gson.toJson(started)).asJsonObject.apply {
            remove("factoryGeneratorStartedAt")
        }
        val legacy = gson.fromJson(legacyJson, MineExpeditionState::class.java).apply { validate() }
        legacy.factoryGeneratorStartedAt shouldBe 0L
        MineFactoryGeneratorCycle.ready(legacy, 0L) shouldBe true
        MineFactoryGeneratorCycle.speed(legacy, 0L) shouldBe 1.0
        MineFactoryGeneratorCycle.phase(legacy, 3_000L) shouldBe 2.0 * PI

        shouldThrow<IllegalArgumentException> { started.copy(factoryGeneratorStartedAt = -1L) }
    }

    test("manual start grants one checkpoint and later work waits for startup") {
        val unfinished = MineExpeditionState(placement, MineExpeditionStage.FACTORY_WATER, completed = setOf(0))
        MineFactoryGeneratorCycle.completeStart(unfinished, 1_000L).accepted shouldBe false

        val readyToCrank = unfinished.copy(completed = setOf(0, 1))
        val started = MineFactoryGeneratorCycle.completeStart(readyToCrank, 1_000L)
        started.accepted shouldBe true
        started.state.stage shouldBe MineExpeditionStage.FACTORY_COAL
        started.state.factoryGeneratorStartedAt shouldBe 1_000L
        MineExpeditionEngine.progressDelta(MineIncidentType.DEAD_FACTORY, readyToCrank, started.state) shouldBe 1
        MineFactoryGeneratorCycle.completeStart(started.state, 1_001L).accepted shouldBe false
        MineFactoryGeneratorCycle.completeStart(readyToCrank, 0L).state.factoryGeneratorStartedAt shouldBe 1L

        MineExpeditionEngine.completeTarget(started.state, 0, 6_999L).accepted shouldBe false
        var state = started.state
        for (target in 0..2) {
            state = MineExpeditionEngine.completeTarget(state, target, 7_000L).state
        }
        state.stage shouldBe MineExpeditionStage.FACTORY_HEAT
        state.factoryGeneratorStartedAt shouldBe 1_000L
        state = MineExpeditionEngine.completeFactoryHeat(
            state,
            MineWorkshopHeat(elapsedMillis = MineWorkshopHeat.REQUIRED_MILLIS),
            11_200L,
        ).state
        state.stage shouldBe MineExpeditionStage.FACTORY_POUR
        state.factoryGeneratorStartedAt shouldBe 1_000L
    }
})
