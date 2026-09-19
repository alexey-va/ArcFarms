package ru.ruscrafting.farms.paper.mine.incident.rescue

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEntityEvent
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator
import ru.ruscrafting.farms.paper.mine.incident.entity.MineIncidentEntityEffects
import ru.ruscrafting.farms.paper.mine.incident.entity.MineIncidentEntityKind
import ru.ruscrafting.farms.paper.mine.incident.isIncidentSurface
import ru.ruscrafting.farms.paper.mine.incident.orderMineIncidentPositions
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import java.util.UUID

/** One in-mine entrance leads to one temporary off-site maze. */
internal class MineLostMinerIncident(
    private val registry: MineRuntimeRegistry,
    private val index: MineBlockIndex,
    private val incidents: MineIncidentCoordinator,
    private val effects: MineIncidentEntityEffects,
    private val maze: MineLostMinerMazeWorld,
    private val travel: ru.ruscrafting.farms.paper.worksite.WorksiteExpeditionTravel,
    private val creatures: MineRescueCreatures,
) {
    private val miners = mutableMapOf<String, UUID>()
    private val entrances = mutableMapOf<String, UUID>()
    private val entranceHitboxes = mutableMapOf<String, UUID>()
    private val entrants = mutableMapOf<UUID, String>()
    private val legacyCleanupDone = mutableSetOf<String>()

    fun start(runtime: MineRuntime, now: Long): Boolean {
        val candidates = candidates(runtime, 1)
        if (candidates.size < runtime.rules().targetMultiplier) return false
        if (!incidents.start(runtime, MineIncidentType.LOST_MINER, 1, now, candidates)) return false
        reconcileMissing(runtime)
        return active(runtime)
    }

    fun onInteractEntity(event: PlayerInteractEntityEvent): Boolean {
        val identity = effects.identity(event.rightClicked) ?: return false
        val runtime = registry.byId(identity.zoneId) ?: return false
        if (!active(runtime) || runtime.state.sequence != identity.sequence) return false
        if (event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return true
        return when (identity.kind) {
            MineIncidentEntityKind.MINER_MAZE_ENTRANCE_HITBOX -> {
                event.isCancelled = true
                if (identity.targetId.startsWith("exit:") || travel.retains(event.player)) {
                    if (travel.exit(event.player)) entrants.remove(event.player.uniqueId)
                } else enter(runtime, event.player)
                true
            }
            MineIncidentEntityKind.MINER -> {
                event.isCancelled = true
                complete(runtime, event.player)
                true
            }
            else -> false
        }
    }

    private fun enter(runtime: MineRuntime, player: Player): Boolean {
        val scene = maze.scene(runtime)?.takeIf(MineLostMinerMazeScene::ready) ?: return false
        val sequence = runtime.state.sequence
        travel.enter(ru.ruscrafting.farms.paper.worksite.WorksiteExpeditionTravel.EntryRequest(
            player, runtime.settings.id, sequence, runtime.settings.permission, scene.surface,
            scene.start.clone().also { it.yaw = player.location.yaw; it.pitch = player.location.pitch },
        ), onEntered = {
            entrants[player.uniqueId] = key(runtime)
            MineLostMinerMazeSounds.playEntry(player)
        }) { active(runtime) && runtime.state.sequence == sequence && scene.ready }
        return travel.retains(player)
    }

    fun onMove(to: Location, player: Player): Boolean {
        val runtimeKey = entrants[player.uniqueId] ?: return false
        val runtime = registry.snapshot().firstOrNull { key(it) == runtimeKey }
        if (runtime != null && active(runtime) && maze.scene(runtime)?.contains(to) == true) return false
        // Walking, teleporting or flying away is an exit; never drag a spectator back into the cave.
        entrants.remove(player.uniqueId)
        travel.reconcile(player, inside = false)
        return false
    }

    fun recover(player: Player) = travel.recover(player)

    fun retainOnTeleport(player: Player, destination: Location): Boolean {
        if (travel.isAuthorized(player, destination)) return true
        val runtimeKey = entrants[player.uniqueId] ?: return false
        val runtime = registry.snapshot().firstOrNull { key(it) == runtimeKey } ?: return false
        return active(runtime) && maze.scene(runtime)?.contains(destination) == true
    }

    fun protects(location: Location): Boolean = maze.owns(location)

    fun isRestoring(runtime: MineRuntime): Boolean = maze.isRestoring(runtime)

    fun complete(runtime: MineRuntime, player: Player): Boolean {
        val targetId = runtime.state.objective?.targets?.firstOrNull()?.id ?: return false
        val scene = maze.scene(runtime) ?: return false
        if (!travel.retains(player) || !scene.contains(player.location) ||
            player.location.distanceSquared(scene.target) > 25.0) return false
        val completed = incidents.completeTarget(runtime, targetId, player).accepted
        if (!completed) return false
        MineLostMinerMazeSounds.playFound(player)
        retire(runtime, scene.surface)
        return true
    }

    fun releasePlayer(player: Player, reason: WorksitePlayerReleaseReason): Boolean {
        val existed = entrants.remove(player.uniqueId) != null
        if (reason in RETURN_TO_SURFACE_REASONS) {
            travel.exit(player)
            travel.quit(player)
        } else travel.reconcile(player, inside = false)
        return existed
    }

    fun onDeath(event: org.bukkit.event.entity.EntityDeathEvent) = creatures.onDeath(event)
    fun onDamage(event: org.bukkit.event.entity.EntityDamageEvent) = creatures.onDamage(event)

    fun process(): Int = maze.process(MAZE_BLOCK_BUDGET, ::recordActive)

    fun activateLoadedState() {
        maze.reconcileLoaded { zoneId, sequence -> active(zoneId, sequence) }
        registry.snapshot().filter(::active).forEach(::reconcileMissing)
    }

    fun onChunkLoad(chunk: Chunk) {
        maze.onChunkLoad(chunk) { zoneId, sequence -> active(zoneId, sequence) }
    }

    fun reconcileChunk(runtime: MineRuntime, chunk: Chunk): Int {
        if (!active(runtime)) {
            cleanupLegacyEntities(runtime)
            return 0
        }
        val scene = maze.scene(runtime) ?: run {
            reconcileMissing(runtime)
            maze.scene(runtime)
        } ?: return 0
        if (!scene.ready) return 0
        val target = runtime.state.objective?.targets?.firstOrNull() ?: return 0
        val entryTargets = mapOf(target.id to target.position, "exit:${target.id}" to
            WorksitePosition(scene.world.name, scene.start.blockX, scene.start.blockY - 1, scene.start.blockZ))
        effects.reconcileChunk(runtime, chunk, MineIncidentEntityKind.MINER_MAZE_ENTRANCE, entryTargets)
            .get(target.id)?.let { entrances[key(runtime)] = it }
        effects.reconcileChunk(runtime, chunk, MineIncidentEntityKind.MINER_MAZE_ENTRANCE_HITBOX, entryTargets)
            .get(target.id)?.let { entranceHitboxes[key(runtime)] = it }
        val reconciled = effects.reconcileChunk(
            runtime,
            chunk,
            MineIncidentEntityKind.MINER,
            mapOf(target.id to scene.targetPosition()),
        )
        reconciled[target.id]?.let { miners[key(runtime)] = it }
        purgeMissing(runtime)
        cleanupLegacyEntities(runtime)
        return if (key(runtime) in miners) 1 else 0
    }

    fun canonicalCount(runtime: MineRuntime): Int = if (miners[key(runtime)]?.let(effects::entity) != null) 1 else 0

    fun reconcileMissing(runtime: MineRuntime): Int {
        if (!active(runtime)) return 0
        purgeMissing(runtime)
        if (sceneComplete(runtime)) {
            maze.scene(runtime)?.let { creatures.reconcile(runtime, it) }
            return canonicalCount(runtime)
        }
        val target = runtime.state.objective?.targets?.firstOrNull() ?: return 0
        val (result, scene) = maze.ensure(runtime, target.position)
        if (result == MineLostMinerMazeEnsureResult.UNAVAILABLE) {
            retire(runtime, target.position.location())
            incidents.abort(runtime)
            return 0
        }
        if (scene == null) return 0
        cleanupLegacyEntities(runtime)
        if (result != MineLostMinerMazeEnsureResult.READY || !scene.ready) return 0
        creatures.reconcile(runtime, scene)
        setOf(scene.surface.chunk, scene.start.chunk, scene.target.chunk).forEach { reconcileChunk(runtime, it) }
        return canonicalCount(runtime)
    }

    fun cleanup(runtime: MineRuntime) {
        val surface = maze.scene(runtime)?.surface
            ?: runtime.state.objective?.targets?.firstOrNull()?.position?.location()
        retire(runtime, surface)
    }

    fun clearQueues() {
        maze.clearQueues()
        miners.clear()
        entrances.clear()
        entranceHitboxes.clear()
        entrants.clear()
        legacyCleanupDone.clear()
    }

    private fun retire(runtime: MineRuntime, surface: Location?) {
        creatures.cleanup(runtime)
        returnEntrants(runtime, surface)
        miners.remove(key(runtime))?.let(effects::remove)
        entrances.remove(key(runtime))?.let(effects::remove)
        entranceHitboxes.remove(key(runtime))?.let(effects::remove)
        OWNED_KINDS.forEach { effects.cleanup(runtime, it) }
        maze.beginRestore(runtime.region.world, runtime.settings.id, runtime.state.sequence)
    }

    private fun returnEntrants(runtime: MineRuntime, surface: Location?) {
        travel.evacuate(runtime.settings.id)
        entrants.entries.removeIf { it.value == key(runtime) }
    }

    private fun cleanupLegacyEntities(runtime: MineRuntime) {
        if (!legacyCleanupDone.add(key(runtime))) return
        LEGACY_CAMP_KINDS.forEach { effects.cleanup(runtime, it) }
    }

    private fun sceneComplete(runtime: MineRuntime): Boolean =
        listOf(miners, entrances, entranceHitboxes).all { ids -> ids[key(runtime)]?.let(effects::entity) != null }

    private fun purgeMissing(runtime: MineRuntime) {
        listOf(miners, entrances, entranceHitboxes).forEach { ids ->
            ids[key(runtime)]?.takeIf { effects.entity(it) == null }?.let { ids.remove(key(runtime)) }
        }
    }

    private fun recordActive(record: MineLostMinerMazeJournalRecord): Boolean = active(record.zoneId, record.sequence)

    private fun active(zoneId: String, sequence: Long): Boolean =
        registry.byId(zoneId)?.let { it.state.sequence == sequence && active(it) } == true

    private fun active(runtime: MineRuntime): Boolean =
        runtime.state.phase == MinePhase.INCIDENT && runtime.state.incident?.type == MineIncidentType.LOST_MINER

    private fun candidates(runtime: MineRuntime, required: Int): List<ObjectiveTargetCandidate> =
        orderMineIncidentPositions(
            runtime,
            index.loadedTargets(runtime.settings.id, MineAnchorRole.MINER)
                .filter {
                    index.isLiveTarget(runtime.settings.id, it, MineAnchorRole.MINER, runtime.railMaterials) &&
                        runtime.isIncidentSurface(it)
                },
            required * runtime.rules().targetMultiplier * 2,
            0x1057L,
        ).mapIndexed { order, position ->
            ObjectiveTargetCandidate("lost_miner_${order + 1}", position, ObjectiveTargetRole("lost_miner"), order.toLong())
        }

    private fun WorksitePosition.location(): Location =
        Location(Bukkit.getWorld(world), x + 0.5, y + 1.0, z + 0.5)

    private fun key(runtime: MineRuntime) = "${runtime.settings.id}:${runtime.state.sequence}"

    private companion object {
        const val MAZE_BLOCK_BUDGET = 128
        val LEGACY_CAMP_KINDS = setOf(
            MineIncidentEntityKind.MINER_CAMP_LANTERN,
            MineIncidentEntityKind.MINER_CAMP_SUPPLIES,
        )
        val OWNED_KINDS = LEGACY_CAMP_KINDS + setOf(
            MineIncidentEntityKind.MINER,
            MineIncidentEntityKind.MINER_MAZE_ENTRANCE,
            MineIncidentEntityKind.MINER_MAZE_ENTRANCE_HITBOX,
        )
        val RETURN_TO_SURFACE_REASONS = setOf(
            WorksitePlayerReleaseReason.QUIT,
            WorksitePlayerReleaseReason.RELOAD,
            WorksitePlayerReleaseReason.SHUTDOWN,
            WorksitePlayerReleaseReason.OBJECTIVE_REPLACED,
        )
    }
}
