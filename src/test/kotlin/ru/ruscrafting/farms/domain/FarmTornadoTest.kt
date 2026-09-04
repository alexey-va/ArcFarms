package ru.ruscrafting.farms.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.util.UUID

class FarmTornadoTest : FunSpec({
    test("tornado does not advance without participants and resumes persisted progress") {
        val player = UUID.randomUUID()
        val initial = tornadoState().copy(incidentProgress = 3)

        FarmTornadoEngine.second(initial, emptySet()).state shouldBe initial
        val resumed = FarmTornadoEngine.second(initial, setOf(player))
        resumed.state.incidentProgress shouldBe 4
        resumed.state.incidentType shouldBe FarmIncidentType.TORNADO
        resumed.contributionCredits shouldBe emptyMap()
    }

    test("tornado completes once and credits every participant for the final second") {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val state = tornadoState().copy(incidentRequired = 2, incidentProgress = 1)

        val completed = FarmTornadoEngine.second(state, setOf(first, second))
        completed.accepted shouldBe true
        completed.state.phase shouldBe FarmPhase.HARVESTING
        completed.state.incidentType shouldBe null
        completed.state.incidentsResolved shouldBe 1
        completed.state.contributors shouldBe mapOf(first to 1, second to 1)
        completed.contributionCredits shouldBe mapOf(first to 1, second to 1)

        FarmTornadoEngine.second(completed.state, setOf(first)).accepted shouldBe false
    }

    test("tornado initialization preserves anchors and resets progress") {
        val anchors = listOf(FarmPointPosition("world", 1.5, 64.0, 2.5))
        val result = FarmTornadoEngine.initialize(
            tornadoState().copy(incidentProgress = 4, specialIncident = null),
            anchors,
            45,
        )

        result.accepted shouldBe true
        result.state.specialIncident?.points shouldBe anchors
        result.state.incidentRequired shouldBe 45
        result.state.incidentProgress shouldBe 0
    }

    test("tornado geometry is deterministic and bounded") {
        val funnel = FarmTornadoShape.funnel(0.5, 1.2, 8.0, height = 24.0, radius = 7.0)
        FarmTornadoShape.funnel(0.5, 1.2, 8.0, 24.0, 7.0) shouldBe funnel
        funnel.y shouldBe (12.0 plusOrMinus 1.0e-9)
        (funnel.x * funnel.x + funnel.z * funnel.z) shouldBeLessThanOrEqual 81.0

        val debris = (0 until 28).map { FarmTornadoShape.debris(it, 28, 4.0, 24.0, 7.0) }
        debris shouldHaveSize 28
        debris.forEach {
            (it.y >= -4.0) shouldBe true
            (it.y <= 24.0) shouldBe true
            (it.x * it.x + it.z * it.z <= 144.0) shouldBe true
        }
    }
})

private fun tornadoState() = FarmShiftState(
    phase = FarmPhase.INCIDENT,
    incidentType = FarmIncidentType.TORNADO,
    incidentRequired = 5,
    specialIncident = FarmSpecialIncidentState(
        points = listOf(FarmPointPosition("world", 0.5, 64.0, 0.5)),
    ),
)
