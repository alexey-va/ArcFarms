package ru.ruscrafting.farms.paper.mine.expedition

import org.bukkit.Location
import org.bukkit.util.Vector
import kotlin.math.abs
import kotlin.math.sqrt

/** Pure bounded motion used by the manual crane and small side-job feedback. */
internal object MineFactoryExperimentMotion {
    data class Bounds(val minX: Double, val maxX: Double, val minZ: Double, val maxZ: Double) {
        init {
            require(minX <= maxX)
            require(minZ <= maxZ)
        }
    }

    data class AimPlane(val y: Double, val bounds: Bounds)

    fun pryProgress(pries: Int, total: Int = 3): Double {
        require(total > 0)
        return (pries.toDouble() / total).coerceIn(0.0, 1.0)
    }

    fun pryWobble(pries: Int, total: Int = 3): Double {
        val progress = pryProgress(pries, total)
        return (if (pries % 2 == 0) -1.0 else 1.0) * (0.10 + progress * 0.16)
    }

    fun phase(startedAt: Long, now: Long, durationMillis: Long): Double {
        require(startedAt >= 0L)
        require(now >= 0L)
        require(durationMillis > 0L)
        return ((now - startedAt).coerceAtLeast(0L).toDouble() / durationMillis).coerceIn(0.0, 1.0)
    }

    /** Intersects a player's eye ray with a horizontal plane and clamps it to the deck bounds. */
    fun boundedAim(eye: Location, direction: Vector, plane: AimPlane): Vector? {
        return boundedAim(eye.toVector(), direction, plane)
    }

    /** Bukkit-free form used by the runtime and focused motion tests. */
    fun boundedAim(eye: Vector, direction: Vector, plane: AimPlane): Vector? {
        if (!finite(eye) || !finite(direction)) return null
        val forward = direction.clone()
        val denominator = forward.y
        if (abs(denominator) < 1.0e-5) return null
        val distance = (plane.y - eye.y) / denominator
        if (!distance.isFinite()) return null
        if (distance < 0.0) return null
        val point = eye.clone().add(forward.multiply(distance))
        return point.apply {
            x = x.coerceIn(plane.bounds.minX, plane.bounds.maxX)
            z = z.coerceIn(plane.bounds.minZ, plane.bounds.maxZ)
            y = plane.y
        }
    }

    fun smooth(current: Vector, target: Vector, maxDistance: Double): Vector {
        require(maxDistance >= 0.0)
        val delta = target.clone().subtract(current)
        val distance = delta.length()
        return if (distance <= maxDistance || distance < 1.0e-6) target.clone()
        else current.clone().add(delta.multiply(maxDistance / distance))
    }

    fun distanceSquared(first: Vector, second: Vector): Double {
        val dx = first.x - second.x
        val dy = first.y - second.y
        val dz = first.z - second.z
        return dx * dx + dy * dy + dz * dz
    }

    fun length(vector: Vector): Double = sqrt(vector.lengthSquared())

    private fun finite(vector: Vector): Boolean =
        vector.x.isFinite() && vector.y.isFinite() && vector.z.isFinite()
}
