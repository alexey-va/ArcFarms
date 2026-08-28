package ru.ruscrafting.farms.paper.farm.presentation

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound as AdventureSound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmMusicLoop
import ru.ruscrafting.farms.domain.FarmOrder
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmSeederStage
import ru.ruscrafting.farms.domain.FarmProcessingStage
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.domain.seederStage
import ru.ruscrafting.farms.paper.ActivityBarKey
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.FarmScoreboardController
import ru.ruscrafting.farms.paper.FarmScoreboardRenderer
import ru.ruscrafting.farms.paper.FarmScoreboardView
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.delivery.FarmDeliveryController
import ru.ruscrafting.farms.paper.farm.harvest.FarmHarvestController
import ru.ruscrafting.farms.paper.farm.incident.route.FarmFoodDeliveryIncident
import ru.ruscrafting.farms.paper.farm.incident.special.FarmSpecialIncidentController
import ru.ruscrafting.farms.paper.farm.incident.special.SPECIAL_FARM_INCIDENT_TYPES
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/** Sole owner of farm boss bars, scoreboard sessions, music and task hints. */
internal class FarmHudController(
    private val settings: () -> ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
    private val port: WorksiteRuntimePort,
    private val delivery: FarmDeliveryController,
    private val foodDelivery: FarmFoodDeliveryIncident,
    private val special: FarmSpecialIncidentController,
    private val harvest: FarmHarvestController,
    private val clock: () -> Long,
) {
    private val scoreboards = FarmScoreboardController(FarmScoreboardRenderer(locale), { settings().farmScoreboard }, debug)
    private val music = FarmMusicLoop()

    fun update(runtimes: Collection<FarmRuntime>): MutableSet<ActivityBarKey> {
        val expectedBars = mutableSetOf<ActivityBarKey>()
        val expectedScoreboards = mutableSetOf<UUID>()
        runtimes.forEach { runtime ->
            if (runtime.state.phase !in VISIBLE_PHASES) return@forEach
            if (runtime.state.phase == FarmPhase.COOLDOWN) {
                val now = clock()
                val remainingMillis = (runtime.state.cooldownEndsAt - now).coerceAtLeast(0)
                val cooldownMillis = runtime.rules.cooldownMillis.coerceAtLeast(1)
                players(runtime).filterNot(port::isAdminEditing).forEach { player ->
                    port.updateBar(
                        player,
                        "farm:${runtime.settings.id}",
                        locale.render(
                            MessageKey.FARM_COOLDOWN_BOSSBAR,
                            player,
                            mapOf("seconds" to locale.text(remainingSeconds(runtime.state.cooldownEndsAt, now))),
                        ),
                        (1.0 - remainingMillis.toDouble() / cooldownMillis).toFloat(),
                        BossBar.Color.YELLOW,
                        expectedBars,
                    )
                }
                return@forEach
            }
            val order = currentOrder(runtime) ?: return@forEach
            val done = runtime.state.completed(order)
            players(runtime).filterNot(port::isAdminEditing).forEach { player ->
                val carrying = delivery.isCarrying(runtime.settings.id, player.uniqueId)
                val phaseDone = phaseDone(runtime, done)
                val phaseTotal = phaseTotal(runtime, order)
                val key = bossBarKey(runtime, carrying)
                val component = locale.render(
                    key,
                    player,
                    mapOf(
                        "order" to locale.renderPath("order.farm.${order.id}", player),
                        "crop" to MaterialRules.cropComponent(
                            MaterialRules.material(requireNotNull(activeCrop(runtime, order))),
                        ),
                        "requirements" to requirements(runtime, order),
                        "instruction" to instruction(runtime, player),
                        "nests" to locale.text(runtime.state.pestNests.size),
                        "pests" to locale.text(runtime.state.pestAlive),
                        "event" to (runtime.state.incidentType?.takeIf { it in SPECIAL_FARM_INCIDENT_TYPES }
                            ?.let { special.name(it, player) } ?: Component.empty()),
                        "time" to (runtime.state.specialIncident?.takeIf {
                            runtime.state.incidentType == FarmIncidentType.MARKET && it.marketAccepted
                        }?.let { locale.text(special.marketTime(runtime, it)) } ?: Component.empty()),
                        "done" to locale.text(phaseDone),
                        "total" to locale.text(phaseTotal),
                    ),
                )
                port.updateBar(
                    player,
                    "farm:${runtime.settings.id}",
                    component,
                    phaseDone.toFloat() / phaseTotal,
                    bossBarColor(runtime.state.phase),
                    expectedBars,
                )
                updateScoreboard(runtime, order, player, phaseDone, phaseTotal, carrying, expectedScoreboards)
            }
        }
        scoreboards.reconcile(expectedScoreboards)
        return expectedBars
    }

    fun enter(player: Player, runtime: FarmRuntime) {
        if (port.isAdminEditing(player)) return
        val actionKey = when (runtime.state.phase) {
            FarmPhase.IDLE, FarmPhase.COOLDOWN -> MessageKey.FARM_ENTRY_IDLE
            FarmPhase.PREPARATION -> MessageKey.FARM_ENTRY_PREPARATION
            FarmPhase.PLANTING -> MessageKey.FARM_ENTRY_PLANTING
            FarmPhase.CARE -> null
            FarmPhase.HARVESTING -> MessageKey.FARM_ENTRY_HARVESTING
            FarmPhase.INCIDENT -> when (runtime.state.incidentType ?: FarmIncidentType.PESTS) {
                FarmIncidentType.DROUGHT -> MessageKey.FARM_ENTRY_DROUGHT
                FarmIncidentType.PESTS -> MessageKey.FARM_ENTRY_PESTS
                FarmIncidentType.BIRDS -> MessageKey.FARM_ENTRY_BIRDS
                FarmIncidentType.FOOD_DELIVERY -> MessageKey.FARM_ENTRY_ROUTE
                FarmIncidentType.PROCESSING -> MessageKey.FARM_ENTRY_PROCESSING
                FarmIncidentType.BARN_FIRE -> MessageKey.FARM_ENTRY_BARN_FIRE
                else -> null
            }
            FarmPhase.DELIVERY -> MessageKey.FARM_ENTRY_DELIVERY
        }
        val action = if (actionKey == null) dynamicEntry(runtime, player) else locale.render(
            actionKey,
            player,
            mapOf(
                "crop" to (runtime.state.preparationCrop
                    ?.let(MaterialRules::material)
                    ?.let(MaterialRules::cropComponent)
                    ?: Component.empty()),
            ),
        )
        port.showScreenTitle(player, MessageKey.FARM_ENTRY_TITLE, mapOf("action" to action), "zone_entry")
    }

    fun taskHint(player: Player, runtime: FarmRuntime, reason: String) {
        if (!port.allowInteraction("farm-task-hint:${runtime.settings.id}:${player.uniqueId}", 900)) return
        when (runtime.state.phase) {
            FarmPhase.PREPARATION -> port.sendActionBar(player, MessageKey.FARM_PREPARATION_REQUIRED)
            FarmPhase.PLANTING -> port.sendActionBar(
                player,
                MessageKey.FARM_PLANTING_REQUIRED,
                mapOf("crop" to MaterialRules.cropComponent(MaterialRules.material(requireNotNull(runtime.state.preparationCrop)))),
            )
            FarmPhase.CARE -> port.sendActionBar(
                player,
                MessageKey.FARM_CARE_REQUIRED,
                mapOf("instruction" to instruction(runtime, player)),
            )
            FarmPhase.INCIDENT -> incidentHint(player, runtime)
            FarmPhase.DELIVERY -> port.sendActionBar(player, MessageKey.FARM_DELIVERY_REQUIRED)
            FarmPhase.HARVESTING -> currentOrder(runtime)?.let { order ->
                port.sendActionBar(player, MessageKey.FARM_WRONG_TARGET, mapOf("crops" to harvest.remainingCrops(runtime, order)))
            }
            FarmPhase.COOLDOWN -> port.sendActionBar(
                player,
                MessageKey.COOLDOWN,
                mapOf("seconds" to locale.text(remainingSeconds(runtime.state.cooldownEndsAt, clock()))),
            )
            FarmPhase.IDLE -> Unit
        }
        debug.event(
            "farm_wrong_action_hint",
            "player" to player.name,
            "zone" to runtime.settings.id,
            "phase" to runtime.state.phase,
            "reason" to reason,
        )
    }

    fun storyTitle(
        runtime: FarmRuntime,
        beat: String,
        sound: Sound? = null,
        titleAndValues: (Player) -> Pair<Component, Map<String, Component>>,
    ) {
        val orderId = runtime.state.orderId ?: return
        players(runtime).forEach { player ->
            val (title, values) = titleAndValues(player)
            val subtitle = locale.renderPath("story.farm.$orderId.$beat", player, values)
            port.showScreenTitle(player, title, subtitle)
            debug.message("title", "local", "story.farm.$orderId.$beat", player, title)
            debug.message("subtitle", "local", "story.farm.$orderId.$beat", player, subtitle)
            if (sound != null && settings().sounds) player.playSound(player.location, sound, 0.8f, 1.0f)
        }
    }

    fun syncMusic(player: Player, runtime: FarmRuntime?, now: Long) {
        val configured = runtime?.settings?.music?.takeIf { settings().sounds && it.enabled }
        val transition = if (configured == null) {
            music.remove(player.uniqueId)
        } else {
            music.sync(
                playerId = player.uniqueId,
                desiredSound = configured.sound,
                now = now,
                durationMillis = TimeUnit.SECONDS.toMillis(configured.durationSeconds.toLong()),
            )
        }
        transition.stopSound?.let { sound ->
            player.stopSound(musicSound(sound, 1.0f))
            debug.event("farm_music_stopped", "player" to player.name, "sound" to sound)
        }
        transition.playSound?.let { sound ->
            val volume = requireNotNull(configured).volume
            player.playSound(musicSound(sound, volume), AdventureSound.Emitter.self())
            debug.event(
                "farm_music_started",
                "player" to player.name,
                "zone" to runtime.settings.id,
                "sound" to sound,
                "duration_seconds" to configured.durationSeconds,
            )
        }
    }

    fun stopMusic(player: Player, reason: String) {
        music.remove(player.uniqueId).stopSound?.let { sound ->
            player.stopSound(musicSound(sound, 1.0f))
            debug.event("farm_music_stopped", "player" to player.name, "sound" to sound, "reason" to reason)
        }
    }

    fun stopAllMusic(reason: String) {
        music.clear().forEach { (playerId, sound) ->
            Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline)?.let { player ->
                player.stopSound(musicSound(sound, 1.0f))
                debug.event("farm_music_stopped", "player" to player.name, "sound" to sound, "reason" to reason)
            }
        }
    }

    fun removePlayer(player: Player, reason: String) {
        scoreboards.remove(player, reason)
        port.removePlayerBars(player)
        stopMusic(player, reason)
    }

    fun restoreAll(reason: String) = scoreboards.restoreAll(reason)
    fun hideBars() = port.hideAllBars()
    fun active(playerId: UUID): Boolean = scoreboards.active(playerId)
    fun title(playerId: UUID): String = scoreboards.tabTitle(playerId)
    fun line(playerId: UUID, line: Int): String = scoreboards.tabLine(playerId, line)

    fun playMilestone(runtime: FarmRuntime, sound: Sound, pitch: Float = 1.0f) {
        if (!settings().sounds) return
        players(runtime).forEach { it.playSound(it.location, sound, 0.85f, pitch) }
    }

    fun playStageFanfare(runtime: FarmRuntime, basePitch: Float) {
        if (!settings().sounds) return
        val recipients = players(runtime).map(Player::getUniqueId)
        recipients.mapNotNull(Bukkit::getPlayer).forEach { player ->
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.65f, basePitch)
        }
        port.runLater(4L) {
            recipients.mapNotNull(Bukkit::getPlayer).filter { runtime.region.contains(it.location) }.forEach { player ->
                player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, basePitch + 0.18f)
            }
        }
        port.runLater(8L) {
            recipients.mapNotNull(Bukkit::getPlayer).filter { runtime.region.contains(it.location) }.forEach { player ->
                player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.55f, basePitch + 0.32f)
            }
        }
    }

    private fun updateScoreboard(
        runtime: FarmRuntime,
        order: FarmOrder,
        player: Player,
        phaseDone: Int,
        phaseTotal: Int,
        carrying: Boolean,
        expected: MutableSet<UUID>,
    ) {
        if (!settings().farmScoreboard.enabled) return
        expected += player.uniqueId
        scoreboards.update(
            player,
            runtime.settings.id,
            FarmScoreboardView(
                orderId = order.id,
                phase = runtime.state.phase,
                done = phaseDone,
                total = phaseTotal,
                required = order.required,
                cropProgress = runtime.state.progress,
                careType = runtime.state.careType,
                seederStage = runtime.state.seederStage(),
                incidentType = runtime.state.incidentType,
                processingStage = runtime.state.processing?.stage,
                incidentCrop = runtime.state.processing?.crop ?: runtime.state.specialIncident?.crop,
                marketAccepted = runtime.state.specialIncident?.marketAccepted == true,
                carrying = carrying,
            ),
        )
    }

    private fun phaseDone(runtime: FarmRuntime, orderDone: Int): Int = when (runtime.state.phase) {
        FarmPhase.PREPARATION -> runtime.state.preparationProgress
        FarmPhase.PLANTING -> runtime.state.plantingProgress
        FarmPhase.CARE -> if (runtime.state.careType == FarmCareType.SEEDER) {
            if (runtime.state.seederStage() == FarmSeederStage.TILLING) runtime.state.preparationProgress else runtime.state.plantingProgress
        } else runtime.state.careProgress()
        FarmPhase.INCIDENT -> runtime.state.incidentProgress
        FarmPhase.DELIVERY -> runtime.state.deliveredCrates.size
        else -> orderDone
    }

    private fun phaseTotal(runtime: FarmRuntime, order: FarmOrder): Int = when (runtime.state.phase) {
        FarmPhase.PREPARATION, FarmPhase.PLANTING -> runtime.state.preparationRequired
        FarmPhase.CARE -> if (runtime.state.careType == FarmCareType.SEEDER) runtime.state.preparationRequired else runtime.state.careRequired()
        FarmPhase.INCIDENT -> runtime.state.incidentRequired
        FarmPhase.DELIVERY -> runtime.settings.delivery.crates
        else -> order.totalRequired
    }.coerceAtLeast(1)

    private fun bossBarKey(runtime: FarmRuntime, carrying: Boolean): MessageKey = when (runtime.state.phase) {
        FarmPhase.PREPARATION -> MessageKey.FARM_PREPARATION_BOSSBAR
        FarmPhase.PLANTING -> MessageKey.FARM_PLANTING_BOSSBAR
        FarmPhase.CARE -> MessageKey.FARM_CARE_BOSSBAR
        FarmPhase.INCIDENT -> when (runtime.state.incidentType ?: FarmIncidentType.PESTS) {
            FarmIncidentType.DROUGHT -> MessageKey.FARM_DROUGHT_BOSSBAR
            FarmIncidentType.PESTS -> MessageKey.FARM_INCIDENT_BOSSBAR
            FarmIncidentType.BIRDS -> MessageKey.FARM_BIRDS_BOSSBAR
            FarmIncidentType.FOOD_DELIVERY -> MessageKey.FARM_ROUTE_BOSSBAR
            FarmIncidentType.PROCESSING -> MessageKey.FARM_PROCESSING_BOSSBAR
            FarmIncidentType.BARN_FIRE -> MessageKey.FARM_BARN_FIRE_BOSSBAR
            FarmIncidentType.MARKET -> if (runtime.state.specialIncident?.marketAccepted == true) {
                MessageKey.FARM_MARKET_ACTIVE_BOSSBAR
            } else MessageKey.FARM_MARKET_PENDING_BOSSBAR
            else -> MessageKey.FARM_SPECIAL_BOSSBAR
        }
        FarmPhase.DELIVERY -> if (carrying) MessageKey.FARM_DELIVERY_CARRYING_BOSSBAR else MessageKey.FARM_DELIVERY_BOSSBAR
        else -> MessageKey.FARM_BOSSBAR
    }

    private fun bossBarColor(phase: FarmPhase): BossBar.Color = when (phase) {
        FarmPhase.PREPARATION -> BossBar.Color.WHITE
        FarmPhase.PLANTING -> BossBar.Color.GREEN
        FarmPhase.CARE -> BossBar.Color.BLUE
        FarmPhase.INCIDENT -> BossBar.Color.RED
        FarmPhase.DELIVERY -> BossBar.Color.PURPLE
        else -> BossBar.Color.GREEN
    }

    private fun activeCrop(runtime: FarmRuntime, order: FarmOrder): String? = when {
        runtime.state.phase == FarmPhase.PLANTING -> runtime.state.preparationCrop
        runtime.state.phase == FarmPhase.INCIDENT && runtime.state.incidentType == FarmIncidentType.PROCESSING ->
            runtime.state.processing?.crop ?: order.required.keys.firstOrNull()
        runtime.state.phase == FarmPhase.INCIDENT && runtime.state.incidentType == FarmIncidentType.MARKET ->
            runtime.state.specialIncident?.crop ?: order.required.keys.firstOrNull()
        else -> order.required.keys.firstOrNull()
    }

    private fun instruction(runtime: FarmRuntime, player: Player): Component {
        if (runtime.state.incidentType == FarmIncidentType.PROCESSING) {
            val key = when (runtime.state.processing?.stage) {
                FarmProcessingStage.LOADING -> MessageKey.FARM_PROCESSING_LOADING_HINT
                FarmProcessingStage.OPERATING -> MessageKey.FARM_PROCESSING_OPERATING_HINT
                FarmProcessingStage.PACKING -> MessageKey.FARM_PROCESSING_PACKING_HINT
                null -> MessageKey.FARM_PROCESSING_LOADING_HINT
            }
            return locale.render(key, player)
        }
        return runtime.state.careType?.let { type ->
            locale.renderPath(
                if (type == FarmCareType.SEEDER) seederInstructionPath(runtime.state) else "care.${type.name.lowercase()}.instruction",
                player,
                mapOf("total" to locale.text(runtime.state.careRequired())),
            )
        } ?: Component.empty()
    }

    private fun dynamicEntry(runtime: FarmRuntime, player: Player): Component = when (runtime.state.phase) {
        FarmPhase.CARE -> runtime.state.careType?.let {
            locale.renderPath(
                "care.${it.name.lowercase()}.entry",
                player,
                mapOf("total" to locale.text(runtime.state.careRequired())),
            )
        } ?: Component.empty()
        FarmPhase.INCIDENT -> runtime.state.incidentType?.takeIf { it in SPECIAL_FARM_INCIDENT_TYPES }?.let { type ->
            if (type == FarmIncidentType.MARKET) {
                runtime.state.specialIncident?.let { incident ->
                    locale.renderPath(
                        if (incident.marketAccepted) "farm.entry-market-active" else "farm.entry-market-pending",
                        player,
                        special.marketValues(runtime, incident, player),
                    )
                } ?: locale.renderPath("farm.entry-market", player)
            } else locale.renderPath("farm.entry-${special.id(type)}", player)
        } ?: Component.empty()
        else -> Component.empty()
    }

    private fun incidentHint(player: Player, runtime: FarmRuntime) {
        when (runtime.state.incidentType) {
            FarmIncidentType.DROUGHT -> port.sendActionBar(player, MessageKey.FARM_DROUGHT_REQUIRED)
            FarmIncidentType.BIRDS -> port.sendActionBar(player, MessageKey.FARM_BIRDS_REQUIRED)
            FarmIncidentType.FOOD_DELIVERY -> port.sendActionBar(player, MessageKey.FARM_ROUTE_REQUIRED)
            FarmIncidentType.PROCESSING -> port.sendActionBar(
                player,
                when (runtime.state.processing?.stage) {
                    FarmProcessingStage.LOADING -> MessageKey.FARM_PROCESSING_LOADING_HINT
                    FarmProcessingStage.OPERATING -> MessageKey.FARM_PROCESSING_OPERATING_HINT
                    FarmProcessingStage.PACKING -> MessageKey.FARM_PROCESSING_PACKING_HINT
                    null -> MessageKey.FARM_PROCESSING_LOADING_HINT
                },
            )
            FarmIncidentType.BARN_FIRE -> port.sendActionBar(player, MessageKey.FARM_BARN_FIRE_AIM_HINT)
            FarmIncidentType.MARKET -> {
                val incident = runtime.state.specialIncident ?: return
                port.sendActionBar(
                    player,
                    if (incident.marketAccepted) MessageKey.FARM_MARKET_ACTIVE else MessageKey.FARM_MARKET_REQUIRED,
                    special.marketValues(runtime, incident, player),
                )
            }
            FarmIncidentType.NIGHT_SHIFT -> port.sendActionBar(
                player,
                MessageKey.FARM_SPECIAL_PROGRESS,
                mapOf(
                    "event" to special.name(FarmIncidentType.NIGHT_SHIFT, player),
                    "done" to locale.text(runtime.state.incidentProgress),
                    "total" to locale.text(runtime.state.incidentRequired),
                ),
            )
            else -> port.sendActionBar(player, MessageKey.FARM_PESTS_REQUIRED)
        }
    }

    private fun requirements(runtime: FarmRuntime, order: FarmOrder): Component = Component.join(
        JoinConfiguration.commas(true),
        order.required.entries
            .filter { (crop, required) -> (runtime.state.progress[crop] ?: 0) < required }
            .map { (crop, required) ->
                MaterialRules.cropComponent(MaterialRules.material(crop))
                    .append(Component.space())
                    .append(Component.text("${runtime.state.progress[crop] ?: 0}/$required"))
            },
    )

    private fun seederInstructionPath(state: FarmShiftState): String = when (state.seederStage()) {
        FarmSeederStage.TILLING -> "care.seeder.tilling-instruction"
        FarmSeederStage.PLANTING -> "care.seeder.planting-instruction"
        null -> "care.seeder.instruction"
    }

    private fun currentOrder(runtime: FarmRuntime): FarmOrder? = runtime.state.orderId?.let(runtime.orders::get)
    private fun players(runtime: FarmRuntime): List<Player> =
        (port.players(runtime.region) + foodDelivery.participants(runtime)).distinctBy(Player::getUniqueId)
    private fun remainingSeconds(deadline: Long, now: Long): Long = ceil((deadline - now).coerceAtLeast(0) / 1_000.0).toLong()
    private fun musicSound(sound: String, volume: Float): AdventureSound = AdventureSound.sound(
        Key.key(sound),
        AdventureSound.Source.MUSIC,
        volume,
        1.0f,
    )

    private companion object {
        val VISIBLE_PHASES = setOf(
            FarmPhase.PREPARATION,
            FarmPhase.PLANTING,
            FarmPhase.CARE,
            FarmPhase.HARVESTING,
            FarmPhase.INCIDENT,
            FarmPhase.DELIVERY,
            FarmPhase.COOLDOWN,
        )
    }
}
