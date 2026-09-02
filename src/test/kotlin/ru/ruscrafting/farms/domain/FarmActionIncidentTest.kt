package ru.ruscrafting.farms.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
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

    test("shield impact knocks the blocker away from the boar with configured lift") {
        val impact = FarmBoarShieldPolicy.knockback(
            boarX = 4.0,
            boarZ = 3.0,
            playerX = 7.0,
            playerZ = 7.0,
            fallbackX = -1.0,
            fallbackZ = 0.0,
            horizontal = 0.9,
            vertical = 0.32,
        )

        impact.x shouldBe (0.54 plusOrMinus 1.0e-9)
        impact.y shouldBe 0.32
        impact.z shouldBe (0.72 plusOrMinus 1.0e-9)
    }

    test("shield impact uses a normalized fallback at zero distance") {
        val impact = FarmBoarShieldPolicy.knockback(2.0, 2.0, 2.0, 2.0, -3.0, 4.0, 1.0, 0.25)

        impact.x shouldBe (-0.6 plusOrMinus 1.0e-9)
        impact.y shouldBe 0.25
        impact.z shouldBe (0.8 plusOrMinus 1.0e-9)
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

    test("raid orbit pursues a nearby configured waypoint instead of wandering toward a distant chord") {
        val center = FarmPointPosition("world", 20.0, 65.0, -4.0)
        val pursued = FarmRaidFlight.orbitPursuitPoint(
            center,
            height = 20.0,
            radius = 28.0,
            angle = 0.0,
            lookAheadDegrees = 12.0,
        )

        pursued.x shouldBe (center.x + kotlin.math.cos(Math.toRadians(12.0)) * 28.0 plusOrMinus 1.0e-9)
        pursued.z shouldBe (center.z + kotlin.math.sin(Math.toRadians(12.0)) * 28.0 plusOrMinus 1.0e-9)
    }

    test("raid velocity steering is smoothed and capped") {
        val velocity = FarmRaidFlight.steer(
            current = FarmPointPosition("world", 0.0, 70.0, 0.0),
            target = FarmPointPosition("world", 20.0, 76.0, 0.0),
            currentVelocity = FarmMotionVector(0.0, 0.0, 0.0),
            maximumSpeed = 0.24,
            steering = 0.22,
        )

        velocity.length().shouldBeLessThanOrEqual(0.240000001)
        (velocity.x > 0.0) shouldBe true
        (velocity.y > 0.0) shouldBe true
    }

    test("raid seats accept four unique riders and reject a fifth") {
        FarmRaidSeatPolicy.canBoard(currentRiders = 3, maximumRiders = 4, alreadyMounted = false) shouldBe true
        FarmRaidSeatPolicy.canBoard(currentRiders = 4, maximumRiders = 4, alreadyMounted = false) shouldBe false
        FarmRaidSeatPolicy.canBoard(currentRiders = 4, maximumRiders = 4, alreadyMounted = true) shouldBe true
    }

    test("raid seats hang on a stable rack below the ghast body") {
        val seats = FarmRaidSeatPolicy.deck(riders = 4, spacing = 1.6, height = -2.6)

        seats.map { it.y }.toSet() shouldBe setOf(-2.6)
        seats.map { it.x to it.z }.toSet() shouldBe setOf(
            -0.8 to -0.8,
            0.8 to -0.8,
            -0.8 to 0.8,
            0.8 to 0.8,
        )
    }

    test("raid seat follows leader velocity with only a bounded positional correction") {
        val velocity = FarmRaidSeatFollower.velocity(
            current = FarmPointPosition("world", 0.0, 70.0, 0.0),
            target = FarmPointPosition("world", 1.0, 70.0, 0.0),
            leaderVelocity = FarmMotionVector(0.28, 0.01, -0.04),
            correctionFactor = 0.18,
            maximumCorrection = 0.06,
        )

        velocity.x shouldBe (0.34 plusOrMinus 1.0e-9)
        velocity.y shouldBe (0.01 plusOrMinus 1.0e-9)
        velocity.z shouldBe (-0.04 plusOrMinus 1.0e-9)
    }

    test("rival workers choose unique nearby destinations around the flying threat") {
        val plots = (-12..12 step 4).flatMap { x ->
            (-12..12 step 4).map { z -> FarmPlotPosition("world", x, 64, z) }
        }
        val threat = FarmPointPosition("world", -10.0, 75.0, 0.0)
        val worker = FarmPointPosition("world", 0.0, 65.0, 0.0)

        val targets = (0L..7L).map { sequence ->
            FarmRivalPatrolPlanner.select(plots, worker, threat, sequence)
        }

        targets.toSet().size shouldBe 8
        targets.all { target ->
            requireNotNull(target)
            val dx = target.x + 0.5 - threat.x
            val dz = target.z + 0.5 - threat.z
            dx * dx + dz * dz <= 18.0 * 18.0
        } shouldBe true
    }

    test("rival field policy accepts only loaded outdoor farmland with headroom") {
        FarmRivalFieldPolicy.isEligible(true, true, true, true) shouldBe true
        FarmRivalFieldPolicy.isEligible(false, true, true, true) shouldBe false
        FarmRivalFieldPolicy.isEligible(true, false, true, true) shouldBe false
        FarmRivalFieldPolicy.isEligible(true, true, false, true) shouldBe false
        FarmRivalFieldPolicy.isEligible(true, true, true, false) shouldBe false
    }
})
