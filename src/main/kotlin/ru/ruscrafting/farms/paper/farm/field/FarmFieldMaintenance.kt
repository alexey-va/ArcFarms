package ru.ruscrafting.farms.paper.farm.field

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Farmland
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmChannelOwnership
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmBlockPolicy
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.ManagedFarmBlockRecord
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.block
import ru.ruscrafting.farms.paper.farm.care.FarmDitchLayout
import ru.ruscrafting.farms.paper.restoreTemporaryBlocksAsync
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/** Coalesces periodic hydration/crop repair and keeps each native pass within the shared queue. */
internal class FarmFieldMaintenance(
    private val ledger: FarmBlockLedger,
    private val registry: FarmBlockRegistry,
    private val tasks: WorksiteTaskPort,
    private val access: WorksiteAccessPort,
    private val logging: WorksiteStatePort,
) {
    private class View(val state: FarmShiftState) {
        val preparation = state.preparationPatch.toHashSet()
        val disease = state.diseaseDamagedCrops.orEmpty().mapTo(hashSetOf()) { it.position }
        val pests = state.pestDamagedCrops.mapTo(hashSetOf()) { it.position }
        val special = state.specialDamagedCrops.mapTo(hashSetOf()) { it.position }
        val flowed = FarmChannelOwnership.flowedPlots(state)
        val dug = FarmChannelOwnership.dugPlots(state)
        val moles = targets(FarmCareType.MOLES, FarmCareRole.MOLE_MOUND, -1)
        val ditches = if (state.phase == FarmPhase.CARE && state.careType == FarmCareType.DITCH_RESCUE)
            FarmDitchLayout.surfacePositions(state) else emptySet()

        private fun targets(type: FarmCareType, role: FarmCareRole, yOffset: Int): Set<FarmPlotPosition> =
            if (state.phase != FarmPhase.CARE || state.careType != type) emptySet() else state.careTargets.asSequence()
                .filter { it.role == role }.map { target -> FarmPlotPosition(target.position.world,
                    kotlin.math.floor(target.position.x).toInt(), kotlin.math.floor(target.position.y).toInt() + yOffset,
                    kotlin.math.floor(target.position.z).toInt()) }.toSet()

        fun controlled(position: FarmPlotPosition) = position in preparation || position in ditches ||
            position in state.droughtPlots || position in state.droughtDamagedPlots || position in pests ||
            position in disease || position in special
    }

    private val pending = mutableMapOf<String, CompletableFuture<Unit>>()
    private var epoch = 0L

    fun clear() {
        epoch++
        pending.values.forEach { it.completeExceptionally(CancellationException("Farm maintenance retired")) }
        pending.clear()
    }

    fun maintainAsync(runtime: FarmRuntime, activeWater: Boolean, irrigationDry: Set<FarmPlotPosition>,
        isCurrent: () -> Boolean): CompletableFuture<Unit> {
        val rules = runtime.settings
        pending[rules.id]?.let { return it }
        val source = runtime.state
        if (source.preparationPatch.isNotEmpty() && !source.preparationReleased) return CompletableFuture.completedFuture(Unit)
        val region = runtime.region
        val world = region.world
        val worldName = world.name
        val indexed = registry.beds(rules.id)
        val crop = source.preparationCrop?.let(MaterialRules::material)
        val token = tasks.lifecycleToken()
        val expectedEpoch = epoch
        val result = CompletableFuture<Unit>()
        pending[rules.id] = result
        fun current() = epoch == expectedEpoch && pending[rules.id] === result && isCurrent() && runtime.state === source &&
            runtime.region === region && runtime.settings === rules
        tasks.compute {
            View(source) to (indexed.asSequence() + source.preparationPatch.asSequence()).distinct()
                .filter { it.world == worldName }.groupBy { it.x shr 4 to (it.z shr 4) }.entries.iterator()
        }.thenCompose { (view, groups) -> tasks.callSync(token) {
            val removed = arrayListOf<FarmPlotPosition>()
            fun next(): CompletableFuture<Unit> {
                if (!current()) return CompletableFuture.failedFuture(CancellationException("Farm maintenance source changed"))
                if (!groups.hasNext()) return registry.removeBedsAsync(rules.id, removed, ::current)
                val (coordinate, positions) = groups.next()
                var loaded: org.bukkit.Chunk? = null
                return tasks.capture(token, ::current) {
                    if (world.isChunkLoaded(coordinate.first, coordinate.second)) loaded = world.getChunkAt(coordinate.first, coordinate.second)
                    true
                }.thenCompose {
                    val chunk = loaded ?: return@thenCompose CompletableFuture.completedFuture(Unit)
                    ledger.loadAsync(chunk, tasks, ::current).thenCompose { records ->
                        val time = world.gameTime
                        tasks.compute {
                            val expired = positions.filter { position -> position !in view.ditches && records.blocks.record(position.x, position.y, position.z)
                                ?.temporaryRestoreAt?.let { it <= time } == true }
                            expired to expired.toHashSet()
                        }
                    }.thenCompose { (expired, restored) -> tasks.callSync(token) {
                        val restore = if (expired.isEmpty()) CompletableFuture.completedFuture(Unit)
                        else ledger.restoreTemporaryBlocksAsync(chunk, expired, tasks, ::current).thenApply { Unit }
                        restore.thenCompose { ledger.loadAsync(chunk, tasks, ::current) }.thenApply { it to restored }
                    }.thenCompose { it } }.thenCompose { (records, restored) ->
                        val iterator = positions.iterator()
                        tasks.capture(token, { current() && chunk.isLoaded }) {
                            if (!iterator.hasNext()) return@capture true
                            val position = iterator.next()
                            // Legacy maintenance restores an expired terrain effect and stops
                            // handling that bed for this pass; hydration resumes next pass.
                            if (position in restored) return@capture !iterator.hasNext()
                            if (maintainPosition(position, records.blocks.record(position.x, position.y, position.z), view,
                                    rules.crops, crop, activeWater, irrigationDry, restoreExpired = false)) removed += position
                            !iterator.hasNext()
                        }
                    }
                }.thenCompose { next() }
            }
            next()
        }.thenCompose { it } }.whenComplete { _, failure ->
            val delivered = tasks.runSync(token) {
                val wasCurrent = current()
                pending.remove(rules.id, result)
                if (failure == null) result.complete(Unit) else {
                    result.completeExceptionally(failure)
                    if (wasCurrent && access.allowInteraction("farm-maintenance:${rules.id}", 10_000))
                        logging.log(Level.WARNING, "Farm field maintenance will retry: ${rules.id}", failure)
                }
            }
            if (!delivered) result.completeExceptionally(CancellationException("Farm maintenance retired"))
        }
        return result
    }

    /** Small synchronous compatibility oracle; runtime maintenance uses maintainAsync. */
    fun maintain(runtime: FarmRuntime, activeWater: Boolean, irrigationDry: Set<FarmPlotPosition>) {
        val source = runtime.state
        if (source.preparationPatch.isNotEmpty() && !source.preparationReleased) return
        val view = View(source)
        val crop = source.preparationCrop?.let(MaterialRules::material)
        val removed = arrayListOf<FarmPlotPosition>()
        (registry.beds(runtime.settings.id) + source.preparationPatch).forEach { position ->
            val soil = position.block() ?: return@forEach
            if (maintainPosition(position, ledger.record(soil), view, runtime.settings.crops, crop, activeWater,
                    irrigationDry, restoreExpired = true)) removed += position
        }
        if (removed.isNotEmpty()) registry.removeBeds(runtime.settings.id, removed)
    }

    private fun maintainPosition(
        position: FarmPlotPosition, record: ManagedFarmBlockRecord?, view: View, crops: Set<String>, crop: Material?,
        activeWater: Boolean, irrigationDry: Set<FarmPlotPosition>, restoreExpired: Boolean,
    ): Boolean {
        if (position in view.ditches) return false
        val soil = position.block() ?: return false
        val source = view.state
        if (record?.temporaryMutation != null) {
            if (restoreExpired && soil.world.gameTime >= requireNotNull(record.temporaryRestoreAt))
                ledger.restoreTemporaryRemovals(listOf(soil), record.temporaryMutation)
            return false
        }
        if (position in view.flowed) { if (soil.type != Material.WATER) soil.setType(Material.WATER, false); return false }
        if (position in view.dug || position in view.moles) return false
        if (!view.controlled(position) && !FarmBlockPolicy.isRecoverableIndexedBed(
                soil.type, soil.getRelative(BlockFace.UP).type, crops)) return true
        if (position in source.droughtPlots) {
            if (soil.type != Material.DIRT) soil.setType(Material.DIRT, false)
            val above = soil.getRelative(BlockFace.UP)
            if (!above.type.isAir && (above.type != Material.WATER || !activeWater)) above.setType(Material.AIR, false)
            return false
        }
        if (position in irrigationDry || position in view.disease) return false
        if (record?.indexed == true && !view.controlled(position)) {
            wet(soil)
            val above = soil.getRelative(BlockFace.UP)
            val expected = record.activeCropData?.let { runCatching { Bukkit.createBlockData(it).material }.getOrNull() }
            if (expected != null && (above.type.isAir || above.type == Material.WATER ||
                    above.type.name in crops && above.type != expected)) ledger.restoreActiveCrop(soil, record)
        }
        val awaitingMachine = source.phase == FarmPhase.CARE && source.careType == FarmCareType.SEEDER
        if ((source.phase == FarmPhase.PREPARATION || awaitingMachine) && position in view.preparation &&
            position !in source.tilledPlots && soil.type != Material.DIRT) soil.setType(Material.DIRT, false)
        if (position in source.tilledPlots && soil.type != Material.FARMLAND) wet(soil)
        if (soil.type == Material.FARMLAND) wet(soil)
        if (source.phase == FarmPhase.INCIDENT) {
            val above = soil.getRelative(BlockFace.UP)
            if (above.type == Material.WATER && !activeWater) above.setType(Material.AIR, false)
            return false
        }
        if (position in source.plantedPlots) {
            val above = soil.getRelative(BlockFace.UP)
            if (above.type == Material.WATER && !activeWater) above.setType(Material.AIR, false)
            val expected = record?.activeCropData?.let { runCatching { Bukkit.createBlockData(it).material }.getOrNull() } ?: crop
            if (expected != null && (above.type.isAir || above.type != expected)) {
                if (record == null) above.setBlockData(expected.createBlockData(), false) else ledger.restoreActiveCrop(soil, record)
            }
        }
        return false
    }

    private fun wet(block: Block) {
        if (block.type != Material.FARMLAND) block.setType(Material.FARMLAND, false)
        val farmland = (block.blockData as? Farmland) ?: (Material.FARMLAND.createBlockData() as Farmland)
        if (farmland.moisture != farmland.maximumMoisture) {
            farmland.moisture = farmland.maximumMoisture
            block.setBlockData(farmland, false)
        }
    }
}
