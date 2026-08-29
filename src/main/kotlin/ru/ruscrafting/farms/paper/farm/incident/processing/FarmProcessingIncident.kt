package ru.ruscrafting.farms.paper.farm.incident.processing

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
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
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmProcessingLayout
import ru.ruscrafting.farms.domain.FarmProcessingDialPlanner
import ru.ruscrafting.farms.domain.FarmProcessingStage
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.domain.FarmStallAction
import ru.ruscrafting.farms.domain.FarmStallWatchdog
import ru.ruscrafting.farms.domain.FarmStallWatchdogState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.BukkitFarmEntityLookup
import ru.ruscrafting.farms.paper.FarmEntityLookup
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.platform.FarmBlockPassability
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayRenderer
import java.util.UUID
import java.util.logging.Level
import kotlin.math.abs

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

/** Crop processing incident: load raw packages, time the mechanism, deliver finished goods. */
internal class FarmProcessingIncident(
    plugin: Plugin,
    private val settings: () -> ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
    private val port: WorksiteRuntimePort,
    private val configuredPoint: (String, FarmPointKind) -> FarmPointPosition?,
    private val transitions: FarmTransitionSink,
    private val blockPassability: FarmBlockPassability,
    textDisplays: FarmTextDisplayRenderer,
    private val entityLookup: FarmEntityLookup = BukkitFarmEntityLookup,
) {
    private val scene = FarmProcessingScene(plugin, debug, textDisplays, entityLookup)
    private val carriedZoneKey = NamespacedKey(plugin, "farm_processing_carried_zone")
    private val carriedSequenceKey = NamespacedKey(plugin, "farm_processing_carried_sequence")
    private val carriedCargoKey = NamespacedKey(plugin, "farm_processing_carried_cargo")
    private val carriedIndexKey = NamespacedKey(plugin, "farm_processing_carried_index")
    private val carriers = mutableMapOf<ProcessingCargoKey, ProcessingCarrierLease>()
    private val carriedDisplays = mutableMapOf<ProcessingCargoKey, UUID>()
    private val successfulCycles = mutableMapOf<Pair<String, UUID>, Long>()
    private val lastUnavailableSequence = mutableMapOf<String, Long>()
    private val adminPreview = FarmProcessingAdminPreview(port)

    fun owns(entity: Entity): Boolean = scene.owns(entity) ||
        entity.persistentDataContainer.has(carriedZoneKey, PersistentDataType.STRING)

    fun hasConfiguredPoint(zoneId: String): Boolean = configuredPoint(zoneId, FarmPointKind.PROCESSING) != null

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
        port.persistAsync()
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
        scene.ensure(sceneSpec(runtime, layout))
    }

    fun interact(event: PlayerInteractEntityEvent, runtimes: Collection<FarmRuntime>): Boolean {
        val identity = scene.identity(event.rightClicked) ?: return false
        event.isCancelled = true
        val runtime = runtimes.firstOrNull { it.settings.id == identity.zoneId } ?: return true
        if (!active(runtime) || runtime.state.sequence != identity.sequence) return true
        val player = event.player
        if (!port.hasAccess(player, runtime.settings.permission)) {
            port.sendChat(player, MessageKey.ZONE_LOCKED)
            return true
        }
        val maxDistance = runtime.settings.processing.interactionRadius
        if (player.world !== event.rightClicked.world ||
            player.location.distanceSquared(event.rightClicked.location) > maxDistance * maxDistance
        ) return true
        if (!port.allowInteraction("farm-processing:${identity.zoneId}:${player.uniqueId}", 250)) return true
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
            updateCarriers(runtime, tick)
            renderMechanism(runtime, tick)
        }
    }

    fun releasePlayer(runtimes: Collection<FarmRuntime>, player: Player, reason: String) {
        carriers.filterValues { it.playerId == player.uniqueId }.keys.toList().forEach { key ->
            runtimes.firstOrNull { it.settings.id == key.zoneId }?.let { returnCargo(it, key, player, reason) }
        }
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
        carriers.keys.filter { it.zoneId == zoneId }.toList().forEach { key ->
            carriers.remove(key)
            removeCarried(key)
        }
        successfulCycles.keys.removeIf { it.first == zoneId }
    }

    fun cleanup(reason: String) {
        scene.cleanup(reason)
        entityLookup.inAllWorlds().filter { entity ->
            entity.persistentDataContainer.has(carriedZoneKey, PersistentDataType.STRING)
        }.forEach(Entity::remove)
        carriers.clear()
        carriedDisplays.clear()
        successfulCycles.clear()
        lastUnavailableSequence.clear()
    }

    private fun pickup(runtime: FarmRuntime, player: Player, cargo: ProcessingCargo, index: Int) {
        val state = runtime.state.processing ?: return
        val expectedStage = if (cargo == ProcessingCargo.RAW) FarmProcessingStage.LOADING else FarmProcessingStage.PACKING
        if (state.stage != expectedStage) {
            showStageHint(runtime, player)
            return
        }
        val remaining = if (cargo == ProcessingCargo.RAW) {
            state.inputRequired - state.inputLoaded
        } else {
            state.outputRequired - state.outputDelivered
        }
        val range = 0 until remaining
        val key = ProcessingCargoKey(runtime.settings.id, cargo, index)
        if (index !in range || key in carriers || carriers.values.any { it.playerId == player.uniqueId }) {
            port.sendActionBar(player, MessageKey.FARM_PROCESSING_ALREADY_CARRYING)
            return
        }
        carriers[key] = ProcessingCarrierLease(
            playerId = player.uniqueId,
            watchdog = FarmStallWatchdogState(Bukkit.getCurrentTick().toLong()),
        )
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
        port.showScreenTitle(
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
        val state = runtime.state.processing ?: return
        if (state.stage != FarmProcessingStage.OPERATING) {
            showStageHint(runtime, player)
            return
        }
        val configured = runtime.settings.processing
        val tick = Bukkit.getCurrentTick().toLong()
        val shifted = tick + runtime.state.placementSequence * 17L
        val phase = Math.floorMod(shifted, configured.dialPeriodTicks.toLong()).toInt()
        val center = configured.dialPeriodTicks / 2
        val success = abs(phase - center) <= configured.dialWindowTicks / 2
        val cycle = Math.floorDiv(shifted, configured.dialPeriodTicks.toLong())
        val cycleKey = runtime.settings.id to player.uniqueId
        if (!success) {
            port.sendActionBar(player, MessageKey.FARM_PROCESSING_TIMING_MISSED)
            if (settings().sounds) player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.65f, 0.75f)
            if (settings().particles) player.spawnParticle(Particle.DUST, player.eyeLocation, 5, 0.25, 0.25, 0.25, 0.0, RED)
            return
        }
        if (successfulCycles[cycleKey] == cycle) {
            port.sendActionBar(player, MessageKey.FARM_PROCESSING_CYCLE_ALREADY_COUNTED)
            return
        }
        successfulCycles[cycleKey] = cycle
        transitions.apply(runtime, FarmShiftEngine.advanceProcessing(runtime.state, player.uniqueId, state.stage), player)
        if (settings().sounds) {
            player.playSound(player.location, Sound.BLOCK_PISTON_EXTEND, 0.85f, 1.1f)
            player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.65f, 1.35f)
        }
        if (settings().particles) player.spawnParticle(Particle.COMPOSTER, player.location.clone().add(0.0, 1.0, 0.0), 7, 0.45, 0.4, 0.45, 0.04)
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
        carriers.remove(entry.key)
        removeCarried(entry.key)
        transitions.apply(
            runtime,
            FarmShiftEngine.advanceProcessing(runtime.state, player.uniqueId, FarmProcessingStage.LOADING),
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
                carriers.remove(key)
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
                    FarmStallAction.REMIND -> port.showScreenTitle(player, pickupTitle(key.cargo), scope = "cargo_watchdog")
                    FarmStallAction.RELEASE -> returnCargo(runtime, key, player, "cargo_stalled")
                }
                return@forEach
            }
            carriers.remove(key)
            removeCarried(key)
            val stage = if (key.cargo == ProcessingCargo.RAW) FarmProcessingStage.LOADING else FarmProcessingStage.PACKING
            transitions.apply(runtime, FarmShiftEngine.advanceProcessing(runtime.state, player.uniqueId, stage), player)
            if (settings().sounds) {
                val pitch = if (key.cargo == ProcessingCargo.RAW) 0.9f else 1.2f
                player.playSound(player.location, Sound.BLOCK_BARREL_CLOSE, 0.9f, pitch)
            }
            if (settings().particles) targetLocation.world.spawnParticle(Particle.COMPOSTER, targetLocation.clone().add(0.0, 1.0, 0.0), 10, 0.5, 0.5, 0.5, 0.04)
        }
    }

    private fun renderMechanism(runtime: FarmRuntime, tick: Long) {
        val state = runtime.state.processing ?: return
        val layout = layout(runtime) ?: return
        val configured = runtime.settings.processing
        if (settings().particles && tick % 10L == 0L) {
            val base = layout.machine.location(runtime).add(0.0, 0.35, 0.0)
            var height = 0.0
            while (height <= configured.particleColumnHeight) {
                base.world.spawnParticle(Particle.DUST, base.clone().add(0.0, height, 0.0), 1, 0.0, 0.0, 0.0, 0.0, GOLD)
                height += 1.15
            }
        }
        val machine = scene.item(runtime.settings.id, FarmProcessingSceneRole.MACHINE)
        if (state.stage != FarmProcessingStage.OPERATING) {
            machine?.isGlowing = false
            return
        }
        val shifted = tick + runtime.state.placementSequence * 17L
        val phase = Math.floorMod(shifted, configured.dialPeriodTicks.toLong()).toInt()
        val center = configured.dialPeriodTicks / 2
        val inSuccessWindow = abs(phase - center) <= configured.dialWindowTicks / 2
        machine?.isGlowing = inSuccessWindow
        if (settings().particles && tick % 3L == 0L) {
            val dial = FarmProcessingDialPlanner.plan(
                machine = layout.machine,
                phase = phase,
                periodTicks = configured.dialPeriodTicks,
                successWindowTicks = configured.dialWindowTicks,
                centerYOffset = configured.dialCenterYOffset,
                rightOffset = configured.dialRightOffset,
                forwardOffset = configured.dialForwardOffset,
                radius = configured.dialRadius,
                pointCount = configured.dialPointCount,
            )
            dial.ring.forEach { point ->
                val location = point.position.location(runtime)
                location.world.spawnParticle(
                    Particle.DUST,
                    location,
                    1,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    if (point.inSuccessWindow) GREEN else DIAL_TRACK,
                )
            }
            val marker = dial.marker.position.location(runtime)
            marker.world.spawnParticle(
                Particle.DUST,
                marker,
                if (dial.marker.inSuccessWindow) 4 else 2,
                0.035,
                0.035,
                0.035,
                0.0,
                if (dial.marker.inSuccessWindow) GREEN_MARKER else GOLD_MARKER,
            )
        }
    }

    private fun layout(runtime: FarmRuntime): FarmProcessingLayout? {
        val machine = configuredPoint(runtime.settings.id, FarmPointKind.PROCESSING) ?: return null
        return FarmProcessingLayout.create(
            machine,
            PROCESSING_INPUT_POINTS.mapNotNull { configuredPoint(runtime.settings.id, it) },
            configuredPoint(runtime.settings.id, FarmPointKind.PROCESSING_OUTPUT),
        )
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
        )
        objects += FarmProcessingSceneObject(
            FarmProcessingSceneRole.MACHINE_INTERACTION,
            0,
            FarmProcessingLayout.offset(layout.machine, 0.0, 0.25, 0.8).location(runtime),
            interactionWidth = 2.1f, interactionHeight = 2.1f,
        )
        layout.inputLabels.forEachIndexed { index, point ->
            objects += FarmProcessingSceneObject(
                FarmProcessingSceneRole.LABEL,
                index,
                point.location(runtime),
                text = locale.render(MessageKey.FARM_PROCESSING_INPUT_LABEL, null),
            )
        }
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
        if (state.stage == FarmProcessingStage.LOADING) {
            (0 until state.inputRequired - state.inputLoaded).filterNot { index ->
                ProcessingCargoKey(runtime.settings.id, ProcessingCargo.RAW, index) in carriers
            }.forEach { index ->
                val point = FarmProcessingLayout.packagePosition(layout.inputRacks, index)
                objects += display(runtime, FarmProcessingSceneRole.RAW_PACKAGE, index, point, FarmProcessingVisualRole.RAW_PACKAGE, true)
                objects += FarmProcessingSceneObject(
                    FarmProcessingSceneRole.RAW_INTERACTION, index, point.location(runtime),
                    interactionWidth = 1.05f, interactionHeight = 1.25f,
                )
            }
        }
        if (state.stage == FarmProcessingStage.PACKING) {
            (0 until state.outputRequired - state.outputDelivered).filterNot { index ->
                ProcessingCargoKey(runtime.settings.id, ProcessingCargo.PRODUCT, index) in carriers
            }.forEach { index ->
                val point = FarmProcessingLayout.floorPackagePosition(layout.outputChute, index)
                objects += display(runtime, FarmProcessingSceneRole.PRODUCT_PACKAGE, index, point, FarmProcessingVisualRole.PRODUCT_PACKAGE, true)
                objects += FarmProcessingSceneObject(
                    FarmProcessingSceneRole.PRODUCT_INTERACTION, index, point.location(runtime),
                    interactionWidth = 1.05f, interactionHeight = 1.25f,
                )
            }
            repeat(state.outputDelivered) { index ->
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
        carriers.remove(key)
        removeCarried(key)
        player?.takeIf(Player::isOnline)?.let {
            port.sendActionBar(it, MessageKey.FARM_PROCESSING_RETURNED)
            port.showScreenTitle(it, MessageKey.FARM_PROCESSING_RETURNED, scope = "cargo_watchdog")
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
        port.sendActionBar(player, stageHint(runtime))
        if (!port.allowInteraction("farm-processing-title:${runtime.settings.id}:${player.uniqueId}", 8_000)) return
        val title = when (runtime.state.processing?.stage) {
            FarmProcessingStage.LOADING, null -> MessageKey.FARM_PROCESSING_LOADING_TITLE
            FarmProcessingStage.OPERATING -> MessageKey.FARM_PROCESSING_OPERATING_TITLE
            FarmProcessingStage.PACKING -> MessageKey.FARM_PROCESSING_PACKING_TITLE
        }
        port.showScreenTitle(player, title, scope = "processing_hint")
    }

    private fun pickupTitle(cargo: ProcessingCargo): MessageKey =
        if (cargo == ProcessingCargo.RAW) MessageKey.FARM_PROCESSING_RAW_PICKED_UP
        else MessageKey.FARM_PROCESSING_PRODUCT_PICKED_UP

    private fun active(runtime: FarmRuntime): Boolean =
        runtime.state.phase == FarmPhase.INCIDENT && runtime.state.incidentType == FarmIncidentType.PROCESSING

    private fun tracked(zoneId: String): Boolean = scene.hasZone(zoneId) || carriers.keys.any { it.zoneId == zoneId }

    private fun logUnavailable(runtime: FarmRuntime, reason: String) {
        if (lastUnavailableSequence[runtime.settings.id] == runtime.state.placementSequence) return
        lastUnavailableSequence[runtime.settings.id] = runtime.state.placementSequence
        port.log(
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
        val GOLD = Particle.DustOptions(Color.fromRGB(255, 178, 36), 1.25f)
        val GREEN = Particle.DustOptions(Color.fromRGB(92, 214, 116), 1.0f)
        val RED = Particle.DustOptions(Color.fromRGB(229, 75, 66), 1.05f)
        val DIAL_TRACK = Particle.DustOptions(Color.fromRGB(101, 116, 120), 0.65f)
        val GOLD_MARKER = Particle.DustOptions(Color.fromRGB(255, 196, 67), 1.45f)
        val GREEN_MARKER = Particle.DustOptions(Color.fromRGB(109, 255, 139), 1.5f)
        val PROCESSING_INPUT_POINTS = listOf(
            FarmPointKind.PROCESSING_INPUT,
            FarmPointKind.PROCESSING_INPUT_2,
            FarmPointKind.PROCESSING_INPUT_3,
            FarmPointKind.PROCESSING_INPUT_4,
        )

    }
}
