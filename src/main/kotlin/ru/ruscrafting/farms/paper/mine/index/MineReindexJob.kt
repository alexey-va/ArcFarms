package ru.ruscrafting.farms.paper.mine.index

import org.bukkit.Chunk
import org.bukkit.World
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.WorksiteTickBudget
import java.util.concurrent.CompletableFuture

internal interface MineChunkTicket {
    fun retain(chunk: Chunk): Boolean
    fun release(chunk: Chunk)
}

internal data class MineReindexTick(val finished: Boolean, val scannedBlocks: Long, val indexedTargets: Int)

/** Paper's async chunk loader; tests can complete it without a main-thread wait. */
internal fun interface MineChunkLoader {
    fun load(world: World, chunkX: Int, chunkZ: Int): CompletableFuture<Chunk?>
}

/** Explicit budgeted admin scan. PDC is published only after every retained chunk validates. */
internal class MineReindexJob(
    private val definition: MineIndexDefinition,
    private val index: MineBlockIndex,
    private val tickets: MineChunkTicket,
    private val chunkLoader: MineChunkLoader = MineChunkLoader { world, chunkX, chunkZ ->
        world.getChunkAtAsync(chunkX, chunkZ, false)
    },
) {
    private val slices = slices(definition.region.bounds)
    private val targets = linkedSetOf<MineIndexedTarget>()
    private val visitedChunks = mutableListOf<Chunk>()
    private var sliceIndex = 0
    private var cursor = 0L
    private var currentChunk: Chunk? = null
    private var retained = false
    private var pendingChunk: CompletableFuture<Chunk?>? = null
    private val haloChunks = linkedMapOf<ChunkKey, Chunk>()
    private val haloRetained = linkedSetOf<ChunkKey>()
    private var haloIndex = 0
    private var pendingHalo: PendingHalo? = null
    private var scanned = 0L
    private var finished = false

    fun tick(blockBudget: Int): MineReindexTick {
        require(blockBudget in 1..262_144)
        if (finished) return status()
        val budget = WorksiteTickBudget(blockBudget)
        try {
            while (sliceIndex < slices.size) {
                val slice = slices[sliceIndex]
                if (budget.stoppedByTime || budget.remainingOperations <= 0 || !ensureChunk(slice)) break
                while (cursor < slice.volume && budget.tryConsume()) {
                    val block = slice.blockAt(definition.region.world, cursor++)
                    scanned++
                    if (definition.region.contains(block.location)) {
                        val roles = MineAnchorClassifier.classify(block, definition.mineable, definition.railMaterials)
                        if (roles.isNotEmpty()) {
                            require(targets.size < MineBlockIndex.MAX_TARGETS_PER_ZONE) {
                                "Mine index target limit exceeded"
                            }
                            targets += MineIndexedTarget(WorksitePosition(block.world.name, block.x, block.y, block.z), roles)
                        }
                    }
                }
                if (cursor < slice.volume) break
                finishChunk()
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
        pendingChunk = null
        pendingHalo = null
        releaseCurrent()
    }

    /** Returns false while the one outstanding async request is still pending. */
    private fun ensureChunk(slice: Slice): Boolean {
        if (currentChunk == null) {
            pendingChunk?.let { future ->
                if (!future.isDone) return false
                pendingChunk = null
                if (future.isCompletedExceptionally || future.isCancelled) {
                    throw IllegalStateException("Chunk ${slice.chunkX},${slice.chunkZ} did not load")
                }
                val chunk = future.getNow(null)
                    ?: throw IllegalStateException("Chunk ${slice.chunkX},${slice.chunkZ} did not load")
                prepareChunk(chunk)
            } ?: run {
                val world = definition.region.world
                if (world.isChunkLoaded(slice.chunkX, slice.chunkZ)) {
                    prepareChunk(world.getChunkAt(slice.chunkX, slice.chunkZ))
                } else {
                    pendingChunk = chunkLoader.load(world, slice.chunkX, slice.chunkZ)
                    return false
                }
            }
        }
        return ensureHalo(slice)
    }

    /** Retain the current chunk and its in-region cardinal neighbours while classifying boundaries. */
    private fun ensureHalo(slice: Slice): Boolean {
        val neighbours = haloCoordinates(slice)
        val world = definition.region.world
        while (haloIndex < neighbours.size) {
            val key = neighbours[haloIndex]
            val pending = pendingHalo
            val chunk = if (pending != null) {
                if (!pending.future.isDone) return false
                pendingHalo = null
                check(!pending.future.isCompletedExceptionally && !pending.future.isCancelled) {
                    "Mine index neighbour ${key.x},${key.z} did not load"
                }
                pending.future.getNow(null)
                    ?: error("Mine index neighbour ${key.x},${key.z} did not load")
            } else if (world.isChunkLoaded(key.x, key.z)) {
                world.getChunkAt(key.x, key.z)
            } else {
                pendingHalo = PendingHalo(key, chunkLoader.load(world, key.x, key.z))
                return false
            }
            check(chunk.world === world && chunk.x == key.x && chunk.z == key.z) {
                "Mine index neighbour loader returned a different chunk"
            }
            haloChunks[key] = chunk
            if (tickets.retain(chunk)) haloRetained += key
            haloIndex++
        }
        return true
    }

    private fun prepareChunk(chunk: Chunk) {
        currentChunk = chunk
        retained = tickets.retain(chunk)
        visitedChunks += chunk
        haloChunks.clear()
        haloRetained.clear()
        haloIndex = 0
        pendingHalo = null
    }

    private fun finishChunk() {
        releaseCurrent()
        cursor = 0L
        sliceIndex++
    }

    private fun releaseCurrent() {
        haloRetained.toList().forEach { key -> haloChunks[key]?.let(tickets::release) }
        haloRetained.clear()
        haloChunks.clear()
        val chunk = currentChunk
        if (chunk != null && retained) tickets.release(chunk)
        retained = false
        currentChunk = null
        haloIndex = 0
        pendingHalo = null
    }

    private fun haloCoordinates(slice: Slice): List<ChunkKey> {
        val bounds = definition.region.bounds
        val chunkXRange = (bounds.minX shr 4)..(bounds.maxX shr 4)
        val chunkZRange = (bounds.minZ shr 4)..(bounds.maxZ shr 4)
        return listOf(
            ChunkKey(slice.chunkX - 1, slice.chunkZ),
            ChunkKey(slice.chunkX + 1, slice.chunkZ),
            ChunkKey(slice.chunkX, slice.chunkZ - 1),
            ChunkKey(slice.chunkX, slice.chunkZ + 1),
        ).filter { it.x in chunkXRange && it.z in chunkZRange }
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

    private data class ChunkKey(val x: Int, val z: Int)

    private data class PendingHalo(val key: ChunkKey, val future: CompletableFuture<Chunk?>)

    private companion object {
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
