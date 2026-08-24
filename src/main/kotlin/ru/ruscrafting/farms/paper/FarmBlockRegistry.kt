package ru.ruscrafting.farms.paper

import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.plugin.Plugin
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.ruscrafting.farms.domain.FarmPlotPosition
import java.util.PriorityQueue

internal data class FarmBlockIndexDefinition(
    val zoneId: String,
    val region: ActivityRegion,
    val crops: Set<String>,
    val blocksPerTick: Int,
    val maxBlocks: Int,
    val maxOrchardLeaves: Int,
) {
    init {
        require(zoneId.matches(Regex("[a-z0-9_-]{1,48}"))) { "Invalid farm block index zone id" }
        require(crops.isNotEmpty()) { "Farm block index crop set is empty" }
        require(blocksPerTick in 1..65_536) { "Farm block index tick budget is invalid" }
        require(maxBlocks >= blocksPerTick) { "Farm block index size limit is invalid" }
        require(maxOrchardLeaves in 1..65_536) { "Farm orchard index limit is invalid" }
    }
}

internal enum class FarmBlockReindexPhase { SCANNING, APPLYING }

internal data class FarmBlockReindexStatus(
    val zoneId: String,
    val phase: FarmBlockReindexPhase,
    val scannedBlocks: Long,
    val totalBlocks: Long,
    val appliedChunks: Int,
    val totalChunks: Int,
    val beds: Int,
    val fixedCrops: Int,
    val orchardLeaves: Int,
)

internal data class FarmBlockReindexResult(
    val status: FarmBlockReindexStatus,
    val durationMillis: Long,
)

internal sealed interface FarmBlockReindexStart {
    data class Started(val status: FarmBlockReindexStatus) : FarmBlockReindexStart
    data class Busy(val status: FarmBlockReindexStatus) : FarmBlockReindexStart
    data class TooLarge(val blocks: Long, val maxBlocks: Int, val chunks: Long) : FarmBlockReindexStart
}

/** Owns the durable farm topology index and its bounded whole-region rebuild lifecycle. */
internal class FarmBlockRegistry(
    private val plugin: Plugin,
    private val ledger: FarmBlockLedger,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val bedsByZone = mutableMapOf<String, MutableSet<FarmPlotPosition>>()
    private val orchardLeavesByZone = mutableMapOf<String, MutableSet<FarmPlotPosition>>()
    private val jobs = mutableMapOf<String, ReindexJob>()
    private var closed = false

    fun beds(zoneId: String): Set<FarmPlotPosition> = bedsByZone[zoneId].orEmpty()

    fun orchardLeaves(zoneId: String): Set<FarmPlotPosition> = orchardLeavesByZone[zoneId].orEmpty()

    fun addBeds(zoneId: String, positions: Collection<FarmPlotPosition>) {
        if (positions.isNotEmpty()) bedsByZone.getOrPut(zoneId, ::linkedSetOf).addAll(positions)
    }

    fun removeBeds(zoneId: String, positions: Collection<FarmPlotPosition>) {
        bedsByZone[zoneId]?.let { beds ->
            beds.removeAll(positions.toSet())
            if (beds.isEmpty()) bedsByZone.remove(zoneId)
        }
    }

    fun removeOrchardLeaves(zoneId: String, positions: Collection<FarmPlotPosition>) {
        orchardLeavesByZone[zoneId]?.let { leaves ->
            leaves.removeAll(positions.toSet())
            if (leaves.isEmpty()) orchardLeavesByZone.remove(zoneId)
        }
    }

    fun isReindexing(zoneId: String): Boolean = zoneId in jobs

    fun status(zoneId: String): FarmBlockReindexStatus? = jobs[zoneId]?.status()

    fun reconcileChunk(definition: FarmBlockIndexDefinition, chunk: Chunk) {
        if (chunk.world != definition.region.world) return
        val chunkBeds = ledger.blockRecords(chunk).asSequence()
            .filter { it.zoneId == definition.zoneId && it.indexed }
            .map { FarmPlotPosition(chunk.world.name, it.x, it.y, it.z) }
            .filter { position ->
                val block = chunk.world.getBlockAt(position.x, position.y, position.z)
                definition.region.contains(block.location) && FarmBlockPolicy.isSelectableBed(
                    block.type,
                    block.getRelative(org.bukkit.block.BlockFace.UP).type,
                    definition.crops,
                )
            }.toSet()
        replaceChunkPositions(bedsByZone, definition.zoneId, chunk, chunkBeds)

        val leaves = ledger.orchardLeafRecords(chunk).asSequence()
            .filter { it.zoneId == definition.zoneId }
            .map { FarmPlotPosition(chunk.world.name, it.x, it.y, it.z) }
            .filter { position ->
                val block = chunk.world.getBlockAt(position.x, position.y, position.z)
                definition.region.contains(block.location) && FarmBlockPolicy.isOrchardLeaf(
                    block.type,
                    block.getRelative(org.bukkit.block.BlockFace.DOWN).type,
                )
            }.toSet()
        replaceChunkPositions(orchardLeavesByZone, definition.zoneId, chunk, leaves)
    }

    fun startReindex(
        definition: FarmBlockIndexDefinition,
        onProgress: (FarmBlockReindexStatus) -> Unit,
        onComplete: (FarmBlockReindexResult) -> Unit,
        onFailure: (Throwable) -> Unit,
    ): FarmBlockReindexStart {
        check(!closed) { "Farm block registry is closed" }
        jobs[definition.zoneId]?.let { return FarmBlockReindexStart.Busy(it.status()) }
        val geometry = ScanGeometry.create(definition)
        if (geometry.totalBlocks > definition.maxBlocks || geometry.totalChunks > MAX_REINDEX_CHUNKS) {
            return FarmBlockReindexStart.TooLarge(
                blocks = geometry.totalBlocks,
                maxBlocks = definition.maxBlocks,
                chunks = geometry.totalChunks,
            )
        }
        val job = ReindexJob(definition, geometry, onProgress, onComplete, onFailure)
        jobs[definition.zoneId] = job
        job.start()
        return FarmBlockReindexStart.Started(job.status())
    }

    fun clear() {
        jobs.values.toList().forEach { it.cancel() }
        jobs.clear()
        bedsByZone.clear()
        orchardLeavesByZone.clear()
    }

    override fun close() {
        if (closed) return
        closed = true
        clear()
    }

    private fun replaceChunkPositions(
        registry: MutableMap<String, MutableSet<FarmPlotPosition>>,
        zoneId: String,
        chunk: Chunk,
        replacement: Set<FarmPlotPosition>,
    ) {
        val positions = registry.getOrPut(zoneId, ::linkedSetOf)
        positions.removeIf { it.world == chunk.world.name && (it.x shr 4) == chunk.x && (it.z shr 4) == chunk.z }
        positions.addAll(replacement)
        if (positions.isEmpty()) registry.remove(zoneId)
    }

    private inner class ReindexJob(
        private val definition: FarmBlockIndexDefinition,
        private val geometry: ScanGeometry,
        private val onProgress: (FarmBlockReindexStatus) -> Unit,
        private val onComplete: (FarmBlockReindexResult) -> Unit,
        private val onFailure: (Throwable) -> Unit,
    ) {
        private val startedAt = clock()
        private val beds = mutableMapOf<Long, MutableSet<FarmPlotPosition>>()
        private val fixedCrops = mutableMapOf<Long, MutableSet<FarmPlotPosition>>()
        private val orchardSampler = BoundedOrchardSampler(definition.maxOrchardLeaves)
        private val appliedBeds = linkedSetOf<FarmPlotPosition>()
        private val appliedLeaves = linkedSetOf<FarmPlotPosition>()
        private var appliedFixedCrops = 0
        private var phase = FarmBlockReindexPhase.SCANNING
        private var chunkIndex = 0
        private var scannedBlocks = 0L
        private var bedCount = 0
        private var fixedCropCount = 0
        private var appliedChunks = 0
        private var currentChunk: Chunk? = null
        private var currentTicketAdded = false
        private var currentCursor = 0L
        private var nextTask: ScheduledTask? = null
        private var finished = false
        private var orchardByChunk: Map<Long, Set<FarmPlotPosition>> = emptyMap()

        fun status(): FarmBlockReindexStatus = FarmBlockReindexStatus(
            zoneId = definition.zoneId,
            phase = phase,
            scannedBlocks = scannedBlocks,
            totalBlocks = geometry.totalBlocks,
            appliedChunks = appliedChunks,
            totalChunks = geometry.chunks.size,
            beds = if (finished) appliedBeds.size else bedCount,
            fixedCrops = if (finished) appliedFixedCrops else fixedCropCount,
            orchardLeaves = if (finished) appliedLeaves.size else orchardSampler.size,
        )

        fun start() = requestChunk()

        fun cancel() {
            if (finished) return
            finished = true
            nextTask?.cancel()
            releaseCurrentChunk()
        }

        private fun requestChunk() {
            if (finished || closed) return
            if (chunkIndex >= geometry.chunks.size) {
                if (phase == FarmBlockReindexPhase.SCANNING) beginApply() else complete()
                return
            }
            val coordinates = geometry.chunks[chunkIndex]
            definition.region.world.getChunkAtAsync(coordinates.x, coordinates.z, false).whenComplete { chunk, failure ->
                runCatching {
                    Tasks.scheduler.runSync {
                        if (finished || closed) return@runSync
                        if (failure != null || chunk == null) {
                            fail(failure ?: IllegalStateException("Chunk ${coordinates.x},${coordinates.z} did not load"))
                            return@runSync
                        }
                        currentChunk = chunk
                        currentTicketAdded = chunk.addPluginChunkTicket(plugin)
                        currentCursor = 0L
                        if (phase == FarmBlockReindexPhase.SCANNING) scanStep() else applyChunk()
                    }
                }.onFailure(::fail)
            }
        }

        private fun scanStep() {
            if (finished) return
            val chunk = currentChunk ?: return fail(IllegalStateException("Farm reindex lost its current chunk"))
            if (currentCursor == 0L && ledger.fixedCropRecords(chunk).any {
                    it.zoneId == definition.zoneId && it.restoreAt != null
                }
            ) {
                return fail(IllegalStateException("Farm ${definition.zoneId} still has pending fixed crop restoration"))
            }
            val slice = geometry.slice(chunk.x, chunk.z)
            val end = minOf(slice.volume, currentCursor + definition.blocksPerTick)
            while (currentCursor < end) {
                val block = slice.blockAt(chunk.world, currentCursor++)
                scannedBlocks++
                val interesting = block.type == org.bukkit.Material.FARMLAND ||
                    MaterialRules.isFixedBlockCrop(block.type) || MaterialRules.isLeaf(block.type)
                if (!interesting || !definition.region.contains(block.location)) continue
                val position = FarmPlotPosition(block.world.name, block.x, block.y, block.z)
                when {
                    FarmBlockPolicy.isSelectableBed(
                        block.type,
                        block.getRelative(org.bukkit.block.BlockFace.UP).type,
                        definition.crops,
                    ) -> if (addBounded(beds, chunk.chunkKey, position, bedCount, MAX_INDEXED_BEDS, "farm beds")) {
                        bedCount++
                    }
                    MaterialRules.isFixedBlockCrop(block.type) && block.type.name in definition.crops ->
                        if (addBounded(
                                fixedCrops,
                                chunk.chunkKey,
                                position,
                                fixedCropCount,
                                MAX_INDEXED_FIXED_CROPS,
                                "fixed crops",
                            )
                        ) fixedCropCount++
                    FarmBlockPolicy.isOrchardLeaf(
                        block.type,
                        block.getRelative(org.bukkit.block.BlockFace.DOWN).type,
                    ) -> orchardSampler.add(position)
                }
            }
            if (currentCursor < slice.volume) {
                nextTask = Tasks.scheduler.runLater(1L, ::scanStep)
                return
            }
            finishCurrentChunk()
            onProgress(status())
            chunkIndex++
            requestChunk()
        }

        private fun beginApply() {
            phase = FarmBlockReindexPhase.APPLYING
            chunkIndex = 0
            orchardByChunk = orchardSampler.values().groupByTo(linkedMapOf()) { position ->
                chunkKey(position.x shr 4, position.z shr 4)
            }.mapValues { (_, positions) ->
                positions.sortedWith(compareBy(FarmPlotPosition::y, FarmPlotPosition::x, FarmPlotPosition::z))
                    .take(MAX_INDEXED_ORCHARD_LEAVES_PER_CHUNK)
                    .toSet()
            }
            onProgress(status())
            requestChunk()
        }

        private fun applyChunk() {
            if (finished) return
            val chunk = currentChunk ?: return fail(IllegalStateException("Farm reindex lost its apply chunk"))
            val key = chunk.chunkKey
            val validBeds = beds[key].orEmpty().mapNotNull { position ->
                val block = chunk.world.getBlockAt(position.x, position.y, position.z)
                block.takeIf {
                    definition.region.contains(it.location) && FarmBlockPolicy.isSelectableBed(
                        it.type,
                        it.getRelative(org.bukkit.block.BlockFace.UP).type,
                        definition.crops,
                    )
                }
            }
            validBeds.forEach(FarmBlockPolicy::makeWet)
            val validFixed = fixedCrops[key].orEmpty().mapNotNull { position ->
                chunk.world.getBlockAt(position.x, position.y, position.z).takeIf {
                    definition.region.contains(it.location) && MaterialRules.isFixedBlockCrop(it.type) &&
                        it.type.name in definition.crops
                }
            }
            val validLeaves = orchardByChunk[key].orEmpty().mapNotNull { position ->
                chunk.world.getBlockAt(position.x, position.y, position.z).takeIf {
                    definition.region.contains(it.location) && FarmBlockPolicy.isOrchardLeaf(
                        it.type,
                        it.getRelative(org.bukkit.block.BlockFace.DOWN).type,
                    )
                }
            }
            runCatching {
                ledger.replaceZoneIndex(chunk, definition.zoneId, validBeds, validFixed, validLeaves)
            }.onFailure { return fail(it) }
            validBeds.mapTo(appliedBeds) { it.toPosition() }
            validLeaves.mapTo(appliedLeaves) { it.toPosition() }
            appliedFixedCrops += validFixed.size
            appliedChunks++
            finishCurrentChunk()
            onProgress(status())
            chunkIndex++
            nextTask = Tasks.scheduler.runLater(1L, ::requestChunk)
        }

        private fun complete() {
            if (finished) return
            finished = true
            bedsByZone[definition.zoneId] = appliedBeds
            orchardLeavesByZone[definition.zoneId] = appliedLeaves
            jobs.remove(definition.zoneId)
            onComplete(FarmBlockReindexResult(status(), (clock() - startedAt).coerceAtLeast(0L)))
        }

        private fun fail(failure: Throwable) {
            if (finished) return
            finished = true
            nextTask?.cancel()
            releaseCurrentChunk()
            jobs.remove(definition.zoneId)
            onFailure(failure)
        }

        private fun finishCurrentChunk() {
            releaseCurrentChunk()
            currentCursor = 0L
        }

        private fun releaseCurrentChunk() {
            val chunk = currentChunk
            if (chunk != null && currentTicketAdded) runCatching { chunk.removePluginChunkTicket(plugin) }
            currentChunk = null
            currentTicketAdded = false
        }

        private fun addBounded(
            target: MutableMap<Long, MutableSet<FarmPlotPosition>>,
            chunkKey: Long,
            position: FarmPlotPosition,
            currentSize: Int,
            limit: Int,
            label: String,
        ): Boolean {
            if (position in target[chunkKey].orEmpty()) return false
            require(currentSize < limit) { "Farm reindex found too many $label" }
            target.getOrPut(chunkKey, ::linkedSetOf) += position
            return true
        }
    }

    private data class ChunkCoordinates(val x: Int, val z: Int)

    private data class ScanGeometry(
        val minX: Int,
        val minY: Int,
        val minZ: Int,
        val maxX: Int,
        val maxY: Int,
        val maxZ: Int,
        val chunks: List<ChunkCoordinates>,
        val totalBlocks: Long,
        val totalChunks: Long,
    ) {
        fun slice(chunkX: Int, chunkZ: Int): ChunkSlice = ChunkSlice(
            minX = maxOf(minX, chunkX shl 4),
            minY = minY,
            minZ = maxOf(minZ, chunkZ shl 4),
            maxX = minOf(maxX, (chunkX shl 4) + 15),
            maxY = maxY,
            maxZ = minOf(maxZ, (chunkZ shl 4) + 15),
        )

        companion object {
            fun create(definition: FarmBlockIndexDefinition): ScanGeometry {
                val bounds = definition.region.bounds
                val minY = maxOf(bounds.minY, definition.region.world.minHeight)
                val maxY = minOf(bounds.maxY, definition.region.world.maxHeight - 1)
                require(minY <= maxY) { "Farm region ${definition.zoneId} is outside the world height" }
                val width = bounds.maxX.toLong() - bounds.minX + 1L
                val height = maxY.toLong() - minY + 1L
                val depth = bounds.maxZ.toLong() - bounds.minZ + 1L
                val total = saturatedMultiply(saturatedMultiply(width, height), depth)
                val minChunkX = bounds.minX shr 4
                val maxChunkX = bounds.maxX shr 4
                val minChunkZ = bounds.minZ shr 4
                val maxChunkZ = bounds.maxZ shr 4
                val totalChunks = saturatedMultiply(
                    maxChunkX.toLong() - minChunkX + 1L,
                    maxChunkZ.toLong() - minChunkZ + 1L,
                )
                val chunks = if (totalChunks <= MAX_REINDEX_CHUNKS) buildList {
                    for (chunkX in minChunkX..maxChunkX) {
                        for (chunkZ in minChunkZ..maxChunkZ) add(ChunkCoordinates(chunkX, chunkZ))
                    }
                } else emptyList()
                return ScanGeometry(
                    bounds.minX,
                    minY,
                    bounds.minZ,
                    bounds.maxX,
                    maxY,
                    bounds.maxZ,
                    chunks,
                    total,
                    totalChunks,
                )
            }

            private fun saturatedMultiply(left: Long, right: Long): Long = runCatching {
                Math.multiplyExact(left, right)
            }.getOrDefault(Long.MAX_VALUE)
        }
    }

    private data class ChunkSlice(
        val minX: Int,
        val minY: Int,
        val minZ: Int,
        val maxX: Int,
        val maxY: Int,
        val maxZ: Int,
    ) {
        private val height = maxY - minY + 1
        private val depth = maxZ - minZ + 1
        val volume: Long = (maxX - minX + 1L) * height * depth

        fun blockAt(world: World, index: Long): Block {
            val yOffset = (index % height).toInt()
            val column = index / height
            val zOffset = (column % depth).toInt()
            val xOffset = (column / depth).toInt()
            return world.getBlockAt(minX + xOffset, minY + yOffset, minZ + zOffset)
        }
    }

    private class BoundedOrchardSampler(private val limit: Int) {
        private data class Ranked(val score: Long, val position: FarmPlotPosition)

        private val order = compareBy<Ranked>(Ranked::score)
            .thenBy { it.position.world }
            .thenBy { it.position.x }
            .thenBy { it.position.y }
            .thenBy { it.position.z }
        private val selected = PriorityQueue(order.reversed())
        private val positions = hashSetOf<FarmPlotPosition>()

        val size: Int get() = selected.size

        fun add(position: FarmPlotPosition) {
            if (!positions.add(position)) return
            val ranked = Ranked(spatialScore(position), position)
            if (selected.size < limit) {
                selected += ranked
                return
            }
            val largest = selected.peek()
            if (order.compare(ranked, largest) >= 0) {
                positions.remove(position)
                return
            }
            selected.remove().also { positions.remove(it.position) }
            selected += ranked
        }

        fun values(): Set<FarmPlotPosition> = selected.mapTo(linkedSetOf(), Ranked::position)

        private fun spatialScore(position: FarmPlotPosition): Long {
            var value = position.world.hashCode().toLong()
            value = value xor (position.x.toLong() * -7046029254386353131L)
            value = java.lang.Long.rotateLeft(value, 21) xor (position.y.toLong() * -4658895280553007687L)
            return java.lang.Long.rotateLeft(value, 17) xor (position.z.toLong() * -7723592293110705685L)
        }
    }

    private companion object {
        const val MAX_REINDEX_CHUNKS = 4_096L
        const val MAX_INDEXED_BEDS = 100_000
        const val MAX_INDEXED_FIXED_CROPS = 100_000
        const val MAX_INDEXED_ORCHARD_LEAVES_PER_CHUNK = 1_024

        fun chunkKey(x: Int, z: Int): Long = (x.toLong() and 0xffffffffL) or (z.toLong() shl 32)

        fun Block.toPosition(): FarmPlotPosition = FarmPlotPosition(world.name, x, y, z)
    }
}
