package ru.ruscrafting.farms.paper.mine.incident.flood

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind
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
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import ru.ruscrafting.farms.paper.mine.recovery.MineIncidentBlockJournal
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import java.util.UUID
import java.util.logging.Level
import kotlin.math.absoluteValue

internal class MineFloodingIncident(
    private val registry: MineRuntimeRegistry,
    private val index: MineBlockIndex,
    private val incidents: MineIncidentCoordinator,
    private val journal: MineIncidentBlockJournal,
    private val items: WorksiteServiceItems?,
    private val state: WorksiteStatePort,
    private val locale: ArcFarmsLocale?,
) {
    fun start(runtime: MineRuntime, required: Int, now: Long): Boolean {
        val candidates = candidates(runtime, required)
        if (candidates.size < required * runtime.rules().targetMultiplier) return false
        if (!incidents.start(runtime, MineIncidentType.FLOODING, required, now, candidates)) return false
        reconcile(runtime)
        return true
    }

    /** Farm-style service-tool supply: keep the temporary pump in the selected slot when possible. */
    fun ensurePump(runtime: MineRuntime, player: Player): Boolean {
        val incident = runtime.state.incident ?: return false
        if (!active(runtime)) return false
        if (incident.serviceLeases.values.any { it == player.uniqueId }) return true
        val itemId = (1..incident.required).map { "pump_$it" }.firstOrNull { it !in incident.serviceLeases } ?: return false
        val identity = identity(runtime, itemId)
        runtime.state = runtime.state.copy(
            incident = incident.copy(serviceLeases = incident.serviceLeases + (itemId to player.uniqueId)),
        )
        val itemName = locale?.render(MessageKey.MINE_SERVICE_PUMP, player) ?: Component.text(MessageKey.MINE_SERVICE_PUMP.path)
        val issued = items?.issueTool(player, identity, Material.BUCKET, itemName)
        if (issued == null) {
            runtime.state = runtime.state.copy(
                incident = runtime.state.incident?.copy(serviceLeases = runtime.state.incident!!.serviceLeases - itemId),
            )
            return false
        }
        state.persistAsync()
        return true
    }

    fun drain(runtime: MineRuntime, targetId: String, player: Player): Boolean {
        val incident = runtime.state.incident ?: return false
        val lease = incident.serviceLeases.entries.firstOrNull { it.value == player.uniqueId } ?: return false
        if (items?.consume(player, identity(runtime, lease.key)) != true) return false
        runtime.state = runtime.state.copy(incident = incident.copy(serviceLeases = incident.serviceLeases - lease.key))
        runtime.state.objective?.target(targetId)?.position?.floodFootprint()?.forEach(journal::restoreNow)
        val completed = incidents.completeTarget(runtime, targetId, player).accepted
        if (completed && !active(runtime)) journal.restore(runtime, INCIDENT_ID)
        return completed
    }

    fun onInteract(event: PlayerInteractEvent): Boolean {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return false
        val clicked = event.clickedBlock ?: return false
        val runtime = registry.at(clicked.location) ?: return false
        if (!active(runtime)) return false
        val position = WorksitePosition(clicked.world.name, clicked.x, clicked.y, clicked.z)
        val target = runtime.state.objective?.targets?.firstOrNull {
            it.position == position || position in it.position.floodFootprint()
        } ?: return false
        event.isCancelled = true
        if (runtime.state.incident?.serviceLeases?.values?.contains(event.player.uniqueId) == true) {
            drain(runtime, target.id, event.player)
        } else ensurePump(runtime, event.player)
        return true
    }

    fun reconcile(runtime: MineRuntime): Int {
        if (!active(runtime)) return 0
        val existing = journal.positions(runtime, INCIDENT_ID).toSet()
        val missing = mutableListOf<Pair<Int, WorksitePosition>>()
        runtime.state.objective?.targets.orEmpty().mapIndexed { targetIndex, target -> targetIndex to target }
            .filter { (_, target) -> target.status != ObjectiveTargetStatus.COMPLETED }
            .flatMap { (targetIndex, target) ->
                target.position.floodFootprint().mapIndexed { offsetIndex, position ->
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
            }
        }
        return missing.size
    }

    fun waterPositions(runtime: MineRuntime): List<WorksitePosition> = journal.positions(runtime, INCIDENT_ID)

    fun isActive(identity: ServiceItemIdentity): Boolean {
        if (identity.activity != ActivityKind.MINE || identity.role.value != PUMP_ROLE) return false
        val runtime = registry.byId(identity.zoneId) ?: return false
        val incident = runtime.state.incident ?: return false
        return active(runtime) && runtime.state.sequence == identity.sequence &&
            incident.objectiveNonce == identity.objectiveNonce && identity.itemId in incident.serviceLeases
    }

    fun release(playerId: UUID, identity: ServiceItemIdentity, reason: WorksitePlayerReleaseReason) {
        val runtime = registry.byId(identity.zoneId) ?: return
        val incident = runtime.state.incident ?: return
        if (incident.type != MineIncidentType.FLOODING || incident.serviceLeases[identity.itemId] != playerId) return
        runtime.state = runtime.state.copy(incident = incident.copy(serviceLeases = incident.serviceLeases - identity.itemId))
        state.persistAsync()
    }

    fun releasePlayer(playerId: UUID): Boolean {
        val runtime = registry.snapshot().firstOrNull {
            it.state.incident?.takeIf { incident -> incident.type == MineIncidentType.FLOODING }
                ?.serviceLeases?.values?.contains(playerId) == true
        } ?: return false
        val lease = runtime.state.incident!!.serviceLeases.entries.first { it.value == playerId }
        val identity = identity(runtime, lease.key)
        Bukkit.getPlayer(playerId)?.let { items?.consume(it, identity) }
        release(playerId, identity, WorksitePlayerReleaseReason.ZONE_EXIT)
        return true
    }

    private fun active(runtime: MineRuntime): Boolean =
        runtime.state.phase == MinePhase.INCIDENT && runtime.state.incident?.type == MineIncidentType.FLOODING

    private fun identity(runtime: MineRuntime, itemId: String) = ServiceItemIdentity(
        ActivityKind.MINE, runtime.settings.id, runtime.state.sequence,
        requireNotNull(runtime.state.incident).objectiveNonce, ObjectiveTargetRole(PUMP_ROLE), itemId,
    )

    private fun candidates(runtime: MineRuntime, required: Int): List<ObjectiveTargetCandidate> =
        orderMineIncidentPositions(
            runtime,
            index.loadedTargets(runtime.settings.id, MineAnchorRole.NEST)
                .filter {
                    index.isLiveTarget(runtime.settings.id, it, MineAnchorRole.NEST, runtime.railMaterials) &&
                        runtime.isIncidentSurface(it) && it.floodFootprint().all { water ->
                            water.blockType() == Material.AIR && runtime.isIncidentSurface(water.copy(y = water.y - 1))
                        }
                },
            required * runtime.rules().targetMultiplier * 2,
            0xF100DL,
        )
            .mapIndexed { order, position ->
                ObjectiveTargetCandidate(
                    "flood_${order + 1}_${token(position.x)}_${token(position.z)}",
                    position, ObjectiveTargetRole("flood_pump"), order.toLong(),
                )
            }

    private fun token(value: Int): String = if (value < 0) "m${value.toLong().absoluteValue}" else value.toString()

    private companion object {
        const val INCIDENT_ID = "flooding"
        const val PUMP_ROLE = "mine_pump"
        const val FLOOD_JOURNAL_STRIDE = 10
    }
}
