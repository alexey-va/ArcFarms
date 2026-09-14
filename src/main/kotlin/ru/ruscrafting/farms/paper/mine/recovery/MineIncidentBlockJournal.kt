package ru.ruscrafting.farms.paper.mine.recovery

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Material
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
        val record = PendingMineBlock(
            id = "mine-incident:${runtime.settings.id}:${runtime.state.sequence}:$incidentId:$ordinal",
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
        return recovery.prepare(record, block, original) { block.setType(temporary, false) }
    }

    fun prepareAll(
        runtime: MineRuntime,
        incidentId: String,
        placements: List<Pair<Int, WorksitePosition>>,
        temporary: Material,
    ): CompletableFuture<Boolean> {
        val mutations = placements.map { (ordinal, position) ->
            val world = Bukkit.getWorld(position.world) ?: return CompletableFuture.completedFuture(false)
            if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) return CompletableFuture.completedFuture(false)
            val block = world.getBlockAt(position.x, position.y, position.z)
            val original = block.type
            val record = PendingMineBlock(
                id = "mine-incident:${runtime.settings.id}:${runtime.state.sequence}:$incidentId:$ordinal",
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
            MineBlockMutation(record, block, original) { block.setType(temporary, false) }
        }
        return recovery.prepareAll(mutations)
    }

    fun positions(runtime: MineRuntime, incidentId: String): List<WorksitePosition> {
        val prefix = "mine-incident:${runtime.settings.id}:${runtime.state.sequence}:$incidentId:"
        return recovery.records(runtime.settings.id).filter { it.id.startsWith(prefix) }.map {
            WorksitePosition(it.world, it.x, it.y, it.z)
        }
    }

    fun restore(runtime: MineRuntime, incidentId: String): Int {
        val positions = positions(runtime, incidentId)
        positions.forEach { position ->
            val world = Bukkit.getWorld(position.world) ?: return@forEach
            if (world.isChunkLoaded(position.x shr 4, position.z shr 4)) {
                recovery.restoreNow(world.getBlockAt(position.x, position.y, position.z))
            }
        }
        return positions.size
    }

    fun restoreNow(position: WorksitePosition): CompletableFuture<Boolean> {
        val world = Bukkit.getWorld(position.world) ?: return CompletableFuture.completedFuture(false)
        if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) return CompletableFuture.completedFuture(false)
        return recovery.restoreNow(world.getBlockAt(position.x, position.y, position.z))
    }

    fun ensureTemporary(position: WorksitePosition, temporary: Material): Boolean =
        recovery.ensureTemporary(position, temporary)

    /** Restores journalled incident blocks whose owning incident state did not survive an abrupt stop. */
    fun restoreOrphans(runtimes: Collection<MineRuntime>, chunk: Chunk? = null): Int {
        val active = runtimes.mapNotNull { runtime ->
            runtime.state.incident?.let { incident ->
                "mine-incident:${runtime.settings.id}:${runtime.state.sequence}:${incident.type.name.lowercase()}:"
            }
        }
        var restored = 0
        recovery.records().filter { record ->
            record.id.startsWith("mine-incident:") && active.none(record.id::startsWith) &&
                (chunk == null || record.world == chunk.world.name && record.x shr 4 == chunk.x && record.z shr 4 == chunk.z)
        }.forEach { record ->
            val world = Bukkit.getWorld(record.world) ?: return@forEach
            if (!world.isChunkLoaded(record.x shr 4, record.z shr 4)) return@forEach
            recovery.restoreNow(world.getBlockAt(record.x, record.y, record.z))
            restored++
        }
        return restored
    }
}
