package ru.ruscrafting.farms.config

import ru.ruscrafting.farms.domain.LumberIncidentType
import ru.ruscrafting.farms.domain.LumberOrder

data class LumberOrderSettings(
    val id: String,
    val species: List<String>,
    val fellingRequired: Int,
    val skiddingRequired: Int,
    val sawingRequired: Int,
    val stackingRequired: Int,
    val incidentTypes: List<LumberIncidentType>,
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9_-]{0,47}"))) { "Invalid lumber order id: $id" }
        require(species.isNotEmpty() && species.distinct().size == species.size) { "Lumber order $id has invalid species" }
        require(listOf(fellingRequired, skiddingRequired, sawingRequired, stackingRequired).all { it in 1..100_000 }) {
            "Lumber order $id has an invalid phase quota"
        }
        require(incidentTypes.size in 3..5 && incidentTypes.distinct().size == incidentTypes.size) {
            "Lumber order $id must contain three to five distinct incidents"
        }
    }

    fun domain(): LumberOrder = LumberOrder(id, species, incidentTypes)
}

data class LumberZoneSettings(
    val id: String,
    val reference: ZoneReference,
    val station: ZoneReference,
    val permission: String,
    val fellingQuota: Int,
    val processingQuota: Int,
    val processingPerUse: Int,
    val species: List<String>,
    val stationMaterials: Set<String>,
    val engineVersion: Int = 1,
    val orders: List<LumberOrderSettings> = emptyList(),
    val targetMultiplier: Int = 2,
    val incidentCountMin: Int = 3,
    val incidentCountMax: Int = 5,
    val recoverySeconds: Int = 90,
    val rewards: FarmRewardSettings = defaultWorksiteRewards(110),
) {
    init {
        require(engineVersion in 1..2) { "Lumber zone $id engine-version must be 1 or 2" }
        require(targetMultiplier in 2..4) { "Lumber zone $id target-multiplier must be 2..4" }
        require(incidentCountMin in 3..5 && incidentCountMax in incidentCountMin..5) {
            "Lumber zone $id incident count range is invalid"
        }
        require(recoverySeconds in 5..3_600) { "Lumber zone $id recovery-seconds is invalid" }
        require(engineVersion == 1 || orders.isNotEmpty()) { "Lumber V2 zone $id has no orders" }
        require(orders.all { it.incidentTypes.size >= incidentCountMax }) {
            "Lumber zone $id order has fewer incidents than incident-count-max"
        }
    }
}
