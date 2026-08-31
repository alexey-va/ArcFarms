package ru.ruscrafting.farms.paper.farm.placement

import org.bukkit.Location
import org.bukkit.HeightMap
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.domain.FarmDeliveryPlanner
import ru.ruscrafting.farms.domain.FarmDeliveryPosition
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockPolicy
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.platform.FarmBlockPassability
import ru.ruscrafting.farms.paper.platform.PaperFarmBlockPassability
import java.util.random.RandomGenerator

/** Loaded-column guard shared by every procedurally placed outdoor farm scene. */
internal object FarmSurfacePolicy {
    fun isSurfaceSpawn(
        location: Location,
        blockPassability: FarmBlockPassability = PaperFarmBlockPassability,
    ): Boolean {
        val world = location.world ?: return false
        if (!world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) return false
        if (location.blockY !in world.minHeight + 1 until world.maxHeight - 1) return false
        val feet = location.block
        val head = feet.getRelative(org.bukkit.block.BlockFace.UP)
        val floor = feet.getRelative(org.bukkit.block.BlockFace.DOWN)
        if (!blockPassability.isPassable(feet) || !blockPassability.isPassable(head) || !floor.type.isSolid) return false
        return isAtOrAboveSurface(location)
    }

    fun isAtOrAboveSurface(location: Location): Boolean {
        val world = location.world ?: return false
        if (!world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) return false
        return world.getHighestBlockYAt(location.blockX, location.blockZ, HeightMap.MOTION_BLOCKING) <= location.blockY
    }

    /** A crop may occupy soil + 1, but no motion-blocking terrain may cover the bed. */
    fun isOutdoorBed(soil: Block): Boolean {
        val world = soil.world
        if (!world.isChunkLoaded(soil.x shr 4, soil.z shr 4)) return false
        return world.getHighestBlockYAt(soil.x, soil.z, HeightMap.MOTION_BLOCKING) <= soil.y + 1
    }

    /** Allows the anchor block itself while rejecting a roof or terrain above it. */
    fun isOpenAbove(anchor: Block): Boolean {
        val world = anchor.world
        if (!world.isChunkLoaded(anchor.x shr 4, anchor.z shr 4)) return false
        return world.getHighestBlockYAt(anchor.x, anchor.z, HeightMap.MOTION_BLOCKING) <= anchor.y
    }
}

/** Shared, bounded placement policy for farm objectives and temporary objects. */
internal class FarmPlacementService(
    private val plugin: Plugin,
    private val blockRegistry: FarmBlockRegistry,
    private val points: FarmPointProvider,
    private val debug: ArcFarmsDebug,
    private val random: RandomGenerator,
) {
    fun selectDeliveryAnchor(runtime: FarmRuntime, preferred: Location?): FarmDeliveryPosition {
        val sources = sources(runtime, preferred)
        val candidates = bedCandidates(runtime, sources, runtime.settings.placementSearchRadius)
        val receiving = points.resolve(runtime, FarmPointKind.RECEIVING)
        val selected = FarmDeliveryPlanner.selectTargets(
            candidates = candidates,
            objectiveX = receiving.x,
            objectiveZ = receiving.z,
            participants = sources.map { it.x to it.z },
            minimumObjectiveDistance = runtime.settings.placementMinObjectiveDistance.toDouble(),
            maximumParticipantDistance = runtime.settings.placementMaxPlayerDistance.toDouble(),
            targetCount = 1,
            selectionIndex = runtime.state.sequence + if (candidates.isEmpty()) 0 else random.nextInt(minOf(candidates.size, 24)),
        ).firstOrNull()
        if (selected != null) {
            debug.event(
                "farm_delivery_anchor_selected",
                "zone" to runtime.settings.id,
                "sequence" to runtime.state.sequence,
                "candidates" to candidates.size,
                "x" to selected.x,
                "y" to selected.y,
                "z" to selected.z,
            )
            return selected
        }
        val fallback = points.resolve(runtime, FarmPointKind.CRATES)
        plugin.logger.warning(
            "No indexed farm bed is available for delivery crates in ${runtime.settings.id}; using the configured fallback",
        )
        return FarmDeliveryPosition(fallback.world, fallback.x, fallback.y, fallback.z)
    }

    /** Resolves the complete delivery layout with one indexed-bed scan. */
    fun deliveryCrateLocations(runtime: FarmRuntime, anchor: FarmDeliveryPosition): List<Location> {
        val world = runtime.region.world.takeIf { it.name == anchor.world } ?: return emptyList()
        val anchorLocation = Location(world, anchor.x, anchor.y, anchor.z)
        val nearby = bedCandidates(runtime, listOf(anchorLocation), runtime.settings.delivery.spawnRadius)
        val candidates = if (nearby.size >= runtime.settings.delivery.crates) {
            nearby
        } else {
            bedCandidates(runtime, sources(runtime, anchorLocation), runtime.settings.placementSearchRadius)
        }
        return FarmDeliveryPlanner.selectTargets(
            candidates = candidates,
            objectiveX = anchor.x,
            objectiveZ = anchor.z,
            participants = listOf(anchor.x to anchor.z),
            minimumObjectiveDistance = 0.0,
            maximumParticipantDistance = runtime.settings.delivery.spawnRadius.toDouble(),
            targetCount = runtime.settings.delivery.crates,
            selectionIndex = runtime.state.sequence,
            minimumTargetDistance = runtime.settings.delivery.minCrateSpacing,
        ).map { selected -> Location(world, selected.x, selected.y, selected.z) }
    }

    fun deliveryCrateLocation(runtime: FarmRuntime, anchor: FarmDeliveryPosition, index: Int): Location? =
        deliveryCrateLocations(runtime, anchor).getOrNull(index)

    fun sources(runtime: FarmRuntime, preferred: Location?): List<Location> {
        val candidates = buildList {
            preferred?.takeIf { it.world == runtime.region.world && runtime.region.contains(it) }?.let(::add)
            runtime.region.world.players.filter { runtime.region.contains(it.location) }.map(Player::getLocation).forEach(::add)
        }.distinctBy { Triple(it.blockX, it.blockY, it.blockZ) }
        if (candidates.isNotEmpty()) return candidates.take(MAX_SOURCES)
        val patch = runtime.state.preparationPatch
        val fallback = if (patch.isNotEmpty()) {
            val x = patch.sumOf { it.x }.toDouble() / patch.size
            val y = patch.sumOf { it.y }.toDouble() / patch.size
            val z = patch.sumOf { it.z }.toDouble() / patch.size
            Location(runtime.region.world, x, y, z)
        } else {
            points.resolve(runtime, FarmPointKind.RECEIVING).let {
                Location(runtime.region.world, it.x, it.y, it.z)
            }
        }
        return listOf(fallback)
    }

    fun safeGroundCandidates(runtime: FarmRuntime, sources: Collection<Location>, radius: Int): List<FarmDeliveryPosition> =
        groundCandidates(runtime, sources, radius)

    fun isOpenToSky(location: Location): Boolean = FarmSurfacePolicy.isSurfaceSpawn(location)

    private fun groundCandidates(
        runtime: FarmRuntime,
        sources: Collection<Location>,
        radius: Int,
    ): List<FarmDeliveryPosition> =
        sources.asSequence().take(MAX_SOURCES).flatMap { source ->
            safeGroundCandidates(runtime, source, radius).asSequence()
        }
            .distinct().toList()

    fun bedCandidates(runtime: FarmRuntime, sources: Collection<Location>, radius: Int): List<FarmDeliveryPosition> {
        val world = runtime.region.world
        val sourcePoints = sources.filter { it.world == world }.take(MAX_SOURCES)
        if (sourcePoints.isEmpty()) return emptyList()
        val radiusSquared = radius.toDouble() * radius
        return blockRegistry.beds(runtime.settings.id).asSequence()
            .filter { it.world == world.name && world.isChunkLoaded(it.x shr 4, it.z shr 4) }
            .filter { bed ->
                sourcePoints.any { source ->
                    val dx = bed.x + 0.5 - source.x
                    val dz = bed.z + 0.5 - source.z
                    dx * dx + dz * dz <= radiusSquared
                }
            }
            .mapNotNull { bed ->
                val soil = world.getBlockAt(bed.x, bed.y, bed.z)
                val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
                val overhead = crop.getRelative(org.bukkit.block.BlockFace.UP)
                if (!FarmBlockPolicy.isSelectableBed(soil.type, crop.type, runtime.settings.crops)) return@mapNotNull null
                if (crop.type.name !in runtime.settings.crops) return@mapNotNull null
                if (!overhead.type.isAir || !runtime.region.contains(crop.location)) return@mapNotNull null
                if (!FarmSurfacePolicy.isOutdoorBed(soil)) return@mapNotNull null
                FarmDeliveryPosition(world.name, bed.x + 0.5, bed.y + 1.05, bed.z + 0.5)
            }
            .distinct()
            .toList()
    }

    private fun safeGroundCandidates(
        runtime: FarmRuntime,
        source: Location,
        radius: Int,
    ): List<FarmDeliveryPosition> {
        val world = runtime.region.world
        if (source.world != world) return emptyList()
        val receiving = points.resolve(runtime, FarmPointKind.RECEIVING)
        val receivingExclusion = runtime.settings.delivery.radius + runtime.settings.placementReceivingExclusionPadding
        val receivingExclusionSquared = receivingExclusion * receivingExclusion
        val patchColumns = runtime.state.preparationPatch.mapTo(hashSetOf()) { it.x to it.z }
        val candidates = mutableListOf<FarmDeliveryPosition>()
        val verticalOffsets = listOf(0, -1, 1, -2, 2)
        for (x in source.blockX - radius..source.blockX + radius) {
            for (z in source.blockZ - radius..source.blockZ + radius) {
                val dx = x - source.blockX
                val dz = z - source.blockZ
                if (dx * dx + dz * dz > radius * radius || (x to z) in patchColumns) continue
                val receivingDx = x + 0.5 - receiving.x
                val receivingDz = z + 0.5 - receiving.z
                if (receivingDx * receivingDx + receivingDz * receivingDz < receivingExclusionSquared) continue
                verticalOffsets.firstNotNullOfOrNull { offset ->
                    val feetY = source.blockY + offset
                    if (!world.isChunkLoaded(x shr 4, z shr 4)) return@firstNotNullOfOrNull null
                    val location = Location(world, x + 0.5, feetY.toDouble(), z + 0.5)
                    if (!runtime.region.contains(location)) return@firstNotNullOfOrNull null
                    val floor = world.getBlockAt(x, feetY - 1, z)
                    val feet = world.getBlockAt(x, feetY, z)
                    val head = world.getBlockAt(x, feetY + 1, z)
                    if (!floor.type.isSolid || !feet.type.isAir || !head.type.isAir) return@firstNotNullOfOrNull null
                    if (!FarmSurfacePolicy.isSurfaceSpawn(location)) return@firstNotNullOfOrNull null
                    FarmDeliveryPosition(world.name, location.x, location.y, location.z)
                }?.let(candidates::add)
            }
        }
        return candidates
    }

    private companion object {
        const val MAX_SOURCES = 8
    }
}
