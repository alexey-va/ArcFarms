package ru.ruscrafting.farms.domain.mine.expedition

import com.google.gson.Gson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.MineIncidentType

class MineFactoryStartupTest : FunSpec({
    val placement = MineExpeditionPlacement("world", 0, 60, 0, 73)
    val plan = MineExpeditionGenerator.plan(MineExpeditionKind.DEAD_FACTORY, 73L)

    test("all unfinished historical commissioning saves resume on the line and retain remaining credit") {
        for (bits in 0 until 7) {
            val completed = (0..2).filterTo(linkedSetOf()) { bits and (1 shl it) != 0 }
            val saved = MineExpeditionState(placement, MineExpeditionStage.FACTORY_WATER,
                completed = completed, factoryProgram = bits % 3, factoryGeneratorStartedAt = 12_000L)
            val restored = Gson().fromJson(Gson().toJson(saved), MineExpeditionState::class.java)
            val migrated = MineFactoryExperiments.normalizeConnected(restored)
            migrated.completed shouldBe completed
            migrated.factoryGeneratorStartedAt shouldBe 0L
            MineFactoryExperiments.normalizeConnected(migrated) shouldBe migrated
            val target = MineFactoryProgram.targets(plan, migrated).single()
            target.id shouldBe "control_crusher_left"
            target.interaction shouldBe MineExpeditionInteraction.OPERATE
            val started = MineExpeditionEngine.completeTarget(migrated, target.target, 1_000L)
            started.accepted shouldBe true
            started.state.stage shouldBe MineExpeditionStage.FACTORY_COAL
            MineExpeditionEngine.progressDelta(MineIncidentType.DEAD_FACTORY, migrated, started.state) shouldBe 3 - completed.size
        }
    }

    test("old ignition timestamp cannot block restored production or reroll side jobs") {
        val jobs = MineFactoryExperimentPlan(setOf(MineFactoryExperiment.ROCK_JAM), product = 2)
        val saved = MineExpeditionState(placement, MineExpeditionStage.FACTORY_COAL,
            factoryExperiments = jobs, factoryGeneratorStartedAt = 50_000L)
        val migrated = MineFactoryExperiments.normalizeConnected(saved)
        migrated.stage shouldBe saved.stage
        migrated.factoryExperiments shouldBe jobs
        migrated.factoryGeneratorStartedAt shouldBe 0L
        MineExpeditionEngine.completeTarget(migrated, 0, 1_000L).accepted shouldBe true
    }
})
