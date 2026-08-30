package ru.ruscrafting.farms.paper.lumber

import ru.ruscrafting.farms.config.LumberOrderSettings
import ru.ruscrafting.farms.config.LumberZoneSettings
import ru.ruscrafting.farms.domain.LumberRules
import ru.ruscrafting.farms.domain.LumberShiftState
import ru.ruscrafting.farms.domain.LumberStateMigration
import ru.ruscrafting.farms.paper.ActivityRegion
import ru.ruscrafting.farms.paper.RegionGateway

internal data class LumberRuntime(
    val settings: LumberZoneSettings,
    val region: ActivityRegion,
    val station: ActivityRegion,
    val cooldownMillis: Long,
    var state: LumberShiftState,
) {
    val orders: Map<String, LumberOrderSettings> = settings.orders.associateBy(LumberOrderSettings::id)

    fun currentOrder(): LumberOrderSettings? = state.orderId?.let(orders::get)

    fun nextOrder(): LumberOrderSettings = settings.orders[(state.sequence % settings.orders.size).toInt()]

    fun rules(order: LumberOrderSettings = currentOrder() ?: nextOrder()): LumberRules = LumberRules(
        fellingQuota = order.fellingRequired,
        processingQuota = order.sawingRequired,
        processingPerUse = 1,
        cooldownMillis = cooldownMillis,
        skiddingQuota = order.skiddingRequired,
        sawingQuota = order.sawingRequired,
        stackingQuota = order.stackingRequired,
        targetMultiplier = settings.targetMultiplier,
        incidentCountMin = settings.incidentCountMin,
        incidentCountMax = settings.incidentCountMax,
    )
}

internal object LumberRuntimeFactory {
    fun build(
        configured: List<LumberZoneSettings>,
        persisted: Map<String, LumberShiftState>,
        cooldownMillis: Long,
        regions: RegionGateway,
    ): List<LumberRuntime> = configured.map { settings ->
        require(settings.engineVersion == 2) { "Legacy lumber zone ${settings.id} cannot enter the V2 runtime" }
        val region = requireNotNull(regions.resolve(settings.reference)) {
            "Lumber zone ${settings.id} cannot resolve ${settings.reference}"
        }
        val station = requireNotNull(regions.resolve(settings.station)) {
            "Lumber station ${settings.id} cannot resolve ${settings.station}"
        }
        LumberRuntime(
            settings = settings,
            region = region,
            station = station,
            cooldownMillis = cooldownMillis,
            state = LumberStateMigration.migrate(persisted[settings.id] ?: LumberShiftState()),
        )
    }
}
