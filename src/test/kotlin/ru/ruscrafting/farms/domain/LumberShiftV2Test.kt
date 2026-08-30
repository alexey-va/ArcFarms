package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.UUID

class LumberShiftV2Test : FunSpec({
    val order = LumberOrder(
        id = "oak_contract",
        species = listOf("OAK", "SPRUCE"),
        incidents = listOf(
            LumberIncidentType.WINDTHROW,
            LumberIncidentType.BARK_BEETLES,
            LumberIncidentType.SAW_JAM,
            LumberIncidentType.CONVEYOR_BREAKDOWN,
            LumberIncidentType.FOREST_FIRE,
        ),
    )
    val rules = LumberRules(
        fellingQuota = 2,
        processingQuota = 2,
        processingPerUse = 1,
        cooldownMillis = 1_000,
        skiddingQuota = 1,
        sawingQuota = 2,
        stackingQuota = 1,
        targetMultiplier = 2,
        incidentCountMin = 3,
        incidentCountMax = 5,
    )
    val player = UUID.fromString("00000000-0000-0000-0000-000000000123")

    test("lumber shift keeps foreground progress across a distinct incident schedule") {
        val started = LumberShiftEngine.start(LumberShiftState(), order, rules, now = 1_000L).state

        started.phase shouldBe LumberPhase.FELLING
        started.incidentSchedule.distinct().size shouldBe started.incidentSchedule.size
        started.incidentSchedule.size shouldBe 4
        val interrupted = LumberShiftEngine.startIncident(
            started.copy(felled = 1),
            LumberIncidentType.WINDTHROW,
            required = 3,
        ).state
        interrupted.phase shouldBe LumberPhase.INCIDENT
        interrupted.resumePhase shouldBe LumberPhase.FELLING
        val progressed = LumberShiftEngine.workIncident(interrupted, player).state
        progressed.incident?.progress shouldBe 1
        LumberShiftEngine.resolveIncident(progressed.copy(incident = progressed.incident?.copy(progress = 3))).state.let { resumed ->
            resumed.phase shouldBe LumberPhase.FELLING
            resumed.felled shouldBe 1
        }
    }

    test("main phases advance without resetting accepted progress") {
        var state = LumberShiftEngine.start(LumberShiftState(), order, rules, now = 1_000L).state
        state = LumberShiftEngine.fell(state, rules, "OAK", player, 1_100L).state
        state = LumberShiftEngine.fell(state, rules, "OAK", player, 1_200L).state
        state.phase shouldBe LumberPhase.SKIDDING
        state = LumberShiftEngine.skid(state, rules, player).state
        state.phase shouldBe LumberPhase.SAWING
        state = LumberShiftEngine.saw(state, rules, player).state
        state = LumberShiftEngine.saw(state, rules, player).state
        state.phase shouldBe LumberPhase.STACKING
        state = LumberShiftEngine.stack(state, rules, player).state
        state.phase shouldBe LumberPhase.DISPATCH
        val completed = LumberShiftEngine.dispatch(state, rules, player, now = 2_000L)
        completed.state.phase shouldBe LumberPhase.COOLDOWN
        completed.events shouldContainExactly listOf(LumberShiftEvent.COMPLETED)
    }

    test("legacy active state resets once while a cooldown sequence is retained") {
        LumberStateMigration.migrate(
            LumberShiftState(phase = LumberPhase.PROCESSING, sequence = 7, processed = 1),
        ) shouldBe LumberShiftState(sequence = 7)

        val cooldown = LumberShiftState(
            phase = LumberPhase.COOLDOWN,
            sequence = 9,
            cooldownEndsAt = 20_000,
            outcome = ShiftOutcome.COMPLETED,
        )
        LumberStateMigration.migrate(cooldown) shouldBe cooldown
    }
})
