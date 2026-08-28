package ru.ruscrafting.farms.domain

import kotlin.math.sqrt

data class FarmRouteProjection(
    val segmentIndex: Int,
    val t: Double,
    val x: Double,
    val y: Double,
    val z: Double,
    val distance: Double,
)

/** Projects a cart onto the whole remaining polyline, not merely one sparse checkpoint segment. */
object FarmRouteGeometry {
    fun atDestination(
        world: String,
        x: Double,
        y: Double,
        z: Double,
        destination: FarmPointPosition,
        radius: Double,
    ): Boolean {
        if (destination.world != world || !radius.isFinite() || radius <= 0.0) return false
        val dx = x - destination.x
        val dy = y - destination.y
        val dz = z - destination.z
        return dx * dx + dy * dy + dz * dz <= radius * radius
    }

    fun closest(
        world: String,
        x: Double,
        y: Double,
        z: Double,
        points: List<FarmPointPosition>,
        fromSegment: Int,
        lookBehindSegments: Int = 1,
    ): FarmRouteProjection? {
        if (points.size < 2 || points.any { it.world != world }) return null
        val first = (fromSegment - lookBehindSegments).coerceIn(0, points.lastIndex - 1)
        var best: FarmRouteProjection? = null
        for (index in first until points.lastIndex) {
            val start = points[index]
            val end = points[index + 1]
            val dx = end.x - start.x
            val dy = end.y - start.y
            val dz = end.z - start.z
            val lengthSquared = dx * dx + dy * dy + dz * dz
            val rawT = if (lengthSquared == 0.0) 0.0 else
                ((x - start.x) * dx + (y - start.y) * dy + (z - start.z) * dz) / lengthSquared
            val t = rawT.coerceIn(0.0, 1.0)
            val projectedX = start.x + dx * t
            val projectedY = start.y + dy * t
            val projectedZ = start.z + dz * t
            val distanceX = x - projectedX
            val distanceY = y - projectedY
            val distanceZ = z - projectedZ
            val distance = sqrt(distanceX * distanceX + distanceY * distanceY + distanceZ * distanceZ)
            val candidate = FarmRouteProjection(index, t, projectedX, projectedY, projectedZ, distance)
            if (best == null || candidate.distance < best.distance) best = candidate
        }
        return best
    }

    fun reachedPoint(projection: FarmRouteProjection, pointsSize: Int): Int =
        (projection.segmentIndex + 1 + if (projection.t >= 0.72) 1 else 0).coerceIn(1, pointsSize)
}
