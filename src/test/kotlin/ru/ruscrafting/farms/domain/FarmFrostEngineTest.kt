package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.UUID

class FarmFrostEngineTest : FunSpec({
    val player = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val plots = listOf(
        FarmPlotPosition("world", 0, 64, 0),
        FarmPlotPosition("world", 16, 64, 0),
        FarmPlotPosition("world", 0, 64, 16),
        FarmPlotPosition("world", 16, 64, 16),
    )

    test("frost initializes durable campfires and temperature objective") {
        val result = FarmFrostEngine.initialize(
            current = frostIncident(),
            campfires = plots,
            now = 1_000,
            targetTemperature = 100,
        )

        result.accepted shouldBe true
        result.state.incidentProgress shouldBe 0
        result.state.incidentRequired shouldBe 100
        result.state.frost shouldBe FarmFrostState(
            campfires = plots.map(::FarmFrostCampfire),
            lastTickAt = 1_000,
        )
    }

    test("fuel extends one campfire and credits the carrier") {
        val initialized = FarmFrostEngine.initialize(frostIncident(), plots, 1_000, 100).state

        val first = FarmFrostEngine.fuel(initialized, plots[0], player, now = 2_000, fuelMillis = 30_000)
        val extended = FarmFrostEngine.fuel(first.state, plots[0], player, now = 3_000, fuelMillis = 30_000)

        first.accepted shouldBe true
        first.contribution shouldBe 1
        first.events shouldContainExactly listOf(FarmShiftEvent.INCIDENT_PROGRESS)
        first.state.frost!!.campfires.first().fuelUntil shouldBe 32_000
        extended.state.frost!!.campfires.first().fuelUntil shouldBe 62_000
        extended.state.contributors[player] shouldBe 2
    }

    test("burning campfires heat while an unlit field cools slowly without resetting") {
        val initialized = FarmFrostEngine.initialize(frostIncident(), plots, 1_000, 100).state
        val fueled = initialized.copy(
            incidentProgress = 40,
            frost = initialized.frost!!.copy(
                campfires = initialized.frost!!.campfires.mapIndexed { index, fire ->
                    fire.copy(fuelUntil = if (index < 2) 20_000 else 0)
                },
            ),
        )

        val heated = FarmFrostEngine.tick(
            fueled,
            now = 3_000,
            heatPerSecondPerFire = 2,
            coolingSecondsPerDegree = 10,
        )
        heated.state.incidentProgress shouldBe 48

        val cooling = heated.state.copy(
            frost = heated.state.frost!!.copy(
                lastTickAt = 3_000,
                campfires = heated.state.frost!!.campfires.map { it.copy(fuelUntil = 0) },
            ),
        )
        val cooled = FarmFrostEngine.tick(
            cooling,
            now = 33_000,
            heatPerSecondPerFire = 2,
            coolingSecondsPerDegree = 10,
        )

        cooled.state.incidentProgress shouldBe 45
        cooled.state.phase shouldBe FarmPhase.INCIDENT
    }

    test("temperature completion returns to harvesting and clears frost state") {
        val initialized = FarmFrostEngine.initialize(frostIncident(), plots, 1_000, 100).state
        val nearlyWarm = initialized.copy(
            incidentProgress = 98,
            frost = initialized.frost!!.copy(
                campfires = initialized.frost!!.campfires.mapIndexed { index, fire ->
                    fire.copy(fuelUntil = if (index == 0) 20_000 else 0)
                },
            ),
        )

        val completed = FarmFrostEngine.tick(
            nearlyWarm,
            now = 2_000,
            heatPerSecondPerFire = 2,
            coolingSecondsPerDegree = 10,
        )

        completed.state.phase shouldBe FarmPhase.HARVESTING
        completed.state.incidentType shouldBe null
        completed.state.frost shouldBe null
        completed.state.incidentsResolved shouldBe 1
        completed.events shouldContainExactly listOf(
            FarmShiftEvent.INCIDENT_PROGRESS,
            FarmShiftEvent.INCIDENT_RESOLVED,
        )
    }

    test("frost planner distributes a deterministic set over the indexed field") {
        val beds = (0 until 8).flatMap { x ->
            (0 until 8).map { z -> FarmPlotPosition("world", x, 64, z) }
        }

        val selected = FarmFrostPlanner.select(beds, count = 5, sequence = 12)

        selected.size shouldBe 5
        selected.distinct().size shouldBe 5
        FarmFrostPlanner.select(beds, count = 5, sequence = 12) shouldContainExactly selected
        selected.all { it.x in 1..6 && it.z in 1..6 } shouldBe true
        FarmFrostPlanner.select(beds.reversed(), count = 5, sequence = 12) shouldContainExactly selected
        (0L..100L).forEach { sequence ->
            FarmFrostPlanner.select(beds, 5, sequence).all { it.x in 1..6 && it.z in 1..6 } shouldBe true
        }
    }

    test("frost planner preserves count on sparse or narrow fields") {
        val sparse = (0..3).map { FarmPlotPosition("world", it * 10, 64, 0) }
        val selected = FarmFrostPlanner.select(sparse + sparse, 5, 12)
        selected.toSet() shouldBe sparse.toSet()
        FarmFrostPlanner.select(sparse, 0, 12) shouldBe emptyList()
        FarmFrostPlanner.select(emptyList(), 5, 12) shouldBe emptyList()
    }

    test("firewood point keeps free yaw but is always upright") {
        val point = FarmPointPosition("world", 4.5, 64.0, 7.5, yaw = 37f, pitch = 28f)

        FarmGroundDisplayPointOrientation.normalize(point) shouldBe point.copy(pitch = 0f)
    }
})

private fun frostIncident(): FarmShiftState = FarmShiftState(
    phase = FarmPhase.INCIDENT,
    incidentType = FarmIncidentType.FROST,
    incidentProgress = 0,
    incidentRequired = 1,
)
