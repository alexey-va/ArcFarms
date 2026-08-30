package ru.ruscrafting.farms.paper.farm.recovery

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.PendingFixedFarmCrop
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmBlockRestoreQueue
import ru.ruscrafting.farms.paper.FarmFixedCropPosition
import ru.ruscrafting.farms.paper.FarmFixedCropRestore
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import ru.ruscrafting.farms.persistence.FixedFarmCropJournal
import java.util.concurrent.TimeUnit
import java.util.logging.Level

/**
 * Sole owner of scheduled fixed-crop restoration. The journal/PDC intent is
 * durable before the world block is removed; the UUID-free queue is only a
 * loaded-runtime cache rebuilt from those durable sources.
 */
internal class FarmFixedCropRecoveryController(
    private val journal: FixedFarmCropJournal,
    private val ledger: FarmBlockLedger,
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
    private val access: WorksiteAccessPort,
    private val port: WorksiteAudiencePort,
    private val state: WorksiteStatePort,
    private val tasks: WorksiteTaskPort,
    private val runtimes: () -> Collection<FarmRuntime>,
    private val clock: () -> Long,
) {
    private val queue = FarmBlockRestoreQueue()

    fun reconcileLoaded() {
        Bukkit.getWorlds().asSequence()
            .flatMap { it.loadedChunks.asSequence() }
            .forEach(::reconcileChunk)
    }

    fun reconcileChunk(chunk: org.bukkit.Chunk) {
        val now = clock()
        ledger.fixedCropRecords(chunk).forEach { record ->
            val runtime = runtimes().firstOrNull { it.settings.id == record.zoneId && it.region.world == chunk.world }
                ?: return@forEach
            val block = chunk.world.getBlockAt(record.x, record.y, record.z)
            if (!runtime.region.contains(block.location)) return@forEach
            val original = runCatching { Bukkit.createBlockData(record.originalBlockData) }.getOrNull()
                ?: return@forEach
            if (!MaterialRules.isFixedBlockCrop(original.material) || original.material.name !in runtime.settings.crops) {
                return@forEach
            }
            val restoreAt = when {
                record.restoreAt != null -> record.restoreAt
                block.type.isAir || block.isReplaceable -> now
                else -> null
            } ?: return@forEach
            if (record.restoreAt == null) ledger.scheduleExistingFixedCropRestore(block, restoreAt)
            queue.schedule(FarmFixedCropRestore(record.position(chunk.world.name), restoreAt))
        }
        journal.records().asSequence().filter { pending ->
            pending.world == chunk.world.name && (pending.x shr 4) == chunk.x && (pending.z shr 4) == chunk.z
        }.forEach { pending ->
            val runtime = runtimes().firstOrNull { it.settings.id == pending.zoneId && it.region.world == chunk.world }
                ?: return@forEach
            val block = chunk.world.getBlockAt(pending.x, pending.y, pending.z)
            if (!runtime.region.contains(block.location)) return@forEach
            val original = runCatching { Bukkit.createBlockData(pending.originalBlockData) }.getOrNull()
                ?: return@forEach
            if (!MaterialRules.isFixedBlockCrop(original.material) || original.material.name !in runtime.settings.crops) {
                return@forEach
            }
            if (block.type == original.material) {
                ledger.reconcileFixedCrop(block, pending.zoneId, pending.originalBlockData, null)
                retire(pending.positionKey, "block_already_present")
                return@forEach
            }
            ledger.reconcileFixedCrop(block, pending.zoneId, pending.originalBlockData, pending.restoreAt)
            queue.schedule(
                FarmFixedCropRestore(
                    FarmFixedCropPosition(pending.world, pending.x, pending.y, pending.z),
                    pending.restoreAt,
                ),
            )
        }
    }

    fun processDue(limit: Int) {
        val now = clock()
        queue.pollDue(now, limit.coerceAtLeast(1)).forEach { restore(it, now) }
    }

    fun prepareHarvest(
        runtime: FarmRuntime,
        player: Player,
        block: Block,
        now: Long,
        onCommitted: (FarmRuntime, Player, String) -> Unit,
    ): Boolean {
        val positionKey = positionKey(block.location)
        if (journal.contains(positionKey)) {
            port.sendActionBar(player, MessageKey.FARM_FIXED_CROP_PENDING)
            return false
        }
        val ledgerRecord = runCatching { ledger.captureFixedCrop(block, runtime.settings.id) }.getOrElse { failure ->
            state.log(Level.SEVERE, "Could not capture fixed crop metadata at $positionKey", failure)
            port.sendChat(player, MessageKey.GENERIC_ERROR)
            return false
        }
        val restoreAt = now + TimeUnit.SECONDS.toMillis(runtime.settings.fixedCropRespawnSeconds.toLong())
        val scheduledRecord = runCatching {
            ledger.scheduleExistingFixedCropRestore(block, restoreAt)
                ?: error("Fixed crop PDC disappeared before harvest")
        }.getOrElse { failure ->
            state.log(Level.SEVERE, "Could not schedule fixed crop restore at $positionKey", failure)
            port.sendChat(player, MessageKey.GENERIC_ERROR)
            return false
        }
        val pending = PendingFixedFarmCrop(
            zoneId = runtime.settings.id,
            world = block.world.name,
            x = block.x,
            y = block.y,
            z = block.z,
            originalBlockData = ledgerRecord.originalBlockData,
            restoreAt = restoreAt,
        )
        val crop = block.type
        val sequence = runtime.state.sequence
        val lifecycle = tasks.lifecycleToken()
        val preparation = runCatching { journal.prepare(pending) }.getOrElse { failure ->
            state.log(Level.SEVERE, "Could not prepare fixed crop journal at $positionKey", failure)
            ledger.reconcileFixedCrop(block, runtime.settings.id, scheduledRecord.originalBlockData, null)
            port.sendChat(player, MessageKey.GENERIC_ERROR)
            return false
        }

        preparation.whenComplete { _, failure ->
            if (!access.isOperational()) return@whenComplete
            tasks.runSync(lifecycle) {
                if (failure != null) {
                    state.log(Level.SEVERE, "Could not persist fixed crop journal at $positionKey", failure)
                    ledger.reconcileFixedCrop(block, pending.zoneId, pending.originalBlockData, null)
                    if (player.isOnline) port.sendChat(player, MessageKey.GENERIC_ERROR)
                    return@runSync
                }
                val currentRuntime = runtimes().firstOrNull {
                    it.settings.id == pending.zoneId && it.state.sequence == sequence && it.state.phase == FarmPhase.HARVESTING
                }
                if (currentRuntime == null || block.type != crop) {
                    if (block.type == crop) {
                        ledger.reconcileFixedCrop(block, pending.zoneId, pending.originalBlockData, null)
                    }
                    retire(positionKey, "harvest_stale")
                    return@runSync
                }
                block.setType(Material.AIR, false)
                queue.schedule(FarmFixedCropRestore(scheduledRecord.position(block.world.name), restoreAt))
                debug.event(
                    "farm_fixed_crop_committed",
                    "player" to player.name,
                    "zone" to currentRuntime.settings.id,
                    "crop" to crop,
                    "restore_at" to restoreAt,
                    "x" to block.x,
                    "y" to block.y,
                    "z" to block.z,
                )
                onCommitted(currentRuntime, player, crop.name)
            }
        }
        return true
    }

    fun contains(location: Location): Boolean = journal.contains(positionKey(location))

    fun records(): List<PendingFixedFarmCrop> = journal.records()

    fun retire(location: Location, reason: String) = retire(positionKey(location), reason)

    fun clearCache() = queue.clear()

    private fun restore(entry: FarmFixedCropRestore, now: Long) {
        val world = Bukkit.getWorld(entry.position.world) ?: return
        if (!world.isChunkLoaded(entry.position.x shr 4, entry.position.z shr 4)) return
        val block = world.getBlockAt(entry.position.x, entry.position.y, entry.position.z)
        val positionKey = positionKey(block.location)
        val pending = journal.record(positionKey)
        val record = ledger.fixedCropRecord(block)
        val restoreAt = pending?.restoreAt ?: record?.restoreAt ?: return
        if (restoreAt > now) {
            queue.schedule(entry.copy(restoreAt = restoreAt))
            return
        }
        val zoneId = pending?.zoneId ?: requireNotNull(record).zoneId
        val originalData = pending?.originalBlockData ?: requireNotNull(record).originalBlockData
        val runtime = runtimes().firstOrNull { it.settings.id == zoneId && it.region.contains(block.location) } ?: return
        val original = runCatching { Bukkit.createBlockData(originalData) }.getOrElse { failure ->
            state.log(Level.SEVERE, "Could not decode fixed crop at ${entry.position}", failure)
            return
        }
        if (!MaterialRules.isFixedBlockCrop(original.material) || original.material.name !in runtime.settings.crops) return
        when {
            block.type == original.material -> {
                ledger.reconcileFixedCrop(block, zoneId, originalData, null)
                retire(positionKey, "block_present")
            }
            block.type.isAir || block.isReplaceable -> {
                block.setBlockData(original, false)
                ledger.reconcileFixedCrop(block, zoneId, originalData, null)
                retire(positionKey, "restored")
                debug.event(
                    "farm_fixed_crop_restored",
                    "zone" to runtime.settings.id,
                    "crop" to original.material,
                    "x" to block.x,
                    "y" to block.y,
                    "z" to block.z,
                )
            }
            else -> {
                queue.schedule(entry.copy(restoreAt = now + 1_000L))
                if (access.allowInteraction("farm-fixed-crop-obstructed:${entry.position}", TimeUnit.MINUTES.toMillis(5))) {
                    state.log(Level.WARNING, "Fixed crop recovery at ${entry.position} is obstructed by ${block.type}")
                }
            }
        }
    }

    private fun retire(positionKey: String, reason: String) {
        journal.remove(positionKey).whenComplete { _, failure ->
            if (failure != null) {
                state.log(Level.SEVERE, "Could not retire fixed crop journal at $positionKey", failure)
                val token = tasks.lifecycleToken()
                tasks.runSync(token) {
                    journal.record(positionKey)?.let { pending ->
                        queue.schedule(
                            FarmFixedCropRestore(
                                FarmFixedCropPosition(pending.world, pending.x, pending.y, pending.z),
                                clock() + 1_000L,
                            ),
                        )
                    }
                }
            } else {
                debug.event("farm_fixed_crop_journal_retired", "position" to positionKey, "reason" to reason)
            }
        }
    }

    private fun positionKey(location: Location): String =
        "${location.world.name}:${location.blockX}:${location.blockY}:${location.blockZ}"
}
