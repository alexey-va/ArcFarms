package ru.ruscrafting.farms.paper.farm

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.ArcFarmsState
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmDeliveryPosition
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ActivityBarKey
import ru.ruscrafting.farms.paper.ActivityStatus
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.FarmRuntimeFactory
import ru.ruscrafting.farms.paper.RegionGateway
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.WorksiteModule
import ru.ruscrafting.farms.paper.blockIndexDefinition
import ru.ruscrafting.farms.paper.farm.admin.FarmWorldAdminService
import ru.ruscrafting.farms.paper.farm.care.FarmCareController
import ru.ruscrafting.farms.paper.farm.care.FarmCarePlanService
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowController
import ru.ruscrafting.farms.paper.farm.delivery.FarmDeliveryController
import ru.ruscrafting.farms.paper.farm.field.FarmFieldController
import ru.ruscrafting.farms.paper.farm.incident.drought.FarmDroughtIncident
import ru.ruscrafting.farms.paper.farm.incident.bird.FarmBirdIncident
import ru.ruscrafting.farms.paper.farm.incident.pest.FarmPestIncident
import ru.ruscrafting.farms.paper.farm.incident.special.FarmSpecialIncidentController
import ru.ruscrafting.farms.paper.farm.incident.route.FarmFoodDeliveryIncident
import ru.ruscrafting.farms.paper.farm.incident.processing.FarmProcessingIncident
import ru.ruscrafting.farms.paper.farm.incident.fire.FarmBarnFireIncident
import ru.ruscrafting.farms.paper.farm.placement.FarmPlacementService
import ru.ruscrafting.farms.paper.farm.point.FarmPointService
import ru.ruscrafting.farms.paper.farm.perk.FarmPerkController
import ru.ruscrafting.farms.paper.farm.presentation.FarmGuidanceController
import ru.ruscrafting.farms.paper.farm.presentation.FarmHudController
import ru.ruscrafting.farms.paper.farm.recovery.FarmFixedCropRecoveryController
import ru.ruscrafting.farms.paper.farm.recovery.FarmIncidentRecoveryController
import ru.ruscrafting.farms.paper.farm.scene.FarmContractSceneController
import ru.ruscrafting.farms.paper.farm.shift.FarmOrderCycleController
import ru.ruscrafting.farms.paper.farm.shift.FarmShiftStartService
import ru.ruscrafting.farms.paper.farm.supply.FarmSupplyController
import ru.ruscrafting.farms.paper.farm.supply.FarmSupplyKind

/** Coordinates farm-zone lifecycle while feature owners retain their own state. */
internal class FarmModule(
    private val settings: () -> ArcFarmsConfig,
    private val debug: ArcFarmsDebug,
    private val port: WorksiteRuntimePort,
    private val registry: FarmRuntimeRegistry,
    private val regionGateway: RegionGateway,
    private val blockRegistry: FarmBlockRegistry,
    private val orderCycle: FarmOrderCycleController,
    private val worldAdmin: FarmWorldAdminService,
    private val shiftStart: FarmShiftStartService,
    private val transitions: FarmTransitionSink,
    private val field: FarmFieldController,
    private val fixedCrops: FarmFixedCropRecoveryController,
    private val incidentRecovery: FarmIncidentRecoveryController,
    private val carePlans: FarmCarePlanService,
    private val care: FarmCareController,
    private val moles: FarmMoleBurrowController,
    private val drought: FarmDroughtIncident,
    private val pests: FarmPestIncident,
    private val birds: FarmBirdIncident,
    private val foodDelivery: FarmFoodDeliveryIncident,
    private val perks: FarmPerkController,
    private val special: FarmSpecialIncidentController,
    private val processing: FarmProcessingIncident,
    private val barnFire: FarmBarnFireIncident,
    private val delivery: FarmDeliveryController,
    private val scene: FarmContractSceneController,
    private val supplies: FarmSupplyController,
    private val points: FarmPointProvider,
    private val pointService: FarmPointService,
    private val placement: FarmPlacementService,
    private val hud: FarmHudController,
    private val guidance: FarmGuidanceController,
) : WorksiteModule<FarmShiftState> {
    override val kind: ActivityKind = ActivityKind.FARM
    override val zoneCount: Int get() = registry.size

    fun rebuild(persisted: ArcFarmsState) {
        registry.replace(FarmRuntimeFactory.build(settings(), persisted.farms, regionGateway))
        orderCycle.retain(registry.snapshot().mapTo(mutableSetOf()) { it.settings.id })
    }

    override fun activateLoadedState() {
        moles.reconcileLoaded()
        org.bukkit.Bukkit.getOnlinePlayers().forEach(moles::recoverPlayer)
        field.reconcile(registry.snapshot())
        reconcileLoadedBlockIndexes()
        fixedCrops.reconcileLoaded()
        registry.snapshot().forEach(::ensureSupplies)
        registry.snapshot().forEach(scene::ensure)
        registry.snapshot().forEach(perks::ensure)
        registry.snapshot().forEach(special::ensure)
        registry.snapshot().forEach(processing::ensure)
        registry.snapshot().forEach(barnFire::ensure)
    }

    override fun reconcileChunk(chunk: Chunk) {
        moles.onChunkLoad(chunk)
        scene.onChunkLoad(chunk)
        special.onChunkLoad(chunk)
        processing.onChunkLoad(chunk)
        registry.snapshot().asSequence().filter { it.region.world == chunk.world }.forEach { runtime ->
            blockRegistry.reconcileChunk(runtime.blockIndexDefinition(), chunk)
        }
        fixedCrops.reconcileChunk(chunk)
        var removed = 0
        chunk.entities.filter { entity ->
            pests.ownsPest(entity) || pests.ownsNest(entity) || birds.owns(entity) || foodDelivery.owns(entity) ||
                processing.owns(entity) || delivery.owns(entity) || supplies.owns(entity) ||
                care.owns(entity) || perks.owns(entity)
        }.forEach { entity ->
            entity.remove()
            removed++
        }
        if (removed > 0) debug.event(
            "farm_transient_entities_reconciled", "world" to chunk.world.name,
            "chunk" to "${chunk.x},${chunk.z}", "removed" to removed, "reason" to "chunk_load",
        )
    }

    fun processRestores() {
        val runtimes = registry.snapshot()
        val hasEditor = worldAdmin.anyEditing()
        if (!hasEditor) care.processIrrigation()
        val moleBlockBudget = runtimes.maxOfOrNull { it.settings.moleBurrow.blocksPerTick } ?: 8
        moles.processBlocks(moleBlockBudget)
        val limit = runtimes.maxOfOrNull { it.settings.restoreBlocksPerTick } ?: 1
        special.processRestores(limit).forEach { chunk ->
            runtimes.filter { it.region.world === chunk.world }.forEach { runtime ->
                blockRegistry.reconcileChunk(runtime.blockIndexDefinition(), chunk)
            }
        }
        fixedCrops.processDue(limit)
        runtimes.forEach { runtime ->
            if (shiftStart.isPending(runtime.settings.id)) return@forEach
            if (!hasRestoreWork(runtime)) return@forEach
            if (hasEditor && isAdminEditing(runtime)) return@forEach
            var remaining = runtime.settings.restoreBlocksPerTick
            if (runtime.state.preparationPatch.isNotEmpty() && !runtime.state.preparationReleased) {
                val release = field.release(runtime, remaining)
                remaining -= release.processed
                if (release.complete) {
                    runtime.state = runtime.state.copy(preparationReleased = true)
                    port.persistAsync()
                }
                if (!release.complete || remaining <= 0) return@forEach
            }
            if (remaining > 0) remaining -= field.finishAutomaticQuota(runtime, remaining)
            if (
                remaining > 0 && runtime.state.phase !in setOf(FarmPhase.INCIDENT, FarmPhase.CARE) &&
                incidentRecovery.pending(runtime)
            ) {
                remaining -= incidentRecovery.restore(runtime, remaining, drought.hasActiveWater(runtime.settings.id))
            }
            if (remaining > 0 && runtime.state.phase == FarmPhase.COOLDOWN &&
                !incidentRecovery.pending(runtime) && runtime.state.preparationPatch.isNotEmpty() &&
                field.restoreOriginal(runtime, remaining)
            ) {
                field.clearState(runtime)
            }
        }
    }

    fun updateSeeder(tick: Long) = registry.snapshot().forEach { runtime ->
        port.guarded("farm_seeder:${runtime.settings.id}") {
            if (!isAdminEditing(runtime)) care.updateSeeder(runtime, processField = tick % 5L == 0L)
        }
    }

    fun updateAmbient() {
        worldAdmin.renderInspectViews()
        registry.snapshot().forEach { runtime ->
            port.guarded("farm_animals:${runtime.settings.id}") {
                if (!isAdminEditing(runtime)) {
                    care.updateAnimals(runtime)
                    if (runtime.state.phase == FarmPhase.INCIDENT && runtime.state.incidentType == FarmIncidentType.NIGHT_SHIFT) {
                        special.updateLights(runtime)
                    }
                }
            }
        }
    }

    fun updatePlayerTimes() = port.guarded("farm_night_time") { special.updatePlayerTimes() }

    fun updateCarriedDisplays() {
        val runtimes = registry.snapshot()
        delivery.updateCarriedDisplays(runtimes)
        foodDelivery.updateVisuals(runtimes)
        care.updateCarriedDisplays()
        processing.update(runtimes, Bukkit.getCurrentTick().toLong())
        barnFire.update(runtimes, Bukkit.getCurrentTick().toLong())
    }

    override fun tick(now: Long) {
        val runtimes = registry.snapshot()
        runtimes.forEach { runtime ->
            port.guarded("farm_perks:${runtime.settings.id}") { perks.tick(runtime) }
        }
        runtimes.forEach { runtime ->
            port.guarded("farm:${runtime.settings.id}") {
                if (isAdminEditing(runtime)) return@guarded
                if (shiftStart.isPending(runtime.settings.id)) return@guarded
                if (
                    runtime.state.phase !in setOf(FarmPhase.INCIDENT, FarmPhase.CARE) &&
                    incidentRecovery.pending(runtime)
                ) return@guarded
                if (runtime.state.phase == FarmPhase.COOLDOWN && runtime.state.preparationPatch.isNotEmpty()) return@guarded
                if (special.expireMarket(runtime, now)) return@guarded
                val result = FarmShiftEngine.tick(runtime.state, currentOrder(runtime), now)
                if (result.events.isNotEmpty()) transitions.apply(runtime, result, null)
                if (runtime.state.phase == FarmPhase.IDLE) {
                    val player = port.players(runtime.region).firstOrNull() ?: return@guarded
                    shiftStart.start(runtime, player, now)
                    return@guarded
                }
                if (
                    runtime.state.phase == FarmPhase.PREPARATION && runtime.state.preparationReleased &&
                    carePlans.shouldUseSeeder(runtime)
                ) {
                    care.initialize(runtime, port.players(runtime.region).firstOrNull(), FarmCareType.SEEDER)
                }
                drought.ensure(runtime)
                pests.ensure(runtime)
                birds.ensure(runtime)
                foodDelivery.ensure(runtime, now)
                special.ensure(runtime)
                processing.ensure(runtime)
                barnFire.ensure(runtime)
                pests.eatCrops(runtime)
                birds.eatCrops(runtime)
                care.updateDisease(runtime, now)
                care.reconcile(runtime)
                care.ensure(runtime)
                delivery.ensure(runtime)
                scene.ensure(runtime)
                ensureSupplies(runtime)
                field.maintain(
                    runtime,
                    drought.hasActiveWater(runtime.settings.id),
                    care.irrigationDryPlots(runtime),
                )
            }
        }
    }

    fun updateHud(): MutableSet<ActivityBarKey> = hud.update(registry.snapshot()).also(moles::updateGuidance)

    fun hudRuntime(player: Player): FarmRuntime? =
        registry.at(player.location) ?: foodDelivery.participantRuntime(player, registry.snapshot())

    override fun states(): Map<String, FarmShiftState> =
        registry.snapshot().associate { it.settings.id to it.state }

    override fun statuses(): List<ActivityStatus> = registry.snapshot().map { runtime ->
        val order = currentOrder(runtime)
        val done = order?.let(runtime.state::completed) ?: 0
        val total = order?.totalRequired ?: 0
        val progress = when (runtime.state.phase) {
            FarmPhase.PREPARATION -> "${runtime.state.preparationProgress}/${runtime.state.preparationRequired}"
            FarmPhase.PLANTING -> "${runtime.state.plantingProgress}/${runtime.state.preparationRequired}"
            FarmPhase.CARE -> if (runtime.state.careType == FarmCareType.SEEDER) {
                "${runtime.state.plantingProgress}/${runtime.state.preparationRequired}"
            } else {
                "${runtime.state.careProgress()}/${runtime.state.careRequired()}"
            }
            FarmPhase.INCIDENT -> "${runtime.state.incidentProgress}/${runtime.state.incidentRequired}"
            FarmPhase.DELIVERY -> "${runtime.state.deliveredCrates.size}/${runtime.settings.delivery.crates}"
            else -> "$done/$total"
        }
        ActivityStatus(kind, runtime.settings.id, "phase.farm.${runtime.state.phase.name.lowercase()}", progress)
    }

    override fun canAccess(player: Player): Boolean =
        registry.snapshot().any { port.hasAccess(player, it.settings.permission) }

    fun refreshPoint(runtime: FarmRuntime, kind: FarmPointKind, actor: Player, reason: String) {
        when (kind) {
            FarmPointKind.TOOL, FarmPointKind.SEEDS, FarmPointKind.WATER, FarmPointKind.ARCHERY,
            FarmPointKind.FIRE_EQUIPMENT,
            -> {
                val supply = when (kind) {
                    FarmPointKind.TOOL -> FarmSupplyKind.TOOL
                    FarmPointKind.SEEDS -> FarmSupplyKind.SEEDS
                    FarmPointKind.WATER -> FarmSupplyKind.WATER
                    FarmPointKind.ARCHERY -> FarmSupplyKind.ARCHERY
                    else -> FarmSupplyKind.FIRE
                }
                supplies.refresh(runtime, supply, { supplyPoint(runtime, it) }, reason)
            }
            FarmPointKind.CRATES -> if (runtime.state.phase == FarmPhase.DELIVERY) {
                runtime.state = runtime.state.copy(
                    deliveryPosition = pointService.configured(runtime.settings.id, FarmPointKind.CRATES)?.let {
                        FarmDeliveryPosition(it.world, it.x, it.y, it.z)
                    } ?: placement.selectDeliveryAnchor(runtime, actor.location),
                )
                delivery.clear(runtime, reason)
                delivery.ensure(runtime)
                port.persistAsync()
            }
            FarmPointKind.CART, FarmPointKind.CUSTOMER -> {
                scene.clear(runtime, reason)
                scene.ensure(runtime)
            }
            FarmPointKind.RECEIVING -> care.refreshPoint(runtime, FarmPointKind.PEN, reason)
            FarmPointKind.HIVE, FarmPointKind.IRRIGATION, FarmPointKind.COVERS,
            FarmPointKind.SCARECROWS, FarmPointKind.PEN -> care.refreshPoint(runtime, kind, reason)
            FarmPointKind.PERK_VENDOR -> perks.refresh(runtime, reason)
            FarmPointKind.PROCESSING, FarmPointKind.PROCESSING_INPUT,
            FarmPointKind.PROCESSING_INPUT_2, FarmPointKind.PROCESSING_INPUT_3,
            FarmPointKind.PROCESSING_INPUT_4,
            FarmPointKind.PROCESSING_OUTPUT,
            -> processing.refresh(runtime, reason)
            FarmPointKind.TRAVEL -> Unit
        }
    }

    override fun cleanup(reason: String) {
        shiftStart.clearPending()
        orderCycle.clearPending()
        scene.cleanup(reason)
        special.cleanup(reason)
        processing.cleanup(reason)
        barnFire.cleanup(reason)
        supplies.cleanup(reason)
        delivery.cleanup(reason)
        pests.cleanup(reason)
        birds.cleanup(reason)
        foodDelivery.cleanup(reason)
        perks.cleanup(reason)
        care.cleanup(reason)
        blockRegistry.clear()
        field.clearCaches()
        fixedCrops.clearCache()
    }

    fun emitGuidance() = guidance.emit()

    private fun reconcileLoadedBlockIndexes() = registry.snapshot().forEach { runtime ->
        runtime.region.world.loadedChunks.forEach { chunk -> blockRegistry.reconcileChunk(runtime.blockIndexDefinition(), chunk) }
    }

    private fun ensureSupplies(runtime: FarmRuntime) = supplies.ensure(runtime) { supplyPoint(runtime, it) }

    private fun hasRestoreWork(runtime: FarmRuntime): Boolean =
        (runtime.state.preparationPatch.isNotEmpty() && !runtime.state.preparationReleased) ||
            field.hasPendingAutomaticQuota(runtime) ||
            (runtime.state.phase != FarmPhase.INCIDENT && incidentRecovery.pending(runtime)) ||
            (runtime.state.phase == FarmPhase.COOLDOWN && runtime.state.preparationPatch.isNotEmpty())

    private fun supplyPoint(runtime: FarmRuntime, kind: FarmSupplyKind): FarmPointPosition = points.resolve(
        runtime,
        when (kind) {
            FarmSupplyKind.TOOL -> FarmPointKind.TOOL
            FarmSupplyKind.SEEDS -> FarmPointKind.SEEDS
            FarmSupplyKind.WATER -> FarmPointKind.WATER
            FarmSupplyKind.ARCHERY -> FarmPointKind.ARCHERY
            FarmSupplyKind.FIRE -> FarmPointKind.FIRE_EQUIPMENT
        },
    )

    private fun isAdminEditing(runtime: FarmRuntime): Boolean = port.players(runtime.region).any(worldAdmin::isEditing)

    private fun currentOrder(runtime: FarmRuntime) = runtime.state.orderId?.let(runtime.orders::get)
}
