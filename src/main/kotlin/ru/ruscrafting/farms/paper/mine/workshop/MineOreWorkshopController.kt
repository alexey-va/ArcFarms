package ru.ruscrafting.farms.paper.mine.workshop

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
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
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineWorkingEngine
import ru.ruscrafting.farms.domain.MineWorkingPlacement
import ru.ruscrafting.farms.domain.MineWorkingState
import ru.ruscrafting.farms.domain.MineWorkshopHeat
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

/**
 * Fixed authored ore workshop. The stations are supplied by the map owner;
 * this controller never creates a room, moves a player, or changes blocks.
 *
 * Cargo is deliberately an ItemDisplay lease, just like farm processing. The
 * MineWorkingState remains the only persisted progress state, so a reload
 * drops the transient carried display and reconstructs the source station.
 */
internal class MineOreWorkshopController(
    private val plugin: Plugin,
    private val stationPoints: (MineRuntime) -> Map<String, Location>,
    private val incidents: MineIncidentCoordinator,
    private val access: WorksiteAccessPort,
    statePort: WorksiteStatePort,
    private val locale: ArcFarmsLocale? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private data class StationEntities(
        val machine: MineWorkshopMachines.Machine,
        val hitbox: Interaction,
        val controls: Map<String, Interaction>,
    )

    private data class Carrier(
        val zoneId: String,
        val playerId: UUID,
        val batch: Int,
        val nonce: Long,
    )

    private val machines = MineWorkshopMachines(plugin)
    private val zoneKey = NamespacedKey(plugin, "mine_ore_workshop_zone")
    private val roleKey = NamespacedKey(plugin, "mine_ore_workshop_role")
    private val sequenceKey = NamespacedKey(plugin, "mine_ore_workshop_sequence")
    private val carriedKey = NamespacedKey(plugin, "mine_ore_workshop_carried")
    private val scenes = mutableMapOf<String, MutableMap<String, StationEntities>>()
    private val carriers = mutableMapOf<UUID, Carrier>()
    private val carriedDisplays = mutableMapOf<UUID, UUID>()
    private val carriedEntityRefs = mutableMapOf<UUID, ItemDisplay>()
    private data class Process(
        val sequence: Long,
        val nonce: Long,
        val batch: Int,
        val stage: MineWorkingStage,
        var started: Long,
        var lastTick: Long,
        var heat: MineWorkshopHeat = MineWorkshopHeat(),
        var operator: UUID? = null,
        var lastSound: Long = 0L,
        var heatSignal: Boolean = false,
    )
    private val processes = mutableMapOf<String, Process>()

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

    /** Fixed packet machinery is visible between incidents; only current actions glow. */
    fun reconcile(runtime: MineRuntime, participants: Collection<Player> = emptyList(), now: Long = clock()) {
        val points = points(runtime) ?: run { clearScene(runtime.settings.id); return }
        val scene = ensureScene(runtime, points) ?: return
        val working = activeWorking(runtime)
        val process = working?.let { process(runtime, it, now) }
        updateStationState(runtime, scene, working)
        render(runtime, scene, working, process, now)
    }

    fun tick(runtime: MineRuntime, participants: Collection<Player>, now: Long) {
        reconcile(runtime, participants, now)
        val working = activeWorking(runtime) ?: run { clearTransient(runtime.settings.id); return }
        updateCarriers(runtime, working)
        val process = process(runtime, working, now)
        val elapsed = (now - process.lastTick).coerceAtLeast(0L)
        process.lastTick = now
        val operator = process.operator?.let(Bukkit::getPlayer)?.takeIf { participant(runtime, it) }
            ?: participants.firstOrNull { participant(runtime, it) && near(it, points(runtime)?.get(FURNACE), 225.0) }
        if (operator != null) {
            when (working.stage) {
                MineWorkingStage.CRUSH -> if (0 in working.completed) {
                    val target = if (1 in working.completed) 2 else 1
                    val duration = if (target == 1) CRUSH_MILLIS else TRANSFER_MILLIS
                    if (now - process.started >= duration && advance(runtime, operator, target, 3, now)) {
                        process.started = now
                    }
                }
                MineWorkingStage.HEAT -> {
                    process.heat = process.heat.tick(elapsed)
                    if (process.heat.ready && !process.heatSignal) {
                        process.heatSignal = true
                        pulse(runtime, TAP, now, Sound.BLOCK_NOTE_BLOCK_PLING)
                    }
                }
                else -> Unit
            }
        }
        val current = activeWorking(runtime)
        val live = current?.let { process(runtime, it, now) }
        scenes[runtime.settings.id]?.let { scene ->
            updateStationState(runtime, scene, current)
            render(runtime, scene, current, live, now)
        }
        effects(runtime, current, live, now)
    }

    fun owns(entity: Entity): Boolean = entity.persistentDataContainer.has(zoneKey, PersistentDataType.STRING) ||
        entity.persistentDataContainer.has(carriedKey, PersistentDataType.STRING)

    // Workshop actions belong to visible hitboxes, never arbitrary floor blocks near a machine.
    fun onInteract(event: PlayerInteractEvent, runtimes: Collection<MineRuntime>): Boolean = false

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
        if (role !in ALL_INTERACTION_ROLES || !near(event.player, interactionPoint(runtime, role))) return true
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
            if (runtime != null && (!participant(runtime, event.player) || !insideWorkshop(runtime, event.player))) release(event.player, "outside-workshop")
        }
        return false
    }

    fun guardMovement(event: PlayerMoveEvent, runtime: MineRuntime): Boolean = guardMovement(event, listOf(runtime))

    fun releasePlayer(player: Player, reason: WorksitePlayerReleaseReason) = release(player, reason.name)

    fun release(player: Player, reason: String = "release") {
        carriers.remove(player.uniqueId) ?: run {
            removeCarriedDisplay(player.uniqueId)
            return
        }
        removeCarriedDisplay(player.uniqueId)
        if (reason.isNotBlank()) Unit
    }

    fun guidanceHint(runtime: MineRuntime, player: Player, now: Long): Component? = hint(runtime, player, now)

    private fun hint(runtime: MineRuntime, player: Player?, now: Long): Component? {
        val working = activeWorking(runtime) ?: return null
        val process = process(runtime, working, now)
        val key = when (working.stage) {
            MineWorkingStage.LOAD -> if (carriers[player?.uniqueId]?.batch == working.batch) "load-deliver" else "load-pickup"
            MineWorkingStage.CRUSH -> when {
                1 in working.completed -> "conveying"
                0 in working.completed -> "crushing"
                else -> "drive"
            }
            MineWorkingStage.HEAT -> when {
                process.heat.ready -> "heat-ready"
                process.heat.running -> "heat-progress"
                else -> "heat-start"
            }
            MineWorkingStage.SHIP -> if (now - process.started < POUR_MILLIS) "pouring" else "collect"
            else -> return null
        }
        val elapsed = (now - process.started).coerceAtLeast(0L)
        val progress = when (working.stage) {
            MineWorkingStage.CRUSH -> if (working.completed.isEmpty()) 0.0 else
                elapsed.toDouble() / if (1 in working.completed) TRANSFER_MILLIS else CRUSH_MILLIS
            MineWorkingStage.SHIP -> elapsed.toDouble() / POUR_MILLIS
            else -> process.heat.progress
        }.coerceIn(0.0, 1.0)
        return locale?.renderPath("mine.working.workshop.$key", player, mapOf(
            "batch" to Component.text(working.batch + 1),
            "batches" to Component.text(MineWorkingEngine.BATCHES),
            "temperature" to Component.text((process.heat.progress * 100).toInt()),
            "progress" to Component.text((progress * 100).toInt()),
        )) ?: Component.text(key)
    }

    fun guidanceTargets(runtime: MineRuntime, player: Player): List<WorksiteGuidanceTarget> {
        val working = activeWorking(runtime) ?: return emptyList()
        val process = process(runtime, working, clock())
        val target = actionRole(working, process, clock(), player.uniqueId) ?: return emptyList()
        val location = interactionPoint(runtime, target) ?: return emptyList()
        return listOf(WorksiteGuidanceTarget(
            "ore_workshop_$target", ObjectiveTargetRole("ore_workshop"), location,
            if (process.heat.ready) org.bukkit.Color.fromRGB(85, 217, 139)
            else org.bukkit.Color.fromRGB(255, 183, 65),
        ))
    }

    fun cleanup(reason: String = "cleanup") {
        scenes.values.flatMap { it.values }.forEach { entities ->
            removeStationEntities(entities)
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
        processes.clear()
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
        val now = clock()
        val process = process(runtime, working, now)
        when (working.stage) {
            MineWorkingStage.LOAD -> when (station) {
                ORE -> pickup(runtime, player, working)
                FEED -> deliver(runtime, player, working)
            }
            MineWorkingStage.CRUSH -> if (station == DRIVE && working.completed.isEmpty()) {
                if (advance(runtime, player, 0, 3, now)) {
                    process.started = now
                    process.operator = player.uniqueId
                    pulse(runtime, station, now, Sound.BLOCK_GRINDSTONE_USE)
                }
            }
            MineWorkingStage.HEAT -> when {
                station == AIR && !process.heat.ready -> {
                    val wasRunning = process.heat.running
                    process.heat = process.heat.start()
                    if (!wasRunning) {
                        process.operator = player.uniqueId
                        process.lastTick = now
                        pulse(runtime, station, now, Sound.BLOCK_IRON_TRAPDOOR_OPEN)
                    }
                }
                station == TAP && process.heat.ready -> if (advance(runtime, player, 0, 1, now, process.heat)) {
                    pulse(runtime, station, now, Sound.BLOCK_LAVA_EXTINGUISH)
                }
            }
            MineWorkingStage.SHIP -> if (station == OUTPUT && now - process.started >= POUR_MILLIS) {
                if (advance(runtime, player, 0, 1, now)) pulse(runtime, OUTPUT, now, Sound.BLOCK_ANVIL_USE)
            }
            else -> Unit
        }
        guidanceHint(runtime, player, now)?.let(player::sendActionBar)
        scenes[runtime.settings.id]?.let { updateStationState(runtime, it, activeWorking(runtime)) }
    }

    private fun pulse(runtime: MineRuntime, role: String, now: Long, sound: Sound) {
        scenes[runtime.settings.id]?.values?.forEach { it.machine.pulse(role, now) }
        val at = interactionPoint(runtime, role) ?: return
        if (soundsEnabled()) at.world?.playSound(at, sound, .65f, .85f)
    }

    private fun soundsEnabled(): Boolean = plugin.config.getBoolean("ui.sounds", true)

    private fun particlesEnabled(): Boolean = plugin.config.getBoolean("ui.particles", true)

    private fun pickup(runtime: MineRuntime, player: Player, working: MineWorkingState) {
        if (carriers.values.any { it.playerId == player.uniqueId }) return
        val carrier = Carrier(runtime.settings.id, player.uniqueId, working.batch, runtime.state.incident!!.objectiveNonce)
        carriers[player.uniqueId] = carrier
        val item = ItemStack(Material.RAW_IRON)
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

    private fun deliver(runtime: MineRuntime, player: Player, working: MineWorkingState) {
        val carrier = carriers[player.uniqueId] ?: return
        if (carrier.zoneId != runtime.settings.id || carrier.batch != working.batch || carrier.nonce != runtime.state.incident?.objectiveNonce) return
        if (!near(player, interactionPoint(runtime, FEED))) return
        if (advance(runtime, player, 0, 1, clock())) {
            carriers.remove(player.uniqueId)
            removeCarriedDisplay(player.uniqueId)
            pulse(runtime, FEED, clock(), Sound.BLOCK_GRAVEL_PLACE)
        }
    }

    private fun updateCarriers(runtime: MineRuntime, working: MineWorkingState) {
        carriers.values.filter { it.zoneId == runtime.settings.id }.toList().forEach { carrier ->
            val player = Bukkit.getPlayer(carrier.playerId)
            val display = carriedEntityRefs[carrier.playerId]
            if (player == null || !participant(runtime, player) || !insideWorkshop(runtime, player) || working.stage != MineWorkingStage.LOAD ||
                carrier.batch != working.batch || carrier.nonce != runtime.state.incident?.objectiveNonce || display == null || !display.isValid) {
                carriers.remove(carrier.playerId)
                removeCarriedDisplay(carrier.playerId)
            } else display.teleport(WorksiteCarryable.carriedLocation(player, CARRY_FORWARD, CARRY_Y))
        }
    }

    private fun process(runtime: MineRuntime, working: MineWorkingState, now: Long): Process {
        val old = processes[runtime.settings.id]
        if (old?.sequence == runtime.state.sequence && old.nonce == runtime.state.incident?.objectiveNonce && old.batch == working.batch && old.stage == working.stage) return old
        return Process(runtime.state.sequence, runtime.state.incident!!.objectiveNonce, working.batch, working.stage, now, now,
            operator = old?.takeIf { it.nonce == runtime.state.incident?.objectiveNonce }?.operator)
            .also { processes[runtime.settings.id] = it }
    }

    private fun actionRole(working: MineWorkingState, process: Process, now: Long, player: UUID? = null): String? = when (working.stage) {
        MineWorkingStage.LOAD -> if (player != null && carriers[player]?.batch != working.batch) ORE else FEED
        MineWorkingStage.CRUSH -> if (working.completed.isEmpty()) DRIVE else null
        MineWorkingStage.HEAT -> when {
            process.heat.ready -> TAP
            process.heat.running -> null
            else -> AIR
        }
        MineWorkingStage.SHIP -> if (now - process.started >= POUR_MILLIS) OUTPUT else null
        else -> null
    }

    private fun render(runtime: MineRuntime, scene: Map<String, StationEntities>, working: MineWorkingState?, process: Process?, now: Long) {
        val stage = working?.stage
        val crushing = stage == MineWorkingStage.CRUSH && 0 in working.completed
        val elapsed = process?.let { (now - it.started).coerceAtLeast(0L) } ?: 0L
        val visual = MineWorkshopVisualState(
            running = crushing || stage == MineWorkingStage.HEAT || stage == MineWorkingStage.SHIP,
            crushing = crushing && 1 !in working.completed,
            transfer = if (crushing && 1 in working.completed) (elapsed.toFloat() / TRANSFER_MILLIS).coerceIn(0f, 1f) else -1f,
            temperature = if (stage == MineWorkingStage.HEAT) process!!.heat.progress.toFloat() else 0f,
            airOpen = process?.heat?.running ?: false,
            heatReady = stage == MineWorkingStage.HEAT && process!!.heat.ready,
            pouring = if (stage == MineWorkingStage.SHIP) (elapsed.toFloat() / POUR_MILLIS).coerceIn(0f, 1f) else -1f,
        )
        scene.forEach { (role, entities) ->
            entities.machine.renderProcess(visual, now)
            val caption = when {
                role == CRUSHER && stage == MineWorkingStage.LOAD -> locale?.renderPath("mine.working.workshop.load-deliver")
                role == ORE && stage == MineWorkingStage.LOAD -> hint(runtime, null, now)
                role == CRUSHER && stage == MineWorkingStage.CRUSH -> hint(runtime, null, now)
                role == FURNACE && stage == MineWorkingStage.HEAT -> hint(runtime, null, now)
                role == OUTPUT && stage == MineWorkingStage.SHIP -> hint(runtime, null, now)
                else -> null
            }
            entities.machine.caption(caption, working?.let { actionRole(it, process!!, now) }
                ?.takeIf { it in entities.controls })
        }
    }

    private fun effects(runtime: MineRuntime, working: MineWorkingState?, process: Process?, now: Long) {
        if (working == null || process == null || now - process.lastSound < 700L) return
        process.lastSound = now
        val role = when (working.stage) {
            MineWorkingStage.CRUSH -> if (working.completed.isEmpty()) return else CRUSHER
            MineWorkingStage.HEAT -> FURNACE
            MineWorkingStage.SHIP -> if (now - process.started >= POUR_MILLIS) return else OUTPUT
            else -> return
        }
        val at = points(runtime)?.get(role)?.clone()?.add(0.0, 1.0, 0.0) ?: return
        if (soundsEnabled()) at.world?.playSound(at, when (role) {
            CRUSHER -> Sound.BLOCK_GRINDSTONE_USE
            FURNACE -> Sound.BLOCK_FURNACE_FIRE_CRACKLE
            else -> Sound.BLOCK_FIRE_EXTINGUISH
        }, .35f, if (role == FURNACE) (.65 + process.heat.progress * .35).toFloat() else .75f)
        if (particlesEnabled()) at.world?.spawnParticle(when (role) {
            CRUSHER -> Particle.CRIT
            FURNACE -> if (process.heat.ready) Particle.HAPPY_VILLAGER else Particle.FLAME
            else -> Particle.CLOUD
        }, at, 4, .25, .2, .25, .01)
    }

    private fun advance(runtime: MineRuntime, player: Player, target: Int, total: Int, now: Long, heat: MineWorkshopHeat? = null): Boolean {
        val incident = runtime.state.incident ?: return false
        val working = incident.working ?: return false
        val step = if (heat != null) MineWorkingEngine.completeWorkshopHeat(working, heat, now)
            else MineWorkingEngine.completeTarget(working, target, total, now)
        if (!step.accepted) return false
        val next = runtime.state.copy(incident = incident.copy(working = step.state))
        val result = incidents.work(runtime, player, state = next)
        if (!result.accepted) return false
        scenes[runtime.settings.id]?.let { scene ->
            if (step.finished || step.state.stage != working.stage) {
                scene[CRUSHER]?.machine?.reset()
            }
            updateStationState(runtime, scene, activeWorking(runtime))
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

    private fun insideWorkshop(runtime: MineRuntime, player: Player): Boolean = points(runtime)?.values?.any {
        it.world === player.world && kotlin.math.abs(it.y - player.location.y) <= 3.0 &&
            it.distanceSquared(player.location) <= 36.0
    } == true

    private fun points(runtime: MineRuntime): Map<String, Location>? {
        val values = stationPoints(runtime)
        if (!STATIONS.all { id -> values[id]?.let { it.world === runtime.region.world && runtime.region.contains(it) } == true }) return null
        if (values.values.maxOf { it.y } - values.values.minOf { it.y } > 2.0) return null
        return values
    }

    private fun near(player: Player, point: Location?, distanceSquared: Double = INTERACTION_DISTANCE_SQUARED): Boolean =
        point != null && point.world === player.world && point.distanceSquared(player.location) <= distanceSquared

    private fun interactionPoint(runtime: MineRuntime, role: String): Location? =
        scenes[runtime.settings.id]?.values?.firstNotNullOfOrNull { it.machine.controls[role] }
            ?: scenes[runtime.settings.id]?.get(role)?.machine?.interactionCenter
            ?: points(runtime)?.get(role)?.clone()?.add(0.0, .7, 0.0)

    private fun ensureScene(runtime: MineRuntime, points: Map<String, Location>): MutableMap<String, StationEntities>? {
        val zone = runtime.settings.id
        val current = scenes.getOrPut(zone) { linkedMapOf() }
        STATIONS.forEach { id ->
            val point = points[id] ?: return@forEach
            val existing = current[id]
            val controlsValid = existing?.controls?.all { (role, hitbox) ->
                hitbox.isValid && existing.machine.controls[role]?.let { expected ->
                    sameLocation(hitbox.location, hitboxBase(expected, CONTROL_HITBOX_HEIGHT))
                } == true
            } ?: false
            if (existing != null && (!existing.machine.body.isValid || !existing.hitbox.isValid || !controlsValid ||
                existing.machine.body.location.world !== point.world ||
                    existing.hitbox.location.distanceSquared(hitboxBase(existing.machine.interactionCenter ?: point.clone().add(0.0, 0.7, 0.0), STATION_HITBOX_HEIGHT)) > 0.001)) {
                removeStationEntities(existing)
                current.remove(id)
            }
            if (id !in current) {
                val machine = machines.create(id, point)
                val hitbox = spawnHitbox(runtime, id, machine.interactionCenter ?: point.clone().add(0.0, 0.7, 0.0), 1.25f, STATION_HITBOX_HEIGHT)
                val controls = machine.controls.mapValues { (role, controlPoint) ->
                    spawnHitbox(runtime, role, controlPoint, .72f, CONTROL_HITBOX_HEIGHT)
                }
                current[id] = StationEntities(machine, hitbox, controls)
            }
        }
        return current
    }

    private fun updateStationState(runtime: MineRuntime, scene: Map<String, StationEntities>, working: MineWorkingState?) {
        val now = clock()
        val process = working?.let { process(runtime, it, now) }
        val action = if (working != null && process != null) actionRole(working, process, now) else null
        scene.forEach { (id, entities) ->
            val source = id == ORE && working?.stage == MineWorkingStage.LOAD
            val active = source || id == action || (action != null && action in entities.controls)
            entities.machine.highlight(active, if (action != null && action in entities.controls) action else null)
            val stationActive = source || id == action
            // Responsive only controls hit feedback; zero size removes an inactive ray obstruction.
            entities.hitbox.interactionWidth = if (stationActive) 1.25f else 0f
            entities.hitbox.interactionHeight = if (stationActive) STATION_HITBOX_HEIGHT else 0f
            entities.hitbox.isResponsive = stationActive
            entities.hitbox.persistentDataContainer.set(sequenceKey, PersistentDataType.LONG, runtime.state.sequence)
            entities.controls.forEach { (role, control) ->
                val selected = role == action
                control.interactionWidth = if (selected) .85f else 0f
                control.interactionHeight = if (selected) CONTROL_HITBOX_HEIGHT else 0f
                control.isResponsive = selected
                control.persistentDataContainer.set(sequenceKey, PersistentDataType.LONG, runtime.state.sequence)
            }
        }
    }

    private fun spawnHitbox(runtime: MineRuntime, role: String, location: Location, width: Float, height: Float): Interaction =
        location.world!!.spawn(hitboxBase(location, height), Interaction::class.java) { entity ->
            entity.interactionWidth = width
            entity.interactionHeight = height
            entity.isResponsive = false
            entity.isPersistent = false
            entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, runtime.settings.id)
            entity.persistentDataContainer.set(roleKey, PersistentDataType.STRING, role)
            entity.persistentDataContainer.set(sequenceKey, PersistentDataType.LONG, runtime.state.sequence)
        }

    private fun hitboxBase(center: Location, height: Float): Location = center.clone().subtract(0.0, height / 2.0, 0.0)

    private fun removeStationEntities(entities: StationEntities) {
        entities.machine.remove()
        entities.hitbox.remove()
        entities.controls.values.forEach(Interaction::remove)
    }

    private fun sameLocation(left: Location, right: Location): Boolean =
        left.world === right.world && left.distanceSquared(right) <= .001

    private fun clearTransient(zoneId: String) {
        carriers.filterValues { it.zoneId == zoneId }.keys.toList().forEach { playerId ->
            removeCarriedDisplay(playerId)
            carriers.remove(playerId)
        }
        scenes[zoneId]?.values?.forEach { it.machine.reset() }
        processes.remove(zoneId)
    }

    private fun clearScene(zoneId: String) {
        scenes.remove(zoneId)?.values?.forEach(::removeStationEntities)
        clearTransient(zoneId)
    }

    private fun removeCarriedDisplay(playerId: UUID) {
        carriedEntityRefs.remove(playerId)?.remove()
        carriedDisplays.remove(playerId)?.let(Bukkit::getEntity)?.remove()
    }

    private companion object {
        const val ORE = "ore"
        const val CRUSHER = "crusher"
        const val FURNACE = "furnace"
        const val OUTPUT = "output"
        const val SHIPPING = "shipping"
        val STATIONS = listOf(ORE, CRUSHER, FURNACE, OUTPUT, SHIPPING)
        const val FEED = "crusher_feed"
        const val DRIVE = "crusher_drive"
        const val AIR = "furnace_air"
        const val TAP = "furnace_tap"
        val ALL_INTERACTION_ROLES = STATIONS + listOf(FEED, DRIVE, AIR, TAP)
        const val INTERACTION_DISTANCE_SQUARED = 25.0
        const val STATION_HITBOX_HEIGHT = 1.4f
        const val CONTROL_HITBOX_HEIGHT = .85f
        const val CARRY_FORWARD = 0.75
        const val CARRY_Y = 1.0
        const val CRUSH_MILLIS = 4_000L
        const val TRANSFER_MILLIS = 3_000L
        const val POUR_MILLIS = 5_000L
    }
}
