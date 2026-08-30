package ru.ruscrafting.farms.paper.worksite

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Chunk
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.network.NetworkSignal
import ru.ruscrafting.farms.paper.ActivityBarKey
import ru.ruscrafting.farms.paper.ActivityRegion
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

internal interface RuntimeComponent {
    fun activateLoadedState() = Unit
    fun reconcileChunk(chunk: Chunk) = Unit
    fun beforeReload(reason: String) = Unit
    fun cleanup(reason: String) = Unit
}

internal interface WorksiteAccessPort {
    fun isOperational(): Boolean
    fun hasAccess(player: Player, permission: String): Boolean
    fun allowInteraction(key: String, cooldownMillis: Long): Boolean
    fun resetInteraction(key: String)
    fun resetInteractionsContaining(fragment: String)
    fun isAdminEditing(player: Player): Boolean
}

internal interface WorksiteAudiencePort {
    fun players(region: ActivityRegion): List<Player>
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
}

internal interface WorksiteStatePort {
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
    fun persistAsync(): CompletableFuture<Unit>
    fun log(level: Level, message: String, failure: Throwable? = null)
}

internal interface WorksiteTaskPort {
    fun guarded(scope: String, task: () -> Unit)
    fun lifecycleToken(): RuntimeTaskSupervisor.Token
    fun runSync(token: RuntimeTaskSupervisor.Token, task: () -> Unit): Boolean
    fun runAsync(token: RuntimeTaskSupervisor.Token, task: () -> Unit): Boolean
    fun runLater(delayTicks: Long, task: () -> Unit): Boolean
    fun runLater(token: RuntimeTaskSupervisor.Token, delayTicks: Long, task: () -> Unit): Boolean
}

internal interface WorksiteStatsPort {
    fun recordContribution(playerId: UUID, kind: ActivityKind, amount: Int)
    fun recordCompletion(kind: ActivityKind, contributors: Map<UUID, Int>)
}

internal interface WorksiteNetworkPort {
    fun signal(signal: NetworkSignal, activity: ActivityKind, actorName: String?, excludedPlayers: Set<UUID>)
    fun complete(activity: ActivityKind, actorName: String?, excludedPlayers: Set<UUID>)
}
