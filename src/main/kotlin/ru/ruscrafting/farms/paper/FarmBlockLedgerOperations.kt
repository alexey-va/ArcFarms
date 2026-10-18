package ru.ruscrafting.farms.paper

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.block.BlockFace
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.concurrent.CompletableFuture

/** Exact native state captured before a journal commit, also used to fence the following mutation. */
internal data class CapturedFarmBed(
    val position: FarmPlotPosition,
    val soilData: String,
    val cropData: String,
    val record: ManagedFarmBlockRecord,
)

/** Chunk batches preserve the legacy original/active crop distinction and record ordering. */
internal fun FarmBlockLedger.captureBlocksAsync(
    chunk: Chunk, positions: List<FarmPlotPosition>, zoneId: String, refreshActive: Boolean,
    tasks: WorksiteTaskPort, isCurrent: () -> Boolean, bedsPerStep: Int = 1,
): CompletableFuture<List<CapturedFarmBed>> {
    require(bedsPerStep in 1..2)
    val token = tasks.lifecycleToken()
    val world = chunk.world
    val worldName = world.name
    return loadAsync(chunk, tasks, isCurrent).thenCompose { loaded ->
        val captured = arrayListOf<CapturedFarmBed>()
        val iterator = positions.iterator()
        tasks.capture(token, { isCurrent() && this.isCurrent(chunk, loaded) }) {
            repeat(bedsPerStep) {
                if (!iterator.hasNext()) return@capture true
                val position = iterator.next()
                require(position.world == worldName && position.x shr 4 == chunk.x && position.z shr 4 == chunk.z)
                val soil = world.getBlockAt(position.x, position.y, position.z)
                val crop = soil.getRelative(BlockFace.UP)
                val soilData = soil.blockData.asString
                val cropData = crop.blockData.asString
                val original = cropData.takeUnless { crop.type.isAir }
                val existing = loaded.blocks.record(position.x, position.y, position.z)
                var record = existing ?: ManagedFarmBlockRecord(zoneId, position.x, position.y, position.z, soilData, original, original)
                // A restarted release sees cleared DIRT/AIR beds; their committed crop is still the recovery intent.
                val alreadyReleased = existing != null && soil.type == Material.DIRT && crop.type.isAir
                if (refreshActive && !alreadyReleased)
                    record = record.copy(activeCropData = cropData.takeUnless { crop.type.isAir || crop.type == Material.WATER })
                captured += CapturedFarmBed(position, soilData, cropData, record)
            }
            !iterator.hasNext()
        }.thenCompose { tasks.compute {
            val records = loaded.contents.blocks.associateByTo(linkedMapOf()) { Triple(it.x, it.y, it.z) }
            for (bed in captured) records[Triple(bed.position.x, bed.position.y, bed.position.z)] = bed.record
            records.values.toList()
        } }.thenCompose { records -> tasks.callSync(token) {
            commitBlocksAsync(chunk, loaded, records, tasks, isCurrent).thenApply { captured as List<CapturedFarmBed> }
        }.thenCompose { it } }
    }
}

/** Restore all native pairs before changing their durable records. Failures retain the recovery journal. */
internal fun FarmBlockLedger.restoreBlocksAsync(
    chunk: Chunk, positions: List<FarmPlotPosition>, clear: Boolean,
    tasks: WorksiteTaskPort, isCurrent: () -> Boolean,
): CompletableFuture<Set<FarmPlotPosition>> = restoreBedRecordsAsync(chunk, positions, clear, false, tasks, isCurrent)

internal fun FarmBlockLedger.restoreTemporaryBlocksAsync(
    chunk: Chunk, positions: List<FarmPlotPosition>, tasks: WorksiteTaskPort, isCurrent: () -> Boolean,
): CompletableFuture<Set<FarmPlotPosition>> = restoreBedRecordsAsync(chunk, positions, false, true, tasks, isCurrent)

private fun FarmBlockLedger.restoreBedRecordsAsync(
    chunk: Chunk, positions: List<FarmPlotPosition>, clear: Boolean, temporary: Boolean,
    tasks: WorksiteTaskPort, isCurrent: () -> Boolean,
): CompletableFuture<Set<FarmPlotPosition>> {
    val token = tasks.lifecycleToken()
    val world = chunk.world
    val worldName = world.name
    return loadAsync(chunk, tasks, isCurrent).thenCompose { loaded ->
        tasks.compute {
            val requested = positions.toHashSet()
            loaded.contents.blocks.filter {
                FarmPlotPosition(worldName, it.x, it.y, it.z) in requested && (!temporary || it.temporaryMutation != null)
            }
        }.thenCompose { selected -> tasks.callSync(token) {
            val iterator = selected.iterator()
            tasks.capture(token, { isCurrent() && this.isCurrent(chunk, loaded) }) {
                if (!iterator.hasNext()) return@capture true
                val record = iterator.next()
                val soilData = Bukkit.createBlockData(record.originalSoilData)
                val cropData = (if (temporary) record.activeCropData else record.originalCropData)?.let(Bukkit::createBlockData)
                val soil = world.getBlockAt(record.x, record.y, record.z)
                soil.setBlockData(soilData, false)
                val crop = soil.getRelative(BlockFace.UP)
                if (cropData == null) crop.setType(Material.AIR, false) else crop.setBlockData(cropData, false)
                !iterator.hasNext()
            }.thenCompose { tasks.compute {
                val restored = selected.mapTo(hashSetOf()) { FarmPlotPosition(worldName, it.x, it.y, it.z) }
                val records = loaded.contents.blocks.mapNotNull { record ->
                    if (FarmPlotPosition(worldName, record.x, record.y, record.z) !in restored) record
                    else if (temporary) {
                        if (record.indexed) record.copy(temporaryMutation = null, temporaryRestoreAt = null) else null
                    } else if (clear) null else record.copy(activeCropData = record.originalCropData)
                }
                restored to records
            } }.thenCompose { (restored, records) -> tasks.callSync(token) {
                commitBlocksAsync(chunk, loaded, records, tasks, isCurrent).thenApply { restored as Set<FarmPlotPosition> }
            }.thenCompose { it } }
        }.thenCompose { it } }
    }
}

/** Called only after the recovered shift snapshot has been durably acknowledged. */
internal fun FarmBlockLedger.removeTransientBlocksAsync(
    chunk: Chunk, positions: Collection<FarmPlotPosition>, tasks: WorksiteTaskPort, isCurrent: () -> Boolean,
): CompletableFuture<Unit> {
    val token = tasks.lifecycleToken()
    val worldName = chunk.world.name
    return loadAsync(chunk, tasks, isCurrent).thenCompose { loaded -> tasks.compute {
        val requested = positions.toHashSet()
        loaded.contents.blocks.filterNot { !it.indexed && FarmPlotPosition(worldName, it.x, it.y, it.z) in requested }
    }.thenCompose { records -> tasks.callSync(token) { commitBlocksAsync(chunk, loaded, records, tasks, isCurrent) }.thenCompose { it } } }
}
