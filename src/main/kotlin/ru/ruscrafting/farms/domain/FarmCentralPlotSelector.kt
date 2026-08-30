package ru.ruscrafting.farms.domain

import java.lang.Math.floorMod

/** Selects spaced field points from the center outward, using outer beds only when needed. */
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

        val centerX = (available.minOf(FarmPlotPosition::x) + available.maxOf(FarmPlotPosition::x)) / 2.0
        val centerZ = (available.minOf(FarmPlotPosition::z) + available.maxOf(FarmPlotPosition::z)) / 2.0
        val target = minOf(count, available.size)
        val seedBand = available.sortedWith(
            compareBy<FarmPlotPosition> { centralDistanceSquared(it, centerX, centerZ) }
                .thenBy { tieRank(it, selectionIndex) }
                .then(POSITION_ORDER),
        ).take(minOf(available.size, maxOf(4, target * 3)))
        val seed = seedBand[floorMod(mix(selectionIndex), seedBand.size.toLong()).toInt()]
        val selected = mutableListOf(seed)
        val remaining = available.filterTo(mutableListOf()) { it != seed }
        val minimumSpacingSquared = minimumSpacing * minimumSpacing

        while (selected.size < target && remaining.isNotEmpty()) {
            val spaced = remaining.filter { candidate ->
                selected.all { existing -> horizontalDistanceSquared(candidate, existing) >= minimumSpacingSquared }
            }
            val next = if (spaced.isNotEmpty()) {
                spaced.minWith(
                    compareBy<FarmPlotPosition> { centralDistanceSquared(it, centerX, centerZ) }
                        .thenByDescending { candidate ->
                            selected.minOf { existing -> horizontalDistanceSquared(candidate, existing) }
                        }
                        .thenBy { tieRank(it, selectionIndex) }
                        .then(POSITION_ORDER),
                )
            } else {
                // A narrow or sparse field may not satisfy the preferred spacing. Keep
                // the event viable by taking the most separated remaining bed.
                remaining.maxWith(
                    compareBy<FarmPlotPosition> { candidate ->
                        selected.minOf { existing -> horizontalDistanceSquared(candidate, existing) }
                    }.thenByDescending { centralDistanceSquared(it, centerX, centerZ) }
                        .thenBy { tieRank(it, selectionIndex) }
                        .then(POSITION_ORDER),
                )
            }
            selected += next
            remaining.remove(next)
        }
        return selected
    }

    private fun centralDistanceSquared(position: FarmPlotPosition, centerX: Double, centerZ: Double): Double {
        val dx = position.x - centerX
        val dz = position.z - centerZ
        return dx * dx + dz * dz
    }

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
