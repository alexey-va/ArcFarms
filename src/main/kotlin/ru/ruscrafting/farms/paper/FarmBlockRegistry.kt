package ru.ruscrafting.farms.paper

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf

import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.Ageable
import org.bukkit.plugin.Plugin
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.ruscrafting.farms.config.FarmCropLayoutSettings
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.worksite.WorksiteDeterministicSeed
import java.util.PriorityQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort

internal data class FarmBlockIndexDefinition(
    val zoneId: String,
    val region: ActivityRegion,
    val crops: Set<String>,
    val blocksPerTick: Int,
    val maxBlocks: Int,
    val maxOrchardLeaves: Int,
    val cropLayout: FarmCropLayoutSettings,
) {
    init {
        require(zoneId.matches(Regex("[a-z0-9_-]{1,48}"))) { "Invalid farm block index zone id" }
        require(crops.isNotEmpty()) { "Farm block index crop set is empty" }
        require(blocksPerTick in 1..262_144) { "Farm block index tick budget is invalid" }
        require(maxBlocks >= blocksPerTick) { "Farm block index size limit is invalid" }
        require(maxOrchardLeaves in 1..65_536) { "Farm orchard index limit is invalid" }
        require(cropLayout.weights.keys.all(crops::contains)) {
            "Farm crop layout contains a crop outside the farm crop set"
        }
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
internal interface FarmHarvestCropIndex {
    fun beds(zoneId: String): Set<FarmPlotPosition>
    fun fixedCrops(zoneId: String): Set<FarmPlotPosition>
}

internal class FarmBlockRegistry(
    private val plugin: Plugin,
    private val ledger: FarmBlockLedger,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nanoTime: () -> Long = System::nanoTime,
    private val addChunkTicket: (Chunk) -> Boolean = { chunk -> chunk.addPluginChunkTicket(plugin) },
    private val removeChunkTicket: (Chunk) -> Unit = { chunk -> chunk.removePluginChunkTicket(plugin) },
    private val tasks: WorksiteTaskPort? = null,
) : FarmHarvestCropIndex, AutoCloseable {
    private val bedsByZone = mutableMapOf<String, FarmTopologyIndex>()
    private val fixedCropsByZone = mutableMapOf<String, FarmTopologyIndex>()
    private val orchardLeavesByZone = mutableMapOf<String, FarmTopologyIndex>()
    private val jobs = mutableMapOf<String, ReindexJob>()
    private var closed = false
    private var epoch = 0L
    private val pendingReconcile = mutableMapOf<Triple<String, java.util.UUID, Long>, CompletableFuture<Unit>>()
    private val reconcileTails = mutableMapOf<String, CompletableFuture<Unit>>()
    private val reconcileCounts = mutableMapOf<String, Int>()

    override fun beds(zoneId: String): Set<FarmPlotPosition> = bedsByZone[zoneId]?.positions.orEmpty()

    override fun fixedCrops(zoneId: String): Set<FarmPlotPosition> = fixedCropsByZone[zoneId]?.positions.orEmpty()

    fun orchardLeaves(zoneId: String): Set<FarmPlotPosition> = orchardLeavesByZone[zoneId]?.positions.orEmpty()

    fun addBeds(zoneId: String, positions: Collection<FarmPlotPosition>) {
        if (positions.isNotEmpty()) bedsByZone[zoneId] = (bedsByZone[zoneId] ?: FarmTopologyIndex()).add(positions)
    }

    fun addBedsAsync(zoneId: String, positions: Collection<FarmPlotPosition>, isCurrent: () -> Boolean): CompletableFuture<Unit> {
        return changeBedsAsync(zoneId, positions, false, isCurrent)
    }

    fun removeBedsAsync(zoneId: String, positions: Collection<FarmPlotPosition>, isCurrent: () -> Boolean): CompletableFuture<Unit> {
        return changeBedsAsync(zoneId, positions, true, isCurrent)
    }

    private fun changeBedsAsync(zoneId: String, positions: Collection<FarmPlotPosition>, removing: Boolean,
        isCurrent: () -> Boolean): CompletableFuture<Unit> {
        val work = tasks ?: return CompletableFuture.completedFuture(Unit).thenApply {
            if (removing) removeBeds(zoneId, positions) else addBeds(zoneId, positions)
        }
        if (positions.isEmpty()) return CompletableFuture.completedFuture(Unit)
        val token = work.lifecycleToken()
        val expectedEpoch = epoch
        fun merge(): CompletableFuture<Unit> {
            if (closed || epoch != expectedEpoch || !isCurrent()) return CompletableFuture.failedFuture(
                CancellationException("Farm bed index source changed"))
            val source = bedsByZone[zoneId]
            return work.compute {
                (source ?: FarmTopologyIndex()).let { if (removing) it.remove(positions) else it.add(positions) }
            }.thenCompose { next ->
                work.callSync(token) {
                    if (closed || epoch != expectedEpoch || !isCurrent()) throw CancellationException("Farm bed index source changed")
                    if (bedsByZone[zoneId] !== source) merge() else {
                        bedsByZone[zoneId] = next
                        CompletableFuture.completedFuture(Unit)
                    }
                }.thenCompose { it }
            }
        }
        return merge()
    }

    fun removeBeds(zoneId: String, positions: Collection<FarmPlotPosition>) {
        bedsByZone[zoneId]?.let { beds -> bedsByZone[zoneId] = beds.remove(positions) }
    }

    fun removeOrchardLeaves(zoneId: String, positions: Collection<FarmPlotPosition>) {
        orchardLeavesByZone[zoneId]?.let { leaves -> orchardLeavesByZone[zoneId] = leaves.remove(positions) }
    }

    fun isReindexing(zoneId: String): Boolean = zoneId in jobs || (reconcileCounts[zoneId] ?: 0) > 0

    fun status(zoneId: String): FarmBlockReindexStatus? = jobs[zoneId]?.status()

    fun reconcileChunk(definition: FarmBlockIndexDefinition, chunk: Chunk) {
        if (tasks != null) { reconcileChunkAsync(definition, chunk); return }
        if (chunk.world != definition.region.world) return
        val records = ledger.blockRecords(chunk)
        val interruptedTemporaryBlocks = records.asSequence()
            .filter { it.zoneId == definition.zoneId && it.indexed && it.temporaryMutation != null }
            .map { chunk.world.getBlockAt(it.x, it.y, it.z) }
            .toList()
        ledger.restoreTemporaryRemovals(interruptedTemporaryBlocks)
        val chunkBeds = records.asSequence()
            .filter { it.zoneId == definition.zoneId && it.indexed }
            .map { FarmPlotPosition(chunk.world.name, it.x, it.y, it.z) }
            .filter { position ->
                val block = chunk.world.getBlockAt(position.x, position.y, position.z)
                definition.region.contains(block.location) && FarmBlockPolicy.isRecoverableIndexedBed(
                    block.type,
                    block.getRelative(org.bukkit.block.BlockFace.UP).type,
                    definition.crops,
                )
            }.toSet()
        replaceChunkPositions(bedsByZone, definition.zoneId, chunk, chunkBeds)

        val fixedCrops = ledger.fixedCropRecords(chunk).asSequence()
            .filter { it.zoneId == definition.zoneId && it.restoreAt == null }
            .map { FarmPlotPosition(chunk.world.name, it.x, it.y, it.z) }
            .filter { position ->
                val block = chunk.world.getBlockAt(position.x, position.y, position.z)
                definition.region.contains(block.location) && MaterialRules.isFixedBlockCrop(block.type) &&
                    block.type.name in definition.crops
            }.toSet()
        replaceChunkPositions(fixedCropsByZone, definition.zoneId, chunk, fixedCrops)

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

    fun reconcileChunkAsync(definition: FarmBlockIndexDefinition, chunk: Chunk): CompletableFuture<Unit> {
        val work = tasks ?: return runCatching { reconcileChunk(definition, chunk); CompletableFuture.completedFuture(Unit) }
            .getOrElse { CompletableFuture.failedFuture(it) }
        if (chunk.world !== definition.region.world || closed) return CompletableFuture.completedFuture(Unit)
        val key = Triple(definition.zoneId, chunk.world.uid, chunk.chunkKey)
        pendingReconcile[key]?.let { return it }
        val token = work.lifecycleToken()
        val expectedEpoch = epoch
        val result = CompletableFuture<Unit>()
        pendingReconcile[key] = result
        reconcileCounts[definition.zoneId] = (reconcileCounts[definition.zoneId] ?: 0) + 1
        val previous = reconcileTails.put(definition.zoneId, result) ?: CompletableFuture.completedFuture(Unit)
        fun current() = !closed && epoch == expectedEpoch && pendingReconcile[key] === result &&
            definition.zoneId !in jobs && chunk.world.isChunkLoaded(chunk.x, chunk.z) &&
            chunk.world.getChunkAt(chunk.x, chunk.z) === chunk
        previous.handle { _, _ -> Unit }.thenCompose { work.callSync(token) {
            if (!current()) throw CancellationException("Farm chunk reconciliation source changed")
            ledger.restoreTemporaryAsync(chunk, definition.zoneId, work, ::current)
                .thenCompose { work.callSync(token) { ledger.loadAsync(chunk, work, ::current) }.thenCompose { it } }
                .thenCompose { loaded -> work.compute {
                    loaded to Triple(loaded.contents.blocks.filter { it.zoneId == definition.zoneId && it.indexed }.iterator(),
                        loaded.contents.fixed.filter { it.zoneId == definition.zoneId && it.restoreAt == null }.iterator(),
                        loaded.contents.leaves.filter { it.zoneId == definition.zoneId }.iterator())
                } }.thenCompose { (loaded, records) -> work.callSync(token) {
                    val (beds, fixed, leaves) = records
                    fun currentRecords() = current() && ledger.isCurrent(chunk, loaded)
                    val chunkBeds = linkedSetOf<FarmPlotPosition>()
                    val chunkFixed = linkedSetOf<FarmPlotPosition>()
                    val chunkLeaves = linkedSetOf<FarmPlotPosition>()
                    work.capture(token, ::currentRecords) {
                        when {
                            beds.hasNext() -> beds.next().let { record ->
                                val block = chunk.world.getBlockAt(record.x, record.y, record.z)
                                if (definition.region.contains(block.location) && FarmBlockPolicy.isRecoverableIndexedBed(
                                        block.type, block.getRelative(org.bukkit.block.BlockFace.UP).type, definition.crops))
                                    chunkBeds += block.toPosition()
                            }
                            fixed.hasNext() -> fixed.next().let { record ->
                                val block = chunk.world.getBlockAt(record.x, record.y, record.z)
                                if (definition.region.contains(block.location) && MaterialRules.isFixedBlockCrop(block.type) &&
                                    block.type.name in definition.crops) chunkFixed += block.toPosition()
                            }
                            leaves.hasNext() -> leaves.next().let { record ->
                                val block = chunk.world.getBlockAt(record.x, record.y, record.z)
                                if (definition.region.contains(block.location) && FarmBlockPolicy.isOrchardLeaf(
                                        block.type, block.getRelative(org.bukkit.block.BlockFace.DOWN).type)) chunkLeaves += block.toPosition()
                            }
                        }
                        !beds.hasNext() && !fixed.hasNext() && !leaves.hasNext()
                    }.thenCompose {
                        val coordinates = Triple(chunk.world.name, chunk.x, chunk.z)
                        fun publish(): CompletableFuture<Unit> {
                            val previousBeds = bedsByZone[definition.zoneId]
                            val previousFixed = fixedCropsByZone[definition.zoneId]
                            val previousLeaves = orchardLeavesByZone[definition.zoneId]
                            return work.compute { Triple((previousBeds ?: FarmTopologyIndex()).replace(coordinates, chunkBeds),
                            (previousFixed ?: FarmTopologyIndex()).replace(coordinates, chunkFixed),
                            (previousLeaves ?: FarmTopologyIndex()).replace(coordinates, chunkLeaves)) }
                            .thenCompose { (nextBeds, nextFixed, nextLeaves) -> work.callSync(token) {
                                if (!currentRecords()) throw CancellationException("Farm journal changed while reconciling")
                                if (bedsByZone[definition.zoneId] !== previousBeds || fixedCropsByZone[definition.zoneId] !== previousFixed ||
                                    orchardLeavesByZone[definition.zoneId] !== previousLeaves) publish() else {
                                    bedsByZone[definition.zoneId] = nextBeds
                                    fixedCropsByZone[definition.zoneId] = nextFixed
                                    orchardLeavesByZone[definition.zoneId] = nextLeaves
                                    CompletableFuture.completedFuture(Unit)
                                }
                            }.thenCompose { it } }
                        }
                        publish()
                    }
                }.thenCompose { it } }
        }.thenCompose { it } }.whenComplete { _, failure ->
            val delivered = work.runSync(token) {
                val wasCurrent = current()
                if (pendingReconcile[key] === result) {
                    pendingReconcile.remove(key)
                    reconcileCounts[definition.zoneId] = (reconcileCounts[definition.zoneId] ?: 1) - 1
                    if (reconcileTails[definition.zoneId] === result) reconcileTails.remove(definition.zoneId)
                }
                if (failure == null) result.complete(Unit) else {
                    result.completeExceptionally(failure)
                    if (wasCurrent) plugin.logger.log(java.util.logging.Level.SEVERE, "Could not reconcile farm chunk ${chunk.x},${chunk.z}", failure)
                }
            }
            if (!delivered) result.completeExceptionally(CancellationException("Farm registry retired"))
        }
        return result
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

    fun cancelReindexes() {
        jobs.values.toList().forEach { it.cancel() }
        jobs.clear()
    }

    fun clear() {
        epoch++
        pendingReconcile.values.forEach { it.completeExceptionally(CancellationException("Farm registry cleared")) }
        pendingReconcile.clear()
        reconcileTails.clear()
        reconcileCounts.clear()
        cancelReindexes()
        bedsByZone.clear()
        fixedCropsByZone.clear()
        orchardLeavesByZone.clear()
    }

    override fun close() {
        if (closed) return
        closed = true
        clear()
    }

    private fun replaceChunkPositions(
        registry: MutableMap<String, FarmTopologyIndex>, zoneId: String, chunk: Chunk, replacement: Set<FarmPlotPosition>,
    ) {
        registry[zoneId] = (registry[zoneId] ?: FarmTopologyIndex())
            .replace(Triple(chunk.world.name, chunk.x, chunk.z), replacement)
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
        private var appliedBeds = FarmTopologyIndex()
        private var appliedFixedCropPositions = FarmTopologyIndex()
        private var appliedLeaves = FarmTopologyIndex()
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
        private var cropLayoutByBed: Map<FarmPlotPosition, String> = emptyMap()

        fun status(): FarmBlockReindexStatus = FarmBlockReindexStatus(
            zoneId = definition.zoneId,
            phase = phase,
            scannedBlocks = scannedBlocks,
            totalBlocks = geometry.totalBlocks,
            appliedChunks = appliedChunks,
            totalChunks = geometry.chunks.size,
            beds = if (finished) appliedBeds.positions.size else bedCount,
            fixedCrops = if (finished) appliedFixedCrops else fixedCropCount,
            orchardLeaves = if (finished) appliedLeaves.positions.size else orchardSampler.size,
        )

        fun start() { if (tasks == null) requestChunk() else requestChunkAsync() }

        private fun current() = !finished && !closed && jobs[definition.zoneId] === this &&
            (currentChunk?.let { it.isLoaded && it.world.getChunkAt(it.x, it.z) === it } != false)

        private fun requestChunkAsync() {
            val work = requireNotNull(tasks)
            val token = work.lifecycleToken()
            if (!current()) return
            if (chunkIndex >= geometry.chunks.size) {
                if (phase == FarmBlockReindexPhase.SCANNING) beginApplyAsync() else complete()
                return
            }
            val coordinates = geometry.chunks[chunkIndex]
            var load: CompletableFuture<Chunk>? = null
            work.capture(token, ::current) {
                val world = definition.region.world
                load = if (world.isChunkLoaded(coordinates.x, coordinates.z))
                    CompletableFuture.completedFuture(world.getChunkAt(coordinates.x, coordinates.z))
                else world.getChunkAtAsync(coordinates.x, coordinates.z, false)
                true
            }.thenCompose { requireNotNull(load) }.thenCompose { chunk -> work.callSync(token) {
                if (!current()) throw CancellationException("Farm reindex source changed")
                prepareCurrentChunk(chunk)
                ledger.loadAsync(chunk, work, ::current).thenCompose { loaded -> work.callSync(token) {
                    check(loaded.contents.fixed.none { it.zoneId == definition.zoneId && it.restoreAt != null }) {
                        "Farm ${definition.zoneId} still has pending fixed crop restoration"
                    }
                    if (phase == FarmBlockReindexPhase.SCANNING) scanChunkAsync(chunk) else applyChunkAsync(chunk)
                }.thenCompose { it } }
            }.thenCompose { it } }.whenComplete { _, failure -> work.runSync(token) {
                if (failure != null && current()) fail(failure)
            } }
        }

        private fun scanChunkAsync(chunk: Chunk): CompletableFuture<Unit> {
            val work = requireNotNull(tasks)
            val token = work.lifecycleToken()
            val slice = geometry.slice(chunk.x, chunk.z)
            val worldName = chunk.world.name
            val crops = definition.crops
            val minimumHeight = chunk.world.minHeight
            val maximumHeight = chunk.world.maxHeight
            var snapshot: org.bukkit.ChunkSnapshot? = null
            return work.capture(token, ::current) { snapshot = chunk.getChunkSnapshot(false, false, false); true }
                .thenCompose { work.compute {
                    val captured = requireNotNull(snapshot)
                    val candidates = mutableListOf<Pair<FarmPlotPosition, Int>>()
                    for (x in slice.minX..slice.maxX) for (z in slice.minZ..slice.maxZ) for (y in slice.minY..slice.maxY) {
                        val material = captured.getBlockType(x and 15, y, z and 15)
                        val category = when {
                            material == Material.FARMLAND && FarmBlockPolicy.isSelectableBed(material,
                                if (y + 1 < maximumHeight) captured.getBlockType(x and 15, y + 1, z and 15) else Material.AIR, crops) -> 0
                            MaterialRules.isFixedBlockCrop(material) && material.name in crops -> 1
                            MaterialRules.isLeaf(material) && FarmBlockPolicy.isOrchardLeaf(material,
                                if (y - 1 >= minimumHeight) captured.getBlockType(x and 15, y - 1, z and 15) else Material.AIR) -> 2
                            else -> -1
                        }
                        if (category >= 0) candidates += FarmPlotPosition(worldName, x, y, z) to category
                    }
                    candidates.iterator()
                } }.thenCompose { candidates -> work.callSync(token) {
                    work.capture(token, ::current) {
                        if (candidates.hasNext()) {
                            val (position, category) = candidates.next()
                            if (definition.region.contains(org.bukkit.Location(chunk.world, position.x.toDouble(), position.y.toDouble(), position.z.toDouble()))) {
                                when (category) {
                                    0 -> if (addBounded(beds, chunk.chunkKey, position, bedCount, MAX_INDEXED_BEDS, "farm beds")) bedCount++
                                    1 -> if (addBounded(fixedCrops, chunk.chunkKey, position, fixedCropCount, MAX_INDEXED_FIXED_CROPS, "fixed crops")) fixedCropCount++
                                    2 -> orchardSampler.add(position)
                                }
                            }
                        }
                        !candidates.hasNext()
                    }.thenApply {
                        scannedBlocks += slice.volume
                        finishCurrentChunk()
                        onProgress(status())
                        chunkIndex++
                        requestChunkAsync()
                        Unit
                    }
                }.thenCompose { it } }
        }

        private fun beginApplyAsync() {
            val work = requireNotNull(tasks)
            val token = work.lifecycleToken()
            phase = FarmBlockReindexPhase.APPLYING
            chunkIndex = 0
            // Scan builders are quiescent until this worker returns; status reads only counters.
            work.compute {
                FarmCropLayoutPlanner.plan(beds.values.flatten(), definition.cropLayout) to
                    orchardSampler.values().groupBy { chunkKey(it.x shr 4, it.z shr 4) }.mapValues { (_, positions) ->
                        positions.sortedWith(compareBy(FarmPlotPosition::y, FarmPlotPosition::x, FarmPlotPosition::z))
                            .take(MAX_INDEXED_ORCHARD_LEAVES_PER_CHUNK).toSet()
                    }
            }.thenCompose { (layout, orchards) -> work.callSync(token) {
                if (!current()) throw CancellationException("Farm reindex source changed during layout")
                cropLayoutByBed = layout
                orchardByChunk = orchards
                onProgress(status())
                requestChunkAsync()
            } }.whenComplete { _, failure -> work.runSync(token) { if (failure != null && current()) fail(failure) } }
        }

        private fun applyChunkAsync(chunk: Chunk): CompletableFuture<Unit> {
            val work = requireNotNull(tasks)
            val token = work.lifecycleToken()
            val bedIterator = beds[chunk.chunkKey].orEmpty().iterator()
            val fixedIterator = fixedCrops[chunk.chunkKey].orEmpty().iterator()
            val leafIterator = orchardByChunk[chunk.chunkKey].orEmpty().iterator()
            val validBeds = mutableListOf<ManagedFarmBlockRecord>()
            val validFixed = mutableListOf<ManagedFarmFixedCropRecord>()
            val validLeaves = mutableListOf<ManagedFarmOrchardLeafRecord>()
            val worldName = chunk.world.name
            return work.capture(token, ::current) {
                when {
                    bedIterator.hasNext() -> bedIterator.next().let { position ->
                        val block = chunk.world.getBlockAt(position.x, position.y, position.z)
                        val crop = block.getRelative(org.bukkit.block.BlockFace.UP)
                        if (definition.region.contains(block.location) && FarmBlockPolicy.isSelectableBed(block.type, crop.type, definition.crops)) {
                            FarmBlockPolicy.makeWet(block)
                            cropLayoutByBed[position]?.let { plantMature(crop, Material.valueOf(it)) }
                            val cropData = crop.blockData.takeUnless { crop.type.isAir }?.asString
                            validBeds += ManagedFarmBlockRecord(definition.zoneId, block.x, block.y, block.z,
                                block.blockData.asString, cropData, cropData, indexed = true)
                        }
                    }
                    fixedIterator.hasNext() -> fixedIterator.next().let { position ->
                        val block = chunk.world.getBlockAt(position.x, position.y, position.z)
                        if (definition.region.contains(block.location) && MaterialRules.isFixedBlockCrop(block.type) && block.type.name in definition.crops)
                            validFixed += ManagedFarmFixedCropRecord(definition.zoneId, block.x, block.y, block.z, block.blockData.asString, null)
                    }
                    leafIterator.hasNext() -> leafIterator.next().let { position ->
                        val block = chunk.world.getBlockAt(position.x, position.y, position.z)
                        if (definition.region.contains(block.location) && FarmBlockPolicy.isOrchardLeaf(block.type, block.getRelative(org.bukkit.block.BlockFace.DOWN).type))
                            validLeaves += ManagedFarmOrchardLeafRecord(definition.zoneId, block.x, block.y, block.z)
                    }
                }
                !bedIterator.hasNext() && !fixedIterator.hasNext() && !leafIterator.hasNext()
            }.thenCompose { ledger.replaceZoneIndexAsync(chunk, definition.zoneId, validBeds, validFixed, validLeaves, work, ::current) }
                .thenCompose { work.compute {
                    Triple(appliedBeds.add(validBeds.map { FarmPlotPosition(worldName, it.x, it.y, it.z) }),
                        appliedFixedCropPositions.add(validFixed.map { FarmPlotPosition(worldName, it.x, it.y, it.z) }),
                        appliedLeaves.add(validLeaves.map { FarmPlotPosition(worldName, it.x, it.y, it.z) }))
                } }.thenCompose { (nextBeds, nextFixed, nextLeaves) -> work.callSync(token) {
                    if (!current()) throw CancellationException("Farm reindex source changed after ledger commit")
                    appliedBeds = nextBeds
                    appliedFixedCropPositions = nextFixed
                    appliedLeaves = nextLeaves
                    appliedFixedCrops += validFixed.size
                    appliedChunks++
                    finishCurrentChunk()
                    onProgress(status())
                    chunkIndex++
                    requestChunkAsync()
                } }
        }

        fun cancel() {
            if (finished) return
            finished = true
            nextTask?.cancel()
            releaseCurrentChunk()
        }

        private fun requestChunk(
            scanBudget: Int = definition.blocksPerTick,
            applyBudget: Int = MAX_APPLY_CHUNKS_PER_TICK,
            scanTickBudget: WorksiteTickBudget? = null,
        ) {
            if (finished || closed) return
            if (chunkIndex >= geometry.chunks.size) {
                if (phase == FarmBlockReindexPhase.SCANNING) beginApply() else complete()
                return
            }
            val coordinates = geometry.chunks[chunkIndex]
            if (definition.region.world.isChunkLoaded(coordinates.x, coordinates.z)) {
                prepareCurrentChunk(definition.region.world.getChunkAt(coordinates.x, coordinates.z))
                if (phase == FarmBlockReindexPhase.SCANNING) {
                    scanStep(
                        scanBudget,
                        scanTickBudget ?: WorksiteTickBudget(scanBudget, nowNanos = nanoTime),
                    )
                } else {
                    applyChunk(applyBudget)
                }
                return
            }
            definition.region.world.getChunkAtAsync(coordinates.x, coordinates.z, false).whenComplete { chunk, failure ->
                runCatching {
                    Tasks.scheduler.runSync {
                        if (finished || closed) return@runSync
                        if (failure != null || chunk == null) {
                            fail(failure ?: IllegalStateException("Chunk ${coordinates.x},${coordinates.z} did not load"))
                            return@runSync
                        }
                        prepareCurrentChunk(chunk)
                        if (phase == FarmBlockReindexPhase.SCANNING) {
                            scanStep(tickBudget = WorksiteTickBudget(definition.blocksPerTick, nowNanos = nanoTime))
                        } else {
                            applyChunk()
                        }
                    }
                }.onFailure(::fail)
            }
        }

        private fun prepareCurrentChunk(chunk: Chunk) {
            currentChunk = chunk
            currentTicketAdded = addChunkTicket(chunk)
            currentCursor = 0L
        }

        private fun scanStep(
            remainingBudget: Int = definition.blocksPerTick,
            tickBudget: WorksiteTickBudget = WorksiteTickBudget(remainingBudget, nowNanos = nanoTime),
        ) {
            if (finished) return
            require(remainingBudget > 0) { "Farm reindex scan budget must be positive" }
            val chunk = currentChunk ?: return fail(IllegalStateException("Farm reindex lost its current chunk"))
            if (currentCursor == 0L && ledger.fixedCropRecords(chunk).any {
                    it.zoneId == definition.zoneId && it.restoreAt != null
                }
            ) {
                return fail(IllegalStateException("Farm ${definition.zoneId} still has pending fixed crop restoration"))
            }
            val slice = geometry.slice(chunk.x, chunk.z)
            val startedAtCursor = currentCursor
            val end = minOf(slice.volume, currentCursor + remainingBudget)
            while (currentCursor < end && tickBudget.tryConsume()) {
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
                nextTask = Tasks.scheduler.runLater(1L) {
                    scanStep(tickBudget = WorksiteTickBudget(definition.blocksPerTick, nowNanos = nanoTime))
                }
                return
            }
            val consumed = (currentCursor - startedAtCursor).toInt()
            finishCurrentChunk()
            onProgress(status())
            chunkIndex++
            if (chunkIndex >= geometry.chunks.size) {
                beginApply()
                return
            }
            val nextBudget = remainingBudget - consumed
            if (nextBudget > 0) {
                requestChunk(scanBudget = nextBudget, scanTickBudget = tickBudget)
            } else {
                nextTask = Tasks.scheduler.runLater(1L) { requestChunk() }
            }
        }

        private fun beginApply() {
            phase = FarmBlockReindexPhase.APPLYING
            chunkIndex = 0
            cropLayoutByBed = FarmCropLayoutPlanner.plan(beds.values.flatten(), definition.cropLayout)
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

        private fun applyChunk(remainingChunks: Int = MAX_APPLY_CHUNKS_PER_TICK) {
            if (finished) return
            require(remainingChunks > 0) { "Farm reindex apply budget must be positive" }
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
            validBeds.forEach { soil ->
                val cropName = cropLayoutByBed[soil.toPosition()] ?: return@forEach
                plantMature(soil.getRelative(org.bukkit.block.BlockFace.UP), Material.valueOf(cropName))
            }
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
            appliedBeds = appliedBeds.add(validBeds.map { it.toPosition() })
            appliedFixedCropPositions = appliedFixedCropPositions.add(validFixed.map { it.toPosition() })
            appliedLeaves = appliedLeaves.add(validLeaves.map { it.toPosition() })
            appliedFixedCrops += validFixed.size
            appliedChunks++
            finishCurrentChunk()
            onProgress(status())
            chunkIndex++
            if (chunkIndex >= geometry.chunks.size) {
                complete()
            } else if (remainingChunks > 1) {
                requestChunk(applyBudget = remainingChunks - 1)
            } else {
                nextTask = Tasks.scheduler.runLater(1L) { requestChunk() }
            }
        }

        private fun complete() {
            if (finished) return
            finished = true
            bedsByZone[definition.zoneId] = appliedBeds
            fixedCropsByZone[definition.zoneId] = appliedFixedCropPositions
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
            if (chunk != null && currentTicketAdded) runCatching { removeChunkTicket(chunk) }
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

        private fun plantMature(block: Block, material: Material) {
            val data = material.createBlockData()
            if (data is Ageable) data.age = data.maximumAge
            block.setBlockData(data, false)
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
                val chunks = if (totalChunks <= MAX_REINDEX_CHUNKS) object : AbstractList<ChunkCoordinates>() {
                    override val size = totalChunks.toInt()
                    private val gridDepth = maxChunkZ - minChunkZ + 1
                    override fun get(index: Int): ChunkCoordinates {
                        require(index in indices)
                        return ChunkCoordinates(minChunkX + index / gridDepth, minChunkZ + index % gridDepth)
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

        private fun spatialScore(position: FarmPlotPosition): Long = WorksiteDeterministicSeed.positionScore(
            seed = 0L,
            world = position.world,
            x = position.x,
            y = position.y,
            z = position.z,
        )
    }

    private companion object {
        const val MAX_REINDEX_CHUNKS = 4_096L
        const val MAX_APPLY_CHUNKS_PER_TICK = 64
        const val MAX_INDEXED_BEDS = 100_000
        const val MAX_INDEXED_FIXED_CROPS = 100_000
        const val MAX_INDEXED_ORCHARD_LEAVES_PER_CHUNK = 1_024

        fun chunkKey(x: Int, z: Int): Long = (x.toLong() and 0xffffffffL) or (z.toLong() shl 32)

        fun Block.toPosition(): FarmPlotPosition = FarmPlotPosition(world.name, x, y, z)
    }
}

/** Stable ordered roots let delayed capture traverse one exact topology revision without copying a whole zone. */
private data class FarmTopologyIndex(
    val positions: PersistentSet<FarmPlotPosition> = persistentSetOf(),
    val chunks: PersistentMap<Triple<String, Int, Int>, PersistentSet<FarmPlotPosition>> = persistentMapOf(),
) {
    fun add(values: Collection<FarmPlotPosition>): FarmTopologyIndex {
        var nextPositions = positions
        var nextChunks = chunks
        values.forEach { position ->
            nextPositions = nextPositions.add(position)
            val key = Triple(position.world, position.x shr 4, position.z shr 4)
            nextChunks = nextChunks.put(key, (nextChunks[key] ?: persistentSetOf()).add(position))
        }
        return FarmTopologyIndex(nextPositions, nextChunks)
    }
    fun remove(values: Collection<FarmPlotPosition>): FarmTopologyIndex {
        var nextPositions = positions
        var nextChunks = chunks
        values.forEach { position ->
            nextPositions = nextPositions.remove(position)
            val key = Triple(position.world, position.x shr 4, position.z shr 4)
            nextChunks[key]?.remove(position)?.let { remaining ->
                nextChunks = if (remaining.isEmpty()) nextChunks.remove(key) else nextChunks.put(key, remaining)
            }
        }
        return FarmTopologyIndex(nextPositions, nextChunks)
    }
    fun replace(key: Triple<String, Int, Int>, values: Collection<FarmPlotPosition>): FarmTopologyIndex =
        remove(chunks[key].orEmpty()).add(values)
}
