package ru.ruscrafting.farms.paper.farm.incident.processing

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.FarmProcessingVisualRole
import ru.ruscrafting.farms.config.FarmProcessingSettings
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmProcessingLayout
import ru.ruscrafting.farms.domain.FarmProcessingState
import ru.ruscrafting.farms.domain.FarmProcessingStage
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.domain.FarmStallAction
import ru.ruscrafting.farms.domain.FarmStallWatchdog
import ru.ruscrafting.farms.domain.FarmStallWatchdogState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.BukkitFarmEntityLookup
import ru.ruscrafting.farms.paper.FarmEntityLookup
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.platform.FarmBlockPassability
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayRenderer
import java.util.UUID
import java.util.logging.Level

internal enum class FarmProcessingPlacementFailure {
    WRONG_WORLD,
    OUTSIDE_REGION,
    CHUNK_UNLOADED,
    FARM_BLOCKS,
    UNSUPPORTED_FLOOR,
    BLOCKED_CLEARANCE,
}

private data class ProcessingCargoKey(val zoneId: String, val cargo: ProcessingCargo, val index: Int)

private data class ProcessingCarrierLease(
    val playerId: UUID,
    var watchdog: FarmStallWatchdogState,
    var bestDistance: Double = Double.POSITIVE_INFINITY,
)

private data class ProcessingSceneRevision(
    val sequence: Long,
    val state: FarmProcessingState,
    val settings: FarmProcessingSettings,
    val layout: FarmProcessingLayout,
)

/** Crop processing incident: load raw packages, walk the millstone ring, deliver finished goods. */
internal class FarmProcessingIncident(
    plugin: Plugin,
    private val settings: () -> ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val state: WorksiteStatePort,
    private val configuredPoint: (String, FarmPointKind) -> FarmPointPosition?,
    private val transitions: FarmTransitionSink,
    private val blockPassability: FarmBlockPassability,
    textDisplays: FarmTextDisplayRenderer,
    private val entityLookup: FarmEntityLookup = BukkitFarmEntityLookup,
) {
    private val scene = FarmProcessingScene(plugin, debug, textDisplays, entityLookup)
    private val crank = FarmProcessingCrankController(plugin, settings, debug, access, audience, state, transitions, entityLookup)
    private val carriedZoneKey = NamespacedKey(plugin, "farm_processing_carried_zone")
    private val carriedSequenceKey = NamespacedKey(plugin, "farm_processing_carried_sequence")
    private val carriedCargoKey = NamespacedKey(plugin, "farm_processing_carried_cargo")
    private val carriedIndexKey = NamespacedKey(plugin, "farm_processing_carried_index")
    private val carriers = mutableMapOf<ProcessingCargoKey, ProcessingCarrierLease>()
    private val carriedDisplays = mutableMapOf<ProcessingCargoKey, UUID>()
    private val layouts = mutableMapOf<String, FarmProcessingLayout>()
    private val sceneSpecs = FarmProcessingSceneSpecCache<ProcessingSceneRevision>()
    private val lastUnavailableSequence = mutableMapOf<String, Long>()
    private val adminPreview = FarmProcessingAdminPreview(audience)

    fun owns(entity: Entity): Boolean = scene.owns(entity) ||
        entity.persistentDataContainer.has(carriedZoneKey, PersistentDataType.STRING) ||
        crank.owns(entity)

    fun hasConfiguredPoint(zoneId: String): Boolean = configuredPoint(zoneId, FarmPointKind.PROCESSING) != null

    fun carrierCount(zoneId: String): Int = carriers.keys.count { it.zoneId == zoneId }

    fun crankParticipantCount(zoneId: String): Int = crank.participantCount(zoneId)

    fun crankProgressDegrees(zoneId: String): Int = crank.progressDegrees(zoneId)

    fun initialize(runtime: FarmRuntime): Boolean {
        if (!active(runtime)) return false
        if (runtime.state.processing != null) return true
        val layout = layout(runtime)
        val failure = layout?.let { validateLayout(runtime, it) }
        if (layout == null || failure != null) {
            logUnavailable(runtime, if (layout == null) "point_missing" else failure!!.name.lowercase())
            return false
        }
        val crop = runtime.state.incidentCrop ?: return false.also { logUnavailable(runtime, "crop_missing") }
        val processing = runtime.settings.processing
        val result = FarmShiftEngine.initializeProcessing(
            runtime.state,
            crop,
            processing.inputPackages,
            processing.machineCycles,
            processing.outputPackages,
        )
        if (!result.accepted) return false
        runtime.state = result.state
        state.persistAsync()
        debug.event(
            "farm_processing_initialized",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "crop" to crop,
            "input" to processing.inputPackages,
            "cycles" to processing.machineCycles,
            "output" to processing.outputPackages,
        )
        return true
    }

    fun ensure(runtime: FarmRuntime) {
        if (!active(runtime)) {
            if (tracked(runtime.settings.id)) clear(runtime.settings.id, "inactive")
            return
        }
        if (!initialize(runtime)) return
        val layout = layout(runtime) ?: return
        val state = requireNotNull(runtime.state.processing)
        val revision = ProcessingSceneRevision(
            sequence = runtime.state.sequence,
            state = state,
            settings = runtime.settings.processing,
            layout = layout,
        )
        scene.ensure(sceneSpecs.resolve(runtime.settings.id, revision) { sceneSpec(runtime, layout) })
    }

    fun interact(event: PlayerInteractEntityEvent, runtimes: Collection<FarmRuntime>): Boolean {
        val identity = scene.identity(event.rightClicked) ?: return false
        event.isCancelled = true
        val runtime = runtimes.firstOrNull { it.settings.id == identity.zoneId } ?: return true
        if (!active(runtime) || runtime.state.sequence != identity.sequence) return true
        val player = event.player
        if (!access.hasAccess(player, runtime.settings.permission)) {
            audience.sendChat(player, MessageKey.ZONE_LOCKED)
            return true
        }
        val maxDistance = runtime.settings.processing.interactionRadius
        if (player.world !== event.rightClicked.world ||
            player.location.distanceSquared(event.rightClicked.location) > maxDistance * maxDistance
        ) return true
        if (!access.allowInteraction("farm-processing:${identity.zoneId}:${player.uniqueId}", 250)) return true
        when (identity.role) {
            FarmProcessingSceneRole.RAW_INTERACTION -> pickup(runtime, player, ProcessingCargo.RAW, identity.index)
            FarmProcessingSceneRole.PRODUCT_INTERACTION -> pickup(runtime, player, ProcessingCargo.PRODUCT, identity.index)
            FarmProcessingSceneRole.MACHINE_INTERACTION -> useMachine(runtime, player)
            else -> showStageHint(runtime, player)
        }
        return true
    }

    fun update(runtimes: Collection<FarmRuntime>, tick: Long) {
        runtimes.forEach { runtime ->
            if (!active(runtime)) return@forEach
            if (tick % 5L == 0L) ensure(runtime)
            updateProximityPickup(runtime)
            updateCarriers(runtime, tick)
            val layout = layout(runtime)
            if (layout == null) {
                crank.clear(runtime.settings.id, "layout_missing")
            } else {
                crank.update(
                    runtime,
                    tick,
                    layout.machine.location(runtime),
                    scene.item(runtime.settings.id, FarmProcessingSceneRole.MACHINE),
                )
            }
        }
    }

    fun releasePlayer(runtimes: Collection<FarmRuntime>, player: Player, reason: String) {
        carriers.filterValues { it.playerId == player.uniqueId }.keys.toList().forEach { key ->
            runtimes.firstOrNull { it.settings.id == key.zoneId }?.let { returnCargo(it, key, player, reason) }
        }
        crank.releasePlayer(player, reason)
    }

    fun validate(runtime: FarmRuntime, anchor: FarmPointPosition): FarmProcessingPlacementFailure? {
        if (anchor.world != runtime.region.world.name) return FarmProcessingPlacementFailure.WRONG_WORLD
        return validateStations(runtime, listOf(anchor))
    }

    fun onChunkLoad(chunk: Chunk) = scene.onChunkLoad(chunk)

    fun refresh(runtime: FarmRuntime, reason: String) {
        clear(runtime.settings.id, reason)
        ensure(runtime)
    }

    fun preview(runtime: FarmRuntime, player: Player) {
        adminPreview.show(runtime, layout(runtime) ?: return, player)
    }

    fun clear(zoneId: String, reason: String) {
        scene.clear(zoneId, reason)
        sceneSpecs.invalidate(zoneId)
        layouts.remove(zoneId)
        carriers.keys.filter { it.zoneId == zoneId }.toList().forEach { key ->
            carriers.remove(key)
            removeCarried(key)
        }
        crank.clear(zoneId, reason)
    }

    fun cleanup(reason: String) {
        scene.cleanup(reason)
        entityLookup.inAllWorlds().filter { entity ->
            entity.persistentDataContainer.has(carriedZoneKey, PersistentDataType.STRING)
        }.forEach(Entity::remove)
        carriers.clear()
        carriedDisplays.clear()
        layouts.clear()
        sceneSpecs.clear()
        crank.cleanup(reason)
        lastUnavailableSequence.clear()
    }

    private fun pickup(runtime: FarmRuntime, player: Player, cargo: ProcessingCargo, index: Int) {
        val state = runtime.state.processing ?: return
        val expectedStage = if (cargo == ProcessingCargo.RAW) FarmProcessingStage.LOADING else FarmProcessingStage.PACKING
        if (state.stage != expectedStage) {
            showStageHint(runtime, player)
            return
        }
        val remainingSlots = if (cargo == ProcessingCargo.RAW) {
            state.remainingInputSlots
        } else {
            state.remainingOutputSlots
        }
        val key = ProcessingCargoKey(runtime.settings.id, cargo, index)
        if (index !in remainingSlots || key in carriers || carriers.values.any { it.playerId == player.uniqueId }) {
            audience.sendActionBar(player, MessageKey.FARM_PROCESSING_ALREADY_CARRYING)
            return
        }
        trackCarrier(key, ProcessingCarrierLease(
            playerId = player.uniqueId,
            watchdog = FarmStallWatchdogState(Bukkit.getCurrentTick().toLong()),
        ))
        val display = player.world.spawn(carriedLocation(runtime, player), ItemDisplay::class.java) { entity ->
            entity.setItemStack(FarmProcessingItems.packageItem(runtime, cargo))
            entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
            val visual = FarmProcessingItems.visual(runtime.settings.processing, FarmProcessingItems.packageRole(cargo))
            entity.transformation = Transformation(
                Vector3f(), AxisAngle4f(), Vector3f(visual.scale, visual.scale, visual.scale), AxisAngle4f(),
            )
            entity.viewRange = runtime.settings.processing.displayViewRange
            entity.teleportDuration = 1
            entity.interpolationDuration = 2
            entity.isPersistent = false
            entity.isGlowing = true
            markCarried(entity, runtime, key)
        }
        carriedDisplays[key] = display.uniqueId
        if (settings().sounds) player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 0.8f, if (cargo == ProcessingCargo.RAW) 0.9f else 1.15f)
        audience.showScreenTitle(
            player,
            if (cargo == ProcessingCargo.RAW) MessageKey.FARM_PROCESSING_RAW_PICKED_UP else MessageKey.FARM_PROCESSING_PRODUCT_PICKED_UP,
        )
        debug.event(
            "farm_processing_picked_up",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "cargo" to cargo,
            "index" to index,
            "player" to player.name,
        )
        ensure(runtime)
    }

    private fun operate(runtime: FarmRuntime, player: Player) {
        showStageHint(runtime, player)
    }

    private fun useMachine(runtime: FarmRuntime, player: Player) {
        when (runtime.state.processing?.stage) {
            FarmProcessingStage.LOADING -> loadCarriedRaw(runtime, player)
            FarmProcessingStage.OPERATING -> operate(runtime, player)
            FarmProcessingStage.PACKING, null -> showStageHint(runtime, player)
        }
    }

    private fun loadCarriedRaw(runtime: FarmRuntime, player: Player) {
        val entry = carriers.entries.firstOrNull { (key, lease) ->
            key.zoneId == runtime.settings.id && key.cargo == ProcessingCargo.RAW && lease.playerId == player.uniqueId
        }
        if (entry == null) {
            showStageHint(runtime, player)
            return
        }
        removeCarrier(entry.key)
        removeCarried(entry.key)
        transitions.apply(
            runtime,
            FarmShiftEngine.advanceProcessing(
                runtime.state,
                player.uniqueId,
                FarmProcessingStage.LOADING,
                entry.key.index,
            ),
            player,
        )
        if (settings().sounds) player.playSound(player.location, Sound.BLOCK_BARREL_CLOSE, 0.9f, 0.9f)
        if (settings().particles) {
            val target = layout(runtime)?.inputDrop?.location(runtime) ?: player.location
            target.world.spawnParticle(Particle.COMPOSTER, target.clone().add(0.0, 1.0, 0.0), 10, 0.5, 0.5, 0.5, 0.04)
        }
    }

    private fun updateCarriers(runtime: FarmRuntime, tick: Long) {
        carriers.filterKeys { it.zoneId == runtime.settings.id }.toMap().forEach { (key, lease) ->
            val player = Bukkit.getPlayer(lease.playerId)
            if (player == null || !player.isOnline || !runtime.region.contains(player.location)) {
                returnCargo(runtime, key, player, "carrier_unavailable")
                return@forEach
            }
            val display = carriedDisplays[key]?.let(Bukkit::getEntity) as? ItemDisplay
            if (display == null || !display.isValid) {
                removeCarrier(key)
                return@forEach
            }
            display.teleport(carriedLocation(runtime, player))
            val layout = layout(runtime) ?: return@forEach
            val target = if (key.cargo == ProcessingCargo.RAW) layout.inputDrop else layout.outputPallet
            val targetLocation = target.location(runtime)
            if (player.world !== targetLocation.world) {
                returnCargo(runtime, key, player, "wrong_world")
                return@forEach
            }
            val distance = player.location.distance(targetLocation)
            if (distance > runtime.settings.processing.deliveryRadius) {
                val progressed = distance <= lease.bestDistance - CARGO_PROGRESS_DISTANCE
                if (progressed || !lease.bestDistance.isFinite()) lease.bestDistance = distance
                val result = FarmStallWatchdog.observe(
                    lease.watchdog,
                    tick,
                    progressed,
                    runtime.settings.processing.cargoReminderSeconds * 20L,
                    runtime.settings.processing.cargoReturnSeconds * 20L,
                )
                lease.watchdog = result.state
                when (result.action) {
                    FarmStallAction.NONE -> Unit
                    FarmStallAction.REMIND -> audience.showScreenTitle(player, pickupTitle(key.cargo), scope = "cargo_watchdog")
                    FarmStallAction.RELEASE -> returnCargo(runtime, key, player, "cargo_stalled")
                }
                return@forEach
            }
            removeCarrier(key)
            removeCarried(key)
            val stage = if (key.cargo == ProcessingCargo.RAW) FarmProcessingStage.LOADING else FarmProcessingStage.PACKING
            transitions.apply(
                runtime,
                FarmShiftEngine.advanceProcessing(runtime.state, player.uniqueId, stage, key.index),
                player,
            )
            if (settings().sounds) {
                val pitch = if (key.cargo == ProcessingCargo.RAW) 0.9f else 1.2f
                player.playSound(player.location, Sound.BLOCK_BARREL_CLOSE, 0.9f, pitch)
            }
            if (settings().particles) targetLocation.world.spawnParticle(Particle.COMPOSTER, targetLocation.clone().add(0.0, 1.0, 0.0), 10, 0.5, 0.5, 0.5, 0.04)
        }
    }

    private fun updateProximityPickup(runtime: FarmRuntime) {
        val state = runtime.state.processing ?: return
        val cargo = when (state.stage) {
            FarmProcessingStage.LOADING -> ProcessingCargo.RAW
            FarmProcessingStage.PACKING -> ProcessingCargo.PRODUCT
            FarmProcessingStage.OPERATING -> return
        }
        val remainingSlots = if (cargo == ProcessingCargo.RAW) {
            state.remainingInputSlots
        } else {
            state.remainingOutputSlots
        }
        if (remainingSlots.isEmpty()) return
        val layout = layout(runtime) ?: return
        val radiusSquared = runtime.settings.processing.proximityPickupRadius.let { it * it }
        audience.players(runtime.region)
            .asSequence()
            .filterNot(access::isAdminEditing)
            .filter { player -> access.hasAccess(player, runtime.settings.permission) }
            .filterNot { player -> carriers.values.any { lease -> lease.playerId == player.uniqueId } }
            .forEach { player ->
                val candidate = remainingSlots
                    .asSequence()
                    .filterNot { index -> ProcessingCargoKey(runtime.settings.id, cargo, index) in carriers }
                    .map { index ->
                        val point = if (cargo == ProcessingCargo.RAW) {
                            FarmProcessingLayout.packagePosition(layout.inputRacks, index)
                        } else {
                            FarmProcessingLayout.floorPackagePosition(layout.outputChute, index)
                        }
                        index to point.location(runtime)
                    }
                    .filter { (_, location) -> location.world === player.world }
                    .minByOrNull { (_, location) -> player.location.distanceSquared(location) }
                    ?: return@forEach
                if (player.location.distanceSquared(candidate.second) <= radiusSquared) {
                    pickup(runtime, player, cargo, candidate.first)
                }
            }
    }


    private fun layout(runtime: FarmRuntime): FarmProcessingLayout? {
        val zoneId = runtime.settings.id
        layouts[zoneId]?.let { return it }
        val machine = configuredPoint(zoneId, FarmPointKind.PROCESSING) ?: return null
        return FarmProcessingLayout.create(
            machine,
            PROCESSING_INPUT_POINTS.mapNotNull { configuredPoint(zoneId, it) },
            configuredPoint(zoneId, FarmPointKind.PROCESSING_OUTPUT),
        ).also { layouts[zoneId] = it }
    }

    private fun validateLayout(
        runtime: FarmRuntime,
        layout: FarmProcessingLayout,
    ): FarmProcessingPlacementFailure? = validateStations(
        runtime,
        layout.inputRacks + layout.machine + layout.outputPallet,
    )

    private fun validateStations(
        runtime: FarmRuntime,
        stations: List<FarmPointPosition>,
    ): FarmProcessingPlacementFailure? {
        val locations = stations.map { point -> point.location(runtime) }
        if (locations.any { !runtime.region.contains(it) }) return FarmProcessingPlacementFailure.OUTSIDE_REGION
        if (locations.any { !it.world.isChunkLoaded(it.blockX shr 4, it.blockZ shr 4) }) {
            return FarmProcessingPlacementFailure.CHUNK_UNLOADED
        }
        if (locations.any { location ->
                location.block.type.name in runtime.settings.crops ||
                    location.block.getRelative(org.bukkit.block.BlockFace.DOWN).type == Material.FARMLAND
            }
        ) return FarmProcessingPlacementFailure.FARM_BLOCKS
        if (locations.any { !it.block.getRelative(org.bukkit.block.BlockFace.DOWN).type.isSolid }) {
            return FarmProcessingPlacementFailure.UNSUPPORTED_FLOOR
        }
        if (locations.any { station ->
                (0..2).any { up -> !blockPassability.isPassable(station.block.getRelative(org.bukkit.block.BlockFace.UP, up)) }
            }
        ) return FarmProcessingPlacementFailure.BLOCKED_CLEARANCE
        return null
    }

    private fun sceneSpec(runtime: FarmRuntime, layout: FarmProcessingLayout): FarmProcessingSceneSpec {
        val state = requireNotNull(runtime.state.processing)
        val processing = runtime.settings.processing
        val objects = mutableListOf<FarmProcessingSceneObject>()
        objects += display(
            runtime,
            FarmProcessingSceneRole.MACHINE,
            0,
            layout.machine,
            FarmProcessingVisualRole.MACHINE,
        )
        objects += display(
            runtime,
            FarmProcessingSceneRole.OUTPUT_PALLET,
            0,
            layout.outputPallet,
            FarmProcessingVisualRole.OUTPUT_PALLET,
            glowing = state.stage == FarmProcessingStage.PACKING,
        )
        objects += FarmProcessingSceneObject(
            FarmProcessingSceneRole.MACHINE_INTERACTION,
            0,
            FarmProcessingLayout.offset(layout.machine, 0.0, 0.25, 0.8).location(runtime),
            interactionWidth = 2.1f, interactionHeight = 2.1f,
        )
        objects += FarmProcessingSceneObject(
            FarmProcessingSceneRole.LABEL,
            100,
            layout.machineLabel.location(runtime),
            text = locale.render(
                when (state.stage) {
                    FarmProcessingStage.LOADING -> MessageKey.FARM_PROCESSING_MACHINE_WAITING_LABEL
                    FarmProcessingStage.OPERATING -> MessageKey.FARM_PROCESSING_MACHINE_ACTIVE_LABEL
                    FarmProcessingStage.PACKING -> MessageKey.FARM_PROCESSING_MACHINE_DONE_LABEL
                },
                null,
            ),
        )
        objects += FarmProcessingSceneObject(
            FarmProcessingSceneRole.LABEL,
            101,
            layout.outputLabel.location(runtime),
            text = locale.render(MessageKey.FARM_PROCESSING_OUTPUT_LABEL, null),
        )
        if (state.stage == FarmProcessingStage.OPERATING) {
            val radius = (processing.crankInnerRadius + processing.crankOuterRadius) / 2.0
            repeat(CRANK_TRACK_POINTS) { index ->
                val angle = 2.0 * kotlin.math.PI * index / CRANK_TRACK_POINTS
                objects += FarmProcessingSceneObject(
                    role = FarmProcessingSceneRole.CRANK_TRACK,
                    index = index,
                    location = layout.machine.location(runtime).add(
                        radius * kotlin.math.cos(angle),
                        CRANK_TRACK_Y_OFFSET,
                        radius * kotlin.math.sin(angle),
                    ),
                    text = Component.text("◆", NamedTextColor.AQUA),
                    scale = CRANK_TRACK_SCALE,
                    billboard = Display.Billboard.FIXED,
                    pitchOffset = 90f,
                )
            }
        }
        if (state.stage == FarmProcessingStage.LOADING) {
            state.remainingInputSlots.filterNot { index ->
                ProcessingCargoKey(runtime.settings.id, ProcessingCargo.RAW, index) in carriers
            }.forEach { index ->
                val point = FarmProcessingLayout.packagePosition(layout.inputRacks, index)
                objects += display(runtime, FarmProcessingSceneRole.RAW_PACKAGE, index, point, FarmProcessingVisualRole.RAW_PACKAGE, true)
                objects += FarmProcessingSceneObject(
                    FarmProcessingSceneRole.RAW_INTERACTION, index, point.location(runtime),
                    interactionWidth = 1.8f, interactionHeight = 1.8f,
                )
            }
        }
        if (state.stage == FarmProcessingStage.PACKING) {
            state.remainingOutputSlots.filterNot { index ->
                ProcessingCargoKey(runtime.settings.id, ProcessingCargo.PRODUCT, index) in carriers
            }.forEach { index ->
                val point = FarmProcessingLayout.floorPackagePosition(layout.outputChute, index)
                objects += display(runtime, FarmProcessingSceneRole.PRODUCT_PACKAGE, index, point, FarmProcessingVisualRole.PRODUCT_PACKAGE, true)
                objects += FarmProcessingSceneObject(
                    FarmProcessingSceneRole.PRODUCT_INTERACTION, index, point.location(runtime),
                    interactionWidth = 1.8f, interactionHeight = 1.8f,
                )
            }
            state.occupiedOutputSlots.sorted().forEach { index ->
                objects += display(
                    runtime,
                    FarmProcessingSceneRole.DELIVERED_PACKAGE,
                    index,
                    FarmProcessingLayout.packagePosition(layout.outputPallet, index, delivered = true),
                    FarmProcessingVisualRole.PRODUCT_PACKAGE,
                )
            }
        }
        return FarmProcessingSceneSpec(
            runtime.settings.id,
            runtime.state.sequence,
            processing.displayViewRange,
            processing.spawnPerTick,
            objects,
        )
    }

    private fun display(
        runtime: FarmRuntime,
        role: FarmProcessingSceneRole,
        index: Int,
        point: FarmPointPosition,
        visualRole: FarmProcessingVisualRole,
        glowing: Boolean = false,
    ): FarmProcessingSceneObject {
        val visual = FarmProcessingItems.visual(runtime.settings.processing, visualRole)
        val item = when (visualRole) {
            FarmProcessingVisualRole.RAW_PACKAGE -> FarmProcessingItems.packageItem(runtime, ProcessingCargo.RAW)
            FarmProcessingVisualRole.PRODUCT_PACKAGE -> FarmProcessingItems.packageItem(runtime, ProcessingCargo.PRODUCT)
            else -> FarmProcessingItems.item(visual)
        }
        return FarmProcessingSceneObject(
            role = role,
            index = index,
            location = point.location(runtime).add(0.0, visual.yOffset, 0.0),
            item = item,
            transform = visual.displayTransform,
            scale = visual.scale,
            yawOffset = visual.yawOffset,
            glowing = glowing,
        )
    }

    private fun returnCargo(runtime: FarmRuntime, key: ProcessingCargoKey, player: Player?, reason: String) {
        removeCarrier(key)
        removeCarried(key)
        player?.takeIf(Player::isOnline)?.let {
            audience.sendActionBar(it, MessageKey.FARM_PROCESSING_RETURNED)
            audience.showScreenTitle(it, MessageKey.FARM_PROCESSING_RETURNED, scope = "cargo_watchdog")
        }
        debug.event(
            "farm_processing_returned",
            "zone" to key.zoneId,
            "sequence" to runtime.state.sequence,
            "cargo" to key.cargo,
            "index" to key.index,
            "player" to player?.name,
            "reason" to reason,
        )
    }

    private fun trackCarrier(key: ProcessingCargoKey, lease: ProcessingCarrierLease) {
        carriers[key] = lease
        sceneSpecs.invalidate(key.zoneId)
    }

    private fun removeCarrier(key: ProcessingCargoKey): ProcessingCarrierLease? =
        carriers.remove(key)?.also { sceneSpecs.invalidate(key.zoneId) }

    private fun removeCarried(key: ProcessingCargoKey) {
        carriedDisplays.remove(key)?.let(Bukkit::getEntity)?.remove()
    }

    private fun carriedLocation(runtime: FarmRuntime, player: Player): Location {
        val behind = player.location.direction.setY(0.0)
        if (behind.lengthSquared() > 0.001) behind.normalize().multiply(-0.6)
        return player.location.clone().add(behind).add(0.0, runtime.settings.processing.carriedYOffset, 0.0)
    }

    private fun markCarried(entity: Entity, runtime: FarmRuntime, key: ProcessingCargoKey) {
        entity.persistentDataContainer.set(carriedZoneKey, PersistentDataType.STRING, key.zoneId)
        entity.persistentDataContainer.set(carriedSequenceKey, PersistentDataType.LONG, runtime.state.sequence)
        entity.persistentDataContainer.set(carriedCargoKey, PersistentDataType.STRING, key.cargo.name)
        entity.persistentDataContainer.set(carriedIndexKey, PersistentDataType.INTEGER, key.index)
    }

    private fun stageHint(runtime: FarmRuntime): MessageKey = when (runtime.state.processing?.stage) {
        FarmProcessingStage.LOADING -> MessageKey.FARM_PROCESSING_LOADING_HINT
        FarmProcessingStage.OPERATING -> MessageKey.FARM_PROCESSING_OPERATING_HINT
        FarmProcessingStage.PACKING -> MessageKey.FARM_PROCESSING_PACKING_HINT
        null -> MessageKey.FARM_PROCESSING_LOADING_HINT
    }

    private fun showStageHint(runtime: FarmRuntime, player: Player) {
        audience.sendActionBar(player, stageHint(runtime))
        val cooldownMillis = runtime.settings.processing.crankTitleReminderSeconds * 1_000L
        if (!access.allowInteraction("farm-processing-title:${runtime.settings.id}:${player.uniqueId}", cooldownMillis)) return
        val title = when (runtime.state.processing?.stage) {
            FarmProcessingStage.LOADING, null -> MessageKey.FARM_PROCESSING_LOADING_TITLE
            FarmProcessingStage.OPERATING -> MessageKey.FARM_PROCESSING_OPERATING_TITLE
            FarmProcessingStage.PACKING -> MessageKey.FARM_PROCESSING_PACKING_TITLE
        }
        audience.showScreenTitle(player, title, scope = "processing_hint")
    }

    private fun pickupTitle(cargo: ProcessingCargo): MessageKey =
        if (cargo == ProcessingCargo.RAW) MessageKey.FARM_PROCESSING_RAW_PICKED_UP
        else MessageKey.FARM_PROCESSING_PRODUCT_PICKED_UP

    private fun active(runtime: FarmRuntime): Boolean =
        runtime.state.phase == FarmPhase.INCIDENT && runtime.state.incidentType == FarmIncidentType.PROCESSING

    private fun tracked(zoneId: String): Boolean = scene.hasZone(zoneId) ||
        carriers.keys.any { it.zoneId == zoneId } ||
        crank.hasZone(zoneId)

    private fun logUnavailable(runtime: FarmRuntime, reason: String) {
        if (lastUnavailableSequence[runtime.settings.id] == runtime.state.placementSequence) return
        lastUnavailableSequence[runtime.settings.id] = runtime.state.placementSequence
        state.log(
            Level.WARNING,
            "Could not start farm processing incident: zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                "placement_sequence=${runtime.state.placementSequence} reason=$reason",
        )
        debug.event(
            "farm_processing_unavailable",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "placement_sequence" to runtime.state.placementSequence,
            "reason" to reason,
        )
    }

    private fun FarmPointPosition.location(runtime: FarmRuntime): Location =
        Location(runtime.region.world, x, y, z, yaw, pitch)

    private companion object {
        const val CARGO_PROGRESS_DISTANCE = 1.0
        const val CRANK_TRACK_POINTS = 24
        const val CRANK_TRACK_Y_OFFSET = 0.035
        const val CRANK_TRACK_SCALE = 1.75f
        val PROCESSING_INPUT_POINTS = listOf(
            FarmPointKind.PROCESSING_INPUT,
            FarmPointKind.PROCESSING_INPUT_2,
            FarmPointKind.PROCESSING_INPUT_3,
            FarmPointKind.PROCESSING_INPUT_4,
        )

    }
}
