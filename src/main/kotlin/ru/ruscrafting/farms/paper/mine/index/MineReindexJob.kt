package ru.ruscrafting.farms.paper.mine.index

import org.bukkit.Chunk
import org.bukkit.World
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.domain.worksite.WorksitePosition

internal interface MineChunkTicket {
    fun retain(chunk: Chunk): Boolean
    fun release(chunk: Chunk)
}

internal data class MineReindexTick(val finished: Boolean, val scannedBlocks: Long, val indexedTargets: Int)

/** Explicit budgeted admin scan. PDC is published only after every retained chunk validates. */
internal class MineReindexJob(
    private val definition: MineIndexDefinition,
    private val index: MineBlockIndex,
    private val tickets: MineChunkTicket,
) {
    private val slices = slices(definition.region.bounds)
    private val targets = linkedSetOf<MineIndexedTarget>()
    private val visitedChunks = mutableListOf<Chunk>()
    private var sliceIndex = 0
    private var cursor = 0L
    private var currentChunk: Chunk? = null
    private var retained = false
    private var scanned = 0L
    private var finished = false

    fun tick(blockBudget: Int): MineReindexTick {
        require(blockBudget in 1..262_144)
        if (finished) return status()
        var remaining = blockBudget
        try {
            while (remaining > 0 && sliceIndex < slices.size) {
                val slice = slices[sliceIndex]
                ensureChunk(slice)
                val count = minOf(remaining.toLong(), slice.volume - cursor).toInt()
                repeat(count) {
                    val block = slice.blockAt(definition.region.world, cursor++)
                    scanned++
                    if (!definition.region.contains(block.location)) return@repeat
                    val roles = MineAnchorClassifier.classify(block, definition.mineable)
                    if (roles.isEmpty()) return@repeat
                    require(targets.size < MAX_TARGETS) { "Mine index target limit exceeded" }
                    targets += MineIndexedTarget(WorksitePosition(block.world.name, block.x, block.y, block.z), roles)
                }
                remaining -= count
                if (cursor >= slice.volume) finishChunk()
            }
            if (sliceIndex >= slices.size) {
                finished = true
                index.replaceZone(definition, visitedChunks, targets)
            }
            return status()
        } catch (failure: Throwable) {
            releaseCurrent()
            throw failure
        }
    }

    fun cancel() {
        if (finished) return
        finished = true
        releaseCurrent()
    }

    private fun ensureChunk(slice: Slice) {
        if (currentChunk != null) return
        currentChunk = definition.region.world.getChunkAt(slice.chunkX, slice.chunkZ).also { chunk ->
            retained = tickets.retain(chunk)
            visitedChunks += chunk
        }
    }

    private fun finishChunk() {
        releaseCurrent()
        cursor = 0L
        sliceIndex++
    }

    private fun releaseCurrent() {
        val chunk = currentChunk ?: return
        if (retained) tickets.release(chunk)
        retained = false
        currentChunk = null
    }

    private fun status() = MineReindexTick(finished, scanned, targets.size)

    private data class Slice(
        val chunkX: Int, val chunkZ: Int,
        val minX: Int, val minY: Int, val minZ: Int,
        val maxX: Int, val maxY: Int, val maxZ: Int,
    ) {
        private val width = maxX - minX + 1
        private val depth = maxZ - minZ + 1
        val volume: Long = width.toLong() * (maxY - minY + 1) * depth

        fun blockAt(world: World, index: Long): org.bukkit.block.Block {
            val x = minX + (index % width).toInt()
            val yz = index / width
            val z = minZ + (yz % depth).toInt()
            val y = minY + (yz / depth).toInt()
            return world.getBlockAt(x, y, z)
        }
    }

    private companion object {
        const val MAX_TARGETS = 150_000
        fun slices(bounds: CuboidBounds): List<Slice> = buildList {
            for (chunkX in (bounds.minX shr 4)..(bounds.maxX shr 4)) {
                for (chunkZ in (bounds.minZ shr 4)..(bounds.maxZ shr 4)) {
                    add(
                        Slice(
                            chunkX, chunkZ,
                            maxOf(bounds.minX, chunkX shl 4), bounds.minY, maxOf(bounds.minZ, chunkZ shl 4),
                            minOf(bounds.maxX, (chunkX shl 4) + 15), bounds.maxY, minOf(bounds.maxZ, (chunkZ shl 4) + 15),
                        ),
                    )
                }
            }
        }
    }
}
