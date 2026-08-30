package ru.ruscrafting.farms.paper.lumber.incident.conveyor

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.block.Block
import org.bukkit.event.player.PlayerInteractEvent
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.LumberIncidentType
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.lumber.LumberRuntime
import ru.ruscrafting.farms.paper.lumber.LumberRuntimeRegistry
import ru.ruscrafting.farms.paper.lumber.incident.LumberIncidentCoordinator
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import java.util.UUID

internal class LumberConveyorIncident(
    private val registry: LumberRuntimeRegistry,
    private val incidents: LumberIncidentCoordinator,
    private val items: WorksiteServiceItems?,
    private val port: WorksiteRuntimePort,
) {
    fun start(runtime: LumberRuntime, required: Int, now: Long): Boolean = incidents.start(
        runtime,
        LumberIncidentType.CONVEYOR_BREAKDOWN,
        required,
        now,
        candidates(runtime, required * 4),
    )

    fun pickupKit(runtime: LumberRuntime, player: Player): Boolean {
        val incident = runtime.state.incident ?: return false
        if (runtime.state.phase != LumberPhase.INCIDENT || incident.type != LumberIncidentType.CONVEYOR_BREAKDOWN) return false
        if (incident.serviceLeases.values.any { it == player.uniqueId }) return false
        val itemId = (1..incident.required).map { "repair_$it" }.firstOrNull { it !in incident.serviceLeases } ?: return false
        val identity = identity(runtime, itemId)
        runtime.state = runtime.state.copy(incident = incident.copy(serviceLeases = incident.serviceLeases + (itemId to player.uniqueId)))
        val issued = items?.issue(player, identity, Material.IRON_NUGGET, Component.translatable("item.minecraft.iron_nugget"))
        if (issued == null) {
            runtime.state = runtime.state.copy(
                incident = runtime.state.incident?.copy(serviceLeases = runtime.state.incident!!.serviceLeases - itemId),
            )
            return false
        }
        port.persistAsync()
        return true
    }

    fun deliver(runtime: LumberRuntime, targetId: String, player: Player): Boolean {
        val incident = runtime.state.incident ?: return false
        val lease = incident.serviceLeases.entries.firstOrNull { it.value == player.uniqueId } ?: return false
        val identity = identity(runtime, lease.key)
        if (items?.consume(player, identity) != true) return false
        runtime.state = runtime.state.copy(incident = incident.copy(serviceLeases = incident.serviceLeases - lease.key))
        return incidents.completeTarget(runtime, targetId, player).accepted
    }

    fun availableKits(runtime: LumberRuntime): Int = runtime.state.incident?.let { incident ->
        if (incident.type == LumberIncidentType.CONVEYOR_BREAKDOWN) incident.required - incident.serviceLeases.size else 0
    } ?: 0

    fun onInteract(event: PlayerInteractEvent, clicked: Block, player: Player): Boolean {
        val runtime = registry.snapshot().firstOrNull { it.station.contains(clicked.location) } ?: return false
        if (runtime.state.phase != LumberPhase.INCIDENT ||
            runtime.state.incident?.type != LumberIncidentType.CONVEYOR_BREAKDOWN
        ) return false
        val position = WorksitePosition(clicked.world.name, clicked.x, clicked.y + 1, clicked.z)
        val target = runtime.state.objective?.targets?.firstOrNull { it.position == position } ?: return false
        event.isCancelled = true
        if (runtime.state.incident?.serviceLeases?.values?.contains(player.uniqueId) != true && !pickupKit(runtime, player)) {
            return true
        }
        deliver(runtime, target.id, player)
        return true
    }

    fun isActive(identity: ServiceItemIdentity): Boolean {
        if (identity.activity != ActivityKind.LUMBER || identity.role.value != "repair_kit") return false
        val runtime = registry.byId(identity.zoneId) ?: return false
        val incident = runtime.state.incident ?: return false
        return runtime.state.phase == LumberPhase.INCIDENT && incident.type == LumberIncidentType.CONVEYOR_BREAKDOWN &&
            runtime.state.sequence == identity.sequence && incident.objectiveNonce == identity.objectiveNonce &&
            identity.itemId in incident.serviceLeases
    }

    fun release(playerId: UUID, identity: ServiceItemIdentity, reason: WorksitePlayerReleaseReason) {
        val runtime = registry.byId(identity.zoneId) ?: return
        val incident = runtime.state.incident ?: return
        if (incident.type != LumberIncidentType.CONVEYOR_BREAKDOWN || incident.serviceLeases[identity.itemId] != playerId) return
        runtime.state = runtime.state.copy(incident = incident.copy(serviceLeases = incident.serviceLeases - identity.itemId))
        port.persistAsync()
    }

    private fun identity(runtime: LumberRuntime, itemId: String): ServiceItemIdentity = ServiceItemIdentity(
        ActivityKind.LUMBER,
        runtime.settings.id,
        runtime.state.sequence,
        requireNotNull(runtime.state.incident).objectiveNonce,
        ObjectiveTargetRole("repair_kit"),
        itemId,
    )

    private fun candidates(runtime: LumberRuntime, limit: Int): List<ObjectiveTargetCandidate> {
        val bounds = runtime.station.bounds
        val positions = mutableListOf<WorksitePosition>()
        scan@ for (y in bounds.minY..bounds.maxY) for (x in bounds.minX..bounds.maxX) for (z in bounds.minZ..bounds.maxZ) {
            val block = runtime.station.world.getBlockAt(x, y, z)
            if (block.isPassable && block.getRelative(0, 1, 0).isPassable && block.getRelative(0, -1, 0).type.isOccluding) {
                positions += WorksitePosition(runtime.station.world.name, x, y, z)
                if (positions.size >= limit) break@scan
            }
        }
        return positions.mapIndexed { index, position ->
            ObjectiveTargetCandidate("repair_anchor_${index + 1}", position, ObjectiveTargetRole("repair_anchor"), index.toLong())
        }
    }
}
