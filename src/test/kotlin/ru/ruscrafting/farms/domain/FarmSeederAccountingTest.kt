package ru.ruscrafting.farms.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class FarmSeederAccountingTest : FunSpec({
    val players = setOf(UUID(0, 1), UUID(0, 2))
    val patch = (0..4).map { FarmPlotPosition("world", it, 64, 0) }
    val horse = FarmCareTarget(0, FarmCareRole.SEEDER_HORSE, FarmPointPosition("world", 0.5, 65.0, 0.5), progress = 1)
    val waypoint = FarmCareTarget(1, FarmCareRole.SEEDER_WAYPOINT, FarmPointPosition("world", 1.5, 65.0, 0.5))
    fun state(stage: FarmSeederStage) = FarmShiftState(
        phase = FarmPhase.CARE,
        careType = FarmCareType.SEEDER,
        seederStage = stage,
        preparationPatch = patch,
        preparationRequired = 4,
        tilledPlots = if (stage == FarmSeederStage.PLANTING) patch.toSet() else setOf(patch[0]),
        plantedPlots = if (stage == FarmSeederStage.PLANTING) setOf(patch[0]) else emptySet(),
        preparationProgress = if (stage == FarmSeederStage.PLANTING) 5 else 1,
        plantingProgress = if (stage == FarmSeederStage.PLANTING) 1 else 0,
        careTargets = listOf(horse, waypoint),
        contributors = players.associateWith { 7 },
    )

    FarmSeederStage.entries.forEach { stage ->
        test("$stage credits only new plots and keeps partial stage state") {
            val before = state(stage)
            val result = FarmShiftEngine.workSeeder(before, setOf(patch[0], patch[2]), players)
            val worked = setOf(patch[0], patch[2])
            result.state shouldBe if (stage == FarmSeederStage.TILLING) {
                before.copy(tilledPlots = worked, preparationProgress = 2, contributors = players.associateWith { 8 })
            } else {
                before.copy(plantedPlots = worked, plantingProgress = 2, contributors = players.associateWith { 8 })
            }
            result.accepted shouldBe true
            result.contribution shouldBe 1
            result.contributionCredits shouldBe players.associateWith { 1 }
            result.events shouldBe listOf(FarmShiftEvent.SEEDER_PROGRESS)
            FarmShiftEngine.workSeeder(result.state, worked, players) shouldBe EngineResult<FarmShiftState, FarmShiftEvent>(result.state, false)
        }
        test("$stage caps credits at quota and finishes the whole patch") {
            val before = state(stage)
            val result = FarmShiftEngine.workSeeder(before, patch.reversed().toSet(), players)
            result.state shouldBe if (stage == FarmSeederStage.TILLING) {
                before.copy(tilledPlots = patch.toSet(), preparationProgress = 5,
                    seederStage = FarmSeederStage.PLANTING, careTargets = listOf(horse),
                    contributors = players.associateWith { 10 })
            } else {
                before.copy(phase = FarmPhase.HARVESTING, plantedPlots = patch.toSet(), plantingProgress = 5,
                    seederStage = null, careTargets = emptyList(), contributors = players.associateWith { 10 })
            }
            result.accepted shouldBe true
            result.contribution shouldBe 3
            result.contributionCredits shouldBe players.associateWith { 3 }
            result.events shouldBe listOf(if (stage == FarmSeederStage.TILLING) FarmShiftEvent.SEEDER_PLANTING_STARTED else FarmShiftEvent.CARE_RESOLVED)
        }
        test("$stage rejects empty work and validates participants and patch boundaries") {
            val before = state(stage)
            FarmShiftEngine.workSeeder(before, emptySet(), players) shouldBe EngineResult<FarmShiftState, FarmShiftEvent>(before, false)
            shouldThrow<IllegalArgumentException> { FarmShiftEngine.workSeeder(before, setOf(patch[1]), emptySet()) }
            shouldThrow<IllegalArgumentException> { FarmShiftEngine.workSeeder(before, setOf(patch[0].copy(x = 99)), players) }
            val inactive = before.copy(phase = FarmPhase.HARVESTING)
            FarmShiftEngine.workSeeder(inactive, emptySet(), emptySet()) shouldBe EngineResult<FarmShiftState, FarmShiftEvent>(inactive, false)
        }
    }
    test("planting rejects untilled plots before awarding contributions") {
        val before = state(FarmSeederStage.PLANTING).copy(tilledPlots = setOf(patch[0]))
        shouldThrow<IllegalArgumentException> { FarmShiftEngine.workSeeder(before, setOf(patch[1]), players) }
    }
})
