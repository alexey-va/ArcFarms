package ru.ruscrafting.farms.domain

import kotlin.math.sqrt

/** Pure shield-facing policy used by the boar breakout incident. */
object FarmBoarShieldPolicy {
    fun canDeflect(
        blocking: Boolean,
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
        if (!blocking || distanceSquared > interceptRadius * interceptRadius) return false
        val viewLength = sqrt(viewX * viewX + viewZ * viewZ)
        val offsetLength = sqrt(boarOffsetX * boarOffsetX + boarOffsetZ * boarOffsetZ)
        if (viewLength <= 1.0e-6 || offsetLength <= 1.0e-6) return false
        val dot = (viewX * boarOffsetX + viewZ * boarOffsetZ) / (viewLength * offsetLength)
        return dot >= facingDot
    }
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
}
