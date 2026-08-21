package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.util.UUID

class LumberAndMineShiftEngineTest : FunSpec({
    val player = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val outsider = UUID.fromString("00000000-0000-0000-0000-000000000002")

    test("lumber order requires the requested species then machine processing") {
        val rules = LumberRules(60_000, fellingQuota = 2, processingQuota = 4, processingPerUse = 2, cooldownMillis = 5_000)
        var state = LumberShiftEngine.start(LumberShiftState(), "OAK", rules, 1_000).state

        LumberShiftEngine.fell(state, rules, "BIRCH", player, 2_000).accepted shouldBe false
        state = LumberShiftEngine.fell(state, rules, "OAK", player, 2_000).state
        val processing = LumberShiftEngine.fell(state, rules, "OAK", player, 3_000)
        processing.state.phase shouldBe LumberPhase.PROCESSING
        processing.events shouldContain ShiftEvent.PHASE_CHANGED

        state = LumberShiftEngine.process(processing.state, rules, player, 4_000).state
        val completed = LumberShiftEngine.process(state, rules, player, 5_000)
        completed.state.phase shouldBe LumberPhase.COOLDOWN
        completed.state.outcome shouldBe ShiftOutcome.COMPLETED
        completed.state.contributors[player] shouldBe 6
    }

    test("mine pauses for supports and only a contributor can extract") {
        val rules = MineRules(90_000, cartQuota = 6, hazardTrigger = 3, supportsRequired = 2, extractionMillis = 10_000, cooldownMillis = 5_000)
        var state = MineShiftEngine.start(MineShiftState(), rules, 1_000).state
        val hazard = MineShiftEngine.mine(state, rules, 3, player, 2_000)
        hazard.state.phase shouldBe MinePhase.HAZARD
        hazard.events shouldContain ShiftEvent.HAZARD_STARTED

        MineShiftEngine.mine(hazard.state, rules, 1, player, 3_000).accepted shouldBe false
        state = MineShiftEngine.stabilize(hazard.state, rules, player, 3_000).state
        val stabilized = MineShiftEngine.stabilize(state, rules, player, 4_000)
        stabilized.state.phase shouldBe MinePhase.MINING
        stabilized.state.hazardResolved shouldBe true

        val extraction = MineShiftEngine.mine(stabilized.state, rules, 3, player, 5_000)
        extraction.state.phase shouldBe MinePhase.EXTRACTION
        extraction.events shouldContain ShiftEvent.EXTRACTION_STARTED
        MineShiftEngine.extract(extraction.state, rules, outsider, 6_000).accepted shouldBe false

        val completed = MineShiftEngine.extract(extraction.state, rules, player, 6_000)
        completed.accepted shouldBe true
        completed.state.phase shouldBe MinePhase.COOLDOWN
        completed.state.outcome shouldBe ShiftOutcome.COMPLETED
    }

    test("mine extraction deadline fails safely") {
        val rules = MineRules(90_000, cartQuota = 2, hazardTrigger = 1, supportsRequired = 1, extractionMillis = 5_000, cooldownMillis = 5_000)
        var state = MineShiftEngine.start(MineShiftState(), rules, 1_000).state
        state = MineShiftEngine.mine(state, rules, 1, player, 2_000).state
        state = MineShiftEngine.stabilize(state, rules, player, 3_000).state
        state = MineShiftEngine.mine(state, rules, 1, player, 4_000).state

        val failed = MineShiftEngine.tick(state, rules, 9_000)
        failed.state.phase shouldBe MinePhase.COOLDOWN
        failed.state.outcome shouldBe ShiftOutcome.TIMED_OUT
        failed.events shouldContain ShiftEvent.TIMED_OUT
    }
})
