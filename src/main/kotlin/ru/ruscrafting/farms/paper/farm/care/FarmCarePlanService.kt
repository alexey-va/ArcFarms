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
import ru.ruscrafting.farms.domain.worksite.WorksiteDeterministicSeed
import ru.ruscrafting.farms.domain.nextPlacementSequence
import ru.ruscrafting.farms.paper.ActivityRegion
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockPolicy
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.block
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowWorld
import ru.ruscrafting.farms.paper.farm.expedition.FarmUndergroundEntrySelection
import ru.ruscrafting.farms.paper.farm.placement.FarmPlacementService
import ru.ruscrafting.farms.paper.farm.placement.FarmSurfacePolicy
import ru.ruscrafting.farms.paper.location
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
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

private const val DITCH_MAX_SURFACE_STEP = 2

/** Planner for care activities. It never mutates runtime or Bukkit state. */
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
    private val tasks: WorksiteTaskPort? = null,
) {
    fun selectAsync(runtime: FarmRuntime, preferredType: FarmCareType?, actor: Player?): CompletableFuture<FarmCarePlan?> {
        val work = tasks ?: return CompletableFuture.completedFuture(select(runtime, preferredType, actor))
        val configured = runtime.state.orderId?.let(runtime.orders::get)?.careTypes
            ?: return CompletableFuture.completedFuture(null)
        val start = if (preferredType == null) {
            Math.floorMod(runtime.state.sequence.toInt() * 17 + random.nextInt(configured.size), configured.size)
        } else configured.indexOf(preferredType).takeIf { it >= 0 } ?: 0
        val candidates = if (preferredType != null) listOf(preferredType) else {
            configured.indices.map { configured[(start + it) % configured.size] }
        }
        val token = work.lifecycleToken()
        val source = runtime.state
        val zoneSettings = runtime.settings
        fun current() = runtime.settings === zoneSettings && runtime.state.sequence == source.sequence &&
            runtime.state.placementSequence == source.placementSequence && runtime.state.phase == source.phase &&
            runtime.state.orderId == source.orderId && runtime.state.preparationPatch === source.preparationPatch
        fun choose(index: Int): CompletableFuture<FarmCarePlan?> {
            if (!current()) return CompletableFuture.failedFuture(CancellationException("Care planning source changed"))
            if (index == candidates.size) return CompletableFuture.completedFuture(null)
            val type = candidates[index]
            return targetsAsync(runtime, type, actor, ::current).thenCompose { selected ->
                work.callSync(token) {
                    check(current()) { "Care planning source changed" }
                    if (selected == null) choose(index + 1) else {
                        val goal = if (type == FarmCareType.APPLE_HARVEST) {
                            zoneSettings.appleTargetCount.coerceAtMost(selected.size)
                        } else selected.sumOf(FarmCareTarget::required)
                        CompletableFuture.completedFuture(FarmCarePlan(type, selected, goal))
                    }
                }.thenCompose { it }
            }
        }
        return choose(0)
    }

    private fun targetsAsync(
        runtime: FarmRuntime,
        type: FarmCareType,
        actor: Player?,
        current: () -> Boolean,
    ): CompletableFuture<List<FarmCareTarget>?> {
        val work = requireNotNull(tasks)
        val token = work.lifecycleToken()
        val patch = mutableListOf<FarmPlotPosition>()
        val farmBeds = mutableListOf<FarmPlotPosition>()
        val patchIterator = runtime.state.preparationPatch.iterator()
        val bedIterator = registry.beds(runtime.settings.id).iterator()
        val world = runtime.region.world
        val worldName = world.name
        val participants = participantCount(runtime.region)
        val zoneSettings = runtime.settings
        val placementSequence = runtime.state.nextPlacementSequence()
        val actorOrigin = actor?.location?.takeIf(runtime.region::contains)?.let { it.x to it.z }
        val explicit = overrides().zones[zoneSettings.id]
        return work.capture(token, current) {
            val iterator = if (patchIterator.hasNext()) patchIterator else bedIterator
            if (!iterator.hasNext()) return@capture true
            val plot = iterator.next()
            if (plot.world == worldName && world.isChunkLoaded(plot.x shr 4, plot.z shr 4) &&
                FarmSurfacePolicy.isOutdoorBed(world.getBlockAt(plot.x, plot.y, plot.z))) {
                if (iterator === patchIterator) patch += plot else farmBeds += plot
            }
            !patchIterator.hasNext() && !bedIterator.hasNext()
        }.thenCompose {
            if (patch.isEmpty()) return@thenCompose CompletableFuture.completedFuture(null)
            val salt = WorksiteDeterministicSeed.derive(
                WorksiteDeterministicSeed.derive(placementSequence, type.ordinal * 17L + 101L), random.nextLong(),
            )
            val allBeds = farmBeds.ifEmpty { patch }
            if (type == FarmCareType.MOLES) {
                return@thenCompose moleTargetsAsync(runtime, allBeds, placementSequence, salt, participants, current)
            }
            if (type == FarmCareType.APPLE_HARVEST) {
                val leaves = mutableListOf<FarmPlotPosition>()
                val iterator = registry.orchardLeaves(zoneSettings.id).iterator()
                return@thenCompose work.capture(token, current) {
                    if (!iterator.hasNext()) return@capture true
                    val point = iterator.next()
                    if (point.world == worldName && world.isChunkLoaded(point.x shr 4, point.z shr 4)) {
                        val leaf = world.getBlockAt(point.x, point.y, point.z)
                        if (runtime.region.contains(leaf.location) && FarmBlockPolicy.isOrchardLeaf(
                                leaf.type, leaf.getRelative(org.bukkit.block.BlockFace.DOWN).type,
                            )) leaves += point
                    }
                    !iterator.hasNext()
                }.thenCompose {
                    work.compute {
                        FarmOrchardPlanner.select(leaves, zoneSettings.applePlacementCount, zoneSettings.appleMinSpacing, salt)
                            .mapIndexed { index, leaf ->
                                FarmCareTarget(index, FarmCareRole.APPLE, FarmPointPosition(leaf.world, leaf.x + 0.5, leaf.y + 0.5, leaf.z + 0.5))
                            }.takeIf { it.size >= minOf(3, zoneSettings.appleTargetCount) }
                    }
                }
            }
            if (type == FarmCareType.POLLINATION) {
                return@thenCompose fixturePointAsync(runtime, FarmPointKind.HIVE, placementSequence, current).thenCompose { hive ->
                    work.compute {
                        hive ?: return@compute null
                        val count = FarmCarePlanner.targetCount(participants, zoneSettings.careTargetsPerPlayer, zoneSettings.careTargetsMax, patch.size)
                        listOf(FarmCareTarget(0, FarmCareRole.HIVE, hive)) +
                            FarmCarePlanner.spread(patch, count.coerceAtMost(patch.size), salt).mapIndexed { index, plot ->
                                FarmCareTarget(index + 1, FarmCareRole.FLOWER_PATCH, FarmPointPosition(plot.world, plot.x + 0.5, plot.y + 1.05, plot.z + 0.5))
                            }
                    }
                }
            }
            if (type == FarmCareType.ANIMAL_RESCUE) {
                val pen = fixturePoint(runtime, FarmPointKind.PEN, placementSequence)
                    ?: return@thenCompose CompletableFuture.completedFuture(null)
                return@thenCompose placement.sourcesAsync(runtime, actor?.location, current).thenCompose { sources ->
                    work.callSync(token) { placement.bedCandidatesAsync(runtime, sources, zoneSettings.placementSearchRadius, current) }
                        .thenCompose { it }.thenCompose { candidates ->
                            work.compute {
                                FarmDeliveryPlanner.selectTargets(
                                    candidates, pen.x, pen.z, sources.map { it.x to it.z },
                                    zoneSettings.placementMinObjectiveDistance.toDouble(), zoneSettings.animalRescueMaxPlayerDistance.toDouble(),
                                    zoneSettings.animalRescueTargetCount, salt, zoneSettings.animalRescueMinSpacing,
                                ).mapIndexed { index, point -> FarmCareTarget(index, FarmCareRole.ANIMAL, FarmPointPosition(point.world, point.x, point.y, point.z)) }
                                    .takeIf { it.isNotEmpty() }
                            }
                        }
                }
            }
            if (type == FarmCareType.DITCH_RESCUE) {
                return@thenCompose ditchTargetsAsync(runtime, allBeds, placementSequence, salt, current)
            }
            work.compute {
                val count = FarmCarePlanner.targetCount(participants, zoneSettings.careTargetsPerPlayer, zoneSettings.careTargetsMax, patch.size)
                fun bedTargets(role: FarmCareRole, amount: Int) = FarmCarePlanner.spread(patch, amount.coerceAtMost(patch.size), salt)
                    .mapIndexed { index, plot -> FarmCareTarget(index, role, FarmPointPosition(plot.world, plot.x + 0.5, plot.y + 1.05, plot.z + 0.5)) }
                when (type) {
                    FarmCareType.SEEDER -> {
                        val center = areaCenter(patch) ?: return@compute null
                        val origin = actorOrigin ?: (center.x.toDouble() to center.z.toDouble())
                        val first = patch.minWithOrNull(compareBy<FarmPlotPosition> {
                            val dx = it.x + 0.5 - origin.first; val dz = it.z + 0.5 - origin.second
                            dx * dx + dz * dz
                        }.thenBy(FarmPlotPosition::x).thenBy(FarmPlotPosition::z)) ?: return@compute null
                        listOf(FarmCareTarget(0, FarmCareRole.SEEDER_HORSE, FarmPointPosition(first.world, first.x + 0.5, first.y + 1.05, first.z + 0.5)))
                    }
                    FarmCareType.WEEDS -> bedTargets(FarmCareRole.WEED_ROOT, count)
                    FarmCareType.IRRIGATION -> FarmCarePlanner.orient(bedTargets(FarmCareRole.VALVE, count), explicit?.get(FarmPointKind.IRRIGATION))
                    FarmCareType.STORM_COVERS -> FarmCarePlanner.orient(bedTargets(FarmCareRole.COVER_ANCHOR, count), explicit?.get(FarmPointKind.COVERS))
                    FarmCareType.SCARECROWS -> FarmCarePlanner.orient(
                        FarmCarePlanner.centralSpread(allBeds, zoneSettings.scarecrowTargetCount.coerceAtMost(allBeds.size), zoneSettings.scarecrowMinSpacing, salt)
                            .mapIndexed { index, plot -> FarmCareTarget(index, FarmCareRole.SCARECROW, FarmPointPosition(plot.world, plot.x + 0.5, plot.y + 1.05, plot.z + 0.5)) },
                        explicit?.get(FarmPointKind.SCARECROWS),
                    )
                    FarmCareType.DISEASE -> bedTargets(FarmCareRole.DISEASED_CROP, zoneSettings.diseaseInitialSpots.coerceAtMost(patch.size))
                    else -> error("Handled by native capture branch: $type")
                }.takeIf { it.isNotEmpty() }
            }
        }
    }

    private fun moleTargetsAsync(
        runtime: FarmRuntime,
        beds: List<FarmPlotPosition>,
        placementSequence: Long,
        salt: Long,
        participants: Int,
        current: () -> Boolean,
    ): CompletableFuture<List<FarmCareTarget>?> {
        val work = requireNotNull(tasks)
        val token = work.lifecycleToken()
        val rules = runtime.settings.moleBurrow
        val requested = participants.coerceAtLeast(1).coerceAtMost(rules.maxBurrows)
        val startedAt = System.nanoTime()
        return work.compute { ru.ruscrafting.farms.domain.FarmMoleEntrancePlanner.preferredBeds(beds, rules.entranceMinBoundaryDistance) }
            .thenCompose { preferred ->
                work.callSync(token) {
                    val clear = mutableListOf<FarmPlotPosition>()
                    val iterator = preferred.iterator()
                    work.capture(token, current) {
                        if (!iterator.hasNext()) return@capture true
                        val plot = iterator.next()
                        if (hasRegionClearance(runtime.region, plot, rules.entranceMinBoundaryDistance)) clear += plot
                        !iterator.hasNext()
                    }.thenCompose {
                        work.compute {
                            val eligible = clear.ifEmpty { preferred }
                            FarmCarePlanner.spread(eligible, rules.candidateAttempts.coerceAtMost(eligible.size), salt xor 0x4D4F4C45L)
                                .map { FarmPointPosition(it.world, it.x + 0.5, it.y + 1.05, it.z + 0.5) }.distinct()
                        }
                    }
                }.thenCompose { it }
            }.thenCompose { candidates ->
                work.callSync(token) {
                    var tested = 0
                    var probes = 0
                    val rejections = linkedMapOf<String, Int>()
                    val occupied = hashSetOf<Triple<Int, Int, Int>>()
                    val selected = mutableListOf<FarmCareTarget>()
                    fun next(): CompletableFuture<List<FarmCareTarget>?> {
                        check(current()) { "Mole source changed" }
                        if (selected.size == requested || tested == candidates.size) {
                            debug.event("farm_mole_entrance_candidates", "zone" to runtime.settings.id,
                                "beds" to beds.size, "candidates" to candidates.size, "tested" to tested,
                                "layout_probes" to probes, "requested" to requested, "selected" to selected.size,
                                "rejections" to rejections.toString(), "elapsed_ms" to (System.nanoTime() - startedAt) / 1_000_000L)
                            return CompletableFuture.completedFuture(selected.takeIf { it.size == requested })
                        }
                        val candidate = candidates[tested++]
                        val id = selected.size
                        return moleBurrow.previewDetailedAsync(runtime, candidate, placementSequence, id).thenCompose { preview ->
                            probes += preview.layoutAttempts
                            preview.rejections.forEach { (reason, count) -> rejections[reason] = rejections.getOrDefault(reason, 0) + count }
                            val positions = preview.scene?.index?.positions
                            work.compute {
                                positions != null && if (positions.any { it in occupied }) false else { occupied += positions; true }
                            }.thenCompose { accepted ->
                                work.callSync(token) {
                                    if (accepted) selected += FarmCareTarget(id, FarmCareRole.MOLE_MOUND, candidate)
                                    else if (positions != null) rejections["overlap"] = rejections.getOrDefault("overlap", 0) + 1
                                    next()
                                }.thenCompose { it }
                            }
                        }
                    }
                    next()
                }.thenCompose { it }
            }
    }

    private fun ditchTargetsAsync(
        runtime: FarmRuntime, beds: List<FarmPlotPosition>, placementSequence: Long, selection: Long,
        current: () -> Boolean,
    ): CompletableFuture<List<FarmCareTarget>?> {
        val work = requireNotNull(tasks)
        val token = work.lifecycleToken()
        val valid = hashSetOf<FarmPlotPosition>()
        val iterator = beds.iterator()
        val maximum = runtime.settings.animalRescueTargetCount
        return work.capture(token, current) {
            if (!iterator.hasNext()) return@capture true
            val plot = iterator.next()
            val world = runtime.region.world
            if (plot.world == world.name && world.isChunkLoaded(plot.x shr 4, plot.z shr 4)) {
                val soil = world.getBlockAt(plot.x, plot.y, plot.z)
                if (soil.type == Material.FARMLAND && FarmSurfacePolicy.isOutdoorBed(soil) && runtime.region.contains(soil.location)) valid += plot
            }
            !iterator.hasNext()
        }.thenCompose {
            work.compute {
                val indexed = beds.groupBy { Triple(it.world, it.x, it.z) }
                val offsets = FarmDitchLayout.cells(placementSequence)
                val candidates = beds.mapNotNull { center ->
                    offsets.map { offset ->
                        indexed[Triple(center.world, center.x + offset.x, center.z + offset.z)].orEmpty().asSequence()
                            .filter { kotlin.math.abs(it.y - center.y) <= DITCH_MAX_SURFACE_STEP }
                            .sortedBy { kotlin.math.abs(it.y - center.y) }.firstOrNull { it in valid }
                    }.takeIf { it.all { plot -> plot != null } }?.filterNotNull()?.takeIf { plots ->
                        plots.maxOf(FarmPlotPosition::y) - plots.minOf(FarmPlotPosition::y) <= DITCH_MAX_SURFACE_STEP
                    }
                }
                if (candidates.isEmpty()) return@compute null
                candidates[Math.floorMod(selection, candidates.size.toLong()).toInt()].take(maximum).mapIndexed { index, plot ->
                    FarmCareTarget(index, FarmCareRole.ANIMAL, FarmPointPosition(plot.world, plot.x + 0.5, plot.y.toDouble(), plot.z + 0.5))
                }
            }
        }
    }

    fun fixturePointAsync(
        runtime: FarmRuntime, kind: FarmPointKind, placementSequence: Long = runtime.state.placementSequence,
        current: () -> Boolean = { true },
    ): CompletableFuture<FarmPointPosition?> {
        val work = tasks ?: return CompletableFuture.completedFuture(fixturePoint(runtime, kind, placementSequence))
        val token = work.lifecycleToken()
        overrides().zones[runtime.settings.id]?.get(kind)?.let { return CompletableFuture.completedFuture(it) }
        if (kind == FarmPointKind.PEN) return CompletableFuture.completedFuture(points.resolve(runtime, FarmPointKind.RECEIVING))
        val patch = runtime.state.preparationPatch
        val rules = runtime.settings
        val world = runtime.region.world
        val worldName = world.name
        return work.compute { areaCenter(patch) }.thenCompose { center ->
            if (center == null) return@thenCompose CompletableFuture.completedFuture(null)
            work.callSync(token) {
                var found: FarmPointPosition? = null
                val radius = rules.careRadius
                var x = center.x - radius; var z = center.z - radius; var y = center.y - 4
                val scan = if (kind != FarmPointKind.HIVE) CompletableFuture.completedFuture(Unit) else work.capture(token, current) {
                    if (found != null || x > center.x + radius) return@capture true
                    if (world.isChunkLoaded(x shr 4, z shr 4) && y in world.minHeight until world.maxHeight) {
                        val block = world.getBlockAt(x, y, z)
                        if (runtime.region.contains(block.location) && (block.type == Material.BEE_NEST || block.type == Material.BEEHIVE)) {
                            found = FarmPointPosition(worldName, x + 0.5, y + 0.5, z + 0.5)
                        }
                    }
                    if (++y > center.y + 1) { y = center.y - 4; if (++z > center.z + radius) { z = center.z - radius; x++ } }
                    found != null || x > center.x + radius
                }
                scan.thenCompose {
                    if (found != null || !rules.proceduralCareFixtures) return@thenCompose CompletableFuture.completedFuture(found)
                    placement.safeGroundCandidatesAsync(runtime, listOf(FarmPointPosition(worldName, center.x.toDouble(), center.y.toDouble(), center.z.toDouble())), radius, current)
                        .thenCompose { candidates ->
                            work.compute {
                                if (candidates.isNotEmpty()) {
                                    val selection = WorksiteDeterministicSeed.derive(placementSequence, kind.ordinal * 31L)
                                    candidates[Math.floorMod(selection, candidates.size.toLong()).toInt()].let { FarmPointPosition(it.world, it.x, it.y, it.z) }
                                } else FarmCarePlanner.spread(patch, 1, WorksiteDeterministicSeed.derive(placementSequence, kind.ordinal.toLong()))
                                    .firstOrNull()?.let { FarmPointPosition(it.world, it.x + 0.5, it.y + 1.0, it.z + 0.5) }
                            }
                        }
                }
            }.thenCompose { it }
        }
    }

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
        // Persisted targets keep an active scene stable across reloads, while a fresh
        // random nonce makes repeated admin/event starts choose genuinely new ground.
        val salt = WorksiteDeterministicSeed.derive(
            WorksiteDeterministicSeed.derive(placementSequence, type.ordinal * 17L + 101L),
            random.nextLong(),
        )
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
                val pen = fixturePoint(runtime, FarmPointKind.PEN, placementSequence) ?: return null
                val sources = placement.sources(runtime, actor?.location)
                val bedCandidates = placement.bedCandidates(runtime, sources, runtime.settings.placementSearchRadius)
                val safePoints = FarmDeliveryPlanner.selectTargets(
                    bedCandidates,
                    pen.x,
                    pen.z,
                    sources.map { it.x to it.z },
                    runtime.settings.placementMinObjectiveDistance.toDouble(),
                    runtime.settings.animalRescueMaxPlayerDistance.toDouble(),
                    runtime.settings.animalRescueTargetCount,
                    salt,
                    runtime.settings.animalRescueMinSpacing,
                ).map { FarmPointPosition(it.world, it.x, it.y, it.z) }
                if (safePoints.isEmpty()) {
                    log(
                        Level.WARNING,
                        "Could not plan farm animal rescue: zone=${runtime.settings.id} " +
                            "sequence=${runtime.state.sequence} reason=no_open_sky_beds " +
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
                val centralBeds = FarmUndergroundEntrySelection.candidates(
                    farmBeds,
                    runtime.settings.moleBurrow.entranceMinBoundaryDistance,
                    runtime.settings.moleBurrow.candidateAttempts,
                    salt xor 0x4D4F4C45L,
                ) { plot, distance -> hasRegionClearance(runtime.region, plot, distance) }
                val bedCandidates = centralBeds
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
            FarmCareType.DITCH_RESCUE -> {
                val safePoints = proceduralDitchSpawnPoints(runtime, farmBeds, placementSequence, salt)
                    .take(runtime.settings.animalRescueTargetCount)
                if (safePoints.isEmpty()) {
                    log(
                        Level.WARNING,
                        "Could not plan farm ditch rescue: zone=${runtime.settings.id} " +
                            "sequence=${runtime.state.sequence} reason=no_safe_ditch " +
                            "indexed_beds=${farmBeds.size}",
                    )
                    return null
                }
                safePoints.mapIndexed { index, position -> FarmCareTarget(index, FarmCareRole.ANIMAL, position) }
            }
        }.takeIf { it.isNotEmpty() }
    }

    private fun existingDitchSpawnPoints(
        runtime: FarmRuntime,
        ditch: FarmPointPosition,
        selection: Long,
    ): List<FarmPointPosition> {
        val world = runtime.region.world.takeIf { it.name == ditch.world } ?: return emptyList()
        val origin = Location(world, ditch.x, ditch.y, ditch.z)
        val candidates = buildList {
            for (x in origin.blockX - DITCH_HALF_WIDTH..origin.blockX + DITCH_HALF_WIDTH) {
                for (z in origin.blockZ - DITCH_HALF_WIDTH..origin.blockZ + DITCH_HALF_WIDTH) {
                    listOf(0, -1, 1).firstNotNullOfOrNull { yOffset ->
                        val location = Location(world, x + 0.5, origin.blockY + yOffset.toDouble(), z + 0.5)
                        location.takeIf { runtime.region.contains(it) && FarmSurfacePolicy.isSurfaceSpawn(it) }
                    }?.let { location ->
                        add(FarmPointPosition(world.name, location.x, location.y, location.z))
                    }
                }
            }
        }
        if (candidates.isEmpty()) return emptyList()
        return centerFirst(candidates, origin.blockX, origin.blockZ, selection)
    }

    private fun proceduralDitchSpawnPoints(
        runtime: FarmRuntime,
        beds: Collection<FarmPlotPosition>,
        placementSequence: Long,
        selection: Long,
    ): List<FarmPointPosition> {
        val indexed = beds.groupBy { Triple(it.world, it.x, it.z) }
        val offsets = FarmDitchLayout.cells(placementSequence)
        val candidates = beds.mapNotNull { center ->
            val footprint = offsets.map { offset ->
                indexed[Triple(center.world, center.x + offset.x, center.z + offset.z)]
                    .orEmpty()
                    .asSequence()
                    .filter { kotlin.math.abs(it.y - center.y) <= DITCH_MAX_SURFACE_STEP }
                    .sortedBy { kotlin.math.abs(it.y - center.y) }
                    .firstOrNull { plot ->
                        val soil = plot.block() ?: return@firstOrNull false
                        soil.type == Material.FARMLAND && FarmSurfacePolicy.isOutdoorBed(soil) &&
                            runtime.region.contains(soil.location)
                    }
            }
            footprint.takeIf { plots -> plots.all { it != null } }
                ?.filterNotNull()
                ?.takeIf { plots ->
                    val ys = plots.map(FarmPlotPosition::y)
                    (ys.maxOrNull() ?: center.y) - (ys.minOrNull() ?: center.y) <= DITCH_MAX_SURFACE_STEP
                }
        }
        debug.event(
            "farm_care_ditch_candidates",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "placement_sequence" to placementSequence,
            "beds" to beds.size,
            "candidates" to candidates.size,
            "surface_step" to DITCH_MAX_SURFACE_STEP,
        )
        if (candidates.isEmpty()) return emptyList()
        val footprint = candidates[Math.floorMod(selection, candidates.size.toLong()).toInt()]
        return footprint.map { plot ->
            FarmPointPosition(
                plot.world,
                plot.x + 0.5,
                plot.y.toDouble(),
                plot.z + 0.5,
            )
        }
    }

    private fun centerFirst(
        candidates: List<FarmPointPosition>,
        centerX: Int,
        centerZ: Int,
        selection: Long,
    ): List<FarmPointPosition> {
        val center = candidates.firstOrNull { it.x.toInt() == centerX && it.z.toInt() == centerZ }
        val rest = candidates.filterNot { it == center }
        if (rest.isEmpty()) return listOfNotNull(center)
        val start = Math.floorMod(selection, rest.size.toLong()).toInt()
        return listOfNotNull(center) + rest.indices.map { rest[(start + it) % rest.size] }
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
            val selection = WorksiteDeterministicSeed.derive(placementSequence, kind.ordinal * 31L)
            val chosen = candidates[Math.floorMod(selection, candidates.size.toLong()).toInt()]
            return FarmPointPosition(chosen.world, chosen.x, chosen.y, chosen.z)
        }
        val fallback = FarmCarePlanner.spread(
            patch, 1, WorksiteDeterministicSeed.derive(placementSequence, kind.ordinal.toLong()),
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

    private companion object {
        const val DITCH_HALF_WIDTH = 1
    }

}
