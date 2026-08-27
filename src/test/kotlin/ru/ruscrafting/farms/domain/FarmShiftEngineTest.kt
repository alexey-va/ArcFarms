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
            FarmCareTarget(0, FarmCareRole.WEED_ROOT, FarmPointPosition("world", 1.5, 65.0, 1.5)),
            FarmCareTarget(1, FarmCareRole.WEED_ROOT, FarmPointPosition("world", 2.5, 65.0, 1.5)),
        )
        var state = FarmShiftEngine.startCare(ready, FarmCareType.WEEDS, targets).state

        state.phase shouldBe FarmPhase.CARE
        FarmShiftEngine.harvest(state, order, rules, "WHEAT", player, 2_000).accepted shouldBe false
        state = FarmShiftEngine.advanceCare(state, 0, player).state
        state.phase shouldBe FarmPhase.CARE
        state.careProgress() shouldBe 1
        FarmShiftEngine.tick(state, order, 2_592_002_000).state shouldBe state

        val resolved = FarmShiftEngine.advanceCare(state, 1, player)
        resolved.state.phase shouldBe FarmPhase.HARVESTING
        resolved.state.careProgress() shouldBe 2
        resolved.events shouldContainExactly listOf(ShiftEvent.CARE_PROGRESS, ShiftEvent.CARE_RESOLVED)
        resolved.state.contributors[player] shouldBe 6
    }

    test("apple care exposes many targets but resolves after any configured quota") {
        val targets = (0 until 200).map { index ->
            FarmCareTarget(
                index,
                FarmCareRole.APPLE,
                FarmPointPosition("world", index.toDouble(), 72.0, 0.0),
            )
        }
        var state = FarmShiftEngine.startCare(
            preparedState(order, rules, player),
            FarmCareType.APPLE_HARVEST,
            targets,
            goal = 50,
        ).state

        targets.take(49).forEach { target -> state = FarmShiftEngine.advanceCare(state, target.id, player).state }
        state.phase shouldBe FarmPhase.CARE
        state.careProgress() shouldBe 49
        state.careRequired() shouldBe 50

        val resolved = FarmShiftEngine.advanceCare(state, targets[149].id, player)
        resolved.state.phase shouldBe FarmPhase.HARVESTING
        resolved.state.careProgress() shouldBe 50
        resolved.events shouldContainExactly listOf(ShiftEvent.CARE_PROGRESS, ShiftEvent.CARE_RESOLVED)
    }

    test("field machinery tills and plants by proximity without checkpoints") {
        val machinePatch = buildList {
            (1..6).forEach { x -> add(FarmPlotPosition("world", x, 64, 1)) }
            (1..6).forEach { x -> add(FarmPlotPosition("world", x, 64, 3)) }
        }
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
                FarmPointPosition("world", 1.5, 65.0, 1.5),
            ),
        )
        FarmShiftEngine.startCare(preparation.copy(phase = FarmPhase.HARVESTING), FarmCareType.SEEDER, targets).accepted shouldBe false
        var state = FarmShiftEngine.startCare(preparation, FarmCareType.SEEDER, targets).state

        state.phase shouldBe FarmPhase.CARE
        state = FarmShiftEngine.startSeeder(state, 0).state
        val firstHalf = machinePatch.take(6).toSet()
        val partial = FarmShiftEngine.workSeeder(state, firstHalf, player)
        partial.state.phase shouldBe FarmPhase.CARE
        partial.state.seederStage() shouldBe FarmSeederStage.TILLING
        partial.events shouldContainExactly listOf(ShiftEvent.SEEDER_PROGRESS)

        val tillingResult = FarmShiftEngine.workSeeder(partial.state, machinePatch.drop(6).toSet(), player)
        tillingResult.accepted shouldBe true
        tillingResult.state.phase shouldBe FarmPhase.CARE
        tillingResult.state.seederStage() shouldBe FarmSeederStage.PLANTING
        tillingResult.state.tilledPlots shouldBe machinePatch.toSet()
        tillingResult.state.plantedPlots shouldBe emptySet()
        tillingResult.state.careTargets shouldBe targets.map { it.copy(progress = 1) }
        tillingResult.events shouldContainExactly listOf(ShiftEvent.SEEDER_PLANTING_STARTED)

        state = tillingResult.state
        val secondPlayer = UUID(0, 99)
        val completedResult = FarmShiftEngine.workSeeder(state, machinePatch.toSet(), secondPlayer)
        completedResult.accepted shouldBe true
        completedResult.state.phase shouldBe FarmPhase.HARVESTING
        completedResult.state.tilledPlots shouldBe machinePatch.toSet()
        completedResult.state.plantedPlots shouldBe machinePatch.toSet()
        completedResult.state.plantingProgress shouldBe machinePatch.size
        completedResult.state.contributors[player] shouldBe machinePatch.size
        completedResult.state.contributors[secondPlayer] shouldBe machinePatch.size
        completedResult.events shouldContainExactly listOf(ShiftEvent.CARE_RESOLVED)
    }

    test("field machinery credits the driver and every pig passenger equally") {
        val patch = (1..4).map { FarmPlotPosition("world", it, 64, 1) }
        val driver = UUID(0, 41)
        val passengerA = UUID(0, 42)
        val passengerB = UUID(0, 43)
        val preparation = FarmShiftState(
            phase = FarmPhase.PREPARATION,
            orderId = order.id,
            preparationPatch = patch,
            preparationCrop = "WHEAT",
            preparationReleased = true,
            preparationRequired = patch.size,
        )
        val target = FarmCareTarget(
            0,
            FarmCareRole.SEEDER_HORSE,
            FarmPointPosition("world", 1.5, 65.0, 1.5),
        )
        val started = FarmShiftEngine.startCare(preparation, FarmCareType.SEEDER, listOf(target)).state
        val mounted = FarmShiftEngine.startSeeder(started, target.id).state

        val result = FarmShiftEngine.workSeeder(
            mounted,
            patch.toSet(),
            linkedSetOf(driver, passengerA, passengerB),
        )

        result.state.contributors[driver] shouldBe patch.size
        result.state.contributors[passengerA] shouldBe patch.size
        result.state.contributors[passengerB] shouldBe patch.size
        result.contributionCredits shouldBe mapOf(
            driver to patch.size,
            passengerA to patch.size,
            passengerB to patch.size,
        )
    }

    test("crop disease adds bounded spots without resetting treated progress") {
        val first = FarmCareTarget(
            0,
            FarmCareRole.DISEASED_CROP,
            FarmPointPosition("world", 1.5, 65.0, 1.5),
        )
        var state = FarmShiftEngine.startCare(preparedState(order, rules, player), FarmCareType.DISEASE, listOf(first)).state
        val second = FarmCareTarget(
            1,
            FarmCareRole.DISEASED_CROP,
            FarmPointPosition("world", 2.5, 65.0, 1.5),
        )

        val spread = FarmShiftEngine.spreadDisease(state, second, maxSpots = 2)
        spread.accepted shouldBe true
        spread.state.careGoal shouldBe 2
        spread.state.careTargets.size shouldBe 2
        state = FarmShiftEngine.advanceCare(spread.state, first.id, player).state
        state.phase shouldBe FarmPhase.CARE
        state.careTargets.first().progress shouldBe 1
        FarmShiftEngine.spreadDisease(spread.state, second.copy(id = 2), maxSpots = 2).accepted shouldBe false
        FarmShiftEngine.tick(state, order, 2_592_002_000).state shouldBe state
    }

    test("a dead diseased crop is replaced without growing or stalling the care goal") {
        val dead = FarmCareTarget(0, FarmCareRole.DISEASED_CROP, FarmPointPosition("world", 1.5, 65.0, 1.5))
        val living = FarmCareTarget(1, FarmCareRole.DISEASED_CROP, FarmPointPosition("world", 2.5, 65.0, 1.5))
        val replacement = FarmCareTarget(2, FarmCareRole.DISEASED_CROP, FarmPointPosition("world", 3.5, 65.0, 1.5))
        val state = FarmShiftEngine.startCare(
            preparedState(order, rules, player),
            FarmCareType.DISEASE,
            listOf(dead, living),
        ).state

        val result = FarmShiftEngine.expireDisease(state, dead.id, replacement)

        result.accepted shouldBe true
        result.state.phase shouldBe FarmPhase.CARE
        result.state.careGoal shouldBe 2
        result.state.careTargets shouldBe listOf(living, replacement)
    }

    test("legacy two-click disease targets migrate to one physical treatment") {
        val target = FarmCareTarget(
            0,
            FarmCareRole.DISEASED_CROP,
            FarmPointPosition("world", 1.5, 65.0, 1.5),
            required = 2,
            progress = 1,
        )
        val state = FarmShiftEngine.startCare(
            preparedState(order, rules, player),
            FarmCareType.DISEASE,
            listOf(target),
        ).state

        val normalized = FarmShiftEngine.normalizeDisease(state)

        normalized.accepted shouldBe true
        normalized.state.phase shouldBe FarmPhase.HARVESTING
        normalized.state.careTargets.single().required shouldBe 1
        normalized.events shouldContainExactly listOf(ShiftEvent.CARE_RESOLVED)
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

    test("manual and mechanized field work complete at the configured tolerant quota") {
        val largePatch = (0 until 100).map { FarmPlotPosition("world", it, 64, 0) }
        var manual = FarmShiftEngine.start(
            FarmShiftState(),
            order,
            largePatch,
            "WHEAT",
            1_000,
            completionPercent = 90,
        ).state
        largePatch.take(90).forEach { manual = FarmShiftEngine.till(manual, it, player).state }
        manual.phase shouldBe FarmPhase.PLANTING
        manual.preparationRequired shouldBe 90
        manual.tilledPlots shouldBe largePatch.toSet()
        manual.preparationProgress shouldBe 100

        val horse = FarmCareTarget(0, FarmCareRole.SEEDER_HORSE, FarmPointPosition("world", 0.5, 65.0, 0.5))
        var machine = FarmShiftEngine.startCare(
            FarmShiftEngine.start(
                FarmShiftState(),
                order,
                largePatch,
                "WHEAT",
                1_000,
                completionPercent = 90,
            ).state,
            FarmCareType.SEEDER,
            listOf(horse),
        ).state
        machine = FarmShiftEngine.startSeeder(machine, horse.id).state
        machine = FarmShiftEngine.workSeeder(machine, largePatch.take(90).toSet(), player).state
        val planted = FarmShiftEngine.workSeeder(machine, largePatch.take(90).toSet(), player)
        planted.state.phase shouldBe FarmPhase.HARVESTING
        planted.state.tilledPlots shouldBe largePatch.toSet()
        planted.state.plantedPlots shouldBe largePatch.toSet()
        planted.state.plantingProgress shouldBe 100
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

    test("admin delivery completion never invents contributor credit") {
        val contributor = UUID.randomUUID()
        val packed = FarmShiftState(
            phase = FarmPhase.DELIVERY,
            contributors = mapOf(contributor to 7),
            deliveredCrates = setOf(0),
        )

        val completed = FarmShiftEngine.completeDeliveryAsAdmin(packed, rules, 3, 1_300)

        completed.accepted shouldBe true
        completed.contribution shouldBe 0
        completed.state.phase shouldBe FarmPhase.COOLDOWN
        completed.state.deliveredCrates shouldBe setOf(0, 1, 2)
        completed.state.contributors shouldBe mapOf(contributor to 7)
        completed.events shouldContainExactly listOf(ShiftEvent.COMPLETED)
    }

    test("food delivery credits only the rider who reaches the final checkpoint") {
        val waiting = FarmShiftState(
            phase = FarmPhase.INCIDENT,
            incidentType = FarmIncidentType.FOOD_DELIVERY,
            incidentCrop = "WHEAT",
            incidentRequired = 1,
        )
        val initialized = FarmShiftEngine.initializeFoodDelivery(waiting, checkpoints = 4, routeName = "orchard")
        initialized.accepted shouldBe true
        initialized.state.incidentProgress shouldBe 1
        initialized.state.specialIncident?.routeName shouldBe "orchard"

        val partial = FarmShiftEngine.advanceFoodDelivery(initialized.state, 3, player, completionContribution = 12)
        partial.accepted shouldBe true
        partial.contribution shouldBe 0
        partial.state.contributors shouldBe emptyMap()
        partial.state.phase shouldBe FarmPhase.INCIDENT

        val completed = FarmShiftEngine.advanceFoodDelivery(partial.state, 4, player, completionContribution = 12)
        completed.state.phase shouldBe FarmPhase.HARVESTING
        completed.state.contributors shouldBe mapOf(player to 12)
        completed.contribution shouldBe 12
        completed.events shouldContainExactly listOf(ShiftEvent.INCIDENT_RESOLVED)
    }

    test("only the expected unavailable incident can be skipped without contribution") {
        val waiting = FarmShiftState(
            phase = FarmPhase.INCIDENT,
            incidentType = FarmIncidentType.FOOD_DELIVERY,
            incidentCrop = "WHEAT",
            incidentRequired = 3,
            incidentProgress = 1,
            specialIncident = FarmSpecialIncidentState(),
        )

        FarmShiftEngine.skipUnavailableIncident(waiting, FarmIncidentType.BIRDS).accepted shouldBe false
        val skipped = FarmShiftEngine.skipUnavailableIncident(waiting, FarmIncidentType.FOOD_DELIVERY)
        skipped.accepted shouldBe true
        skipped.contribution shouldBe 0
        skipped.events shouldContainExactly emptyList()
        skipped.state.phase shouldBe FarmPhase.HARVESTING
        skipped.state.contributors shouldBe emptyMap()
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
