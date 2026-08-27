package ru.ruscrafting.farms.paper.farm.admin

import org.bukkit.Material
import org.bukkit.block.data.Ageable
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.FarmAdminStageProgress
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmFieldQuota
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmOrder
import ru.ruscrafting.farms.domain.FarmPatchPlanner
import ru.ruscrafting.farms.domain.FarmPestNest
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmSeederStage
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.domain.ShiftEvent
import ru.ruscrafting.farms.domain.seederStage
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.blockIndexDefinition
import ru.ruscrafting.farms.paper.block
import ru.ruscrafting.farms.paper.farm.FarmShiftLauncher
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.care.FarmCareController
import ru.ruscrafting.farms.paper.farm.delivery.FarmDeliveryController
import ru.ruscrafting.farms.paper.farm.field.FarmFieldController
import ru.ruscrafting.farms.paper.farm.harvest.FarmHarvestController
import ru.ruscrafting.farms.paper.farm.incident.drought.FarmDroughtIncident
import ru.ruscrafting.farms.paper.farm.incident.bird.FarmBirdIncident
import ru.ruscrafting.farms.paper.farm.incident.pest.FarmPestIncident
import ru.ruscrafting.farms.paper.farm.incident.special.FarmSpecialIncidentController
import ru.ruscrafting.farms.paper.farm.incident.route.FarmFoodDeliveryIncident
import ru.ruscrafting.farms.paper.farm.placement.FarmPlacementService
import ru.ruscrafting.farms.paper.farm.presentation.FarmGuidanceController
import ru.ruscrafting.farms.paper.farm.recovery.FarmIncidentRecoveryController
import ru.ruscrafting.farms.paper.farm.scene.FarmContractSceneController
import ru.ruscrafting.farms.paper.farm.shift.FarmOrderCycleController
import ru.ruscrafting.farms.paper.farm.supply.FarmSupplyController
import ru.ruscrafting.farms.paper.farm.supply.FarmSupplyKind
import java.util.concurrent.CompletableFuture

/**
 * Operator-facing farm scenarios. This class orchestrates feature APIs but owns
 * no gameplay entities, block journals or shift state.
 */
internal class FarmGameplayAdminService(
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
    private val port: WorksiteRuntimePort,
    private val runtimes: () -> Collection<FarmRuntime>,
    private val orderCycle: FarmOrderCycleController,
    private val worldAdmin: FarmWorldAdminService,
    private val field: FarmFieldController,
    private val care: FarmCareController,
    private val drought: FarmDroughtIncident,
    private val pests: FarmPestIncident,
    private val birds: FarmBirdIncident,
    private val foodDelivery: FarmFoodDeliveryIncident,
    private val special: FarmSpecialIncidentController,
    private val incidentRecovery: FarmIncidentRecoveryController,
    private val delivery: FarmDeliveryController,
    private val scene: FarmContractSceneController,
    private val supplies: FarmSupplyController,
    private val harvest: FarmHarvestController,
    private val placement: FarmPlacementService,
    private val guidance: FarmGuidanceController,
    private val ledger: FarmBlockLedger,
    private val registry: FarmBlockRegistry,
    private val transitions: FarmTransitionSink,
    private val shiftLauncher: FarmShiftLauncher,
    private val persistAsync: () -> CompletableFuture<Unit>,
    private val clock: () -> Long,
) {
    fun setStage(player: Player, zoneId: String, stage: String): Boolean {
        val runtime = runtime(zoneId, player) ?: return false
        val normalized = stage.lowercase()
        if (normalized !in STANDARD_STAGES && normalized !in CARE_STAGES) {
            port.sendChat(player, MessageKey.ADMIN_STAGE_UNKNOWN)
            return false
        }
        if (normalized == "reset") return resetStage(player, runtime)
        orderCycle.resumeInMemory(zoneId)
        if (normalized == "preparation") return preparationStage(player, runtime)
        if (!ensureShift(runtime, player)) return false
        if (!clearActiveObjective(runtime, player)) return false
        val order = currentOrder(runtime) ?: return false
        val nextCrop = harvest.nextRequiredCrop(runtime.state, order)?.key ?: order.required.keys.first()
        CARE_STAGES[normalized]?.let { careType ->
            return careStage(player, runtime, normalized, careType)
        }
        val events = mutableListOf<ShiftEvent>()
        runtime.state = when (normalized) {
            "planting" -> plantingState(runtime, events)
            "harvesting" -> harvestingState(runtime)
            "pests", "drought", "birds", "giant-crop", "channels", "night-shift", "market" ->
                incidentState(runtime, normalized, nextCrop, events)
            "delivery", "complete" -> deliveryState(runtime, order, player, events)
            else -> return false
        }
        transitions.apply(runtime, EngineResult(runtime.state, true, events = events), player)
        if (normalized == "complete") completeDelivery(runtime, player)
        persistAsync()
        port.sendChat(player, MessageKey.ADMIN_STAGE_SET, mapOf("stage" to locale.renderPath("admin.stage.$normalized", player)))
        return true
    }

    fun stopCycle(player: Player, zoneId: String): Boolean {
        if (runtime(zoneId, player) == null) return false
        if (!orderCycle.set(zoneId, paused = true)) return genericError(player)
        port.sendChat(player, MessageKey.ADMIN_ORDER_CYCLE_STOPPED)
        debug.event("farm_admin_order_cycle", "player" to player.name, "zone" to zoneId, "paused" to true)
        return true
    }

    fun startCycle(player: Player, zoneId: String): Boolean {
        val runtime = runtime(zoneId, player) ?: return false
        if (!orderCycle.set(zoneId, paused = false)) return genericError(player)
        if (runtime.state.phase == FarmPhase.IDLE && runtime.region.contains(player.location)) {
            resetPatchScan(runtime)
            shiftLauncher.start(runtime, player, clock(), null)
        }
        port.sendChat(player, MessageKey.ADMIN_ORDER_CYCLE_STARTED)
        debug.event("farm_admin_order_cycle", "player" to player.name, "zone" to zoneId, "paused" to false)
        return true
    }

    fun advance(player: Player, zoneId: String): Boolean {
        val runtime = runtime(zoneId, player) ?: return false
        val next = when (runtime.state.phase) {
            FarmPhase.IDLE, FarmPhase.COOLDOWN -> "preparation"
            FarmPhase.PREPARATION -> "planting"
            FarmPhase.PLANTING -> currentOrder(runtime)?.careTypes?.let { types ->
                care.adminStageName(types[Math.floorMod(runtime.state.sequence.toInt(), types.size)])
            } ?: "harvesting"
            FarmPhase.CARE -> "harvesting"
            FarmPhase.HARVESTING -> currentOrder(runtime)?.let { special.id(harvest.plannedIncident(runtime, it)) } ?: "pests"
            FarmPhase.INCIDENT -> "harvesting"
            FarmPhase.DELIVERY -> "complete"
        }
        return setStage(player, zoneId, next)
    }

    fun status(player: Player, zoneId: String): Boolean {
        val runtime = runtime(zoneId, player) ?: return false
        val state = runtime.state
        val order = currentOrder(runtime)
        val water = drought.flowStats(zoneId)
        val incident = state.incidentType?.let { locale.renderPath("admin.stage.${special.id(it)}", player) } ?: locale.text("—")
        port.sendChat(player, MessageKey.ADMIN_DEBUG_HEADER, mapOf("zone" to locale.text(zoneId)))
        port.sendChat(player, MessageKey.ADMIN_DEBUG_SHIFT, mapOf(
            "phase" to locale.renderPath("phase.farm.${state.phase.name.lowercase()}", player),
            "sequence" to locale.text(state.sequence),
            "order" to (state.orderId?.let { locale.renderPath("order.farm.$it", player) } ?: locale.text("—")),
            "done" to locale.text(order?.let(state::completed) ?: 0),
            "total" to locale.text(order?.totalRequired ?: 0),
            "rarity" to locale.text(order?.rarity?.name?.lowercase() ?: "—"),
            "customer" to (order?.let { locale.renderPath("customer.${it.customerType.name.lowercase()}.name", player) } ?: locale.text("—")),
            "cart" to locale.text(state.deliveredCrates.size * 100 / runtime.settings.delivery.crates.coerceAtLeast(1)),
        ))
        port.sendChat(player, MessageKey.ADMIN_DEBUG_PATCH, mapOf(
            "size" to locale.text(state.preparationRequired),
            "tilled" to locale.text(state.preparationProgress),
            "planted" to locale.text(state.plantingProgress),
            "damaged" to locale.text(
                state.droughtDamagedPlots.size + state.pestDamagedCrops.size +
                    state.diseaseDamagedCrops.orEmpty().size,
            ),
        ))
        port.sendChat(player, MessageKey.ADMIN_DEBUG_INCIDENT, mapOf(
            "incident" to incident,
            "dry" to locale.text(state.droughtPlots.size),
            "flows" to locale.text(water.activeFlows),
            "water" to locale.text(water.trackedBlocks),
            "nests" to locale.text(state.pestNests.size),
            "spawned" to locale.text(state.pestNests.sumOf(FarmPestNest::spawned)),
            "alive" to locale.text(state.pestAlive),
            "entities" to locale.text(pests.activeCount(runtime)),
        ))
        val careProgress = if (state.careType == FarmCareType.SEEDER) {
            if (state.seederStage() == FarmSeederStage.TILLING) state.preparationProgress else state.plantingProgress
        } else state.careProgress()
        port.sendChat(player, MessageKey.ADMIN_DEBUG_CARE, mapOf(
            "care" to (state.careType?.let { locale.renderPath("care.${it.name.lowercase()}.name", player) } ?: locale.text("—")),
            "done" to locale.text(careProgress),
            "total" to locale.text(if (state.careType == FarmCareType.SEEDER) state.preparationRequired else state.careRequired()),
            "entities" to locale.text(care.count(zoneId)),
        ))
        port.sendChat(player, MessageKey.ADMIN_DEBUG_DELIVERY, mapOf(
            "done" to locale.text(state.deliveredCrates.size),
            "total" to locale.text(runtime.settings.delivery.crates),
            "carriers" to locale.text(delivery.carrierCount(zoneId)),
        ))
        debug.event(
            "farm_admin_debug_status", "player" to player.name, "zone" to zoneId, "phase" to state.phase,
            "sequence" to state.sequence, "order" to state.orderId,
            "progress" to order?.let { "${state.completed(it)}/${it.totalRequired}" },
            "patch" to state.preparationRequired, "drought" to state.droughtPlots.size,
            "nests" to state.pestNests.size, "pests" to state.pestAlive,
            "crates" to "${state.deliveredCrates.size}/${runtime.settings.delivery.crates}",
        )
        return true
    }

    fun setContract(player: Player, zoneId: String, orderId: String): Boolean {
        val runtime = runtime(zoneId, player) ?: return false
        val order = runtime.orders[orderId] ?: run {
            port.sendChat(player, MessageKey.ADMIN_DEBUG_CONTRACT_UNKNOWN, mapOf("order" to locale.text(orderId)))
            return false
        }
        if (worldAdmin.anotherEditor(player)) {
            debug.event("farm_admin_contract_blocked", "zone" to zoneId, "reason" to "another_editor")
            return genericError(player)
        }
        worldAdmin.stopEditing(player)
        if (!reset(runtime)) return genericError(player)
        orderCycle.resumeInMemory(zoneId)
        resetPatchScan(runtime)
        if (!shiftLauncher.start(runtime, player, clock(), order)) return false
        port.sendChat(player, MessageKey.ADMIN_DEBUG_CONTRACT_SET, mapOf("order" to locale.renderPath("order.farm.${order.id}", player)))
        return true
    }

    fun giveSupply(player: Player, zoneId: String, rawKind: String): Boolean {
        val runtime = runtime(zoneId, player) ?: return false
        val kind = FarmSupplyKind.entries.firstOrNull { it.name.equals(rawKind, ignoreCase = true) } ?: return false
        if (!supplies.give(runtime, kind, player)) {
            port.sendChat(player, MessageKey.ADMIN_DEBUG_INVENTORY_FULL)
            return true
        }
        port.sendChat(player, MessageKey.ADMIN_DEBUG_SUPPLY_GIVEN, mapOf("supply" to locale.renderPath("admin.point.${kind.name.lowercase()}", player)))
        return true
    }

    fun showGuidance(player: Player, zoneId: String): Boolean {
        if (runtime(zoneId, player) == null) return false
        guidance.showDebug(player.uniqueId, zoneId, 10)
        port.sendChat(player, MessageKey.ADMIN_DEBUG_HIGHLIGHTED)
        debug.event("farm_admin_debug_highlight", "player" to player.name, "zone" to zoneId)
        return true
    }

    private fun resetStage(player: Player, runtime: FarmRuntime): Boolean {
        if (!reset(runtime) || !orderCycle.set(runtime.settings.id, paused = true)) return genericError(player)
        port.sendChat(player, MessageKey.ADMIN_STAGE_SET, mapOf("stage" to locale.renderPath("admin.stage.reset", player)))
        return true
    }

    private fun preparationStage(player: Player, runtime: FarmRuntime): Boolean {
        if (!reset(runtime)) return genericError(player)
        resetPatchScan(runtime)
        if (!shiftLauncher.start(runtime, player, clock(), null)) return false
        port.sendChat(player, MessageKey.ADMIN_STAGE_SET, mapOf("stage" to locale.renderPath("admin.stage.preparation", player)))
        return true
    }

    private fun ensureShift(runtime: FarmRuntime, player: Player): Boolean {
        orderCycle.resumeInMemory(runtime.settings.id)
        if (runtime.state.phase !in setOf(FarmPhase.IDLE, FarmPhase.COOLDOWN) && runtime.state.preparationPatch.isNotEmpty()) return true
        if (!reset(runtime)) return genericError(player)
        resetPatchScan(runtime)
        return shiftLauncher.start(runtime, player, clock(), null)
    }

    private fun clearActiveObjective(runtime: FarmRuntime, player: Player): Boolean {
        drought.clearZone(runtime.settings.id, "admin_stage")
        care.clear(runtime, "admin_stage")
        runtime.state = runtime.state.copy(careType = null, seederStage = null, careTargets = emptyList(), careGoal = null)
        pests.clear(runtime, "admin_stage")
        birds.clear(runtime.settings.id, "admin_stage")
        foodDelivery.clear(runtime.settings.id, "admin_stage")
        restoreGiantCrop(runtime, "admin_stage")
        special.clearZone(runtime, "admin_stage")
        // An explicit admin transition must leave no incident journal behind. A bounded
        // restore here used to deadlock bird incidents: processRestores deliberately
        // pauses while the runtime is still in INCIDENT, so the remaining crop entries
        // could never make progress and every subsequent /admin event was rejected.
        incidentRecovery.restore(runtime)
        if (incidentRecovery.pending(runtime)) {
            port.sendChat(player, MessageKey.ADMIN_INCIDENT_RECOVERY_PENDING, mapOf("count" to locale.text(incidentRecovery.remaining(runtime))))
            return false
        }
        runtime.state = runtime.state.copy(specialIncident = null, specialDamagedCrops = emptyList())
        delivery.clear(runtime, "admin_stage")
        return true
    }

    private fun careStage(player: Player, runtime: FarmRuntime, stage: String, type: FarmCareType): Boolean {
        val seeder = type == FarmCareType.SEEDER
        if (type in BED_PATCH_CARE_TYPES && !reselectPatch(runtime, player, seeder)) {
            port.sendChat(player, MessageKey.FARM_PATCH_UNAVAILABLE)
            return false
        }
        if (!seeder) preparePatch(runtime, plant = true, mature = true)
        val prepared = if (seeder) runtime.state else FarmAdminStageProgress.completed(runtime.state, planted = true)
        runtime.state = prepared.copy(
            phase = if (seeder) FarmPhase.PREPARATION else FarmPhase.HARVESTING,
            preparationReleased = !seeder,
            tilledPlots = if (seeder) emptySet() else prepared.tilledPlots,
            plantedPlots = if (seeder) emptySet() else prepared.plantedPlots,
            preparationProgress = if (seeder) 0 else prepared.preparationProgress,
            plantingProgress = if (seeder) 0 else prepared.plantingProgress,
            careType = null, careTargets = emptyList(), careGoal = null, incidentType = null,
        )
        if (!care.initialize(runtime, player, type)) {
            port.sendChat(
                player,
                if (type == FarmCareType.MOLES) MessageKey.ADMIN_MOLES_UNAVAILABLE else MessageKey.ADMIN_CARE_UNAVAILABLE,
            )
            return false
        }
        persistAsync()
        port.sendChat(player, MessageKey.ADMIN_STAGE_SET, mapOf("stage" to locale.renderPath("admin.stage.$stage", player)))
        return true
    }

    private fun plantingState(runtime: FarmRuntime, events: MutableList<ShiftEvent>): FarmShiftState {
        preparePatch(runtime, plant = false, mature = false)
        events += ShiftEvent.PLANTING_STARTED
        return FarmAdminStageProgress.completed(runtime.state, planted = false).copy(
            phase = FarmPhase.PLANTING, preparationReleased = true,
            careType = null, careTargets = emptyList(), careGoal = null, incidentType = null,
            pestNestsInitialized = false, pestNests = emptyList(), pestAlive = 0,
        )
    }

    private fun harvestingState(runtime: FarmRuntime): FarmShiftState {
        preparePatch(runtime, plant = true, mature = true)
        val resolved = runtime.state.incidentsResolved + if (runtime.state.phase == FarmPhase.INCIDENT) 1 else 0
        return FarmAdminStageProgress.completed(runtime.state, planted = true).copy(
            phase = FarmPhase.HARVESTING, preparationReleased = true,
            careType = null, careTargets = emptyList(), careGoal = null,
            incidentCrop = null, incidentType = null, incidentProgress = 0, incidentRequired = 0,
            incidentResolved = resolved > 0,
            incidentsResolved = resolved.coerceAtMost(runtime.rules.incidentTargetCount(runtime.state.sequence)),
            pestNestsInitialized = false, pestNests = emptyList(), pestAlive = 0,
        )
    }

    private fun incidentState(
        runtime: FarmRuntime,
        stage: String,
        crop: String,
        events: MutableList<ShiftEvent>,
    ): FarmShiftState {
        preparePatch(runtime, plant = true, mature = true)
        val type = INCIDENT_STAGES.getValue(stage)
        val quota = if (type == FarmIncidentType.DROUGHT) {
            runtime.settings.droughtTargetBeds(field.incidentBeds(runtime).size.coerceAtLeast(runtime.state.preparationPatch.size))
        } else runtime.rules.incidentQuota
        events += ShiftEvent.INCIDENT_STARTED
        return FarmAdminStageProgress.forcedIncident(runtime.state, runtime.rules).copy(
            phase = FarmPhase.INCIDENT, incidentCrop = crop, incidentType = type,
            incidentProgress = 0, incidentRequired = quota, incidentResolved = false,
            droughtPlots = emptySet(), droughtDamagedPlots = emptySet(),
            pestNestsInitialized = false, pestNests = emptyList(), pestAlive = 0,
            pestDamagedCrops = emptyList(), diseaseDamagedCrops = emptyList(),
            specialIncident = null, specialDamagedCrops = emptyList(),
        )
    }

    private fun deliveryState(
        runtime: FarmRuntime,
        order: FarmOrder,
        player: Player,
        events: MutableList<ShiftEvent>,
    ): FarmShiftState {
        events += ShiftEvent.DELIVERY_STARTED
        return runtime.state.copy(
            phase = FarmPhase.DELIVERY, progress = order.required, harvestCheckpoint = 10, harvestMilestone = 4,
            incidentCrop = null, incidentType = null, incidentProgress = 0, incidentRequired = 0,
            pestNestsInitialized = false, pestNests = emptyList(), pestAlive = 0,
            deliveryPosition = placement.selectDeliveryAnchor(runtime, player.location), deliveredCrates = emptySet(),
        )
    }

    private fun completeDelivery(runtime: FarmRuntime, player: Player) {
        val result = FarmShiftEngine.completeDeliveryAsAdmin(
            runtime.state,
            runtime.rules,
            runtime.settings.delivery.crates,
            clock(),
        )
        transitions.apply(runtime, result, player)
    }

    private fun reset(runtime: FarmRuntime): Boolean {
        drought.clearZone(runtime.settings.id, "admin_reset")
        care.clear(runtime, "admin_reset")
        pests.clear(runtime, "admin_reset")
        birds.clear(runtime.settings.id, "admin_reset")
        foodDelivery.clear(runtime.settings.id, "admin_reset")
        restoreGiantCrop(runtime, "admin_reset")
        special.clearZone(runtime, "admin_reset")
        delivery.clear(runtime, "admin_reset")
        scene.clear(runtime, "admin_reset")
        incidentRecovery.restore(runtime)
        if (incidentRecovery.pending(runtime)) return false
        if (runtime.state.preparationPatch.isNotEmpty() && !field.restoreOriginal(runtime)) return false
        field.commitAfterRecovery(runtime, FarmShiftState(sequence = runtime.state.sequence))
        drought.resetGrowth(runtime.settings.id)
        port.players(runtime.region).forEach { supplies.removeServiceItems(it, runtime.settings.id, "admin_reset") }
        return true
    }

    private fun restoreGiantCrop(runtime: FarmRuntime, reason: String) {
        val touched = special.restoreZoneNow(runtime)
        touched.forEach { registry.reconcileChunk(runtime.blockIndexDefinition(), it) }
        if (touched.isNotEmpty()) debug.event(
            "farm_giant_crop_admin_restored", "zone" to runtime.settings.id, "reason" to reason, "chunks" to touched.size,
        )
    }

    private fun preparePatch(runtime: FarmRuntime, plant: Boolean, mature: Boolean) {
        val crop = MaterialRules.material(requireNotNull(runtime.state.preparationCrop))
        runtime.state.preparationPatch.forEach { position ->
            val soil = position.block() ?: return@forEach
            ledger.capture(soil, runtime.settings.id)
            field.wet(soil)
            val above = soil.getRelative(org.bukkit.block.BlockFace.UP)
            if (!plant) {
                above.setType(Material.AIR, false)
                return@forEach
            }
            val data = crop.createBlockData()
            if (mature && data is Ageable) data.age = data.maximumAge
            above.setBlockData(data, false)
            ledger.captureActiveCrop(soil, runtime.settings.id)
        }
    }

    private fun reselectPatch(runtime: FarmRuntime, player: Player, mechanized: Boolean): Boolean {
        val previous = runtime.state.preparationPatch
        val selected = field.selectPatch(runtime, player.location, mechanized, runtime.state.sequence, previous)
        if (selected.isEmpty()) return false
        val maxSize = if (mechanized) runtime.settings.seederPatchMaxSize else runtime.settings.preparationPatchMaxSize
        val replacement = FarmPatchPlanner.retainCurrent(previous, selected, maxSize)
        runtime.state = runtime.state.copy(
            preparationPatch = replacement,
            preparationRequired = FarmFieldQuota.required(replacement.size, runtime.settings.fieldCompletionPercent),
            preparationReleased = false,
        )
        registry.addBeds(runtime.settings.id, replacement)
        debug.event(
            "farm_admin_patch_reselected", "zone" to runtime.settings.id,
            "stage" to if (mechanized) "mechanized" else "care", "before" to previous.size, "after" to replacement.size,
        )
        return true
    }

    private fun resetPatchScan(runtime: FarmRuntime) = port.resetInteraction("farm-patch-scan:${runtime.settings.id}")

    private fun currentOrder(runtime: FarmRuntime): FarmOrder? = runtime.state.orderId?.let(runtime.orders::get)

    private fun runtime(zoneId: String, player: Player): FarmRuntime? =
        runtimes().firstOrNull { it.settings.id == zoneId } ?: run {
            port.sendChat(player, MessageKey.ADMIN_ZONE_UNKNOWN, mapOf("zone" to locale.text(zoneId)))
            null
        }

    private fun genericError(player: Player): Boolean {
        port.sendChat(player, MessageKey.GENERIC_ERROR)
        return false
    }

    private companion object {
        val CARE_STAGES = mapOf(
            "seeder" to FarmCareType.SEEDER, "weeds" to FarmCareType.WEEDS,
            "irrigation" to FarmCareType.IRRIGATION, "pollination" to FarmCareType.POLLINATION,
            "covers" to FarmCareType.STORM_COVERS, "scarecrows" to FarmCareType.SCARECROWS,
            "animals" to FarmCareType.ANIMAL_RESCUE, "disease" to FarmCareType.DISEASE,
            "moles" to FarmCareType.MOLES, "apples" to FarmCareType.APPLE_HARVEST,
        )
        val INCIDENT_STAGES = mapOf(
            "pests" to FarmIncidentType.PESTS, "drought" to FarmIncidentType.DROUGHT,
            "birds" to FarmIncidentType.BIRDS,
            "food-delivery" to FarmIncidentType.FOOD_DELIVERY,
            "giant-crop" to FarmIncidentType.GIANT_CROP, "channels" to FarmIncidentType.CHANNELS,
            "night-shift" to FarmIncidentType.NIGHT_SHIFT, "market" to FarmIncidentType.MARKET,
        )
        val STANDARD_STAGES = setOf("preparation", "planting", "harvesting", "delivery", "complete", "reset") + INCIDENT_STAGES.keys
        val BED_PATCH_CARE_TYPES = setOf(FarmCareType.SEEDER, FarmCareType.WEEDS, FarmCareType.DISEASE, FarmCareType.MOLES)
    }
}
