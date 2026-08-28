package ru.ruscrafting.farms.paper.farm.care

import ru.ruscrafting.farms.domain.FarmCareRole

internal data class FarmCareEntityKey(val zoneId: String, val targetId: Int)

internal data class FarmCareEntityIdentity(
    val zoneId: String,
    val sequence: Long,
    val targetId: Int,
    val role: FarmCareRole,
)
