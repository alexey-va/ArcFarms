package ru.ruscrafting.farms.paper.lumber.incident.load

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEntityEvent
import ru.ruscrafting.farms.domain.LumberIncidentType
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetPool
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.lumber.LumberRuntime
import ru.ruscrafting.farms.paper.lumber.LumberRuntimeRegistry
import ru.ruscrafting.farms.paper.lumber.incident.LumberIncidentCoordinator
import ru.ruscrafting.farms.paper.lumber.skidding.LumberBundleEffects
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import java.util.UUID

internal class LumberLostLoadIncident(
    private val registry: LumberRuntimeRegistry,
    private val incidents: LumberIncidentCoordinator,
    private val effects: LumberBundleEffects,
    private val state: WorksiteStatePort,
    private val clock: () -> Long,
) {
    private data class Carry(val zoneId: String, val sequence: Long, val targetId: String)
    private val carried = mutableMapOf<UUID, Carry>()

    fun start(runtime: LumberRuntime, required: Int, now: Long): Boolean {
        val started = incidents.start(
            runtime,
            LumberIncidentType.LOST_LOAD,
            required,
            now,
            candidates(runtime, required * 4),
        )
        if (started) reconcile(runtime)
        return started
    }

    fun pickup(runtime: LumberRuntime, targetId: String, player: Player): Boolean {
        if (!active(runtime) || player.uniqueId in carried) return false
        val objective = runtime.state.objective ?: return false
        val leased = ObjectiveTargetPool.lease(objective, targetId, player.uniqueId, clock())
        if (!leased.accepted) return false
        runtime.state = runtime.state.copy(objective = leased.state)
        carried[player.uniqueId] = Carry(runtime.settings.id, runtime.state.sequence, targetId)
        effects.hideGround(runtime, targetId)
        effects.showCarried(runtime, targetId, player)
        state.persistAsync()
        return true
    }

    fun deliver(runtime: LumberRuntime, player: Player): Boolean {
        val carry = carried[player.uniqueId] ?: return false
        if (carry.zoneId != runtime.settings.id || carry.sequence != runtime.state.sequence) return false
        carried.remove(player.uniqueId)
        effects.hideCarried(player.uniqueId)
        val result = incidents.completeTarget(runtime, carry.targetId, player)
        if (active(runtime)) reconcile(runtime) else effects.cleanupZone(runtime.settings.id)
        return result.accepted
    }

    fun releasePlayer(playerId: UUID): Boolean {
        val carry = carried.remove(playerId) ?: return false
        effects.hideCarried(playerId)
        val runtime = registry.byId(carry.zoneId) ?: return true
        val objective = runtime.state.objective ?: return true
        val released = ObjectiveTargetPool.release(objective, playerId)
        if (released.accepted) {
            runtime.state = runtime.state.copy(objective = released.state)
            released.state.target(carry.targetId)?.let { effects.showGround(runtime, it.id, it.position) }
            state.persistAsync()
        }
        return true
    }

    fun onInteractEntity(event: PlayerInteractEntityEvent): Boolean {
        val identity = effects.identity(event.rightClicked) ?: return false
        val runtime = registry.byId(identity.zoneId) ?: return false
        if (!active(runtime) || runtime.state.sequence != identity.sequence) return false
        event.isCancelled = true
        pickup(runtime, identity.targetId, event.player)
        return true
    }

    fun onMove(to: Location, player: Player): Boolean {
        val carry = carried[player.uniqueId] ?: return false
        val runtime = registry.byId(carry.zoneId) ?: return false
        if (runtime.station.contains(to)) return deliver(runtime, player)
        if (!runtime.region.contains(to) && !runtime.station.contains(to)) return releasePlayer(player.uniqueId)
        return false
    }

    fun reconcile(runtime: LumberRuntime) {
        if (!active(runtime)) return
        runtime.state.objective?.targets.orEmpty().forEach { target ->
            if (target.status == ObjectiveTargetStatus.AVAILABLE) effects.showGround(runtime, target.id, target.position)
            else effects.hideGround(runtime, target.id)
        }
    }

    fun updateCarried() = carried.keys.toList().forEach { playerId ->
        val player = Bukkit.getPlayer(playerId)
        if (player == null || !player.isOnline) releasePlayer(playerId) else effects.moveCarried(player)
    }

    fun cleanup() {
        registry.snapshot().forEach { effects.cleanupZone(it.settings.id) }
        carried.clear()
    }

    private fun active(runtime: LumberRuntime): Boolean =
        runtime.state.phase == LumberPhase.INCIDENT && runtime.state.incident?.type == LumberIncidentType.LOST_LOAD

    private fun candidates(runtime: LumberRuntime, limit: Int): List<ObjectiveTargetCandidate> {
        val bounds = runtime.region.bounds
        val positions = mutableListOf<WorksitePosition>()
        var inspected = 0
        scan@ for (y in bounds.minY..bounds.maxY) for (x in bounds.minX..bounds.maxX) for (z in bounds.minZ..bounds.maxZ) {
            if (++inspected > MAX_SCAN_BLOCKS) break@scan
            val block = runtime.region.world.getBlockAt(x, y, z)
            if (block.isPassable && block.getRelative(0, 1, 0).isPassable && block.getRelative(0, -1, 0).type.isOccluding) {
                positions += WorksitePosition(runtime.region.world.name, x, y, z)
                if (positions.size >= limit) break@scan
            }
        }
        return positions.mapIndexed { index, position ->
            ObjectiveTargetCandidate("lost_load_${index + 1}", position, ObjectiveTargetRole("lost_load"), index.toLong())
        }
    }

    private companion object {
        const val MAX_SCAN_BLOCKS = 250_000
    }
}
