package ru.ruscrafting.farms.domain

/** Deterministically assigns each managed bed to its nearest matching care target. */
object FarmCarePlotAssignment {
    fun assignedTo(
        patch: Collection<FarmPlotPosition>,
        targets: Collection<FarmCareTarget>,
        targetId: Int,
    ): Set<FarmPlotPosition> {
        if (targets.isEmpty()) return emptySet()
        return patch.filterTo(linkedSetOf()) { plot ->
            targets.minWith(
                compareBy<FarmCareTarget> { candidate ->
                    val dx = plot.x + 0.5 - candidate.position.x
                    val dz = plot.z + 0.5 - candidate.position.z
                    dx * dx + dz * dz
                }.thenBy(FarmCareTarget::id),
            ).id == targetId
        }
    }
}
