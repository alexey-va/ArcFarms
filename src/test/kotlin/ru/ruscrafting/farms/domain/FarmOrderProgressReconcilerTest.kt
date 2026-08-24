package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmOrderProgressReconcilerTest : FunSpec({
    test("reduced quotas clamp progress without resetting the active order") {
        val state = FarmShiftState(
            phase = FarmPhase.HARVESTING,
            sequence = 12,
            orderId = "bakery",
            progress = linkedMapOf("WHEAT" to 3_000, "CARROTS" to 400),
            harvestCheckpoint = 5,
            harvestMilestone = 2,
            incidentsResolved = 1,
        )

        val result = FarmOrderProgressReconciler.reconcile(
            state,
            linkedMapOf("WHEAT" to 1_200, "CARROTS" to 480),
        )

        result.changed shouldBe true
        result.state.progress shouldBe linkedMapOf("WHEAT" to 1_200, "CARROTS" to 400)
        result.state.orderId shouldBe state.orderId
        result.state.sequence shouldBe state.sequence
        result.state.incidentsResolved shouldBe 1
        result.state.harvestCheckpoint shouldBe 9
        result.state.harvestMilestone shouldBe 3
    }

    test("a changed crop set is rejected later instead of silently migrated") {
        val state = FarmShiftState(
            phase = FarmPhase.HARVESTING,
            orderId = "bakery",
            progress = mapOf("WHEAT" to 100),
        )

        FarmOrderProgressReconciler.reconcile(state, mapOf("CARROTS" to 100)).state shouldBe state
    }

    test("a quota reduction leaves one harvest to drive the normal delivery transition") {
        val state = FarmShiftState(
            phase = FarmPhase.INCIDENT,
            orderId = "market",
            progress = linkedMapOf("WHEAT" to 3_000, "CARROTS" to 1_000),
            incidentCrop = "CARROTS",
            incidentType = FarmIncidentType.PESTS,
            incidentRequired = 4,
        )

        val result = FarmOrderProgressReconciler.reconcile(
            state,
            linkedMapOf("WHEAT" to 640, "CARROTS" to 320),
        )

        result.state.progress shouldBe linkedMapOf("WHEAT" to 640, "CARROTS" to 319)
        result.state.phase shouldBe FarmPhase.INCIDENT
        result.state.harvestCheckpoint shouldBe 9
        result.state.harvestMilestone shouldBe 3
    }
})
