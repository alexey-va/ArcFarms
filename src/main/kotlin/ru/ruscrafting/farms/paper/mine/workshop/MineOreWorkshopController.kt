package ru.ruscrafting.farms.paper.mine.workshop

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.domain.FarmProcessingCrankState
import ru.ruscrafting.farms.domain.FarmProcessingCrankTracker
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineWorkingEngine
import ru.ruscrafting.farms.domain.MineWorkingPlacement
import ru.ruscrafting.farms.domain.MineWorkingStage
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteCarryable
import ru.ruscrafting.farms.paper.worksite.WorksiteGuidanceTarget
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import java.util.UUID
import kotlin.math.PI

/**
 * Fixed authored ore workshop. The stations are supplied by the map owner;
 * this controller never creates a room, moves a player, or changes blocks.
 *
 * Cargo is deliberately an ItemDisplay lease, just like farm processing. The
 * MineWorkingState remains the only persisted progress state, so a reload
 * drops the transient carried display and reconstructs the source station.
 */
internal class MineOreWorkshopController(
    plugin: Plugin,
    private val stationPoints: (MineRuntime) -> Map<String, Location>,
    private val incidents: MineIncidentCoordinator,
    private val access: WorksiteAccessPort,
    private val statePort: WorksiteStatePort,
    private val locale: ArcFarmsLocale? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private enum class Cargo { ORE, BILLET }

    private data class StationEntities(
        val machine: MineWorkshopMachines.Machine,
        val hitbox: Interaction,
        val material: Material,
    )

    private data class Carrier(
        val zoneId: String,
        val playerId: UUID,
        val cargo: Cargo,
        val batch: Int,
    )

    private data class CrankKey(val zoneId: String, val playerId: UUID)

    private val machines = MineWorkshopMachines(plugin)
    private val zoneKey = NamespacedKey(plugin, "mine_ore_workshop_zone")
    private val roleKey = NamespacedKey(plugin, "mine_ore_workshop_role")
    private val sequenceKey = NamespacedKey(plugin, "mine_ore_workshop_sequence")
    private val carriedKey = NamespacedKey(plugin, "mine_ore_workshop_carried")
    private val scenes = mutableMapOf<String, MutableMap<String, StationEntities>>()
    private val carriers = mutableMapOf<UUID, Carrier>()
    private val carriedDisplays = mutableMapOf<UUID, UUID>()
    private val carriedEntityRefs = mutableMapOf<UUID, ItemDisplay>()
    private val crankStates = mutableMapOf<CrankKey, FarmProcessingCrankState>()
    private val crankRadians = mutableMapOf<String, Double>()

    fun configured(runtime: MineRuntime): Boolean = points(runtime) != null

    /** Starts ORE_WORKSHOP in the authored stations using a state-only placement dummy. */
    fun start(runtime: MineRuntime, required: Int): Boolean {
        if (required <= 0 || runtime.state.incident != null) return false
        val points = points(runtime) ?: return false
        val ore = points[ORE] ?: return false
        val world = ore.world ?: return false
        val placement = MineWorkingPlacement(
            WorksitePosition(world.name, ore.blockX, ore.blockY, ore.blockZ),
            direction = 0,
            floorId = "authored-${runtime.settings.id}".take(64),
        )
        return incidents.start(
            runtime,
            MineIncidentType.ORE_WORKSHOP,
            required,
            clock(),
            working = MineWorkingEngine.initial(MineIncidentType.ORE_WORKSHOP, placement),
        )
    }

    /** Keeps authored machinery visible even while no incident is active. */
    fun reconcile(runtime: MineRuntime, participants: Collection<Player> = emptyList(), now: Long = clock()) {
        val points = points(runtime) ?: run {
            clearScene(runtime.settings.id)
            return
        }
        val scene = ensureScene(runtime, points) ?: return
        val working = activeWorking(runtime)
        updateStationState(runtime, scene, working)
        if (working?.stage == MineWorkingStage.HEAT) {
            points[FURNACE]?.let { furnace ->
                furnace.world?.spawnParticle(Particle.FLAME, furnace.clone().add(0.0, 0.8, 0.0), 2, 0.18, 0.2, 0.18, 0.01)
            }
        }
        // `participants` is intentionally accepted here so the root can use one
        // reconcile call for visuals and participant discovery. Movement work is
        // performed by tick, where the operation is rate-limited by the tracker.
        if (participants.isNotEmpty() && working?.stage == MineWorkingStage.CRUSH) {
            participants.filter { participant(runtime, it) }.forEach { player ->
                val location = points[CRUSHER] ?: return@forEach
                if (player.world === location.world && player.location.distanceSquared(location) <= CRANK_OUTER_RADIUS * CRANK_OUTER_RADIUS + 1.0) {
                    player.spawnParticle(Particle.CRIT, player.location.clone().add(0.0, 0.4, 0.0), 1, 0.05, 0.05, 0.05, 0.0)
                }
            }
        }
    }

    /** Advances carriers, heat windows and the physical crank. */
    fun tick(runtime: MineRuntime, participants: Collection<Player>, now: Long) {
        reconcile(runtime, participants, now)
        val working = activeWorking(runtime) ?: run {
            clearTransient(runtime.settings.id)
            return
        }
        val points = points(runtime) ?: return
        updateCarriers(runtime, points, working, now)
        val current = activeWorking(runtime) ?: return
        if (current.stage == MineWorkingStage.HEAT) {
            val reheated = MineWorkingEngine.reheat(current, now)
            if (reheated != current) {
                runtime.state = runtime.state.copy(incident = runtime.state.incident!!.copy(working = reheated))
                statePort.persistAsync()
            }
        }
        if (current.stage == MineWorkingStage.CRUSH) sampleCrank(runtime, participants, points[CRUSHER], now)
        animateCrusher(runtime, points[CRUSHER], participants)
    }

    fun owns(entity: Entity): Boolean = entity.persistentDataContainer.has(zoneKey, PersistentDataType.STRING) ||
        entity.persistentDataContainer.has(carriedKey, PersistentDataType.STRING)

    fun onInteract(event: PlayerInteractEvent, runtimes: Collection<MineRuntime>): Boolean {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return false
        val block = event.clickedBlock ?: return false
        val runtime = runtimes.firstOrNull { runtime ->
            val point = points(runtime)?.values?.firstOrNull { it.world === block.world && it.distanceSquared(block.location) <= 9.0 }
            point != null
        } ?: return false
        event.isCancelled = true
        val station = nearestStation(runtime, block.location) ?: return true
        interactStation(runtime, event.player, station)
        return true
    }

    fun onInteract(event: PlayerInteractEvent, runtime: MineRuntime): Boolean = onInteract(event, listOf(runtime))

    fun interact(event: PlayerInteractEvent, runtimes: Collection<MineRuntime>): Boolean = onInteract(event, runtimes)

    fun interact(event: PlayerInteractEvent, runtime: MineRuntime): Boolean = onInteract(event, runtime)

    fun onInteractEntity(event: PlayerInteractEntityEvent, runtimes: Collection<MineRuntime>): Boolean {
        val zone = event.rightClicked.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) ?: return false
        event.isCancelled = true
        if (event.hand != EquipmentSlot.HAND) return true
        val runtime = runtimes.firstOrNull { it.settings.id == zone } ?: return true
        val sequence = event.rightClicked.persistentDataContainer.get(sequenceKey, PersistentDataType.LONG)
        if (sequence != null && sequence != runtime.state.sequence) return true
        val role = event.rightClicked.persistentDataContainer.get(roleKey, PersistentDataType.STRING) ?: return true
        if (role !in STATIONS || !near(event.player, points(runtime)?.get(role))) return true
        interactStation(runtime, event.player, role)
        return true
    }

    fun onInteractEntity(event: PlayerInteractEntityEvent, runtime: MineRuntime): Boolean = onInteractEntity(event, listOf(runtime))

    fun interact(event: PlayerInteractEntityEvent, runtimes: Collection<MineRuntime>): Boolean =
        onInteractEntity(event, runtimes)

    fun interact(event: PlayerInteractEntityEvent, runtime: MineRuntime): Boolean = onInteractEntity(event, runtime)

    /** Fixed-map workshops never authorize travel or entry teleportation. */
    fun guardMovement(event: PlayerMoveEvent, runtimes: Collection<MineRuntime>): Boolean {
        carriers[event.player.uniqueId]?.let { carrier ->
            val runtime = runtimes.firstOrNull { it.settings.id == carrier.zoneId }
            if (runtime != null && !participant(runtime, event.player)) release(event.player, "outside-workshop")
        }
        return false
    }

    fun guardMovement(event: PlayerMoveEvent, runtime: MineRuntime): Boolean = guardMovement(event, listOf(runtime))

    fun releasePlayer(player: Player, reason: WorksitePlayerReleaseReason) = release(player, reason.name)

    fun release(player: Player, reason: String = "release") {
        val carrier = carriers.remove(player.uniqueId) ?: run {
            removeCarriedDisplay(player.uniqueId)
            crankStates.keys.removeIf { it.playerId == player.uniqueId }
            crankProgress.keys.removeIf { it.playerId == player.uniqueId }
            return
        }
        removeCarriedDisplay(player.uniqueId)
        crankStates.keys.removeIf { it.playerId == player.uniqueId }
        crankProgress.keys.removeIf { it.playerId == player.uniqueId }
        // The lease is transient. The persisted state has no inventory cargo to
        // restore, so returning simply makes the authored station visible again.
        if (reason.isNotBlank()) Unit

    }

    fun guidanceHint(runtime: MineRuntime, player: Player, now: Long): Component? {
        val working = activeWorking(runtime) ?: return null
        val stage = working.stage.name.lowercase()
        val path = if (working.stage == MineWorkingStage.HEAT) {
            if (MineWorkingEngine.canQuench(working, now)) "heat-ready" else "heat-wait"
        } else "hint.$stage"
        return locale?.renderPath("mine.working.$path", player, mapOf(
            "batch" to Component.text(working.batch + 1),
            "batches" to Component.text(MineWorkingEngine.BATCHES),
            "seconds" to Component.text(((working.heatStartedAt + MineWorkingEngine.HEAT_MILLIS - now).coerceAtLeast(0) + 999) / 1000),
        )) ?: Component.text("${stage.replace('_', ' ')} · batch ${working.batch + 1}/${MineWorkingEngine.BATCHES}")
    }

    fun guidanceTargets(runtime: MineRuntime, player: Player): List<WorksiteGuidanceTarget> {
        val working = activeWorking(runtime) ?: return emptyList()
        val points = points(runtime) ?: return emptyList()
        val ids = targetStations(working.stage)
        return ids.mapNotNull { id ->
            points[id]?.let { location -> WorksiteGuidanceTarget(
                "ore_workshop_$id",
                ObjectiveTargetRole("ore_workshop"),
                location.clone(),
                org.bukkit.Color.fromRGB(255, 183, 65),
            ) }
        }.take(3)
    }

    fun cleanup(reason: String = "cleanup") {
        scenes.values.flatMap { it.values }.forEach { entities ->
            entities.machine.remove()
            entities.hitbox.remove()
        }
        carriedEntityRefs.values.forEach(Entity::remove)
        carriedDisplays.values.mapNotNull(Bukkit::getEntity).forEach(Entity::remove)
        Bukkit.getWorlds().forEach { world ->
            world.loadedChunks.forEach { chunk ->
                chunk.entities.filter(::owns).forEach(Entity::remove)
            }
        }
        scenes.clear()
        machines.close()
        carriers.clear()
        carriedDisplays.clear()
        carriedEntityRefs.clear()
        crankStates.clear()
        crankProgress.clear()
        crankRadians.clear()
        if (reason.isNotBlank()) Unit
    }

    fun cleanup(runtime: MineRuntime, reason: String = "cleanup") {
        clearScene(runtime.settings.id)
        clearTransient(runtime.settings.id)
        if (reason.isNotBlank()) Unit
    }

    private fun interactStation(runtime: MineRuntime, player: Player, station: String) {
        if (!participant(runtime, player)) return
        val working = activeWorking(runtime) ?: return
        if (!access.allowInteraction("mine-ore-workshop:${runtime.settings.id}:${player.uniqueId}", 250L)) return
        when (working.stage) {
            MineWorkingStage.LOAD -> when (station) {
                ORE -> pickup(runtime, player, Cargo.ORE, working)
                CRUSHER -> deliver(runtime, player, Cargo.ORE, working)
            }
            MineWorkingStage.HEAT -> if (station == FURNACE && MineWorkingEngine.canQuench(working, clock())) {
                advance(runtime, player, target = 0, total = 1, now = clock())
            }
            MineWorkingStage.SHIP -> when (station) {
                OUTPUT -> pickup(runtime, player, Cargo.BILLET, working)
                SHIPPING -> deliver(runtime, player, Cargo.BILLET, working)
            }
            MineWorkingStage.CRUSH -> Unit // movement around the crank is authoritative
            else -> Unit
        }
    }

    private fun pickup(runtime: MineRuntime, player: Player, cargo: Cargo, working: ru.ruscrafting.farms.domain.MineWorkingState) {
        if (carriers.values.any { it.playerId == player.uniqueId }) return
        val carrier = Carrier(runtime.settings.id, player.uniqueId, cargo, working.batch)
        carriers[player.uniqueId] = carrier
        val item = ItemStack(if (cargo == Cargo.ORE) Material.RAW_IRON else Material.IRON_INGOT)
        val display = player.world.spawn(WorksiteCarryable.carriedLocation(player, CARRY_FORWARD, CARRY_Y), ItemDisplay::class.java) { entity ->
            entity.setItemStack(item)
            entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
            entity.transformation = Transformation(Vector3f(), AxisAngle4f(), Vector3f(0.7f), AxisAngle4f())
            entity.viewRange = 1.5f
            entity.teleportDuration = 1
            entity.interpolationDuration = 2
            entity.isPersistent = false
            entity.isGlowing = true
            entity.persistentDataContainer.set(carriedKey, PersistentDataType.STRING, runtime.settings.id)
        }
        carriedDisplays[player.uniqueId] = display.uniqueId
        carriedEntityRefs[player.uniqueId] = display
        updateStationState(runtime, scenes[runtime.settings.id].orEmpty(), working)
    }

    private fun deliver(runtime: MineRuntime, player: Player, cargo: Cargo, working: ru.ruscrafting.farms.domain.MineWorkingState) {
        val carrier = carriers[player.uniqueId] ?: return
        if (carrier.zoneId != runtime.settings.id || carrier.cargo != cargo || carrier.batch != working.batch) return
        val target = if (cargo == Cargo.ORE) CRUSHER else SHIPPING
        val targetLocation = points(runtime)?.get(target) ?: return
        if (!atDeliveryPoint(player, targetLocation)) return
        if (advance(runtime, player, 0, 1, clock())) {
            carriers.remove(player.uniqueId)
            removeCarriedDisplay(player.uniqueId)

        }
    }

    private fun updateCarriers(runtime: MineRuntime, points: Map<String, Location>, working: ru.ruscrafting.farms.domain.MineWorkingState, now: Long) {
        carriers.values.filter { it.zoneId == runtime.settings.id }.toList().forEach { carrier ->
            val player = Bukkit.getPlayer(carrier.playerId)
            val target = if (carrier.cargo == Cargo.ORE) CRUSHER else SHIPPING
            val targetLocation = points[target]
            if (player == null || !player.isOnline || targetLocation == null || !participant(runtime, player)) {
                player?.let { release(it, "carrier-unavailable") }
                return@forEach
            }
            val display = carriedEntityRefs[carrier.playerId]
                ?.takeIf(ItemDisplay::isValid)
                ?: carriedDisplays[carrier.playerId]?.let(Bukkit::getEntity) as? ItemDisplay
            if (display == null || !display.isValid) {
                carriers.remove(carrier.playerId)
                return@forEach
            }
            if (display.world === player.world) display.teleport(WorksiteCarryable.carriedLocation(player, CARRY_FORWARD, CARRY_Y))
            if (atDeliveryPoint(player, targetLocation)) deliver(runtime, player, carrier.cargo, working)
        }
    }

    private fun sampleCrank(runtime: MineRuntime, participants: Collection<Player>, crusher: Location?, now: Long) {
        val center = crusher ?: return
        val working = activeWorking(runtime) ?: return
        participants.filter { participant(runtime, it) }.forEach { player ->
            if (player.world !== center.world || kotlin.math.abs(player.location.y - center.y) > 1.5) return@forEach
            val key = CrankKey(runtime.settings.id, player.uniqueId)
            val sample = FarmProcessingCrankTracker.sample(
                crankStates[key], player.location.x, player.location.z,
                center.x, center.z, CRANK_INNER_RADIUS, CRANK_OUTER_RADIUS,
                radiusTolerance = 0.35, maxStepDistance = 2.0,
            )
            if (sample.state == null) crankStates.remove(key) else crankStates[key] = sample.state
            if (sample.acceptedRadians <= 0.0) return@forEach
            crankRadians[runtime.settings.id] = (crankRadians[runtime.settings.id] ?: 0.0) + sample.acceptedRadians
            val accumulated = crankProgress.getOrPut(key) { 0.0 } + sample.acceptedRadians
            if (accumulated + 1e-6 >= FarmProcessingCrankTracker.FULL_LAP_RADIANS) {
                if (advance(runtime, player, working.completed.size, MineWorkingEngine.CRUSH_STROKES, now) &&
                    activeWorking(runtime)?.stage == MineWorkingStage.CRUSH) {
                    crankProgress[key] = (accumulated - FarmProcessingCrankTracker.FULL_LAP_RADIANS).coerceAtLeast(0.0)
                }
            } else crankProgress[key] = accumulated
        }
    }

    private val crankProgress = mutableMapOf<CrankKey, Double>()

    private fun animateCrusher(runtime: MineRuntime, crusher: Location?, participants: Collection<Player>) {
        val machine = scenes[runtime.settings.id]?.get(CRUSHER)?.machine ?: return
        val active = activeWorking(runtime)?.stage == MineWorkingStage.CRUSH
        machine.turn(((crankRadians[runtime.settings.id] ?: 0.0) % (2 * PI)).toFloat())
        machine.highlight(active)
        if (active && participants.any { participant(runtime, it) }) {
            crusher?.world?.spawnParticle(Particle.CRIT, crusher.clone().add(0.0, 0.55, 0.0), 2, 0.2, 0.15, 0.2, 0.01)
        }
    }

    private fun advance(runtime: MineRuntime, player: Player, target: Int, total: Int, now: Long): Boolean {
        val incident = runtime.state.incident ?: return false
        val working = incident.working ?: return false
        val step = MineWorkingEngine.completeTarget(working, target, total, now)
        if (!step.accepted) return false
        val next = runtime.state.copy(incident = incident.copy(working = step.state))
        val result = incidents.work(runtime, player, state = next)
        if (!result.accepted) return false
        scenes[runtime.settings.id]?.let { scene ->
            updateStationState(runtime, scene, activeWorking(runtime))
        }
        if (step.finished || step.state.stage != working.stage) {
            crankStates.keys.removeIf { it.zoneId == runtime.settings.id }
            crankProgress.keys.removeIf { it.zoneId == runtime.settings.id }
            if (step.state.stage == MineWorkingStage.CRUSH) crankRadians[runtime.settings.id] = 0.0
        }
        return true
    }

    private fun activeWorking(runtime: MineRuntime) = runtime.state.incident?.takeIf {
        runtime.state.phase == MinePhase.INCIDENT && it.type == MineIncidentType.ORE_WORKSHOP
    }?.working

    private fun participant(runtime: MineRuntime, player: Player): Boolean =
        player.isOnline && !player.isDead && player.gameMode != org.bukkit.GameMode.SPECTATOR && player.world === runtime.region.world &&
            runtime.region.contains(player.location) && !access.isAdminEditing(player) &&
            access.hasAccess(player, runtime.settings.permission)

    private fun points(runtime: MineRuntime): Map<String, Location>? {
        val values = stationPoints(runtime)
        if (!STATIONS.all { id -> values[id]?.let { it.world === runtime.region.world && runtime.region.contains(it) } == true }) return null
        if (values.values.maxOf { it.y } - values.values.minOf { it.y } > 2.0) return null
        return values
    }

    private fun nearestStation(runtime: MineRuntime, location: Location): String? =
        points(runtime)?.entries?.filter { (_, point) -> point.world === location.world }?.minByOrNull { (_, point) -> point.distanceSquared(location) }
            ?.takeIf { it.value.distanceSquared(location) <= 9.0 }?.key

    private fun atDeliveryPoint(player: Player, point: Location): Boolean =
        point.world === player.world && point.distanceSquared(player.location) <= DELIVERY_DISTANCE_SQUARED

    private fun near(player: Player, point: Location?): Boolean = point != null && point.world === player.world &&
        point.distanceSquared(player.location) <= INTERACTION_DISTANCE_SQUARED

    private fun ensureScene(runtime: MineRuntime, points: Map<String, Location>): MutableMap<String, StationEntities>? {
        val zone = runtime.settings.id
        val current = scenes.getOrPut(zone) { linkedMapOf() }
        STATIONS.forEach { id ->
            val point = points[id] ?: return@forEach
            val existing = current[id]
            if (existing != null && (!existing.machine.body.isValid || !existing.hitbox.isValid || existing.machine.body.location.world !== point.world || existing.hitbox.location.distanceSquared(point.clone().add(0.0, 0.35, 0.0)) > 0.001)) {
                existing.machine.remove(); existing.hitbox.remove(); current.remove(id)
            }
            if (id !in current) {
                val machine = machines.create(id, point)
                val hitbox = point.world!!.spawn(point.clone().add(0.0, 0.35, 0.0), Interaction::class.java) { entity ->
                    entity.interactionWidth = 2.4f
                    entity.interactionHeight = 2.0f
                    entity.isResponsive = false
                    entity.isPersistent = false
                    entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, zone)
                    entity.persistentDataContainer.set(roleKey, PersistentDataType.STRING, id)
                    entity.persistentDataContainer.set(sequenceKey, PersistentDataType.LONG, runtime.state.sequence)
                }
                current[id] = StationEntities(machine, hitbox, STATION_MATERIALS.getValue(id))
            }
        }
        return current
    }

    private fun updateStationState(runtime: MineRuntime, scene: Map<String, StationEntities>, working: ru.ruscrafting.farms.domain.MineWorkingState?) {
        val activeTargets = working?.let { targetStations(it.stage).toSet() }.orEmpty()
        scene.forEach { (id, entities) ->
            val glowing = id in activeTargets
            entities.machine.highlight(glowing)
            entities.hitbox.isResponsive = glowing
            entities.hitbox.persistentDataContainer.set(sequenceKey, PersistentDataType.LONG, runtime.state.sequence)
        }
    }

    private fun clearTransient(zoneId: String) {
        carriers.filterValues { it.zoneId == zoneId }.keys.toList().forEach { playerId ->
            removeCarriedDisplay(playerId)
            carriers.remove(playerId)
        }
        crankStates.keys.removeIf { it.zoneId == zoneId }
        crankProgress.keys.removeIf { it.zoneId == zoneId }
        crankRadians.remove(zoneId)
    }

    private fun clearScene(zoneId: String) {
        scenes.remove(zoneId)?.values?.forEach { entities -> entities.machine.remove(); entities.hitbox.remove() }
        clearTransient(zoneId)
    }

    private fun removeCarriedDisplay(playerId: UUID) {
        carriedEntityRefs.remove(playerId)?.remove()
        carriedDisplays.remove(playerId)?.let(Bukkit::getEntity)?.remove()
    }

    private fun targetStations(stage: MineWorkingStage): List<String> = when (stage) {
        MineWorkingStage.LOAD -> listOf(ORE, CRUSHER)
        MineWorkingStage.CRUSH -> listOf(CRUSHER)
        MineWorkingStage.HEAT -> listOf(FURNACE)
        MineWorkingStage.SHIP -> listOf(OUTPUT, SHIPPING)
        else -> emptyList()
    }

    private companion object {
        const val ORE = "ore"
        const val CRUSHER = "crusher"
        const val FURNACE = "furnace"
        const val OUTPUT = "output"
        const val SHIPPING = "shipping"
        val STATIONS = listOf(ORE, CRUSHER, FURNACE, OUTPUT, SHIPPING)
        val STATION_MATERIALS = mapOf(
            ORE to Material.RAW_IRON,
            CRUSHER to Material.GRINDSTONE,
            FURNACE to Material.BLAST_FURNACE,
            OUTPUT to Material.IRON_INGOT,
            SHIPPING to Material.HOPPER,
        )
        const val INTERACTION_DISTANCE_SQUARED = 25.0
        const val DELIVERY_DISTANCE_SQUARED = 3.0625
        const val CARRY_FORWARD = 0.75
        const val CARRY_Y = 1.0
        const val CRANK_INNER_RADIUS = 1.2
        const val CRANK_OUTER_RADIUS = 3.2
    }
}
