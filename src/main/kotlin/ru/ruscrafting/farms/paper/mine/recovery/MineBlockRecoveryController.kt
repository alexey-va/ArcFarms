package ru.ruscrafting.farms.paper.mine.recovery

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.block.Block
import ru.ruscrafting.farms.domain.PendingMineBlock
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.worksite.RuntimeComponent
import ru.ruscrafting.farms.persistence.MineRecoveryJournal
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Level

/** Owns the unchanged V1 journal schema and converges only loaded due records. */
internal class MineBlockRecoveryController(
    private val journal: MineRecoveryJournal,
    private val port: WorksiteRuntimePort,
    private val clock: () -> Long,
) : RuntimeComponent {
    private val inFlightPositions = ConcurrentHashMap.newKeySet<String>()
    private val retiringRecords = ConcurrentHashMap.newKeySet<String>()

    val pendingCount: Int get() = journal.records().size

    fun canStart(zoneId: String): Boolean = journal.records().none { it.zoneId == zoneId }

    fun containsPosition(positionKey: String): Boolean =
        journal.containsPosition(positionKey) || positionKey in inFlightPositions

    fun prepare(
        record: PendingMineBlock,
        block: Block,
        expectedOriginal: Material,
        mutation: () -> Unit,
    ): CompletableFuture<Boolean> {
        if (!inFlightPositions.add(record.positionKey) || journal.containsPosition(record.positionKey)) {
            return CompletableFuture.completedFuture(false)
        }
        val token = port.lifecycleToken()
        val result = CompletableFuture<Boolean>()
        journal.prepare(record).whenComplete { _, failure ->
            if (failure != null) {
                inFlightPositions.remove(record.positionKey)
                result.completeExceptionally(failure)
                return@whenComplete
            }
            if (!port.runSync(token) {
                    try {
                        if (block.type != expectedOriginal || !port.isOperational()) {
                            retire(record, "stale")
                            result.complete(false)
                        } else {
                            mutation()
                            result.complete(true)
                        }
                    } catch (mutationFailure: Throwable) {
                        result.completeExceptionally(mutationFailure)
                    } finally {
                        inFlightPositions.remove(record.positionKey)
                    }
                }
            ) {
                inFlightPositions.remove(record.positionKey)
                retire(record, "stale")
                result.complete(false)
            }
        }
        return result
    }

    fun processDue(now: Long = clock(), budget: Int = 128): Int {
        require(budget in 1..262_144)
        var processed = 0
        journal.records().asSequence().filter { it.restoreAt <= now }.take(budget).forEach { record ->
            val world = Bukkit.getWorld(record.world) ?: return@forEach
            if (!world.isChunkLoaded(record.x shr 4, record.z shr 4)) return@forEach
            val temporary = material(record.temporaryMaterial, record) ?: return@forEach
            val next = material(record.nextMaterial, record) ?: return@forEach
            val block = world.getBlockAt(record.x, record.y, record.z)
            runCatching {
                if (block.type == temporary) block.setType(next, false)
                retire(record, "restored")
                processed++
            }.onFailure { failure ->
                port.log(Level.WARNING, "Could not restore mine block ${record.id}", failure)
            }
        }
        return processed
    }

    override fun activateLoadedState() {
        processDue()
    }

    override fun reconcileChunk(chunk: Chunk) {
        processDue()
    }

    override fun cleanup(reason: String) {
        inFlightPositions.clear()
        processDue(Long.MAX_VALUE, 262_144)
    }

    private fun retire(record: PendingMineBlock, reason: String) {
        if (!retiringRecords.add(record.id)) return
        journal.remove(record.id).whenComplete { _, failure ->
            retiringRecords.remove(record.id)
            if (failure != null) port.log(Level.SEVERE, "Could not retire $reason mine journal record ${record.id}", failure)
        }
    }

    private fun material(name: String, record: PendingMineBlock): Material? =
        runCatching { MaterialRules.material(name) }.getOrNull().also { material ->
            if (material == null && port.allowInteraction("mine-journal-material:${record.id}", TimeUnit.MINUTES.toMillis(5))) {
                port.log(Level.SEVERE, "Mine journal record ${record.id} contains an unknown material and was retained for recovery")
            }
        }
}
