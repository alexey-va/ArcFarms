package ru.ruscrafting.farms.paper.mine

import org.bukkit.Chunk
import org.bukkit.World
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
                    val expected = ChunkKey(world.uid, chunkX, chunkZ)
                    chunkLoader.load(world, chunkX, chunkZ).whenComplete { chunk, failure ->
                        tasks.runSync(token) {
                            if (activation != generation) return@runSync
                            if (failure != null) {
                                onFailure("Mine warmup failed for ${world.name} [$chunkX,$chunkZ]", failure)
                                return@runSync
                            }
                            if (chunk == null || ChunkKey(chunk.world.uid, chunk.x, chunk.z) != expected) {
                                onFailure("Mine warmup returned the wrong chunk for ${world.name} [$chunkX,$chunkZ]", null)
                                return@runSync
                            }
                            if (expected !in retained && tickets.retain(chunk)) retained[expected] = chunk
                        }
                    }
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

    private data class ChunkKey(val worldId: UUID, val x: Int, val z: Int)

    private companion object {
        const val RADIUS = 3
    }
}
