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
import ru.ruscrafting.farms.domain.FarmOrchardPlanner
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
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
) {
    fun select(runtime: FarmRuntime, preferredType: FarmCareType?, actor: Player?): FarmCarePlan? {
        val configured = runtime.state.orderId?.let(runtime.orders::get)?.careTypes ?: return null
        val start = if (preferredType == null) {
            Math.floorMod(runtime.state.sequence.toInt() * 17 + random.nextInt(configured.size), configured.size)
        } else configured.indexOf(preferredType).takeIf { it >= 0 } ?: 0
        val candidates = if (preferredType != null) listOf(preferredType) else {
            configured.indices.map { configured[(start + it) % configured.size] }
        }
        val selected = candidates.firstNotNullOfOrNull { type -> targets(runtime, type, actor)?.let { type to it } }
        if (selected == null) {
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
        val count = runtime.settings.careTargetCount
        val salt = runtime.state.sequence * 101L + type.ordinal * 17L
        fun bedTargets(role: FarmCareRole, amount: Int, required: Int = 1): List<FarmCareTarget> =
            FarmCarePlanner.spread(patch, amount.coerceAtMost(patch.size), salt).mapIndexed { index, plot ->
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
            FarmCareType.WEEDS -> bedTargets(FarmCareRole.WEED_ROOT, count + 1, required = 2)
            FarmCareType.IRRIGATION -> FarmCarePlanner.orient(bedTargets(FarmCareRole.VALVE, count), explicit(FarmPointKind.IRRIGATION))
            FarmCareType.POLLINATION -> {
                val hive = fixturePoint(runtime, FarmPointKind.HIVE) ?: return null
                listOf(FarmCareTarget(0, FarmCareRole.HIVE, hive)) +
                    bedTargets(FarmCareRole.FLOWER_PATCH, count).mapIndexed { index, target -> target.copy(id = index + 1) }
            }
            FarmCareType.STORM_COVERS -> FarmCarePlanner.orient(
                FarmCarePlanner.corners(patch).mapIndexed { index, plot ->
                    FarmCareTarget(index, FarmCareRole.COVER_ANCHOR, FarmPointPosition(plot.world, plot.x + 0.5, plot.y + 1.05, plot.z + 0.5))
                },
                explicit(FarmPointKind.COVERS),
            )
            FarmCareType.SCARECROWS -> FarmCarePlanner.orient(
                bedTargets(FarmCareRole.SCARECROW, minOf(3, count), required = 2),
                explicit(FarmPointKind.SCARECROWS),
            )
            FarmCareType.ANIMAL_RESCUE -> {
                val pen = fixturePoint(runtime, FarmPointKind.PEN) ?: return null
                val sources = placement.sources(runtime, actor?.location)
                val safePoints = FarmDeliveryPlanner.selectTargets(
                    placement.openSkyGroundCandidates(runtime, sources, runtime.settings.placementSearchRadius),
                    pen.x,
                    pen.z,
                    sources.map { it.x to it.z },
                    runtime.settings.placementMinObjectiveDistance.toDouble(),
                    runtime.settings.animalRescueMaxPlayerDistance.toDouble(),
                    runtime.settings.animalRescueTargetCount,
                    salt,
                    runtime.settings.animalRescueMinSpacing,
                ).map { FarmPointPosition(it.world, it.x, it.y, it.z) }
                if (safePoints.isEmpty()) return null
                safePoints.mapIndexed { index, position -> FarmCareTarget(index, FarmCareRole.ANIMAL, position) }
            }
            FarmCareType.DISEASE -> bedTargets(
                FarmCareRole.DISEASED_CROP,
                runtime.settings.diseaseInitialSpots.coerceAtMost(patch.size),
                required = 2,
            )
            FarmCareType.MOLES -> {
                val sources = placement.sources(runtime, actor?.location)
                val receiving = points.resolve(runtime, FarmPointKind.RECEIVING)
                val searchRadius = maxOf(runtime.settings.careRadius, runtime.settings.placementSearchRadius)
                val groundCandidates = FarmDeliveryPlanner.selectTargets(
                    candidates = placement.safeGroundCandidates(runtime, sources, searchRadius),
                    objectiveX = receiving.x,
                    objectiveZ = receiving.z,
                    participants = sources.map { it.x to it.z },
                    minimumObjectiveDistance = 3.0,
                    maximumParticipantDistance = runtime.settings.placementMaxPlayerDistance.toDouble(),
                    targetCount = runtime.settings.moleBurrow.candidateAttempts,
                    selectionIndex = salt,
                    minimumTargetDistance = 2.0,
                )
                val bedCandidates = FarmCarePlanner.spread(
                    patch,
                    runtime.settings.moleBurrow.candidateAttempts.coerceAtMost(patch.size),
                    salt xor 0x4D4F4C45L,
                ).map { plot -> FarmPointPosition(plot.world, plot.x + 0.5, plot.y + 1.05, plot.z + 0.5) }
                val candidates = groundCandidates.map { FarmPointPosition(it.world, it.x, it.y, it.z) } + bedCandidates
                debug.event(
                    "farm_mole_entrance_candidates",
                    "zone" to runtime.settings.id,
                    "ground" to groundCandidates.size,
                    "beds" to bedCandidates.size,
                    "radius" to searchRadius,
                )
                candidates.asSequence().distinct()
                    .firstOrNull { moleBurrow.preview(runtime, it) != null }
                    ?.let { listOf(FarmCareTarget(0, FarmCareRole.MOLE_MOUND, it)) }
                    ?: return null
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

    fun shouldUseSeeder(runtime: FarmRuntime): Boolean = runtime.state.preparationProgress == 0 &&
        runtime.state.plantingProgress == 0 && isSeederSequence(runtime, runtime.state.sequence)

    fun isSeederSequence(runtime: FarmRuntime, sequence: Long): Boolean {
        val every = runtime.settings.seederEveryShifts
        return every > 0 && Math.floorMod(sequence - 1L, every.toLong()) == 0L
    }

    fun fixturePoint(runtime: FarmRuntime, kind: FarmPointKind): FarmPointPosition? {
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
            val chosen = candidates[Math.floorMod(kind.ordinal * 31 + runtime.state.sequence.toInt(), candidates.size)]
            return FarmPointPosition(chosen.world, chosen.x, chosen.y, chosen.z)
        }
        val fallback = FarmCarePlanner.spread(patch, 1, runtime.state.sequence + kind.ordinal).firstOrNull() ?: return null
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
