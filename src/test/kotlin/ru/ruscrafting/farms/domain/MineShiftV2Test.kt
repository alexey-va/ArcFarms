package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class MineShiftV2Test : FunSpec({
    val player = UUID.fromString("00000000-0000-0000-0000-000000000777")
    val order = MineOrder(
        "deep_vein",
        listOf(
            MineIncidentType.CAVE_IN,
            MineIncidentType.GAS_LEAK,
            MineIncidentType.FLOODING,
            MineIncidentType.TRACK_DAMAGE,
            MineIncidentType.CRYSTAL_RESONANCE,
        ),
    )
    val rules = MineRules(
        cartQuota = 4,
        hazardTrigger = 2,
        supportsRequired = 1,
        cooldownMillis = 5_000L,
        prospectingQuota = 2,
        miningQuota = 3,
        loadingQuota = 2,
        incidentCountMin = 3,
        incidentCountMax = 3,
    )

    test("expedition completes every foreground phase and resumes its exact objective") {
        var state = MineShiftEngine.start(MineShiftState(), order, rules, 1_000L).state
        state.phase shouldBe MinePhase.PROSPECTING
        state = MineShiftEngine.prospect(state, rules, player).state
        state = MineShiftEngine.prospect(state, rules, player).state
        state.phase shouldBe MinePhase.MINING

        val interrupted = MineShiftEngine.startIncident(state, MineIncidentType.GAS_LEAK, 2, 2_000L).state
        interrupted.phase shouldBe MinePhase.INCIDENT
        interrupted.resumePhase shouldBe MinePhase.MINING
        val worked = MineShiftEngine.workIncident(interrupted, player, amount = 2).state
        state = MineShiftEngine.resolveIncident(worked).state
        state.phase shouldBe MinePhase.MINING

        repeat(3) { state = MineShiftEngine.mineTarget(state, rules, player).state }
        state.phase shouldBe MinePhase.LOADING
        repeat(2) { state = MineShiftEngine.load(state, rules, player).state }
        state.phase shouldBe MinePhase.EXTRACTION
        state = MineShiftEngine.extract(state, rules, player, 3_000L).state
        state.phase shouldBe MinePhase.COOLDOWN
    }

    test("legacy active mine state resets only when entering V2") {
        MineStateMigration.migrate(MineShiftState(phase = MinePhase.HAZARD, sequence = 4)).let { migrated ->
            migrated.phase shouldBe MinePhase.IDLE
            migrated.sequence shouldBe 4
        }
        val cooldown = MineShiftState(
            phase = MinePhase.COOLDOWN,
            sequence = 5,
            hazardResolved = true,
            cooldownEndsAt = 9_000L,
            outcome = ShiftOutcome.COMPLETED,
        )
        MineStateMigration.migrate(cooldown) shouldBe cooldown
    }
})
