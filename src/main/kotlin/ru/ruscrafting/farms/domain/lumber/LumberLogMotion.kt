package ru.ruscrafting.farms.domain.lumber

import kotlin.math.atan2
import kotlin.math.hypot

/** A leash pulls the workpiece after its carrier; looking around never moves the log. */
object LumberLogMotion {
    fun advance(from: LumberPose, carrier: LumberPose, followDistance: Double): LumberPose {
        if (from.world != carrier.world) return from
        val dx = carrier.x - from.x
        val dz = carrier.z - from.z
        val distance = hypot(dx, dz)
        if (distance <= followDistance) return from
        val step = minOf(distance - followDistance, .7)
        val targetYaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        var turn = (targetYaw - from.yaw) % 360f
        if (turn <= -180f) turn += 360f
        if (turn > 180f) turn -= 360f
        return from.copy(x = from.x + dx / distance * step,
            y = from.y + (carrier.y - from.y).coerceIn(-.15, .15),
            z = from.z + dz / distance * step,
            yaw = from.yaw + turn.coerceIn(-22f, 22f))
    }
}
