package ru.ruscrafting.farms.paper.mine.incident.cavein

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.placement.WorksitePlacementPlanner
import ru.ruscrafting.farms.domain.placement.toPlacementPoint
import ru.ruscrafting.farms.domain.worksite.WorksiteDeterministicSeed
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentPlacementReport
import ru.ruscrafting.farms.paper.mine.incident.entity.MineIncidentEntityEffects
import ru.ruscrafting.farms.paper.mine.incident.entity.MineIncidentEntityKind
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import ru.ruscrafting.farms.paper.mine.lift.MineLiftAccess
import ru.ruscrafting.farms.paper.mine.recovery.MineBlockRecoveryController
import ru.ruscrafting.farms.paper.mine.recovery.MineIncidentBlockJournal
import ru.ruscrafting.farms.paper.mine.recovery.MineTemporaryEnsureResult
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteAsyncBlockScanner
import ru.ruscrafting.farms.paper.worksite.WorksiteBlockSnapshot
import ru.ruscrafting.farms.paper.worksite.WorksiteChunkCoordinate
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import java.util.logging.Level
import kotlin.math.floor

/** A physical, crash-safe rubble wall placed in a suitable passage of the live mine. */
internal class MineCaveInIncident(
    private val registry: MineRuntimeRegistry,
    private val index: MineBlockIndex,
    private val incidents: MineIncidentCoordinator,
    private val journal: MineIncidentBlockJournal,
    private val recovery: MineBlockRecoveryController,
    private val audience: WorksiteAudiencePort,
    private val state: WorksiteStatePort,
    private val blockScanner: WorksiteAsyncBlockScanner,
    private val lift: MineLiftAccess?,
    private val effects: MineIncidentEntityEffects,
) {
    private val placementFailures = mutableMapOf<String, String>()
    private val placementReports = mutableMapOf<String, MineIncidentPlacementReport>()
    private val pendingSearches = mutableMapOf<String, PendingSearch>()
    private val retryAfter = mutableMapOf<String, Long>()
    private val loggedBlockedTargets = mutableSetOf<String>()

    fun start(runtime: MineRuntime, now: Long): Boolean {
        val zoneId = runtime.settings.id
        pendingSearches[zoneId]?.let { pending ->
            if (pending.sequence == runtime.state.sequence) return true
            pendingSearches.remove(zoneId, pending)
        }
        if (now < retryAfter.getOrDefault(zoneId, 0L)) return false

        val world = runtime.region.world
        val indexedAnchors = index.targets(zoneId, MineAnchorRole.NEST)
        // MineWorldWarmup owns bounded full-region retention. Until it reaches this zone,
        // never submit a cold all-index snapshot: the async scanner can only inspect loaded chunks.
        val anchors = indexedAnchors.filter { world.isChunkLoaded(it.x shr 4, it.z shr 4) }
        if (anchors.isEmpty()) {
            rejectSearch(runtime, now, SearchResult(emptyList(), indexedAnchors.size, 0,
                mapOf((if (indexedAnchors.isEmpty()) "no_indexed_anchors" else "warmup_pending") to 1)))
            return false
        }
        val chunkCoordinates = buildSet {
            anchors.forEach { anchor ->
                for (chunkX in (anchor.x - FOOTPRINT_RADIUS shr 4)..(anchor.x + FOOTPRINT_RADIUS shr 4)) {
                    for (chunkZ in (anchor.z - FOOTPRINT_RADIUS shr 4)..(anchor.z + FOOTPRINT_RADIUS shr 4)) {
                        if (world.isChunkLoaded(chunkX, chunkZ)) add(WorksiteChunkCoordinate(chunkX, chunkZ))
                    }
                }
            }
        }
        val search = PendingSearch(
            runtime = runtime,
            sequence = runtime.state.sequence,
            requestedAt = now,
            anchors = anchors,
            bounds = runtime.region.bounds,
            caveCeilingMaterials = runtime.caveCeilingMaterials.toSet(),
            participants = world.players.asSequence()
                .filter { runtime.region.contains(it.location) }
                .map { ParticipantPoint(it.location.x, it.location.y, it.location.z) }
                .toList(),
            liftPoints = lift?.floors().orEmpty().mapNotNull { floor ->
                floor.exit.takeIf { it.world.name == world.name }?.let { ParticipantPoint(it.x, it.y, it.z) }
            },
            recoveryPositions = recovery.records(zoneId).mapTo(hashSetOf()) { it.positionKey },
        )
        pendingSearches[zoneId] = search
        placementFailures.remove(zoneId)
        placementReports.remove(zoneId)
        if (!blockScanner.submit(
                world = world,
                chunkCoordinates = chunkCoordinates,
                stillValid = { searchStillCurrent(search) },
                plan = { snapshot -> select(search, snapshot) },
                complete = { result ->
                    result.fold(
                        onSuccess = { finishSearch(search, it) },
                        onFailure = { failure -> failSearch(search, failure) },
                    )
                },
            )) {
            pendingSearches.remove(zoneId, search)
            rejectSearch(runtime, now, SearchResult(emptyList(), anchors.size, 0, mapOf("async_scan_unavailable" to 1)))
            return false
        }
        return true
    }

    private fun beginIncident(runtime: MineRuntime, footprint: Footprint, selection: SearchResult, now: Long): Boolean {
        val candidates = footprint.blocks.mapIndexed { ordinal, position ->
            ObjectiveTargetCandidate(
                "cave_rubble_${ordinal + 1}_${position.x}_${position.y}_${position.z}".replace('-', 'm').take(48),
                position,
                ObjectiveTargetRole(RUBBLE_ROLE),
                ordinal.toLong(),
            )
        }
        if (!incidents.start(runtime, MineIncidentType.CAVE_IN, candidates.size, now, candidates)) {
            rejectSearch(
                runtime,
                now,
                selection.copy(candidates = emptyList(), rejected = mapOf("state_transition_rejected" to 1)),
            )
            return false
        }
        placementFailures.remove(runtime.settings.id)
        placementReports[runtime.settings.id] = MineIncidentPlacementReport(
            MineIncidentType.CAVE_IN, RUBBLE_BLOCKS, candidates.size, selection.considered, selection.rejected,
        )
        retryAfter.remove(runtime.settings.id)
        reconcile(runtime)
        return true
    }

    fun placementFailure(zoneId: String): String? = placementFailures[zoneId]

    fun diagnostics(runtime: MineRuntime): MineIncidentPlacementReport {
        placementReports[runtime.settings.id]?.let { return it }
        val pending = pendingSearches[runtime.settings.id]
        return MineIncidentPlacementReport(
            MineIncidentType.CAVE_IN, RUBBLE_BLOCKS, 0,
            pending?.anchors?.size ?: index.loadedTargets(runtime.settings.id, MineAnchorRole.NEST).size,
            if (pending == null) emptyMap() else mapOf("search_pending" to 1),
        )
    }

    fun onBreak(event: BlockBreakEvent): Boolean {
        val runtime = registry.at(event.block.location) ?: return false
        if (!active(runtime)) return false
        val position = event.block.position()
        val target = runtime.state.objective?.targets?.firstOrNull {
            it.position == position && it.role.value == RUBBLE_ROLE && it.status != ObjectiveTargetStatus.COMPLETED
        } ?: return false
        event.isCancelled = true
        event.isDropItems = false
        event.expToDrop = 0
        if (event.block.type != RUBBLE) {
            state.log(
                Level.WARNING,
                "Mine cave-in rubble break rejected zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                    "target=${target.id} position=$position reason=unexpected_material actual=${event.block.type} expected=$RUBBLE",
            )
            return true
        }
        if (!MaterialRules.isPickaxe(event.player.inventory.itemInMainHand)) {
            audience.sendActionBar(event.player, MessageKey.MINE_PICKAXE_REQUIRED)
            return true
        }
        if (!canMine(event.player, event.block)) return true
        journal.restoreNow(position).whenComplete { restored, failure ->
            if (failure != null || restored != true) {
                state.log(
                    Level.WARNING,
                    "Mine cave-in rubble break rejected zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                        "target=${target.id} position=$position reason=${failure?.javaClass?.simpleName ?: "journal_missing"}",
                    failure,
                )
                return@whenComplete
            }
            val completed = incidents.completeTarget(runtime, target.id, event.player).accepted
            if (completed && active(runtime)) {
                reconcileMarker(runtime, runtime.state.objective?.targets.orEmpty().map { it.position })
            }
            if (completed && !active(runtime)) {
                journal.restore(runtime, INCIDENT_ID)
                effects.cleanup(runtime, MineIncidentEntityKind.CAVE_IN_MARKER)
            }
        }
        return true
    }

    /** Exact target predicate for the high-priority break/damage reclaim guard. */
    fun canMine(player: Player, block: Block): Boolean {
        val runtime = registry.at(block.location) ?: return false
        if (!active(runtime) || block.type != RUBBLE || !MaterialRules.isPickaxe(player.inventory.itemInMainHand)) return false
        if (player.world !== block.world || player.location.distanceSquared(block.location.clone().add(0.5, 0.5, 0.5)) > PLAYER_INTERACTION_DISTANCE_SQUARED) {
            return false
        }
        val position = block.position()
        return runtime.state.objective?.targets.orEmpty().any {
            it.role.value == RUBBLE_ROLE && it.status != ObjectiveTargetStatus.COMPLETED && it.position == position
        }
    }

    fun reconcile(runtime: MineRuntime): Int {
        if (!active(runtime)) return 0
        val targets = runtime.state.objective?.targets.orEmpty().filter { it.role.value == RUBBLE_ROLE }
        if (targets.size !in MIN_RUBBLE_BLOCKS..MAX_RUBBLE_BLOCKS) {
            targets.forEach { target ->
                if (!recovery.containsPosition(target.position.key()) && target.position.block()?.type == RUBBLE) {
                    target.position.block()?.setType(Material.AIR, false)
                }
            }
            effects.cleanup(runtime, MineIncidentEntityKind.CAVE_IN_MARKER)
            incidents.abort(runtime)
            state.log(Level.WARNING, "Mine cave-in legacy scene aborted zone=${runtime.settings.id} " +
                "sequence=${runtime.state.sequence} targets=${targets.size} expected=$MIN_RUBBLE_BLOCKS..$MAX_RUBBLE_BLOCKS")
            return 0
        }
        val existing = journal.positions(runtime, INCIDENT_ID).toSet()
        val missing = mutableListOf<Pair<Int, WorksitePosition>>()
        var replayRejected = false
        var replayPending = false
        targets.forEachIndexed { ordinal, target ->
            if (target.status == ObjectiveTargetStatus.COMPLETED) {
                if (target.position in existing) journal.restoreNow(target.position)
            } else if (target.position in existing) {
                when (journal.ensureTemporaryResult(target.position, RUBBLE)) {
                    MineTemporaryEnsureResult.READY -> Unit
                    MineTemporaryEnsureResult.PENDING -> replayPending = true
                    MineTemporaryEnsureResult.REJECTED -> {
                        replayRejected = true
                        state.log(
                            Level.WARNING,
                            "Mine cave-in recovery rejected zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                                "target=${target.id} position=${target.position} reason=journal_conflict",
                        )
                    }
                }
            } else if (target.position.block()?.type == Material.AIR) {
                missing += ordinal to target.position
            } else {
                val key = "${runtime.settings.id}:${runtime.state.sequence}:${target.id}"
                if (loggedBlockedTargets.add(key)) state.log(
                    Level.WARNING, "Mine cave-in reconcile blocked zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                        "target=${target.id} position=${target.position} reason=footprint_occupied material=${target.position.block()?.type}",
                )
            }
        }
        if (replayRejected) {
            journal.restore(runtime, INCIDENT_ID)
            effects.cleanup(runtime, MineIncidentEntityKind.CAVE_IN_MARKER)
            incidents.abort(runtime)
            return 0
        }
        if (replayPending) return 0
        if (missing.isNotEmpty()) {
            journal.prepareAll(runtime, INCIDENT_ID, missing, RUBBLE).whenComplete { prepared, failure ->
                if (failure == null && prepared == true) {
                    if (active(runtime)) reconcileMarker(runtime, targets.map { it.position })
                } else {
                    state.log(
                        Level.WARNING,
                        "Mine cave-in placement failed zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                            "blocks=${missing.size} reason=${failure?.javaClass?.simpleName ?: "journal_rejected"}",
                        failure,
                    )
                    if (active(runtime)) {
                        journal.restore(runtime, INCIDENT_ID)
                        effects.cleanup(runtime, MineIncidentEntityKind.CAVE_IN_MARKER)
                        incidents.abort(runtime)
                    }
                }
            }
        } else if (existing.size == targets.count { it.status != ObjectiveTargetStatus.COMPLETED }) {
            reconcileMarker(runtime, targets.map { it.position })
        }
        return missing.size
    }

    fun cleanup(runtime: MineRuntime): Int {
        cancelPending(runtime.settings.id)
        placementFailures.remove(runtime.settings.id)
        placementReports.remove(runtime.settings.id)
        retryAfter.remove(runtime.settings.id)
        loggedBlockedTargets.removeIf { it.startsWith("${runtime.settings.id}:") }
        effects.cleanup(runtime, MineIncidentEntityKind.CAVE_IN_MARKER)
        return journal.restore(runtime, INCIDENT_ID)
    }

    fun cancelPending(zoneId: String) {
        pendingSearches.remove(zoneId)
    }

    private fun failSearch(search: PendingSearch, failure: Throwable) {
        if (pendingSearches[search.runtime.settings.id] !== search) return
        pendingSearches.remove(search.runtime.settings.id)
        state.log(
            Level.SEVERE,
            "Mine cave-in async placement failed zone=${search.runtime.settings.id} sequence=${search.sequence}",
            failure,
        )
        rejectSearch(
            search.runtime,
            search.requestedAt,
            SearchResult(emptyList(), search.anchors.size, 0, mapOf("async_scan_failed" to 1)),
        )
    }

    private fun finishSearch(search: PendingSearch, result: SearchResult) {
        val runtime = search.runtime
        if (!searchStillCurrent(search)) return
        val selected = result.candidates.asSequence().mapNotNull { anchor ->
            if (anchorIssue(runtime, anchor) != null) return@mapNotNull null
            footprints(runtime.state.sequence, anchor) { column ->
                ceilingPosition(runtime.region.bounds, runtime.caveCeilingMaterials, anchor, column)
            }
                .firstOrNull { rubbleIssue(runtime, it) == null }
        }.firstOrNull()
        pendingSearches.remove(runtime.settings.id, search)
        if (selected == null) {
            val rejected = result.rejected.toMutableMap()
            if (result.candidates.isNotEmpty()) rejected["snapshot_stale"] = result.candidates.size
            rejectSearch(runtime, search.requestedAt, result.copy(rejected = rejected))
            return
        }
        beginIncident(runtime, selected, result, search.requestedAt)
    }

    private fun rejectSearch(runtime: MineRuntime, now: Long, result: SearchResult) {
        val report = MineIncidentPlacementReport(
            MineIncidentType.CAVE_IN, RUBBLE_BLOCKS, 0, result.considered, result.rejected,
        )
        placementReports[runtime.settings.id] = report
        placementFailures[runtime.settings.id] = report.technical().replace(
            "considered=${result.considered}",
            "considered=${result.considered} checked=${result.checked}",
        )
        retryAfter[runtime.settings.id] = now + RETRY_MILLIS
        state.log(
            Level.WARNING,
            "Mine cave-in placement search rejected zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                placementFailures.getValue(runtime.settings.id),
        )
    }

    private fun searchStillCurrent(search: PendingSearch): Boolean {
        val runtime = search.runtime
        val current = pendingSearches[runtime.settings.id] === search && runtime.state.sequence == search.sequence &&
            runtime.state.incident == null && runtime.state.phase != MinePhase.IDLE
        if (!current) pendingSearches.remove(runtime.settings.id, search)
        return current
    }

    private fun select(search: PendingSearch, snapshot: WorksiteBlockSnapshot): SearchResult {
        val anchors = orderForParticipants(search.sequence, search.anchors, search.participants)
        val rejected = linkedMapOf<String, Int>()
        var checked = 0
        val candidates = mutableListOf<WorksitePosition>()
        for (anchor in anchors) {
            checked++
            val issue = snapshotAnchorIssue(search, snapshot, anchor)
            if (issue != null) {
                rejected[issue] = rejected.getOrDefault(issue, 0) + 1
                continue
            }
            val footprints = footprints(search.sequence, anchor) { column ->
                ceilingPosition(search.bounds, search.caveCeilingMaterials, anchor, column, snapshot::type)
            }
            val footprintIssues = footprints.map { footprint -> snapshotRubbleIssue(search, snapshot, footprint) }
            if (footprintIssues.any { it == null }) {
                candidates += anchor
                if (candidates.size >= MAX_REVALIDATION_CANDIDATES) break
                continue
            }
            val reason = when {
                "journalled_block" in footprintIssues -> "journalled_block"
                "outside_region" in footprintIssues -> "outside_region"
                "chunk_unloaded" in footprintIssues -> "chunk_unloaded"
                "missing_stone_or_ore_ceiling" in footprintIssues -> "missing_stone_or_ore_ceiling"
                "missing_solid_floor" in footprintIssues -> "missing_solid_floor"
                else -> "footprint_occupied"
            }
            rejected[reason] = rejected.getOrDefault(reason, 0) + 1
        }
        return SearchResult(candidates, anchors.size, checked, rejected)
    }

    private fun snapshotAnchorIssue(
        search: PendingSearch,
        snapshot: WorksiteBlockSnapshot,
        anchor: WorksitePosition,
    ): String? {
        val floor = snapshot.type(anchor) ?: return "chunk_unloaded"
        if (!floor.isSolid || snapshot.type(anchor.copy(y = anchor.y + 1))?.isSolid != false ||
            snapshot.type(anchor.copy(y = anchor.y + 2))?.isSolid != false) return "anchor_changed"
        val centerX = anchor.x + 0.5
        val centerY = anchor.y + 1.0
        val centerZ = anchor.z + 0.5
        if (search.participants.any { it.distanceSquared(centerX, centerY, centerZ) < PLAYER_CLEARANCE_SQUARED }) {
            return "near_player"
        }
        if (search.liftPoints.any { it.distanceSquared(centerX, centerY, centerZ) < LIFT_CLEARANCE_SQUARED }) {
            return "near_lift"
        }
        return null
    }

    private fun snapshotRubbleIssue(
        search: PendingSearch,
        snapshot: WorksiteBlockSnapshot,
        footprint: Footprint,
    ): String? {
        val occupied = footprint.blocks + footprint.marker
        val checked = occupied + footprint.roof
        val bounds = search.bounds
        if (checked.any { it.x !in bounds.minX..bounds.maxX || it.y !in bounds.minY..bounds.maxY ||
                it.z !in bounds.minZ..bounds.maxZ }) return "outside_region"
        if (checked.any { snapshot.type(it) == null }) return "chunk_unloaded"
        if (footprint.blocks.any { it.key() in search.recoveryPositions }) return "journalled_block"
        if (footprint.bottom.any { snapshot.type(it.copy(y = it.y - 1))?.isSolid != true }) return "missing_solid_floor"
        if (footprint.roof.size < MIN_ROOF_BLOCKS) return "missing_stone_or_ore_ceiling"
        if (occupied.any { snapshot.type(it)?.isAir != true }) return "footprint_occupied"
        return null
    }

    private fun anchorIssue(runtime: MineRuntime, anchor: WorksitePosition): String? {
        val world = Bukkit.getWorld(anchor.world) ?: return "world_unavailable"
        if (!world.isChunkLoaded(anchor.x shr 4, anchor.z shr 4)) return "chunk_unloaded"
        if (!index.isLiveTarget(runtime.settings.id, anchor, MineAnchorRole.NEST, runtime.railMaterials)) return "anchor_changed"
        val center = anchor.copy(y = anchor.y + 1)
        if (world.players.any { it.location.distanceSquared(center.location()) < PLAYER_CLEARANCE_SQUARED }) return "near_player"
        if (lift?.floors().orEmpty().any { floor ->
                floor.exit.world.name == anchor.world && floor.exit.distanceSquared(center.location()) < LIFT_CLEARANCE_SQUARED
            }) return "near_lift"
        return null
    }

    private fun rubbleIssue(runtime: MineRuntime, footprint: Footprint): String? {
        val occupied = footprint.blocks + footprint.marker
        val checked = occupied + footprint.roof
        if (checked.any { !runtime.region.contains(it.location()) }) return "outside_region"
        if (checked.any { !it.loaded() }) return "chunk_unloaded"
        if (footprint.blocks.any { recovery.containsPosition(it.key()) }) return "journalled_block"
        if (footprint.bottom.any { it.copy(y = it.y - 1).block()?.type?.isSolid != true }) return "missing_solid_floor"
        if (footprint.roof.size < MIN_ROOF_BLOCKS) return "missing_stone_or_ore_ceiling"
        if (occupied.any { it.block()?.type != Material.AIR }) return "footprint_occupied"
        return null
    }

    private fun footprints(
        sequence: Long,
        anchor: WorksitePosition,
        ceiling: (WorksitePosition) -> WorksitePosition?,
    ): List<Footprint> = listOf(false, true).map { rotated -> footprint(sequence, anchor, rotated, ceiling) }

    private fun footprint(
        sequence: Long,
        anchor: WorksitePosition,
        rotated: Boolean,
        ceiling: (WorksitePosition) -> WorksitePosition?,
    ): Footprint {
        fun point(dx: Int, dy: Int, dz: Int): WorksitePosition {
            val (x, z) = if (rotated) dz to dx else dx to dz
            return anchor.copy(x = anchor.x + x, y = anchor.y + dy, z = anchor.z + z)
        }
        val bottom = (-2..2).flatMap { dx -> (-1..2).map { dz -> point(dx, 1, dz) } }
        val second = (-2..2).flatMap { dx -> (-1..2).map { dz -> point(dx, 2, dz) } }
        val third = (-1..2).flatMap { dx -> (-1..2).map { dz -> point(dx, 3, dz) } }
        val cap = listOf(0 to 0) + (-1..1).flatMap { dx -> (-1..1).map { dz -> dx to dz } }.filterNot { it == 0 to 0 }
        val seed = WorksiteDeterministicSeed.positionScore(
            sequence xor if (rotated) ROTATED_SALT else 0L,
            anchor.world,
            anchor.x,
            anchor.y,
            anchor.z,
        )
        val shuffledThird = WorksitePlacementPlanner.seededOrder(
            third,
            seed,
            WorksitePosition::toPlacementPoint,
        )
        val shuffledCap = cap.map { (dx, dz) -> point(dx, 4, dz) }.let { list ->
            listOf(list.first()) + WorksitePlacementPlanner.seededOrder(
                list.drop(1),
                seed xor CAP_SALT,
                WorksitePosition::toPlacementPoint,
            )
        }
        val count = MIN_RUBBLE_BLOCKS + java.lang.Math.floorMod(seed, (MAX_RUBBLE_BLOCKS - MIN_RUBBLE_BLOCKS + 1).toLong()).toInt()
        val mandatory = bottom + second + shuffledThird.take(14) + shuffledCap.take(1)
        val optional = shuffledThird.drop(14) + shuffledCap.drop(1)
        val blocks = mandatory + optional.take(count - mandatory.size)
        val roof = bottom.mapNotNull(ceiling)
        return Footprint(
            blocks = blocks,
            bottom = bottom,
            roof = roof,
            marker = if (rotated) point(-2, 1, 0) else point(0, 1, -2),
        )
    }

    private fun ceilingPosition(
        bounds: CuboidBounds,
        caveCeilingMaterials: Set<Material>,
        anchor: WorksitePosition,
        column: WorksitePosition,
        typeAt: (WorksitePosition) -> Material? = { it.block()?.type },
    ): WorksitePosition? {
        for (dy in MIN_ROOF_HEIGHT..MAX_ROOF_HEIGHT) {
            val position = column.copy(y = anchor.y + dy)
            if (position.x !in bounds.minX..bounds.maxX || position.y !in bounds.minY..bounds.maxY ||
                position.z !in bounds.minZ..bounds.maxZ) return null
            val material = typeAt(position) ?: return null
            if (material.isAir) continue
            return position.takeIf { material.isSolid && material in caveCeilingMaterials }
        }
        return null
    }

    private fun orderForParticipants(
        sequence: Long,
        anchors: Collection<WorksitePosition>,
        participants: List<ParticipantPoint>,
    ): List<WorksitePosition> {
        val seeded = WorksitePlacementPlanner.seededOrder(
            anchors, sequence xor ORDER_SALT, WorksitePosition::toPlacementPoint,
        )
        if (participants.isEmpty()) return seeded
        return seeded.sortedBy { anchor ->
            val score = participants.minOf { participant ->
                val dx = anchor.x + 0.5 - participant.x
                val dy = anchor.y + 1.0 - participant.y
                val dz = anchor.z + 0.5 - participant.z
                kotlin.math.abs(dx * dx + dz * dz - IDEAL_PLAYER_DISTANCE_SQUARED) + dy * dy * 4.0
            }
            kotlin.math.floor(score / PARTICIPANT_SCORE_BAND).toLong()
        }
    }

    private fun reconcileMarker(runtime: MineRuntime, positions: List<WorksitePosition>) {
        val remaining = runtime.state.objective?.targets.orEmpty()
            .filter { it.role.value == RUBBLE_ROLE && it.status != ObjectiveTargetStatus.COMPLETED }
            .associate { it.id to it.position }
        val chunks = positions.map { (it.x shr 4) to (it.z shr 4) }.toSet() +
            listOfNotNull(markerPosition(positions)?.let { (it.x shr 4) to (it.z shr 4) })
        chunks.forEach { (x, z) ->
            if (runtime.region.world.isChunkLoaded(x, z)) effects.reconcileChunk(
                runtime, runtime.region.world.getChunkAt(x, z), MineIncidentEntityKind.CAVE_IN_MARKER, remaining,
            )
        }
    }

    private fun markerPosition(positions: List<WorksitePosition>): WorksitePosition? {
        if (positions.isEmpty()) return null
        val minX = positions.minOf { it.x }; val maxX = positions.maxOf { it.x }
        val minZ = positions.minOf { it.z }; val maxZ = positions.maxOf { it.z }
        val y = positions.minOf { it.y }
        return if (maxX - minX >= maxZ - minZ) {
            WorksitePosition(positions.first().world, (minX + maxX) / 2, y, minZ - 1)
        } else {
            WorksitePosition(positions.first().world, minX - 1, y, (minZ + maxZ) / 2)
        }
    }

    private fun WorksitePosition.location() = org.bukkit.Location(Bukkit.getWorld(world), x + 0.5, y.toDouble(), z + 0.5)
    private fun WorksitePosition.block(): Block? = Bukkit.getWorld(world)
        ?.takeIf { it.isChunkLoaded(x shr 4, z shr 4) }?.getBlockAt(x, y, z)
    private fun WorksitePosition.loaded(): Boolean = Bukkit.getWorld(world)?.isChunkLoaded(x shr 4, z shr 4) == true
    private fun WorksitePosition.key(): String = "$world:$x:$y:$z"
    private fun Block.position() = WorksitePosition(world.name, x, y, z)
    private fun active(runtime: MineRuntime): Boolean =
        runtime.state.phase == MinePhase.INCIDENT && runtime.state.incident?.type == MineIncidentType.CAVE_IN &&
            runtime.state.incident?.scenarioPlacement == null

    private data class SearchResult(
        val candidates: List<WorksitePosition>,
        val considered: Int,
        val checked: Int,
        val rejected: Map<String, Int>,
    )

    private data class ParticipantPoint(val x: Double, val y: Double, val z: Double) {
        fun distanceSquared(otherX: Double, otherY: Double, otherZ: Double): Double {
            val dx = x - otherX
            val dy = y - otherY
            val dz = z - otherZ
            return dx * dx + dy * dy + dz * dz
        }
    }

    private data class PendingSearch(
        val runtime: MineRuntime,
        val sequence: Long,
        val requestedAt: Long,
        val anchors: List<WorksitePosition>,
        val bounds: CuboidBounds,
        val caveCeilingMaterials: Set<Material>,
        val participants: List<ParticipantPoint>,
        val liftPoints: List<ParticipantPoint>,
        val recoveryPositions: Set<String>,
    )

    private data class Footprint(
        val blocks: List<WorksitePosition>,
        val bottom: List<WorksitePosition>,
        val roof: List<WorksitePosition>,
        val marker: WorksitePosition,
    )

    private companion object {
        const val ROTATED_SALT = 0x524f5441544544L
        const val CAP_SALT = 0x434150L
        const val ORDER_SALT = 0x43415645494eL
        const val INCIDENT_ID = "cave_in"
        const val RUBBLE_ROLE = "cave_in_rubble"
        const val RUBBLE_BLOCKS = 60
        const val MIN_RUBBLE_BLOCKS = 55
        const val MAX_RUBBLE_BLOCKS = 65
        const val MIN_ROOF_BLOCKS = 12
        const val MIN_ROOF_HEIGHT = 5
        const val MAX_ROOF_HEIGHT = 12
        const val MARKER_TARGET_ID = "cave_in_marker"
        const val FOOTPRINT_RADIUS = 2
        const val MAX_REVALIDATION_CANDIDATES = 32
        const val RETRY_MILLIS = 5_000L
        const val PLAYER_CLEARANCE_SQUARED = 36.0
        const val PLAYER_INTERACTION_DISTANCE_SQUARED = 25.0
        const val LIFT_CLEARANCE_SQUARED = 100.0
        const val IDEAL_PLAYER_DISTANCE_SQUARED = 196.0
        const val PARTICIPANT_SCORE_BAND = 64.0
        val RUBBLE = Material.COBBLESTONE
    }
}
