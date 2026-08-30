package ru.ruscrafting.farms.paper.mine.incident.creature

import org.bukkit.Chunk
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDeathEvent
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator
import ru.ruscrafting.farms.paper.mine.incident.entity.MineIncidentEntityEffects
import ru.ruscrafting.farms.paper.mine.incident.entity.MineIncidentEntityKind
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex

internal class MineCreatureNestIncident(
    private val registry: MineRuntimeRegistry,
    private val index: MineBlockIndex,
    private val incidents: MineIncidentCoordinator,
    private val effects: MineIncidentEntityEffects,
) {
    private val entities = mutableMapOf<String, MutableMap<String, java.util.UUID>>()

    fun start(runtime: MineRuntime, required: Int, now: Long): Boolean {
        val candidates = candidates(runtime)
        if (candidates.size < required * runtime.rules().targetMultiplier) return false
        if (!incidents.start(runtime, MineIncidentType.CREATURE_NEST, required, now, candidates)) return false
        runtime.region.world.loadedChunks.forEach { reconcileChunk(runtime, it) }
        return true
    }

    fun defeat(runtime: MineRuntime, targetId: String, player: Player): Boolean {
        if (!active(runtime)) return false
        entities[key(runtime)]?.remove(targetId)?.let(effects::remove)
        val completed = incidents.completeTarget(runtime, targetId, player).accepted
        if (completed && !active(runtime)) cleanup(runtime)
        return completed
    }

    fun onDeath(event: EntityDeathEvent): Boolean {
        val identity = effects.identity(event.entity) ?: return false
        if (identity.kind != MineIncidentEntityKind.CREATURE) return false
        val runtime = registry.byId(identity.zoneId) ?: return false
        if (!active(runtime) || runtime.state.sequence != identity.sequence) return false
        event.drops.clear()
        event.droppedExp = 0
        val killer = event.entity.killer
        if (killer != null) defeat(runtime, identity.targetId, killer)
        return true
    }

    fun reconcileChunk(runtime: MineRuntime, chunk: Chunk): Int {
        if (!active(runtime)) return 0
        val expected = runtime.state.objective?.targets.orEmpty()
            .filter { it.status != ObjectiveTargetStatus.COMPLETED }
            .associate { it.id to it.position }
        val reconciled = effects.reconcileChunk(runtime, chunk, MineIncidentEntityKind.CREATURE, expected)
        val tracked = entities.getOrPut(key(runtime), ::linkedMapOf)
        tracked.entries.removeIf { (_, id) -> effects.entity(id) == null }
        tracked.putAll(reconciled)
        return tracked.size
    }

    fun spawnedCount(runtime: MineRuntime): Int = entities[key(runtime)]?.size ?: 0

    fun cleanup(runtime: MineRuntime) {
        entities.remove(key(runtime))?.values?.forEach(effects::remove)
        effects.cleanup(runtime, MineIncidentEntityKind.CREATURE)
    }

    private fun active(runtime: MineRuntime): Boolean =
        runtime.state.phase == MinePhase.INCIDENT && runtime.state.incident?.type == MineIncidentType.CREATURE_NEST

    private fun candidates(runtime: MineRuntime): List<ObjectiveTargetCandidate> =
        index.loadedTargets(runtime.settings.id, MineAnchorRole.NEST)
            .filter { index.isLiveTarget(runtime.settings.id, it, MineAnchorRole.NEST) }
            .mapIndexed { order, position ->
                ObjectiveTargetCandidate("nest_${order + 1}", position, ObjectiveTargetRole("creature_nest"), order.toLong())
            }

    private fun key(runtime: MineRuntime) = "${runtime.settings.id}:${runtime.state.sequence}"
}
