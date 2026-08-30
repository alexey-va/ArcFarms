package ru.ruscrafting.farms.domain.placement

import java.lang.Math.floorMod
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Favors varied targets on a middle ellipse, occasionally explores the wider
 * field, then falls back through the broad ring, all spaced points, and finally
 * spacing relaxation. No valid candidate is discarded by the preference.
 */
object BalancedRingPlacementStrategy : WorksitePlacementStrategy {
    override val id: String = "balanced_ring"

    override fun select(
        candidates: List<WorksitePlacementPoint>,
        request: WorksitePlacementRequest,
        minimumSpacing: Double,
    ): List<WorksitePlacementPoint> {
        val available = candidates.distinct().sortedWith(WorksitePlacementPlanner.POINT_ORDER)
        if (request.count == 0 || available.isEmpty()) return emptyList()
        if (available.size <= request.count) return available

        val geometry = geometry(available)
        val target = minOf(request.count, available.size)
        val selected = mutableListOf<WorksitePlacementPoint>()
        val remaining = available.toMutableList()
        val minimumSpacingSquared = minimumSpacing * minimumSpacing

        while (selected.size < target && remaining.isNotEmpty()) {
            val targetPoint = targetPoint(selected.size, target, request.seed)
            val spaced = remaining.filter { candidate ->
                selected.all { existing -> horizontalDistanceSquared(candidate, existing) >= minimumSpacingSquared }
            }
            val preferred = spaced.filter { candidate ->
                val radius = normalizedRadius(candidate, geometry)
                radius in if (targetPoint.explore) BROAD_RING else PREFERRED_RING
            }
            val broad = spaced.filter { normalizedRadius(it, geometry) in BROAD_RING }
            val pool = when {
                preferred.isNotEmpty() -> preferred
                broad.isNotEmpty() -> broad
                spaced.isNotEmpty() -> spaced
                else -> remaining
            }
            val next = if (spaced.isNotEmpty() || selected.isEmpty()) {
                pool.minWith(
                    compareBy<WorksitePlacementPoint> { targetDistanceSquared(it, geometry, targetPoint) }
                        .thenByDescending { candidate -> minimumDistanceSquared(candidate, selected) }
                        .thenBy { tieRank(it, request.seed xor selected.size.toLong()) }
                        .then(WorksitePlacementPlanner.POINT_ORDER),
                )
            } else {
                pool.maxWith(
                    compareBy<WorksitePlacementPoint> { minimumDistanceSquared(it, selected) }
                        .thenByDescending { targetDistanceSquared(it, geometry, targetPoint) }
                        .thenBy { tieRank(it, request.seed) }
                        .then(WorksitePlacementPlanner.POINT_ORDER),
                )
            }
            selected += next
            remaining.remove(next)
        }
        return selected
    }

    private fun geometry(positions: Collection<WorksitePlacementPoint>): FieldGeometry = FieldGeometry(
        centerX = (positions.minOf(WorksitePlacementPoint::x) + positions.maxOf(WorksitePlacementPoint::x)) / 2.0,
        centerZ = (positions.minOf(WorksitePlacementPoint::z) + positions.maxOf(WorksitePlacementPoint::z)) / 2.0,
        halfWidth = (positions.maxOf(WorksitePlacementPoint::x) - positions.minOf(WorksitePlacementPoint::x)) / 2.0,
        halfDepth = (positions.maxOf(WorksitePlacementPoint::z) - positions.minOf(WorksitePlacementPoint::z)) / 2.0,
    )

    private fun targetPoint(slot: Int, count: Int, seed: Long): RingTarget {
        val phase = unit(mix(seed xor PHASE_SALT)) * 2.0 * PI
        val explore = floorMod(mix(seed xor (slot + 1L) * EXPLORE_SALT), EXPLORE_EVERY) == 0L
        val radiusUnit = unit(mix(seed xor (slot + 1L) * RADIUS_SALT))
        val radius = if (explore) {
            BROAD_TARGET_MIN + radiusUnit * (BROAD_TARGET_MAX - BROAD_TARGET_MIN)
        } else {
            PREFERRED_TARGET_MIN + radiusUnit * (PREFERRED_TARGET_MAX - PREFERRED_TARGET_MIN)
        }
        val jitter = (unit(mix(seed xor (slot + 1L) * ANGLE_SALT)) - 0.5) * ANGLE_JITTER
        val angle = phase + slot * (2.0 * PI / count) + jitter
        return RingTarget(radius * cos(angle), radius * sin(angle), explore)
    }

    private fun targetDistanceSquared(
        position: WorksitePlacementPoint,
        geometry: FieldGeometry,
        target: RingTarget,
    ): Double {
        val dx = normalized(position.x - geometry.centerX, geometry.halfWidth) - target.x
        val dz = normalized(position.z - geometry.centerZ, geometry.halfDepth) - target.z
        return dx * dx + dz * dz
    }

    private fun normalizedRadius(position: WorksitePlacementPoint, geometry: FieldGeometry): Double = hypot(
        normalized(position.x - geometry.centerX, geometry.halfWidth),
        normalized(position.z - geometry.centerZ, geometry.halfDepth),
    )

    private fun normalized(offset: Double, halfExtent: Double): Double = if (halfExtent == 0.0) 0.0 else offset / halfExtent

    private fun minimumDistanceSquared(
        candidate: WorksitePlacementPoint,
        selected: List<WorksitePlacementPoint>,
    ): Double = selected.minOfOrNull { horizontalDistanceSquared(candidate, it) } ?: Double.POSITIVE_INFINITY

    private fun horizontalDistanceSquared(first: WorksitePlacementPoint, second: WorksitePlacementPoint): Double {
        if (first.world != second.world) return Double.POSITIVE_INFINITY
        val dx = first.x - second.x
        val dz = first.z - second.z
        return dx * dx + dz * dz
    }

    private fun unit(value: Long): Double = floorMod(value, UNIT_DENOMINATOR).toDouble() / UNIT_DENOMINATOR

    private fun tieRank(position: WorksitePlacementPoint, seed: Long): Long {
        val coordinateSeed = java.lang.Double.doubleToLongBits(position.x) xor
            java.lang.Long.rotateLeft(java.lang.Double.doubleToLongBits(position.y), 17) xor
            java.lang.Long.rotateLeft(java.lang.Double.doubleToLongBits(position.z), 33) xor position.world.hashCode().toLong()
        return mix(seed xor coordinateSeed)
    }

    private fun mix(value: Long): Long {
        var mixed = value xor (value ushr 33)
        mixed *= -49064778989728563L
        mixed = mixed xor (mixed ushr 33)
        mixed *= -4265267296055464877L
        return mixed xor (mixed ushr 33)
    }

    private data class FieldGeometry(
        val centerX: Double,
        val centerZ: Double,
        val halfWidth: Double,
        val halfDepth: Double,
    )

    private data class RingTarget(val x: Double, val z: Double, val explore: Boolean)

    private val PREFERRED_RING = 0.40..0.84
    private val BROAD_RING = 0.24..1.16
    private const val PREFERRED_TARGET_MIN = 0.52
    private const val PREFERRED_TARGET_MAX = 0.72
    private const val BROAD_TARGET_MIN = 0.30
    private const val BROAD_TARGET_MAX = 1.10
    private const val ANGLE_JITTER = 0.30
    private const val EXPLORE_EVERY = 9L
    private const val UNIT_DENOMINATOR = 1_000_003L
    private const val PHASE_SALT = 0x63D83595L
    private const val EXPLORE_SALT = 0x4F1BBCDCL
    private const val RADIUS_SALT = 0x2C1B3C6DL
    private const val ANGLE_SALT = 0x7A4D91E3L
}
