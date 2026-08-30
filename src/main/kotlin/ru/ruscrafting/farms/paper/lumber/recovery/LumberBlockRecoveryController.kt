package ru.ruscrafting.farms.paper.lumber.recovery

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.ruscrafting.farms.domain.PendingLumberBlock
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.lumber.LumberRuntime
import ru.ruscrafting.farms.paper.worksite.RuntimeComponent
import ru.ruscrafting.farms.persistence.LumberRecoveryJournal
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level

internal interface LumberBlockEffects {
    fun drops(block: Block, tool: ItemStack, player: Player): List<ItemStack>
    fun remove(block: Block)
    fun deliver(block: Block, drops: List<ItemStack>)
    fun restore(block: Block, blockData: String)
    fun placeTemporary(block: Block, material: Material) = block.setType(material, false)
}

internal object PaperLumberBlockEffects : LumberBlockEffects {
    override fun drops(block: Block, tool: ItemStack, player: Player): List<ItemStack> =
        block.getDrops(tool, player).map(ItemStack::clone)

    override fun remove(block: Block) = block.setType(Material.AIR, false)

    override fun deliver(block: Block, drops: List<ItemStack>) {
        drops.forEach { block.world.dropItemNaturally(block.location.toCenterLocation(), it) }
    }

    override fun restore(block: Block, blockData: String) {
        block.setBlockData(Bukkit.createBlockData(blockData), false)
    }
}

/** Journals original data before any destructive lumber mutation and converges loaded due records. */
internal class LumberBlockRecoveryController(
    private val journal: LumberRecoveryJournal,
    private val port: WorksiteRuntimePort,
    private val clock: () -> Long,
    private val effects: LumberBlockEffects = PaperLumberBlockEffects,
) : RuntimeComponent {
    private val inFlight = mutableSetOf<String>()
    private val nonce = AtomicLong()

    fun prepare(
        runtime: LumberRuntime,
        player: Player,
        block: Block,
        tool: ItemStack,
        stillValid: () -> Boolean = { true },
        afterMutation: () -> Unit = {},
    ): CompletableFuture<Boolean> {
        val positionKey = positionKey(block)
        if (journal.containsPosition(positionKey) || !inFlight.add(positionKey)) {
            return CompletableFuture.completedFuture(false)
        }
        val originalData = block.blockData.asString
        val drops = effects.drops(block, tool.clone(), player).map(ItemStack::clone)
        val sequence = runtime.state.sequence
        val record = PendingLumberBlock(
            id = "${runtime.settings.id}:${sequence}:${block.world.name}:${block.x}:${block.y}:${block.z}:${nonce.incrementAndGet()}",
            zoneId = runtime.settings.id,
            world = block.world.name,
            x = block.x,
            y = block.y,
            z = block.z,
            originalBlockData = originalData,
            restoreAt = clock() + runtime.settings.recoverySeconds * 1_000L,
        )
        val token = port.lifecycleToken()
        val result = CompletableFuture<Boolean>()
        journal.prepare(record).whenComplete { _, failure ->
            if (failure != null) {
                inFlight.remove(positionKey)
                result.completeExceptionally(failure)
                return@whenComplete
            }
            val scheduled = port.runSync(token) {
                try {
                    val valid = runtime.state.sequence == sequence && block.blockData.asString == originalData && stillValid()
                    if (!valid) {
                        result.complete(false)
                        return@runSync
                    }
                    effects.remove(block)
                    effects.deliver(block, drops)
                    afterMutation()
                    result.complete(true)
                } catch (mutationFailure: Throwable) {
                    result.completeExceptionally(mutationFailure)
                } finally {
                    inFlight.remove(positionKey)
                }
            }
            if (!scheduled) {
                inFlight.remove(positionKey)
                result.complete(false)
            }
        }
        return result
    }

    fun prepareTemporary(
        runtime: LumberRuntime,
        block: Block,
        material: Material,
    ): CompletableFuture<Boolean> {
        require(material.isBlock && !material.isAir)
        val positionKey = positionKey(block)
        if (journal.containsPosition(positionKey) || !inFlight.add(positionKey)) {
            return CompletableFuture.completedFuture(false)
        }
        val originalData = block.blockData.asString
        val sequence = runtime.state.sequence
        val record = PendingLumberBlock(
            id = "${runtime.settings.id}:temporary:$sequence:${block.world.name}:${block.x}:${block.y}:${block.z}:${nonce.incrementAndGet()}",
            zoneId = runtime.settings.id,
            world = block.world.name,
            x = block.x,
            y = block.y,
            z = block.z,
            originalBlockData = originalData,
            restoreAt = clock() + runtime.settings.recoverySeconds * 1_000L,
        )
        val token = port.lifecycleToken()
        val result = CompletableFuture<Boolean>()
        journal.prepare(record).whenComplete { _, failure ->
            if (failure != null) {
                inFlight.remove(positionKey)
                result.completeExceptionally(failure)
                return@whenComplete
            }
            if (!port.runSync(token) {
                    try {
                        if (runtime.state.sequence != sequence || block.blockData.asString != originalData) {
                            result.complete(false)
                        } else {
                            effects.placeTemporary(block, material)
                            result.complete(true)
                        }
                    } catch (mutationFailure: Throwable) {
                        result.completeExceptionally(mutationFailure)
                    } finally {
                        inFlight.remove(positionKey)
                    }
                }
            ) {
                inFlight.remove(positionKey)
                result.complete(false)
            }
        }
        return result
    }

    fun restoreNow(block: Block): CompletableFuture<Boolean> {
        val record = journal.records().firstOrNull { it.positionKey == positionKey(block) }
            ?: return CompletableFuture.completedFuture(false)
        val token = port.lifecycleToken()
        val result = CompletableFuture<Boolean>()
        if (!port.runSync(token) {
                runCatching {
                    effects.restore(block, record.originalBlockData)
                    check(block.blockData.asString == record.originalBlockData)
                    journal.remove(record.id).whenComplete { _, failure ->
                        if (failure == null) result.complete(true) else result.completeExceptionally(failure)
                    }
                }.onFailure(result::completeExceptionally)
            }
        ) result.complete(false)
        return result
    }

    fun processDue(now: Long = clock(), budget: Int = 64): Int {
        require(budget in 1..262_144)
        var processed = 0
        journal.records().asSequence().filter { it.restoreAt <= now }.take(budget).forEach { record ->
            val world = Bukkit.getWorld(record.world) ?: return@forEach
            if (!world.isChunkLoaded(record.x shr 4, record.z shr 4)) return@forEach
            val block = world.getBlockAt(record.x, record.y, record.z)
            runCatching {
                if (block.blockData.asString != record.originalBlockData) effects.restore(block, record.originalBlockData)
                check(block.blockData.asString == record.originalBlockData) { "Lumber block restoration did not converge" }
                journal.remove(record.id).whenComplete { _, failure ->
                    if (failure != null) port.log(Level.WARNING, "Could not retire lumber recovery ${record.id}", failure)
                }
                processed++
            }.onFailure { failure -> port.log(Level.WARNING, "Could not restore lumber block ${record.id}", failure) }
        }
        return processed
    }

    override fun activateLoadedState() {
        processDue()
    }

    override fun reconcileChunk(chunk: org.bukkit.Chunk) {
        processDue()
    }

    override fun cleanup(reason: String) {
        inFlight.clear()
        processDue(Long.MAX_VALUE, 262_144)
    }

    private fun positionKey(block: Block): String = "${block.world.name}:${block.x}:${block.y}:${block.z}"
}
