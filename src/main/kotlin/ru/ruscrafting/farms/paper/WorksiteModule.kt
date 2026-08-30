package ru.ruscrafting.farms.paper

import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.paper.worksite.RuntimeComponent
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteNetworkPort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatsPort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.UUID

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

internal interface WorksiteMoveHandler {
    fun onMove(from: Location, to: Location, player: Player): Boolean
}

internal data class ActivityBarKey(val playerId: UUID, val runtimeKey: String)

/** Compatibility composite; new owners depend on the smallest port they use. */
internal interface WorksiteRuntimePort :
    WorksiteAccessPort,
    WorksiteAudiencePort,
    WorksiteStatePort,
    WorksiteTaskPort,
    WorksiteStatsPort,
    WorksiteNetworkPort

/** Aggregates independent worksite types without knowing their concrete state machines. */
internal class WorksiteModuleRegistry(
    modules: Collection<WorksiteModule<*>>,
) {
    private val modulesByKind = modules.associateBy(WorksiteModule<*>::kind).also { indexed ->
        require(indexed.size == modules.size) { "Only one worksite module may own each activity kind" }
    }
    private val modulesInOrder = ActivityKind.entries.mapNotNull(modulesByKind::get)

    fun statuses(): List<ActivityStatus> = modulesInOrder.flatMap(WorksiteModule<*>::statuses)

    fun tick(now: Long) = modulesInOrder.forEach { it.tick(now) }

    fun activateLoadedState() = modulesInOrder.forEach(WorksiteModule<*>::activateLoadedState)

    fun reconcileChunk(chunk: Chunk) = modulesInOrder.forEach { it.reconcileChunk(chunk) }

    fun beforeReload(reason: String) = modulesInOrder.forEach { it.beforeReload(reason) }

    fun cleanup(reason: String) = modulesInOrder.forEach { it.cleanup(reason) }

    fun isAvailable(kind: ActivityKind): Boolean = modulesByKind[kind]?.isAvailable() == true

    fun canAccess(player: Player, kind: ActivityKind): Boolean = modulesByKind[kind]?.canAccess(player) == true

    fun onBreakHigh(kind: ActivityKind, event: BlockBreakEvent): Boolean =
        (modulesByKind[kind] as? WorksiteBlockBreakHandler)?.onBreakHigh(event) == true

    fun onBreakMonitor(event: BlockBreakEvent) {
        modulesInOrder.filterIsInstance<WorksiteBlockBreakHandler>().forEach { it.onBreakMonitor(event) }
    }

    fun onInteract(event: PlayerInteractEvent, clicked: Block, player: Player): Boolean =
        modulesInOrder.filterIsInstance<WorksiteBlockInteractHandler>().any { it.onInteract(event, clicked, player) }

    fun onMove(from: Location, to: Location, player: Player): Boolean =
        modulesInOrder.filterIsInstance<WorksiteMoveHandler>().any { it.onMove(from, to, player) }
}
