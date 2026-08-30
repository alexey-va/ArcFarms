package ru.ruscrafting.farms.paper.mine.incident

import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDeathEvent
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
    private val scheduler: MineIncidentScheduler,
) {
    fun tick(runtime: MineRuntime, now: Long, onlineParticipants: Int) {
        scheduler.tick(runtime, now, onlineParticipants)
        caveIn.reconcile(runtime)
        trackDamage.reconcile(runtime)
        flooding.reconcile(runtime)
        powerFailure.reconcile(runtime)
        creatureNest.reconcileMissing(runtime)
        lostMiner.reconcileMissing(runtime)
    }

    fun onInteract(event: PlayerInteractEvent): Boolean =
        caveIn.onInteract(event) || trackDamage.onInteract(event) || gasLeak.onInteract(event) ||
            crystalResonance.onInteract(event) || flooding.onInteract(event) || powerFailure.onInteract(event)

    fun onMove(to: Location, player: Player): Boolean = lostMiner.onMove(to, player)

    fun onInteractEntity(event: PlayerInteractEntityEvent): Boolean = lostMiner.onInteractEntity(event)

    fun onEntityDeath(event: EntityDeathEvent): Boolean = creatureNest.onDeath(event)

    fun releasePlayer(playerId: UUID) {
        caveIn.releasePlayer(playerId)
        trackDamage.releasePlayer(playerId)
        flooding.releasePlayer(playerId)
        lostMiner.releasePlayer(playerId)
    }

    fun isActive(identity: ServiceItemIdentity): Boolean =
        caveIn.isActive(identity) || trackDamage.isActive(identity) || flooding.isActive(identity)

    fun release(playerId: UUID, identity: ServiceItemIdentity, reason: WorksitePlayerReleaseReason) {
        caveIn.release(playerId, identity, reason)
        trackDamage.release(playerId, identity, reason)
        flooding.release(playerId, identity, reason)
    }

    fun reconcileChunk(runtime: MineRuntime, chunk: Chunk) {
        creatureNest.reconcileChunk(runtime, chunk)
        lostMiner.reconcileChunk(runtime, chunk)
    }

    fun cleanup() {
        registry.snapshot().forEach { runtime ->
            runtime.state.incident?.serviceLeases?.values?.toSet().orEmpty().forEach(::releasePlayer)
        }
        scheduler.cleanup()
        registry.snapshot().forEach { runtime ->
            creatureNest.cleanup(runtime)
            lostMiner.cleanup(runtime)
        }
    }
}
