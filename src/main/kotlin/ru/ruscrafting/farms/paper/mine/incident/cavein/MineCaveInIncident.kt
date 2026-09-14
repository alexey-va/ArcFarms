package ru.ruscrafting.farms.paper.mine.incident.cavein

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.event.block.BlockBreakEvent
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator
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
) {
    private val placementFailures = mutableMapOf<String, String>()

    fun start(runtime: MineRuntime, now: Long): Boolean {
        val selection = select(runtime)
        val footprint = selection.footprint
        if (footprint == null) {
            placementFailures[runtime.settings.id] =
                "required=$RUBBLE_BLOCKS usable=0 considered=${selection.considered} checked=${selection.checked} " +
                "rejected=${selection.rejected.entries.joinToString(",", "{", "}") { "${it.key}=${it.value}" }}"
            return false
        }
        val candidates = footprint.mapIndexed { ordinal, position ->
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
            if (completed && !active(runtime)) journal.restore(runtime, INCIDENT_ID)
        }
        return true
    }

    fun reconcile(runtime: MineRuntime): Int {
        if (!active(runtime)) return 0
        val existing = journal.positions(runtime, INCIDENT_ID).toSet()
        var scheduled = 0
        runtime.state.objective?.targets.orEmpty().forEachIndexed { ordinal, target ->
            if (target.role.value != RUBBLE_ROLE) return@forEachIndexed
            if (target.status == ObjectiveTargetStatus.COMPLETED) {
                if (target.position in existing) journal.restoreNow(target.position)
            } else if (target.position in existing) {
                journal.ensureTemporary(target.position, RUBBLE)
            } else if (target.position.block()?.type == Material.AIR) {
                journal.prepare(runtime, INCIDENT_ID, ordinal, target.position, RUBBLE)
                scheduled++
            } else {
                state.log(
                    Level.WARNING,
                    "Mine cave-in reconcile blocked zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                        "target=${target.id} position=${target.position} reason=footprint_occupied " +
                        "material=${target.position.block()?.type}",
                )
            }
        }
        return scheduled
    }

    fun cleanup(runtime: MineRuntime): Int {
        placementFailures.remove(runtime.settings.id)
        return journal.restore(runtime, INCIDENT_ID)
    }

    private fun select(runtime: MineRuntime): Selection {
        val anchors = index.targets(runtime.settings.id, MineAnchorRole.NEST)
            .sortedWith(compareBy<WorksitePosition> { it.y }.thenBy { it.x }.thenBy { it.z })
            .rotate(runtime.state.sequence)
        val rejected = linkedMapOf<String, Int>()
        var checked = 0
        anchors.take(MAX_CHECKS).forEach { anchor ->
            checked++
            val issue = anchorIssue(runtime, anchor)
            if (issue != null) {
                rejected[issue] = rejected.getOrDefault(issue, 0) + 1
                return@forEach
            }
            val footprint = footprints(anchor).firstOrNull { rubbleIssue(runtime, it) == null }
            if (footprint != null) return Selection(footprint, anchors.size, checked, rejected)
            val footprintIssues = footprints(anchor).mapNotNull { rubbleIssue(runtime, it) }
            val reason = when {
                "journalled_block" in footprintIssues -> "journalled_block"
                "outside_region" in footprintIssues -> "outside_region"
                "chunk_unloaded" in footprintIssues -> "chunk_unloaded"
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

    private fun rubbleIssue(runtime: MineRuntime, footprint: List<WorksitePosition>): String? {
        if (footprint.any { !runtime.region.contains(it.location()) }) return "outside_region"
        if (footprint.any { !it.loaded() }) return "chunk_unloaded"
        if (footprint.any { recovery.containsPosition(it.key()) }) return "journalled_block"
        if (footprint.take(3).any { it.copy(y = it.y - 1).block()?.type?.isSolid != true }) return "missing_solid_floor"
        if (footprint.any { it.block()?.type != Material.AIR }) return "footprint_occupied"
        return null
    }

    private fun footprints(anchor: WorksitePosition): List<List<WorksitePosition>> = listOf(
        listOf(-1 to 0, 0 to 0, 1 to 0).map { (dx, dz) -> anchor.copy(x = anchor.x + dx, y = anchor.y + 1, z = anchor.z + dz) } +
            anchor.copy(y = anchor.y + 2),
        listOf(0 to -1, 0 to 0, 0 to 1).map { (dx, dz) -> anchor.copy(x = anchor.x + dx, y = anchor.y + 1, z = anchor.z + dz) } +
            anchor.copy(y = anchor.y + 2),
    )

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
        val footprint: List<WorksitePosition>?,
        val considered: Int,
        val checked: Int,
        val rejected: Map<String, Int>,
    )

    private companion object {
        const val INCIDENT_ID = "cave_in"
        const val RUBBLE_ROLE = "cave_in_rubble"
        const val RUBBLE_BLOCKS = 4
        const val MAX_CHECKS = 512
        const val PLAYER_CLEARANCE_SQUARED = 36.0
        const val LIFT_CLEARANCE_SQUARED = 100.0
        val RUBBLE = Material.COBBLESTONE
    }
}
