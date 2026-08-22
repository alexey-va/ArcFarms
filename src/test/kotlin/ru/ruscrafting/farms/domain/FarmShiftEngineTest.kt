package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.UUID

class FarmShiftEngineTest : FunSpec({
    val order = FarmOrder("test_order", linkedMapOf("WHEAT" to 2, "CARROTS" to 2))
    val rules = FarmRules(
        incidentTriggerPercent = 50,
        incidentQuota = 1,
        cooldownMillis = 5_000,
    )
    val player = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val patch = listOf(
        FarmPlotPosition("world", 1, 64, 1),
        FarmPlotPosition("world", 2, 64, 1),
    )

    test("farm requires every selected plot to be tilled and planted before harvesting") {
        val started = FarmShiftEngine.start(FarmShiftState(), order, patch, "WHEAT", 1_000)

        started.accepted shouldBe true
        started.events shouldContainExactly listOf(ShiftEvent.STARTED)
        started.state.phase shouldBe FarmPhase.PREPARATION

        FarmShiftEngine.harvest(started.state, order, rules, "WHEAT", player, 1_500).accepted shouldBe false
        val firstBed = FarmShiftEngine.till(started.state, patch[0], player)
        firstBed.state.phase shouldBe FarmPhase.PREPARATION
        firstBed.state.preparationProgress shouldBe 1
        FarmShiftEngine.till(firstBed.state, patch[0], player).accepted shouldBe false
        val tilled = FarmShiftEngine.till(firstBed.state, patch[1], player)
        tilled.state.phase shouldBe FarmPhase.PLANTING
        tilled.events shouldContainExactly listOf(ShiftEvent.PREPARATION_PROGRESS, ShiftEvent.PLANTING_STARTED)

        FarmShiftEngine.plant(tilled.state, patch[0], "CARROTS", player).accepted shouldBe false
        val firstSeed = FarmShiftEngine.plant(tilled.state, patch[0], "WHEAT", player)
        firstSeed.state.phase shouldBe FarmPhase.PLANTING
        FarmShiftEngine.plant(firstSeed.state, patch[0], "WHEAT", player).accepted shouldBe false
        val ready = FarmShiftEngine.plant(firstSeed.state, patch[1], "WHEAT", player)
        ready.state.phase shouldBe FarmPhase.HARVESTING
        ready.events shouldContainExactly listOf(ShiftEvent.PLANTING_PROGRESS, ShiftEvent.PREPARATION_COMPLETED)

        FarmShiftEngine.harvest(ready.state, order, rules, "POTATOES", player, 2_000).accepted shouldBe false
    }

    test("farm incident pauses harvesting and only pest defeats resolve it") {
        var state = preparedState(order, rules, player)
        state = FarmShiftEngine.harvest(state, order, rules, "WHEAT", player, 2_000).state
        val incident = FarmShiftEngine.harvest(state, order, rules, "WHEAT", player, 3_000)

        incident.state.phase shouldBe FarmPhase.INCIDENT
        incident.state.incidentCrop shouldBe "CARROTS"
        incident.events.last() shouldBe ShiftEvent.INCIDENT_STARTED

        val blockedHarvest = FarmShiftEngine.harvest(incident.state, order, rules, "CARROTS", player, 3_500)
        blockedHarvest.accepted shouldBe false
        blockedHarvest.state shouldBe incident.state

        val rescued = FarmShiftEngine.defeatPest(activePestEncounter(incident.state), player)
        rescued.state.phase shouldBe FarmPhase.HARVESTING
        rescued.state.incidentResolved shouldBe true
        rescued.events shouldContainExactly listOf(
            ShiftEvent.INCIDENT_PROGRESS,
            ShiftEvent.INCIDENT_RESOLVED,
        )

        val firstCarrot = FarmShiftEngine.harvest(rescued.state, order, rules, "CARROTS", player, 5_000)
        firstCarrot.contribution shouldBe 1
        firstCarrot.state.phase shouldBe FarmPhase.HARVESTING
        val packed = FarmShiftEngine.harvest(firstCarrot.state, order, rules, "CARROTS", player, 5_100)
        packed.contribution shouldBe 1
        packed.state.phase shouldBe FarmPhase.DELIVERY
        packed.events.last() shouldBe ShiftEvent.DELIVERY_STARTED

        val completed = FarmShiftEngine.deliver(packed.state, rules, 0, 1, player, 6_000)
        completed.state.phase shouldBe FarmPhase.COOLDOWN
        completed.state.outcome shouldBe ShiftOutcome.COMPLETED
        completed.state.contributors[player] shouldBe 10
        completed.events shouldContainExactly listOf(ShiftEvent.COMPLETED)
    }

    test("drought is a distinct incident action and pest kills cannot bypass it") {
        val droughtRules = rules.copy(droughtQuota = 4)
        var state = preparedState(order, droughtRules, player)
        state = FarmShiftEngine.harvest(state, order, droughtRules, "WHEAT", player, 2_000).state
        state = FarmShiftEngine.harvest(
            state,
            order,
            droughtRules,
            "WHEAT",
            player,
            3_000,
            FarmIncidentType.DROUGHT,
        ).state

        state.phase shouldBe FarmPhase.INCIDENT
        state.incidentType shouldBe FarmIncidentType.DROUGHT
        state.incidentRequired shouldBe 4
        FarmShiftEngine.defeatPest(state, player).accepted shouldBe false
        repeat(3) { state = FarmShiftEngine.waterDrySoil(state, player).state }
        state.phase shouldBe FarmPhase.INCIDENT
        FarmShiftEngine.waterDrySoil(state, player).state.phase shouldBe FarmPhase.HARVESTING
    }

    test("completed farm emits completion once and remains quiet during cooldown") {
        var state = preparedState(order, rules, player)
        state = FarmShiftEngine.harvest(state, order, rules, "WHEAT", player, 2_000).state
        state = FarmShiftEngine.harvest(state, order, rules, "WHEAT", player, 3_000).state
        state = FarmShiftEngine.defeatPest(activePestEncounter(state), player).state
        state = FarmShiftEngine.harvest(state, order, rules, "CARROTS", player, 5_000).state
        val packed = FarmShiftEngine.harvest(state, order, rules, "CARROTS", player, 5_050)
        val completed = FarmShiftEngine.deliver(packed.state, rules, 0, 1, player, 5_100)

        completed.events.last() shouldBe ShiftEvent.COMPLETED
        (5_200L..10_000L step 100).forEach { now ->
            val nextTick = FarmShiftEngine.tick(completed.state, order, now)
            nextTick.accepted shouldBe false
            nextTick.events shouldContainExactly emptyList()
            nextTick.state shouldBe completed.state
            nextTick.state.cooldownEndsAt shouldBe completed.state.cooldownEndsAt
        }
    }

    test("legacy golden state resumes ordinary harvesting without a multiplier") {
        val legacy = FarmShiftState(
            phase = FarmPhase.GOLDEN_HARVEST,
            orderId = order.id,
            progress = order.required.keys.associateWith { 0 },
        )

        val harvested = FarmShiftEngine.harvest(legacy, order, rules, "WHEAT", player, 14_000)

        harvested.accepted shouldBe true
        harvested.state.phase shouldBe FarmPhase.HARVESTING
        harvested.state.progress.getValue("WHEAT") shouldBe 1
        harvested.contribution shouldBe 1
        harvested.events shouldContainExactly listOf(ShiftEvent.PROGRESS)
    }

    test("farm objective and incident survive indefinite inactivity") {
        val started = FarmShiftEngine.start(FarmShiftState(sequence = 4), order, patch, "WHEAT", 1_000).state
        val partial = FarmShiftEngine.till(started, patch.first(), player).state
        val afterMonth = FarmShiftEngine.tick(partial, order, 2_592_002_000)

        afterMonth.accepted shouldBe false
        afterMonth.state shouldBe partial
        afterMonth.events shouldContainExactly emptyList()
    }

    test("field care keeps every target and waits indefinitely for one player") {
        val ready = preparedState(order, rules, player)
        val targets = listOf(
            FarmCareTarget(0, FarmCareRole.WEED_ROOT, FarmPointPosition("world", 1.5, 65.0, 1.5), required = 2),
            FarmCareTarget(1, FarmCareRole.WEED_ROOT, FarmPointPosition("world", 2.5, 65.0, 1.5), required = 2),
        )
        var state = FarmShiftEngine.startCare(ready, FarmCareType.WEEDS, targets).state

        state.phase shouldBe FarmPhase.CARE
        FarmShiftEngine.harvest(state, order, rules, "WHEAT", player, 2_000).accepted shouldBe false
        repeat(3) { index ->
            val target = if (index < 2) 0 else 1
            state = FarmShiftEngine.advanceCare(state, target, player).state
        }
        state.phase shouldBe FarmPhase.CARE
        state.careProgress() shouldBe 3
        FarmShiftEngine.tick(state, order, 2_592_002_000).state shouldBe state

        val resolved = FarmShiftEngine.advanceCare(state, 1, player)
        resolved.state.phase shouldBe FarmPhase.HARVESTING
        resolved.state.careProgress() shouldBe 4
        resolved.events shouldContainExactly listOf(ShiftEvent.CARE_PROGRESS, ShiftEvent.CARE_RESOLVED)
        resolved.state.contributors[player] shouldBe 8
    }

    test("one player can prepare a one hundred plot patch without duplicate progress") {
        val largePatch = (0 until 100).map { index ->
            FarmPlotPosition("world", index % 20, 64, index / 20 * 2)
        }
        var state = FarmShiftEngine.start(FarmShiftState(), order, largePatch, "WHEAT", 1_000).state
        largePatch.forEach { plot -> state = FarmShiftEngine.till(state, plot, player).state }

        state.phase shouldBe FarmPhase.PLANTING
        state.preparationProgress shouldBe 100
        FarmShiftEngine.till(state, largePatch.first(), player).accepted shouldBe false

        largePatch.forEach { plot -> state = FarmShiftEngine.plant(state, plot, "WHEAT", player).state }
        state.phase shouldBe FarmPhase.HARVESTING
        state.plantingProgress shouldBe 100
        state.contributors[player] shouldBe 200
    }

    test("planting progress waits indefinitely when every player leaves") {
        var state = FarmShiftEngine.start(FarmShiftState(), order, patch, "WHEAT", 1_000).state
        patch.forEach { state = FarmShiftEngine.till(state, it, player).state }
        state = FarmShiftEngine.plant(state, patch.first(), "WHEAT", player).state

        val afterMonth = FarmShiftEngine.tick(state, order, 2_592_002_000)

        afterMonth.accepted shouldBe false
        afterMonth.state shouldBe state
        afterMonth.events shouldContainExactly emptyList()
    }

    test("packed order waits indefinitely for a carrier and completes only on delivery") {
        var state = preparedState(order, rules, player)
        state = FarmShiftEngine.harvest(state, order, rules, "WHEAT", player, 2_000).state
        state = FarmShiftEngine.harvest(state, order, rules, "WHEAT", player, 3_000).state
        state = FarmShiftEngine.defeatPest(activePestEncounter(state), player).state
        state = FarmShiftEngine.harvest(state, order, rules, "CARROTS", player, 5_000).state
        state = FarmShiftEngine.harvest(state, order, rules, "CARROTS", player, 5_100).state

        state.phase shouldBe FarmPhase.DELIVERY
        FarmShiftEngine.tick(state, order, 2_592_005_000).state shouldBe state
        FarmShiftEngine.tick(state, order, 2_592_005_000).events shouldContainExactly emptyList()
    }

    test("every configured harvest crate must be delivered exactly once") {
        val packed = FarmShiftState(phase = FarmPhase.DELIVERY)
        val first = FarmShiftEngine.deliver(packed, rules, 0, 3, player, 1_000)
        first.state.phase shouldBe FarmPhase.DELIVERY
        first.state.deliveredCrates shouldBe setOf(0)
        first.events shouldContainExactly listOf(ShiftEvent.DELIVERY_PROGRESS)

        FarmShiftEngine.deliver(first.state, rules, 0, 3, player, 1_100).accepted shouldBe false
        val second = FarmShiftEngine.deliver(first.state, rules, 2, 3, player, 1_200)
        second.state.phase shouldBe FarmPhase.DELIVERY
        val completed = FarmShiftEngine.deliver(second.state, rules, 1, 3, player, 1_300)
        completed.state.phase shouldBe FarmPhase.COOLDOWN
        completed.state.deliveredCrates shouldBe setOf(0, 2, 1)
        completed.events shouldContainExactly listOf(ShiftEvent.COMPLETED)
    }

    test("pest incident waits for every nest and every live pest") {
        var state = preparedState(order, rules, player)
        state = FarmShiftEngine.harvest(state, order, rules, "WHEAT", player, 2_000).state
        state = FarmShiftEngine.harvest(state, order, rules, "WHEAT", player, 3_000).state.copy(
            pestNestsInitialized = true,
            pestNests = listOf(FarmPestNest(patch.first(), health = 2, spawned = 1)),
            pestAlive = 1,
            incidentRequired = 2,
        )

        val hit = FarmShiftEngine.damagePestNest(state, patch.first(), player)
        hit.state.pestNests.single().health shouldBe 1
        hit.state.phase shouldBe FarmPhase.INCIDENT

        val killed = FarmShiftEngine.defeatPest(hit.state, player)
        killed.state.pestAlive shouldBe 0
        killed.state.phase shouldBe FarmPhase.INCIDENT

        val destroyed = FarmShiftEngine.damagePestNest(killed.state, patch.first(), player)
        destroyed.state.pestNests shouldBe emptyList()
        destroyed.state.phase shouldBe FarmPhase.HARVESTING
        destroyed.events shouldContainExactly listOf(
            ShiftEvent.INCIDENT_PROGRESS,
            ShiftEvent.INCIDENT_RESOLVED,
        )
    }
})

private fun activePestEncounter(state: FarmShiftState): FarmShiftState = state.copy(
    pestNestsInitialized = true,
    pestAlive = 1,
    incidentRequired = 1,
)

private fun preparedState(order: FarmOrder, rules: FarmRules, player: UUID): FarmShiftState {
    val patch = listOf(
        FarmPlotPosition("world", 1, 64, 1),
        FarmPlotPosition("world", 2, 64, 1),
    )
    var state = FarmShiftEngine.start(FarmShiftState(), order, patch, "WHEAT", 1_000).state
    patch.forEach { state = FarmShiftEngine.till(state, it, player).state }
    patch.forEach { state = FarmShiftEngine.plant(state, it, "WHEAT", player).state }
    return state
}
