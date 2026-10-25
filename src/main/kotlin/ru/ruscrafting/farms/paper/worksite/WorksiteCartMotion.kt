package ru.ruscrafting.farms.paper.worksite

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Existing heavy-cart follow policy, shared by mine and lumber without vehicle or renderer ownership. */
internal object WorksiteCartMotion {
    data class Frame(
        val x: Double, val y: Double, val z: Double, val yaw: Float, val wheelPhase: Float,
        val lastPlayerX: Double, val lastPlayerZ: Double, val pushX: Double, val pushZ: Double,
    )

    fun advance(current: Frame, playerX: Double, playerY: Double, playerZ: Double, playerYaw: Float, followDistance: Double = 1.9): Frame {
        val translated = squared(playerX - current.lastPlayerX, playerZ - current.lastPlayerZ) > .0004
        val radians = Math.toRadians(playerYaw.toDouble())
        val pushX = if (translated) -sin(radians) else current.pushX
        val pushZ = if (translated) cos(radians) else current.pushZ
        val dx = playerX + pushX * followDistance - current.x
        val dz = playerZ + pushZ * followDistance - current.z
        val distance = hypot(dx, dz)
        val step = minOf(distance, .7)
        val x = current.x + if (distance > .001) dx / distance * step else 0.0
        val z = current.z + if (distance > .001) dz / distance * step else 0.0
        val y = current.y + (playerY - current.y).coerceIn(-.15, .15)
        val towardX = playerX - x
        val towardZ = playerZ - z
        val towardLength = hypot(towardX, towardZ)
        val wheel = current.wheelPhase + if (towardLength > .001)
            (((x - current.x) * towardX + (z - current.z) * towardZ) / towardLength / .32).toFloat() else 0f
        val targetYaw = if (towardLength > .001) Math.toDegrees(atan2(-towardX, towardZ)).toFloat() else current.yaw
        var delta = (targetYaw - current.yaw) % 360f
        if (delta <= -180f) delta += 360f
        if (delta > 180f) delta -= 360f
        return Frame(x, y, z, current.yaw + delta.coerceIn(-22f, 22f), wheel, playerX, playerZ, pushX, pushZ)
    }

    private fun squared(x: Double, z: Double) = x * x + z * z
}
