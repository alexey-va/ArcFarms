package ru.ruscrafting.farms.paper

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.network.NetworkSignal
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/** Common orchestration boundary for independently-owned worksite lifecycles. */
internal interface WorksiteModule<S> {
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

/**
 * Narrow platform port shared by worksite modules. It keeps Paper presentation,
 * network relays, statistics and persistence outside each domain state machine.
 */
internal interface WorksiteRuntimePort {
    fun isOperational(): Boolean
    fun hasAccess(player: Player, permission: String): Boolean
    fun allowInteraction(key: String, cooldownMillis: Long): Boolean
    fun resetInteraction(key: String)
    fun resetInteractionsContaining(fragment: String)
    fun players(region: ActivityRegion): List<Player>
    fun isAdminEditing(player: Player): Boolean

    fun sendChat(player: Player, key: MessageKey, values: Map<String, Component> = emptyMap())
    fun sendActionBar(player: Player, key: MessageKey, values: Map<String, Component> = emptyMap())
    fun showScreenTitle(
        player: Player,
        key: MessageKey,
        values: Map<String, Component> = emptyMap(),
        scope: String = "player",
    )
    fun showScreenTitle(player: Player, title: Component, subtitle: Component)
    fun broadcast(
        regions: Collection<ActivityRegion>,
        key: MessageKey,
        values: Map<String, Component> = emptyMap(),
        sound: Sound? = null,
        title: Boolean = false,
        valuesForPlayer: ((Player) -> Map<String, Component>)? = null,
    )

    fun updateBar(
        player: Player,
        runtimeKey: String,
        name: Component,
        progress: Float,
        color: BossBar.Color,
        expected: MutableSet<ActivityBarKey>,
    )
    fun reconcileBars(expected: Set<ActivityBarKey>)
    fun removePlayerBars(player: Player)
    fun hideAllBars()

    fun spawnGuidanceDust(player: Player, location: Location, color: Color, size: Float = 1.1f)
    fun successBurst(region: ActivityRegion)
    fun warningBurst(region: ActivityRegion)
    fun celebration(regions: Collection<ActivityRegion>)
    fun announceWinner(regions: Collection<ActivityRegion>, contributors: Map<UUID, Int>)

    fun traceBlockBreak(event: BlockBreakEvent, activity: ActivityKind, zone: String)
    fun tracePlayerAction(
        player: Player,
        activity: ActivityKind,
        zone: String,
        action: String,
        block: Material? = null,
    )
    fun traceResult(
        activity: ActivityKind,
        zone: String,
        actor: Player?,
        phase: Any,
        progress: String?,
        result: EngineResult<*, *>,
    )

    fun recordContribution(playerId: UUID, kind: ActivityKind, amount: Int)
    fun recordCompletion(kind: ActivityKind, contributors: Map<UUID, Int>)
    fun signal(signal: NetworkSignal, activity: ActivityKind, actorName: String?, excludedPlayers: Set<UUID>)
    fun complete(activity: ActivityKind, actorName: String?, excludedPlayers: Set<UUID>)
    fun persistAsync(): CompletableFuture<Unit>
    fun guarded(scope: String, task: () -> Unit)

    fun lifecycleToken(): RuntimeTaskSupervisor.Token
    fun runSync(token: RuntimeTaskSupervisor.Token, task: () -> Unit): Boolean
    fun runAsync(token: RuntimeTaskSupervisor.Token, task: () -> Unit): Boolean
    fun runLater(delayTicks: Long, task: () -> Unit): Boolean
    fun runLater(token: RuntimeTaskSupervisor.Token, delayTicks: Long, task: () -> Unit): Boolean
    fun log(level: Level, message: String, failure: Throwable? = null)
}

/** Aggregates independent worksite types without knowing their concrete state machines. */
internal class WorksiteModuleRegistry(
    modules: Collection<WorksiteModule<*>>,
) {
    private val modulesByKind = modules.associateBy(WorksiteModule<*>::kind).also { indexed ->
        require(indexed.size == modules.size) { "Only one worksite module may own each activity kind" }
    }

    fun statuses(): List<ActivityStatus> = modulesByKind.values.flatMap(WorksiteModule<*>::statuses)

    fun tick(now: Long) = modulesByKind.values.forEach { it.tick(now) }

    fun isAvailable(kind: ActivityKind): Boolean = modulesByKind[kind]?.isAvailable() == true

    fun canAccess(player: Player, kind: ActivityKind): Boolean = modulesByKind[kind]?.canAccess(player) == true

    fun onBreakHigh(kind: ActivityKind, event: BlockBreakEvent): Boolean =
        (modulesByKind[kind] as? WorksiteBlockBreakHandler)?.onBreakHigh(event) == true

    fun onBreakMonitor(event: BlockBreakEvent) {
        modulesByKind.values.filterIsInstance<WorksiteBlockBreakHandler>().forEach { it.onBreakMonitor(event) }
    }

    fun onInteract(event: PlayerInteractEvent, clicked: Block, player: Player): Boolean =
        modulesByKind.values.filterIsInstance<WorksiteBlockInteractHandler>().any { it.onInteract(event, clicked, player) }

    fun onMove(from: Location, to: Location, player: Player): Boolean =
        modulesByKind.values.filterIsInstance<WorksiteMoveHandler>().any { it.onMove(from, to, player) }
}
