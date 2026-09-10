package ru.ruscrafting.farms.paper.mine.incident.scenario

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.domain.*
import ru.ruscrafting.farms.domain.worksite.*
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator
import ru.ruscrafting.farms.paper.mine.lift.MineLiftAccess
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import java.util.UUID

/** Owns mine-specific verbs; interruption, contribution and target leasing remain shared. */
internal class MineScenarioController(
    private val registry: MineRuntimeRegistry,
    val rooms: MineScenarioRooms,
    private val incidents: MineIncidentCoordinator,
    private val access: WorksiteAccessPort,
    private val state: WorksiteStatePort,
    private val locale: ArcFarmsLocale?,
    private val lift: MineLiftAccess?,
    private val actors: ru.ruscrafting.farms.paper.mine.incident.entity.MineScenarioActors,
    private val carts: ru.ruscrafting.farms.paper.mine.extraction.MineCartEffects,
    private val audience: ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort,
    private val environment: MineScenarioEnvironment,
    private val cargo: MineScenarioCargo,
) {
    private val activeScenes = mutableMapOf<String, Long>()
    private val workingSince = mutableMapOf<String, Pair<UUID, Long>>()

    fun supports(type: MineIncidentType): Boolean = MineScenarioCatalog.definition(type.name.lowercase()) != null

    fun start(runtime: MineRuntime, type: MineIncidentType, now: Long): Boolean {
        val definition = MineScenarioCatalog.definition(type.name.lowercase()) ?: return false
        val floors = lift?.floors().orEmpty().filter { it.exit.world === runtime.region.world }
        val bounds = runtime.region.bounds
        val participants = runtime.region.world.players.filter { registry.forAudience(it.location) === runtime && eligible(it, runtime) }
        val anchor = participants.minByOrNull { it.uniqueId.toString() }?.location
        val floor = floors.minByOrNull { kotlin.math.abs(it.y - (anchor?.y ?: (bounds.minY + 1).toDouble())) }
        val other = floors.filter { it.id != floor?.id }.minByOrNull { kotlin.math.abs(it.y - (floor?.y ?: 0.0)) }
        if (definition.scope == MineScenarioScope.MULTI_FLOOR && other == null) return false
        val active = registry.snapshot().mapNotNull { it.state.incident?.takeIf { incident -> incident.scenarioPlacement != null } }
        if (active.any { it.type == MineIncidentType.LIFT_BREAKDOWN } ||
            (type == MineIncidentType.LIFT_BREAKDOWN && active.any { MineScenarioCatalog.definition(it.type.name.lowercase())?.scope == MineScenarioScope.MULTI_FLOOR })) return false
        val entry = floor?.exit ?: anchor ?: return false
        if (!safe(entry)) return false
        val entryPosition = entry.position()
        if (active.any { incident ->
                val occupied = incident.scenarioPlacement?.entrance ?: return@any false
                occupied.world == entryPosition.world && occupied.distanceSquared(entryPosition) <= 36
            }) return false
        val sequence = runtime.state.sequence
        val owner = owner(runtime)
        val index = registry.snapshot().sortedBy { it.settings.id }.indexOf(runtime)
        val origin = WorksitePosition(runtime.region.world.name, bounds.minX - 3,
            runtime.region.world.maxHeight - 12 - index * 12, bounds.minZ - 8)
        val placement = MineScenarioPlacement(origin, entry.position(), floor?.id ?: runtime.settings.id,
            (if (definition.scope == MineScenarioScope.MULTI_FLOOR) requireNotNull(other).exit else entry).position(),
            if (definition.scope == MineScenarioScope.MULTI_FLOOR) requireNotNull(other).id else floor?.id ?: runtime.settings.id)
        if (!rooms.canPrepare(runtime, placement)) return false
        if (type == MineIncidentType.LIFT_BREAKDOWN && lift?.beginMaintenance(owner) != true) { rooms.releasePreflight(runtime); return false }
        val started = incidents.start(runtime, type, definition.totalRequired, now, placement = placement)
        if (!started) { rooms.releasePreflight(runtime); if (type == MineIncidentType.LIFT_BREAKDOWN) lift?.endMaintenance(owner); return false }
        check(runtime.state.sequence == sequence)
        registry.snapshot().filter { it === runtime }.flatMap { it.region.world.players }.filter { registry.forAudience(it.location) === runtime }
            .forEach { player ->
                val definitionType = FarmEventTypeRegistry.definition(type)
                val title = locale?.renderPath(definitionType.titlePath, player) ?: return@forEach
                val subtitle = locale.renderPath(definitionType.subtitlePath, player)
                audience.showScreenTitle(player, title, subtitle)
            }
        return true
    }

    fun tick(runtime: MineRuntime, now: Long) {
        val incident = runtime.state.incident
        if (incident?.scenarioPlacement == null) {
            val previous = activeScenes[runtime.settings.id]
            if (previous != null && rooms.restore(runtime, previous)) {
                activeScenes.remove(runtime.settings.id)
                cargo.cleanup(runtime)
                environment.forget(runtime.settings.id)
                actors.cleanup(runtime)
                carts.hide(runtime.settings.id)
                lift?.endMaintenance("mine:${runtime.settings.id}:$previous")
                workingSince.keys.removeIf { it.startsWith("${runtime.settings.id}:") }
            }
            return
        }
        if (liftBreakdownDeadlineExceeded(incident, now)) {
            incidents.abort(runtime)
            lift?.endMaintenance(owner(runtime))
            return
        }
        activeScenes[runtime.settings.id] = runtime.state.sequence
        if (incident.type == MineIncidentType.LIFT_BREAKDOWN) {
            if (lift?.beginMaintenance(owner(runtime)) != true || !lift.maintenanceReady(owner(runtime))) return
        }
        runtime.state.objective?.let { objective ->
            val reclaimed = reclaimMineScenarioLeases(objective, now) { playerId ->
                Bukkit.getPlayer(playerId)?.isOnline == true
            }
            if (reclaimed != objective) {
                runtime.state = runtime.state.copy(objective = reclaimed)
                state.persistAsync()
            }
        }
        val room = rooms.ensure(runtime)?.takeIf { it.ready } ?: return
        val stage = stage(runtime) ?: return
        val objectiveId = "scenario_${stage.index}"
        if (runtime.state.objective?.key?.objectiveId != objectiveId) {
            rooms.template.pads.forEach { pad ->
                val origin = incident.scenarioPlacement.origin
                runtime.region.world.getBlockAt(origin.x + pad.first, origin.y + pad.second, origin.z + pad.third).setType(Material.AIR, false)
            }
            val candidates = rooms.template.pads.take(stage.definition.stages[stage.index].targetCount).mapIndexed { index, pad ->
                val origin = incident.scenarioPlacement.origin
                ObjectiveTargetCandidate("stage_${stage.index}_$index", WorksitePosition(origin.world,
                    origin.x + pad.first, origin.y + pad.second, origin.z + pad.third),
                    ObjectiveTargetRole(stage.definition.stages[stage.index].action.name.lowercase()), index.toLong())
            }
            val nextStep = if (incident.type == MineIncidentType.RUNAWAY_CART && stage.index > 0) incident.scenarioStep else 0
            runtime.state = runtime.state.copy(incident = runtime.state.incident?.copy(scenarioStep = nextStep), objective = ObjectiveTargetPool.plan(
                WorksiteObjectiveKey(runtime.settings.id, objectiveId, runtime.state.sequence),
                stage.definition.stages[stage.index].required, candidates))
            state.persistAsync()
        }
        cargo.reconcile(runtime, stage.definition.stages[stage.index].action, stage.definition.stages[stage.index].material)
        actors.reconcile(runtime, stage.definition.stages[stage.index].action, runtime.state.objective?.targets.orEmpty())
        val material = Material.valueOf(stage.definition.stages[stage.index].material)
        runtime.state.objective?.targets.orEmpty().forEach { target ->
            val block = target.position.location(runtime).block
            val expected = if (stage.definition.stages[stage.index].action == MineScenarioAction.DEFEND || target.status == ObjectiveTargetStatus.COMPLETED || target.status == ObjectiveTargetStatus.LEASED) Material.AIR else material
            if (block.type != expected) block.setType(expected, false)
        }
        val participants = runtime.region.world.players.filter { room.contains(it.location) && eligible(it, runtime) }
        environment.apply(runtime, room, stage.index, incident.progress)
        environment.tickPlayers(runtime, room, participants)
        tickVehicle(runtime, stage.definition.stages[stage.index].action, participants)
        if (stage.definition.stages[stage.index].action == MineScenarioAction.HERD) {
            val guide = workingSince["${runtime.settings.id}:${stage.index}"]?.first?.let(Bukkit::getPlayer)
            if (guide != null && guide in participants) {
                actors.guide(runtime, room.lair.clone().add(0.0, 1.0, 0.0)).forEach { id -> complete(runtime, id, guide) }
            }
        }
        if (stage.definition.stages[stage.index].action == MineScenarioAction.SUSTAIN) {
            val key = "${runtime.settings.id}:${stage.index}"
            val worker = workingSince[key]
            val player = worker?.first?.let(Bukkit::getPlayer)
            val target = runtime.state.objective?.targets?.firstOrNull { it.status != ObjectiveTargetStatus.COMPLETED }
            if (player == null || player !in participants || target == null || player.location.distanceSquared(target.position.location(runtime)) > 16.0) {
                workingSince.remove(key)
            } else if (now - worker.second >= stage.definition.stages[stage.index].durationSeconds * 1000L) {
                workingSince.remove(key)
                complete(runtime, target.id, player)
            }
        }
    }

    fun onInteract(event: PlayerInteractEvent, now: Long): Boolean {
        if (event.hand != EquipmentSlot.HAND || event.action != Action.RIGHT_CLICK_BLOCK) return false
        val player = event.player
        val runtime = runtimeFor(player) ?: return false
        if (!eligible(player, runtime)) return false
        val room = rooms.scene(runtime)
        if (room == null || !room.contains(player.location)) {
            val entry = runtime.state.incident?.scenarioPlacement?.entrance?.location(runtime) ?: return false
            if (player.location.distanceSquared(entry) > 25.0) return false
            event.isCancelled = true
            rooms.enter(player, runtime)
            return true
        }
        val clicked = event.clickedBlock ?: return false
        if (clicked.location.distanceSquared(room.lair) <= 4.0) {
            event.isCancelled = true
            rooms.travel.exit(player)
            return true
        }
        val target = runtime.state.objective?.targets?.firstOrNull { it.position == clicked.location.position() } ?: return false
        event.isCancelled = true
        if (player.location.distanceSquared(clicked.location) > 36.0 || target.status != ObjectiveTargetStatus.AVAILABLE) return true
        val stage = stage(runtime) ?: return true
        when (stage.definition.stages[stage.index].action) {
            MineScenarioAction.INTERACT -> complete(runtime, target.id, player)
            MineScenarioAction.ORDERED_INTERACT -> {
                val next = runtime.state.objective?.targets?.firstOrNull { it.status != ObjectiveTargetStatus.COMPLETED }
                if (next?.id == target.id) complete(runtime, target.id, player)
                else locale?.renderPath("mine.events.common.wrong-order", player)?.let(player::sendActionBar)
            }
            MineScenarioAction.CARRY, MineScenarioAction.ESCORT -> {
                val objective = runtime.state.objective ?: return true
                if (objective.targets.any { it.leasedBy == player.uniqueId }) return true
                val leased = ObjectiveTargetPool.lease(objective, target.id, player.uniqueId, now, 3_600_000)
                if (leased.accepted) { runtime.state = runtime.state.copy(objective = leased.state); state.persistAsync() }
            }
            MineScenarioAction.STEER -> {
                val objective = runtime.state.objective ?: return true
                val next = objective.targets.firstOrNull { it.status != ObjectiveTargetStatus.COMPLETED }
                val gate = 5 + objective.completed * 7
                if (next?.id == target.id && runtime.state.incident!!.scenarioStep in (gate - 2)..gate) complete(runtime, target.id, player)
            }
            MineScenarioAction.SUSTAIN, MineScenarioAction.HERD -> workingSince.putIfAbsent("${runtime.settings.id}:${stage.index}", player.uniqueId to now)
            else -> Unit
        }
        return true
    }

    private fun tickVehicle(runtime: MineRuntime, action: MineScenarioAction, participants: List<Player>) {
        val braking = runtime.state.incident?.type == MineIncidentType.RUNAWAY_CART && action == MineScenarioAction.INTERACT
        if (!braking && action !in setOf(MineScenarioAction.ESCORT, MineScenarioAction.STEER)) {
            carts.hide(runtime.settings.id)
            return
        }
        val incident = runtime.state.incident ?: return
        val origin = incident.scenarioPlacement?.origin ?: return
        val objective = runtime.state.objective ?: return
        val carrier = objective.targets.firstOrNull { it.status == ObjectiveTargetStatus.LEASED }?.leasedBy?.let(Bukkit::getPlayer)
        val step = incident.scenarioStep.coerceAtMost(20)
        val point = WorksitePosition(origin.world, origin.x + 8, origin.y + 1, origin.z + 3 + step)
        if (action == MineScenarioAction.ESCORT && step >= 19 && carrier != null) {
            val room = rooms.scene(runtime) ?: return
            if (room.contains(carrier.location)) rooms.travel.exit(carrier)
            carts.show(runtime, carrier.location.clone().subtract(0.0, 1.0, 0.0).position(), carrier.location.yaw)
            return
        }
        carts.show(runtime, point, 0f)
        if (braking) return
        if (participants.isEmpty()) return
        var next = step
        if (action == MineScenarioAction.STEER) {
            val gate = 5 + objective.completed * 7
            next = if (step >= gate) 0 else step + 1
        } else if (carrier != null && carrier in participants && carrier.location.distanceSquared(point.location(runtime)) <= 25.0) {
            next = step + 1
        }
        if (next != step) {
            runtime.state = runtime.state.copy(incident = incident.copy(scenarioStep = next))
            state.persistAsync()
        }
    }

    fun onDeath(event: org.bukkit.event.entity.EntityDeathEvent): Boolean {
        val identity = actors.death(event) ?: return false
        val runtime = registry.byId(identity.zoneId) ?: return true
        if (runtime.state.sequence == identity.sequence && runtime.state.incident?.scenarioPlacement != null) {
            event.entity.killer?.takeIf { eligible(it, runtime) }?.let { complete(runtime, identity.targetId, it) }
        }
        return true
    }

    fun onInteractEntity(event: org.bukkit.event.player.PlayerInteractEntityEvent): Boolean {
        val gate = rooms.gate(event.rightClicked) ?: return false
        event.isCancelled = true
        if (event.hand != EquipmentSlot.HAND) return true
        val runtime = registry.byId(gate.first) ?: return true
        if (!eligible(event.player, runtime) || event.player.location.distanceSquared(event.rightClicked.location) > 36.0) return true
        if (gate.second) rooms.travel.exit(event.player) else rooms.enter(event.player, runtime)
        return true
    }

    fun onBreak(event: BlockBreakEvent): Boolean {
        val runtime = rooms.at(event.block.location)?.let(registry::byId) ?: return false
        event.isCancelled = true
        if (!eligible(event.player, runtime)) return true
        val stage = stage(runtime) ?: return true
        if (stage.definition.stages[stage.index].action != MineScenarioAction.BREAK) return true
        val target = runtime.state.objective?.targets?.firstOrNull { it.position == event.block.location.position() } ?: return true
        if (event.player.location.distanceSquared(event.block.location) <= 36.0) complete(runtime, target.id, event.player)
        return true
    }

    fun onMove(player: Player, to: Location): Boolean {
        val runtime = runtimeFor(player) ?: return false
        val placement = runtime.state.incident?.scenarioPlacement ?: return false
        val target = runtime.state.objective?.targets?.firstOrNull { it.leasedBy == player.uniqueId } ?: return false
        val currentStage = stage(runtime)?.let { it.definition.stages[it.index] } ?: return false
        if (currentStage.action == MineScenarioAction.ESCORT && runtime.state.incident!!.scenarioStep < 19) return false
        val destination = if (placement.destinationFloorId != placement.floorId) placement.destination.location(runtime) else rooms.scene(runtime)?.lair ?: return false
        if (to.world === destination.world && to.distanceSquared(destination) <= 6.25 && eligible(player, runtime)) complete(runtime, target.id, player)
        return false
    }

    fun runtimeFor(player: Player): MineRuntime? = rooms.at(player.location)?.let(registry::byId)
        ?: rooms.travel.record(player)?.zoneId?.let(registry::byId)
        ?: registry.snapshot().firstOrNull { it.state.objective?.targets?.any { target -> target.leasedBy == player.uniqueId } == true }
        ?: registry.forAudience(player.location)?.takeIf { it.state.incident?.scenarioPlacement != null }

    fun reconcileChunk(runtime: MineRuntime, chunk: org.bukkit.Chunk) {
        val stage = stage(runtime) ?: return
        if (runtime.state.incident?.scenarioPlacement == null) return
        actors.reconcileChunk(runtime, chunk, stage.definition.stages[stage.index].action,
            runtime.state.objective?.targets.orEmpty())
    }

    fun close() {
        registry.snapshot().forEach { runtime ->
            cargo.cleanup(runtime)
            actors.cleanup(runtime)
            carts.hide(runtime.settings.id)
            environment.forget(runtime.settings.id)
            lift?.endMaintenance(owner(runtime))
        }
        workingSince.clear()
        activeScenes.clear()
        rooms.close()
    }

    fun release(player: Player) {
        cargo.release(player)
        registry.snapshot().forEach { runtime ->
            val objective = runtime.state.objective ?: return@forEach
            if (runtime.state.incident?.scenarioPlacement == null) return@forEach
            val released = ObjectiveTargetPool.release(objective, player.uniqueId)
            if (released.accepted) { runtime.state = runtime.state.copy(objective = released.state); state.persistAsync() }
        }
        workingSince.entries.removeIf { it.value.first == player.uniqueId }
    }

    private fun complete(runtime: MineRuntime, target: String, player: Player) { incidents.completeTarget(runtime, target, player) }
    private fun stage(runtime: MineRuntime) = runtime.state.incident?.let { MineScenarioCatalog.definition(it.type.name.lowercase())?.stageAt(it.progress) }
    private fun eligible(player: Player, runtime: MineRuntime) = player.isOnline && !player.isDead && !access.isAdminEditing(player) && access.hasAccess(player, runtime.settings.permission)
    private fun owner(runtime: MineRuntime) = "mine:${runtime.settings.id}:${runtime.state.sequence}"
    private fun safe(location: Location) = location.block.isPassable && location.clone().add(0.0, 1.0, 0.0).block.isPassable && location.clone().subtract(0.0, 1.0, 0.0).block.type.isSolid
    private fun Location.position() = WorksitePosition(world.name, blockX, blockY, blockZ)
    private fun WorksitePosition.location(runtime: MineRuntime) = Location(runtime.region.world, x + 0.5, y.toDouble(), z + 0.5)
    private fun WorksitePosition.distanceSquared(other: WorksitePosition): Long {
        val dx = x.toLong() - other.x
        val dy = y.toLong() - other.y
        val dz = z.toLong() - other.z
        return dx * dx + dy * dy + dz * dz
    }
}

internal const val LIFT_BREAKDOWN_DEADLINE_MILLIS = 180_000L

internal fun liftBreakdownDeadlineExceeded(incident: MineIncidentState, now: Long): Boolean =
    incident.type == MineIncidentType.LIFT_BREAKDOWN &&
        now >= incident.startedAt && now - incident.startedAt >= LIFT_BREAKDOWN_DEADLINE_MILLIS

/** Reclaims only abandoned leases; completed targets and contribution history remain unchanged. */
internal fun reclaimMineScenarioLeases(
    objective: WorksiteObjectiveState,
    now: Long,
    isOnline: (UUID) -> Boolean,
): WorksiteObjectiveState {
    require(now >= 0) { "Mine scenario lease time cannot be negative" }
    val owners = objective.targets
        .filter { target ->
            target.status == ObjectiveTargetStatus.LEASED &&
                (target.leaseExpiresAt <= now || target.leasedBy?.let { !isOnline(it) } == true)
        }
        .mapNotNull(ObjectiveTargetState::leasedBy)
        .distinct()
    return owners.fold(objective) { current, playerId ->
        ObjectiveTargetPool.release(current, playerId).state
    }
}
