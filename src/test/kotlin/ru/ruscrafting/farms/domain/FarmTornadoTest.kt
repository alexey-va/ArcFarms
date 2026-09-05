package ru.ruscrafting.farms.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.util.UUID

class FarmTornadoTest : FunSpec({
    test("harvesting continues through tornado and completion clears only the overlay") {
        val order = FarmOrder("tornado_order", linkedMapOf("WHEAT" to 2, "CARROTS" to 3))
        val rules = FarmRules(listOf(40), incidentQuota = 2, cooldownMillis = 1_000)
        val player = UUID.randomUUID()
        val harvesting = FarmShiftState(phase = FarmPhase.HARVESTING, orderId = order.id, progress = mapOf("WHEAT" to 1))
        val started = FarmShiftEngine.harvest(harvesting, order, rules, "WHEAT", player, 1_000, FarmIncidentType.TORNADO)
        started.state.phase shouldBe FarmPhase.HARVESTING
        started.state.progress["WHEAT"] shouldBe 2
        started.state.tornado shouldBe FarmTornadoState(durationSeconds = 45)
        started.events shouldContain FarmShiftEvent.TORNADO_STARTED

        val harvested = FarmShiftEngine.harvest(started.state, order, rules, "CARROTS", player, 2_000)
        harvested.state.phase shouldBe FarmPhase.HARVESTING
        harvested.state.progress["CARROTS"] shouldBe 1
        harvested.state.tornado shouldBe started.state.tornado
        val quickDelivery = FarmShiftEngine.harvest(harvested.state.copy(progress = mapOf("WHEAT" to 2, "CARROTS" to 2)), order, rules, "CARROTS", player, 3_000)
        quickDelivery.state.phase shouldBe FarmPhase.DELIVERY
        quickDelivery.state.tornado shouldBe null

        var current = harvested.state
        repeat(45) { current = FarmTornadoEngine.second(current, setOf(player)).state }
        current.phase shouldBe FarmPhase.HARVESTING
        current.tornado shouldBe null
        current.progress shouldBe harvested.state.progress
        current.contributors shouldBe harvested.state.contributors
        current.incidentsResolved shouldBe 1
        val resumed = FarmShiftEngine.harvest(current, order, rules, "CARROTS", player, 4_000, FarmIncidentType.TORNADO)
        resumed.state.phase shouldBe FarmPhase.HARVESTING
        resumed.state.tornado shouldBe null
        val completed = FarmShiftEngine.harvest(resumed.state, order, rules, "CARROTS", player, 5_000, FarmIncidentType.TORNADO)
        completed.state.phase shouldBe FarmPhase.DELIVERY
        completed.state.tornado shouldBe null
    }

    test("tornado does not advance without participants and resumes persisted progress") {
        val player = UUID.randomUUID()
        val initial = tornadoState().copy(tornado = FarmTornadoState(elapsedSeconds = 3, durationSeconds = 10))

        FarmTornadoEngine.second(initial, emptySet()).state shouldBe initial
        val resumed = FarmTornadoEngine.second(initial, setOf(player))
        resumed.state.tornado?.elapsedSeconds shouldBe 4
        resumed.state.phase shouldBe FarmPhase.HARVESTING
        resumed.contributionCredits shouldBe emptyMap()
    }

    test("tornado completes once without granting harvest credit") {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val state = tornadoState().copy(tornado = FarmTornadoState(elapsedSeconds = 9, durationSeconds = 10))

        val completed = FarmTornadoEngine.second(state, setOf(first, second))
        completed.accepted shouldBe true
        completed.state.phase shouldBe FarmPhase.HARVESTING
        completed.state.tornado shouldBe null
        completed.state.incidentsResolved shouldBe 0
        completed.state.contributors shouldBe emptyMap()
        completed.contributionCredits shouldBe emptyMap()

        FarmTornadoEngine.second(completed.state, setOf(first)).accepted shouldBe false
    }

    test("tornado initialization preserves elapsed progress") {
        val anchors = listOf(FarmPointPosition("world", 1.5, 64.0, 2.5))
        val result = FarmTornadoEngine.initialize(
            tornadoState().copy(tornado = FarmTornadoState(elapsedSeconds = 4, durationSeconds = 45)),
            anchors,
            45,
        )

        result.accepted shouldBe true
        result.state.tornado?.points shouldBe anchors
        result.state.tornado?.durationSeconds shouldBe 45
        result.state.tornado?.elapsedSeconds shouldBe 4
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
    phase = FarmPhase.HARVESTING,
    orderId = "order",
)
