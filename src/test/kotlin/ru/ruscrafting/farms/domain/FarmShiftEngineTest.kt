package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.UUID

class FarmShiftEngineTest : FunSpec({
    val order = FarmOrder("test_order", linkedMapOf("WHEAT" to 2, "CARROTS" to 2))
    val rules = FarmRules(incidentTriggerPercent = 50, incidentQuota = 1, goldenWindowMillis = 10_000, cooldownMillis = 5_000)
    val player = UUID.fromString("00000000-0000-0000-0000-000000000001")

    test("farm starts on the selected order and only accepts requested crops") {
        val started = FarmShiftEngine.start(FarmShiftState(), order, rules, 1_000)

        started.accepted shouldBe true
        started.events shouldContainExactly listOf(ShiftEvent.STARTED)
        started.state.phase shouldBe FarmPhase.HARVESTING

        FarmShiftEngine.harvest(started.state, order, rules, "POTATOES", player, 2_000).accepted shouldBe false
    }

    test("farm incident pauses no progress and unlocks golden harvest when resolved") {
        var state = FarmShiftEngine.start(FarmShiftState(), order, rules, 1_000).state
        state = FarmShiftEngine.harvest(state, order, rules, "WHEAT", player, 2_000).state
        val incident = FarmShiftEngine.harvest(state, order, rules, "WHEAT", player, 3_000)

        incident.state.phase shouldBe FarmPhase.INCIDENT
        incident.state.incidentCrop shouldBe "CARROTS"
        incident.events.last() shouldBe ShiftEvent.INCIDENT_STARTED

        val rescued = FarmShiftEngine.harvest(incident.state, order, rules, "CARROTS", player, 4_000)
        rescued.state.phase shouldBe FarmPhase.GOLDEN_HARVEST
        rescued.state.incidentResolved shouldBe true
        rescued.state.goldenCrop shouldBe "CARROTS"
        rescued.events shouldContainExactly listOf(
            ShiftEvent.PROGRESS,
            ShiftEvent.INCIDENT_PROGRESS,
            ShiftEvent.INCIDENT_RESOLVED,
            ShiftEvent.GOLDEN_STARTED,
        )

        val completed = FarmShiftEngine.harvest(rescued.state, order, rules, "CARROTS", player, 5_000)
        completed.contribution shouldBe 1
        completed.state.phase shouldBe FarmPhase.COOLDOWN
        completed.state.outcome shouldBe ShiftOutcome.COMPLETED
        completed.state.contributors[player] shouldBe 5
        completed.events.last() shouldBe ShiftEvent.COMPLETED
    }

    test("golden window expires without ending or resetting the shared order") {
        var state = FarmShiftEngine.start(FarmShiftState(), order, rules, 1_000).state
        state = FarmShiftEngine.harvest(state, order, rules, "WHEAT", player, 2_000).state
        state = FarmShiftEngine.harvest(state, order, rules, "WHEAT", player, 3_000).state
        state = FarmShiftEngine.harvest(state, order, rules, "CARROTS", player, 4_000).state

        val expired = FarmShiftEngine.tick(state, order, rules, 14_000)
        expired.state.phase shouldBe FarmPhase.HARVESTING
        expired.state.completed(order) shouldBe 3
        expired.events shouldContainExactly listOf(ShiftEvent.GOLDEN_ENDED)
    }

    test("farm objective and incident survive indefinite inactivity") {
        val started = FarmShiftEngine.start(FarmShiftState(sequence = 4), order, rules, 1_000).state
        val partial = FarmShiftEngine.harvest(started, order, rules, "WHEAT", player, 2_000).state
        val afterMonth = FarmShiftEngine.tick(partial, order, rules, 2_592_002_000)

        afterMonth.accepted shouldBe false
        afterMonth.state shouldBe partial
        afterMonth.events shouldContainExactly emptyList()
    }
})
