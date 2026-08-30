package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.util.UUID

class LumberAndMineShiftEngineTest : FunSpec({
    val player = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val outsider = UUID.fromString("00000000-0000-0000-0000-000000000002")

    test("lumber order requires the requested species then machine processing") {
        val rules = LumberRules(fellingQuota = 2, processingQuota = 4, processingPerUse = 2, cooldownMillis = 5_000)
        var state = LumberShiftEngine.start(LumberShiftState(), "OAK", rules, 1_000).state

        LumberShiftEngine.fell(state, rules, "BIRCH", player, 2_000).accepted shouldBe false
        state = LumberShiftEngine.fell(state, rules, "OAK", player, 2_000).state
        val processing = LumberShiftEngine.fell(state, rules, "OAK", player, 3_000)
        processing.state.phase shouldBe LumberPhase.PROCESSING
        processing.events shouldContain LumberShiftEvent.PHASE_CHANGED

        state = LumberShiftEngine.process(processing.state, rules, player, 4_000).state
        val completed = LumberShiftEngine.process(state, rules, player, 5_000)
        completed.state.phase shouldBe LumberPhase.COOLDOWN
        completed.state.outcome shouldBe ShiftOutcome.COMPLETED
        completed.state.contributors[player] shouldBe 6
    }

    test("lumber progress survives indefinite inactivity") {
        val rules = LumberRules(fellingQuota = 2, processingQuota = 2, processingPerUse = 1, cooldownMillis = 5_000)
        val started = LumberShiftEngine.start(LumberShiftState(), "OAK", rules, 1_000).state
        val partial = LumberShiftEngine.fell(started, rules, "OAK", player, 2_000).state

        LumberShiftEngine.tick(partial, rules, 2_592_002_000).state shouldBe partial
    }

    test("mine pauses for supports and any later player can deliver the shared cart") {
        val rules = MineRules(cartQuota = 6, hazardTrigger = 3, supportsRequired = 2, cooldownMillis = 5_000)
        var state = MineShiftEngine.start(MineShiftState(), rules, 1_000).state
        val hazard = MineShiftEngine.mine(state, rules, 3, player, 2_000)
        hazard.state.phase shouldBe MinePhase.HAZARD
        hazard.events shouldContain MineShiftEvent.HAZARD_STARTED

        MineShiftEngine.mine(hazard.state, rules, 1, player, 3_000).accepted shouldBe false
        state = MineShiftEngine.stabilize(hazard.state, rules, player, 3_000).state
        val stabilized = MineShiftEngine.stabilize(state, rules, player, 4_000)
        stabilized.state.phase shouldBe MinePhase.MINING
        stabilized.state.hazardResolved shouldBe true

        val extraction = MineShiftEngine.mine(stabilized.state, rules, 3, player, 5_000)
        extraction.state.phase shouldBe MinePhase.EXTRACTION
        extraction.events shouldContain MineShiftEvent.EXTRACTION_STARTED
        val completed = MineShiftEngine.extract(extraction.state, rules, outsider, 6_000)
        completed.accepted shouldBe true
        completed.contribution shouldBe 1
        completed.state.phase shouldBe MinePhase.COOLDOWN
        completed.state.outcome shouldBe ShiftOutcome.COMPLETED
        completed.state.contributors[outsider] shouldBe 1
    }

    test("mine extraction waits indefinitely without resetting the cart") {
        val rules = MineRules(cartQuota = 2, hazardTrigger = 1, supportsRequired = 1, cooldownMillis = 5_000)
        var state = MineShiftEngine.start(MineShiftState(), rules, 1_000).state
        state = MineShiftEngine.mine(state, rules, 1, player, 2_000).state
        state = MineShiftEngine.stabilize(state, rules, player, 3_000).state
        state = MineShiftEngine.mine(state, rules, 1, player, 4_000).state

        val afterMonth = MineShiftEngine.tick(state, rules, 2_592_004_000)
        afterMonth.state shouldBe state
        afterMonth.events shouldBe emptyList()
    }

    test("mine tick cannot skip an unresolved hazard after a quota reload") {
        val rules = MineRules(cartQuota = 4, hazardTrigger = 2, supportsRequired = 2, cooldownMillis = 5_000)
        val stale = MineShiftState(
            phase = MinePhase.MINING,
            sequence = 1,
            cart = 6,
            hazardResolved = false,
            startedAt = 1_000,
        )

        val advanced = MineShiftEngine.tick(stale, rules, 2_000)

        advanced.state.phase shouldBe MinePhase.HAZARD
        advanced.state.supports shouldBe 0
        advanced.events shouldContain MineShiftEvent.HAZARD_STARTED
    }

    test("long-lived statistics saturate instead of wrapping negative") {
        val stats = PlayerActivityStats(
            contributions = mapOf(ActivityKind.FARM to Long.MAX_VALUE - 1),
            completedShifts = mapOf(ActivityKind.FARM to Int.MAX_VALUE),
        )

        stats.contribute(ActivityKind.FARM, 10).contributions[ActivityKind.FARM] shouldBe Long.MAX_VALUE
        stats.complete(ActivityKind.FARM).completedShifts[ActivityKind.FARM] shouldBe Int.MAX_VALUE
        incrementContribution(mapOf(player to Int.MAX_VALUE), player, 1)[player] shouldBe Int.MAX_VALUE
    }
})
