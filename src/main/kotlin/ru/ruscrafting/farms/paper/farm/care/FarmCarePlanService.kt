package ru.ruscrafting.farms.paper.farm.care

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.FarmCarePlanner
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareTarget
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmDeliveryPlanner
import ru.ruscrafting.farms.domain.FarmLocationOverrides
import ru.ruscrafting.farms.domain.FarmMoleEntrancePlanner
import ru.ruscrafting.farms.domain.FarmOrchardPlanner
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmSpatialSeed
import ru.ruscrafting.farms.domain.nextPlacementSequence
import ru.ruscrafting.farms.paper.ActivityRegion
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockPolicy
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.block
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowWorld
import ru.ruscrafting.farms.paper.farm.placement.FarmPlacementService
import ru.ruscrafting.farms.paper.farm.placement.FarmSurfacePolicy
import ru.ruscrafting.farms.paper.location
import java.util.random.RandomGenerator
import java.util.logging.Level

internal data class FarmCarePlan(
    val type: FarmCareType,
    val targets: List<FarmCareTarget>,
    val goal: Int,
)

internal val FARM_OUTDOOR_CARE_ROLES = setOf(
    FarmCareRole.SEEDER_HORSE,
    FarmCareRole.WEED_ROOT,
    FarmCareRole.VALVE,
    FarmCareRole.FLOWER_PATCH,
    FarmCareRole.COVER_ANCHOR,
    FarmCareRole.SCARECROW,
    FarmCareRole.ANIMAL,
    FarmCareRole.DISEASED_CROP,
)

/** Deterministic planner for care activities. It never mutates runtime or Bukkit state. */
internal class FarmCarePlanService(
    private val debug: ArcFarmsDebug,
    private val registry: FarmBlockRegistry,
    private val placement: FarmPlacementService,
    private val points: FarmPointProvider,
    private val overrides: () -> FarmLocationOverrides,
    private val random: RandomGenerator,
    private val moleBurrow: FarmMoleBurrowWorld,
    private val participantCount: (ActivityRegion) -> Int,
    private val log: (Level, String) -> Unit,
) {
    fun select(runtime: FarmRuntime, preferredType: FarmCareType?, actor: Player?): FarmCarePlan? {
        val configured = runtime.state.orderId?.let(runtime.orders::get)?.careTypes ?: run {
            log(
                Level.WARNING,
                "Could not plan farm care activity: zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                    "reason=order_missing order=${runtime.state.orderId}",
            )
            return null
        }
        val start = if (preferredType == null) {
            Math.floorMod(runtime.state.sequence.toInt() * 17 + random.nextInt(configured.size), configured.size)
        } else configured.indexOf(preferredType).takeIf { it >= 0 } ?: 0
        val candidates = if (preferredType != null) listOf(preferredType) else {
            configured.indices.map { configured[(start + it) % configured.size] }
        }
        val selected = candidates.firstNotNullOfOrNull { type -> targets(runtime, type, actor)?.let { type to it } }
        if (selected == null) {
            log(
                Level.WARNING,
                "Could not plan farm care activity: zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                    "requested=${preferredType ?: "automatic"} attempted=${candidates.joinToString(",")} " +
                    "phase=${runtime.state.phase} patch=${runtime.state.preparationPatch.size} " +
                    "indexed_beds=${registry.beds(runtime.settings.id).size} " +
                    "orchard_leaves=${registry.orchardLeaves(runtime.settings.id).size} " +
                    "procedural=${runtime.settings.proceduralCareFixtures} actor=${actor?.name ?: "none"}",
            )
            debug.event(
                "farm_care_unavailable",
                "zone" to runtime.settings.id,
                "sequence" to runtime.state.sequence,
                "types" to configured.joinToString(","),
                "procedural" to runtime.settings.proceduralCareFixtures,
            )
            return null
        }
        val goal = if (selected.first == FarmCareType.APPLE_HARVEST) {
            runtime.settings.appleTargetCount.coerceAtMost(selected.second.size)
        } else selected.second.sumOf(FarmCareTarget::required)
        return FarmCarePlan(selected.first, selected.second, goal)
    }

    fun targets(runtime: FarmRuntime, type: FarmCareType, actor: Player?): List<FarmCareTarget>? {
        val patch = runtime.state.preparationPatch.filter { plot ->
            plot.block()?.let(FarmSurfacePolicy::isOutdoorBed) == true
        }
        if (patch.isEmpty()) return null
        val farmBeds = registry.beds(runtime.settings.id).filter { plot ->
            plot.block()?.let(FarmSurfacePolicy::isOutdoorBed) == true
        }.ifEmpty { patch }
        val count = FarmCarePlanner.targetCount(
            participantCount(runtime.region),
            runtime.settings.careTargetsPerPlayer,
            runtime.settings.careTargetsMax,
            patch.size,
        )
        // Planning happens before FarmShiftEngine.startCare advances the durable nonce.
        // A shift sequence alone is constant while an administrator repeatedly forces
        // scenarios, which used to place every target on the same beds.
        val placementSequence = runtime.state.nextPlacementSequence()
        val salt = FarmSpatialSeed.mix(placementSequence, type.ordinal * 17L + 101L)
        fun bedTargets(
            role: FarmCareRole,
            amount: Int,
            required: Int = 1,
            candidates: List<FarmPlotPosition> = patch,
        ): List<FarmCareTarget> =
            FarmCarePlanner.spread(candidates, amount.coerceAtMost(candidates.size), salt).mapIndexed { index, plot ->
                FarmCareTarget(
                    id = index,
                    role = role,
                    position = FarmPointPosition(plot.world, plot.x + 0.5, plot.y + 1.05, plot.z + 0.5),
                    required = required,
                )
            }
        fun explicit(kind: FarmPointKind): FarmPointPosition? = overrides().zones[runtime.settings.id]?.get(kind)

        return when (type) {
            FarmCareType.SEEDER -> {
                val origin = actor?.location?.takeIf(runtime.region::contains) ?: areaCenter(patch)?.location() ?: return null
                val start = patch.minWithOrNull(
                    compareBy<FarmPlotPosition> { plot ->
                        val dx = plot.x + 0.5 - origin.x
                        val dz = plot.z + 0.5 - origin.z
                        dx * dx + dz * dz
                    }.thenBy(FarmPlotPosition::x).thenBy(FarmPlotPosition::z),
                ) ?: return null
                listOf(FarmCareTarget(0, FarmCareRole.SEEDER_HORSE, FarmPointPosition(start.world, start.x + 0.5, start.y + 1.05, start.z + 0.5)))
            }
            FarmCareType.WEEDS -> bedTargets(FarmCareRole.WEED_ROOT, count)
            FarmCareType.IRRIGATION -> FarmCarePlanner.orient(bedTargets(FarmCareRole.VALVE, count), explicit(FarmPointKind.IRRIGATION))
            FarmCareType.POLLINATION -> {
                val hive = fixturePoint(runtime, FarmPointKind.HIVE, placementSequence) ?: return null
                listOf(FarmCareTarget(0, FarmCareRole.HIVE, hive)) +
                    bedTargets(FarmCareRole.FLOWER_PATCH, count).mapIndexed { index, target -> target.copy(id = index + 1) }
            }
            FarmCareType.STORM_COVERS -> FarmCarePlanner.orient(
                bedTargets(FarmCareRole.COVER_ANCHOR, count),
                explicit(FarmPointKind.COVERS),
            )
            FarmCareType.SCARECROWS -> FarmCarePlanner.orient(
                // Scarecrows protect the whole farm, not only the currently harvested patch.
                // Prefer central beds so delivery stays readable and convenient, but retain
                // the outer field as a fallback when the interior cannot keep targets apart.
                FarmCarePlanner.centralSpread(
                    farmBeds,
                    runtime.settings.scarecrowTargetCount.coerceAtMost(farmBeds.size),
                    minimumSpacing = runtime.settings.scarecrowMinSpacing,
                    selectionIndex = salt,
                ).mapIndexed { index, plot ->
                    FarmCareTarget(
                        id = index,
                        role = FarmCareRole.SCARECROW,
                        position = FarmPointPosition(plot.world, plot.x + 0.5, plot.y + 1.05, plot.z + 0.5),
                    )
                },
                explicit(FarmPointKind.SCARECROWS),
            )
            FarmCareType.ANIMAL_RESCUE -> {
                val ditch = explicit(FarmPointKind.DITCH) ?: return null
                val ditchLocation = runtime.region.world.takeIf { it.name == ditch.world }
                    ?.let { Location(it, ditch.x, ditch.y, ditch.z) } ?: return null
                val bedCandidates = placement.bedCandidates(runtime, listOf(ditchLocation), runtime.settings.careRadius)
                val safePoints = FarmDeliveryPlanner.selectTargets(
                    bedCandidates,
                    ditch.x,
                    ditch.z,
                    listOf(ditch.x to ditch.z),
                    0.0,
                    runtime.settings.careRadius.toDouble(),
                    runtime.settings.animalRescueTargetCount,
                    salt,
                    runtime.settings.animalRescueMinSpacing,
                ).map { FarmPointPosition(it.world, it.x, it.y, it.z) }
                if (safePoints.isEmpty()) {
                    log(
                        Level.WARNING,
                            "Could not plan farm animal rescue: zone=${runtime.settings.id} " +
                            "sequence=${runtime.state.sequence} reason=no_ditch_spawn_candidates " +
                            "indexed_beds=${registry.beds(runtime.settings.id).size} candidates=${bedCandidates.size} " +
                            "requested=${runtime.settings.animalRescueTargetCount}",
                    )
                    return null
                }
                safePoints.mapIndexed { index, position -> FarmCareTarget(index, FarmCareRole.ANIMAL, position) }
            }
            FarmCareType.DISEASE -> bedTargets(
                FarmCareRole.DISEASED_CROP,
                runtime.settings.diseaseInitialSpots.coerceAtMost(patch.size),
            )
            FarmCareType.MOLES -> {
                // Keep discoverable entrances away from both the indexed field edge and
                // concave WorldGuard boundaries. For a narrow farm the pure planner
                // automatically reduces only the margin of the narrow axis.
                val indexedInterior = FarmMoleEntrancePlanner.preferredBeds(
                    farmBeds,
                    runtime.settings.moleBurrow.entranceMinBoundaryDistance,
                )
                val centralBeds = indexedInterior.filter { plot ->
                    hasRegionClearance(runtime.region, plot, runtime.settings.moleBurrow.entranceMinBoundaryDistance)
                }.ifEmpty { indexedInterior }
                val bedCandidates = FarmCarePlanner.spread(
                    centralBeds,
                    runtime.settings.moleBurrow.candidateAttempts.coerceAtMost(centralBeds.size),
                    salt xor 0x4D4F4C45L,
                ).map { plot -> FarmPointPosition(plot.world, plot.x + 0.5, plot.y + 1.05, plot.z + 0.5) }
                val requested = participantCount(runtime.region).coerceAtLeast(1)
                    .coerceAtMost(runtime.settings.moleBurrow.maxBurrows)
                val startedAt = System.nanoTime()
                var tested = 0
                var layoutProbes = 0
                val rejections = linkedMapOf<String, Int>()
                val occupied = hashSetOf<Triple<Int, Int, Int>>()
                val selected = mutableListOf<FarmCareTarget>()
                bedCandidates.asSequence().distinct().forEach { candidate ->
                    if (selected.size >= requested) return@forEach
                    tested += 1
                    val burrowId = selected.size
                    val preview = moleBurrow.previewDetailed(runtime, candidate, placementSequence, burrowId)
                    layoutProbes += preview.layoutAttempts
                    preview.rejections.forEach { (reason, amount) ->
                        rejections[reason] = rejections.getOrDefault(reason, 0) + amount
                    }
                    val scene = preview.scene ?: return@forEach
                    val positions = scene.records.map { Triple(it.x, it.y, it.z) }
                    if (positions.any { it in occupied }) {
                        rejections["overlap"] = rejections.getOrDefault("overlap", 0) + 1
                        return@forEach
                    }
                    occupied += positions
                    selected += FarmCareTarget(burrowId, FarmCareRole.MOLE_MOUND, candidate)
                }
                val rejectionSummary = rejections.entries.sortedByDescending(Map.Entry<String, Int>::value)
                    .joinToString(",") { (reason, amount) -> "$reason:$amount" }.ifEmpty { "none" }
                debug.event(
                    "farm_mole_entrance_candidates",
                    "zone" to runtime.settings.id,
                    "beds" to farmBeds.size,
                    "central_beds" to centralBeds.size,
                    "candidates" to bedCandidates.size,
                    "tested" to tested,
                    "layout_probes" to layoutProbes,
                    "requested" to requested,
                    "selected" to selected.size,
                    "rejections" to rejectionSummary,
                    "elapsed_ms" to ((System.nanoTime() - startedAt) / 1_000_000L),
                )
                if (selected.size < requested) log(
                    Level.WARNING,
                    "Could not plan mole burrow: zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                        "beds=${farmBeds.size} central_beds=${centralBeds.size} candidates=${bedCandidates.size} " +
                        "requested=$requested selected=${selected.size} " +
                        "tested=$tested layout_probes=$layoutProbes " +
                        "depth=${runtime.settings.moleBurrow.minDepth}-${runtime.settings.moleBurrow.maxDepth} " +
                        "rejections=$rejectionSummary",
                )
                selected.takeIf { it.size == requested } ?: return null
            }
            FarmCareType.APPLE_HARVEST -> {
                val leaves = registry.orchardLeaves(runtime.settings.id).filter { position ->
                    val leaf = position.block() ?: return@filter false
                    runtime.region.contains(leaf.location) &&
                        FarmBlockPolicy.isOrchardLeaf(leaf.type, leaf.getRelative(org.bukkit.block.BlockFace.DOWN).type)
                }
                FarmOrchardPlanner.select(
                    leaves,
                    runtime.settings.applePlacementCount,
                    runtime.settings.appleMinSpacing,
                    salt,
                ).mapIndexed { index, leaf ->
                    FarmCareTarget(index, FarmCareRole.APPLE, FarmPointPosition(leaf.world, leaf.x + 0.5, leaf.y + 0.5, leaf.z + 0.5))
                }.takeIf { it.size >= minOf(3, runtime.settings.appleTargetCount) } ?: return null
            }
        }.takeIf { it.isNotEmpty() }
    }

    private fun hasRegionClearance(
        region: ActivityRegion,
        plot: FarmPlotPosition,
        requestedDistance: Int,
    ): Boolean {
        if (requestedDistance <= 0) return true
        val distanceX = requestedDistance.coerceAtMost((region.bounds.maxX - region.bounds.minX) / 2)
        val distanceZ = requestedDistance.coerceAtMost((region.bounds.maxZ - region.bounds.minZ) / 2)
        val y = plot.y.coerceIn(region.bounds.minY, region.bounds.maxY).toDouble()
        return listOf(
            plot.x - distanceX to plot.z,
            plot.x + distanceX to plot.z,
            plot.x to plot.z - distanceZ,
            plot.x to plot.z + distanceZ,
            plot.x - distanceX to plot.z - distanceZ,
            plot.x - distanceX to plot.z + distanceZ,
            plot.x + distanceX to plot.z - distanceZ,
            plot.x + distanceX to plot.z + distanceZ,
        ).all { (x, z) -> region.contains(Location(region.world, x + 0.5, y, z + 0.5)) }
    }

    fun shouldUseSeeder(runtime: FarmRuntime): Boolean = runtime.state.preparationProgress == 0 &&
        runtime.state.plantingProgress == 0 && isSeederSequence(runtime, runtime.state.sequence)

    fun isSeederSequence(runtime: FarmRuntime, sequence: Long): Boolean {
        val every = runtime.settings.seederEveryShifts
        return every > 0 && Math.floorMod(sequence - 1L, every.toLong()) == 0L
    }

    fun fixturePoint(
        runtime: FarmRuntime,
        kind: FarmPointKind,
        placementSequence: Long = runtime.state.placementSequence,
    ): FarmPointPosition? {
        overrides().zones[runtime.settings.id]?.get(kind)?.let { return it }
        if (kind == FarmPointKind.PEN) return points.resolve(runtime, FarmPointKind.RECEIVING)
        val patch = runtime.state.preparationPatch
        if (patch.isEmpty()) return null
        if (kind == FarmPointKind.HIVE) {
            val center = areaCenter(patch)?.location() ?: return null
            nearbyBlocks(center, runtime.region, runtime.settings.careRadius, 4, 1) {
                it.type == Material.BEE_NEST || it.type == Material.BEEHIVE
            }.firstOrNull()?.let { hive ->
                return FarmPointPosition(hive.world.name, hive.x + 0.5, hive.y + 0.5, hive.z + 0.5)
            }
        }
        if (!runtime.settings.proceduralCareFixtures) return null
        val center = areaCenter(patch)?.location() ?: return null
        val candidates = placement.safeGroundCandidates(runtime, listOf(center), runtime.settings.careRadius)
        if (candidates.isNotEmpty()) {
            val selection = FarmSpatialSeed.mix(placementSequence, kind.ordinal * 31L)
            val chosen = candidates[Math.floorMod(selection, candidates.size.toLong()).toInt()]
            return FarmPointPosition(chosen.world, chosen.x, chosen.y, chosen.z)
        }
        val fallback = FarmCarePlanner.spread(
            patch, 1, FarmSpatialSeed.mix(placementSequence, kind.ordinal.toLong()),
        ).firstOrNull() ?: return null
        return FarmPointPosition(fallback.world, fallback.x + 0.5, fallback.y + 1.0, fallback.z + 0.5)
    }

    private fun areaCenter(plots: Collection<FarmPlotPosition>): FarmPlotPosition? {
        if (plots.isEmpty()) return null
        val world = plots.first().world
        val sameWorld = plots.filter { it.world == world }
        return FarmPlotPosition(
            world,
            sameWorld.sumOf(FarmPlotPosition::x) / sameWorld.size,
            sameWorld.sumOf(FarmPlotPosition::y) / sameWorld.size,
            sameWorld.sumOf(FarmPlotPosition::z) / sameWorld.size,
        )
    }

    private fun nearbyBlocks(
        center: Location,
        region: ActivityRegion,
        horizontal: Int,
        below: Int,
        above: Int,
        predicate: (Block) -> Boolean,
    ): List<Block> = buildList {
        val world = center.world
        for (x in center.blockX - horizontal..center.blockX + horizontal) {
            for (z in center.blockZ - horizontal..center.blockZ + horizontal) {
                if (!world.isChunkLoaded(x shr 4, z shr 4)) continue
                for (y in center.blockY - below..center.blockY + above) {
                    val block = world.getBlockAt(x, y, z)
                    if (region.contains(block.location) && predicate(block)) add(block)
                }
            }
        }
    }

}
