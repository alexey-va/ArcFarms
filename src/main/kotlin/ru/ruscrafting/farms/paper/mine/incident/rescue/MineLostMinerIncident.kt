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
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import java.util.UUID

internal class MineLostMinerIncident(
    private val registry: MineRuntimeRegistry,
    private val index: MineBlockIndex,
    private val incidents: MineIncidentCoordinator,
    private val effects: MineIncidentEntityEffects,
    private val deliveryPoint: (MineRuntime) -> WorksitePosition?,
) {
    private val miners = mutableMapOf<String, UUID>()
    private val escorts = mutableMapOf<UUID, String>()

    fun start(runtime: MineRuntime, now: Long): Boolean {
        val candidates = candidates(runtime)
        if (candidates.size < runtime.rules().targetMultiplier) return false
        if (!incidents.start(runtime, MineIncidentType.LOST_MINER, 1, now, candidates)) return false
        runtime.region.world.loadedChunks.forEach { reconcileChunk(runtime, it) }
        return true
    }

    fun onInteractEntity(event: PlayerInteractEntityEvent): Boolean {
        val identity = effects.identity(event.rightClicked) ?: return false
        if (identity.kind != MineIncidentEntityKind.MINER) return false
        val runtime = registry.byId(identity.zoneId) ?: return false
        if (!active(runtime) || runtime.state.sequence != identity.sequence) return false
        event.isCancelled = true
        return beginEscort(runtime, event.player)
    }

    fun beginEscort(runtime: MineRuntime, player: Player): Boolean {
        if (!active(runtime) || key(runtime) !in miners) return false
        escorts[player.uniqueId] = key(runtime)
        return true
    }

    fun onMove(to: Location, player: Player): Boolean {
        val runtimeKey = escorts[player.uniqueId] ?: return false
        val runtime = registry.snapshot().firstOrNull { key(it) == runtimeKey } ?: return escorts.remove(player.uniqueId) != null
        if (!active(runtime)) return escorts.remove(player.uniqueId) != null
        val destination = deliveryPoint(runtime) ?: return false
        if (near(to, destination, 2.5)) return complete(runtime, player)
        val miner = miners[key(runtime)]?.let(effects::entity)
        if (miner != null && miner.world === to.world && miner.location.distanceSquared(to) > 36.0) {
            miner.teleport(to.clone().add(0.0, 0.0, -1.0))
        }
        return false
    }

    fun complete(runtime: MineRuntime, player: Player): Boolean {
        val targetId = runtime.state.objective?.targets?.firstOrNull()?.id ?: return false
        val completed = incidents.completeTarget(runtime, targetId, player).accepted
        if (completed) cleanup(runtime)
        return completed
    }

    fun releasePlayer(playerId: UUID): Boolean = escorts.remove(playerId) != null

    fun reconcileChunk(runtime: MineRuntime, chunk: Chunk): Int {
        if (!active(runtime)) return 0
        val target = runtime.state.objective?.targets?.firstOrNull() ?: return 0
        val reconciled = effects.reconcileChunk(
            runtime, chunk, MineIncidentEntityKind.MINER, mapOf(target.id to target.position),
        )
        reconciled[target.id]?.let { miners[key(runtime)] = it }
        miners[key(runtime)]?.takeIf { effects.entity(it) == null }?.let { miners.remove(key(runtime)) }
        return if (key(runtime) in miners) 1 else 0
    }

    fun canonicalCount(runtime: MineRuntime): Int = if (miners[key(runtime)]?.let(effects::entity) != null) 1 else 0

    fun cleanup(runtime: MineRuntime) {
        miners.remove(key(runtime))?.let(effects::remove)
        escorts.entries.removeIf { it.value == key(runtime) }
        effects.cleanup(runtime, MineIncidentEntityKind.MINER)
    }

    private fun active(runtime: MineRuntime): Boolean =
        runtime.state.phase == MinePhase.INCIDENT && runtime.state.incident?.type == MineIncidentType.LOST_MINER

    private fun candidates(runtime: MineRuntime): List<ObjectiveTargetCandidate> =
        index.loadedTargets(runtime.settings.id, MineAnchorRole.MINER)
            .filter { index.isLiveTarget(runtime.settings.id, it, MineAnchorRole.MINER) }
            .mapIndexed { order, position ->
                ObjectiveTargetCandidate("lost_miner_${order + 1}", position, ObjectiveTargetRole("lost_miner"), order.toLong())
            }

    private fun near(location: Location, position: WorksitePosition, radius: Double): Boolean {
        if (location.world.name != position.world) return false
        val dx = location.x - (position.x + 0.5)
        val dy = location.y - (position.y + 1.0)
        val dz = location.z - (position.z + 0.5)
        return dx * dx + dy * dy + dz * dz <= radius * radius
    }

    private fun key(runtime: MineRuntime) = "${runtime.settings.id}:${runtime.state.sequence}"
}
