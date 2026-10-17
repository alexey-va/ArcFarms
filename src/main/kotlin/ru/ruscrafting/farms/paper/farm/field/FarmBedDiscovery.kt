package ru.ruscrafting.farms.paper.farm.field

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import org.bukkit.Location
import ru.ruscrafting.farms.domain.FarmBedCandidatePool
import ru.ruscrafting.farms.domain.FarmPatchPlanner
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockPolicy
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.block
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.placement.FarmSurfacePolicy
import ru.ruscrafting.farms.paper.toFarmPlotPosition

/** Selects indexed farm beds and performs the expensive local scan only when the durable index is insufficient. */
internal class FarmBedDiscovery(
    private val debug: ArcFarmsDebug,
    private val registry: FarmBlockRegistry,
    private val points: FarmPointProvider,
    private val tasks: WorksiteTaskPort? = null,
) {
    /** Native eligibility is captured one bed at a time; component/flood planning only sees values. */
    fun selectPatchAsync(
        runtime: FarmRuntime, anchor: Location, mechanized: Boolean, selectionIndex: Long,
        additionalCandidates: Collection<FarmPlotPosition> = emptyList(), isCurrent: () -> Boolean = { true },
    ): CompletableFuture<List<FarmPlotPosition>> {
        val work = tasks ?: return CompletableFuture.completedFuture(
            selectPatch(runtime, anchor, mechanized, selectionIndex, additionalCandidates))
        val token = work.lifecycleToken()
        val source = runtime.state
        val rules = runtime.settings
        val region = runtime.region
        val anchorPlot = anchor.toFarmPlotPosition()
        val indexed = registry.beds(rules.id)
        val targetSize = if (mechanized) rules.seederPatchSize else rules.preparationPatchSize
        val maxSize = if (mechanized) rules.seederPatchMaxSize else rules.preparationPatchMaxSize
        fun current() = isCurrent() && runtime.state === source && runtime.settings === rules && runtime.region === region
        fun plan(candidates: Collection<FarmPlotPosition>) = if (mechanized) FarmPatchPlanner.selectMechanized(
            candidates, anchorPlot, targetSize, maxSize, rules.seederComponentGap, rules.seederComponentLimit, selectionIndex,
        ) else FarmPatchPlanner.select(candidates, anchorPlot, targetSize, maxSize, selectionIndex)

        return operationPointsAsync(runtime, ::current).thenCompose { operationPoints ->
            availableAsync(runtime, indexed, additionalCandidates, operationPoints, ::current).thenCompose { candidates ->
                work.compute { plan(candidates) }.thenCompose { selected -> work.callSync(token) {
                    if (!current()) throw CancellationException("Farm patch source changed")
                    if (selected.size >= targetSize) {
                        debug.event("farm_patch_index_fast_path", "zone" to rules.id, "indexed" to candidates.size,
                            "selected" to selected.size, "mechanized" to mechanized)
                        CompletableFuture.completedFuture(selected)
                    } else {
                        discoverAsync(runtime, anchorPlot, targetSize, operationPoints, ::current)
                            .thenCompose { discovered -> work.compute { plan(discovered + additionalCandidates) } }
                    }
                }.thenCompose { it } }
            }.thenCompose { selected -> work.callSync(token) {
                availableAsync(runtime, selected, emptyList(), operationPoints, ::current).thenApply { valid ->
                    if (selected.size != valid.size) throw CancellationException("Selected farm patch changed")
                    selected
                }
            }.thenCompose { it } }
        }
    }

    private fun discoverAsync(
        runtime: FarmRuntime, anchor: FarmPlotPosition, candidateLimit: Int,
        operationPoints: List<FarmPointPosition>, isCurrent: () -> Boolean,
    ): CompletableFuture<Set<FarmPlotPosition>> {
        require(candidateLimit in 1..MAX_LOCAL_SCAN_CANDIDATES) { "Farm bed scan candidate limit is invalid" }
        val work = requireNotNull(tasks)
        val token = work.lifecycleToken()
        val rules = runtime.settings
        val world = runtime.region.world
        val minimumY = (anchor.y - 5).coerceAtLeast(world.minHeight)
        val maximumY = (anchor.y + 3).coerceAtMost(world.maxHeight - 1)
        val columns = columnsAround(anchor.x, anchor.z, rules.preparationSearchRadius).iterator()
        var column: Pair<Int, Int>? = null
        var y = minimumY
        var scanned = 0
        val discovered = linkedSetOf<FarmPlotPosition>()
        return work.capture(token, isCurrent) {
            if (scanned >= MAX_LOCAL_SCAN_BLOCKS || discovered.size >= candidateLimit) return@capture true
            if (column == null) {
                if (!columns.hasNext()) return@capture true
                val next = columns.next()
                if (world.isChunkLoaded(next.first shr 4, next.second shr 4) && minimumY <= maximumY) {
                    column = next
                    y = minimumY
                }
            } else {
                val (x, z) = requireNotNull(column)
                if (world.isChunkLoaded(x shr 4, z shr 4)) {
                    scanned++
                    val soil = world.getBlockAt(x, y, z)
                    if (runtime.region.contains(soil.location) && !isNearOperationPoint(soil.location, operationPoints) &&
                        FarmBlockPolicy.isSelectableBed(soil.type, soil.getRelative(org.bukkit.block.BlockFace.UP).type, rules.crops) &&
                        FarmSurfacePolicy.isOutdoorBed(soil)) discovered += soil.toFarmPlotPosition()
                }
                if (++y > maximumY) column = null
            }
            false
        }.thenCompose { registry.addBedsAsync(rules.id, discovered, isCurrent) }.thenCompose {
            val indexed = registry.beds(rules.id)
            availableAsync(runtime, indexed, discovered, operationPoints, isCurrent).thenApply { candidates ->
                debug.event("farm_beds_discovered", "zone" to rules.id, "indexed" to indexed.size,
                    "locally_discovered" to discovered.size, "candidates" to candidates.size,
                    "search_radius" to rules.preparationSearchRadius, "scanned_blocks" to scanned,
                    "scan_limited" to (scanned >= MAX_LOCAL_SCAN_BLOCKS || discovered.size >= candidateLimit))
                candidates
            }
        }
    }

    private fun availableAsync(
        runtime: FarmRuntime, indexed: Collection<FarmPlotPosition>, additional: Collection<FarmPlotPosition>,
        operationPoints: List<FarmPointPosition>, isCurrent: () -> Boolean,
    ): CompletableFuture<Set<FarmPlotPosition>> {
        val work = requireNotNull(tasks)
        val token = work.lifecycleToken()
        val rules = runtime.settings
        val region = runtime.region
        val world = region.world
        val worldName = world.name
        return work.compute { FarmBedCandidatePool.merge(indexed, additional) { true }.iterator() }.thenCompose { candidates ->
            work.callSync(token) {
                val result = linkedSetOf<FarmPlotPosition>()
                work.capture(token, isCurrent) {
                    if (!candidates.hasNext()) return@capture true
                    val position = candidates.next()
                    if (position.world == worldName && world.isChunkLoaded(position.x shr 4, position.z shr 4)) {
                        val soil = world.getBlockAt(position.x, position.y, position.z)
                        if (region.contains(soil.location) && !isNearOperationPoint(soil.location, operationPoints) &&
                            FarmSurfacePolicy.isOutdoorBed(soil) && FarmBlockPolicy.isSelectableBed(
                                soil.type, soil.getRelative(org.bukkit.block.BlockFace.UP).type, rules.crops)) result += position
                    }
                    !candidates.hasNext()
                }.thenApply { result as Set<FarmPlotPosition> }
            }.thenCompose { it }
        }
    }

    private fun operationPointsAsync(runtime: FarmRuntime, isCurrent: () -> Boolean): CompletableFuture<List<FarmPointPosition>> {
        val work = requireNotNull(tasks)
        val token = work.lifecycleToken()
        val result = arrayListOf<FarmPointPosition>()
        var tail = CompletableFuture.completedFuture(Unit)
        for (kind in OPERATION_POINT_KINDS) tail = tail.thenCompose {
            work.callSync(token) {
                if (!isCurrent()) throw CancellationException("Farm operation point source changed")
                points.resolveAsync(runtime, kind, isCurrent)
            }.thenCompose { it }.thenApply { result += it; Unit }
        }
        return tail.thenCompose { work.callSync(token) { result as List<FarmPointPosition> } }
    }

    fun selectPatch(
        runtime: FarmRuntime,
        anchor: Location,
        mechanized: Boolean,
        selectionIndex: Long,
        additionalCandidates: Collection<FarmPlotPosition> = emptyList(),
    ): List<FarmPlotPosition> {
        val anchorPlot = anchor.toFarmPlotPosition()
        val targetSize = if (mechanized) runtime.settings.seederPatchSize else runtime.settings.preparationPatchSize
        val maxSize = if (mechanized) runtime.settings.seederPatchMaxSize else runtime.settings.preparationPatchMaxSize
        fun plan(candidates: Collection<FarmPlotPosition>): List<FarmPlotPosition> = if (mechanized) {
            FarmPatchPlanner.selectMechanized(
                candidates = candidates,
                anchor = anchorPlot,
                targetSize = targetSize,
                maxSize = maxSize,
                componentGap = runtime.settings.seederComponentGap,
                maxComponents = runtime.settings.seederComponentLimit,
                selectionIndex = selectionIndex,
            )
        } else {
            FarmPatchPlanner.select(
                candidates = candidates,
                anchor = anchorPlot,
                targetSize = targetSize,
                maxSize = maxSize,
                selectionIndex = selectionIndex,
            )
        }
        val indexed = available(runtime, registry.beds(runtime.settings.id) + additionalCandidates)
        val indexedSelection = plan(indexed)
        if (indexedSelection.size >= targetSize) {
            debug.event(
                "farm_patch_index_fast_path",
                "zone" to runtime.settings.id,
                "indexed" to indexed.size,
                "selected" to indexedSelection.size,
                "mechanized" to mechanized,
            )
            return indexedSelection
        }
        return plan(discover(runtime, anchor, targetSize) + additionalCandidates)
    }

    fun discover(
        runtime: FarmRuntime,
        anchor: Location,
        candidateLimit: Int = MAX_LOCAL_SCAN_CANDIDATES,
    ): Set<FarmPlotPosition> {
        require(candidateLimit in 1..MAX_LOCAL_SCAN_CANDIDATES) { "Farm bed scan candidate limit is invalid" }
        val radius = runtime.settings.preparationSearchRadius
        val discovered = linkedSetOf<FarmPlotPosition>()
        val world = runtime.region.world
        val operationPoints = operationPoints(runtime)
        var scannedBlocks = 0
        scan@ for ((x, z) in columnsAround(anchor.blockX, anchor.blockZ, radius)) {
            if (!world.isChunkLoaded(x shr 4, z shr 4)) continue
            val minimumY = (anchor.blockY - 5).coerceAtLeast(world.minHeight)
            val maximumY = (anchor.blockY + 3).coerceAtMost(world.maxHeight - 1)
            for (y in minimumY..maximumY) {
                if (scannedBlocks >= MAX_LOCAL_SCAN_BLOCKS || discovered.size >= candidateLimit) break@scan
                scannedBlocks++
                val block = world.getBlockAt(x, y, z)
                if (!runtime.region.contains(block.location)) continue
                if (isNearOperationPoint(block.location, operationPoints)) continue
                val above = block.getRelative(org.bukkit.block.BlockFace.UP).type
                if (!FarmBlockPolicy.isSelectableBed(block.type, above, runtime.settings.crops)) continue
                if (!FarmSurfacePolicy.isOutdoorBed(block)) continue
                discovered += block.toFarmPlotPosition()
            }
        }
        registry.addBeds(runtime.settings.id, discovered)
        val indexed = registry.beds(runtime.settings.id)
        val candidates = available(runtime, indexed + discovered)
        debug.event(
            "farm_beds_discovered",
            "zone" to runtime.settings.id,
            "indexed" to indexed.size,
            "locally_discovered" to discovered.size,
            "candidates" to candidates.size,
            "search_radius" to radius,
            "scanned_blocks" to scannedBlocks,
            "scan_limited" to (scannedBlocks >= MAX_LOCAL_SCAN_BLOCKS || discovered.size >= candidateLimit),
        )
        return candidates
    }

    fun incidentAsync(runtime: FarmRuntime, isCurrent: () -> Boolean): CompletableFuture<Set<FarmPlotPosition>> {
        val work = tasks ?: return CompletableFuture.completedFuture(incident(runtime))
        val token = work.lifecycleToken()
        val patch = runtime.state.preparationPatch
        if (patch.isEmpty()) return CompletableFuture.completedFuture(emptySet())
        val indexed = registry.beds(runtime.settings.id)
        val world = runtime.region.world
        val worldName = world.name
        val crops = runtime.settings.crops
        return operationPointsAsync(runtime, isCurrent).thenCompose { operationPoints ->
          work.compute { FarmBedCandidatePool.merge(indexed, patch) { true } }.thenCompose { candidates ->
            work.callSync(token) {
                val iterator = candidates.iterator()
                val selected = linkedSetOf<FarmPlotPosition>()
                work.capture(token, isCurrent) {
                    if (!iterator.hasNext()) return@capture true
                    val position = iterator.next()
                    if (position.world == worldName && world.isChunkLoaded(position.x shr 4, position.z shr 4)) {
                        val soil = world.getBlockAt(position.x, position.y, position.z)
                        if (runtime.region.contains(soil.location) && soil.type in FARM_SOIL_TYPES &&
                            !isNearOperationPoint(soil.location, operationPoints) && FarmSurfacePolicy.isOutdoorBed(soil) &&
                            FarmBlockPolicy.isOpenBedContent(soil.getRelative(org.bukkit.block.BlockFace.UP).type, crops)) selected += position
                    }
                    !iterator.hasNext()
                }.thenApply { selected as Set<FarmPlotPosition> }
            }.thenCompose { it }
          }
        }
    }

    fun incident(runtime: FarmRuntime): Set<FarmPlotPosition> {
        val patch = runtime.state.preparationPatch
        if (patch.isEmpty()) return emptySet()
        val indexed = registry.beds(runtime.settings.id)
        val operationPoints = operationPoints(runtime)
        val candidates = FarmBedCandidatePool.merge(indexed, patch) { position ->
            val soil = position.block() ?: return@merge false
            if (!runtime.region.contains(soil.location) || soil.type !in FARM_SOIL_TYPES) return@merge false
            if (isNearOperationPoint(soil.location, operationPoints)) return@merge false
            if (!FarmSurfacePolicy.isOutdoorBed(soil)) return@merge false
            FarmBlockPolicy.isOpenBedContent(
                soil.getRelative(org.bukkit.block.BlockFace.UP).type,
                runtime.settings.crops,
            )
        }
        debug.event(
            "farm_incident_beds_discovered",
            "zone" to runtime.settings.id,
            "indexed" to indexed.size,
            "candidates" to candidates.size,
        )
        return candidates
    }

    private fun available(
        runtime: FarmRuntime,
        candidates: Collection<FarmPlotPosition>,
    ): Set<FarmPlotPosition> {
        val operationPoints = operationPoints(runtime)
        return FarmBedCandidatePool.merge(candidates, emptyList()) { position ->
            val soil = position.block() ?: return@merge false
            if (!runtime.region.contains(soil.location) || isNearOperationPoint(soil.location, operationPoints)) {
                return@merge false
            }
            if (!FarmSurfacePolicy.isOutdoorBed(soil)) return@merge false
            FarmBlockPolicy.isSelectableBed(
                soil.type,
                soil.getRelative(org.bukkit.block.BlockFace.UP).type,
                runtime.settings.crops,
            )
        }
    }

    private fun operationPoints(runtime: FarmRuntime): List<FarmPointPosition> = OPERATION_POINT_KINDS.map { kind ->
        points.resolve(runtime, kind)
    }

    private fun isNearOperationPoint(
        location: Location,
        operationPoints: Collection<FarmPointPosition>,
    ): Boolean = operationPoints.any { point ->
        point.world == location.world.name && kotlin.math.abs(point.y - location.y) <= 3.0 &&
            (point.x - location.x) * (point.x - location.x) + (point.z - location.z) * (point.z - location.z) <= 9.0
    }

    private fun columnsAround(centerX: Int, centerZ: Int, radius: Int): Sequence<Pair<Int, Int>> = sequence {
        yield(centerX to centerZ)
        for (distance in 1..radius) {
            val minimumX = centerX - distance
            val maximumX = centerX + distance
            val minimumZ = centerZ - distance
            val maximumZ = centerZ + distance
            for (x in minimumX..maximumX) {
                yield(x to minimumZ)
                yield(x to maximumZ)
            }
            for (z in minimumZ + 1 until maximumZ) {
                yield(minimumX to z)
                yield(maximumX to z)
            }
        }
    }

    private companion object {
        const val MAX_LOCAL_SCAN_BLOCKS = 32_768
        const val MAX_LOCAL_SCAN_CANDIDATES = 4_096
        val OPERATION_POINT_KINDS = listOf(
            FarmPointKind.TOOL,
            FarmPointKind.SEEDS,
            FarmPointKind.WATER,
            FarmPointKind.CRATES,
            FarmPointKind.RECEIVING,
            FarmPointKind.CART,
            FarmPointKind.CUSTOMER,
        )
    }
}
