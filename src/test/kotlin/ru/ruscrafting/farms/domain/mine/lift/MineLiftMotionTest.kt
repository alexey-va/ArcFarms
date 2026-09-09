package ru.ruscrafting.farms.domain.mine.lift

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import kotlin.math.abs

class MineLiftMotionTest : FreeSpec({
    "speed validation accepts the faster cap but rejects unsafe values" {
        MineLiftMotion(listOf(123.0, 38.0), MineLiftMotion.MAX_SPEED)
        shouldThrow<IllegalArgumentException> { MineLiftMotion(listOf(123.0, 38.0), 20.000001) }
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
        repeat(MineLiftMotion.BOARDING_TICKS) { lift.tick() }

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
        lift.phase shouldBe MineLiftMotion.Phase.MOVING
        while (lift.phase == MineLiftMotion.Phase.MOVING) lift.tick()
        lift.y shouldBe 123.0
        lift.floor shouldBe 0
    }
})
