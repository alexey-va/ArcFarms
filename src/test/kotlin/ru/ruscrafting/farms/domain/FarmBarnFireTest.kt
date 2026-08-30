package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.UUID

class FarmBarnFireTest : FunSpec({
    val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val second = UUID.fromString("00000000-0000-0000-0000-000000000002")
    val hotspots = listOf(
        FarmPointPosition("farm", 1.5, 65.02, 1.5),
        FarmPointPosition("farm", 4.5, 65.02, 1.5),
        FarmPointPosition("farm", 7.5, 65.02, 1.5),
    )

    fun incident() = FarmShiftState(
        phase = FarmPhase.INCIDENT,
        sequence = 8,
        placementSequence = 11,
        orderId = "test_order",
        incidentCrop = "WHEAT",
        incidentType = FarmIncidentType.BARN_FIRE,
    )

    test("barn fire persists independent hotspots and credits actual extinguishers") {
        var state = FarmShiftEngine.initializeBarnFire(incident(), hotspots).state

        state.incidentRequired shouldBe 3
        state.specialIncident?.active shouldBe setOf(0, 1, 2)

        val firstHit = FarmShiftEngine.extinguishBarnFire(state, 1, first)
        firstHit.events shouldContainExactly listOf(FarmShiftEvent.INCIDENT_PROGRESS)
        state = firstHit.state
        state.specialIncident?.active shouldBe setOf(0, 2)
        state.incidentProgress shouldBe 1
        FarmShiftEngine.extinguishBarnFire(state, 1, first).accepted shouldBe false

        state = FarmShiftEngine.extinguishBarnFire(state, 0, second).state
        val completed = FarmShiftEngine.extinguishBarnFire(state, 2, first)
        completed.events shouldContainExactly listOf(FarmShiftEvent.INCIDENT_PROGRESS, FarmShiftEvent.INCIDENT_RESOLVED)
        completed.state.phase shouldBe FarmPhase.HARVESTING
        completed.state.specialIncident shouldBe null
        completed.state.contributors[first] shouldBe 2
        completed.state.contributors[second] shouldBe 1
    }

    test("barn fire never expires while players are absent") {
        val state = FarmShiftEngine.initializeBarnFire(incident(), hotspots).state

        FarmShiftEngine.tick(state, null, Long.MAX_VALUE / 2).state shouldBe state
    }

    test("barn fire spreads through fresh hotspots only and stops at the planned limit") {
        var state = FarmShiftEngine.initializeBarnFire(incident(), hotspots, initialHotspotCount = 1).state
        state.specialIncident?.active shouldBe setOf(0)

        state = FarmShiftEngine.spreadBarnFire(state, hotspotCount = 1).state
        state.specialIncident?.active shouldBe setOf(0, 1)

        state = FarmShiftEngine.extinguishBarnFire(state, 0, first).state
        state.specialIncident?.active shouldBe setOf(1)
        state = FarmShiftEngine.spreadBarnFire(state, hotspotCount = 1).state
        state.specialIncident?.active shouldBe setOf(1, 2)

        val capped = FarmShiftEngine.spreadBarnFire(state, hotspotCount = 1)
        capped.accepted shouldBe false
        capped.state.specialIncident?.active shouldBe setOf(1, 2)
    }
})
