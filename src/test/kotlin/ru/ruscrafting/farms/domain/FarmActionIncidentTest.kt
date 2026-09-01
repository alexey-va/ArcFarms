package ru.ruscrafting.farms.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.util.UUID

class FarmActionIncidentTest : FunSpec({
    test("boar charge is stopped only by a nearby raised shield facing the attacker") {
        FarmBoarShieldPolicy.canDeflect(
            blocking = true,
            serviceShield = true,
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
            serviceShield = true,
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
            serviceShield = true,
            distanceSquared = 4.0,
            interceptRadius = 2.5,
            facingDot = 0.25,
            viewX = -1.0,
            viewZ = 0.0,
            boarOffsetX = 2.0,
            boarOffsetZ = 0.0,
        ) shouldBe false

        FarmBoarShieldPolicy.canDeflect(
            blocking = true,
            serviceShield = false,
            distanceSquared = 4.0,
            interceptRadius = 2.5,
            facingDot = 0.25,
            viewX = 1.0,
            viewZ = 0.0,
            boarOffsetX = 2.0,
            boarOffsetZ = 0.0,
        ) shouldBe false
    }

    test("raid damage authorization is one-shot and cannot leak past the gun call") {
        val gate = FarmRaidDamageGate()
        val player = UUID.randomUUID()
        val worker = UUID.randomUUID()

        gate.consume(player, worker) shouldBe false
        gate.authorize(player, worker) {
            gate.consume(player, worker) shouldBe true
            gate.consume(player, worker) shouldBe false
        }
        gate.consume(player, worker) shouldBe false
    }

    test("raid damage authorization is retired when the platform damage call fails") {
        val gate = FarmRaidDamageGate()
        val player = UUID.randomUUID()
        val worker = UUID.randomUUID()

        shouldThrow<IllegalStateException> {
            gate.authorize(player, worker) { error("damage failed") }
        }

        gate.consume(player, worker) shouldBe false
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

    test("raid orbit keeps a fixed radius and completes one circuit in the configured period") {
        val center = FarmPointPosition("world", 20.0, 65.0, -4.0)
        val startAngle = 0.35
        val start = FarmRaidFlight.orbitPoint(center, height = 12.0, radius = 16.0, angle = startAngle)
        val completedAngle = FarmRaidFlight.advanceOrbit(startAngle, elapsedTicks = 45L * 20L, periodSeconds = 45)
        val completed = FarmRaidFlight.orbitPoint(center, height = 12.0, radius = 16.0, angle = completedAngle)

        start.x shouldBe (completed.x plusOrMinus 1.0e-9)
        start.y shouldBe 77.0
        start.z shouldBe (completed.z plusOrMinus 1.0e-9)
    }
})
