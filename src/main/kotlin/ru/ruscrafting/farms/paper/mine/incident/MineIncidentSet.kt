package ru.ruscrafting.farms.paper.mine.incident

import org.bukkit.Chunk
import org.bukkit.Location
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
import ru.ruscrafting.farms.paper.mine.incident.track.MineTrackDamageIncident
import ru.ruscrafting.farms.paper.mine.recovery.MineIncidentBlockJournal
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
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
    private val coordinator: MineIncidentCoordinator,
    private val scheduler: MineIncidentScheduler,
    private val journal: MineIncidentBlockJournal,
) {
    /** Farm-style admin switch: retire the current scene before forcing the requested incident. */
    fun forceAdmin(runtime: MineRuntime, type: ru.ruscrafting.farms.domain.MineIncidentType, now: Long): Boolean {
        if (runtime.state.phase == ru.ruscrafting.farms.domain.MinePhase.INCIDENT || runtime.state.incident != null) {
            clearActive(runtime)
            if (!coordinator.abort(runtime)) return false
        }
        return scheduler.force(runtime, type, now)
    }

    fun tick(runtime: MineRuntime, now: Long, onlineParticipants: Int) {
        scheduler.tick(runtime, now, onlineParticipants)
        caveIn.reconcile(runtime)
        trackDamage.reconcile(runtime)
        flooding.reconcile(runtime)
        powerFailure.reconcile(runtime)
        creatureNest.reconcileMissing(runtime)
        lostMiner.reconcileMissing(runtime)
    }

    fun onInteract(event: PlayerInteractEvent): Boolean {
        return trackDamage.onInteract(event) || gasLeak.onInteract(event) ||
            crystalResonance.onInteract(event) || flooding.onInteract(event) || powerFailure.onInteract(event)
    }

    fun onBreak(event: BlockBreakEvent): Boolean = caveIn.onBreak(event)

    fun onMove(to: Location, player: Player): Boolean = lostMiner.onMove(to, player)

    fun onInteractEntity(event: PlayerInteractEntityEvent): Boolean = lostMiner.onInteractEntity(event)

    fun onEntityDeath(event: EntityDeathEvent): Boolean = creatureNest.onDeath(event)

    fun onEntityDamage(event: EntityDamageEvent): Boolean = creatureNest.onDamage(event)

    fun releasePlayer(playerId: UUID) {
        trackDamage.releasePlayer(playerId)
        flooding.releasePlayer(playerId)
        lostMiner.releasePlayer(playerId)
    }

    fun isActive(identity: ServiceItemIdentity): Boolean =
        trackDamage.isActive(identity) || flooding.isActive(identity)

    fun release(playerId: UUID, identity: ServiceItemIdentity, reason: WorksitePlayerReleaseReason) {
        trackDamage.release(playerId, identity, reason)
        flooding.release(playerId, identity, reason)
    }

    fun reconcileChunk(runtime: MineRuntime, chunk: Chunk) {
        caveIn.reconcile(runtime)
        flooding.reconcile(runtime)
        powerFailure.reconcile(runtime)
        creatureNest.reconcileChunk(runtime, chunk)
        lostMiner.reconcileChunk(runtime, chunk)
    }

    fun reconcileRecovery(chunk: Chunk? = null): Int {
        if (chunk == null) registry.snapshot().forEach { runtime ->
            caveIn.reconcile(runtime)
            flooding.reconcile(runtime)
            powerFailure.reconcile(runtime)
        }
        return journal.restoreOrphans(registry.snapshot(), chunk)
    }

    fun cleanup() {
        registry.snapshot().forEach { runtime ->
            runtime.state.incident?.serviceLeases?.values?.toSet().orEmpty().forEach(::releasePlayer)
        }
        scheduler.cleanup()
        registry.snapshot().forEach { runtime ->
            caveIn.cleanup(runtime)
            creatureNest.cleanup(runtime)
            lostMiner.cleanup(runtime)
        }
    }

    private fun clearActive(runtime: MineRuntime) {
        runtime.state.incident?.serviceLeases?.values?.toSet().orEmpty().forEach(::releasePlayer)
        runtime.state.incident?.type?.name?.lowercase()?.let { journal.restore(runtime, it) }
        caveIn.cleanup(runtime)
        creatureNest.cleanup(runtime)
        lostMiner.cleanup(runtime)
    }
}
