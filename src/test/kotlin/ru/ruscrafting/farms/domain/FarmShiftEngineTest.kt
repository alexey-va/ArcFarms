package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.UUID

class FarmShiftEngineTest : FunSpec({
    val order = FarmOrder("test_order", linkedMapOf("WHEAT" to 2, "CARROTS" to 2))
    val rules = FarmRules(60_000, 50, 10_000, 5_000)
    val player = UUID.fromString("00000000-0000-0000-0000-000000000001")

    test("farm starts on the selected order and only accepts requested crops") {
        val started = FarmShiftEngine.start(FarmShiftState(), order, rules, 1_000)

        started.accepted shouldBe true
        started.events shouldContainExactly listOf(ShiftEvent.STARTED)
        started.state.phase shouldBe FarmPhase.HARVESTING
        started.state.deadlineAt shouldBe 61_000

        FarmShiftEngine.harvest(started.state, order, rules, "POTATOES", player, 2_000).accepted shouldBe false
    }

    test("golden harvest doubles the selected remaining crop and completes the shift") {
        var state = FarmShiftEngine.start(FarmShiftState(), order, rules, 1_000).state
        state = FarmShiftEngine.harvest(state, order, rules, "WHEAT", player, 2_000).state
        val golden = FarmShiftEngine.harvest(state, order, rules, "WHEAT", player, 3_000)

        golden.state.phase shouldBe FarmPhase.GOLDEN_HARVEST
        golden.state.goldenCrop shouldBe "CARROTS"
        golden.events.last() shouldBe ShiftEvent.GOLDEN_STARTED

        val completed = FarmShiftEngine.harvest(golden.state, order, rules, "CARROTS", player, 4_000)
        completed.contribution shouldBe 2
        completed.state.phase shouldBe FarmPhase.COOLDOWN
        completed.state.outcome shouldBe ShiftOutcome.COMPLETED
        completed.state.contributors[player] shouldBe 4
        completed.events.last() shouldBe ShiftEvent.COMPLETED
    }

    test("golden window expires without ending the shared order") {
        var state = FarmShiftEngine.start(FarmShiftState(), order, rules, 1_000).state
        state = FarmShiftEngine.harvest(state, order, rules, "WHEAT", player, 2_000).state
        state = FarmShiftEngine.harvest(state, order, rules, "WHEAT", player, 3_000).state

        val expired = FarmShiftEngine.tick(state, order, rules, 13_000)
        expired.state.phase shouldBe FarmPhase.HARVESTING
        expired.events shouldContainExactly listOf(ShiftEvent.GOLDEN_ENDED)
    }

    test("farm times out and resets after cooldown") {
        val started = FarmShiftEngine.start(FarmShiftState(sequence = 4), order, rules, 1_000).state
        val timedOut = FarmShiftEngine.tick(started, order, rules, 61_000)
        timedOut.state.outcome shouldBe ShiftOutcome.TIMED_OUT
        timedOut.state.phase shouldBe FarmPhase.COOLDOWN

        val reset = FarmShiftEngine.tick(timedOut.state, order, rules, 66_000)
        reset.state shouldBe FarmShiftState(sequence = 5)
        reset.events shouldContainExactly listOf(ShiftEvent.RESET)
    }
})
