package ru.ruscrafting.farms.config

import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MineOrder
import ru.ruscrafting.farms.domain.MineResource

data class MineOrderSettings(
    val id: String,
    val prospectingRequired: Int,
    val miningRequired: Int,
    val loadingRequired: Int,
    val incidentTypes: List<MineIncidentType>,
    val miningMaterials: Set<String> = emptySet(),
    val miningRequirements: Map<String, Int> = emptyMap(),
    /** Canonical order fields.  Legacy exact-material fields above remain readable. */
    val miningResources: Set<String> = emptySet(),
    val resourceRequirements: Map<String, Int> = emptyMap(),
) {
    val requestedResources: Set<String> by lazy {
        MineResource.normalizeSet(miningResources + miningMaterials + miningRequirements.keys + resourceRequirements.keys)
    }

    val normalizedRequirements: Map<String, Int> by lazy {
        MineResource.normalizeRequirements(miningRequirements) + MineResource.normalizeRequirements(resourceRequirements)
    }

    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9_-]{0,47}"))) { "Invalid mine order id: $id" }
        require(listOf(prospectingRequired, miningRequired, loadingRequired).all { it in 1..100_000 }) {
            "Mine order $id has an invalid phase quota"
        }
        require(incidentTypes.size in 1..MineIncidentType.entries.size && incidentTypes.distinct().size == incidentTypes.size) {
            "Mine order $id must contain a non-empty distinct incident pool"
        }
        require((miningRequirements.values + resourceRequirements.values).all { it in 1..100_000 }) {
            "Mine order $id has invalid resource mining requirements"
        }
        require(normalizedRequirements.values.sumOf(Int::toLong) <= 100_000L) {
            "Mine order $id has too many resource mining requirements"
        }
        require(normalizedRequirements.isEmpty() || normalizedRequirements.keys == requestedResources) {
            "Mine order $id resource requirements must cover exactly requested resources"
        }
    }

    val totalMiningRequired: Int get() = normalizedRequirements.values.sum().takeIf { it > 0 } ?: miningRequired

    fun domain(): MineOrder = MineOrder(id, incidentTypes)
}

data class MineCartVisualSettings(
    val material: String = "MINECART",
    val customModelData: Int = 0,
    val itemModel: String? = null,
    val displayTransform: FarmItemDisplayTransform = FarmItemDisplayTransform.GROUND,
    val scale: Float = 1.0f,
    val yOffset: Double = 0.15,
    val viewRange: Float = 2.0f,
    val interactionWidth: Float = 1.5f,
    val interactionHeight: Float = 1.0f,
) {
    init {
        require(customModelData >= 0) { "Mine cart custom-model-data must not be negative" }
        require(itemModel == null || itemModel.matches(Regex("[a-z0-9._-]+:[a-z0-9/._-]+"))) {
            "Mine cart item-model must be a namespaced item model"
        }
        require(scale in 0.05f..8.0f) { "Mine cart scale must be in 0.05..8.0" }
        require(yOffset in -4.0..4.0) { "Mine cart y-offset must be in -4.0..4.0" }
        require(viewRange in 0.25f..64.0f) { "Mine cart view-range must be in 0.25..64.0" }
        require(interactionWidth in 0.1f..16.0f) { "Mine cart interaction-width must be in 0.1..16.0" }
        require(interactionHeight in 0.1f..16.0f) { "Mine cart interaction-height must be in 0.1..16.0" }
    }
}
