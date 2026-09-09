package ru.ruscrafting.farms.domain.mine.lift

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import kotlin.math.abs

class MineLiftMotionTest : FreeSpec({
    "occupied trip holds its destination and queued calls cannot steal the cabin" {
        val lift = MineLiftMotion(listOf(120.0, 90.0, 70.0, 40.0), 6.0)
        lift.board(3) shouldBe true
        lift.board(2) shouldBe false
        lift.board(3) shouldBe true
        repeat(10) { lift.call(1) }
        lift.queued shouldBe listOf(1)
        repeat(59) { lift.tick() shouldBe false }
        lift.phase shouldBe MineLiftMotion.Phase.BOARDING
        lift.tick()
        lift.phase shouldBe MineLiftMotion.Phase.MOVING
        var arrived = false
        repeat(500) { if (!arrived) arrived = lift.tick() }
        arrived shouldBe true
        lift.floor shouldBe 3
        lift.y shouldBe 40.0
        repeat(100) { lift.tick() }
        lift.phase shouldBe MineLiftMotion.Phase.DOCKED
        lift.tick()
        lift.target shouldBe 1
        lift.phase shouldBe MineLiftMotion.Phase.MOVING
    }

    "cabin moves monotonically without overshoot and obeys maximum speed both ways" {
        val lift = MineLiftMotion(listOf(120.0, 40.0), 6.0)
        for (target in listOf(1, 0)) {
            lift.board(target) shouldBe true
            var previous = lift.y
            var arrivals = 0
            repeat(600) {
                if (lift.tick()) arrivals++
                abs(lift.y - previous).shouldBeLessThanOrEqual(6.0 / 20.0 + 0.000001)
                (lift.y in 40.0..120.0) shouldBe true
                previous = lift.y
            }
            arrivals shouldBe 1
            lift.floor shouldBe target
        }
    }
})
