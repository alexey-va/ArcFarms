package ru.ruscrafting.farms.domain.lumber

import ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveState
import java.util.UUID

enum class LumberIncidentType { FEED_JAM, SAWDUST_SMOLDER }

/** A foreground repair holds the baseline at an already idle machine boundary. */
data class LumberIncidentState(
    val type: LumberIncidentType,
    val generation: Long,
    val crankTurns: Int = 0,
    val crankOperator: UUID? = null,
    val hotspots: WorksiteObjectiveState? = null,
)
