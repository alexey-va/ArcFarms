package ru.ruscrafting.farms.domain

import java.lang.Math.floorMod
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Selects spaced field points around a varied middle ring, with the whole field as fallback. */
object FarmCentralPlotSelector {
    fun select(
        candidates: Collection<FarmPlotPosition>,
        count: Int,
        minimumSpacing: Double,
        selectionIndex: Long,
    ): List<FarmPlotPosition> {
        require(count >= 0) { "Central plot count must not be negative" }
        require(minimumSpacing.isFinite() && minimumSpacing >= 0.0) { "Central plot spacing is invalid" }
        val available = candidates.distinct().sortedWith(POSITION_ORDER)
        if (count == 0 || available.isEmpty()) return emptyList()
        if (available.size <= count) return available

        val geometry = geometry(available)
        val target = minOf(count, available.size)
        val selected = mutableListOf<FarmPlotPosition>()
        val remaining = available.toMutableList()
        val minimumSpacingSquared = minimumSpacing * minimumSpacing

        while (selected.size < target && remaining.isNotEmpty()) {
            val targetPoint = targetPoint(selected.size, target, selectionIndex)
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
                    compareBy<FarmPlotPosition> { targetDistanceSquared(it, geometry, targetPoint) }
                        .thenByDescending { candidate -> minimumDistanceSquared(candidate, selected) }
                        .thenBy { tieRank(it, selectionIndex xor selected.size.toLong()) }
                        .then(POSITION_ORDER),
                )
            } else {
                // A narrow or sparse field may not satisfy the preferred spacing. Keep
                // the event viable by relaxing spacing only after every valid ring and
                // full-field candidate has been attempted.
                pool.maxWith(
                    compareBy<FarmPlotPosition> { minimumDistanceSquared(it, selected) }
                        .thenByDescending { targetDistanceSquared(it, geometry, targetPoint) }
                        .thenBy { tieRank(it, selectionIndex) }
                        .then(POSITION_ORDER),
                )
            }
            selected += next
            remaining.remove(next)
        }
        return selected
    }

    private fun geometry(positions: Collection<FarmPlotPosition>): FieldGeometry {
        val minimumX = positions.minOf(FarmPlotPosition::x)
        val maximumX = positions.maxOf(FarmPlotPosition::x)
        val minimumZ = positions.minOf(FarmPlotPosition::z)
        val maximumZ = positions.maxOf(FarmPlotPosition::z)
        return FieldGeometry(
            centerX = (minimumX + maximumX) / 2.0,
            centerZ = (minimumZ + maximumZ) / 2.0,
            halfWidth = (maximumX - minimumX) / 2.0,
            halfDepth = (maximumZ - minimumZ) / 2.0,
        )
    }

    private fun targetPoint(slot: Int, count: Int, selectionIndex: Long): RingTarget {
        val phase = unit(mix(selectionIndex xor PHASE_SALT)) * 2.0 * PI
        val explore = floorMod(mix(selectionIndex xor (slot + 1L) * EXPLORE_SALT), EXPLORE_EVERY) == 0L
        val radiusUnit = unit(mix(selectionIndex xor (slot + 1L) * RADIUS_SALT))
        val radius = if (explore) {
            BROAD_TARGET_MIN + radiusUnit * (BROAD_TARGET_MAX - BROAD_TARGET_MIN)
        } else {
            PREFERRED_TARGET_MIN + radiusUnit * (PREFERRED_TARGET_MAX - PREFERRED_TARGET_MIN)
        }
        val jitter = (unit(mix(selectionIndex xor (slot + 1L) * ANGLE_SALT)) - 0.5) * ANGLE_JITTER
        val angle = phase + slot * (2.0 * PI / count) + jitter
        return RingTarget(radius * cos(angle), radius * sin(angle), explore)
    }

    private fun targetDistanceSquared(
        position: FarmPlotPosition,
        geometry: FieldGeometry,
        target: RingTarget,
    ): Double {
        val dx = normalized(position.x - geometry.centerX, geometry.halfWidth) - target.x
        val dz = normalized(position.z - geometry.centerZ, geometry.halfDepth) - target.z
        return dx * dx + dz * dz
    }

    private fun normalizedRadius(position: FarmPlotPosition, geometry: FieldGeometry): Double = hypot(
        normalized(position.x - geometry.centerX, geometry.halfWidth),
        normalized(position.z - geometry.centerZ, geometry.halfDepth),
    )

    private fun normalized(offset: Double, halfExtent: Double): Double =
        if (halfExtent == 0.0) 0.0 else offset / halfExtent

    private fun minimumDistanceSquared(candidate: FarmPlotPosition, selected: List<FarmPlotPosition>): Double =
        selected.minOfOrNull { horizontalDistanceSquared(candidate, it) } ?: Double.POSITIVE_INFINITY

    private fun unit(value: Long): Double = floorMod(value, UNIT_DENOMINATOR).toDouble() / UNIT_DENOMINATOR

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

    private fun horizontalDistanceSquared(first: FarmPlotPosition, second: FarmPlotPosition): Double {
        if (first.world != second.world) return Double.POSITIVE_INFINITY
        val dx = (first.x - second.x).toDouble()
        val dz = (first.z - second.z).toDouble()
        return dx * dx + dz * dz
    }

    private fun tieRank(position: FarmPlotPosition, selectionIndex: Long): Long {
        val coordinateSeed = position.x.toLong() * 73_856_093L xor
            position.y.toLong() * 19_349_663L xor position.z.toLong() * 83_492_791L
        return mix(selectionIndex xor coordinateSeed)
    }

    private fun mix(value: Long): Long {
        var mixed = value xor (value ushr 33)
        mixed *= -49064778989728563L
        mixed = mixed xor (mixed ushr 33)
        mixed *= -4265267296055464877L
        return mixed xor (mixed ushr 33)
    }

    private val POSITION_ORDER = compareBy<FarmPlotPosition>(
        FarmPlotPosition::world,
        FarmPlotPosition::y,
        FarmPlotPosition::x,
        FarmPlotPosition::z,
    )
}
