package ru.ruscrafting.farms.domain

/** Deterministically assigns each managed bed to its nearest matching care target. */
object FarmCarePlotAssignment {
    fun assignments(
        patch: Collection<FarmPlotPosition>,
        targets: Collection<FarmCareTarget>,
    ): Map<Int, Set<FarmPlotPosition>> {
        if (targets.isEmpty()) return emptyMap()
        val assigned: Map<Int, MutableSet<FarmPlotPosition>> =
            targets.associate { it.id to linkedSetOf<FarmPlotPosition>() }
        patch.forEach { plot ->
            val nearest = targets.minWith(
                compareBy<FarmCareTarget> { candidate ->
                    val dx = plot.x + 0.5 - candidate.position.x
                    val dz = plot.z + 0.5 - candidate.position.z
                    dx * dx + dz * dz
                }.thenBy(FarmCareTarget::id),
            )
            assigned.getValue(nearest.id).add(plot)
        }
        return assigned
    }

    fun assignedTo(
        patch: Collection<FarmPlotPosition>,
        targets: Collection<FarmCareTarget>,
        targetId: Int,
    ): Set<FarmPlotPosition> {
        return assignments(patch, targets)[targetId].orEmpty()
    }
}
