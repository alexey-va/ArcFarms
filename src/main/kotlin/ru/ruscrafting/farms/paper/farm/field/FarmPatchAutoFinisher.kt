package ru.ruscrafting.farms.paper.farm.field

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.type.Farmland
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmSeederStage
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.block
import ru.ruscrafting.farms.paper.captureBlocksAsync
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException

/** Applies the tolerated field remainder gradually after the player-visible quota has completed. */
internal class FarmPatchAutoFinisher(
    private val ledger: FarmBlockLedger,
    private val debug: ArcFarmsDebug,
    private val tasks: WorksiteTaskPort,
) {
    private val completedActions = mutableSetOf<ActionKey>()
    private val pending = mutableMapOf<String, CompletableFuture<Int>>()
    private var epoch = 0L

    fun clear() {
        epoch++
        pending.values.forEach { it.completeExceptionally(CancellationException("Farm auto-finish retired")) }
        pending.clear()
        completedActions.clear()
    }

    fun isPending(zoneId: String) = zoneId in pending

    fun processAsync(runtime: FarmRuntime, limit: Int): CompletableFuture<Int> {
        require(limit >= 1)
        val rules = runtime.settings
        pending[rules.id]?.let { return it }
        val source = runtime.state
        if (!source.preparationReleased || source.preparationPatch.isEmpty()) return CompletableFuture.completedFuture(0)
        val action = action(runtime) ?: return CompletableFuture.completedFuture(0)
        val key = ActionKey(rules.id, source.sequence, action)
        completedActions.removeIf { it.zoneId == rules.id && it.sequence != source.sequence }
        if (key in completedActions) return CompletableFuture.completedFuture(0)
        val region = runtime.region
        val world = region.world
        val worldName = world.name
        val crop = source.preparationCrop?.let(MaterialRules::material)
        val token = tasks.lifecycleToken()
        val expectedEpoch = epoch
        val result = CompletableFuture<Int>()
        pending[rules.id] = result
        var processed = 0
        fun current() = epoch == expectedEpoch && pending[rules.id] === result && runtime.state === source &&
            runtime.settings === rules && runtime.region === region
        tasks.compute {
            // A quota selects the first matching beds in patch order. Group only consecutive
            // chunk runs: grouping all beds by chunk would change that selection at the limit.
            val runs = arrayListOf<Pair<Pair<Int, Int>, MutableList<FarmPlotPosition>>>()
            for (position in source.preparationPatch) if (position.world == worldName) {
                val coordinate = position.x shr 4 to (position.z shr 4)
                if (runs.lastOrNull()?.first != coordinate) runs += coordinate to arrayListOf()
                runs.last().second += position
            }
            runs.iterator()
        }
            .thenCompose { groups -> tasks.callSync(token) {
                fun next(): CompletableFuture<Unit> {
                    if (!current()) return CompletableFuture.failedFuture(CancellationException("Farm auto-finish source changed"))
                    if (!groups.hasNext() || processed >= limit) return CompletableFuture.completedFuture(Unit)
                    val (coordinate, positions) = groups.next()
                    var loaded: org.bukkit.Chunk? = null
                    return tasks.capture(token, ::current) {
                        if (world.isChunkLoaded(coordinate.first, coordinate.second)) loaded = world.getChunkAt(coordinate.first, coordinate.second)
                        true
                    }.thenCompose {
                        val chunk = loaded ?: return@thenCompose CompletableFuture.completedFuture(Unit)
                        ledger.loadAsync(chunk, tasks, ::current).thenCompose { records ->
                            val iterator = positions.iterator()
                            val selected = arrayListOf<ru.ruscrafting.farms.domain.FarmPlotPosition>()
                            tasks.capture(token, { current() && chunk.isLoaded }) {
                                if (!iterator.hasNext() || processed + selected.size >= limit) return@capture true
                                val position = iterator.next()
                                val soil = world.getBlockAt(position.x, position.y, position.z)
                                if (action.needsMutation(soil, crop, records.blocks.record(position.x, position.y, position.z))) selected += position
                                !iterator.hasNext() || processed + selected.size >= limit
                            }.thenCompose {
                                if (selected.isEmpty()) return@thenCompose CompletableFuture.completedFuture(Unit)
                                ledger.captureBlocksAsync(chunk, selected, rules.id, false, tasks, ::current).thenCompose { captured ->
                                    val beds = captured.iterator()
                                    tasks.capture(token, { current() && chunk.isLoaded }) {
                                        if (!beds.hasNext()) return@capture true
                                        val bed = beds.next()
                                        val soil = world.getBlockAt(bed.position.x, bed.position.y, bed.position.z)
                                        if (soil.blockData.asString != bed.soilData || soil.getRelative(org.bukkit.block.BlockFace.UP).blockData.asString != bed.cropData)
                                            throw CancellationException("Farm auto-finish bed changed after journal capture")
                                        action.mutate(soil, crop, ledger, bed.record)
                                        processed++
                                        !beds.hasNext()
                                    }.thenCompose {
                                        if (action != Action.PLANT) CompletableFuture.completedFuture(Unit)
                                        else ledger.captureBlocksAsync(chunk, selected, rules.id, true, tasks, ::current).thenApply { Unit }
                                    }
                                }
                            }
                        }
                    }.thenCompose { next() }
                }
                next()
            }.thenCompose { it } }.whenComplete { _, failure ->
                val delivered = tasks.runSync(token) {
                    if (pending[rules.id] !== result) return@runSync
                    pending.remove(rules.id, result)
                    if (failure != null) result.completeExceptionally(failure) else {
                        val complete = processed < limit
                        if (complete) completedActions += key
                        debug.event("farm_patch_auto_finish", "zone" to rules.id, "sequence" to source.sequence,
                            "action" to action.name.lowercase(), "processed" to processed, "complete" to complete)
                        result.complete(processed)
                    }
                }
                if (!delivered) result.completeExceptionally(CancellationException("Farm auto-finish retired"))
            }
        return result
    }

    fun hasPending(runtime: FarmRuntime): Boolean {
        val action = action(runtime) ?: return false
        return ActionKey(runtime.settings.id, runtime.state.sequence, action) !in completedActions
    }

    fun process(runtime: FarmRuntime, limit: Int): Int {
        require(limit >= 1) { "Farm patch auto-finish limit must be positive" }
        if (!runtime.state.preparationReleased || runtime.state.preparationPatch.isEmpty()) return 0
        val action = action(runtime) ?: return 0
        val key = ActionKey(runtime.settings.id, runtime.state.sequence, action)
        completedActions.removeIf { it.zoneId == runtime.settings.id && it.sequence != runtime.state.sequence }
        if (key in completedActions) return 0
        val crop = runtime.state.preparationCrop?.let(MaterialRules::material)
        val selected = mutableListOf<Block>()
        for (position in runtime.state.preparationPatch) {
            if (selected.size >= limit) break
            val soil = position.block() ?: continue
            if (action.needsMutation(soil, crop, ledger.record(soil))) selected += soil
        }
        if (selected.isNotEmpty()) {
            ledger.captureAll(selected, runtime.settings.id)
            selected.forEach { soil -> action.mutate(soil, crop, ledger) }
            if (action == Action.PLANT) ledger.updateActiveCrops(selected)
        }
        val complete = selected.size < limit
        if (complete) completedActions += key
        debug.event(
            "farm_patch_auto_finish",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "action" to action.name.lowercase(),
            "processed" to selected.size,
            "complete" to complete,
        )
        return selected.size
    }

    private data class ActionKey(val zoneId: String, val sequence: Long, val action: Action)

    private fun action(runtime: FarmRuntime): Action? = when {
        runtime.state.phase == FarmPhase.PLANTING -> Action.TILL
        runtime.state.phase == FarmPhase.CARE && runtime.state.careType == FarmCareType.SEEDER &&
            runtime.state.seederStage == FarmSeederStage.PLANTING -> Action.TILL
        runtime.state.mechanizedPreparation &&
            runtime.state.plantedPlots.containsAll(runtime.state.preparationPatch) -> Action.RESTORE_RECORDED_CROP
        (runtime.state.phase == FarmPhase.HARVESTING || runtime.state.phase == FarmPhase.CARE) &&
            runtime.state.plantedPlots.containsAll(runtime.state.preparationPatch) -> Action.PLANT
        else -> null
    }

    private enum class Action {
        TILL,
        PLANT,
        RESTORE_RECORDED_CROP;

        fun needsMutation(
            soil: Block,
            crop: Material?,
            record: ru.ruscrafting.farms.paper.ManagedFarmBlockRecord?,
        ): Boolean = when (this) {
            TILL -> soil.type != Material.FARMLAND
            PLANT -> crop != null && soil.getRelative(org.bukkit.block.BlockFace.UP).let { above ->
                (above.type.isAir || above.type == crop) && (soil.type != Material.FARMLAND || above.type != crop)
            }
            RESTORE_RECORDED_CROP -> record?.activeCropData != null &&
                (soil.type != Material.FARMLAND || soil.getRelative(org.bukkit.block.BlockFace.UP).blockData.asString != record.activeCropData)
        }

        fun mutate(soil: Block, crop: Material?, ledger: FarmBlockLedger,
            record: ru.ruscrafting.farms.paper.ManagedFarmBlockRecord? = null) {
            if (soil.type != Material.FARMLAND) soil.setType(Material.FARMLAND, false)
            val farmland = (soil.blockData as? Farmland) ?: (Material.FARMLAND.createBlockData() as Farmland)
            farmland.moisture = farmland.maximumMoisture
            soil.setBlockData(farmland, false)
            when (this) {
                PLANT -> soil.getRelative(org.bukkit.block.BlockFace.UP)
                    .setBlockData(requireNotNull(crop).createBlockData(), false)
                RESTORE_RECORDED_CROP -> if (record == null) ledger.restoreActiveCrop(soil) else ledger.restoreActiveCrop(soil, record)
                TILL -> Unit
            }
        }
    }
}
