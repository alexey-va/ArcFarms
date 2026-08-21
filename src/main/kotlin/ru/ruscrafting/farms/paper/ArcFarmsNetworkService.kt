package ru.ruscrafting.farms.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.title.Title
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisManager
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.network.ActivityNetworkGateway
import ru.ruscrafting.farms.network.ArcFarmsNetworkRepository
import ru.ruscrafting.farms.network.NetworkEvent
import ru.ruscrafting.farms.network.NetworkSignal
import ru.ruscrafting.farms.network.WorkdayState
import ru.ruscrafting.farms.network.WorkdayUpdate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

class ArcFarmsNetworkService(
    private val plugin: Plugin,
    private val settings: () -> ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    private val repository: ArcFarmsNetworkRepository,
    private val redis: RedisManager,
    private val clock: () -> Long = System::currentTimeMillis,
) : ActivityNetworkGateway, AutoCloseable {
    @Volatile
    private var currentWorkday: WorkdayState? = null
    private var listener: ChannelListener? = null
    private val seenEvents = ConcurrentHashMap<String, Long>()
    private val pendingProbes = ConcurrentHashMap<String, Long>()
    private val tasks = mutableListOf<ScheduledTask>()
    private var lastProbeAtMs = 0L
    @Volatile
    private var started = false

    fun start() {
        check(!started) { "ArcFarms network service is already started" }
        listener = repository.registerEvents(::receive)
        refreshWorkday()
        tasks += Tasks.scheduler.runLater(60L) { probe() }
        tasks += Tasks.scheduler.runTimer(1_200L, 1_200L) { maintain() }
        started = true
    }

    override fun signal(
        signal: NetworkSignal,
        activity: ActivityKind,
        actorName: String?,
        excludedPlayers: Set<UUID>,
    ) {
        if (!settings().network.enabled) return
        require(signal !in setOf(NetworkSignal.ACTIVITY_COMPLETED, NetworkSignal.WORKDAY_STAMP, NetworkSignal.WORKDAY_COMPLETED)) {
            "Use the dedicated completion path for $signal"
        }
        emit(NetworkEvent.create(signal, activity, actorName), excludedPlayers)
    }

    override fun complete(activity: ActivityKind, actorName: String?, excludedPlayers: Set<UUID>) {
        val current = settings()
        if (!current.network.enabled) return
        emit(NetworkEvent.create(NetworkSignal.ACTIVITY_COMPLETED, activity, actorName), excludedPlayers)
        if (!current.network.workdayEnabled) return
        repository.markCompleted(activity).whenComplete { update, failure ->
            if (!started) return@whenComplete
            Tasks.scheduler.runSync {
                if (failure != null) {
                    plugin.logger.log(Level.WARNING, "ArcFarms could not stamp the persistent network workday", failure)
                    return@runSync
                }
                updateWorkday(update.state)
                when (update) {
                    is WorkdayUpdate.Stamped -> emit(
                        NetworkEvent.create(
                            signal = NetworkSignal.WORKDAY_STAMP,
                            activity = update.activity,
                            cycle = update.state.cycle,
                            completed = update.state.completed,
                            actorName = actorName,
                        ),
                        emptySet(),
                    )
                    is WorkdayUpdate.Completed -> emit(
                        NetworkEvent.create(
                            signal = NetworkSignal.WORKDAY_COMPLETED,
                            activity = update.closingActivity,
                            cycle = update.completedCycle,
                            completed = ActivityKind.entries.toSet(),
                            actorName = actorName,
                        ),
                        emptySet(),
                    )
                    is WorkdayUpdate.AlreadyStamped -> Unit
                    is WorkdayUpdate.Contended -> plugin.logger.warning(
                        "ArcFarms workday update remained contended after bounded retries; it will be retried on the next completion",
                    )
                }
            }
        }
    }

    override fun workday(): WorkdayState? = currentWorkday

    private fun receive(event: NetworkEvent, origin: String) {
        val current = settings()
        if (!current.network.enabled || origin == current.serverId || origin !in current.network.allowedOrigins) return
        val now = clock()
        if (event.occurredAtMs < now - EVENT_MAX_AGE_MS || event.occurredAtMs > now + EVENT_FUTURE_SKEW_MS) return
        if (seenEvents.size >= MAX_SEEN_EVENTS) return
        if (seenEvents.putIfAbsent(event.eventId, now) != null) return
        Tasks.scheduler.runSync {
            when (event.signal) {
                NetworkSignal.NODE_PROBE -> acknowledge(event, origin)
                NetworkSignal.NODE_ACK -> acceptAcknowledgement(event, origin)
                else -> {
                    applyWorkdayEvent(event)
                    deliver(event, emptySet())
                }
            }
        }
    }

    private fun emit(event: NetworkEvent, excludedPlayers: Set<UUID>) {
        seenEvents[event.eventId] = clock()
        when (event.signal) {
            NetworkSignal.NODE_PROBE, NetworkSignal.NODE_ACK -> Unit
            else -> deliver(event, excludedPlayers)
        }
        repository.publish(event)
    }

    private fun deliver(event: NetworkEvent, excludedPlayers: Set<UUID>) {
        val current = settings()
        val isCall = event.signal in CALL_SIGNALS
        if (isCall && !current.network.callsEnabled) return
        if (event.signal == NetworkSignal.ACTIVITY_COMPLETED && !current.network.completionsEnabled) return
        if (event.signal in WORKDAY_SIGNALS && !current.network.workdayEnabled) return
        val recipients = plugin.server.onlinePlayers.filterNot { it.uniqueId in excludedPlayers }
        when (event.signal) {
            NetworkSignal.WORKDAY_STAMP -> recipients.forEach { player ->
                player.sendActionBar(render(event, player))
                if (current.sounds) player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.55f, 1.35f)
            }
            NetworkSignal.WORKDAY_COMPLETED -> recipients.forEach { player -> celebrateWorkday(player, event) }
            NetworkSignal.NODE_PROBE, NetworkSignal.NODE_ACK -> Unit
            else -> recipients.forEach { player ->
                var message = render(event, player)
                if (isCall) message = message.append(Component.space()).append(callToAction(player, requireNotNull(event.activity)))
                player.sendMessage(message)
                signalSound(player, event.signal)
            }
        }
    }

    private fun render(event: NetworkEvent, player: Player): Component {
        val activity = event.activity?.let { activityName(it, player) } ?: Component.empty()
        val actor = event.actorName?.let(Component::text) ?: locale.render(MessageKey.NETWORK_ACTOR_FALLBACK, player)
        val values = mapOf(
            "activity" to activity,
            "actor" to actor,
            "cycle" to locale.text(event.cycle ?: currentWorkday?.cycle ?: 1),
            "seals" to seals(event.completed, player),
        )
        return locale.render(
            when (event.signal) {
                NetworkSignal.FARM_INCIDENT -> MessageKey.NETWORK_FARM_INCIDENT
                NetworkSignal.FARM_RESCUED -> MessageKey.NETWORK_FARM_RESCUED
                NetworkSignal.LUMBER_PROCESSING -> MessageKey.NETWORK_LUMBER_PROCESSING
                NetworkSignal.MINE_HAZARD -> MessageKey.NETWORK_MINE_HAZARD
                NetworkSignal.MINE_STABLE -> MessageKey.NETWORK_MINE_STABLE
                NetworkSignal.MINE_EXTRACTION -> MessageKey.NETWORK_MINE_EXTRACTION
                NetworkSignal.ACTIVITY_COMPLETED -> MessageKey.NETWORK_ACTIVITY_COMPLETED
                NetworkSignal.WORKDAY_STAMP -> MessageKey.NETWORK_WORKDAY_STAMP
                NetworkSignal.WORKDAY_COMPLETED -> MessageKey.NETWORK_WORKDAY_COMPLETED
                NetworkSignal.NODE_PROBE, NetworkSignal.NODE_ACK -> error("Node probes are not player-facing")
            },
            player,
            values,
        )
    }

    private fun callToAction(player: Player, activity: ActivityKind): Component {
        val current = settings()
        val onHub = current.serverId == current.network.hubServer
        val command = if (onHub) current.navigation.getValue(activity.configKey) else current.network.transferCommand
        val key = if (onHub) MessageKey.NETWORK_CALL_LOCAL else MessageKey.NETWORK_CALL_REMOTE
        return locale.render(key, player)
            .clickEvent(ClickEvent.runCommand("/$command"))
            .hoverEvent(HoverEvent.showText(locale.render(MessageKey.NETWORK_CALL_HOVER, player)))
    }

    private fun signalSound(player: Player, signal: NetworkSignal) {
        if (!settings().sounds) return
        val sound = when (signal) {
            NetworkSignal.FARM_INCIDENT -> Sound.ENTITY_BEE_LOOP_AGGRESSIVE
            NetworkSignal.FARM_RESCUED -> Sound.ENTITY_VILLAGER_YES
            NetworkSignal.LUMBER_PROCESSING -> Sound.BLOCK_PISTON_EXTEND
            NetworkSignal.MINE_HAZARD -> Sound.ENTITY_GENERIC_EXPLODE
            NetworkSignal.MINE_STABLE -> Sound.BLOCK_ANVIL_USE
            NetworkSignal.MINE_EXTRACTION -> Sound.BLOCK_BELL_RESONATE
            NetworkSignal.ACTIVITY_COMPLETED -> Sound.UI_TOAST_CHALLENGE_COMPLETE
            else -> return
        }
        player.playSound(player.location, sound, 0.55f, 1.0f)
    }

    private fun celebrateWorkday(player: Player, event: NetworkEvent) {
        val message = render(event, player)
        player.showTitle(
            Title.title(
                message,
                locale.render(MessageKey.NETWORK_WORKDAY_NEXT, player, mapOf("cycle" to locale.text((event.cycle ?: 0) + 1))),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(650)),
            ),
        )
        player.sendMessage(message)
        if (settings().sounds) {
            player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.0f)
            player.playSound(player.location, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.55f, 1.2f)
        }
        if (settings().particles) {
            player.spawnParticle(Particle.FIREWORK, player.location.add(0.0, 1.3, 0.0), 30, 1.5, 1.0, 1.5, 0.09)
            player.spawnParticle(Particle.FLASH, player.location.add(0.0, 1.8, 0.0), 2, 0.6, 0.5, 0.6, 0.0, Color.YELLOW)
            Tasks.scheduler.runLater(10L) {
                if (player.isOnline) {
                    player.spawnParticle(Particle.END_ROD, player.location.add(0.0, 1.4, 0.0), 34, 1.6, 1.0, 1.6, 0.08)
                    if (settings().sounds) player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.25f)
                }
            }
        }
    }

    private fun seals(completed: Set<ActivityKind>, player: Player): Component = Component.join(
        net.kyori.adventure.text.JoinConfiguration.separator(Component.text("  ")),
        ActivityKind.entries.map { activity ->
            locale.render(
                if (activity in completed) MessageKey.NETWORK_SEAL_DONE else MessageKey.NETWORK_SEAL_PENDING,
                player,
                mapOf("activity" to activityName(activity, player)),
            )
        },
    )

    private fun activityName(activity: ActivityKind, player: Player): Component = locale.render(
        when (activity) {
            ActivityKind.FARM -> MessageKey.MENU_FARM_NAME
            ActivityKind.LUMBER -> MessageKey.MENU_LUMBER_NAME
            ActivityKind.MINE -> MessageKey.MENU_MINE_NAME
        },
        player,
    )

    private fun acknowledge(event: NetworkEvent, origin: String) {
        plugin.logger.info("ArcFarms xserver probe received from node=$origin event=${event.eventId}")
        repository.publish(NetworkEvent.create(NetworkSignal.NODE_ACK, replyTo = event.eventId))
    }

    private fun acceptAcknowledgement(event: NetworkEvent, origin: String) {
        val replyTo = requireNotNull(event.replyTo)
        if (pendingProbes.remove(replyTo) != null) {
            plugin.logger.info("ArcFarms xserver peer acknowledged node=$origin reply=$replyTo")
        }
    }

    private fun probe() {
        if (!settings().network.enabled || !settings().network.nodeProbeEnabled) return
        val event = NetworkEvent.create(NetworkSignal.NODE_PROBE)
        pendingProbes[event.eventId] = clock()
        seenEvents[event.eventId] = clock()
        lastProbeAtMs = clock()
        repository.publish(event)
        plugin.logger.info("ArcFarms xserver probe sent node=${settings().serverId} event=${event.eventId}")
    }

    private fun maintain() {
        val cutoff = clock() - EVENT_MAX_AGE_MS
        seenEvents.entries.removeIf { it.value < cutoff }
        pendingProbes.entries.removeIf { it.value < cutoff }
        if (!redis.isSubscriptionActive()) redis.init()
        refreshWorkday()
        if (clock() - lastProbeAtMs >= PROBE_INTERVAL_MS) probe()
    }

    private fun refreshWorkday() {
        if (!settings().network.enabled || !settings().network.workdayEnabled) return
        repository.loadWorkday().whenComplete { state, failure ->
            if (failure == null) updateWorkday(state)
            else plugin.logger.log(Level.WARNING, "ArcFarms network workday is temporarily unavailable; local activities remain active", failure)
        }
    }

    private fun applyWorkdayEvent(event: NetworkEvent) {
        when (event.signal) {
            NetworkSignal.WORKDAY_STAMP -> {
                val cycle = requireNotNull(event.cycle)
                val revision = currentWorkday?.revision ?: 0
                updateWorkday(WorkdayState(cycle, revision, event.completed), mergeSameCycle = true)
            }
            NetworkSignal.WORKDAY_COMPLETED -> {
                val nextCycle = requireNotNull(event.cycle) + 1
                val revision = currentWorkday?.revision ?: 0
                updateWorkday(WorkdayState(nextCycle, revision))
            }
            else -> Unit
        }
    }

    @Synchronized
    private fun updateWorkday(candidate: WorkdayState, mergeSameCycle: Boolean = false) {
        val current = currentWorkday
        if (current == null || candidate.cycle > current.cycle ||
            (candidate.cycle == current.cycle && candidate.revision > current.revision)
        ) {
            currentWorkday = candidate
            return
        }
        if (mergeSameCycle && candidate.cycle == current.cycle) {
            currentWorkday = current.copy(completed = current.completed + candidate.completed)
        }
    }

    override fun close() {
        tasks.forEach(ScheduledTask::cancel)
        tasks.clear()
        listener?.let(repository::unregisterEvents)
        listener = null
        seenEvents.clear()
        pendingProbes.clear()
        started = false
    }

    private val ActivityKind.configKey: String
        get() = when (this) {
            ActivityKind.FARM -> "farm"
            ActivityKind.LUMBER -> "lumber"
            ActivityKind.MINE -> "mine"
        }

    companion object {
        private val CALL_SIGNALS = setOf(
            NetworkSignal.FARM_INCIDENT,
            NetworkSignal.LUMBER_PROCESSING,
            NetworkSignal.MINE_HAZARD,
            NetworkSignal.MINE_EXTRACTION,
        )
        private val WORKDAY_SIGNALS = setOf(NetworkSignal.WORKDAY_STAMP, NetworkSignal.WORKDAY_COMPLETED)
        private const val EVENT_MAX_AGE_MS = 15 * 60 * 1000L
        private const val EVENT_FUTURE_SKEW_MS = 60 * 1000L
        private const val PROBE_INTERVAL_MS = 5 * 60 * 1000L
        private const val MAX_SEEN_EVENTS = 4_096
    }
}
