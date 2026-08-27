package ru.ruscrafting.farms.paper.farm.field

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
) {
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
