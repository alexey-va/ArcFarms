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
import java.util.logging.Level
import kotlin.math.absoluteValue

internal class MineFloodingIncident(
    private val registry: MineRuntimeRegistry,
    private val index: MineBlockIndex,
    private val incidents: MineIncidentCoordinator,
    private val journal: MineIncidentBlockJournal,
    private val state: WorksiteStatePort,
) {
    fun start(runtime: MineRuntime, required: Int, now: Long): Boolean {
        val candidate = candidates(runtime, required).firstOrNull() ?: return false
        // Flooding is one local obstruction, like cave-in, rather than several remote water rooms.
        if (!incidents.start(runtime, MineIncidentType.FLOODING, 1, now, listOf(candidate))) return false
        reconcile(runtime)
        return true
    }

    fun drain(runtime: MineRuntime, targetId: String, player: Player): Boolean {
        val objective = runtime.state.objective ?: return false
        objective.target(targetId)?.position?.let { target ->
            runtime.floodFootprint(target).forEach(journal::restoreNow)
        }
        val completed = incidents.completeTarget(runtime, targetId, player).accepted
        if (completed) player.playSound(player.location, Sound.BLOCK_WATER_AMBIENT, 0.75f, 1.15f)
        if (completed && !active(runtime)) journal.restore(runtime, INCIDENT_ID)
        return completed
    }

    /** Entity-marker route; the parent router validates the PDC kind before calling this. */
    fun onInteractEntity(runtime: MineRuntime, targetId: String, player: Player): Boolean {
        if (!active(runtime)) return false
        val target = runtime.state.objective?.targets?.firstOrNull {
            it.id == targetId && it.status != ObjectiveTargetStatus.COMPLETED
        } ?: return false
        drain(runtime, target.id, player)
        return true
    }

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
        if (clicked.type != Material.WATER && clicked.type != Material.WATER_CAULDRON) return false
        event.isCancelled = true
        drain(runtime, target.id, event.player)
        return true
    }

    fun reconcile(runtime: MineRuntime): Int {
        if (!active(runtime)) return 0
        val existing = journal.positions(runtime, INCIDENT_ID).toSet()
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
                } else if (position.blockType() == Material.AIR) {
                    missing += ordinal to position
                }
        }
        if (missing.isNotEmpty()) journal.prepareAll(runtime, INCIDENT_ID, missing, Material.WATER).whenComplete { prepared, failure ->
            if (failure != null || prepared != true) {
                state.log(
                    Level.WARNING,
                    "Mine flooding placement failed zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                        "blocks=${missing.size} reason=${failure?.javaClass?.simpleName ?: "journal_rejected"}",
                    failure,
                )
                if (active(runtime)) {
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

    private fun active(runtime: MineRuntime): Boolean =
        runtime.state.phase == MinePhase.INCIDENT && runtime.state.incident?.type == MineIncidentType.FLOODING

    private fun candidates(runtime: MineRuntime, required: Int): List<ObjectiveTargetCandidate> =
        orderMineIncidentPositions(
            runtime,
            index.loadedTargets(runtime.settings.id, MineAnchorRole.NEST)
                .filter {
                    index.isLiveTarget(runtime.settings.id, it, MineAnchorRole.NEST, runtime.railMaterials) &&
                        runtime.isIncidentSurface(it) && hasMineObjectiveMarkerSpace(it) &&
                        runtime.floodFootprint(it).let { footprint ->
                            footprint.size >= MIN_FLOOD_BLOCKS && footprint.all { water -> water.blockType() == Material.AIR }
                        }
                },
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
                val distance = kotlin.math.abs(position.x - target.position.x) + kotlin.math.abs(position.z - target.position.z)
                data.level = distance.coerceIn(0, minOf(7, data.maximumLevel))
                block.setBlockData(data, false)
            }
        }
    }

    private companion object {
        const val INCIDENT_ID = "flooding"
        const val FLOOD_JOURNAL_STRIDE = 100
        const val MIN_FLOOD_BLOCKS = 12
    }
}
