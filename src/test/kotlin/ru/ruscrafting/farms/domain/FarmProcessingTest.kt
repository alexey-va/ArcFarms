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
    }
})
