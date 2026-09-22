package ru.ruscrafting.farms.paper.mine.expedition

import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Ray selection uses the exact animated cuboids; unlabelled casing still occludes labels behind it. */
internal object MineDisplayInspection {
    data class Hit(val part: MineDisplayBlueprints.Part, val distance: Float)

    fun pick(parts: List<MineDisplayBlueprints.Part>, phase: Float, eye: Vector3f,
             direction: Vector3f, distance: Float): Hit? {
        if (!eye.isFinite || !direction.isFinite || direction.lengthSquared() < .000001f ||
            !distance.isFinite() || distance <= 0f || !phase.isFinite()) return null
        val ray = Vector3f(direction).normalize()
        var closest: Hit? = null
        for (part in parts) {
            if (part.idleHidden) continue
            val inverse = MineDisplayBlueprints.rotation(part, phase).conjugate()
            val origin = inverse.transform(Vector3f(eye).sub(MineDisplayBlueprints.center(part, phase)))
            val heading = inverse.transform(Vector3f(ray))
            var near = 0f
            var far = closest?.distance ?: distance
            for (axis in 0..2) {
                val half = part.size.get(axis) / 2f
                val position = origin.get(axis)
                val velocity = heading.get(axis)
                if (abs(velocity) < .000001f) {
                    if (abs(position) > half) { far = -1f; break }
                } else {
                    val a = (-half - position) / velocity
                    val b = (half - position) / velocity
                    near = max(near, min(a, b))
                    far = min(far, max(a, b))
                    if (near > far) break
                }
            }
            if (near <= far) closest = Hit(part, near)
        }
        return closest
    }
}
