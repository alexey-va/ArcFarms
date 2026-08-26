package ru.ruscrafting.farms.domain

internal data class FarmSpacedSelection(
    val values: List<FarmMatureCrop>,
    val distanceChecks: Long,
)

/** Deterministic farthest-point selection with O(candidates × targets) distance work. */
internal object FarmSpacedPlotSelector {
    private data class Candidate(var value: FarmMatureCrop, var minimumDistanceSquared: Double)

    fun select(candidates: List<FarmMatureCrop>, count: Int, minimumSpacing: Double): FarmSpacedSelection {
        require(count >= 0) { "Spaced selection count must not be negative" }
        require(minimumSpacing.isFinite() && minimumSpacing >= 0.0) { "Spaced selection distance is invalid" }
        if (candidates.isEmpty() || count == 0) return FarmSpacedSelection(emptyList(), 0)
        val target = minOf(count, candidates.size)
        val selected = mutableListOf(candidates.first())
        var distanceChecks = 0L
        val remaining = candidates.drop(1).mapTo(mutableListOf()) { candidate ->
            distanceChecks++
            Candidate(candidate, distanceSquared(candidate.plot, selected.first().plot))
        }
        val minimumSquared = minimumSpacing * minimumSpacing
        while (selected.size < target && remaining.isNotEmpty()) {
            val spaced = remaining.indices.filter { remaining[it].minimumDistanceSquared >= minimumSquared }
            val pool = spaced.ifEmpty { remaining.indices.toList() }
            val chosenIndex = pool.maxWithOrNull(
                compareBy<Int> { remaining[it].minimumDistanceSquared }
                    .thenBy { remaining[it].value.plot.x }
                    .thenBy { remaining[it].value.plot.z }
                    .thenBy { remaining[it].value.plot.y },
            ) ?: break
            val chosen = remaining.removeAt(chosenIndex).value
            selected += chosen
            remaining.forEach { candidate ->
                distanceChecks++
                candidate.minimumDistanceSquared = minOf(
                    candidate.minimumDistanceSquared,
                    distanceSquared(candidate.value.plot, chosen.plot),
                )
            }
        }
        return FarmSpacedSelection(selected, distanceChecks)
    }

    private fun distanceSquared(first: FarmPlotPosition, second: FarmPlotPosition): Double {
        val dx = (first.x - second.x).toDouble()
        val dz = (first.z - second.z).toDouble()
        return dx * dx + dz * dz
    }
}
