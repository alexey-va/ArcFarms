package ru.ruscrafting.farms.paper.mine.incident.cavein

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.event.block.BlockBreakEvent
import ru.ruscrafting.farms.config.MessageKey
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
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import java.util.logging.Level

/** A physical, crash-safe rubble wall placed in a suitable passage of the live mine. */
internal class MineCaveInIncident(
    private val registry: MineRuntimeRegistry,
    private val index: MineBlockIndex,
    private val incidents: MineIncidentCoordinator,
    private val journal: MineIncidentBlockJournal,
    private val recovery: MineBlockRecoveryController,
    private val audience: WorksiteAudiencePort,
    private val state: WorksiteStatePort,
    private val lift: MineLiftAccess?,
    private val effects: MineIncidentEntityEffects,
) {
    private val placementFailures = mutableMapOf<String, String>()
    private val loggedBlockedTargets = mutableSetOf<String>()

    fun start(runtime: MineRuntime, now: Long): Boolean {
        val selection = select(runtime)
        val footprint = selection.footprint
        if (footprint == null) {
            placementFailures[runtime.settings.id] =
                "required=$RUBBLE_BLOCKS usable=0 considered=${selection.considered} checked=${selection.checked} " +
                "rejected=${selection.rejected.entries.joinToString(",", "{", "}") { "${it.key}=${it.value}" }}"
            return false
        }
        val candidates = footprint.blocks.mapIndexed { ordinal, position ->
            ObjectiveTargetCandidate(
                "cave_rubble_${ordinal + 1}_${position.x}_${position.y}_${position.z}".replace('-', 'm').take(48),
                position,
                ObjectiveTargetRole(RUBBLE_ROLE),
                ordinal.toLong(),
            )
        }
        if (!incidents.start(runtime, MineIncidentType.CAVE_IN, candidates.size, now, candidates)) {
            placementFailures[runtime.settings.id] =
                "required=$RUBBLE_BLOCKS usable=${candidates.size} considered=${selection.considered} checked=${selection.checked} " +
                "rejected={state_transition_rejected=1}"
            return false
        }
        placementFailures.remove(runtime.settings.id)
        reconcile(runtime)
        return true
    }

    fun placementFailure(zoneId: String): String? = placementFailures[zoneId]

    fun diagnostics(runtime: MineRuntime): MineIncidentPlacementReport {
        val selection = select(runtime)
        return MineIncidentPlacementReport(
            MineIncidentType.CAVE_IN,
            RUBBLE_BLOCKS,
            selection.footprint?.blocks?.size ?: 0,
            selection.considered,
            selection.rejected,
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
            if (completed && !active(runtime)) {
                journal.restore(runtime, INCIDENT_ID)
                effects.cleanup(runtime, MineIncidentEntityKind.CAVE_IN_MARKER)
            }
        }
        return true
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
        targets.forEachIndexed { ordinal, target ->
            if (target.status == ObjectiveTargetStatus.COMPLETED) {
                if (target.position in existing) journal.restoreNow(target.position)
            } else if (target.position in existing) {
                journal.ensureTemporary(target.position, RUBBLE)
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
        placementFailures.remove(runtime.settings.id)
        loggedBlockedTargets.removeIf { it.startsWith("${runtime.settings.id}:") }
        effects.cleanup(runtime, MineIncidentEntityKind.CAVE_IN_MARKER)
        return journal.restore(runtime, INCIDENT_ID)
    }

    private fun select(runtime: MineRuntime): Selection {
        val anchors = index.targets(runtime.settings.id, MineAnchorRole.NEST)
            .let { orderForParticipants(runtime, it) }
        val rejected = linkedMapOf<String, Int>()
        var checked = 0
        anchors.take(MAX_CHECKS).forEach { anchor ->
            checked++
            val issue = anchorIssue(runtime, anchor)
            if (issue != null) {
                rejected[issue] = rejected.getOrDefault(issue, 0) + 1
                return@forEach
            }
            val footprint = footprints(runtime, anchor).firstOrNull { rubbleIssue(runtime, it) == null }
            if (footprint != null) return Selection(footprint, anchors.size, checked, rejected)
            val footprintIssues = footprints(runtime, anchor).mapNotNull { rubbleIssue(runtime, it) }
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
        return Selection(null, anchors.size, checked, rejected)
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

    private fun footprints(runtime: MineRuntime, anchor: WorksitePosition): List<Footprint> =
        listOf(false, true).map { rotated -> footprint(runtime, anchor, rotated) }

    private fun footprint(runtime: MineRuntime, anchor: WorksitePosition, rotated: Boolean): Footprint {
        fun point(dx: Int, dy: Int, dz: Int): WorksitePosition {
            val (x, z) = if (rotated) dz to dx else dx to dz
            return anchor.copy(x = anchor.x + x, y = anchor.y + dy, z = anchor.z + z)
        }
        val bottom = (-2..2).flatMap { dx -> (-1..2).map { dz -> point(dx, 1, dz) } }
        val second = (-2..2).flatMap { dx -> (-1..2).map { dz -> point(dx, 2, dz) } }
        val third = (-1..2).flatMap { dx -> (-1..2).map { dz -> point(dx, 3, dz) } }
        val cap = listOf(0 to 0) + (-1..1).flatMap { dx -> (-1..1).map { dz -> dx to dz } }.filterNot { it == 0 to 0 }
        val seed = WorksiteDeterministicSeed.positionScore(
            runtime.state.sequence xor if (rotated) ROTATED_SALT else 0L,
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
        val roof = bottom.mapNotNull { column -> ceilingPosition(runtime, anchor, column) }
        return Footprint(
            blocks = blocks,
            bottom = bottom,
            roof = roof,
            marker = if (rotated) point(-2, 1, 0) else point(0, 1, -2),
        )
    }

    private fun ceilingPosition(
        runtime: MineRuntime,
        anchor: WorksitePosition,
        column: WorksitePosition,
    ): WorksitePosition? {
        for (dy in MIN_ROOF_HEIGHT..MAX_ROOF_HEIGHT) {
            val position = column.copy(y = anchor.y + dy)
            if (!runtime.region.contains(position.location()) || !position.loaded()) return null
            val material = position.block()?.type ?: return null
            if (material.isAir) continue
            return position.takeIf { material.isSolid && material in runtime.caveCeilingMaterials }
        }
        return null
    }

    private fun orderForParticipants(runtime: MineRuntime, anchors: Collection<WorksitePosition>): List<WorksitePosition> {
        val players = runtime.region.world.players.filter { runtime.region.contains(it.location) }
        val stable = compareBy<WorksitePosition>({ it.y }, { it.x }, { it.z })
        if (players.isEmpty()) return anchors.sortedWith(stable).rotate(runtime.state.sequence)
        return anchors.sortedWith(compareBy<WorksitePosition> { anchor ->
            players.minOf { player ->
                val dx = anchor.x + 0.5 - player.location.x
                val dy = anchor.y + 1.0 - player.location.y
                val dz = anchor.z + 0.5 - player.location.z
                kotlin.math.abs(dx * dx + dz * dz - IDEAL_PLAYER_DISTANCE_SQUARED) + dy * dy * 4.0
            }
        }.then(stable))
    }

    private fun reconcileMarker(runtime: MineRuntime, positions: List<WorksitePosition>) {
        val marker = markerPosition(positions) ?: return
        val chunkX = marker.x shr 4
        val chunkZ = marker.z shr 4
        val world = runtime.region.world
        if (world.isChunkLoaded(chunkX, chunkZ)) {
            effects.reconcileChunk(runtime, world.getChunkAt(chunkX, chunkZ), MineIncidentEntityKind.CAVE_IN_MARKER,
                mapOf(MARKER_TARGET_ID to marker))
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

    private fun List<WorksitePosition>.rotate(sequence: Long): List<WorksitePosition> {
        if (isEmpty()) return this
        val offset = java.lang.Math.floorMod(sequence * 31L + 17L, size.toLong()).toInt()
        return drop(offset) + take(offset)
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

    private data class Selection(
        val footprint: Footprint?,
        val considered: Int,
        val checked: Int,
        val rejected: Map<String, Int>,
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
        const val INCIDENT_ID = "cave_in"
        const val RUBBLE_ROLE = "cave_in_rubble"
        const val RUBBLE_BLOCKS = 60
        const val MIN_RUBBLE_BLOCKS = 55
        const val MAX_RUBBLE_BLOCKS = 65
        const val MIN_ROOF_BLOCKS = 12
        const val MIN_ROOF_HEIGHT = 5
        const val MAX_ROOF_HEIGHT = 12
        const val MARKER_TARGET_ID = "cave_in_marker"
        const val MAX_CHECKS = 512
        const val PLAYER_CLEARANCE_SQUARED = 36.0
        const val LIFT_CLEARANCE_SQUARED = 100.0
        const val IDEAL_PLAYER_DISTANCE_SQUARED = 196.0
        val RUBBLE = Material.COBBLESTONE
    }
}
