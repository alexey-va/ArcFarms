package ru.ruscrafting.farms.paper.mine.recovery

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Material
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.PendingMineBlock
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime
import java.util.concurrent.CompletableFuture

/** Namespaced incident mutations over the durable mine block journal. */
internal class MineIncidentBlockJournal(
    private val recovery: MineBlockRecoveryController,
) {
    fun prepare(
        runtime: MineRuntime,
        incidentId: String,
        ordinal: Int,
        position: WorksitePosition,
        temporary: Material,
    ): CompletableFuture<Boolean> {
        val world = Bukkit.getWorld(position.world) ?: return CompletableFuture.completedFuture(false)
        if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) return CompletableFuture.completedFuture(false)
        val block = world.getBlockAt(position.x, position.y, position.z)
        val original = block.type
        val objectiveNonce = runtime.state.incident?.objectiveNonce ?: 0L
        val record = PendingMineBlock(
            id = incidentRecordId(runtime, incidentId, objectiveNonce, ordinal),
            zoneId = runtime.settings.id,
            world = position.world,
            x = position.x,
            y = position.y,
            z = position.z,
            originalMaterial = original.name,
            temporaryMaterial = temporary.name,
            nextMaterial = original.name,
            restoreAt = Long.MAX_VALUE,
        )
        return recovery.prepare(
            record,
            block,
            original,
            stillValid = incidentStillValid(runtime, incidentId, runtime.state.sequence, objectiveNonce),
        ) { block.setType(temporary, false) }
    }

    fun prepareAll(
        runtime: MineRuntime,
        incidentId: String,
        placements: List<Pair<Int, WorksitePosition>>,
        temporary: Material,
    ): CompletableFuture<Boolean> {
        val sequence = runtime.state.sequence
        val objectiveNonce = runtime.state.incident?.objectiveNonce ?: 0L
        val stillValid = incidentStillValid(runtime, incidentId, sequence, objectiveNonce)
        val mutations = placements.map { (ordinal, position) ->
            val world = Bukkit.getWorld(position.world) ?: return CompletableFuture.completedFuture(false)
            if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) return CompletableFuture.completedFuture(false)
            val block = world.getBlockAt(position.x, position.y, position.z)
            val original = block.type
            val record = PendingMineBlock(
                id = incidentRecordId(runtime, incidentId, objectiveNonce, ordinal),
                zoneId = runtime.settings.id,
                world = position.world,
                x = position.x,
                y = position.y,
                z = position.z,
                originalMaterial = original.name,
                temporaryMaterial = temporary.name,
                nextMaterial = original.name,
                restoreAt = Long.MAX_VALUE,
            )
            MineBlockMutation(record, block, original, stillValid) { block.setType(temporary, false) }
        }
        return recovery.prepareAll(mutations)
    }

    fun nextOrdinal(runtime: MineRuntime, incidentId: String): Int {
        val prefix = incidentPrefix(runtime.settings.id, runtime.state.sequence, incidentId,
            runtime.state.incident?.objectiveNonce ?: 0L)
        return (recovery.records(runtime.settings.id).asSequence().filter { it.id.startsWith(prefix) }
            .mapNotNull { it.id.removePrefix(prefix).toIntOrNull() }.maxOrNull() ?: -1) + 1
    }

    fun positions(runtime: MineRuntime, incidentId: String): List<WorksitePosition> {
        val records = recovery.records(runtime.settings.id)
        val currentPrefix = incidentPrefix(runtime.settings.id, runtime.state.sequence, incidentId,
            runtime.state.incident?.objectiveNonce ?: 0L)
        val current = records.filter { it.id.startsWith(currentPrefix) }
        val legacy = records.filter {
            isLegacyIncidentRecord(it.id, legacyIncidentPrefix(runtime.settings.id, runtime.state.sequence, incidentId))
        }
        return (current + legacy).distinctBy(PendingMineBlock::positionKey).map {
            WorksitePosition(it.world, it.x, it.y, it.z)
        }
    }

    fun restore(runtime: MineRuntime, incidentId: String): Int {
        return restore(runtime.settings.id, runtime.state.sequence, incidentId, runtime.state.incident?.objectiveNonce ?: 0L)
    }

    fun restore(zoneId: String, sequence: Long, incidentId: String, nonce: Long): Int {
        val prefix = incidentPrefix(zoneId, sequence, incidentId, nonce)
        val legacy = legacyIncidentPrefix(zoneId, sequence, incidentId)
        val records = recovery.records(zoneId).filter { it.id.startsWith(prefix) || isLegacyIncidentRecord(it.id, legacy) }
        records.forEach(recovery::requestRestore)
        return records.size
    }

    fun restoreNow(position: WorksitePosition): CompletableFuture<Boolean> {
        val world = Bukkit.getWorld(position.world) ?: return CompletableFuture.completedFuture(false)
        if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) return CompletableFuture.completedFuture(false)
        return recovery.restoreNow(world.getBlockAt(position.x, position.y, position.z))
    }

    fun ensureTemporary(position: WorksitePosition, temporary: Material): Boolean =
        recovery.ensureTemporary(position, temporary)

    fun ensureTemporaryResult(position: WorksitePosition, temporary: Material): MineTemporaryEnsureResult =
        recovery.ensureTemporaryResult(position, temporary)

    /** Completes incident failure handling on the Paper thread when the lifecycle is active. */
    fun runOnMain(task: () -> Unit): Boolean = recovery.runOnMain(task)

    /** Restores journalled incident blocks whose owning incident state did not survive an abrupt stop. */
    fun restoreOrphans(runtimes: Collection<MineRuntime>, chunk: Chunk? = null): Int {
        val activeCurrent = runtimes.mapNotNull { runtime ->
            runtime.state.incident?.let { incident ->
                incidentPrefix(runtime.settings.id, runtime.state.sequence, incident.type.name.lowercase(), incident.objectiveNonce)
            }
        }
        val activeLegacy = runtimes.mapNotNull { runtime ->
            runtime.state.incident?.let { incident ->
                legacyIncidentPrefix(runtime.settings.id, runtime.state.sequence, incident.type.name.lowercase())
            }
        }
        var restored = 0
        recovery.records().filter { record ->
            record.id.startsWith("mine-incident:") &&
                activeCurrent.none(record.id::startsWith) &&
                activeLegacy.none { isLegacyIncidentRecord(record.id, it) } &&
                (chunk == null || record.world == chunk.world.name && record.x shr 4 == chunk.x && record.z shr 4 == chunk.z)
        }.forEach { record ->
            recovery.requestRestore(record)
            restored++
        }
        return restored
    }

    private fun incidentStillValid(
        runtime: MineRuntime,
        incidentId: String,
        sequence: Long,
        objectiveNonce: Long,
    ): () -> Boolean = {
        val incident = runtime.state.incident
            runtime.state.phase == MinePhase.INCIDENT &&
            runtime.state.sequence == sequence &&
            incident?.type?.name?.equals(incidentId, ignoreCase = true) == true &&
            incident?.objectiveNonce == objectiveNonce
    }

    private fun incidentRecordId(runtime: MineRuntime, incidentId: String, objectiveNonce: Long, ordinal: Int): String =
        "${incidentPrefix(runtime.settings.id, runtime.state.sequence, incidentId, objectiveNonce)}$ordinal"

    private fun incidentPrefix(zoneId: String, sequence: Long, incidentId: String, objectiveNonce: Long): String =
        "mine-incident:$zoneId:$sequence:$incidentId:$objectiveNonce:"

    private fun legacyIncidentPrefix(zoneId: String, sequence: Long, incidentId: String): String =
        "mine-incident:$zoneId:$sequence:$incidentId:"

    private fun isLegacyIncidentRecord(recordId: String, legacyPrefix: String): Boolean =
        recordId.startsWith(legacyPrefix) && !recordId.removePrefix(legacyPrefix).contains(':')
}
