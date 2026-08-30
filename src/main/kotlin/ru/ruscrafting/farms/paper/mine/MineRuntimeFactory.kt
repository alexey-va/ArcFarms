package ru.ruscrafting.farms.paper.mine

import ru.ruscrafting.farms.config.MineOrderSettings
import ru.ruscrafting.farms.config.MineZoneSettings
import ru.ruscrafting.farms.domain.MineRules
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.MineStateMigration
import ru.ruscrafting.farms.paper.ActivityRegion
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.RegionGateway

internal data class MineRuntime(
    val settings: MineZoneSettings,
    val region: ActivityRegion,
    val cooldownMillis: Long,
    var state: MineShiftState,
) {
    val orders: Map<String, MineOrderSettings> = settings.orders.associateBy(MineOrderSettings::id)

    fun currentOrder(): MineOrderSettings? = state.orderId?.let(orders::get)

    fun nextOrder(): MineOrderSettings = settings.orders[(state.sequence % settings.orders.size).toInt()]

    fun rules(order: MineOrderSettings = currentOrder() ?: nextOrder()): MineRules = MineRules(
        cartQuota = order.miningRequired,
        hazardTrigger = minOf(settings.hazardTrigger, order.miningRequired - 1).coerceAtLeast(1),
        supportsRequired = settings.supportsRequired,
        cooldownMillis = cooldownMillis,
        prospectingQuota = order.prospectingRequired,
        miningQuota = order.miningRequired,
        loadingQuota = order.loadingRequired,
        targetMultiplier = settings.targetMultiplier,
        incidentCountMin = settings.incidentCountMin,
        incidentCountMax = settings.incidentCountMax,
    )
}

internal object MineRuntimeFactory {
    fun build(
        configured: List<MineZoneSettings>,
        persisted: Map<String, MineShiftState>,
        cooldownMillis: Long,
        regions: RegionGateway,
    ): List<MineRuntime> = configured.map { settings ->
        require(settings.engineVersion == 2) { "Legacy mine zone ${settings.id} cannot enter the V2 runtime" }
        MaterialRules.material(settings.temporaryMaterial)
        MaterialRules.material(settings.baseMaterial)
        settings.materialWeights.keys.forEach(MaterialRules::material)
        MineRuntime(
            settings = settings,
            region = requireNotNull(regions.resolve(settings.reference)) {
                "Mine zone ${settings.id} cannot resolve ${settings.reference}"
            },
            cooldownMillis = cooldownMillis,
            state = MineStateMigration.migrate(persisted[settings.id] ?: MineShiftState()),
        )
    }
}
