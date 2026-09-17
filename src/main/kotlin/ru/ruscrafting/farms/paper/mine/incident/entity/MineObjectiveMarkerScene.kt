package ru.ruscrafting.farms.paper.mine.incident.entity

import org.bukkit.Chunk
import org.bukkit.entity.Entity
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime

/** One recoverable glowing physical marker per block-interaction objective. */
internal class MineObjectiveMarkerScene(private val effects: MineIncidentEntityEffects) {
    private val activeScenes = mutableMapOf<String, Pair<Long, MineIncidentEntityKind>>()
    private val activeChunks = mutableMapOf<String, Set<Pair<Int, Int>>>()
    private val idleCleanupZones = mutableSetOf<String>()

    fun reconcile(runtime: MineRuntime): Int {
        val kind = activeKind(runtime)
        if (kind == null) {
            activeScenes.remove(runtime.settings.id)
            activeChunks.remove(runtime.settings.id)
            if (idleCleanupZones.add(runtime.settings.id)) cleanupEntities(runtime)
            return 0
        }
        idleCleanupZones.remove(runtime.settings.id)
        val identity = runtime.state.sequence to kind
        if (activeScenes[runtime.settings.id] != identity) {
            activeChunks.remove(runtime.settings.id)
            cleanupEntities(runtime)
        }
        activeScenes[runtime.settings.id] = identity
        val expected = expected(runtime)
        val currentChunks = chunksAround(expected.values)
        val chunksToScan = activeChunks[runtime.settings.id].orEmpty() + currentChunks
        reconcileChunks(runtime, kind, expected, chunksToScan)
        reconcileChunks(runtime, requireNotNull(kind.objectiveMarkerHitboxKind), expected, chunksToScan)
        activeChunks[runtime.settings.id] = currentChunks
        return expected.size
    }

    fun reconcileChunk(runtime: MineRuntime, chunk: Chunk) {
        val kind = activeKind(runtime)
        if (kind == null) {
            idleCleanupZones.add(runtime.settings.id)
            cleanupEntities(runtime)
            return
        }
        val expected = expected(runtime)
        reconcileChunks(runtime, kind, expected, chunksAround(chunk))
        reconcileChunks(runtime, requireNotNull(kind.objectiveMarkerHitboxKind), expected, chunksAround(chunk))
    }

    fun cleanup(runtime: MineRuntime) {
        activeScenes.remove(runtime.settings.id)
        activeChunks.remove(runtime.settings.id)
        idleCleanupZones.remove(runtime.settings.id)
        cleanupEntities(runtime)
    }

    fun identity(entity: Entity): MineIncidentEntityIdentity? = effects.identity(entity)

    fun hasBlockedTarget(runtime: MineRuntime): Boolean =
        activeKind(runtime) != null && expected(runtime).values.any(::isMineObjectiveMarkerBlocked)

    private fun cleanupEntities(runtime: MineRuntime) = OWNED_KINDS.forEach { effects.cleanup(runtime, it) }

    /** Reconcile both sides of a chunk boundary, retaining one identity per target. */
    private fun reconcileChunks(
        runtime: MineRuntime,
        kind: MineIncidentEntityKind,
        expected: Map<String, WorksitePosition>,
        chunks: Set<Pair<Int, Int>>,
    ) {
        val canonical = linkedMapOf<String, java.util.UUID>()
        chunks.sortedWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second }).forEach { (chunkX, chunkZ) ->
            if (!runtime.region.world.isChunkLoaded(chunkX, chunkZ)) return@forEach
            effects.reconcileChunk(runtime, runtime.region.world.getChunkAt(chunkX, chunkZ), kind, expected)
                .forEach { (targetId, entityId) ->
                    val previous = canonical.putIfAbsent(targetId, entityId)
                    if (previous != null && previous != entityId) effects.remove(entityId)
                }
        }
    }

    private fun chunksAround(positions: Collection<WorksitePosition>): Set<Pair<Int, Int>> = positions
        .flatMap { position ->
            (-1..1).flatMap { dx -> (-1..1).map { dz -> (position.x shr 4) + dx to (position.z shr 4) + dz } }
        }
        .toSet()

    private fun chunksAround(chunk: Chunk): Set<Pair<Int, Int>> =
        (-1..1).flatMap { dx -> (-1..1).map { dz -> chunk.x + dx to chunk.z + dz } }.toSet()

    private fun expected(runtime: MineRuntime): Map<String, WorksitePosition> = runtime.state.objective?.targets.orEmpty()
        .filter { it.status != ObjectiveTargetStatus.COMPLETED }
        .associate { it.id to it.position }

    private fun activeKind(runtime: MineRuntime): MineIncidentEntityKind? {
        if (runtime.state.phase != MinePhase.INCIDENT) return null
        return when (runtime.state.incident?.type) {
            MineIncidentType.GAS_LEAK -> MineIncidentEntityKind.GAS_MARKER
            // Amethyst buds/clusters are the physical affordance; never shadow them with
            // a synthetic display or hitbox.
            MineIncidentType.CRYSTAL_RESONANCE -> null
            MineIncidentType.FLOODING -> MineIncidentEntityKind.FLOOD_MARKER
            MineIncidentType.POWER_FAILURE -> MineIncidentEntityKind.POWER_MARKER
            else -> null
        }
    }

    private companion object {
        val OWNED_KINDS = setOf(
            MineIncidentEntityKind.GAS_MARKER,
            MineIncidentEntityKind.GAS_MARKER_HITBOX,
            MineIncidentEntityKind.CRYSTAL_MARKER,
            MineIncidentEntityKind.CRYSTAL_MARKER_HITBOX,
            MineIncidentEntityKind.FLOOD_MARKER,
            MineIncidentEntityKind.FLOOD_MARKER_HITBOX,
            MineIncidentEntityKind.POWER_MARKER,
            MineIncidentEntityKind.POWER_MARKER_HITBOX,
        )
    }
}
