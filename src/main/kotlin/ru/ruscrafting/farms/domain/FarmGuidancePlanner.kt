package ru.ruscrafting.farms.domain

import java.lang.Math.floorMod

object FarmGuidancePlanner {
    fun individualMissingPlots(
        remaining: Collection<FarmPlotPosition>,
        threshold: Int,
    ): List<FarmPlotPosition> {
        require(threshold > 0) { "Missing plot threshold must be positive" }
        if (remaining.isEmpty() || remaining.size > threshold) return emptyList()
        return remaining.sortedWith(compareBy(FarmPlotPosition::world, FarmPlotPosition::x, FarmPlotPosition::y, FarmPlotPosition::z))
    }
}

object FarmDeliveryPlanner {
    private const val NEAREST_CANDIDATE_LIMIT = 24

    fun selectAnchor(
        candidates: Collection<FarmDeliveryPosition>,
        anchorX: Double,
        anchorZ: Double,
        selectionIndex: Int,
    ): FarmDeliveryPosition? {
        val nearest = candidates
            .sortedWith(
                compareBy<FarmDeliveryPosition> { position ->
                    val dx = position.x - anchorX
                    val dz = position.z - anchorZ
                    dx * dx + dz * dz
                }.thenBy(FarmDeliveryPosition::x)
                    .thenBy(FarmDeliveryPosition::y)
                    .thenBy(FarmDeliveryPosition::z),
            )
            .take(NEAREST_CANDIDATE_LIMIT)
        if (nearest.isEmpty()) return null
        return nearest[floorMod(selectionIndex, nearest.size)]
    }

    fun selectTargets(
        candidates: Collection<FarmDeliveryPosition>,
        objectiveX: Double,
        objectiveZ: Double,
        participants: Collection<Pair<Double, Double>>,
        minimumObjectiveDistance: Double,
        maximumParticipantDistance: Double,
        targetCount: Int,
        selectionIndex: Long,
    ): List<FarmDeliveryPosition> {
        require(minimumObjectiveDistance >= 0.0 && minimumObjectiveDistance.isFinite()) {
            "Minimum objective distance is invalid"
        }
        require(maximumParticipantDistance > 0.0 && maximumParticipantDistance.isFinite()) {
            "Maximum participant distance is invalid"
        }
        require(targetCount > 0) { "Placement target count must be positive" }
        val unique = candidates.distinct().sortedWith(
            compareBy(FarmDeliveryPosition::world, FarmDeliveryPosition::x, FarmDeliveryPosition::y, FarmDeliveryPosition::z),
        )
        if (unique.isEmpty()) return emptyList()
        val minimumSquared = minimumObjectiveDistance * minimumObjectiveDistance
        val objectiveSafe = unique.filter { candidate ->
            horizontalDistanceSquared(candidate.x, candidate.z, objectiveX, objectiveZ) >= minimumSquared
        }.ifEmpty { unique }
        val maximumSquared = maximumParticipantDistance * maximumParticipantDistance
        val participantSafe = objectiveSafe.filter { candidate ->
            participants.isEmpty() || participants.minOf { (x, z) ->
                horizontalDistanceSquared(candidate.x, candidate.z, x, z)
            } <= maximumSquared
        }.ifEmpty { objectiveSafe }
        val nearest = participantSafe.sortedWith(
            compareBy<FarmDeliveryPosition> { candidate ->
                participants.minOfOrNull { (x, z) -> horizontalDistanceSquared(candidate.x, candidate.z, x, z) }
                    ?: horizontalDistanceSquared(candidate.x, candidate.z, objectiveX, objectiveZ)
            }.thenBy(FarmDeliveryPosition::x)
                .thenBy(FarmDeliveryPosition::y)
                .thenBy(FarmDeliveryPosition::z),
        ).take(maxOf(NEAREST_CANDIDATE_LIMIT, targetCount))
        val selected = mutableListOf(nearest[floorMod(selectionIndex, nearest.size.toLong()).toInt()])
        while (selected.size < minOf(targetCount, nearest.size)) {
            val next = nearest.asSequence().filterNot(selected::contains).maxWithOrNull(
                compareBy<FarmDeliveryPosition> { candidate ->
                    selected.minOf { existing ->
                        horizontalDistanceSquared(candidate.x, candidate.z, existing.x, existing.z)
                    }
                }.thenByDescending { candidate ->
                    participants.minOfOrNull { (x, z) -> horizontalDistanceSquared(candidate.x, candidate.z, x, z) }
                        ?: 0.0
                }.thenByDescending(FarmDeliveryPosition::x)
                    .thenByDescending(FarmDeliveryPosition::z),
            ) ?: break
            selected += next
        }
        return selected
    }

    private fun horizontalDistanceSquared(firstX: Double, firstZ: Double, secondX: Double, secondZ: Double): Double {
        val dx = firstX - secondX
        val dz = firstZ - secondZ
        return dx * dx + dz * dz
    }
}
