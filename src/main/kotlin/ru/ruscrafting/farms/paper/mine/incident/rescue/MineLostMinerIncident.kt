package ru.ruscrafting.farms.paper.mine.incident.rescue

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.GameMode
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
    private val access: ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort,
    private val closingWarning: (Player, Int) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    private val minerLabel: () -> net.kyori.adventure.text.Component = { net.kyori.adventure.text.Component.empty() },
    private val candidateStock: ru.ruscrafting.farms.paper.mine.incident.MineIncidentCandidateStock? = null,
) {
    private val miners = mutableMapOf<String, UUID>()
    private val entrances = mutableMapOf<String, UUID>()
    private val entranceHitboxes = mutableMapOf<String, UUID>()
    private val entrants = mutableMapOf<UUID, String>()
    private val legacyCleanupDone = mutableSetOf<String>()
    private val completedScenes = mutableMapOf<String, CompletionGrace>()
    private val completedExitEntrances = mutableMapOf<String, MutableSet<UUID>>()
    private val completedExitHitboxes = mutableMapOf<String, MutableSet<UUID>>()

    private val preparedTargets = mutableMapOf<String, WorksitePosition>()
    private val nextPreparationAt = mutableMapOf<String,Long>()
    private val candidateCursor = mutableMapOf<String,Int>()

    fun prewarm(runtime: MineRuntime, now: Long) {
        if (now < (nextPreparationAt[key(runtime)] ?: 0L) || active(runtime) || transitioning(runtime)) return
        nextPreparationAt[key(runtime)] = now + 1_000
        val target = preparedTargets[key(runtime)] ?: candidateStock?.candidates(runtime, MineIncidentType.LOST_MINER)?.let { values ->
            if(values.isEmpty()) null else values[(candidateCursor[key(runtime)] ?: 0).mod(values.size)]
        }?.also {
            preparedTargets[key(runtime)] = it
        } ?: return
        val result = maze.ensure(runtime, target)
        if (result.first == MineLostMinerMazeEnsureResult.UNAVAILABLE) {
            preparedTargets.remove(key(runtime))
            candidateCursor[key(runtime)]=(candidateCursor[key(runtime)] ?: 0)+1
        }
    }

    fun start(runtime: MineRuntime, now: Long): Boolean {
        if (transitioning(runtime)) return false
        val target = preparedTargets[key(runtime)] ?: return false
        if (maze.scene(runtime)?.ready != true) return false
        val candidates = listOf(ObjectiveTargetCandidate("lost_miner_1", target, ObjectiveTargetRole("lost_miner"), 0))
        if (!incidents.start(runtime, MineIncidentType.LOST_MINER, 1, now, candidates)) return false
        reconcileMissing(runtime)
        return active(runtime)
    }

    fun onInteractEntity(event: PlayerInteractEntityEvent): Boolean {
        val identity = effects.identity(event.rightClicked) ?: return false
        val runtime = registry.byId(identity.zoneId) ?: return false
        val retained = completedScenes["${identity.zoneId}:${identity.sequence}"]
        if (runtime.state.sequence != identity.sequence && retained == null) return false
        if (event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return true
        if (!active(runtime) && retained == null) return false
        return when (identity.kind) {
            MineIncidentEntityKind.MINER_MAZE_ENTRANCE_HITBOX -> {
                event.isCancelled = true
                if (retained != null && !identity.targetId.startsWith("exit:")) return true
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
        val retained = completedScenes[runtimeKey]
        if ((runtime != null && active(runtime) && maze.scene(runtime)?.contains(to) == true) ||
            (retained != null && retained.scene.contains(to))) return false
        // Walking, teleporting or flying away is an exit; never drag a spectator back into the cave.
        entrants.remove(player.uniqueId)
        travel.reconcile(player, inside = false)
        return false
    }

    fun recover(player: Player) = travel.recover(player)

    fun retainOnTeleport(player: Player, destination: Location): Boolean {
        if (travel.isAuthorized(player, destination)) return true
        val runtimeKey = entrants[player.uniqueId] ?: return false
        val runtime = registry.snapshot().firstOrNull { key(it) == runtimeKey }
        val retained = completedScenes[runtimeKey]
        return (runtime != null && active(runtime) && maze.scene(runtime)?.contains(destination) == true) ||
            (retained != null && retained.scene.contains(destination))
    }

    fun protects(location: Location): Boolean = maze.owns(location)

    fun isRestoring(runtime: MineRuntime): Boolean = maze.isRestoring(runtime)

    fun complete(runtime: MineRuntime, player: Player): Boolean {
        if (!active(runtime) || player.isDead || player.gameMode == GameMode.SPECTATOR ||
            access.isAdminEditing(player) || !access.hasAccess(player, runtime.settings.permission)) return false
        val targetId = runtime.state.objective?.targets?.firstOrNull()?.id ?: return false
        val scene = maze.scene(runtime) ?: return false
        // The NPC is the objective. Flying out and back (or reaching it on foot)
        // must not make a valid nearby click depend on a vanished portal lease.
        if (!scene.contains(player.location) ||
            player.location.distanceSquared(scene.target) > 25.0) return false
        val hadReturn = travel.retains(player)
        val completed = incidents.completeTarget(runtime, targetId, player).accepted
        if (!completed) return false
        MineLostMinerMazeSounds.playFound(player)
        retainCompletedScene(runtime, scene, targetId)
        if (!hadReturn) travel.evacuatePlayer(player, scene.surface)
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

    /** Lets the incident set run grace timers with its authoritative mine clock. */
    fun tick(runtime: MineRuntime, now: Long) {
        completedScenes.values.filter { it.zoneId == runtime.settings.id }.toList()
            .forEach { tickCompletedScene(runtime, it, now) }
    }

    /** A retained rescue blocks a new rescue in the same zone until its maze is restored. */
    fun transitioning(runtime: MineRuntime): Boolean =
        completedScenes.keys.any { it.substringBefore(':') == runtime.settings.id } || isRestoring(runtime)

    /** Admin replacement may explicitly bypass the natural completion grace. */
    fun forceCleanup(runtime: MineRuntime) {
        completedScenes.values.filter { it.zoneId == runtime.settings.id }.toList().forEach { retireCompleted(runtime, it) }
    }

    fun activateLoadedState() {
        maze.reconcileLoaded { zoneId, sequence -> registry.byId(zoneId)?.state?.sequence == sequence }
        registry.snapshot().filter(::active).forEach(::reconcileMissing)
    }

    fun onChunkLoad(chunk: Chunk) {
        maze.onChunkLoad(chunk) { zoneId, sequence -> activeOrRetained(zoneId, sequence) || registry.byId(zoneId)?.state?.sequence == sequence }
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
        reconciled[target.id]?.let { id ->
            miners[key(runtime)] = id
            effects.entity(id)?.apply { customName(minerLabel()); isCustomNameVisible = true }
        }
        purgeMissing(runtime)
        cleanupLegacyEntities(runtime)
        return if (key(runtime) in miners) 1 else 0
    }

    fun canonicalCount(runtime: MineRuntime): Int = if (miners[key(runtime)]?.let(effects::entity) != null) 1 else 0

    fun reconcileMissing(runtime: MineRuntime): Int {
        if (completedScenes.containsKey(key(runtime))) return 0
        if (!active(runtime)) return 0
        purgeMissing(runtime)
        if (sceneComplete(runtime)) {
            maze.scene(runtime)?.let { creatures.reconcile(runtime, it) }
            return canonicalCount(runtime)
        }
        val target = runtime.state.objective?.targets?.firstOrNull() ?: return 0
        val (result, scene) = maze.ensure(runtime, target.position)
        if (result == MineLostMinerMazeEnsureResult.UNAVAILABLE) {
            retire(runtime)
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
        if (!active(runtime) && key(runtime) in preparedTargets) return
        completedScenes.values.filter { it.zoneId == runtime.settings.id }.toList().forEach { retireCompleted(runtime, it) }
        completedScenes.remove(key(runtime))
        completedExitEntrances.keys.removeIf { it.substringBefore(':') == runtime.settings.id }
        completedExitHitboxes.keys.removeIf { it.substringBefore(':') == runtime.settings.id }
        retire(runtime)
    }

    fun clearQueues() {
        preparedTargets.clear(); nextPreparationAt.clear(); candidateCursor.clear()
        maze.clearQueues()
        miners.clear()
        entrances.clear()
        entranceHitboxes.clear()
        entrants.clear()
        legacyCleanupDone.clear()
        completedScenes.clear()
        completedExitEntrances.clear()
        completedExitHitboxes.clear()
    }

    private fun retire(runtime: MineRuntime) {
        preparedTargets.remove(key(runtime))
        creatures.cleanup(runtime)
        returnEntrants(runtime, skipSpectators = false)
        miners.remove(key(runtime))?.let(effects::remove)
        entrances.remove(key(runtime))?.let(effects::remove)
        entranceHitboxes.remove(key(runtime))?.let(effects::remove)
        OWNED_KINDS.forEach { effects.cleanup(runtime, it) }
        maze.beginRestore(runtime.region.world, runtime.settings.id, runtime.state.sequence)
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

    private fun recordActive(record: MineLostMinerMazeJournalRecord): Boolean = activeOrRetained(record.zoneId, record.sequence)

    private fun activeOrRetained(zoneId: String, sequence: Long): Boolean =
        active(zoneId, sequence) || "$zoneId:$sequence" in preparedTargets || completedScenes["$zoneId:$sequence"]?.sequence == sequence

    private fun active(zoneId: String, sequence: Long): Boolean =
        registry.byId(zoneId)?.let { it.state.sequence == sequence && active(it) } == true

    private fun active(runtime: MineRuntime): Boolean =
        runtime.state.phase == MinePhase.INCIDENT && runtime.state.incident?.type == MineIncidentType.LOST_MINER

    private fun retainCompletedScene(runtime: MineRuntime, scene: MineLostMinerMazeScene, targetId: String) {
        preparedTargets.remove(key(runtime))
        val completedAt = clock()
        completedScenes[key(runtime)] = CompletionGrace(
            runtime.settings.id, runtime.state.sequence, targetId, scene, completedAt,
            completedAt + HARD_DEADLINE_MILLIS - WARNING_MILLIS, completedAt + HARD_DEADLINE_MILLIS,
        )
        returnEntrants(runtime, skipSpectators = false)
        creatures.cleanup(runtime)
        miners.remove(key(runtime))?.let(effects::remove)
        entrances.remove(key(runtime))?.let(effects::remove)
        entranceHitboxes.remove(key(runtime))?.let(effects::remove)
        effects.cleanup(runtime, MineIncidentEntityKind.MINER_MAZE_ENTRANCE)
        effects.cleanup(runtime, MineIncidentEntityKind.MINER_MAZE_ENTRANCE_HITBOX)
    }

    private fun reconcileExit(
        runtime: MineRuntime,
        scene: MineLostMinerMazeScene,
        chunk: Chunk? = null,
        targetId: String = completedScenes[key(runtime)]?.targetId ?: "lost_miner_1",
    ) {
        val expected = mapOf("exit:$targetId" to WorksitePosition(scene.world.name, scene.start.blockX, scene.start.blockY - 1, scene.start.blockZ))
        val chunks = if (chunk != null) listOf(chunk) else listOfNotNull(
            scene.world.takeIf { it.isChunkLoaded(scene.start.blockX shr 4, scene.start.blockZ shr 4) }
                ?.getChunkAt(scene.start.blockX shr 4, scene.start.blockZ shr 4),
        )
        chunks.forEach { currentChunk ->
            effects.reconcileChunk(runtime, currentChunk, MineIncidentEntityKind.MINER_MAZE_ENTRANCE, expected)
                .values.forEach { id -> completedExitEntrances.getOrPut(key(runtime)) { linkedSetOf() }.add(id) }
            effects.reconcileChunk(runtime, currentChunk, MineIncidentEntityKind.MINER_MAZE_ENTRANCE_HITBOX, expected)
                .values.forEach { id -> completedExitHitboxes.getOrPut(key(runtime)) { linkedSetOf() }.add(id) }
        }
    }

    private fun tickCompletedScene(runtime: MineRuntime?, grace: CompletionGrace, now: Long) {
        if (now > grace.completedAt + 1_500L) grace.scene.world.players.filter {
            it.location.distanceSquared(grace.scene.start) <= 2.25 && travel.retains(it)
        }.forEach { if (travel.exit(it)) entrants.remove(it.uniqueId) }
        if (now >= grace.deadlineAt) {
            evacuateAtDeadline(grace)
            retireCompleted(runtime, grace)
            return
        }
        if (!grace.warningSent && now >= grace.warningAt) {
            grace.warningSent = true
            grace.scene.world.players.filter { near(grace.scene, it.location) && it.gameMode != GameMode.SPECTATOR }
                .forEach { closingWarning(it, WARNING_SECONDS) }
        }
        if (now >= grace.completedAt + GRACE_MILLIS && grace.scene.world.players.none { near(grace.scene, it.location) }) {
            retireCompleted(runtime, grace)
        }
    }

    private fun retireCompleted(runtime: MineRuntime?, grace: CompletionGrace) {
        val sceneKey = "${grace.zoneId}:${grace.sequence}"
        if (!completedScenes.remove(sceneKey, grace)) return
        runtime?.takeIf { it.state.sequence == grace.sequence }?.let(creatures::cleanup)
        returnEntrants(grace.zoneId, grace.sequence, skipSpectators = true)
        miners.remove(sceneKey)?.let(effects::remove)
        entrances.remove(sceneKey)?.let(effects::remove)
        entranceHitboxes.remove(sceneKey)?.let(effects::remove)
        completedExitEntrances.remove(sceneKey).orEmpty().forEach(effects::remove)
        completedExitHitboxes.remove(sceneKey).orEmpty().forEach(effects::remove)
        maze.beginRestore(grace.scene.world, grace.zoneId, grace.sequence)
    }

    private fun evacuateAtDeadline(grace: CompletionGrace) {
        travel.records().values.filter { it.zoneId == grace.zoneId && it.sequence == grace.sequence }
            .mapNotNull { Bukkit.getPlayer(it.playerId) }
            .forEach { player ->
                if (player.gameMode == GameMode.SPECTATOR) travel.reconcile(player, inside = false)
                else travel.exit(player)
            }
        grace.scene.world.players.filter { near(grace.scene, it.location) && it.gameMode != GameMode.SPECTATOR }
            .filterNot { player -> travel.records()[player.uniqueId]?.let { it.zoneId == grace.zoneId && it.sequence == grace.sequence } == true }
            .forEach { player -> player.teleport(grace.scene.surface, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN) }
        entrants.entries.removeIf { entry ->
            entry.value == "${grace.zoneId}:${grace.sequence}" && Bukkit.getPlayer(entry.key)?.gameMode == GameMode.SPECTATOR
        }
    }

    private fun returnEntrants(runtime: MineRuntime, skipSpectators: Boolean) =
        returnEntrants(runtime.settings.id, runtime.state.sequence, skipSpectators)

    private fun returnEntrants(zoneId: String, sequence: Long, skipSpectators: Boolean) {
        if (!skipSpectators) {
            travel.records().values.filter { it.zoneId == zoneId && it.sequence == sequence }
                .mapNotNull { Bukkit.getPlayer(it.playerId) }.forEach(travel::exit)
            entrants.entries.removeIf { it.value == "$zoneId:$sequence" }
            return
        }
        travel.records().values.filter { it.zoneId == zoneId && it.sequence == sequence }
            .mapNotNull { Bukkit.getPlayer(it.playerId) }
            .forEach { player ->
                if (player.gameMode == GameMode.SPECTATOR) travel.reconcile(player, inside = false)
                else travel.exit(player)
            }
        entrants.entries.removeIf { it.value == "$zoneId:$sequence" }
    }

    private fun near(scene: MineLostMinerMazeScene, location: Location): Boolean {
        if (location.world !== scene.world) return false
        if (scene.contains(location)) return true
        return scene.records.any { record ->
            val dx = location.x - (record.x + 0.5)
            val dy = location.y - (record.y + 0.5)
            val dz = location.z - (record.z + 0.5)
            dx * dx + dy * dy + dz * dz <= CLEANUP_DISTANCE_SQUARED
        }
    }

    private fun candidates(runtime: MineRuntime, required: Int): List<ObjectiveTargetCandidate> =
        orderMineIncidentPositions(
            runtime,
            candidateStock?.candidates(runtime, MineIncidentType.LOST_MINER).orEmpty(),
            required * runtime.rules().targetMultiplier * 2,
            0x1057L,
        ).mapIndexed { order, position ->
            ObjectiveTargetCandidate("lost_miner_${order + 1}", position, ObjectiveTargetRole("lost_miner"), order.toLong())
        }

    private fun key(runtime: MineRuntime) = "${runtime.settings.id}:${runtime.state.sequence}"

    private companion object {
        const val MAZE_BLOCK_BUDGET = 128
        const val GRACE_MILLIS = 60_000L
        const val HARD_DEADLINE_MILLIS = 5 * 60_000L
        const val WARNING_MILLIS = 15_000L
        const val WARNING_SECONDS = 15
        const val CLEANUP_DISTANCE_SQUARED = 64.0
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

    private data class CompletionGrace(
        val zoneId: String,
        val sequence: Long,
        val targetId: String,
        val scene: MineLostMinerMazeScene,
        val completedAt: Long,
        val warningAt: Long,
        val deadlineAt: Long,
        var warningSent: Boolean = false,
    )
}
