package ru.ruscrafting.farms.paper

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.ActivityStatsIndex
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.winner
import ru.ruscrafting.farms.network.ActivityNetworkGateway
import ru.ruscrafting.farms.network.NetworkSignal
import java.time.Duration
import java.util.UUID
import java.util.logging.Level

/** Shared Paper presentation and infrastructure adapter for every worksite type. */
internal class PaperWorksiteRuntimePort(
    private val plugin: Plugin,
    private val locale: ArcFarmsLocale,
    private val settings: () -> ArcFarmsConfig,
    private val debug: ArcFarmsDebug,
    private val network: ActivityNetworkGateway,
    private val stats: ActivityStatsIndex,
    private val supervisor: RuntimeTaskSupervisor,
    private val operational: () -> Boolean,
    private val access: (Player, String) -> Boolean,
    private val interaction: (String, Long) -> Boolean,
    private val interactionReset: (String) -> Unit,
    private val interactionResetMatching: (String) -> Unit,
    private val adminEditing: (Player) -> Boolean,
    private val persist: () -> Unit,
    private val guard: (String, () -> Unit) -> Unit,
) : WorksiteRuntimePort {
    private val activeBars = mutableMapOf<ActivityBarKey, BossBar>()

    override fun isOperational(): Boolean = operational()
    override fun hasAccess(player: Player, permission: String): Boolean = access(player, permission)
    override fun allowInteraction(key: String, cooldownMillis: Long): Boolean = interaction(key, cooldownMillis)
    override fun resetInteraction(key: String) = interactionReset(key)
    override fun resetInteractionsContaining(fragment: String) = interactionResetMatching(fragment)
    override fun players(region: ActivityRegion): List<Player> = region.world.players.filter { region.contains(it.location) }
    override fun isAdminEditing(player: Player): Boolean = adminEditing(player)

    override fun sendChat(player: Player, key: MessageKey, values: Map<String, Component>) {
        val message = locale.render(key, player, values)
        player.sendMessage(message)
        debug.message("chat", "player", key.path, player, message)
    }

    override fun sendActionBar(player: Player, key: MessageKey, values: Map<String, Component>) {
        val message = locale.render(key, player, values)
        player.sendActionBar(message)
        debug.message("actionbar", "player", key.path, player, message)
    }

    override fun broadcast(
        regions: Collection<ActivityRegion>,
        key: MessageKey,
        values: Map<String, Component>,
        sound: Sound?,
        title: Boolean,
        valuesForPlayer: ((Player) -> Map<String, Component>)?,
    ) {
        regions.flatMap(::players).distinctBy(Player::getUniqueId).forEach { player ->
            val playerValues = valuesForPlayer?.invoke(player) ?: values
            if (title) {
                showScreenTitle(player, key, playerValues, "local")
            } else {
                val message = locale.render(key, player, playerValues)
                player.sendActionBar(message)
                debug.message("actionbar", "local", key.path, player, message)
            }
            if (sound != null && settings().sounds) player.playSound(player.location, sound, 0.8f, 1.0f)
        }
    }

    override fun showScreenTitle(
        player: Player,
        key: MessageKey,
        values: Map<String, Component>,
        scope: String,
    ) {
        val title = locale.render(key, player, values)
        val subtitleKey = TITLE_SUBTITLES[key]
        val subtitle = subtitleKey?.let { locale.render(it, player, values) } ?: Component.empty()
        showScreenTitle(player, title, subtitle)
        debug.message("title", scope, key.path, player, title)
        if (subtitleKey != null) debug.message("subtitle", scope, subtitleKey.path, player, subtitle)
    }

    override fun showScreenTitle(player: Player, title: Component, subtitle: Component) {
        player.showTitle(
            Title.title(
                title,
                subtitle,
                Title.Times.times(
                    Duration.ofMillis(300),
                    Duration.ofSeconds(settings().titleStaySeconds.toLong()),
                    Duration.ofMillis(700),
                ),
            ),
        )
    }

    override fun updateBar(
        player: Player,
        runtimeKey: String,
        name: Component,
        progress: Float,
        color: BossBar.Color,
        expected: MutableSet<ActivityBarKey>,
    ) {
        if (!settings().bossbars) return
        val key = ActivityBarKey(player.uniqueId, runtimeKey)
        expected += key
        val existing = activeBars[key]
        val bar = existing ?: BossBar.bossBar(
            name,
            progress.coerceIn(0f, 1f),
            color,
            BossBar.Overlay.PROGRESS,
        ).also {
            activeBars[key] = it
            player.showBossBar(it)
            debug.message("bossbar", "local", runtimeKey, player, name)
        }
        if (existing != null && existing.name() != name) {
            debug.message("bossbar", "local", runtimeKey, player, name)
        }
        bar.name(name)
        bar.progress(progress.coerceIn(0f, 1f))
        bar.color(color)
    }

    override fun reconcileBars(expected: Set<ActivityBarKey>) {
        (activeBars.keys - expected).forEach { key ->
            val bar = activeBars.remove(key) ?: return@forEach
            Bukkit.getPlayer(key.playerId)?.hideBossBar(bar)
        }
    }

    override fun removePlayerBars(player: Player) {
        activeBars.keys.filter { it.playerId == player.uniqueId }.forEach { key ->
            activeBars.remove(key)?.let(player::hideBossBar)
        }
    }

    override fun hideAllBars() {
        activeBars.forEach { (key, bar) -> Bukkit.getPlayer(key.playerId)?.hideBossBar(bar) }
        activeBars.clear()
    }

    override fun spawnGuidanceDust(player: Player, location: Location, color: Color, size: Float) {
        player.spawnParticle(Particle.DUST, location, 1, 0.0, 0.0, 0.0, 0.0, Particle.DustOptions(color, size))
    }

    override fun warningBurst(region: ActivityRegion) {
        if (!settings().particles) return
        players(region).forEach { player ->
            player.spawnParticle(
                Particle.DUST,
                player.location.clone().add(0.0, 1.15, 0.0),
                5,
                0.55,
                0.4,
                0.55,
                0.0,
                Particle.DustOptions(DANGER_COLOR, 1.1f),
            )
        }
    }

    override fun successBurst(region: ActivityRegion) {
        if (!settings().particles) return
        players(region).forEach { player ->
            player.spawnParticle(
                Particle.DUST,
                player.location.clone().add(0.0, 1.0, 0.0),
                6,
                0.55,
                0.45,
                0.55,
                0.0,
                Particle.DustOptions(SUCCESS_COLOR, 1.15f),
            )
        }
    }

    override fun celebration(regions: Collection<ActivityRegion>) {
        val recipients = regions.flatMap(::players).distinctBy(Player::getUniqueId)
        recipients.forEach { player ->
            if (settings().particles) {
                player.spawnParticle(Particle.FIREWORK, player.location.add(0.0, 1.2, 0.0), 8, 0.7, 0.55, 0.7, 0.025)
            }
            if (settings().sounds) player.playSound(player.location, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.7f, 1.15f)
        }
        if (recipients.isEmpty()) return
        supervisor.runLater(8L) {
            recipients.filter(Player::isOnline).forEach { player ->
                if (settings().particles) {
                    player.spawnParticle(Particle.FIREWORK, player.location.add(0.0, 1.8, 0.0), 10, 0.9, 0.7, 0.9, 0.04)
                }
                if (settings().sounds) {
                    player.playSound(player.location, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.9f, 1.0f)
                    player.playSound(player.location, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.65f, 1.2f)
                }
            }
        }
    }

    override fun announceWinner(regions: Collection<ActivityRegion>, contributors: Map<UUID, Int>) {
        val winnerId = winner(contributors) ?: return
        val winnerName = Bukkit.getPlayer(winnerId)?.name ?: winnerId.toString().take(8)
        broadcast(
            regions,
            MessageKey.SHIFT_WINNER,
            mapOf(
                "player" to Component.text(winnerName),
                "amount" to locale.text(contributors.getValue(winnerId)),
            ),
        )
    }

    override fun traceBlockBreak(event: BlockBreakEvent, activity: ActivityKind, zone: String) {
        debug.event(
            "player_block_break",
            "player" to event.player.name,
            "activity" to activity,
            "zone" to zone,
            "block" to event.block.type,
            "world" to event.block.world.name,
            "x" to event.block.x,
            "y" to event.block.y,
            "z" to event.block.z,
        )
    }

    override fun tracePlayerAction(
        player: Player,
        activity: ActivityKind,
        zone: String,
        action: String,
        block: org.bukkit.Material?,
    ) {
        debug.event(
            "player_action",
            "player" to player.name,
            "activity" to activity,
            "zone" to zone,
            "action" to action,
            "block" to block,
        )
    }

    override fun traceResult(
        activity: ActivityKind,
        zone: String,
        actor: Player?,
        phase: Any,
        progress: String?,
        result: EngineResult<*>,
    ) {
        if (!result.accepted && result.events.isEmpty()) return
        debug.event(
            "activity_result",
            "activity" to activity,
            "zone" to zone,
            "player" to actor?.name,
            "phase" to phase,
            "progress" to progress,
            "contribution" to result.contribution,
            "events" to result.events.joinToString(","),
        )
    }

    override fun recordContribution(playerId: UUID, kind: ActivityKind, amount: Int) = stats.contribute(playerId, kind, amount)

    override fun recordCompletion(kind: ActivityKind, contributors: Map<UUID, Int>) {
        contributors.keys.forEach { playerId -> stats.complete(playerId, kind) }
    }

    override fun signal(signal: NetworkSignal, activity: ActivityKind, actorName: String?, excludedPlayers: Set<UUID>) =
        network.signal(signal, activity, actorName, excludedPlayers)

    override fun complete(activity: ActivityKind, actorName: String?, excludedPlayers: Set<UUID>) =
        network.complete(activity, actorName, excludedPlayers)

    override fun persistAsync() = persist()
    override fun guarded(scope: String, task: () -> Unit) = guard(scope, task)
    override fun lifecycleToken(): RuntimeTaskSupervisor.Token = supervisor.token()
    override fun runSync(token: RuntimeTaskSupervisor.Token, task: () -> Unit): Boolean = supervisor.runSync(token, task) != null
    override fun runLater(delayTicks: Long, task: () -> Unit): Boolean = supervisor.runLater(supervisor.token(), delayTicks, task) != null
    override fun runLater(token: RuntimeTaskSupervisor.Token, delayTicks: Long, task: () -> Unit): Boolean =
        supervisor.runLater(token, delayTicks, task) != null

    override fun log(level: Level, message: String, failure: Throwable?) {
        if (failure == null) plugin.logger.log(level, message) else plugin.logger.log(level, message, failure)
    }

    private companion object {
        val DANGER_COLOR: Color = Color.fromRGB(255, 95, 109)
        val SUCCESS_COLOR: Color = Color.fromRGB(85, 217, 139)
        val TITLE_SUBTITLES = mapOf(
            MessageKey.FARM_ENTRY_TITLE to MessageKey.FARM_ENTRY_SUBTITLE,
            MessageKey.FARM_PLANTING_STARTED to MessageKey.FARM_PLANTING_STARTED_SUBTITLE,
            MessageKey.FARM_PREPARATION_COMPLETED to MessageKey.FARM_PREPARATION_COMPLETED_SUBTITLE,
            MessageKey.FARM_CARE_RESOLVED to MessageKey.FARM_CARE_RESOLVED_SUBTITLE,
            MessageKey.FARM_CARE_SEEDER_RESOLVED to MessageKey.FARM_CARE_SEEDER_RESOLVED_SUBTITLE,
            MessageKey.FARM_MOLE_ENTERED to MessageKey.FARM_MOLE_ENTERED_SUBTITLE,
            MessageKey.FARM_INCIDENT_STARTED to MessageKey.FARM_INCIDENT_STARTED_SUBTITLE,
            MessageKey.FARM_INCIDENT_RESOLVED to MessageKey.FARM_INCIDENT_RESOLVED_SUBTITLE,
            MessageKey.FARM_DROUGHT_STARTED to MessageKey.FARM_DROUGHT_STARTED_SUBTITLE,
            MessageKey.FARM_BIRDS_STARTED to MessageKey.FARM_BIRDS_STARTED_SUBTITLE,
            MessageKey.FARM_ROUTE_STARTED to MessageKey.FARM_ROUTE_STARTED_SUBTITLE,
            MessageKey.FARM_ROUTE_MOUNTED to MessageKey.FARM_ROUTE_MOUNTED_SUBTITLE,
            MessageKey.FARM_SPECIAL_RESOLVED to MessageKey.FARM_SPECIAL_RESOLVED_SUBTITLE,
            MessageKey.FARM_GIANT_CROP_STARTED to MessageKey.FARM_GIANT_CROP_STARTED_SUBTITLE,
            MessageKey.FARM_CHANNELS_STARTED to MessageKey.FARM_CHANNELS_STARTED_SUBTITLE,
            MessageKey.FARM_NIGHT_SHIFT_STARTED to MessageKey.FARM_NIGHT_SHIFT_STARTED_SUBTITLE,
            MessageKey.FARM_MARKET_EXPIRED to MessageKey.FARM_MARKET_EXPIRED_SUBTITLE,
            MessageKey.FARM_MARKET_STARTED to MessageKey.FARM_MARKET_STARTED_SUBTITLE,
            MessageKey.FARM_MARKET_ACCEPTED to MessageKey.FARM_MARKET_ACCEPTED_SUBTITLE,
            MessageKey.FARM_DELIVERY_STARTED to MessageKey.FARM_DELIVERY_STARTED_SUBTITLE,
            MessageKey.FARM_DELIVERY_PICKED_UP to MessageKey.FARM_DELIVERY_PICKED_UP_SUBTITLE,
            MessageKey.FARM_CROP_COMPLETED to MessageKey.FARM_CROP_COMPLETED_SUBTITLE,
            MessageKey.FARM_COMPLETED to MessageKey.FARM_COMPLETED_SUBTITLE,
            MessageKey.LUMBER_PROCESSING to MessageKey.LUMBER_PROCESSING_SUBTITLE,
            MessageKey.LUMBER_COMPLETED to MessageKey.LUMBER_COMPLETED_SUBTITLE,
            MessageKey.MINE_HAZARD_STARTED to MessageKey.MINE_HAZARD_STARTED_SUBTITLE,
            MessageKey.MINE_HAZARD_RESOLVED to MessageKey.MINE_HAZARD_RESOLVED_SUBTITLE,
            MessageKey.MINE_EXTRACTION_STARTED to MessageKey.MINE_EXTRACTION_STARTED_SUBTITLE,
            MessageKey.MINE_COMPLETED to MessageKey.MINE_COMPLETED_SUBTITLE,
        )
    }
}
