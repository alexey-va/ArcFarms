package ru.ruscrafting.farms.config

import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MineOrder

data class MineOrderSettings(
    val id: String,
    val prospectingRequired: Int,
    val miningRequired: Int,
    val loadingRequired: Int,
    val incidentTypes: List<MineIncidentType>,
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9_-]{0,47}"))) { "Invalid mine order id: $id" }
        require(listOf(prospectingRequired, miningRequired, loadingRequired).all { it in 1..100_000 }) {
            "Mine order $id has an invalid phase quota"
        }
        require(incidentTypes.size in 3..5 && incidentTypes.distinct().size == incidentTypes.size) {
            "Mine order $id must contain three to five distinct incidents"
        }
    }

    fun domain(): MineOrder = MineOrder(id, incidentTypes)
}
