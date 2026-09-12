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
    var settings: MineZoneSettings,
    var region: ActivityRegion,
    var cooldownMillis: Long,
    var state: MineShiftState,
) {
    val orders: Map<String, MineOrderSettings> get() = settings.orders.associateBy(MineOrderSettings::id)
    val railMaterials get() = settings.extractionRailMaterials.mapTo(linkedSetOf(), MaterialRules::material)
    val mineableMaterials get() = (if (settings.miningOnly) {
        settings.orders.asSequence().flatMap { it.miningMaterials.asSequence() }
    } else {
        settings.materialWeights.keys.asSequence()
    }).mapTo(linkedSetOf(), MaterialRules::material)

    fun currentOrder(): MineOrderSettings? = state.orderId?.let(orders::get)

    fun nextOrder(): MineOrderSettings = settings.orders[(state.sequence % settings.orders.size).toInt()]

    fun rules(order: MineOrderSettings = defaultOrder()): MineRules = MineRules(
        miningOnly = settings.miningOnly,
        cartQuota = order.totalMiningRequired,
        hazardTrigger = minOf(settings.hazardTrigger, order.totalMiningRequired - 1).coerceAtLeast(1),
        supportsRequired = settings.supportsRequired,
        cooldownMillis = cooldownMillis,
        prospectingQuota = order.prospectingRequired,
        miningQuota = order.totalMiningRequired,
        loadingQuota = order.loadingRequired,
        targetMultiplier = settings.targetMultiplier,
        incidentCountMin = settings.incidentCountMin,
        incidentCountMax = settings.incidentCountMax,
    )

    private fun defaultOrder(): MineOrderSettings = currentOrder() ?: run {
        check(state.phase in setOf(ru.ruscrafting.farms.domain.MinePhase.IDLE, ru.ruscrafting.farms.domain.MinePhase.COOLDOWN)) {
            "Active mine order ${settings.id}/${state.orderId} is missing from runtime settings"
        }
        nextOrder()
    }
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
        MaterialRules.material(settings.cartVisual.material)
        settings.materialWeights.keys.forEach(MaterialRules::material)
        settings.extractionRailMaterials.forEach(MaterialRules::material)
        MineRuntime(
            settings = settings,
            region = requireNotNull(regions.resolve(settings.reference)) {
                "Mine zone ${settings.id} cannot resolve ${settings.reference}"
            },
            cooldownMillis = cooldownMillis,
            state = migrate(settings, persisted[settings.id] ?: MineShiftState()),
        )
    }

    /** Startup validation and runtime construction must apply the same compatibility migration. */
    fun migrate(settings: MineZoneSettings, persisted: MineShiftState): MineShiftState = MineStateMigration.migrate(persisted).let { saved ->
        val allowed = settings.orders.flatMap { it.incidentTypes }.toSet()
        if (settings.miningOnly && saved.phase !in setOf(ru.ruscrafting.farms.domain.MinePhase.IDLE,
                ru.ruscrafting.farms.domain.MinePhase.COOLDOWN) &&
            (saved.orderId !in settings.orders.map { it.id } ||
                saved.objective?.key?.objectiveId == "mining" || saved.resumeObjective?.key?.objectiveId == "mining" ||
                saved.incidentSchedule.any { it !in allowed } || saved.phase in setOf(
                ru.ruscrafting.farms.domain.MinePhase.PROSPECTING, ru.ruscrafting.farms.domain.MinePhase.LOADING))) {
            MineShiftState(engineVersion = 2, sequence = saved.sequence)
        } else if (settings.miningOnly && saved.phase !in setOf(
                ru.ruscrafting.farms.domain.MinePhase.IDLE, ru.ruscrafting.farms.domain.MinePhase.COOLDOWN,
            ) && saved.mined > 0 && saved.minedByMaterial.isEmpty()) {
            val order = settings.orders.firstOrNull { it.id == saved.orderId }
            if (order?.miningRequirements?.isNotEmpty() == true) {
                var remaining = saved.mined
                val migrated = linkedMapOf<String, Int>()
                order.miningMaterials.forEach { material ->
                    val assigned = minOf(remaining, order.miningRequirements[material] ?: 0)
                    if (assigned > 0) migrated[material] = assigned
                    remaining -= assigned
                }
                val total = migrated.values.sum()
                saved.copy(mined = total, cart = total, minedByMaterial = migrated)
            } else saved
        } else saved
    }
}
