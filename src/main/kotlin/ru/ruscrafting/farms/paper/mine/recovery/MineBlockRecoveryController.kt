package ru.ruscrafting.farms.paper.mine.recovery

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.block.Block
import ru.ruscrafting.farms.domain.PendingMineBlock
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import ru.ruscrafting.farms.paper.worksite.RuntimeComponent
import ru.ruscrafting.farms.persistence.MineRecoveryJournal
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Level

/** Owns the unchanged V1 journal schema and converges only loaded due records. */
internal class MineBlockRecoveryController(
    private val journal: MineRecoveryJournal,
    private val access: WorksiteAccessPort,
    private val state: WorksiteStatePort,
    private val tasks: WorksiteTaskPort,
    private val clock: () -> Long,
) : RuntimeComponent {
    private val inFlightPositions = ConcurrentHashMap.newKeySet<String>()
    private val pendingResults = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
    private val retiringRecords = ConcurrentHashMap.newKeySet<String>()
    private var dueCursor = 0

    val pendingCount: Int get() = journal.records().size

    fun records(zoneId: String): List<PendingMineBlock> = journal.records().filter { it.zoneId == zoneId }

    fun canStart(zoneId: String): Boolean = journal.records().none {
        it.zoneId == zoneId && !it.isOrdinaryMiningBreak()
    }

    fun containsPosition(positionKey: String): Boolean =
        journal.containsPosition(positionKey) || positionKey in inFlightPositions

    fun prepare(
        record: PendingMineBlock,
        block: Block,
        expectedOriginal: Material,
        stillValid: () -> Boolean = { true },
        mutation: () -> Unit,
    ): CompletableFuture<Boolean> {
        if (!inFlightPositions.add(record.positionKey)) {
            state.log(Level.INFO, "Mine recovery prepare rejected reason=position_in_flight zone=${record.zoneId} " +
                "record=${record.id} position=${record.positionKey} expected=$expectedOriginal actual=${block.type}")
            return CompletableFuture.completedFuture(false)
        }
        if (journal.containsPosition(record.positionKey)) {
            inFlightPositions.remove(record.positionKey)
            state.log(Level.INFO, "Mine recovery prepare rejected reason=position_journaled zone=${record.zoneId} " +
                "record=${record.id} position=${record.positionKey} expected=$expectedOriginal actual=${block.type}")
            return CompletableFuture.completedFuture(false)
        }
        val token = tasks.lifecycleToken()
        val result = CompletableFuture<Boolean>()
        pendingResults[record.positionKey] = result
        journal.prepare(record).whenComplete { _, failure ->
            if (failure != null) {
                release(record.positionKey, result)
                result.completeExceptionally(failure)
                return@whenComplete
            }
            if (!tasks.runSync(token) {
                    try {
                        val actual = block.type
                        val rejection = when {
                            actual != expectedOriginal -> "block_changed"
                            !access.isOperational() -> "runtime_not_operational"
                            !stillValid() -> "validation_failed"
                            else -> null
                        }
                        if (rejection != null) {
                            state.log(Level.INFO, "Mine recovery mutation rejected reason=$rejection zone=${record.zoneId} " +
                                "record=${record.id} position=${record.positionKey} expected=$expectedOriginal actual=$actual " +
                                "temporary=${record.temporaryMaterial} next=${record.nextMaterial}")
                            retire(record, "stale")
                            result.complete(false)
                        } else {
                            mutation()
                            result.complete(true)
                        }
                    } catch (mutationFailure: Throwable) {
                        state.log(Level.SEVERE, "Mine recovery mutation failed zone=${record.zoneId} record=${record.id} " +
                            "position=${record.positionKey} expected=$expectedOriginal actual=${block.type}", mutationFailure)
                        result.completeExceptionally(mutationFailure)
                    } finally {
                        release(record.positionKey, result)
                    }
                }
            ) {
                release(record.positionKey, result)
                state.log(Level.INFO, "Mine recovery mutation rejected reason=lifecycle_token_inactive zone=${record.zoneId} " +
                    "record=${record.id} position=${record.positionKey} expected=$expectedOriginal actual=${block.type}")
                retire(record, "stale")
                result.complete(false)
            }
        }
        return result
    }

    fun processDue(now: Long = clock(), budget: Int = 128): Int {
        require(budget in 1..262_144)
        var processed = 0
        val due = journal.records()
            .filter { it.restoreAt <= now && it.positionKey !in inFlightPositions }
        if (due.isEmpty()) {
            dueCursor = 0
            return 0
        }
        val start = dueCursor % due.size
        val scanCount = minOf(budget, due.size)
        dueCursor = (start + scanCount) % due.size
        repeat(scanCount) { offset ->
            val record = due[(start + offset) % due.size]
            val world = Bukkit.getWorld(record.world) ?: return@repeat
            if (!world.isChunkLoaded(record.x shr 4, record.z shr 4)) return@repeat
            val temporary = material(record.temporaryMaterial, record) ?: return@repeat
            val next = material(record.nextMaterial, record) ?: return@repeat
            val block = world.getBlockAt(record.x, record.y, record.z)
            runCatching {
                val before = block.type
                val changed = before == temporary
                if (!changed) {
                    state.log(Level.INFO, "Mine recovery due skipped reason=block_changed zone=${record.zoneId} record=${record.id} " +
                        "position=${record.positionKey} expected=$temporary before=$before next=$next")
                    retire(record, "changed")
                    processed++
                    return@runCatching
                }
                if (world.players.any { player ->
                        player.location.distanceSquared(block.location.toCenterLocation()) <= RESTORE_PLAYER_RADIUS_SQUARED
                    }) return@runCatching
                block.setType(next, false)
                state.log(Level.INFO, "Mine recovery due zone=${record.zoneId} record=${record.id} " +
                    "position=${record.positionKey} expected=$temporary before=$before next=$next changed=$changed " +
                    "restoreAt=${record.restoreAt} overdueMillis=${(now - record.restoreAt).coerceAtLeast(0)}")
                retire(record, "restored")
                processed++
            }.onFailure { failure ->
                state.log(Level.WARNING, "Could not restore mine block ${record.id}", failure)
            }
        }
        return processed
    }

    fun restoreNow(block: Block): CompletableFuture<Boolean> {
        val positionKey = "${block.world.name}:${block.x}:${block.y}:${block.z}"
        val record = journal.records().firstOrNull { it.positionKey == positionKey }
            ?: return CompletableFuture.completedFuture(false)
        val temporary = material(record.temporaryMaterial, record)
            ?: return CompletableFuture.completedFuture(false)
        val next = material(record.nextMaterial, record) ?: return CompletableFuture.completedFuture(false)
        val before = block.type
        val changed = before == temporary
        if (changed) block.setType(next, false)
        state.log(Level.INFO, "Mine recovery forced zone=${record.zoneId} record=${record.id} " +
            "position=${record.positionKey} expected=$temporary before=$before next=$next changed=$changed")
        retire(record, "restored-now")
        return CompletableFuture.completedFuture(true)
    }

    override fun activateLoadedState() {
        val records = journal.records()
        val now = clock()
        state.log(Level.INFO, "Mine recovery activated pending=${records.size} due=${records.count { it.restoreAt <= now }} " +
            "zones=${records.groupingBy(PendingMineBlock::zoneId).eachCount()}")
        val processed = processDue(now)
        state.log(Level.INFO, "Mine recovery activation completed processed=$processed remaining=${journal.records().size}")
    }

    override fun reconcileChunk(chunk: Chunk) {
        processDue()
    }

    override fun beforeReload(reason: String) = cancelPending()

    override fun cleanup(reason: String) {
        state.log(Level.INFO, "Mine recovery cleanup started reason=$reason pending=${journal.records().size} inFlight=${pendingResults.size}")
        cancelPending()
        val processed = processDue(Long.MAX_VALUE, 262_144)
        state.log(Level.INFO, "Mine recovery cleanup completed reason=$reason processed=$processed remaining=${journal.records().size}")
    }

    private fun cancelPending() {
        val cancelled = pendingResults.values.toList()
        pendingResults.clear()
        inFlightPositions.clear()
        cancelled.forEach { it.complete(false) }
    }

    private fun release(positionKey: String, result: CompletableFuture<Boolean>) {
        pendingResults.remove(positionKey, result)
        inFlightPositions.remove(positionKey)
    }

    private fun retire(record: PendingMineBlock, reason: String) {
        if (!retiringRecords.add(record.id)) return
        journal.remove(record.id).whenComplete { _, failure ->
            retiringRecords.remove(record.id)
            if (failure != null) state.log(Level.SEVERE, "Could not retire $reason mine journal record ${record.id}", failure)
            else state.log(Level.INFO, "Mine recovery journal retired reason=$reason zone=${record.zoneId} " +
                "record=${record.id} position=${record.positionKey}")
        }
    }

    private fun material(name: String, record: PendingMineBlock): Material? =
        runCatching { MaterialRules.material(name) }.getOrNull().also { material ->
            if (material == null && access.allowInteraction("mine-journal-material:${record.id}", TimeUnit.MINUTES.toMillis(5))) {
                state.log(Level.SEVERE, "Mine journal record ${record.id} contains an unknown material and was retained for recovery")
            }
        }

    private fun PendingMineBlock.isOrdinaryMiningBreak(): Boolean =
        id.startsWith(MINING_RECORD_PREFIX) && temporaryMaterial == Material.AIR.name

    private companion object {
        const val MINING_RECORD_PREFIX = "mine:"
        const val RESTORE_PLAYER_RADIUS_BLOCKS = 12.0
        const val RESTORE_PLAYER_RADIUS_SQUARED = RESTORE_PLAYER_RADIUS_BLOCKS * RESTORE_PLAYER_RADIUS_BLOCKS
    }
}
