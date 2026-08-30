package ru.ruscrafting.farms.paper.mine.incident.construction

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
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import java.util.UUID
import kotlin.math.absoluteValue

/** Shared lease safety for support and track construction; target meaning stays role-specific. */
internal abstract class MineConstructionIncident(
    private val type: MineIncidentType,
    private val anchorRole: MineAnchorRole,
    private val itemRole: String,
    private val itemMaterial: Material,
    private val registry: MineRuntimeRegistry,
    private val index: MineBlockIndex,
    private val incidents: MineIncidentCoordinator,
    private val items: WorksiteServiceItems?,
    private val state: WorksiteStatePort,
) {
    fun start(runtime: MineRuntime, required: Int, now: Long): Boolean {
        val candidates = candidates(runtime)
        if (candidates.size < required * runtime.rules().targetMultiplier) return false
        return incidents.start(runtime, type, required, now, candidates)
    }

    fun pickupKit(runtime: MineRuntime, player: Player): Boolean {
        val incident = runtime.state.incident ?: return false
        if (!active(runtime) || incident.serviceLeases.values.any { it == player.uniqueId }) return false
        val itemId = (1..incident.required).map { "kit_$it" }.firstOrNull { it !in incident.serviceLeases } ?: return false
        val identity = identity(runtime, itemId)
        runtime.state = runtime.state.copy(
            incident = incident.copy(serviceLeases = incident.serviceLeases + (itemId to player.uniqueId)),
        )
        val issued = items?.issue(player, identity, itemMaterial, Component.text(displayName))
        if (issued == null) {
            runtime.state = runtime.state.copy(
                incident = runtime.state.incident?.copy(serviceLeases = runtime.state.incident!!.serviceLeases - itemId),
            )
            return false
        }
        state.persistAsync()
        return true
    }

    fun complete(runtime: MineRuntime, targetId: String, player: Player): Boolean {
        val incident = runtime.state.incident ?: return false
        val lease = incident.serviceLeases.entries.firstOrNull { it.value == player.uniqueId } ?: return false
        val identity = identity(runtime, lease.key)
        if (items?.consume(player, identity) != true) return false
        runtime.state = runtime.state.copy(incident = incident.copy(serviceLeases = incident.serviceLeases - lease.key))
        return incidents.completeTarget(runtime, targetId, player).accepted
    }

    fun onInteract(event: PlayerInteractEvent): Boolean {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return false
        val clicked = event.clickedBlock ?: return false
        val runtime = registry.at(clicked.location) ?: return false
        if (!active(runtime)) return false
        val position = WorksitePosition(clicked.world.name, clicked.x, clicked.y, clicked.z)
        val target = runtime.state.objective?.targets?.firstOrNull { it.position == position } ?: return false
        event.isCancelled = true
        val holding = runtime.state.incident?.serviceLeases?.values?.contains(event.player.uniqueId) == true
        if (holding) complete(runtime, target.id, event.player) else pickupKit(runtime, event.player)
        return true
    }

    fun reconcile(runtime: MineRuntime): Int {
        if (!active(runtime)) return 0
        val invalid = runtime.state.objective?.targets.orEmpty().filter { target ->
            !index.isLiveTarget(runtime.settings.id, target.position, anchorRole)
        }
        invalid.forEach { incidents.invalidate(runtime, it.id) }
        return invalid.size
    }

    fun isActive(identity: ServiceItemIdentity): Boolean {
        if (identity.activity != ActivityKind.MINE || identity.role.value != itemRole) return false
        val runtime = registry.byId(identity.zoneId) ?: return false
        val incident = runtime.state.incident ?: return false
        return active(runtime) && runtime.state.sequence == identity.sequence &&
            incident.objectiveNonce == identity.objectiveNonce && identity.itemId in incident.serviceLeases
    }

    fun release(playerId: UUID, identity: ServiceItemIdentity, reason: WorksitePlayerReleaseReason) {
        val runtime = registry.byId(identity.zoneId) ?: return
        val incident = runtime.state.incident ?: return
        if (incident.type != type || incident.serviceLeases[identity.itemId] != playerId) return
        runtime.state = runtime.state.copy(incident = incident.copy(serviceLeases = incident.serviceLeases - identity.itemId))
        state.persistAsync()
    }

    fun releasePlayer(playerId: UUID): Boolean {
        val runtime = registry.snapshot().firstOrNull { current ->
            current.state.incident?.takeIf { it.type == type }?.serviceLeases?.values?.contains(playerId) == true
        } ?: return false
        val lease = runtime.state.incident!!.serviceLeases.entries.first { it.value == playerId }
        Bukkit.getPlayer(playerId)?.let { items?.consume(it, identity(runtime, lease.key)) }
        release(playerId, identity(runtime, lease.key), WorksitePlayerReleaseReason.ZONE_EXIT)
        return true
    }

    val displayName: String get() = when (type) {
        MineIncidentType.CAVE_IN -> "Support kit"
        MineIncidentType.TRACK_DAMAGE -> "Track repair kit"
        else -> error("Unsupported construction incident: $type")
    }

    private fun active(runtime: MineRuntime): Boolean =
        runtime.state.phase == MinePhase.INCIDENT && runtime.state.incident?.type == type

    private fun identity(runtime: MineRuntime, itemId: String) = ServiceItemIdentity(
        ActivityKind.MINE,
        runtime.settings.id,
        runtime.state.sequence,
        requireNotNull(runtime.state.incident).objectiveNonce,
        ObjectiveTargetRole(itemRole),
        itemId,
    )

    private fun candidates(runtime: MineRuntime): List<ObjectiveTargetCandidate> =
        index.loadedTargets(runtime.settings.id, anchorRole).filter { index.isLiveTarget(runtime.settings.id, it, anchorRole) }
            .mapIndexed { order, position ->
                ObjectiveTargetCandidate(
                    "${itemRole}_${order + 1}_${token(position.x)}_${token(position.y)}_${token(position.z)}".take(48),
                    position,
                    ObjectiveTargetRole(itemRole),
                    order.toLong(),
                )
            }

    private fun token(value: Int): String = if (value < 0) "m${value.toLong().absoluteValue}" else value.toString()
}
