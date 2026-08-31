package ru.ruscrafting.farms.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class FarmActionIncidentTest : FunSpec({
    test("boar charge is stopped only by a nearby raised shield facing the attacker") {
        FarmBoarShieldPolicy.canDeflect(
            blocking = true,
            distanceSquared = 4.0,
            interceptRadius = 2.5,
            facingDot = 0.25,
            viewX = 1.0,
            viewZ = 0.0,
            boarOffsetX = 2.0,
            boarOffsetZ = 0.0,
        ) shouldBe true

        FarmBoarShieldPolicy.canDeflect(
            blocking = false,
            distanceSquared = 4.0,
            interceptRadius = 2.5,
            facingDot = 0.25,
            viewX = 1.0,
            viewZ = 0.0,
            boarOffsetX = 2.0,
            boarOffsetZ = 0.0,
        ) shouldBe false

        FarmBoarShieldPolicy.canDeflect(
            blocking = true,
            distanceSquared = 4.0,
            interceptRadius = 2.5,
            facingDot = 0.25,
            viewX = -1.0,
            viewZ = 0.0,
            boarOffsetX = 2.0,
            boarOffsetZ = 0.0,
        ) shouldBe false
    }

    test("autonomous raid flight advances by a bounded straight-line step and stops exactly at target") {
        val start = FarmPointPosition("world", 0.0, 70.0, 0.0)
        val target = FarmPointPosition("world", 3.0, 74.0, 0.0)

        val first = FarmRaidFlight.step(start, target, 2.5)
        first.x shouldBe (1.5 plusOrMinus 1.0e-9)
        first.y shouldBe (72.0 plusOrMinus 1.0e-9)
        first.z shouldBe (0.0 plusOrMinus 1.0e-9)
        FarmRaidFlight.step(first, target, 2.5) shouldBe target
    }

    test("raid flight cannot silently cross worlds") {
        shouldThrow<IllegalArgumentException> {
            FarmRaidFlight.step(
                FarmPointPosition("farm", 0.0, 70.0, 0.0),
                FarmPointPosition("rival", 1.0, 70.0, 0.0),
                1.0,
            )
        }
    }
})
