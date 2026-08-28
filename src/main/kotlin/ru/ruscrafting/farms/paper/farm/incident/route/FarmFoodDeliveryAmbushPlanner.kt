package ru.ruscrafting.farms.paper.farm.incident.route

import ru.ruscrafting.farms.domain.FarmPointPosition
import kotlin.math.floor
import kotlin.math.sqrt

/** Places a bounded number of ambushes by travelled route distance. */
internal object FarmFoodDeliveryAmbushPlanner {
    /** Returns one-based route progress checkpoints. */
    fun checkpoints(
        points: List<FarmPointPosition>,
        distancePerAmbush: Double,
        maxAmbushes: Int,
        firstAllowedDistance: Double,
        endSafeDistance: Double,
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
        val spacing = eligibleDistance / (count + 1)
        return (1..count).mapNotNull { ordinal ->
            val target = firstDistance + spacing * ordinal
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
}
