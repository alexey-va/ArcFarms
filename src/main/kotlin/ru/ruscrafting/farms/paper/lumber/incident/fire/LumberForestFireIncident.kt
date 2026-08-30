package ru.ruscrafting.farms.paper.lumber.incident.fire

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
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
import ru.ruscrafting.farms.paper.lumber.recovery.LumberBlockRecoveryController
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/**
 * Bounded recoverable fire simulation. LIGHT is intentional: real FIRE continues to spread with
 * zero participants, while the flame itself is rendered on the shared guidance cadence.
 */
internal class LumberForestFireIncident(
    private val registry: LumberRuntimeRegistry,
    private val incidents: LumberIncidentCoordinator,
    private val recovery: LumberBlockRecoveryController,
    private val items: WorksiteServiceItems?,
    private val port: WorksiteRuntimePort,
) {
    fun start(runtime: LumberRuntime, required: Int, now: Long): Boolean {
        val candidates = candidates(runtime, required * CANDIDATE_MULTIPLIER)
        if (candidates.size < required * 2) return false
        if (!incidents.start(runtime, LumberIncidentType.FOREST_FIRE, required, now, candidates)) return false
        candidates.forEach { candidate ->
            val block = candidate.position.block(runtime) ?: return@forEach
            recovery.prepareTemporary(runtime, block, Material.LIGHT).whenComplete { prepared, failure ->
                if (failure != null) port.log(Level.WARNING, "Could not prepare lumber fire ${candidate.id}", failure)
                if (prepared != true) {
                    val token = port.lifecycleToken()
                    port.runSync(token) { incidents.invalidate(runtime, candidate.id) }
                }
            }
        }
        return true
    }

    fun pickupWater(runtime: LumberRuntime, player: Player): Boolean {
        val incident = runtime.state.incident ?: return false
        if (!active(runtime) || incident.serviceLeases.values.any { it == player.uniqueId }) return false
        val itemId = (1..incident.required).map { "water_$it" }.firstOrNull { it !in incident.serviceLeases } ?: return false
        val identity = identity(runtime, itemId)
        runtime.state = runtime.state.copy(
            incident = incident.copy(serviceLeases = incident.serviceLeases + (itemId to player.uniqueId)),
        )
        val issued = items?.issue(
            player,
            identity,
            Material.WATER_BUCKET,
            Component.translatable("item.minecraft.water_bucket"),
        )
        if (issued == null) {
            runtime.state = runtime.state.copy(
                incident = runtime.state.incident?.copy(serviceLeases = runtime.state.incident!!.serviceLeases - itemId),
            )
            return false
        }
        port.persistAsync()
        return true
    }

    fun extinguish(runtime: LumberRuntime, targetId: String, player: Player): CompletableFuture<Boolean> {
        val incident = runtime.state.incident ?: return CompletableFuture.completedFuture(false)
        if (incident.serviceLeases.values.none { it == player.uniqueId }) return CompletableFuture.completedFuture(false)
        val objective = runtime.state.objective ?: return CompletableFuture.completedFuture(false)
        val target = objective.target(targetId) ?: return CompletableFuture.completedFuture(false)
        val block = target.position.block(runtime) ?: return CompletableFuture.completedFuture(false)
        val allPositions: List<WorksitePosition> =
            objective.targets.map { it.position } + objective.reserve.map { it.position }
        val leases = incident.serviceLeases
        val leasedIdentities = leases.mapValues { (itemId, _) -> identity(runtime, itemId) }
        val finalTarget = incident.progress + 1 >= incident.required
        val result = CompletableFuture<Boolean>()
        recovery.restoreNow(block).whenComplete { restored, failure ->
            if (failure != null || restored != true) {
                if (failure != null) result.completeExceptionally(failure) else result.complete(false)
                return@whenComplete
            }
            val token = port.lifecycleToken()
            if (!port.runSync(token) {
                    val completed = incidents.completeTarget(runtime, targetId, player).accepted
                    if (completed && finalTarget) {
                        allPositions.forEach { position -> position.block(runtime)?.let(recovery::restoreNow) }
                        leases.forEach { (itemId, playerId) ->
                            Bukkit.getPlayer(playerId)?.let { holder -> items?.consume(holder, leasedIdentities.getValue(itemId)) }
                        }
                    }
                    result.complete(completed)
                }
            ) result.complete(false)
        }
        return result
    }

    fun onInteract(event: PlayerInteractEvent, clicked: Block, player: Player): Boolean {
        val runtime = registry.at(clicked.location) ?: return false
        if (!active(runtime)) return false
        val position = WorksitePosition(clicked.world.name, clicked.x, clicked.y + 1, clicked.z)
        val target = runtime.state.objective?.targets?.firstOrNull { it.position == position } ?: return false
        event.isCancelled = true
        if (runtime.state.incident?.serviceLeases?.values?.contains(player.uniqueId) != true) pickupWater(runtime, player)
        extinguish(runtime, target.id, player)
        return true
    }

    /** Vanilla cannot advance this incident; no participants means literally no mutation. */
    fun tick(runtime: LumberRuntime, onlineParticipants: Int, now: Long) {
        if (!active(runtime) || onlineParticipants <= 0) return
        // Future bounded spread may consume this cadence; targets change only through player action today.
    }

    fun isActive(identity: ServiceItemIdentity): Boolean {
        if (identity.activity != ActivityKind.LUMBER || identity.role.value != WATER_ROLE) return false
        val runtime = registry.byId(identity.zoneId) ?: return false
        val incident = runtime.state.incident ?: return false
        return active(runtime) && runtime.state.sequence == identity.sequence &&
            incident.objectiveNonce == identity.objectiveNonce && identity.itemId in incident.serviceLeases
    }

    fun release(playerId: UUID, identity: ServiceItemIdentity, reason: WorksitePlayerReleaseReason) {
        val runtime = registry.byId(identity.zoneId) ?: return
        val incident = runtime.state.incident ?: return
        if (incident.type != LumberIncidentType.FOREST_FIRE || incident.serviceLeases[identity.itemId] != playerId) return
        runtime.state = runtime.state.copy(incident = incident.copy(serviceLeases = incident.serviceLeases - identity.itemId))
        port.persistAsync()
    }

    private fun active(runtime: LumberRuntime): Boolean =
        runtime.state.phase == LumberPhase.INCIDENT && runtime.state.incident?.type == LumberIncidentType.FOREST_FIRE

    private fun identity(runtime: LumberRuntime, itemId: String) = ServiceItemIdentity(
        ActivityKind.LUMBER,
        runtime.settings.id,
        runtime.state.sequence,
        requireNotNull(runtime.state.incident).objectiveNonce,
        ObjectiveTargetRole(WATER_ROLE),
        itemId,
    )

    private fun candidates(runtime: LumberRuntime, limit: Int): List<ObjectiveTargetCandidate> {
        val bounds = runtime.region.bounds
        val positions = mutableListOf<WorksitePosition>()
        var inspected = 0
        scan@ for (y in bounds.minY..bounds.maxY) for (x in bounds.minX..bounds.maxX) for (z in bounds.minZ..bounds.maxZ) {
            if (++inspected > MAX_SCAN_BLOCKS) break@scan
            if (runtime.region.world.isChunkLoaded(x shr 4, z shr 4)) {
                val block = runtime.region.world.getBlockAt(x, y, z)
                if (block.isPassable && block.getRelative(0, 1, 0).isPassable && block.getRelative(0, -1, 0).type.isOccluding) {
                    positions += WorksitePosition(runtime.region.world.name, x, y, z)
                    if (positions.size >= limit) break@scan
                }
            }
        }
        return positions.mapIndexed { index, position ->
            ObjectiveTargetCandidate("fire_${index + 1}", position, ObjectiveTargetRole("fire"), index.toLong())
        }
    }

    private fun WorksitePosition.block(runtime: LumberRuntime): Block? = runtime.region.world
        .takeIf { it.name == world && it.isChunkLoaded(x shr 4, z shr 4) }
        ?.getBlockAt(x, y, z)

    private companion object {
        const val WATER_ROLE = "fire_water"
        const val CANDIDATE_MULTIPLIER = 4
        const val MAX_SCAN_BLOCKS = 250_000
    }
}
