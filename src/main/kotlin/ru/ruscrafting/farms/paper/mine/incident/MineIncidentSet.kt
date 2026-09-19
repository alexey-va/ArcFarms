package ru.ruscrafting.farms.paper.mine.incident

import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.incident.cavein.MineCaveInIncident
import ru.ruscrafting.farms.paper.mine.incident.creature.MineCreatureNestIncident
import ru.ruscrafting.farms.paper.mine.incident.crystal.MineCrystalResonanceIncident
import ru.ruscrafting.farms.paper.mine.incident.flood.MineFloodingIncident
import ru.ruscrafting.farms.paper.mine.incident.gas.MineGasLeakIncident
import ru.ruscrafting.farms.paper.mine.incident.power.MinePowerFailureIncident
import ru.ruscrafting.farms.paper.mine.incident.rescue.MineLostMinerIncident
import ru.ruscrafting.farms.paper.mine.incident.entity.MineObjectiveMarkerScene
import ru.ruscrafting.farms.paper.mine.incident.entity.MineIncidentEntityKind
import ru.ruscrafting.farms.paper.mine.incident.entity.isObjectiveMarkerHitbox
import ru.ruscrafting.farms.paper.mine.incident.track.MineTrackDamageIncident
import ru.ruscrafting.farms.paper.mine.recovery.MineIncidentBlockJournal
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import java.util.UUID

/** Owns incident routing, reconciliation, service-item release and cleanup for one mine module. */
internal class MineIncidentSet(
    private val registry: MineRuntimeRegistry,
    private val caveIn: MineCaveInIncident,
    private val trackDamage: MineTrackDamageIncident,
    private val gasLeak: MineGasLeakIncident,
    private val crystalResonance: MineCrystalResonanceIncident,
    private val flooding: MineFloodingIncident,
    private val powerFailure: MinePowerFailureIncident,
    private val creatureNest: MineCreatureNestIncident,
    private val lostMiner: MineLostMinerIncident,
    private val objectiveMarkers: MineObjectiveMarkerScene,
    private val coordinator: MineIncidentCoordinator,
    private val scheduler: MineIncidentScheduler,
    private val journal: MineIncidentBlockJournal,
    private val workings: ru.ruscrafting.farms.paper.mine.working.MineWorkingController,
    private val workshop: ru.ruscrafting.farms.paper.mine.workshop.MineOreWorkshopController,
    private val access: WorksiteAccessPort,
) {
    /** Farm-style admin switch: retire the current scene before forcing the requested incident. */
    fun forceAdmin(runtime: MineRuntime, type: ru.ruscrafting.farms.domain.MineIncidentType, now: Long): Boolean {
        if (type !in MineIncidentScheduler.SUPPORTED_TYPES) return false
        workings.cancelPending(runtime.settings.id)
        workings.forceCleanup(runtime)
        lostMiner.forceCleanup(runtime)
        if (type != ru.ruscrafting.farms.domain.MineIncidentType.CAVE_IN) {
            caveIn.cancelPending(runtime.settings.id)
        }
        if (runtime.state.phase == ru.ruscrafting.farms.domain.MinePhase.INCIDENT || runtime.state.incident != null) {
            clearActive(runtime)
            if (!coordinator.abort(runtime)) return false
        }
        if (type == ru.ruscrafting.farms.domain.MineIncidentType.LOST_MINER && lostMiner.isRestoring(runtime)) {
            return false
        }
        return scheduler.force(runtime, type, now)
    }

    fun tick(runtime: MineRuntime, now: Long, participants: Collection<Player>) {
        scheduler.tick(runtime, now, participants.size)
        if (abortIncompatibleObjective(runtime)) return
        if (runtime.state.incident?.type != ru.ruscrafting.farms.domain.MineIncidentType.ORE_WORKSHOP) workings.tick(runtime, now)
        lostMiner.tick(runtime, now)
        workshop.tick(runtime, participants, now)
        gasLeak.tick(runtime, participants, now)
        caveIn.reconcile(runtime)
        trackDamage.reconcile(runtime)
        participants.forEach { trackDamage.ensureKit(runtime, it) }
        flooding.reconcile(runtime)
        powerFailure.reconcile(runtime)
        reconcileObjectiveMarkers(runtime)
        creatureNest.reconcileMissing(runtime)
        lostMiner.reconcileMissing(runtime)
    }

    fun process(): Int = lostMiner.process() + workings.process()

    fun blocksOreSupply(runtime: MineRuntime): Boolean = workings.blocksOreSupply(runtime)

    fun protectsTemporaryBlock(location: Location): Boolean = flooding.protects(location) || workings.protects(location) || lostMiner.protects(location)

    fun retainOnTeleport(player: Player, destination: Location): Boolean =
        workings.retains(player, destination) || lostMiner.retainOnTeleport(player, destination)

    fun onInteract(event: PlayerInteractEvent): Boolean {
        val clicked = event.clickedBlock
        val runtime = clicked?.location?.let(registry::at)
        if (runtime != null && !canUseMineBlock(event.player, runtime)) {
            event.isCancelled = true
            return true
        }
        val handled = workshop.onInteract(event, registry.snapshot()) || workings.onInteract(event) || trackDamage.onInteract(event) || gasLeak.onInteract(event) ||
            crystalResonance.onInteract(event) || flooding.onInteract(event) || powerFailure.onInteract(event)
        if (handled) clicked?.location?.let(registry::at)?.let { runtime ->
            if (runtime.state.phase == ru.ruscrafting.farms.domain.MinePhase.INCIDENT) objectiveMarkers.reconcile(runtime)
            else objectiveMarkers.cleanup(runtime)
        }
        return handled
    }

    fun onBucketFill(event: org.bukkit.event.player.PlayerBucketFillEvent): Boolean = flooding.onBucketFill(event)

    fun onBreak(event: BlockBreakEvent): Boolean {
        val runtime = registry.at(event.block.location)
        if (runtime != null && !canUseMineBlock(event.player, runtime)) {
            event.isCancelled = true
            return true
        }
        return workings.onBreak(event) || caveIn.onBreak(event)
    }

    fun canMine(player: Player, block: org.bukkit.block.Block): Boolean {
        val runtime = registry.at(block.location)
        if (runtime != null && !canUseMineBlock(player, runtime)) return false
        return workings.canMine(player, block) || caveIn.canMine(player, block)
    }

    fun onMove(to: Location, player: Player): Boolean = lostMiner.onMove(to, player)

    fun onInteractEntity(event: PlayerInteractEntityEvent): Boolean {
        if (workshop.onInteractEntity(event, registry.snapshot()) || workings.onInteractEntity(event)) return true
        val identity = objectiveMarkers.identity(event.rightClicked)
        if (identity == null || !identity.kind.isObjectiveMarkerHitbox) return lostMiner.onInteractEntity(event)
        val runtime = registry.byId(identity.zoneId)
        if (runtime == null || runtime.state.sequence != identity.sequence ||
            event.hand != org.bukkit.inventory.EquipmentSlot.HAND ||
            event.player.world !== event.rightClicked.world ||
            event.player.location.distanceSquared(event.rightClicked.location) > 25.0 ||
            !canUseMineBlock(event.player, runtime)) {
            event.isCancelled = true
            return true
        }
        event.isCancelled = true
        when (identity.kind) {
            MineIncidentEntityKind.GAS_MARKER_HITBOX -> gasLeak.onInteractEntity(runtime, identity.targetId, event.player)
            MineIncidentEntityKind.CRYSTAL_MARKER_HITBOX -> crystalResonance.onInteractEntity(runtime, identity.targetId, event.player)
            MineIncidentEntityKind.FLOOD_MARKER_HITBOX -> flooding.onInteractEntity(runtime, identity.targetId, event.player)
            MineIncidentEntityKind.POWER_MARKER_HITBOX -> powerFailure.onInteractEntity(runtime, identity.targetId, event.player)
            else -> error("unreachable objective marker kind ${identity.kind}")
        }
        if (runtime.state.phase == ru.ruscrafting.farms.domain.MinePhase.INCIDENT) objectiveMarkers.reconcile(runtime)
        else objectiveMarkers.cleanup(runtime)
        return true
    }

    private fun canUseMineBlock(player: Player, runtime: MineRuntime): Boolean =
        player.gameMode != org.bukkit.GameMode.SPECTATOR &&
            !access.isAdminEditing(player) &&
            player.world === runtime.region.world &&
            access.hasAccess(player, runtime.settings.permission)

    fun onEntityDeath(event: EntityDeathEvent): Boolean = lostMiner.onDeath(event) || creatureNest.onDeath(event)

    fun onEntityDamage(event: EntityDamageEvent): Boolean = lostMiner.onDamage(event) || creatureNest.onDamage(event)

    fun releasePlayer(player: Player, reason: WorksitePlayerReleaseReason) {
        workshop.releasePlayer(player, reason)
        trackDamage.releasePlayer(player.uniqueId)
        lostMiner.releasePlayer(player, reason)
        workings.releasePlayer(player, reason)
    }

    fun isActive(identity: ServiceItemIdentity): Boolean =
        trackDamage.isActive(identity) || workings.isActive(identity)

    fun release(playerId: UUID, identity: ServiceItemIdentity, reason: WorksitePlayerReleaseReason) {
        trackDamage.release(playerId, identity, reason)
        workings.release(playerId, identity, reason)
    }

    fun reconcileChunk(runtime: MineRuntime, chunk: Chunk) {
        caveIn.reconcile(runtime)
        flooding.reconcile(runtime)
        powerFailure.reconcile(runtime)
        creatureNest.reconcileChunk(runtime, chunk)
        lostMiner.reconcileChunk(runtime, chunk)
        if (!abortBlockedObjective(runtime)) objectiveMarkers.reconcileChunk(runtime, chunk)
    }

    fun reconcileRecovery(chunk: Chunk? = null): Int {
        if (chunk == null) {
            registry.snapshot().forEach(::abortIncompatibleObjective)
            lostMiner.activateLoadedState()
            workings.reconcileLoaded()
            registry.snapshot().forEach { runtime ->
                caveIn.reconcile(runtime)
                flooding.reconcile(runtime)
                powerFailure.reconcile(runtime)
                reconcileObjectiveMarkers(runtime)
            }
        } else { lostMiner.onChunkLoad(chunk); workings.onChunkLoad(chunk) }
        return journal.restoreOrphans(registry.snapshot(), chunk)
    }

    fun beforeReload() { workings.beforeReload(); workshop.cleanup() }

    fun cleanup() {
        registry.snapshot().forEach { runtime ->
            runtime.state.incident?.serviceLeases?.values?.toSet().orEmpty().forEach(trackDamage::releasePlayer)
        }
        scheduler.cleanup()
        registry.snapshot().forEach { runtime ->
            caveIn.cleanup(runtime)
            creatureNest.cleanup(runtime)
            lostMiner.cleanup(runtime)
            objectiveMarkers.cleanup(runtime)
        }
        lostMiner.clearQueues()
        workings.cleanup()
        workshop.cleanup()
    }

    fun guardMovement(event: org.bukkit.event.player.PlayerMoveEvent): Boolean {
        workshop.guardMovement(event, registry.snapshot())
        return workings.guardMovement(event)
    }
    fun updateVisuals(now: Long) {
        registry.snapshot().forEach { runtime ->
            if (runtime.state.incident?.type == ru.ruscrafting.farms.domain.MineIncidentType.ORE_WORKSHOP) {
                workshop.tick(runtime, runtime.region.world.players, now)
            }
        }
    }
    fun recoverPlayer(player: Player) { workings.recover(player); lostMiner.recover(player) }

    private fun clearActive(runtime: MineRuntime) {
        if (runtime.state.incident?.type == ru.ruscrafting.farms.domain.MineIncidentType.ORE_WORKSHOP) workshop.cleanup(runtime)
        if (runtime.state.incident?.working != null) workings.retire(runtime)
        runtime.state.incident?.serviceLeases?.values?.toSet().orEmpty().forEach(trackDamage::releasePlayer)
        runtime.state.incident?.type?.name?.lowercase()?.let { journal.restore(runtime, it) }
        caveIn.cleanup(runtime)
        creatureNest.cleanup(runtime)
        lostMiner.cleanup(runtime)
        objectiveMarkers.cleanup(runtime)
    }

    private fun reconcileObjectiveMarkers(runtime: MineRuntime) {
        if (!abortBlockedObjective(runtime)) objectiveMarkers.reconcile(runtime)
    }

    private fun abortBlockedObjective(runtime: MineRuntime): Boolean {
        if (!objectiveMarkers.hasBlockedTarget(runtime)) return false
        clearActive(runtime)
        coordinator.abort(runtime)
        return true
    }

    /** Retire persisted pre-0.40.36 objectives whose world contract changed. */
    private fun abortIncompatibleObjective(runtime: MineRuntime): Boolean {
        val incident = runtime.state.incident ?: return false
        val objective = runtime.state.objective
        val incompatible = when (incident.type) {
            ru.ruscrafting.farms.domain.MineIncidentType.POWER_FAILURE -> true
            ru.ruscrafting.farms.domain.MineIncidentType.ORE_WORKSHOP ->
                incident.working?.placement?.floorId?.startsWith("authored-") != true
            ru.ruscrafting.farms.domain.MineIncidentType.TUNNEL_DRIVE,
            ru.ruscrafting.farms.domain.MineIncidentType.RAIL_EXTENSION,
            ru.ruscrafting.farms.domain.MineIncidentType.TRACK_DAMAGE ->
                incident.working?.placement?.geometryVersion != ru.ruscrafting.farms.domain.MineWorkingPlacement.CURRENT_GEOMETRY_VERSION
            ru.ruscrafting.farms.domain.MineIncidentType.FLOODING ->
                incident.required != 1 || objective?.targets?.size != 1
            ru.ruscrafting.farms.domain.MineIncidentType.CRYSTAL_RESONANCE ->
                objective == null || objective.targets.any {
                    val material = it.position.blockType()
                    it.status != ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus.COMPLETED &&
                        material != null && material != Material.AMETHYST_CLUSTER
                }
            else -> false
        }
        if (!incompatible) return false
        clearActive(runtime)
        coordinator.abort(runtime)
        return true
    }
}
