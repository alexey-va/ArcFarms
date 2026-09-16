package ru.ruscrafting.farms.paper.mine

import org.bukkit.Chunk
import org.bukkit.World
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.mine.index.MineChunkLoader
import ru.ruscrafting.farms.paper.mine.index.MineChunkTicket
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.UUID

/** Keeps the area MyWorlds validates before a mine teleport resident without blocking the tick thread. */
internal class MineWorldWarmup(
    private val tickets: MineChunkTicket,
    private val tasks: WorksiteTaskPort,
    private val chunkLoader: MineChunkLoader = MineChunkLoader { world, chunkX, chunkZ ->
        world.getChunkAtAsync(chunkX, chunkZ, false)
    },
    private val onFailure: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val retained = linkedMapOf<ChunkKey, Chunk>()
    private var generation = 0L

    fun activate(worlds: Collection<World>) {
        generation++
        releaseRetained()
        val activation = generation
        val token = tasks.lifecycleToken()
        worlds.distinctBy(World::getUID).forEach { world ->
            val centerX = world.spawnLocation.blockX shr 4
            val centerZ = world.spawnLocation.blockZ shr 4
            for (chunkX in centerX - RADIUS..centerX + RADIUS) {
                for (chunkZ in centerZ - RADIUS..centerZ + RADIUS) {
                    request(world, chunkX, chunkZ, activation, token, attempt = 1)
                }
            }
        }
    }

    fun cleanup() {
        generation++
        releaseRetained()
    }

    private fun releaseRetained() {
        retained.values.forEach(tickets::release)
        retained.clear()
    }

    private fun request(
        world: World,
        chunkX: Int,
        chunkZ: Int,
        activation: Long,
        token: RuntimeTaskSupervisor.Token,
        attempt: Int,
    ) {
        val future = runCatching { chunkLoader.load(world, chunkX, chunkZ) }.getOrElse { failure ->
            retryOrLog(world, chunkX, chunkZ, activation, token, attempt, failure)
            return
        }
        future.whenComplete { chunk, failure ->
            tasks.runSync(token) {
                if (activation != generation) return@runSync
                val expected = ChunkKey(world.uid, chunkX, chunkZ)
                if (failure != null) {
                    retryOrLog(world, chunkX, chunkZ, activation, token, attempt, failure)
                    return@runSync
                }
                if (chunk == null || ChunkKey(chunk.world.uid, chunk.x, chunk.z) != expected) {
                    retryOrLog(world, chunkX, chunkZ, activation, token, attempt, null)
                    return@runSync
                }
                if (expected !in retained && tickets.retain(chunk)) retained[expected] = chunk
            }
        }
    }

    private fun retryOrLog(
        world: World,
        chunkX: Int,
        chunkZ: Int,
        activation: Long,
        token: RuntimeTaskSupervisor.Token,
        attempt: Int,
        failure: Throwable?,
    ) {
        if (activation != generation) return
        if (attempt < MAX_ATTEMPTS && tasks.runLater(token, RETRY_DELAY_TICKS) {
                if (activation == generation) request(world, chunkX, chunkZ, activation, token, attempt + 1)
            }
        ) return
        val reason = if (failure == null) "returned the wrong chunk" else "failed"
        onFailure("Mine warmup $reason for ${world.name} [$chunkX,$chunkZ] after $attempt attempts", failure)
    }

    private data class ChunkKey(val worldId: UUID, val x: Int, val z: Int)

    private companion object {
        const val RADIUS = 3
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_TICKS = 20L
    }
}
