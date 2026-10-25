package ru.ruscrafting.farms.paper.lumber

import org.bukkit.Bukkit
import org.joml.Vector3d
import org.joml.Vector3f
import ru.arc.paper.display.DisplayOrientedVolume
import ru.arc.paper.scene.PaperSpatialSpace
import ru.ruscrafting.farms.domain.lumber.LumberPose
import ru.ruscrafting.farms.paper.lumber.scene.LumberSceneGeometry
import kotlin.math.abs
import kotlin.math.sin

/** Checks the authored cart and stock against exact native collision shapes without loading chunks. */
internal class LumberCartClearance(private val space: PaperSpatialSpace = PaperSpatialSpace()) {
    fun allows(from: LumberPose, to: LumberPose): Boolean {
        if (from.world != to.world) return false
        val world = Bukkit.getWorld(to.world) ?: return false
        val dx = to.x - from.x; val dy = to.y - from.y; val dz = to.z - from.z
        if (dx * dx + dy * dy + dz * dz > 1.0) return false
        var yaw = (to.yaw - from.yaw) % 360f
        if (yaw <= -180f) yaw += 360f
        if (yaw > 180f) yaw -= 360f
        if (abs(yaw) > 45f) return false
        // Eight short sweeps include rotation about the cart origin. Padding stays horizontal so wheels retain floor contact.
        var previous = LumberSceneGeometry.cartClearance(from)
        val radius = previous.maxOf { part ->
            kotlin.math.hypot(part.center.x - from.x, part.center.z - from.z) +
                kotlin.math.hypot(part.size.x / 2.0, part.size.z / 2.0)
        }
        val margin = 2 * radius * sin(Math.toRadians(abs(yaw).toDouble() / STEPS) / 2)
        for (step in 1..STEPS) {
            val fraction = step.toDouble() / STEPS
            val pose = LumberPose(to.world, from.x + dx * fraction, from.y + dy * fraction,
                from.z + dz * fraction, from.yaw + yaw * fraction.toFloat())
            val next = LumberSceneGeometry.cartClearance(pose)
            require(previous.size == next.size && previous.size <= 32)
            if (previous.zip(next).any { (start, end) ->
                val center = start.center.double()
                val volume = DisplayOrientedVolume(center,
                    start.rotation.transform(Vector3f(1f, 0f, 0f)).double(),
                    start.rotation.transform(Vector3f(0f, 1f, 0f)).double(),
                    start.rotation.transform(Vector3f(0f, 0f, 1f)).double(),
                    Vector3d(start.size.x / 2.0 + margin, start.size.y / 2.0, start.size.z / 2.0 + margin))
                    .swept(end.center.double().sub(center))
                !space.clear(world, volume) || !LumberSceneGeometry.clearOfMachine(volume)
            }) return false
            previous = next
        }
        return true
    }

    private fun Vector3f.double() = Vector3d(x.toDouble(), y.toDouble(), z.toDouble())
    private companion object { const val STEPS = 8 }
}
