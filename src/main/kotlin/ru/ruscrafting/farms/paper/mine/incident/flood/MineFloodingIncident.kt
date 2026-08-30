package ru.ruscrafting.farms.paper.mine.incident.flood

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import ru.ruscrafting.farms.paper.mine.recovery.MineIncidentBlockJournal
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import java.util.UUID
import kotlin.math.absoluteValue

internal class MineFloodingIncident(
    private val registry: MineRuntimeRegistry,
    private val index: MineBlockIndex,
    private val incidents: MineIncidentCoordinator,
    private val journal: MineIncidentBlockJournal,
    private val items: WorksiteServiceItems?,
    private val state: WorksiteStatePort,
) {
    fun start(runtime: MineRuntime, required: Int, now: Long): Boolean {
        val candidates = candidates(runtime)
        if (candidates.size < required * runtime.rules().targetMultiplier) return false
        if (!incidents.start(runtime, MineIncidentType.FLOODING, required, now, candidates)) return false
        reconcile(runtime)
        return true
    }

    fun pickupPump(runtime: MineRuntime, player: Player): Boolean {
        val incident = runtime.state.incident ?: return false
        if (!active(runtime) || incident.serviceLeases.values.any { it == player.uniqueId }) return false
        val itemId = (1..incident.required).map { "pump_$it" }.firstOrNull { it !in incident.serviceLeases } ?: return false
        val identity = identity(runtime, itemId)
        runtime.state = runtime.state.copy(
            incident = incident.copy(serviceLeases = incident.serviceLeases + (itemId to player.uniqueId)),
        )
        val issued = items?.issue(player, identity, Material.BUCKET, Component.text("Mine pump"))
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
        runtime.state.objective?.target(targetId)?.position?.floodPosition()?.let(journal::restoreNow)
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
        val target = runtime.state.objective?.targets?.firstOrNull { it.position == position } ?: return false
        event.isCancelled = true
        if (runtime.state.incident?.serviceLeases?.values?.contains(event.player.uniqueId) == true) {
            drain(runtime, target.id, event.player)
        } else pickupPump(runtime, event.player)
        return true
    }

    fun reconcile(runtime: MineRuntime): Int {
        if (!active(runtime)) return 0
        val existing = journal.positions(runtime, INCIDENT_ID).toSet()
        var scheduled = 0
        runtime.state.objective?.targets.orEmpty().forEachIndexed { ordinal, target ->
            val position = target.position.floodPosition()
            if (position !in existing) {
                journal.prepare(runtime, INCIDENT_ID, ordinal, position, Material.WATER)
                scheduled++
            }
        }
        return scheduled
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

    private fun candidates(runtime: MineRuntime): List<ObjectiveTargetCandidate> =
        index.loadedTargets(runtime.settings.id, MineAnchorRole.PUMP)
            .filter { index.isLiveTarget(runtime.settings.id, it, MineAnchorRole.PUMP) && it.floodPosition().block()?.type == Material.AIR }
            .mapIndexed { order, position ->
                ObjectiveTargetCandidate(
                    "flood_${order + 1}_${token(position.x)}_${token(position.z)}",
                    position, ObjectiveTargetRole("flood_pump"), order.toLong(),
                )
            }

    private fun WorksitePosition.floodPosition() = copy(y = y + 1)
    private fun WorksitePosition.block() = Bukkit.getWorld(world)?.takeIf { it.isChunkLoaded(x shr 4, z shr 4) }?.getBlockAt(x, y, z)
    private fun token(value: Int): String = if (value < 0) "m${value.toLong().absoluteValue}" else value.toString()

    private companion object {
        const val INCIDENT_ID = "flooding"
        const val PUMP_ROLE = "mine_pump"
    }
}
