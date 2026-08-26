package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class FarmSpecialIncidentEngineTest : FunSpec({
    val player = UUID(0, 7)
    val cropPlots = (0 until 4).map { FarmPlotPosition("world", it, 64, 0) }

    test("giant crop survives inactivity and completes after its configured hit count") {
        var state = incident(FarmIncidentType.GIANT_CROP).copy(
            specialIncident = FarmSpecialIncidentState(points = listOf(FarmPointPosition("world", 0.5, 65.0, 0.5))),
            incidentRequired = 4,
        )

        FarmShiftEngine.tick(state, order(), now = 99_000).accepted shouldBe false
        repeat(4) { state = FarmSpecialIncidentEngine.damageGiantCrop(state, player).state }

        state.phase shouldBe FarmPhase.HARVESTING
        state.incidentsResolved shouldBe 1
        state.contributors[player] shouldBe 4
    }

    test("giant crop adopts the durable physical block count after an upgrade or restart") {
        val initial = incident(FarmIncidentType.GIANT_CROP).copy(
            specialIncident = FarmSpecialIncidentState(points = listOf(FarmPointPosition("world", 0.5, 65.0, 0.5))),
            incidentRequired = 16,
            incidentProgress = 2,
        )

        val reconciled = FarmSpecialIncidentEngine.reconcileGiantCrop(initial, totalBlocks = 41, brokenBlocks = 7)

        reconciled.accepted shouldBe true
        reconciled.state.incidentRequired shouldBe 41
        reconciled.state.incidentProgress shouldBe 7
        reconciled.state.phase shouldBe FarmPhase.INCIDENT
    }

    test("an unavailable special incident can be retargeted or safely skipped before it starts") {
        val initial = incident(FarmIncidentType.GIANT_CROP)

        val retargeted = FarmSpecialIncidentEngine.retargetUninitialized(initial, FarmIncidentType.CHANNELS)
        retargeted.accepted shouldBe true
        retargeted.state.incidentType shouldBe FarmIncidentType.CHANNELS

        val skipped = FarmSpecialIncidentEngine.skipUnavailable(initial)
        skipped.accepted shouldBe true
        skipped.events shouldBe emptyList()
        skipped.state.phase shouldBe FarmPhase.HARVESTING
        skipped.state.incidentsResolved shouldBe 1
    }

    test("channel water progress is the correctly configured prefix and supports backtracking") {
        var state = incident(FarmIncidentType.CHANNELS).copy(
            specialIncident = FarmSpecialIncidentState(
                points = (0 until 4).map { FarmPointPosition("world", it + 0.5, 65.0, 0.5) },
                solution = setOf(0, 2, 3),
            ),
            incidentRequired = 4,
        )

        state = FarmSpecialIncidentEngine.toggleChannelGate(state, 2, player).state
        state.incidentProgress shouldBe 0
        state = FarmSpecialIncidentEngine.toggleChannelGate(state, 0, player).state
        state.incidentProgress shouldBe 3
        state = FarmSpecialIncidentEngine.toggleChannelGate(state, 2, player).state
        state.incidentProgress shouldBe 2
        state = FarmSpecialIncidentEngine.toggleChannelGate(state, 2, player).state
        state.incidentProgress shouldBe 3
        state = FarmSpecialIncidentEngine.toggleChannelGate(state, 3, player).state
        state.phase shouldBe FarmPhase.HARVESTING
    }

    test("market can be declined without a penalty or accepted for a persisted money bonus") {
        val initial = incident(FarmIncidentType.MARKET).copy(
            specialIncident = FarmSpecialIncidentState(plots = cropPlots, crop = "WHEAT"),
            incidentRequired = cropPlots.size,
        )
        FarmSpecialIncidentEngine.declineMarket(initial).state.phase shouldBe FarmPhase.HARVESTING

        var accepted = FarmSpecialIncidentEngine.acceptMarket(initial, now = 100, durationMillis = 120_000).state
        accepted.specialIncident?.marketDeadlineAt shouldBe 120_100
        cropPlots.forEach { plot ->
            accepted = FarmSpecialIncidentEngine.harvestSpecialCrop(
                accepted,
                FarmIncidentType.MARKET,
                FarmCropDamage(plot, "WHEAT"),
                player,
                marketBonusPercent = 25,
            ).state
        }
        accepted.phase shouldBe FarmPhase.HARVESTING
        accepted.rewardMoneyBonusPercent shouldBe 25
        accepted.specialDamagedCrops.size shouldBe 0
    }

    test("rush delivery can count a regrown crop at the same managed bed") {
        var state = incident(FarmIncidentType.MARKET).copy(
            specialIncident = FarmSpecialIncidentState(plots = listOf(cropPlots.first()), crop = "WHEAT"),
            incidentRequired = 3,
        )
        state = FarmSpecialIncidentEngine.acceptMarket(state, now = 100, durationMillis = 120_000).state
        repeat(3) {
            state = FarmSpecialIncidentEngine.harvestSpecialCrop(
                state,
                FarmIncidentType.MARKET,
                FarmCropDamage(cropPlots.first(), "WHEAT"),
                player,
            ).state
        }
        state.phase shouldBe FarmPhase.HARVESTING
        state.specialDamagedCrops shouldBe emptyList()
    }

    test("giant crop planner uses a matching field block and physical blueprint quota") {
        val candidate = FarmGiantCropCandidate(FarmPlotPosition("world", 12, 65, 8), "MELON")
        val plan = FarmSpecialIncidentPlanner.plan(
            type = FarmIncidentType.GIANT_CROP,
            sequence = 9,
            matureCrops = emptyList(),
            giantCandidates = listOf(candidate),
            fallbackPlot = null,
            irrigationSource = null,
            channelGates = 4,
            nightCrops = 8,
            nightCropMinSpacing = 4.0,
            nightPatrols = 0,
            nightPatrolMinSpacing = 4.0,
            marketCrops = 32,
        ) ?: error("Giant crop plan is missing")

        plan.state.crop shouldBe "MELON"
        plan.state.points.single() shouldBe FarmPointPosition("world", 12.5, 65.0, 8.5)
        plan.required shouldBe FarmGiantCropBlueprint.voxels("MELON").size
    }

    test("accepted market expires at its persisted deadline without granting the money bonus") {
        val initial = incident(FarmIncidentType.MARKET).copy(
            specialIncident = FarmSpecialIncidentState(plots = cropPlots, crop = "WHEAT"),
            incidentRequired = cropPlots.size,
        )
        val accepted = FarmSpecialIncidentEngine.acceptMarket(initial, now = 5_000, durationMillis = 120_000).state
        FarmSpecialIncidentEngine.expireMarket(accepted, 124_999).accepted shouldBe false

        val expired = FarmSpecialIncidentEngine.expireMarket(accepted, 125_000)
        expired.accepted shouldBe true
        expired.events shouldBe listOf(ShiftEvent.MARKET_EXPIRED)
        expired.state.phase shouldBe FarmPhase.HARVESTING
        expired.state.rewardMoneyBonusPercent shouldBe 0
    }

    test("main harvest cannot erase crops still waiting for incident recovery") {
        val damaged = FarmCropDamage(cropPlots.first(), "WHEAT")
        val recovering = incident(FarmIncidentType.NIGHT_SHIFT).copy(
            phase = FarmPhase.HARVESTING,
            incidentType = null,
            specialDamagedCrops = listOf(damaged),
        )

        val blocked = FarmShiftEngine.harvest(
            recovering,
            order(),
            FarmRules(incidentTriggerPercents = listOf(99), incidentQuota = 1, cooldownMillis = 0),
            "WHEAT",
            player,
            now = 2,
        )

        blocked.accepted shouldBe false
        blocked.state.progress["WHEAT"] shouldBe 0
        blocked.state.specialDamagedCrops shouldBe listOf(damaged)
    }

    test("special planner is deterministic and keeps market crops homogeneous") {
        val mature = listOf(
            FarmMatureCrop(cropPlots[0], "WHEAT"),
            FarmMatureCrop(cropPlots[1], "CARROTS"),
            FarmMatureCrop(cropPlots[2], "WHEAT"),
            FarmMatureCrop(cropPlots[3], "WHEAT"),
        )
        val first = FarmSpecialIncidentPlanner.plan(
            FarmIncidentType.MARKET,
            sequence = 4,
            matureCrops = mature,
            fallbackPlot = cropPlots.first(),
            irrigationSource = FarmPointPosition("world", -2.0, 65.0, 0.0),
            channelGates = 4,
            nightCrops = 4,
            nightCropMinSpacing = 4.0,
            nightPatrols = 2,
            nightPatrolMinSpacing = 4.0,
            marketCrops = 3,
        )
        val second = FarmSpecialIncidentPlanner.plan(
            FarmIncidentType.MARKET,
            sequence = 4,
            matureCrops = mature,
            fallbackPlot = cropPlots.first(),
            irrigationSource = FarmPointPosition("world", -2.0, 65.0, 0.0),
            channelGates = 4,
            nightCrops = 4,
            nightCropMinSpacing = 4.0,
            nightPatrols = 2,
            nightPatrolMinSpacing = 4.0,
            marketCrops = 3,
        )

        first shouldBe second
        first?.state?.crop shouldBe "WHEAT"
        first?.required shouldBe 3
    }

    test("night shift spreads crop targets and patrol anchors across the available field") {
        val mature = (0 until 10).flatMap { x ->
            (0 until 10).map { z ->
                FarmMatureCrop(FarmPlotPosition("world", x * 2, 64, z * 2), "WHEAT")
            }
        }
        val plan = FarmSpecialIncidentPlanner.plan(
            FarmIncidentType.NIGHT_SHIFT,
            sequence = 7,
            matureCrops = mature,
            fallbackPlot = mature.first().plot,
            irrigationSource = FarmPointPosition("world", -2.0, 65.0, 0.0),
            channelGates = 4,
            nightCrops = 12,
            nightCropMinSpacing = 6.0,
            nightPatrols = 3,
            nightPatrolMinSpacing = 10.0,
            marketCrops = 3,
        ) ?: error("Night shift plan is missing")

        plan.required shouldBe 12
        plan.state.plots.size shouldBe 12
        plan.state.points.size shouldBe 3
        (minimumPlotDistance(plan.state.plots) >= 5.5) shouldBe true
        (plan.state.plots.maxOf { it.x } - plan.state.plots.minOf { it.x } >= 16) shouldBe true
        (plan.state.plots.maxOf { it.z } - plan.state.plots.minOf { it.z } >= 16) shouldBe true
        (minimumPointDistance(plan.state.points) >= 10.0) shouldBe true
    }

    test("night patrols use the full indexed field even when mature crops are clustered") {
        val mature = (0 until 12).map { x ->
            FarmMatureCrop(FarmPlotPosition("world", x, 64, 0), "WHEAT")
        }
        val wholeFarm = (0 until 10).flatMap { x ->
            (0 until 10).map { z -> FarmPlotPosition("world", x * 10, 64, z * 10) }
        }

        val plan = FarmSpecialIncidentPlanner.plan(
            FarmIncidentType.NIGHT_SHIFT,
            sequence = 41,
            matureCrops = mature,
            nightPatrolPlots = wholeFarm,
            fallbackPlot = mature.first().plot,
            irrigationSource = null,
            channelGates = 4,
            nightCrops = 6,
            nightCropMinSpacing = 2.0,
            nightPatrols = 3,
            nightPatrolMinSpacing = 30.0,
            marketCrops = 8,
        ) ?: error("Night shift plan is missing")

        plan.state.points.size shouldBe 3
        (plan.state.points.maxOf { it.z } - plan.state.points.minOf { it.z } >= 30.0) shouldBe true
        (minimumPointDistance(plan.state.points) >= 30.0) shouldBe true
    }

    test("night shift keeps the configured patrol count when few unmarked beds remain") {
        val mature = (0 until 7).map { x ->
            FarmMatureCrop(FarmPlotPosition("world", x * 2, 64, 0), "WHEAT")
        }
        val plan = FarmSpecialIncidentPlanner.plan(
            FarmIncidentType.NIGHT_SHIFT,
            sequence = 11,
            matureCrops = mature,
            fallbackPlot = mature.first().plot,
            irrigationSource = FarmPointPosition("world", -2.0, 65.0, 0.0),
            channelGates = 4,
            nightCrops = 6,
            nightCropMinSpacing = 3.0,
            nightPatrols = 2,
            nightPatrolMinSpacing = 6.0,
            marketCrops = 3,
        ) ?: error("Night shift plan is missing")

        plan.state.plots.size shouldBe 6
        plan.state.points.size shouldBe 2
        (minimumPointDistance(plan.state.points) >= 6.0) shouldBe true
    }
})

private fun minimumPlotDistance(values: List<FarmPlotPosition>): Double = (0 until values.lastIndex).minOf { first ->
    (first + 1 until values.size).minOf { second ->
        val dx = (values[first].x - values[second].x).toDouble()
        val dz = (values[first].z - values[second].z).toDouble()
        kotlin.math.sqrt(dx * dx + dz * dz)
    }
}

private fun minimumPointDistance(values: List<FarmPointPosition>): Double = (0 until values.lastIndex).minOf { first ->
    (first + 1 until values.size).minOf { second ->
        val dx = values[first].x - values[second].x
        val dz = values[first].z - values[second].z
        kotlin.math.sqrt(dx * dx + dz * dz)
    }
}

private fun incident(type: FarmIncidentType) = FarmShiftState(
    phase = FarmPhase.INCIDENT,
    sequence = 1,
    orderId = "order",
    progress = mapOf("WHEAT" to 0),
    incidentCrop = "WHEAT",
    incidentType = type,
    incidentRequired = 1,
    startedAt = 1,
)

private fun order() = FarmOrder("order", mapOf("WHEAT" to 100))
