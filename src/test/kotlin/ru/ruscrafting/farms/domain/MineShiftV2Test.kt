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

    test("basic order mines directly and resumes after its single invasion") {
        val basicRules = rules.copy(miningOnly = true, incidentCountMin = 1, incidentCountMax = 1)
        val basicOrder = MineOrder("basic_ore", listOf(MineIncidentType.CREATURE_NEST))
        var state = MineShiftEngine.start(MineShiftState(), basicOrder, basicRules, 1000).state
        state.phase shouldBe MinePhase.MINING
        state.incidentSchedule shouldBe listOf(MineIncidentType.CREATURE_NEST)
        state = MineShiftEngine.mineTarget(state, basicRules, player).state
        state = MineShiftEngine.startIncident(state, MineIncidentType.CREATURE_NEST, 3, 2000).state
        MineShiftEngine.mineTarget(state, basicRules, player).accepted shouldBe false
        state = MineShiftEngine.workIncident(state, player, 3).state
        state = MineShiftEngine.resolveIncident(state).state
        state.mined shouldBe 1
        state.phase shouldBe MinePhase.MINING
        repeat(2) { state = MineShiftEngine.mineTarget(state, basicRules, player).state }
        state.phase shouldBe MinePhase.EXTRACTION
        state = MineShiftEngine.extract(state, basicRules, player, 3000).state
        state.phase shouldBe MinePhase.COOLDOWN
        MineShiftEngine.extract(state, basicRules, player, 3000).accepted shouldBe false
        MineShiftEngine.tick(state, basicRules, 8000).state.phase shouldBe MinePhase.IDLE
    }

    test("multi ore order completes only after every material quota") {
        val basicRules = rules.copy(miningOnly = true, miningQuota = 3, incidentCountMin = 1, incidentCountMax = 1)
        val basicOrder = MineOrder("mixed_ore", listOf(MineIncidentType.CREATURE_NEST))
        val requirements = linkedMapOf("COAL_ORE" to 2, "IRON_ORE" to 1)
        var state = MineShiftEngine.start(MineShiftState(), basicOrder, basicRules, 1000).state

        state = MineShiftEngine.mineTarget(state, basicRules, player, "COAL_ORE", requirements).state
        state = MineShiftEngine.mineTarget(state, basicRules, player, "COAL_ORE", requirements).state
        state.mined shouldBe 2
        state.minedByMaterial shouldBe mapOf("COAL" to 2)
        MineShiftEngine.mineTarget(state, basicRules, player, "COAL_ORE", requirements).accepted shouldBe false
        state.phase shouldBe MinePhase.MINING

        state = MineShiftEngine.mineTarget(state, basicRules, player, "DEEPSLATE_IRON_ORE", requirements).state
        state.mined shouldBe 3
        state.minedByMaterial shouldBe mapOf("COAL" to 2, "IRON" to 1)
        state.phase shouldBe MinePhase.EXTRACTION
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
