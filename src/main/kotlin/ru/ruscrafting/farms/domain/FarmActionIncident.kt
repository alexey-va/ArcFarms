package ru.ruscrafting.farms.domain

import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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

    fun advanceOrbit(angle: Double, elapsedTicks: Long, periodSeconds: Int): Double {
        require(angle.isFinite() && elapsedTicks >= 0 && periodSeconds > 0)
        val periodTicks = periodSeconds * 20.0
        val advanced = angle + elapsedTicks / periodTicks * 2.0 * PI
        return ((advanced % (2.0 * PI)) + 2.0 * PI) % (2.0 * PI)
    }
}
