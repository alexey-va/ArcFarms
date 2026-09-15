package ru.ruscrafting.farms.paper.mine.incident.entity

import org.bukkit.Chunk
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime

/** One recoverable glowing physical marker per block-interaction objective. */
internal class MineObjectiveMarkerScene(private val effects: MineIncidentEntityEffects) {
    private val activeScenes = mutableMapOf<String, Pair<Long, MineIncidentEntityKind>>()

    fun reconcile(runtime: MineRuntime): Int {
        val kind = activeKind(runtime)
        if (kind == null) {
            if (activeScenes.remove(runtime.settings.id) != null) cleanupEntities(runtime)
            return 0
        }
        val identity = runtime.state.sequence to kind
        if (activeScenes[runtime.settings.id] != identity) cleanupEntities(runtime)
        activeScenes[runtime.settings.id] = identity
        val expected = expected(runtime)
        expected.values.map { it.x shr 4 to (it.z shr 4) }.distinct().forEach { (chunkX, chunkZ) ->
            if (runtime.region.world.isChunkLoaded(chunkX, chunkZ)) {
                effects.reconcileChunk(runtime, runtime.region.world.getChunkAt(chunkX, chunkZ), kind, expected)
            }
        }
        return expected.size
    }

    fun reconcileChunk(runtime: MineRuntime, chunk: Chunk) {
        val kind = activeKind(runtime) ?: return
        effects.reconcileChunk(runtime, chunk, kind, expected(runtime))
    }

    fun cleanup(runtime: MineRuntime) {
        activeScenes.remove(runtime.settings.id)
        cleanupEntities(runtime)
    }

    private fun cleanupEntities(runtime: MineRuntime) = OWNED_KINDS.forEach { effects.cleanup(runtime, it) }

    private fun expected(runtime: MineRuntime): Map<String, WorksitePosition> = runtime.state.objective?.targets.orEmpty()
        .filter { it.status != ObjectiveTargetStatus.COMPLETED }
        .associate { it.id to it.position }

    private fun activeKind(runtime: MineRuntime): MineIncidentEntityKind? {
        if (runtime.state.phase != MinePhase.INCIDENT) return null
        return when (runtime.state.incident?.type) {
            MineIncidentType.GAS_LEAK -> MineIncidentEntityKind.GAS_MARKER
            MineIncidentType.CRYSTAL_RESONANCE -> MineIncidentEntityKind.CRYSTAL_MARKER
            MineIncidentType.FLOODING -> MineIncidentEntityKind.FLOOD_MARKER
            MineIncidentType.POWER_FAILURE -> MineIncidentEntityKind.POWER_MARKER
            else -> null
        }
    }

    private companion object {
        val OWNED_KINDS = setOf(
            MineIncidentEntityKind.GAS_MARKER,
            MineIncidentEntityKind.CRYSTAL_MARKER,
            MineIncidentEntityKind.FLOOD_MARKER,
            MineIncidentEntityKind.POWER_MARKER,
        )
    }
}
