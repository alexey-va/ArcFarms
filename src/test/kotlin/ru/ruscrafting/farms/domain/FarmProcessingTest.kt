package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.util.UUID

class FarmProcessingTest : FunSpec({
    val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val second = UUID.fromString("00000000-0000-0000-0000-000000000002")

    fun incident() = FarmShiftState(
        phase = FarmPhase.INCIDENT,
        sequence = 4,
        orderId = "test_order",
        incidentCrop = "WHEAT",
        incidentType = FarmIncidentType.PROCESSING,
    )

    test("processing keeps three durable stages and resolves without resetting progress") {
        var state = FarmShiftEngine.initializeProcessing(incident(), "WHEAT", 2, 2, 2).state

        state.processing?.stage shouldBe FarmProcessingStage.LOADING
        state.incidentRequired shouldBe 6
        FarmShiftEngine.advanceProcessing(state, first, FarmProcessingStage.OPERATING).accepted shouldBe false

        val firstLoad = FarmShiftEngine.advanceProcessing(state, first, FarmProcessingStage.LOADING)
        firstLoad.events shouldContainExactly listOf(ShiftEvent.INCIDENT_PROGRESS)
        state = firstLoad.state
        val loaded = FarmShiftEngine.advanceProcessing(state, second, FarmProcessingStage.LOADING)
        loaded.events shouldContainExactly listOf(ShiftEvent.INCIDENT_PROGRESS, ShiftEvent.PROCESSING_STAGE_CHANGED)
        state = loaded.state
        state.processing?.stage shouldBe FarmProcessingStage.OPERATING
        state.incidentProgress shouldBe 2

        state = FarmShiftEngine.advanceProcessing(state, first, FarmProcessingStage.OPERATING).state
        val operated = FarmShiftEngine.advanceProcessing(state, second, FarmProcessingStage.OPERATING)
        operated.events shouldContainExactly listOf(ShiftEvent.INCIDENT_PROGRESS, ShiftEvent.PROCESSING_STAGE_CHANGED)
        state = operated.state
        state.processing?.stage shouldBe FarmProcessingStage.PACKING
        state.incidentProgress shouldBe 4

        state = FarmShiftEngine.advanceProcessing(state, first, FarmProcessingStage.PACKING).state
        val completed = FarmShiftEngine.advanceProcessing(state, second, FarmProcessingStage.PACKING)
        completed.events shouldContainExactly listOf(ShiftEvent.INCIDENT_PROGRESS, ShiftEvent.INCIDENT_RESOLVED)
        completed.state.phase shouldBe FarmPhase.HARVESTING
        completed.state.processing shouldBe null
        completed.state.incidentsResolved shouldBe 1
        completed.state.contributors[first] shouldBe 3
        completed.state.contributors[second] shouldBe 3
    }

    test("processing state survives an indefinitely late ordinary tick") {
        val state = FarmShiftEngine.initializeProcessing(incident(), "WHEAT", 4, 6, 4).state
        val partial = FarmShiftEngine.advanceProcessing(state, first, FarmProcessingStage.LOADING).state

        FarmShiftEngine.tick(partial, null, Long.MAX_VALUE / 2).state shouldBe partial
    }

    test("single anchor rotates the entire nine by five workshop") {
        val north = FarmProcessingLayout.create(FarmPointPosition("world", 100.0, 65.0, 200.0, 0f))
        val east = FarmProcessingLayout.create(FarmPointPosition("world", 100.0, 65.0, 200.0, 90f))

        north.footprint.size shouldBe 45
        north.inputRacks.single().x shouldBe (97.0 plusOrMinus 0.0001)
        north.outputPallet.x shouldBe (103.0 plusOrMinus 0.0001)
        east.inputRacks.single().z shouldBe (197.0 plusOrMinus 0.0001)
        east.outputPallet.z shouldBe (203.0 plusOrMinus 0.0001)
        north.inputLabels.size shouldBe 1

        val custom = FarmProcessingLayout.create(
            FarmPointPosition("world", 100.0, 65.0, 200.0),
            inputRackOverrides = listOf(
                FarmPointPosition("world", 90.0, 65.0, 200.0),
                FarmPointPosition("world", 92.0, 65.0, 200.0),
            ),
        )
        custom.inputRacks.size shouldBe 2
        FarmProcessingLayout.packagePosition(custom.inputRacks, 0).x shouldBe (89.57 plusOrMinus 0.0001)
        FarmProcessingLayout.packagePosition(custom.inputRacks, 1).x shouldBe (91.57 plusOrMinus 0.0001)

        north.outputChute.y shouldBe 65.0
        north.outputChute.x shouldBe (101.65 plusOrMinus 0.0001)
        north.outputChute.z shouldBe (198.9 plusOrMinus 0.0001)
        val floorPackages = (0 until 4).map { FarmProcessingLayout.floorPackagePosition(north.outputChute, it) }
        floorPackages.map { it.y }.distinct() shouldContainExactly listOf(65.0)
        floorPackages.map { it.x }.distinct().sorted().zip(listOf(101.125, 102.175)).forEach { (actual, expected) ->
            actual shouldBe (expected plusOrMinus 0.0001)
        }
        floorPackages.map { it.z }.distinct().sorted().zip(listOf(198.425, 199.375)).forEach { (actual, expected) ->
            actual shouldBe (expected plusOrMinus 0.0001)
        }
    }

    test("timing dial stays beside the grounded millstone at eye level and exposes a green success sector") {
        val plan = FarmProcessingDialPlanner.plan(
            machine = FarmPointPosition("world", 100.0, 65.0, 200.0, 0f),
            phase = 30,
            periodTicks = 60,
            successWindowTicks = 10,
            centerYOffset = 1.45,
            rightOffset = 1.45,
            forwardOffset = 0.8,
            radius = 0.8,
            pointCount = 32,
        )

        plan.ring.size shouldBe 32
        plan.ring.minOf { it.position.y } shouldBe (65.65 plusOrMinus 0.0001)
        plan.ring.maxOf { it.position.y } shouldBe (67.25 plusOrMinus 0.0001)
        plan.ring.map { it.position.x }.average() shouldBe (101.45 plusOrMinus 0.0001)
        plan.marker.position.y shouldBe (67.25 plusOrMinus 0.0001)
        plan.marker.inSuccessWindow shouldBe true
        plan.ring.any(FarmProcessingDialPoint::inSuccessWindow) shouldBe true
        plan.ring.any { !it.inSuccessWindow } shouldBe true
    }
})
