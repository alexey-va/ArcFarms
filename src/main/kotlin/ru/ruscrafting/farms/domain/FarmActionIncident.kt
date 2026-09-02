package ru.ruscrafting.farms.domain

import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class FarmMotionVector(val x: Double, val y: Double, val z: Double) {
    fun length(): Double = sqrt(x * x + y * y + z * z)
}

/** Pure shield-facing policy used by the boar breakout incident. */
object FarmBoarShieldPolicy {
    fun canDeflect(
        blocking: Boolean,
        serviceShield: Boolean,
        distanceSquared: Double,
        interceptRadius: Double,
        facingDot: Double,
        viewX: Double,
        viewZ: Double,
        boarOffsetX: Double,
        boarOffsetZ: Double,
    ): Boolean {
        require(distanceSquared >= 0.0)
        require(interceptRadius.isFinite() && interceptRadius > 0.0)
        require(facingDot.isFinite() && facingDot in -1.0..1.0)
        if (!blocking || !serviceShield || distanceSquared > interceptRadius * interceptRadius) return false
        val viewLength = sqrt(viewX * viewX + viewZ * viewZ)
        val offsetLength = sqrt(boarOffsetX * boarOffsetX + boarOffsetZ * boarOffsetZ)
        if (viewLength <= 1.0e-6 || offsetLength <= 1.0e-6) return false
        val dot = (viewX * boarOffsetX + viewZ * boarOffsetZ) / (viewLength * offsetLength)
        return dot >= facingDot
    }

    fun knockback(
        boarX: Double,
        boarZ: Double,
        playerX: Double,
        playerZ: Double,
        fallbackX: Double,
        fallbackZ: Double,
        horizontal: Double,
        vertical: Double,
    ): FarmMotionVector {
        require(horizontal.isFinite() && horizontal >= 0.0)
        require(vertical.isFinite() && vertical >= 0.0)
        var dx = playerX - boarX
        var dz = playerZ - boarZ
        var length = sqrt(dx * dx + dz * dz)
        if (length <= 1.0e-6) {
            dx = fallbackX
            dz = fallbackZ
            length = sqrt(dx * dx + dz * dz)
        }
        if (length <= 1.0e-6) return FarmMotionVector(0.0, vertical, 0.0)
        return FarmMotionVector(dx / length * horizontal, vertical, dz / length * horizontal)
    }
}

object FarmRaidSeatPolicy {
    fun canBoard(currentRiders: Int, maximumRiders: Int, alreadyMounted: Boolean): Boolean {
        require(currentRiders >= 0 && maximumRiders > 0)
        return alreadyMounted || currentRiders < maximumRiders
    }

    fun deck(riders: Int, spacing: Double, height: Double): List<FarmMotionVector> {
        require(riders in 1..8)
        require(spacing.isFinite() && spacing > 0.0)
        require(height.isFinite() && height in -6.0..8.0)
        if (riders == 1) return listOf(FarmMotionVector(0.0, height, 0.0))
        val rows = (riders + 1) / 2
        return (0 until riders).map { index ->
            val row = index / 2
            val rowSize = minOf(2, riders - row * 2)
            val column = index % 2
            FarmMotionVector(
                x = (column - (rowSize - 1) / 2.0) * spacing,
                y = height,
                z = (row - (rows - 1) / 2.0) * spacing,
            )
        }
    }
}

object FarmRivalPatrolPlanner {
    private data class Candidate(val plot: FarmPlotPosition, val threatDistance: Double, val workerDistance: Double)

    fun select(
        candidates: Collection<FarmPlotPosition>,
        worker: FarmPointPosition,
        threat: FarmPointPosition,
        sequence: Long,
    ): FarmPlotPosition? {
        val evaluated = candidates.asSequence()
            .filter { it.world == worker.world && it.world == threat.world }
            .distinct()
            .map { plot ->
                val threatDx = plot.x + 0.5 - threat.x
                val threatDz = plot.z + 0.5 - threat.z
                val workerDx = plot.x + 0.5 - worker.x
                val workerDz = plot.z + 0.5 - worker.z
                Candidate(
                    plot,
                    threatDx * threatDx + threatDz * threatDz,
                    workerDx * workerDx + workerDz * workerDz,
                )
            }
            .toList()
        val local = evaluated.filter {
            it.workerDistance in MIN_TARGET_DISTANCE_SQUARED..MAX_TARGET_DISTANCE_SQUARED &&
                it.threatDistance <= MAX_THREAT_DISTANCE_SQUARED
        }
        val ranked = local.ifEmpty { evaluated.filter { it.threatDistance <= MAX_THREAT_DISTANCE_SQUARED } }
            .ifEmpty { evaluated }
            .asSequence()
            .sortedWith(
                compareBy<Candidate> { it.threatDistance }
                    .thenByDescending { it.workerDistance }
                    .thenBy { it.plot.x }.thenBy { it.plot.z }.thenBy { it.plot.y },
            )
            .toList()
        if (ranked.isEmpty()) return null
        return ranked[java.lang.Math.floorMod(sequence, ranked.size.toLong()).toInt()].plot
    }

    private const val MIN_TARGET_DISTANCE_SQUARED = 36.0
    private const val MAX_TARGET_DISTANCE_SQUARED = 576.0
    private const val MAX_THREAT_DISTANCE_SQUARED = 324.0
}

object FarmRaidSeatFollower {
    fun velocity(
        current: FarmPointPosition,
        target: FarmPointPosition,
        leaderVelocity: FarmMotionVector,
        correctionFactor: Double,
        maximumCorrection: Double,
    ): FarmMotionVector {
        require(current.world == target.world) { "Raid seat cannot cross worlds" }
        require(correctionFactor.isFinite() && correctionFactor in 0.0..1.0)
        require(maximumCorrection.isFinite() && maximumCorrection >= 0.0)
        val dx = (target.x - current.x) * correctionFactor
        val dy = (target.y - current.y) * correctionFactor
        val dz = (target.z - current.z) * correctionFactor
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        val scale = if (length > maximumCorrection && length > 1.0e-9) maximumCorrection / length else 1.0
        return FarmMotionVector(
            leaderVelocity.x + dx * scale,
            leaderVelocity.y + dy * scale,
            leaderVelocity.z + dz * scale,
        )
    }
}

object FarmRaidWeaponAim {
    fun spread(direction: FarmMotionVector, yawDegrees: Double, pitchDegrees: Double): FarmMotionVector {
        require(yawDegrees.isFinite() && pitchDegrees.isFinite())
        val length = direction.length()
        require(length > 1.0e-9) { "Raid weapon direction must not be zero" }
        val forward = FarmMotionVector(direction.x / length, direction.y / length, direction.z / length)
        val horizontal = sqrt(forward.x * forward.x + forward.z * forward.z)
        val right = if (horizontal > 1.0e-9) {
            FarmMotionVector(forward.z / horizontal, 0.0, -forward.x / horizontal)
        } else {
            FarmMotionVector(1.0, 0.0, 0.0)
        }
        val yaw = tan(Math.toRadians(yawDegrees))
        val pitch = tan(Math.toRadians(pitchDegrees))
        val candidate = FarmMotionVector(
            forward.x + right.x * yaw,
            forward.y + pitch,
            forward.z + right.z * yaw,
        )
        val candidateLength = candidate.length()
        return FarmMotionVector(
            candidate.x / candidateLength,
            candidate.y / candidateLength,
            candidate.z / candidateLength,
        )
    }
}

object FarmRaidBlastDamage {
    fun lethal(configuredDamage: Double, health: Double, absorption: Double): Double {
        require(configuredDamage.isFinite() && configuredDamage >= 0.0)
        require(health.isFinite() && health >= 0.0)
        require(absorption.isFinite() && absorption >= 0.0)
        return maxOf(configuredDamage, health + absorption + 1.0)
    }
}

object FarmRivalFieldPolicy {
    fun isEligible(loaded: Boolean, outdoor: Boolean, farmland: Boolean, headroom: Boolean): Boolean =
        loaded && outdoor && farmland && headroom

    /** Selects a bounded, deterministic farthest-point sample instead of one sorted corner of a large field. */
    fun distribute(
        candidates: Collection<FarmPlotPosition>,
        maximumPlots: Int,
        selectionIndex: Long,
    ): List<FarmPlotPosition> {
        require(maximumPlots > 0) { "Rival field sample size must be positive" }
        val ordered = candidates.distinct().sortedWith(
            compareBy<FarmPlotPosition> { it.x }.thenBy { it.z }.thenBy { it.y }.thenBy { it.world },
        )
        if (ordered.isEmpty()) return emptyList()
        val mixed = FarmSpatialSeed.mix(selectionIndex, 0x524956414c5f4649L)
        val offset = java.lang.Math.floorMod((mixed xor (mixed ushr 32)).toInt(), ordered.size)
        val rotated = ordered.drop(offset) + ordered.take(offset)
        return FarmSpacedPlotSelector.select(
            rotated.map { FarmMatureCrop(it, "") },
            minOf(maximumPlots, rotated.size),
            minimumSpacing = 0.0,
        ).values.map(FarmMatureCrop::plot)
    }
}

/** One-shot authorization for damage emitted by the raid gun's synchronous Paper damage call. */
class FarmRaidDamageGate {
    private data class Hit(val playerId: UUID, val targetId: UUID)

    private val pending = mutableSetOf<Hit>()

    fun <T> authorize(playerId: UUID, targetId: UUID, damage: () -> T): T {
        val hit = Hit(playerId, targetId)
        check(pending.add(hit)) { "Raid damage authorization is already active" }
        return try {
            damage()
        } finally {
            pending.remove(hit)
        }
    }

    fun consume(playerId: UUID, targetId: UUID): Boolean = pending.remove(Hit(playerId, targetId))
}

/** Deterministic straight-line flight step for the autonomous ghast. */
object FarmRaidFlight {
    fun step(current: FarmPointPosition, target: FarmPointPosition, distance: Double): FarmPointPosition {
        require(current.world == target.world) { "Raid flight cannot cross worlds" }
        require(distance.isFinite() && distance > 0.0) { "Raid flight step must be positive" }
        val dx = target.x - current.x
        val dy = target.y - current.y
        val dz = target.z - current.z
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (length <= distance) return target
        val scale = distance / length
        return current.copy(
            x = current.x + dx * scale,
            y = current.y + dy * scale,
            z = current.z + dz * scale,
        )
    }

    fun orbitPoint(center: FarmPointPosition, height: Double, radius: Double, angle: Double): FarmPointPosition {
        require(height.isFinite() && radius.isFinite() && radius > 0.0 && angle.isFinite())
        return center.copy(
            x = center.x + cos(angle) * radius,
            y = center.y + height,
            z = center.z + sin(angle) * radius,
        )
    }

    fun orbitPursuitPoint(
        center: FarmPointPosition,
        height: Double,
        radius: Double,
        angle: Double,
        lookAheadDegrees: Double,
    ): FarmPointPosition {
        require(lookAheadDegrees.isFinite() && lookAheadDegrees in 0.0..90.0)
        return orbitPoint(center, height, radius, angle + Math.toRadians(lookAheadDegrees))
    }

    fun advanceOrbit(angle: Double, elapsedTicks: Long, periodSeconds: Int): Double {
        require(angle.isFinite() && elapsedTicks >= 0 && periodSeconds > 0)
        val periodTicks = periodSeconds * 20.0
        val advanced = angle + elapsedTicks / periodTicks * 2.0 * PI
        return ((advanced % (2.0 * PI)) + 2.0 * PI) % (2.0 * PI)
    }

    fun steer(
        current: FarmPointPosition,
        target: FarmPointPosition,
        currentVelocity: FarmMotionVector,
        maximumSpeed: Double,
        steering: Double,
    ): FarmMotionVector {
        require(current.world == target.world) { "Raid flight cannot cross worlds" }
        require(maximumSpeed.isFinite() && maximumSpeed > 0.0)
        require(steering.isFinite() && steering in 0.0..1.0)
        val dx = target.x - current.x
        val dy = target.y - current.y
        val dz = target.z - current.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        val desired = if (distance <= 1.0e-6) {
            FarmMotionVector(0.0, 0.0, 0.0)
        } else {
            FarmMotionVector(dx / distance * maximumSpeed, dy / distance * maximumSpeed, dz / distance * maximumSpeed)
        }
        var next = FarmMotionVector(
            currentVelocity.x + (desired.x - currentVelocity.x) * steering,
            currentVelocity.y + (desired.y - currentVelocity.y) * steering,
            currentVelocity.z + (desired.z - currentVelocity.z) * steering,
        )
        val length = next.length()
        if (length > maximumSpeed) {
            val scale = maximumSpeed / length
            next = FarmMotionVector(next.x * scale, next.y * scale, next.z * scale)
        }
        return next
    }
}
