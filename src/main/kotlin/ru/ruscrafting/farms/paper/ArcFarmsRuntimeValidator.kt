package ru.ruscrafting.farms.paper

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Monster
import org.bukkit.inventory.ItemStack
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.domain.ArcFarmsState
import ru.ruscrafting.farms.domain.FarmIncidentRecovery
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmLocationOverrides
import ru.ruscrafting.farms.domain.FarmOrder
import ru.ruscrafting.farms.domain.FarmOrderProgressReconciler
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmRules
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.persistence.FixedFarmCropJournal
import ru.ruscrafting.farms.persistence.MineRecoveryJournal

internal object FarmRuntimeFactory {
    fun build(
        settings: ArcFarmsConfig,
        persisted: Map<String, FarmShiftState>,
        regionGateway: RegionGateway,
    ): List<FarmRuntime> = settings.farms.map { configured ->
        val region = requireNotNull(regionGateway.resolve(configured.reference)) {
            "Farm zone ${configured.id} cannot resolve ${configured.reference}"
        }
        val orders = configured.orders.map { order ->
            FarmOrder(
                id = order.id,
                required = order.required,
                rarity = order.rarity,
                careTypes = order.careTypes,
                incidentTypes = order.incidentTypes,
                customerType = order.customerType,
                cartLoadMaterial = order.cartLoadMaterial,
                cartLoadCustomModelData = order.cartLoadCustomModelData,
            )
        }
        FarmRuntime(
            settings = configured,
            region = region,
            orders = orders.associateBy(FarmOrder::id),
            orderList = orders,
            rules = FarmRules(
                incidentTriggerPercents = configured.incidentTriggerPercents,
                incidentQuota = configured.incidentQuota,
                cooldownMillis = settings.completedCooldownSeconds * 1000L,
                droughtQuota = configured.droughtTargetBeds(configured.preparationPatchSize),
                incidentCountMin = configured.incidentCountMin,
                incidentCountMax = configured.incidentCountMax,
            ),
            state = repairLegacyPreparation(persisted[configured.id] ?: FarmShiftState()),
        )
    }

    internal fun repairLegacyPreparation(state: FarmShiftState): FarmShiftState =
        if (state.phase == FarmPhase.PREPARATION && state.preparationPatch.isEmpty()) {
            state.copy(
                phase = FarmPhase.HARVESTING,
                preparationCrop = null,
                preparationProgress = 0,
                plantingProgress = 0,
                preparationRequired = 0,
                tilledPlots = emptySet(),
                plantedPlots = emptySet(),
            )
        } else {
            state
        }
}

/** Central fail-closed validator for config, persisted state, reload and world ownership. */
internal class ArcFarmsRuntimeValidator(
    private val regionGateway: RegionGateway,
    private val economyAvailable: () -> Boolean,
    private val fixedCropJournal: FixedFarmCropJournal,
    private val mineJournal: MineRecoveryJournal,
) {
    fun validatePersisted(candidate: ArcFarmsConfig, persisted: ArcFarmsState) {
        persisted.pendingFarmRewards.forEach { reward ->
            reward.items.forEach { item -> MaterialRules.material(item.material) }
            require(reward.moneyCents == 0L || economyAvailable()) {
                "Pending farm money reward ${reward.id} requires Vault and an economy provider"
            }
        }
        val farmZones = candidate.farms.associateBy(FarmZoneSettings::id)
        persisted.farms.forEach { (id, state) ->
            if (state.phase == FarmPhase.IDLE) return@forEach
            val zone = farmZones[id]
            if (
                zone == null && state.phase == FarmPhase.COOLDOWN && state.preparationPatch.isEmpty() &&
                !FarmIncidentRecovery.pending(state)
            ) return@forEach
            requireNotNull(zone) { "Persisted active farm zone $id is missing from config" }
            if (state.phase != FarmPhase.COOLDOWN) {
                val order = zone.orders.firstOrNull { it.id == state.orderId }
                require(order != null) { "Persisted active farm order $id/${state.orderId} is missing from config" }
                require(state.progress.keys == order.required.keys) { "Persisted farm order $id changed its crop set" }
                require(state.progress.all { (crop, amount) -> amount <= order.required.getValue(crop) }) {
                    "Persisted farm progress exceeds the configured order in $id"
                }
                require(state.preparationCrop == null || state.preparationCrop in order.required) {
                    "Persisted farm preparation crop ${state.preparationCrop} is missing from $id"
                }
                require(state.incidentCrop == null || state.incidentCrop in order.required) {
                    "Persisted farm incident crop ${state.incidentCrop} is missing from $id"
                }
            }
            val region = requireNotNull(regionGateway.resolve(zone.reference)) {
                "Persisted farm region $id cannot be resolved"
            }
            val managedPlots = buildList {
                addAll(state.preparationPatch)
                addAll(state.droughtPlots)
                addAll(state.droughtDamagedPlots)
                state.pestNests.mapTo(this) { it.position }
                state.pestDamagedCrops.mapTo(this) { it.position }
                state.diseaseDamagedCrops.orEmpty().mapTo(this) { it.position }
                addAll(state.specialIncident?.plots.orEmpty())
                state.specialDamagedCrops.mapTo(this) { it.position }
            }
            require(managedPlots.all { position ->
                position.world == region.world.name && position.location()?.let(region::contains) == true
            }) { "Persisted farm-managed block escaped region $id" }
            state.specialIncident?.let { special ->
                require(special.crop == null || special.crop in zone.crops) {
                    "Persisted farm incident contains an unknown crop in $id"
                }
                if (state.incidentType == FarmIncidentType.RIVAL_RAID) {
                    require(special.points.size == 2) { "Persisted rival raid $id must contain departure and rival points" }
                    val departure = special.points[0]
                    val rival = special.points[1]
                    require(departure.world == region.world.name && region.contains(
                        Location(region.world, departure.x, departure.y, departure.z),
                    )) { "Persisted rival raid departure escaped region $id" }
                    val dx = rival.x - departure.x
                    val dz = rival.z - departure.z
                    require(rival.world == region.world.name &&
                        dx * dx + dz * dz <= zone.rivalRaid.maximumDistance * zone.rivalRaid.maximumDistance
                    ) { "Persisted rival farm point is invalid in $id" }
                } else {
                    require(special.points.all { point ->
                        point.world == region.world.name && region.contains(Location(region.world, point.x, point.y, point.z))
                    }) { "Persisted farm incident point escaped region $id" }
                }
            }
            require(state.careTargets.all { target ->
                val position = target.position
                val location = Location(region.world, position.x, position.y, position.z)
                position.world == region.world.name && region.contains(location)
            }) { "Persisted farm care target escaped region $id" }
            require(state.pestDamagedCrops.all { it.crop in zone.crops }) {
                "Persisted farm pest damage contains an unknown crop in $id"
            }
            require(state.diseaseDamagedCrops.orEmpty().all { it.crop in zone.crops }) {
                "Persisted farm disease damage contains an unknown crop in $id"
            }
            require(state.specialDamagedCrops.all { it.crop in zone.crops }) {
                "Persisted farm special damage contains an unknown crop in $id"
            }
            state.deliveryPosition?.let { position ->
                val location = Location(region.world, position.x, position.y, position.z)
                require(position.world == region.world.name && region.contains(location)) {
                    "Persisted farm delivery escaped region $id"
                }
            }
        }
        LumbermillController.validatePersisted(candidate.lumbermills, persisted.lumbermills)
        MineController.validatePersisted(candidate.mines, persisted.mines)
    }

    fun validateReload(candidate: ArcFarmsConfig, snapshot: ArcFarmsState) {
        validatePersisted(candidate, snapshot)
        val farmZones = candidate.farms.associateBy(FarmZoneSettings::id)
        snapshot.farms.filterValues {
            it.phase !in setOf(FarmPhase.IDLE, FarmPhase.COOLDOWN) || it.preparationPatch.isNotEmpty() ||
                FarmIncidentRecovery.pending(it)
        }.forEach { (id, state) ->
            val zone = requireNotNull(farmZones[id]) { "Cannot remove farm zone $id with pending world recovery" }
            if (state.phase != FarmPhase.COOLDOWN) {
                val order = zone.orders.firstOrNull { it.id == state.orderId }
                require(order != null) { "Cannot remove active farm order $id/${state.orderId} during reload" }
                require(state.preparationCrop == null || state.preparationCrop in order.required) {
                    "Cannot remove active farm preparation crop ${state.preparationCrop} from $id"
                }
            }
            if (state.preparationPatch.isNotEmpty()) {
                val region = requireNotNull(regionGateway.resolve(zone.reference)) {
                    "Cannot resolve active farm patch region $id during reload"
                }
                require(state.preparationPatch.all { position ->
                    position.world == region.world.name && region.contains(requireNotNull(position.location()))
                }) { "Cannot move or shrink active farm patch region $id during reload" }
            }
        }
        LumbermillController.validateReload(candidate.lumbermills, snapshot.lumbermills)
        MineController.validateReload(candidate.mines, snapshot.mines, mineJournal)
    }

    fun validateRuntime(candidate: ArcFarmsConfig) {
        require(
            candidate.enterprises.values.none { it.mode == ru.ruscrafting.farms.config.WorksiteEnterpriseMode.LIVE } ||
                economyAvailable(),
        ) { "LIVE enterprise funding requires Vault and an economy provider" }
        if (candidate.menuBackground.enabled) {
            val material = MaterialRules.material(candidate.menuBackground.material)
            require(material.isItem && !material.isAir) { "ui.menu-background.material must be a non-air item material" }
        }
        candidate.configuredMenuItems.forEach { (path, visual) ->
            val material = MaterialRules.material(visual.material)
            require(material.isItem && !material.isAir) { "$path.material must be a non-air item material" }
        }
        candidate.destinations.values.filter { it.server == candidate.serverId }.forEach { destination ->
            requireNotNull(Bukkit.getWorld(destination.world)) {
                "Destination world ${destination.world} is not loaded on ${candidate.serverId}"
            }
        }
        candidate.farms.forEach { zone -> validateFarmZone(zone) }
        fixedCropJournal.records().forEach { pending ->
            val zone = candidate.farms.firstOrNull { it.id == pending.zoneId }
                ?: error("Pending fixed crop references missing farm zone ${pending.zoneId}")
            val material = Bukkit.createBlockData(pending.originalBlockData).material
            require(MaterialRules.isFixedBlockCrop(material) && material.name in zone.crops) {
                "Pending fixed crop ${pending.positionKey} is no longer configured in ${pending.zoneId}"
            }
        }
        LumbermillController.validateRuntime(candidate.lumbermills, regionGateway)
        MineController.validateRuntime(candidate.mines, regionGateway)
    }

    fun validateLocations(candidate: ArcFarmsConfig, overrides: FarmLocationOverrides) {
        overrides.zones.forEach { (zoneId, points) ->
            val configured = candidate.farms.firstOrNull { it.id == zoneId }
                ?: error("Farm location override references unknown zone $zoneId")
            val region = requireNotNull(regionGateway.resolve(configured.reference)) {
                "Farm location override cannot resolve zone $zoneId"
            }
            points.forEach { (kind, point) ->
                val world = requireNotNull(Bukkit.getWorld(point.world)) {
                    "Farm point $zoneId/$kind world ${point.world} is not loaded"
                }
                if (kind == FarmPointKind.TRAVEL) {
                    require(candidate.destinations.getValue("farm").server == candidate.serverId) {
                        "Farm travel point can only be overridden on its destination server"
                    }
                } else if (kind == FarmPointKind.RIVAL_FARM) {
                    require(world === region.world) { "Rival farm point $zoneId must use ${region.world.name}" }
                    val receiving = points[FarmPointKind.RECEIVING]
                    val receivingX = receiving?.x ?: configured.delivery.x
                    val receivingZ = receiving?.z ?: configured.delivery.z
                    val dx = point.x - receivingX
                    val dz = point.z - receivingZ
                    require(dx * dx + dz * dz <= configured.rivalRaid.maximumDistance * configured.rivalRaid.maximumDistance) {
                        "Rival farm point $zoneId is farther than ${configured.rivalRaid.maximumDistance} blocks"
                    }
                } else {
                    require(region.contains(Location(world, point.x, point.y, point.z))) {
                        "Farm point $zoneId/$kind is outside ${region.label}"
                    }
                }
            }
        }
    }

    fun reconcileOrderProgress(candidate: ArcFarmsConfig, persisted: ArcFarmsState): ArcFarmsState {
        val zones = candidate.farms.associateBy(FarmZoneSettings::id)
        var changed = false
        val farms = persisted.farms.mapValues { (zoneId, state) ->
            val order = zones[zoneId]?.orders?.firstOrNull { it.id == state.orderId } ?: return@mapValues state
            val result = FarmOrderProgressReconciler.reconcile(state, order.required)
            changed = changed || result.changed
            result.state
        }
        return if (changed) persisted.copy(farms = farms) else persisted
    }

    private fun validateFarmZone(zone: FarmZoneSettings) {
        require(MaterialRules.material(zone.boarBreakout.shieldMaterial) == Material.SHIELD) {
            "Farm zone ${zone.id} special-incidents.boar-breakout.shield-material must be SHIELD"
        }
        require(MaterialRules.material(zone.rivalRaid.workerHeldItem).let { it.isItem && !it.isAir }) {
            "Farm zone ${zone.id} special-incidents.rival-raid.worker-held-item must be a non-air item"
        }
        val raidGun = MaterialRules.material(zone.rivalRaid.gunMaterial)
        require(raidGun.isItem && !raidGun.isAir) {
            "Farm zone ${zone.id} special-incidents.rival-raid.gun-material must be a non-air item"
        }
        val raidGrenade = MaterialRules.material(zone.rivalRaid.grenadeMaterial)
        require(raidGrenade.isItem && !raidGrenade.isAir) {
            "Farm zone ${zone.id} special-incidents.rival-raid.grenade-material must be a non-air item"
        }
        val raidPreviewSoil = MaterialRules.material(zone.rivalRaid.grenadePreviewSoilMaterial)
        require(raidPreviewSoil.isBlock && raidPreviewSoil.isSolid && raidPreviewSoil.isOccluding) {
            "Farm zone ${zone.id} special-incidents.rival-raid.grenade-preview-soil-material must be a solid block"
        }
        require(!zone.rewards.requiresEconomy || economyAvailable()) {
            "Farm zone ${zone.id} money reward requires Vault and an economy provider"
        }
        zone.rewards.items.forEach { reward ->
            val material = MaterialRules.material(reward.material)
            require(material.isItem) { "Farm zone ${zone.id} reward ${reward.id} must use an item material" }
        }
        require(zone.rewards.items.sumOf { reward ->
            val stackSize = MaterialRules.material(reward.material).maxStackSize
            (reward.amount + stackSize - 1) / stackSize
        } <= 54) { "Farm zone ${zone.id} fixed item rewards exceed 54 inventory stacks" }
        zone.rewards.randomBundles.entries.forEach { bundle ->
            bundle.items.forEach { reward ->
                val material = MaterialRules.material(reward.material)
                require(material.isItem) { "Farm zone ${zone.id} reward bundle ${bundle.id} must use item materials" }
            }
            require(bundle.items.sumOf { reward ->
                val stackSize = MaterialRules.material(reward.material).maxStackSize
                (reward.amount + stackSize - 1) / stackSize
            } <= 27) { "Farm zone ${zone.id} reward bundle ${bundle.id} exceeds 27 inventory stacks" }
        }
        val region = requireNotNull(regionGateway.resolve(zone.reference)) {
            "Farm zone ${zone.id} cannot resolve ${zone.reference}"
        }
        val deliveryWorld = requireNotNull(Bukkit.getWorld(zone.delivery.world)) {
            "Farm zone ${zone.id} delivery world ${zone.delivery.world} is not loaded"
        }
        require(region.contains(Location(deliveryWorld, zone.delivery.x, zone.delivery.y, zone.delivery.z))) {
            "Farm zone ${zone.id} delivery point is outside ${region.label}"
        }
        listOf(zone.supplies.tool, zone.supplies.seeds, zone.supplies.water, zone.delivery.pickup).forEach { point ->
            val supplyWorld = requireNotNull(Bukkit.getWorld(point.world)) {
                "Farm zone ${zone.id} supply world ${point.world} is not loaded"
            }
            require(region.contains(Location(supplyWorld, point.x, point.y, point.z))) {
                "Farm zone ${zone.id} supply point is outside ${region.label}"
            }
        }
        require(MaterialRules.isHoe(ItemStack(MaterialRules.material(zone.supplies.toolMaterial)))) {
            "Farm zone ${zone.id} supplies.tool-material must be a hoe"
        }
        zone.careVisuals.values.forEach { MaterialRules.material(it.material) }
        require(MaterialRules.material(zone.moleBurrow.lairVisual.material).isItem) {
            "Farm zone ${zone.id} mole-burrow lair visual must use an item material"
        }
        zone.orders.forEach { order ->
            require(MaterialRules.material(order.cartLoadMaterial).isItem) {
                "Farm order ${zone.id}/${order.id} cart load must use an item material"
            }
        }
        zone.careAnimalEntities.forEach { entityName ->
            val entityType = EntityType.valueOf(entityName)
            require(entityType.entityClass?.let(Mob::class.java::isAssignableFrom) == true) {
                "Farm zone ${zone.id} care-animal-entities must contain mobs"
            }
        }
        MaterialRules.material(zone.delivery.itemMaterial)
        zone.crops.forEach { cropName ->
            val crop = MaterialRules.material(cropName)
            require(MaterialRules.isPlantableCrop(crop) || MaterialRules.isFixedBlockCrop(crop)) {
                "Farm zone ${zone.id} crop $cropName is neither plantable nor a managed block crop"
            }
            require(MaterialRules.harvestItemForCrop(crop).isItem) {
                "Farm zone ${zone.id} crop $cropName has no inventory-safe harvest item"
            }
        }
        zone.orders.forEach { order ->
            require(order.required.keys.any { MaterialRules.isPlantableCrop(MaterialRules.material(it)) }) {
                "Farm order ${zone.id}/${order.id} needs at least one plantable crop for preparation"
            }
        }
        val pestType = EntityType.valueOf(zone.pestEntity)
        require(pestType.entityClass?.let(LivingEntity::class.java::isAssignableFrom) == true) {
            "Farm zone ${zone.id} pest-entity must be a living entity"
        }
        val patrolType = EntityType.valueOf(zone.specialIncidents.nightPatrolEntity)
        require(patrolType.entityClass?.let(Monster::class.java::isAssignableFrom) == true) {
            "Farm zone ${zone.id} night-shift patrol entity must be a monster"
        }
        require(MaterialRules.material(zone.specialIncidents.nightPatrolHeldItem).isItem) {
            "Farm zone ${zone.id} night-shift patrol held-item must be an item"
        }
    }
}
