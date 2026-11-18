package ru.ruscrafting.farms.paper.mine

import org.bukkit.Material
import ru.ruscrafting.farms.config.MineOrderSettings
import ru.ruscrafting.farms.config.MineZoneSettings
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineRules
import ru.ruscrafting.farms.domain.MineResource
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
    private var cachedMaterials: MaterialSets? = null

    val orders: Map<String, MineOrderSettings> get() = settings.orders.associateBy(MineOrderSettings::id)
    val railMaterials get() = materialSets().rails
    val mineableMaterials get() = materialSets().mineable
    /** Structural stone and generated ores that may form a real cave ceiling above a procedural collapse. */
    val caveCeilingMaterials get() = materialSets().caveCeiling

    private fun materialSets(): MaterialSets {
        val current = settings
        val cached = cachedMaterials
        if (cached != null && cached.settings === current) return cached
        return MaterialSets(current).also { cachedMaterials = it }
    }

    private class MaterialSets(val settings: MineZoneSettings) {
        val rails = buildSet<Material> {
            settings.extractionRailMaterials.mapTo(this, MaterialRules::material)
        }
        val mineable = buildSet<Material> {
            (if (settings.miningOnly) {
                settings.orders.asSequence().flatMap { order ->
                    order.requestedResources.asSequence().flatMap(MineResource::variants)
                }
            } else {
                settings.materialWeights.keys.asSequence()
            }).mapTo(this, MaterialRules::material)
        }
        val caveCeiling = buildSet {
            add(MaterialRules.material(settings.baseMaterial))
            settings.materialWeights.keys.mapTo(this, MaterialRules::material)
            addAll(mineable)
        }
    }

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
    fun migrate(settings: MineZoneSettings, persisted: MineShiftState): MineShiftState = MineStateMigration.migrate(persisted).let { original ->
        val order = settings.orders.firstOrNull { it.id == original.orderId }
        val normalized = normalizeResourceProgress(order, original)
        // Inventory cleanup precedes runtime reconfiguration, while its input
        // snapshot can predate that cleanup. Working supplies are reissued on
        // demand; restoring their leases would reserve cargo for absent items.
        val saved = normalized.incident?.takeIf { it.working != null }?.let { incident ->
            normalized.copy(incident = incident.copy(serviceLeases = emptyMap()))
        } ?: normalized
        val allowed = settings.orders.flatMap { it.incidentTypes }.toSet()
        val incompatibleIncident = saved.incident?.let { incident ->
            incident.scenarioPlacement != null ||
                incident.type == MineIncidentType.CAVE_IN &&
                saved.objective?.targets.orEmpty().none { it.role.value == "cave_in_rubble" }
        } == true
        if (incompatibleIncident) {
            MineShiftState(engineVersion = 2, sequence = saved.sequence)
        } else if (settings.miningOnly && saved.phase !in setOf(MinePhase.IDLE, MinePhase.COOLDOWN) &&
            (order == null ||
                saved.objective?.key?.objectiveId == "mining" || saved.resumeObjective?.key?.objectiveId == "mining" ||
                saved.incidentSchedule.any { it !in allowed } || saved.phase in setOf(MinePhase.PROSPECTING, MinePhase.LOADING))) {
            MineShiftState(engineVersion = 2, sequence = saved.sequence)
        } else if (settings.miningOnly && order != null && saved.mined >= order.totalMiningRequired) {
            val quota = order.totalMiningRequired
            when {
                saved.phase == MinePhase.MINING -> saved.copy(phase = MinePhase.EXTRACTION, mined = quota, cart = quota, objective = null)
                saved.phase == MinePhase.INCIDENT && saved.resumePhase == MinePhase.MINING ->
                    saved.copy(resumePhase = MinePhase.EXTRACTION, mined = quota, cart = quota, resumeObjective = null)
                else -> saved
            }
        } else saved
    }

    /**
     * State files written by the first multi-ore release used exact block
     * names.  Merge ordinary/deepslate variants into the active order's
     * resource buckets before any phase or quota decision is made.
     */
    private fun normalizeResourceProgress(order: MineOrderSettings?, state: MineShiftState): MineShiftState {
        val requirements = order?.normalizedRequirements.orEmpty()
        val normalized = MineResource.normalizeProgress(state.minedByMaterial)
        if (requirements.isEmpty()) {
            return if (normalized == state.minedByMaterial) state else state.copy(minedByMaterial = normalized)
        }
        val progress = if (normalized.isEmpty() && state.mined > 0) {
            var remaining = state.mined
            buildMap {
                requirements.forEach { (resource, quota) ->
                    val assigned = minOf(remaining, quota)
                    if (assigned > 0) put(resource, assigned)
                    remaining -= assigned
                }
            }
        } else {
            normalized.mapValues { (resource, value) -> minOf(value, requirements[resource] ?: 0) }
                .filterValues { it > 0 }
        }
        val total = requirements.entries.sumOf { (resource, quota) -> minOf(progress[resource] ?: 0, quota) }
        return state.copy(mined = total, cart = total, minedByMaterial = progress)
    }
}
