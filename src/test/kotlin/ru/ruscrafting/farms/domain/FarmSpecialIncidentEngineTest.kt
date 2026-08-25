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

        var accepted = FarmSpecialIncidentEngine.acceptMarket(initial).state
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
        accepted.specialDamagedCrops.size shouldBe 4
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
            giantHits = 16,
            channelGates = 4,
            nightCrops = 4,
            marketCrops = 3,
        )
        val second = FarmSpecialIncidentPlanner.plan(
            FarmIncidentType.MARKET,
            sequence = 4,
            matureCrops = mature,
            fallbackPlot = cropPlots.first(),
            irrigationSource = FarmPointPosition("world", -2.0, 65.0, 0.0),
            giantHits = 16,
            channelGates = 4,
            nightCrops = 4,
            marketCrops = 3,
        )

        first shouldBe second
        first?.state?.crop shouldBe "WHEAT"
        first?.required shouldBe 3
    }
})

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
