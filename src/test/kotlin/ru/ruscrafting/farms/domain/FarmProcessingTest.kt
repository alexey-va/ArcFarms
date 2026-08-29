package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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

    test("walking a full circle accumulates one lap in either direction") {
        fun walk(direction: Int): Double {
            var state: FarmProcessingCrankState? = null
            var progress = 0.0
            repeat(49) { step ->
                val angle = direction * 2.0 * PI * step / 48.0
                val sample = FarmProcessingCrankTracker.sample(
                    previous = state,
                    x = 100.0 + 2.2 * cos(angle),
                    z = 200.0 + 2.2 * sin(angle),
                    centerX = 100.0,
                    centerZ = 200.0,
                    innerRadius = 1.4,
                    outerRadius = 3.0,
                    maxStepDistance = 1.2,
                )
                state = sample.state
                progress += sample.acceptedRadians
            }
            return progress
        }

        walk(1) shouldBe (FarmProcessingCrankTracker.FULL_LAP_RADIANS plusOrMinus 0.0001)
        walk(-1) shouldBe (FarmProcessingCrankTracker.FULL_LAP_RADIANS plusOrMinus 0.0001)
    }

    test("reversing and teleporting do not manufacture millstone progress") {
        var state: FarmProcessingCrankState? = null
        fun sample(angle: Double, radius: Double = 2.2): FarmProcessingCrankSample {
            val result = FarmProcessingCrankTracker.sample(
                previous = state,
                x = radius * cos(angle),
                z = radius * sin(angle),
                centerX = 0.0,
                centerZ = 0.0,
                innerRadius = 1.4,
                outerRadius = 3.0,
                maxStepDistance = 1.2,
            )
            state = result.state
            return result
        }

        sample(0.0).status shouldBe FarmProcessingCrankSampleStatus.ENTERED
        sample(0.2).acceptedRadians shouldBe (0.2 plusOrMinus 0.0001)
        sample(0.0).status shouldBe FarmProcessingCrankSampleStatus.REVERSED
        sample(0.2).status shouldBe FarmProcessingCrankSampleStatus.REVERSED
        sample(PI).status shouldBe FarmProcessingCrankSampleStatus.TELEPORTED
        sample(PI).acceptedRadians shouldBe 0.0
        sample(PI, radius = 3.5).status shouldBe FarmProcessingCrankSampleStatus.OUTSIDE
    }
})
