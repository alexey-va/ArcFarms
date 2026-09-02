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

    test("channel segments can be dug in any order while water advances only through a connected prefix") {
        var state = incident(FarmIncidentType.CHANNELS).copy(
            specialIncident = FarmSpecialIncidentState(
                points = (0 until 4).map { FarmPointPosition("world", it + 0.5, 65.0, 0.5) },
                routeName = FARM_CHANNEL_ROUTE_NAME,
                solution = setOf(0),
                active = setOf(0),
            ),
            incidentRequired = 4,
            incidentProgress = 1,
        )

        state = FarmSpecialIncidentEngine.digChannelSegment(state, 2, player).state
        state.incidentProgress shouldBe 2
        FarmSpecialIncidentEngine.channelFlowProgress(state.specialIncident!!.active, 4) shouldBe 1
        FarmSpecialIncidentEngine.advanceChannelFlow(state).accepted shouldBe false
        val duplicate = FarmSpecialIncidentEngine.digChannelSegment(state, 2, player)
        duplicate.accepted shouldBe false
        state = FarmSpecialIncidentEngine.digChannelSegment(state, 1, player).state
        state.incidentProgress shouldBe 3
        state = FarmSpecialIncidentEngine.advanceChannelFlow(state).state
        state = FarmSpecialIncidentEngine.advanceChannelFlow(state).state
        FarmSpecialIncidentEngine.channelFlowProgress(state.specialIncident!!.active, 4) shouldBe 3
        state = FarmSpecialIncidentEngine.digChannelSegment(state, 3, player).state
        state.phase shouldBe FarmPhase.INCIDENT
        state = FarmSpecialIncidentEngine.advanceChannelFlow(state).state
        state = FarmSpecialIncidentEngine.completeChannels(state).state
        state.phase shouldBe FarmPhase.HARVESTING
    }

    test("boar defense and rival raid use the same durable cooperative action progress") {
        listOf(FarmIncidentType.BOAR_BREAKOUT, FarmIncidentType.RIVAL_RAID).forEach { type ->
            var state = incident(type).copy(
                specialIncident = FarmSpecialIncidentState(
                    points = listOf(FarmPointPosition("world", 0.5, 65.0, 0.5)),
                ),
                incidentRequired = 2,
            )

            state = FarmSpecialIncidentEngine.advanceAction(state, type, player).state
            state.phase shouldBe FarmPhase.INCIDENT
            state.incidentProgress shouldBe 1
            state = FarmSpecialIncidentEngine.advanceAction(state, type, player).state
            state.phase shouldBe FarmPhase.HARVESTING
            state.contributors[player] shouldBe 2
        }
    }

    test("channel planner chooses its own first water block without a configured irrigation point") {
        val crops = (0 until 12).map { x -> FarmMatureCrop(FarmPlotPosition("world", x, 64, 0), "WHEAT") }

        val plan = FarmSpecialIncidentPlanner.plan(
            type = FarmIncidentType.CHANNELS,
            sequence = 8,
            matureCrops = crops,
            nightPatrolPlots = crops.map(FarmMatureCrop::plot),
            fallbackPlot = crops.first().plot,
            channelBlockages = 5,
            nightCropPlacements = 8,
            nightCropTarget = 4,
            nightCropMinSpacing = 4.0,
            nightPatrols = 0,
            nightPatrolMinSpacing = 4.0,
            marketCrops = 32,
        ) ?: error("Channel plan is missing")

        plan.required shouldBe 5
        plan.state.points.size shouldBe 5
        plan.state.routeName shouldBe FARM_CHANNEL_ROUTE_NAME
        plan.state.points.zipWithNext().all { (left, right) ->
            kotlin.math.abs(left.x - right.x) + kotlin.math.abs(left.z - right.z) == 1.0
        } shouldBe true
        plan.state.solution shouldBe setOf(0)
        plan.state.active shouldBe setOf(0)
    }

    test("channel planner supports a fifty block connected trench") {
        val plots = (0 until 64).map { x -> FarmPlotPosition("world", x, 64, 0) }
        val crops = plots.take(7).map { FarmMatureCrop(it, "WHEAT") }

        val plan = FarmSpecialIncidentPlanner.plan(
            type = FarmIncidentType.CHANNELS,
            sequence = 9,
            matureCrops = crops,
            channelPlots = plots,
            nightPatrolPlots = plots,
            fallbackPlot = crops.first().plot,
            channelBlockages = 50,
            nightCropPlacements = 8,
            nightCropTarget = 4,
            nightCropMinSpacing = 4.0,
            nightPatrols = 0,
            nightPatrolMinSpacing = 4.0,
            marketCrops = 32,
        ) ?: error("Channel plan is missing")

        plan.required shouldBe 50
        plan.state.points.size shouldBe 50
        plan.state.points.zipWithNext().all { (left, right) ->
            kotlin.math.abs(left.x - right.x) + kotlin.math.abs(left.z - right.z) == 1.0
        } shouldBe true
    }

    test("channel planner varies the trench through the same broad field across placements") {
        val plots = (0..12).flatMap { x ->
            (0..12).map { z -> FarmPlotPosition("world", x, 64, z) }
        }
        val routes = (1L..8L).map { sequence ->
            FarmSpecialIncidentPlanner.planChannelRoute(plots, requestedSegments = 17, sequence)
        }

        routes.all { it.size == 17 } shouldBe true
        routes.all { route ->
            route.zipWithNext().all { (left, right) ->
                kotlin.math.abs(left.x - right.x) + kotlin.math.abs(left.z - right.z) == 1
            }
        } shouldBe true
        routes.distinct().size shouldBe 8
        routes.map { it.first() }.distinct().size shouldBe 8
        routes.zipWithNext().all { (left, right) ->
            left.toSet().intersect(right.toSet()).size <= 11
        } shouldBe true
    }

    test("channel route may descend but never climbs back uphill") {
        val plots = buildList {
            for (x in 0..8) {
                val y = when (x) {
                    0, 1 -> 66
                    2, 3, 4 -> 65
                    else -> 66
                }
                add(FarmPlotPosition("world", x, y, 0))
            }
            for (x in 0..8) add(FarmPlotPosition("world", x, 64, 1))
        }

        val route = FarmSpecialIncidentPlanner.planChannelRoute(
            plots,
            requestedSegments = 8,
            sequence = 13,
        )

        route.size shouldBe 8
        route.zipWithNext().all { (from, to) -> to.y <= from.y } shouldBe true
    }

    test("channel route stays one block wide without touching an earlier non-consecutive segment") {
        val plots = (0..12).flatMap { x ->
            (0..12).map { z -> FarmPlotPosition("world", x, 64, z) }
        }
        val routes = (1L..32L).map { sequence ->
            FarmSpecialIncidentPlanner.planChannelRoute(plots, requestedSegments = 32, sequence)
        }

        routes.all { it.size == 32 } shouldBe true
        routes.all { route ->
            route.indices.all { leftIndex ->
                ((leftIndex + 2) until route.size).all { rightIndex ->
                    val left = route[leftIndex]
                    val right = route[rightIndex]
                    kotlin.math.abs(left.x - right.x) + kotlin.math.abs(left.z - right.z) > 1
                }
            }
        } shouldBe true
    }

    test("channel trail connects only neighbouring dug cells and never the remote irrigation point") {
        FarmChannelTrailPolicy.visibleLinks(segmentCount = 5, visible = setOf(0)) shouldBe emptyList()
        FarmChannelTrailPolicy.visibleLinks(segmentCount = 5, visible = setOf(0, 1, 2)) shouldBe listOf(0 to 1, 1 to 2)
        FarmChannelTrailPolicy.visibleLinks(segmentCount = 5, visible = setOf(0, 2, 3)) shouldBe listOf(2 to 3)
    }

    test("channel guidance renders unfinished earth only on every tenth segment") {
        val markers = (0 until 37).filter { index ->
            FarmChannelMarkerPolicy.showsEarthGuidance(index, solved = index in setOf(0, 20), stride = 10)
        }

        markers shouldBe listOf(10, 30)
    }

    test("channel segments are placed on indexed surface beds instead of interpolating underground y") {
        val beds = (0 until 8).map { x -> FarmPlotPosition("world", x, 72, 0) }
        val plan = FarmSpecialIncidentPlanner.plan(
            type = FarmIncidentType.CHANNELS,
            sequence = 4,
            matureCrops = beds.map { FarmMatureCrop(it, "WHEAT") },
            nightPatrolPlots = beds,
            fallbackPlot = beds.first(),
            channelBlockages = 4,
            nightCropPlacements = 6,
            nightCropTarget = 4,
            nightCropMinSpacing = 4.0,
            nightPatrols = 0,
            nightPatrolMinSpacing = 4.0,
            marketCrops = 32,
        ) ?: error("Channel plan is missing")

        plan.state.points.size shouldBe 4
        plan.state.points.all { it.y == 73.05 } shouldBe true
        plan.state.points.map { it.x to it.z }.distinct().size shouldBe 4
    }

    test("legacy channel points can be reprojected to distinct current surface beds") {
        val projected = FarmSpecialIncidentPlanner.projectChannelGates(
            points = listOf(
                FarmPointPosition("world", 2.0, 15.0, 2.0),
                FarmPointPosition("world", 3.0, 16.0, 2.0),
            ),
            surfacePlots = listOf(
                FarmPlotPosition("world", 2, 64, 2),
                FarmPlotPosition("world", 3, 65, 2),
            ),
        )

        projected shouldBe listOf(
            FarmPointPosition("world", 2.5, 65.05, 2.5),
            FarmPointPosition("world", 3.5, 66.05, 2.5),
        )
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
            channelBlockages = 4,
            nightCropPlacements = 8,
            nightCropTarget = 4,
            nightCropMinSpacing = 4.0,
            nightPatrols = 0,
            nightPatrolMinSpacing = 4.0,
            marketCrops = 32,
        ) ?: error("Giant crop plan is missing")

        plan.state.crop shouldBe "MELON"
        plan.state.points.single() shouldBe FarmPointPosition("world", 12.5, 65.0, 8.5)
        plan.required shouldBe FarmGiantCropBlueprint.voxels("MELON").size
    }

    test("sweet berry bushes are never selected for a giant crop") {
        FarmGiantCropBlueprint.supports("SWEET_BERRY_BUSH") shouldBe false
        FarmSpecialIncidentPlanner.plan(
            type = FarmIncidentType.GIANT_CROP,
            sequence = 9,
            matureCrops = emptyList(),
            giantCandidates = listOf(FarmGiantCropCandidate(cropPlots.first(), "SWEET_BERRY_BUSH")),
            fallbackPlot = null,
            channelBlockages = 4,
            nightCropPlacements = 8,
            nightCropTarget = 4,
            nightCropMinSpacing = 4.0,
            nightPatrols = 0,
            nightPatrolMinSpacing = 4.0,
            marketCrops = 32,
        ) shouldBe null
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
        expired.events shouldBe listOf(FarmShiftEvent.MARKET_EXPIRED)
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
            channelBlockages = 4,
            nightCropPlacements = 4,
            nightCropTarget = 2,
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
            channelBlockages = 4,
            nightCropPlacements = 4,
            nightCropTarget = 2,
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
            channelBlockages = 4,
            nightCropPlacements = 12,
            nightCropTarget = 8,
            nightCropMinSpacing = 6.0,
            nightPatrols = 3,
            nightPatrolMinSpacing = 10.0,
            marketCrops = 3,
        ) ?: error("Night shift plan is missing")

        plan.required shouldBe 8
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
            channelBlockages = 4,
            nightCropPlacements = 6,
            nightCropTarget = 4,
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
            channelBlockages = 4,
            nightCropPlacements = 6,
            nightCropTarget = 4,
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
