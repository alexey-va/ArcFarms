package ru.ruscrafting.farms.paper.mine.lift

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.mine.lift.MineLiftMotion

class MineLiftMaintenanceTest : FreeSpec({
    "maintenance claim is exclusive and only its owner can release it" {
        val claim = MineLiftMaintenanceClaim()
        claim.begin("event-a") shouldBe true
        claim.begin("event-b") shouldBe false
        claim.end("event-b")
        claim.owns("event-a") shouldBe true
        claim.end("event-a")
        claim.ownsAny() shouldBe false
        claim.begin("event-a") shouldBe true
        claim.clear()
        claim.ownsAny() shouldBe false
    }

    "readiness waits for riders and queued calls, then opens at an empty dock" {
        val claim = MineLiftMaintenanceClaim()
        claim.begin("event") shouldBe true
        claim.ready("event", MineLiftMotion.Phase.DOCKED, 1, emptyList()) shouldBe false
        claim.ready("event", MineLiftMotion.Phase.DOCKED, 0, listOf(1)) shouldBe false
        claim.ready("event", MineLiftMotion.Phase.DOCKED, 0, emptyList()) shouldBe true
    }

    "an occupied trip finishes before queued calls continue" {
        val lift = MineLiftMotion(listOf(123.0, 88.0, 38.0), 20.0)
        lift.board(2) shouldBe true
        lift.call(1)
        while (lift.phase != MineLiftMotion.Phase.MOVING) lift.tick()
        while (lift.phase == MineLiftMotion.Phase.MOVING) lift.tick()
        lift.floor shouldBe 2
        lift.queued shouldBe listOf(1)
        repeat(MineLiftMotion.DOCK_TICKS) { lift.tick() }
        lift.tick()
        lift.phase shouldBe MineLiftMotion.Phase.MOVING
        while (lift.phase == MineLiftMotion.Phase.MOVING) lift.tick()
        lift.queued shouldBe emptyList()
    }
})
