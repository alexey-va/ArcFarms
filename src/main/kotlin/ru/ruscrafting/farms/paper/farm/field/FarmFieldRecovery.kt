package ru.ruscrafting.farms.paper.farm.field

import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import org.bukkit.Material
import org.bukkit.block.BlockFace
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.captureBlocksAsync
import ru.ruscrafting.farms.paper.restoreBlocksAsync
import ru.ruscrafting.farms.paper.removeTransientBlocksAsync
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

/** Owns pending patch release/restore batches; their journal precedes every native mutation. */
internal class FarmFieldRecovery(private val ledger: FarmBlockLedger, private val tasks: WorksiteTaskPort) {
    private class Progress(val sequence: Long, val patch: List<FarmPlotPosition>, val restoring: Boolean) {
        var cursor = 0
        var completed: PersistentSet<FarmPlotPosition> = persistentSetOf()
    }

    private val progress = mutableMapOf<String, Progress>()
    private val pending = mutableMapOf<String, CompletableFuture<FarmPatchReleaseResult>>()
    private var epoch = 0L

    fun isPending(zoneId: String) = zoneId in pending

    fun clear() {
        epoch++
        pending.values.forEach { it.completeExceptionally(CancellationException("Farm field retired")) }
        pending.clear()
        progress.clear()
    }

    fun process(runtime: FarmRuntime, limit: Int, restoring: Boolean): CompletableFuture<FarmPatchReleaseResult> {
        require(limit > 0)
        val rules = runtime.settings
        pending[rules.id]?.let { return it }
        val patch = runtime.state.preparationPatch
        val sequence = runtime.state.sequence
        val phase = runtime.state.phase
        val source = progress[rules.id]?.takeIf { it.sequence == sequence && it.patch === patch && it.restoring == restoring }
            ?: Progress(sequence, patch, restoring).also { progress[rules.id] = it }
        val region = runtime.region
        val world = region.world
        val worldName = world.name
        val mechanized = runtime.state.mechanizedPreparation
        val token = tasks.lifecycleToken()
        val expectedEpoch = epoch
        val result = CompletableFuture<FarmPatchReleaseResult>()
        pending[rules.id] = result
        fun current() = epoch == expectedEpoch && pending[rules.id] === result && runtime.settings === rules &&
            runtime.region === region && runtime.state.sequence == sequence && runtime.state.preparationPatch === patch &&
            runtime.state.phase == phase
        val selected = arrayListOf<FarmPlotPosition>()
        var visited = 0
        var processed = 0
        val wholeRelease = mechanized && !restoring && limit >= patch.size
        val bedsPerStep = if (wholeRelease) 2 else 1
        val completedBefore = source.completed
        val selection = if (wholeRelease) tasks.compute {
            patch.filter { it !in completedBefore && it.world == worldName }
                .groupBy { it.x shr 4 to (it.z shr 4) }
        } else tasks.capture(token, ::current) {
            if (visited >= patch.size || selected.size >= limit) return@capture true
            if (source.cursor >= patch.size) source.cursor = 0
            val position = patch[source.cursor++]
            visited++
            if (position !in source.completed && position.world == worldName &&
                world.isChunkLoaded(position.x shr 4, position.z shr 4)) selected += position
            visited >= patch.size || selected.size >= limit
        }.thenCompose { tasks.compute { selected.groupBy { it.x shr 4 to (it.z shr 4) } } }
        selection
            .thenCompose { groups -> tasks.callSync(token) {
                fun processChunk(coordinate: Pair<Int, Int>, positions: List<FarmPlotPosition>): CompletableFuture<Unit> {
                    if (!current()) return CompletableFuture.failedFuture(CancellationException("Farm patch source changed"))
                    var loaded: org.bukkit.Chunk? = null
                    val operation = tasks.capture(token, ::current) {
                        if (world.isChunkLoaded(coordinate.first, coordinate.second)) loaded = world.getChunkAt(coordinate.first, coordinate.second)
                        true
                    }.thenCompose {
                      val chunk = loaded ?: return@thenCompose CompletableFuture.completedFuture(Unit)
                      if (restoring) {
                        ledger.restoreBlocksAsync(chunk, positions, clear = false, tasks, ::current).thenCompose { restored ->
                            val previous = source.completed
                            tasks.compute { previous.addAll(restored) }.thenCompose { completed -> tasks.callSync(token) {
                                if (!current()) throw CancellationException("Farm patch restore source changed")
                                source.completed = completed
                                processed += restored.size
                            } }
                        }
                    } else {
                        ledger.captureBlocksAsync(chunk, positions, rules.id, mechanized, tasks, ::current, bedsPerStep).thenCompose { captured ->
                            val beds = captured.iterator()
                            tasks.capture(token, { current() && chunk.isLoaded }) {
                                repeat(bedsPerStep) {
                                    if (!beds.hasNext()) return@capture true
                                    val bed = beds.next()
                                    val soil = world.getBlockAt(bed.position.x, bed.position.y, bed.position.z)
                                    val crop = soil.getRelative(BlockFace.UP)
                                    if (soil.blockData.asString != bed.soilData || crop.blockData.asString != bed.cropData)
                                        throw CancellationException("Farm bed changed after its journal was captured")
                                    if (crop.type.name in rules.crops && !MaterialRules.isFixedBlockCrop(crop.type)) crop.setType(Material.AIR, false)
                                    soil.setType(Material.DIRT, false)
                                    source.completed = source.completed.add(bed.position)
                                    processed++
                                }
                                !beds.hasNext()
                            }
                        }
                      }
                    }
                    return operation
                }
                if (wholeRelease) {
                    // Independent chunk journals may prepare together. Every native step
                    // still shares the runtime queue and follows that chunk's committed journal.
                    CompletableFuture.allOf(*groups.map { (coordinate, positions) ->
                        processChunk(coordinate, positions)
                    }.toTypedArray()).thenApply { Unit }
                } else {
                    val iterator = groups.entries.iterator()
                    fun next(): CompletableFuture<Unit> {
                        if (!iterator.hasNext()) return CompletableFuture.completedFuture(Unit)
                        val (coordinate, positions) = iterator.next()
                        return processChunk(coordinate, positions)
                            .thenCompose { tasks.callSync(token) { next() }.thenCompose { it } }
                    }
                    next()
                }
            }.thenCompose { it } }
            .whenComplete { _, failure ->
                val delivered = tasks.runSync(token) {
                    if (pending[rules.id] !== result) return@runSync
                    pending.remove(rules.id, result)
                    if (failure != null) result.completeExceptionally(failure)
                    else result.complete(FarmPatchReleaseResult(processed, source.completed.size == patch.size))
                }
                if (!delivered) result.completeExceptionally(CancellationException("Farm field retired"))
            }
        return result
    }

    /** The state writer has acknowledged recovery. Stale generation/sequence work leaves the journal intact. */
    fun clearTransient(runtime: FarmRuntime, recovered: List<FarmPlotPosition>, sequence: Long): CompletableFuture<Unit> {
        val token = tasks.lifecycleToken()
        val expectedEpoch = epoch
        val world = runtime.region.world
        val worldName = world.name
        fun current() = epoch == expectedEpoch && runtime.state.sequence == sequence
        return tasks.compute { recovered.groupBy { it.x shr 4 to (it.z shr 4) }.entries.iterator() }.thenCompose { groups ->
            tasks.callSync(token) {
                fun next(): CompletableFuture<Unit> {
                    if (!current()) return CompletableFuture.failedFuture(CancellationException("Farm recovery source changed"))
                    if (!groups.hasNext()) return CompletableFuture.completedFuture(Unit)
                    val (coordinate, positions) = groups.next()
                    return tasks.capture(token, ::current) { true }.thenCompose {
                        if (positions.first().world != worldName || !world.isChunkLoaded(coordinate.first, coordinate.second)) next()
                        else ledger.removeTransientBlocksAsync(world.getChunkAt(coordinate.first, coordinate.second), positions, tasks, ::current)
                            .thenCompose { next() }
                    }
                }
                next()
            }.thenCompose { it }
        }
    }
}
