package ru.ruscrafting.farms.domain.mine.lift

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import kotlin.math.abs

class MineLiftMotionTest : FreeSpec({
    "casino trip allows five seconds to board and closes before its sixteen block journey" {
        val lift = MineLiftMotion(listOf(65.0, 49.0), 8.0, boardingTicks = 100)
        lift.board(1) shouldBe true
        repeat(169) { lift.tick() shouldBe false }
        lift.tick() shouldBe true
        lift.y shouldBe 49.0
    }

    "additional passengers share the boarding window and entry closes before cabin movement" {
        val lift = MineLiftMotion(listOf(65.0, 49.0, 33.0), 8.0, boardingTicks = 100)
        lift.doorsOpen shouldBe true
        lift.board(1) shouldBe true
        repeat(99) { lift.tick() shouldBe false }
        lift.phase shouldBe MineLiftMotion.Phase.BOARDING
        lift.doorsOpen shouldBe true
        lift.canBoard(1) shouldBe true
        lift.board(1) shouldBe true
        lift.canBoard(2) shouldBe false
        lift.board(2) shouldBe false

        lift.tick() shouldBe false
        lift.phase shouldBe MineLiftMotion.Phase.CLOSING
        lift.doorsOpen shouldBe false
        lift.canBoard(1) shouldBe false
        lift.board(1) shouldBe false
        lift.y shouldBe 65.0
        repeat(MineLiftMotion.CLOSING_TICKS - 1) {
            lift.tick() shouldBe false
            lift.phase shouldBe MineLiftMotion.Phase.CLOSING
            lift.y shouldBe 65.0
        }
        lift.tick() shouldBe false
        lift.phase shouldBe MineLiftMotion.Phase.MOVING
        lift.y shouldBe 65.0
        lift.doorsOpen shouldBe false
        lift.tick()
        (lift.y < 65.0) shouldBe true
    }

    "speed validation accepts the faster cap but rejects unsafe values" {
        MineLiftMotion(listOf(123.0, 38.0), MineLiftMotion.MAX_SPEED)
        shouldThrow<IllegalArgumentException> { MineLiftMotion(listOf(123.0, 38.0), 20.000001) }
        shouldThrow<IllegalArgumentException> { MineLiftMotion(listOf(123.0, 38.0), 8.0, boardingTicks = 0) }
        shouldThrow<IllegalArgumentException> { MineLiftMotion(listOf(65.0, 49.0), 6.0, arrivalHoldTicks = -1) }
    }

    "arrival hold keeps doors closed and riders committed until interpolation can finish in both directions" {
        val lift = MineLiftMotion(listOf(65.0, 49.0), 6.0, arrivalHoldTicks = 3)
        for (destination in listOf(1, 0)) {
            lift.board(destination) shouldBe true
            repeat(MineLiftMotion.BOARDING_TICKS + MineLiftMotion.CLOSING_TICKS) { lift.tick() shouldBe false }
            val start = lift.y
            repeat(79) { lift.tick() shouldBe false }
            (lift.y != start) shouldBe true
            (lift.y != listOf(65.0, 49.0)[destination]) shouldBe true
            lift.tick() shouldBe false // The final position is sent, but arrival is not released.
            lift.y shouldBe listOf(65.0, 49.0)[destination]
            repeat(2) {
                lift.phase shouldBe MineLiftMotion.Phase.MOVING
                lift.doorsOpen shouldBe false
                lift.canBoard(1 - destination) shouldBe false
                lift.floor shouldBe 1 - destination
                lift.tick() shouldBe false
                lift.y shouldBe listOf(65.0, 49.0)[destination]
            }
            lift.tick() shouldBe true
            lift.phase shouldBe MineLiftMotion.Phase.DOCKED
            lift.doorsOpen shouldBe true
            lift.floor shouldBe destination
            lift.tick() shouldBe false
        }
    }

    "occupied trip holds its destination and queued calls cannot steal the cabin" {
        val lift = MineLiftMotion(listOf(120.0, 90.0, 70.0, 40.0), 6.0)
        lift.board(3) shouldBe true
        lift.board(2) shouldBe false
        lift.board(3) shouldBe true
        repeat(10) { lift.call(1) }
        lift.queued shouldBe listOf(1)
        repeat(29) { lift.tick() shouldBe false }
        lift.phase shouldBe MineLiftMotion.Phase.BOARDING
        lift.tick()
        lift.phase shouldBe MineLiftMotion.Phase.CLOSING
        repeat(MineLiftMotion.CLOSING_TICKS) { lift.tick() }
        lift.phase shouldBe MineLiftMotion.Phase.MOVING
        var arrived = false
        repeat(500) { if (!arrived) arrived = lift.tick() }
        arrived shouldBe true
        lift.floor shouldBe 3
        lift.y shouldBe 40.0
        repeat(40) { lift.tick() }
        lift.phase shouldBe MineLiftMotion.Phase.DOCKED
        lift.tick()
        lift.target shouldBe 1
        lift.phase shouldBe MineLiftMotion.Phase.CLOSING
        repeat(MineLiftMotion.CLOSING_TICKS) { lift.tick() }
        lift.phase shouldBe MineLiftMotion.Phase.MOVING
    }

    "cabin moves monotonically without overshoot and obeys maximum speed both ways" {
        val lift = MineLiftMotion(listOf(120.0, 40.0), 16.0)
        for (target in listOf(1, 0)) {
            lift.board(target) shouldBe true
            var previous = lift.y
            var arrivals = 0
            repeat(600) {
                if (lift.tick()) arrivals++
                abs(lift.y - previous).shouldBeLessThanOrEqual(16.0 / 20.0 + 0.000001)
                (lift.y in 40.0..120.0) shouldBe true
                previous = lift.y
            }
            arrivals shouldBe 1
            lift.floor shouldBe target
        }
    }

    "maximum speed is capped and queued travel reaches exact endpoints" {
        val lift = MineLiftMotion(listOf(123.0, 38.0), 20.0)
        lift.board(1) shouldBe true
        repeat(MineLiftMotion.BOARDING_TICKS + MineLiftMotion.CLOSING_TICKS) { lift.tick() }

        var previous = lift.y
        while (lift.phase == MineLiftMotion.Phase.MOVING) {
            lift.tick()
            abs(lift.y - previous).shouldBeLessThanOrEqual(1.0 + 0.000001)
            previous = lift.y
        }
        lift.y shouldBe 38.0
        lift.floor shouldBe 1

        lift.call(0)
        repeat(MineLiftMotion.DOCK_TICKS) { lift.tick() }
        lift.tick()
        lift.phase shouldBe MineLiftMotion.Phase.CLOSING
        repeat(MineLiftMotion.CLOSING_TICKS) { lift.tick() }
        lift.phase shouldBe MineLiftMotion.Phase.MOVING
        while (lift.phase == MineLiftMotion.Phase.MOVING) lift.tick()
        lift.y shouldBe 123.0
        lift.floor shouldBe 0
    }
})
