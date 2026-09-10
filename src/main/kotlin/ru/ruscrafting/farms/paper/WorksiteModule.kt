package ru.ruscrafting.farms.paper

import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.entity.EntityDeathEvent
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.paper.worksite.RuntimeComponent
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksiteParticipantOwner
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItemOwner
import java.util.UUID

data class ActivityStatus(
    val kind: ActivityKind,
    val id: String,
    val phasePath: String,
    val progress: String,
)

/** Common orchestration boundary for independently-owned worksite lifecycles. */
internal interface WorksiteModule<S> : RuntimeComponent {
    val kind: ActivityKind
    val zoneCount: Int

    fun states(): Map<String, S>
    fun statuses(): List<ActivityStatus>
    fun tick(now: Long)
    fun isAvailable(): Boolean = zoneCount > 0
    fun canAccess(player: Player): Boolean
}

/** Optional event capabilities keep the central listener independent of concrete worksites. */
internal interface WorksiteBlockBreakHandler {
    fun onBreakHigh(event: BlockBreakEvent): Boolean
    fun onBreakMonitor(event: BlockBreakEvent): Boolean = false
}

internal interface WorksiteBlockInteractHandler {
    fun onInteract(event: PlayerInteractEvent, clicked: Block, player: Player): Boolean
}

/** Handles player interactions that may not target a block, such as service-item shots. */
internal interface WorksitePlayerInteractHandler {
    fun onInteract(event: PlayerInteractEvent, player: Player): Boolean
}

internal interface WorksiteMoveHandler {
    fun onMove(from: Location, to: Location, player: Player): Boolean
}

/** Lets a live off-site activity retain its participant across plugin-owned teleports. */
internal interface WorksiteTemporaryBlockOwner {
    fun protectsTemporaryBlock(location: Location): Boolean
}

internal interface WorksiteTeleportRetention {
    fun retainOnTeleport(player: Player, destination: org.bukkit.Location): Boolean
}

/** Optional durable participant recovery after the shared stale-release pass. */
internal interface WorksiteParticipantRecoveryOwner {
    fun recoverPlayer(player: Player)
}

internal interface WorksiteEntityInteractHandler {
    fun onInteractEntity(event: PlayerInteractEntityEvent): Boolean
}

internal interface WorksiteEntityDeathHandler {
    fun onEntityDeath(event: EntityDeathEvent): Boolean
}

/** One-tick visual work only; gameplay state still advances through [WorksiteModule.tick]. */
internal interface WorksiteFastVisualHandler {
    fun updateVisuals()
}

internal interface WorksiteGuidanceHandler {
    fun updateGuidance(expectedBars: MutableSet<ActivityBarKey>)
    fun emitGuidance()
}

internal data class ActivityBarKey(val playerId: UUID, val runtimeKey: String)

/** Aggregates independent worksite types without knowing their concrete state machines. */
internal class WorksiteModuleRegistry(
    modules: Collection<WorksiteModule<*>>,
) : WorksiteServiceItemOwner, WorksiteParticipantOwner {
    private val modulesByKind = modules.associateBy(WorksiteModule<*>::kind).also { indexed ->
        require(indexed.size == modules.size) { "Only one worksite module may own each activity kind" }
    }
    private val modulesInOrder = ActivityKind.entries.mapNotNull(modulesByKind::get)

    fun statuses(): List<ActivityStatus> = modulesInOrder.flatMap(WorksiteModule<*>::statuses)

    fun tick(now: Long) = modulesInOrder.forEach { it.tick(now) }

    fun updateGuidance(): MutableSet<ActivityBarKey> = linkedSetOf<ActivityBarKey>().also { expectedBars ->
        modulesInOrder.filterIsInstance<WorksiteGuidanceHandler>().forEach { it.updateGuidance(expectedBars) }
    }

    fun emitGuidance() = modulesInOrder.filterIsInstance<WorksiteGuidanceHandler>().forEach { it.emitGuidance() }

    fun activateLoadedState() = modulesInOrder.forEach(WorksiteModule<*>::activateLoadedState)

    fun reconcileChunk(chunk: Chunk) = modulesInOrder.forEach { it.reconcileChunk(chunk) }

    fun beforeReload(reason: String) = modulesInOrder.forEach { it.beforeReload(reason) }

    fun cleanup(reason: String) = modulesInOrder.forEach { it.cleanup(reason) }

    fun isAvailable(kind: ActivityKind): Boolean = modulesByKind[kind]?.isAvailable() == true

    fun canAccess(player: Player, kind: ActivityKind): Boolean = modulesByKind[kind]?.canAccess(player) == true

    fun onBreakHigh(kind: ActivityKind, event: BlockBreakEvent): Boolean =
        (modulesByKind[kind] as? WorksiteBlockBreakHandler)?.onBreakHigh(event) == true

    fun onBreakHigh(event: BlockBreakEvent): Boolean =
        BREAK_ROUTING_ORDER.any { kind -> onBreakHigh(kind, event) }

    fun onBreakMonitor(event: BlockBreakEvent) {
        modulesInOrder.filterIsInstance<WorksiteBlockBreakHandler>().forEach { it.onBreakMonitor(event) }
    }

    fun onInteract(event: PlayerInteractEvent, clicked: Block, player: Player): Boolean =
        modulesInOrder.filterIsInstance<WorksiteBlockInteractHandler>().any { it.onInteract(event, clicked, player) }

    fun onPlayerInteract(event: PlayerInteractEvent, player: Player): Boolean =
        modulesInOrder.filterIsInstance<WorksitePlayerInteractHandler>().any { it.onInteract(event, player) }

    fun onMove(from: Location, to: Location, player: Player): Boolean {
        var handled = false
        modulesInOrder.filterIsInstance<WorksiteMoveHandler>().forEach { module ->
            handled = module.onMove(from, to, player) || handled
        }
        return handled
    }

    fun protectsTemporaryBlock(location: Location): Boolean =
        modulesInOrder.filterIsInstance<WorksiteTemporaryBlockOwner>().any { it.protectsTemporaryBlock(location) }

    fun retainOnTeleport(player: Player, destination: org.bukkit.Location): Boolean =
        modulesInOrder.filterIsInstance<WorksiteTeleportRetention>().any { it.retainOnTeleport(player, destination) }

    fun recoverPlayer(player: Player) =
        modulesInOrder.filterIsInstance<WorksiteParticipantRecoveryOwner>().forEach { it.recoverPlayer(player) }

    fun onInteractEntity(event: PlayerInteractEntityEvent): Boolean =
        modulesInOrder.filterIsInstance<WorksiteEntityInteractHandler>().any { it.onInteractEntity(event) }

    fun onEntityDeath(event: EntityDeathEvent): Boolean =
        modulesInOrder.filterIsInstance<WorksiteEntityDeathHandler>().any { it.onEntityDeath(event) }

    fun updateVisuals() = modulesInOrder.filterIsInstance<WorksiteFastVisualHandler>().forEach { it.updateVisuals() }

    override fun isActive(identity: ServiceItemIdentity): Boolean =
        (modulesByKind[identity.activity] as? WorksiteServiceItemOwner)?.isActive(identity) == true

    override fun release(
        playerId: UUID,
        identity: ServiceItemIdentity,
        reason: WorksitePlayerReleaseReason,
    ) {
        (modulesByKind[identity.activity] as? WorksiteServiceItemOwner)?.release(playerId, identity, reason)
    }

    override fun releasePlayer(player: Player, reason: WorksitePlayerReleaseReason) {
        var firstFailure: Throwable? = null
        modulesInOrder.filterIsInstance<WorksiteParticipantOwner>().forEach { owner ->
            runCatching { owner.releasePlayer(player, reason) }.exceptionOrNull()?.let { failure ->
                if (firstFailure == null) firstFailure = failure else requireNotNull(firstFailure).addSuppressed(failure)
            }
        }
        firstFailure?.let { throw it }
    }

    private companion object {
        val BREAK_ROUTING_ORDER = listOf(ActivityKind.MINE, ActivityKind.FARM, ActivityKind.LUMBER)
    }
}
