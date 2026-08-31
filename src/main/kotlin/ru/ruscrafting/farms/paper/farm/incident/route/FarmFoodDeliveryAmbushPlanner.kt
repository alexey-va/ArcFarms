package ru.ruscrafting.farms.paper.farm.incident.route

import ru.ruscrafting.farms.domain.FarmPointPosition
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.random.Random

/** Places stable but varied ambushes in separated route-distance bands. */
internal object FarmFoodDeliveryAmbushPlanner {
    /** Returns one-based route progress checkpoints. */
    fun checkpoints(
        points: List<FarmPointPosition>,
        distancePerAmbush: Double,
        maxAmbushes: Int,
        firstAllowedDistance: Double,
        endSafeDistance: Double,
        placementSeed: Long,
    ): List<Int> {
        if (points.size < 3 || maxAmbushes <= 0) return emptyList()
        val cumulative = DoubleArray(points.size)
        points.zipWithNext().forEachIndexed { index, (from, to) ->
            val dx = to.x - from.x
            val dy = to.y - from.y
            val dz = to.z - from.z
            cumulative[index + 1] = cumulative[index] + sqrt(dx * dx + dy * dy + dz * dz)
        }
        val total = cumulative.last()
        val firstDistance = firstAllowedDistance
        val lastDistance = total - endSafeDistance
        val eligibleDistance = lastDistance - firstDistance
        if (eligibleDistance <= 0.0 || eligibleDistance < distancePerAmbush * MINIMUM_ROUTE_FRACTION) return emptyList()

        val count = floor(eligibleDistance / distancePerAmbush).toInt().coerceIn(1, maxAmbushes)
        val bandWidth = eligibleDistance / count
        val random = Random(placementSeed xor (points.size.toLong() shl 32))
        return (0 until count).mapNotNull { band ->
            val bandStart = firstDistance + bandWidth * band
            val target = bandStart + bandWidth * random.nextDouble(BAND_INSET, 1.0 - BAND_INSET)
            cumulative.indexOfFirst { it >= target }
                .takeIf { it in 1 until points.lastIndex }
                ?.plus(1)
        }.distinct()
    }

    fun distanceAt(points: List<FarmPointPosition>, index: Int): Double {
        if (index <= 0) return 0.0
        return points.zipWithNext().take(index).sumOf { (from, to) ->
            val dx = to.x - from.x
            val dy = to.y - from.y
            val dz = to.z - from.z
            sqrt(dx * dx + dy * dy + dz * dz)
        }
    }

    private const val MINIMUM_ROUTE_FRACTION = 0.5
    private const val BAND_INSET = 0.25
}
