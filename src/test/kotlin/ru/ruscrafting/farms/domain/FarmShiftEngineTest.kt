package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.UUID

class FarmShiftEngineTest : FunSpec({
    val order = FarmOrder("test_order", linkedMapOf("WHEAT" to 2, "CARROTS" to 2))
    val rules = FarmRules(
        incidentTriggerPercents = listOf(50),
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
        rescued.state.incidentsResolved shouldBe 1
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

    test("a long harvest triggers three spaced incidents before delivery") {
        val longOrder = FarmOrder("long_order", linkedMapOf("WHEAT" to 20))
        val longRules = FarmRules(
            incidentTriggerPercents = listOf(25, 50, 75),
            incidentQuota = 1,
            cooldownMillis = 5_000,
        )
        var state = FarmShiftState(
            phase = FarmPhase.HARVESTING,
            orderId = longOrder.id,
            progress = mapOf("WHEAT" to 0),
        )
        val incidentStarts = mutableListOf<Int>()
        repeat(20) {
            if (state.phase == FarmPhase.INCIDENT) {
                state = FarmShiftEngine.defeatPest(activePestEncounter(state), player).state
            }
            val result = FarmShiftEngine.harvest(state, longOrder, longRules, "WHEAT", player, 2_000L + it)
            state = result.state
            if (ShiftEvent.INCIDENT_STARTED in result.events) incidentStarts += state.completed(longOrder)
        }

        incidentStarts shouldContainExactly listOf(5, 10, 15)
        state.phase shouldBe FarmPhase.DELIVERY
        state.incidentsResolved shouldBe 3
    }

    test("incident count varies deterministically inside the configured range") {
        val rangedRules = FarmRules(
            incidentTriggerPercents = listOf(15, 32, 50, 68, 85),
            incidentQuota = 1,
            cooldownMillis = 5_000,
            incidentCountMin = 3,
            incidentCountMax = 5,
        )

        rangedRules.incidentTargetCount(0) shouldBe 3
        rangedRules.incidentTargetCount(1) shouldBe 4
        rangedRules.incidentTargetCount(2) shouldBe 5
        rangedRules.incidentTriggers(0) shouldContainExactly listOf(15, 50, 85)
        rangedRules.incidentTriggers(1) shouldContainExactly listOf(15, 32, 68, 85)
        rangedRules.incidentTriggers(2) shouldContainExactly listOf(15, 32, 50, 68, 85)
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

    test("field machinery tills and plants in two complete horse-driven routes") {
        val machinePatch = buildList {
            (1..6).forEach { x -> add(FarmPlotPosition("world", x, 64, 1)) }
            (1..6).forEach { x -> add(FarmPlotPosition("world", x, 64, 3)) }
        }
        val passes = FarmMachinePlanner.plan(machinePatch, workingWidth = 1, originX = 0.5, originZ = 1.5)
        val first = passes.first().entry
        val preparation = FarmShiftState(
            phase = FarmPhase.PREPARATION,
            orderId = order.id,
            preparationPatch = machinePatch,
            preparationCrop = "WHEAT",
            preparationReleased = true,
            preparationRequired = machinePatch.size,
        )
        val targets = listOf(
            FarmCareTarget(
                0,
                FarmCareRole.SEEDER_HORSE,
                FarmPointPosition("world", first.x + 0.5, 65.0, first.z + 0.5),
            ),
        ) + passes.mapIndexed { index, pass ->
            FarmCareTarget(
                index + 1,
                FarmCareRole.SEEDER_WAYPOINT,
                FarmPointPosition("world", pass.exit.x + 0.5, 65.0, pass.exit.z + 0.5),
            )
        }
        FarmShiftEngine.startCare(preparation.copy(phase = FarmPhase.HARVESTING), FarmCareType.SEEDER, targets).accepted shouldBe false
        var state = FarmShiftEngine.startCare(preparation, FarmCareType.SEEDER, targets).state

        state.phase shouldBe FarmPhase.CARE
        state = FarmShiftEngine.startSeeder(state, 0).state
        val tillingAssignments = FarmMachinePlanner.assignToWaypoints(
            machinePatch,
            targets.filter { it.role == FarmCareRole.SEEDER_WAYPOINT }.map { it.id to it.position },
            targets.first().position,
        )
        var tillingCompleted: EngineResult<FarmShiftState>? = null
        targets.filter { it.role == FarmCareRole.SEEDER_WAYPOINT }.reversed().forEach { target ->
            val assigned = tillingAssignments.getValue(target.id)
            state = FarmShiftEngine.workSeeder(state, assigned, player).state
            tillingCompleted = FarmShiftEngine.completeSeederPass(state, target.id, assigned)
            state = requireNotNull(tillingCompleted).state
        }
        val tillingResult = requireNotNull(tillingCompleted)
        tillingResult.accepted shouldBe true
        tillingResult.state.phase shouldBe FarmPhase.CARE
        tillingResult.state.seederStage() shouldBe FarmSeederStage.PLANTING
        tillingResult.state.tilledPlots shouldBe machinePatch.toSet()
        tillingResult.state.plantedPlots shouldBe emptySet()
        tillingResult.state.careTargets.filter { it.role == FarmCareRole.SEEDER_WAYPOINT }
            .all { !it.complete } shouldBe true
        tillingResult.events shouldContainExactly listOf(ShiftEvent.CARE_PROGRESS, ShiftEvent.SEEDER_PLANTING_STARTED)

        state = tillingResult.state
        val plantingTargets = state.careTargets.filter { it.role == FarmCareRole.SEEDER_WAYPOINT }
        val plantingAssignments = FarmMachinePlanner.assignToWaypoints(
            machinePatch,
            plantingTargets.map { it.id to it.position },
            state.careTargets.first { it.role == FarmCareRole.SEEDER_HORSE }.position,
        )
        val secondPlayer = UUID(0, 99)
        var completed: EngineResult<FarmShiftState>? = null
        plantingTargets.forEach { target ->
            val assigned = plantingAssignments.getValue(target.id)
            state = FarmShiftEngine.workSeeder(state, assigned, secondPlayer).state
            completed = FarmShiftEngine.completeSeederPass(state, target.id, assigned)
            state = requireNotNull(completed).state
        }
        val completedResult = requireNotNull(completed)
        completedResult.accepted shouldBe true
        completedResult.state.phase shouldBe FarmPhase.HARVESTING
        completedResult.state.tilledPlots shouldBe machinePatch.toSet()
        completedResult.state.plantedPlots shouldBe machinePatch.toSet()
        completedResult.state.plantingProgress shouldBe machinePatch.size
        completedResult.state.contributors[player] shouldBe machinePatch.size
        completedResult.state.contributors[secondPlayer] shouldBe machinePatch.size
        completedResult.events shouldContainExactly listOf(ShiftEvent.CARE_PROGRESS, ShiftEvent.CARE_RESOLVED)
    }

    test("crop disease adds bounded spots without resetting treated progress") {
        val first = FarmCareTarget(
            0,
            FarmCareRole.DISEASED_CROP,
            FarmPointPosition("world", 1.5, 65.0, 1.5),
            required = 2,
        )
        var state = FarmShiftEngine.startCare(preparedState(order, rules, player), FarmCareType.DISEASE, listOf(first)).state
        state = FarmShiftEngine.advanceCare(state, first.id, player).state
        val second = FarmCareTarget(
            1,
            FarmCareRole.DISEASED_CROP,
            FarmPointPosition("world", 2.5, 65.0, 1.5),
            required = 2,
        )

        val spread = FarmShiftEngine.spreadDisease(state, second, maxSpots = 2)
        spread.accepted shouldBe true
        spread.state.careTargets.first().progress shouldBe 1
        spread.state.careTargets.size shouldBe 2
        FarmShiftEngine.spreadDisease(spread.state, second.copy(id = 2), maxSpots = 2).accepted shouldBe false
        FarmShiftEngine.tick(spread.state, order, 2_592_002_000).state shouldBe spread.state
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
