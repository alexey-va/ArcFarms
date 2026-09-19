package ru.ruscrafting.farms.paper.mine.incident.flood

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.block.data.Levelled
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator
import ru.ruscrafting.farms.paper.mine.incident.orderMineIncidentPositions
import ru.ruscrafting.farms.paper.mine.incident.isIncidentSurface
import ru.ruscrafting.farms.paper.mine.incident.blockType
import ru.ruscrafting.farms.paper.mine.incident.floodFootprint
import ru.ruscrafting.farms.paper.mine.incident.entity.hasMineObjectiveMarkerSpace
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import ru.ruscrafting.farms.paper.mine.recovery.MineIncidentBlockJournal
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import kotlin.math.absoluteValue

internal class MineFloodingIncident(
    private val registry: MineRuntimeRegistry,
    private val index: MineBlockIndex,
    private val incidents: MineIncidentCoordinator,
    private val journal: MineIncidentBlockJournal,
    private val state: WorksiteStatePort,
    private val access: ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort,
) {
    private val pendingScoops = mutableSetOf<String>()
    private val drainedPositions = mutableMapOf<String, MutableSet<WorksitePosition>>()
    private val pendingPreparations = ConcurrentHashMap.newKeySet<String>()

    fun start(runtime: MineRuntime, required: Int, now: Long): Boolean {
        val candidate = candidates(runtime, required).firstOrNull() ?: return false
        // Flooding is one local obstruction, like cave-in, rather than several remote water rooms.
        if (!incidents.start(runtime, MineIncidentType.FLOODING, 1, now, listOf(candidate))) return false
        clearDrained(runtime)
        reconcile(runtime)
        return true
    }

    /** The marker only locates the leak; scooping always targets a real water block. */
    fun onInteractEntity(runtime: MineRuntime, targetId: String, player: Player): Boolean = false

    fun onInteract(event: PlayerInteractEvent): Boolean {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return false
        val clicked = event.clickedBlock ?: return false
        val runtime = registry.at(clicked.location) ?: return false
        if (!active(runtime)) return false
        val position = WorksitePosition(clicked.world.name, clicked.x, clicked.y, clicked.z)
        val target = runtime.state.objective?.targets?.firstOrNull {
            it.status != ObjectiveTargetStatus.COMPLETED &&
                (it.position == position || position in runtime.floodFootprint(it.position))
        } ?: return false
        if (event.item?.type != Material.BUCKET || clicked.type != Material.WATER) return false
        event.isCancelled = true
        val drained = drainAt(runtime, target.id, position, event.player)
        if (!drained) return true
        return true
    }

    fun onBucketFill(event: org.bukkit.event.player.PlayerBucketFillEvent): Boolean {
        val block = event.block
        val runtime = registry.at(block.location) ?: return false
        if (!active(runtime) || event.bucket != Material.BUCKET) return false
        val position = WorksitePosition(block.world.name, block.x, block.y, block.z)
        val target = runtime.state.objective?.targets?.firstOrNull {
            it.status != ObjectiveTargetStatus.COMPLETED && position in runtime.floodFootprint(it.position)
        } ?: return false
        event.isCancelled = true
        if (event.player.world === block.world && event.player.location.distanceSquared(block.location) <= 36.0) {
            drainAt(runtime, target.id, position, event.player)
        }
        return true
    }

    fun reconcile(runtime: MineRuntime): Int {
        if (!active(runtime)) {
            clearDrained(runtime)
            return 0
        }
        val key = incidentKey(runtime)
        // Journal persistence completes before the recovery callback applies water.
        // Do not replay those records while that first mutation is still pending.
        if (key in pendingPreparations) return 0
        val existing = journal.positions(runtime, INCIDENT_ID).toSet()
        val drained = drainedPositions.getOrPut(incidentKey(runtime), ::linkedSetOf)
        val missing = mutableListOf<Pair<Int, WorksitePosition>>()
        runtime.state.objective?.targets.orEmpty().mapIndexed { targetIndex, target -> targetIndex to target }
            .filter { (_, target) -> target.status != ObjectiveTargetStatus.COMPLETED }
            .flatMap { (targetIndex, target) ->
                runtime.floodFootprint(target.position).mapIndexed { offsetIndex, position ->
                    targetIndex * FLOOD_JOURNAL_STRIDE + offsetIndex to position
                }
            }
            .distinctBy { (_, position) -> position }.forEach { (ordinal, position) ->
                if (position in existing) {
                    journal.ensureTemporary(position, Material.WATER)
                } else if (position.blockType() == Material.AIR && position !in drained && existing.isEmpty()) {
                    missing += ordinal to position
                } else if (position.blockType() == Material.AIR && existing.isNotEmpty()) {
                    // A missing cell after a durable water record is a completed scoop.
                    drained += position
                }
        }
        if (missing.isNotEmpty() && pendingPreparations.add(key)) journal.prepareAll(runtime, INCIDENT_ID, missing, Material.WATER).whenComplete { prepared, failure ->
            pendingPreparations.remove(key)
            if (incidentKey(runtime) != key) return@whenComplete
            if (failure != null || prepared != true) {
                state.log(
                    Level.WARNING,
                    "Mine flooding placement failed zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                        "blocks=${missing.size} reason=${failure?.javaClass?.simpleName ?: "journal_rejected"}",
                    failure,
                )
                if (active(runtime)) journal.runOnMain {
                    if (!active(runtime) || incidentKey(runtime) != key) return@runOnMain
                    journal.restore(runtime, INCIDENT_ID)
                    incidents.abort(runtime)
                }
            } else {
                shapeWater(runtime)
            }
        }
        if (missing.isEmpty()) shapeWater(runtime)
        return missing.size
    }

    fun waterPositions(runtime: MineRuntime): List<WorksitePosition> = journal.positions(runtime, INCIDENT_ID)

    fun protects(location: org.bukkit.Location): Boolean {
        val runtime = registry.at(location) ?: return false
        return WorksitePosition(location.world.name, location.blockX, location.blockY, location.blockZ) in journal.positions(runtime, INCIDENT_ID)
    }

    private fun drainAt(runtime: MineRuntime, targetId: String, clicked: WorksitePosition, player: Player): Boolean {
        if (access.isAdminEditing(player) || !access.hasAccess(player, runtime.settings.permission) ||
            player.gameMode == org.bukkit.GameMode.SPECTATOR) return false
        val objective = runtime.state.objective ?: return false
        val target = objective.target(targetId)?.takeIf { it.status != ObjectiveTargetStatus.COMPLETED } ?: return false
        if (clicked !in runtime.floodFootprint(target.position) || clicked !in journal.positions(runtime, INCIDENT_ID) ||
            clicked.blockType() != Material.WATER) return false
        val key = "${runtime.settings.id}:${runtime.state.sequence}:$targetId:${clicked.x}:${clicked.y}:${clicked.z}"
        if (!pendingScoops.add(key)) return false
        journal.restoreNow(clicked).whenComplete { restored, failure ->
            pendingScoops.remove(key)
            if (failure != null || restored != true || !active(runtime)) return@whenComplete
            drainedPositions.getOrPut(incidentKey(runtime), ::linkedSetOf).add(clicked)
            val remaining = runtime.floodFootprint(target.position).any { it in journal.positions(runtime, INCIDENT_ID) }
            if (!remaining) {
                val completed = incidents.completeTarget(runtime, targetId, player).accepted
                if (completed && !active(runtime)) journal.restore(runtime, INCIDENT_ID)
            }
            player.playSound(player.location, Sound.BLOCK_WATER_AMBIENT, 0.75f, 1.15f)
        }
        return true
    }

    private fun incidentKey(runtime: MineRuntime): String =
        "${runtime.settings.id}:${runtime.state.sequence}:$INCIDENT_ID:${runtime.state.incident?.objectiveNonce ?: -1L}"

    private fun clearDrained(runtime: MineRuntime) {
        val prefix = "${runtime.settings.id}:${runtime.state.sequence}:$INCIDENT_ID:"
        drainedPositions.keys.removeIf { it.startsWith(prefix) }
    }

    private fun active(runtime: MineRuntime): Boolean =
        runtime.state.phase == MinePhase.INCIDENT && runtime.state.incident?.type == MineIncidentType.FLOODING

    private fun candidates(runtime: MineRuntime, required: Int): List<ObjectiveTargetCandidate> =
        orderMineIncidentPositions(
            runtime,
            (index.targets(runtime.settings.id, MineAnchorRole.SUPPORT).map { it to MineAnchorRole.SUPPORT } +
                index.targets(runtime.settings.id, MineAnchorRole.NEST).map { it to MineAnchorRole.NEST })
                .distinctBy { it.first }
                .filter { (position, role) ->
                    index.isLiveTarget(runtime.settings.id, position, role, runtime.railMaterials) &&
                        position.blockType() != null &&
                        runtime.isIncidentSurface(position) && hasMineObjectiveMarkerSpace(position) &&
                        runtime.floodFootprint(position).let { footprint ->
                            footprint.size >= MIN_FLOOD_BLOCKS && footprint.all { water -> water.blockType() == Material.AIR }
                        }
                }.map { it.first },
            1,
            0xF100DL,
        )
            .mapIndexed { order, position ->
                ObjectiveTargetCandidate(
                    "flood_${order + 1}_${token(position.x)}_${token(position.z)}",
                    position, ObjectiveTargetRole("flood_pump"), order.toLong(),
                )
            }

    private fun token(value: Int): String = if (value < 0) "m${value.toLong().absoluteValue}" else value.toString()

    private fun shapeWater(runtime: MineRuntime) {
        runtime.state.objective?.targets.orEmpty().filter { it.status != ObjectiveTargetStatus.COMPLETED }.forEach { target ->
            runtime.floodFootprint(target.position).forEach { position ->
                val block = Bukkit.getWorld(position.world)?.takeIf { it.isChunkLoaded(position.x shr 4, position.z shr 4) }
                    ?.getBlockAt(position.x, position.y, position.z) ?: return@forEach
                if (block.type != Material.WATER) return@forEach
                val data = block.blockData as? Levelled ?: return@forEach
                data.level = 0
                block.setBlockData(data, false)
            }
        }
    }

    private companion object {
        const val INCIDENT_ID = "flooding"
        const val FLOOD_JOURNAL_STRIDE = 100
        const val MIN_FLOOD_BLOCKS = 20
    }
}
