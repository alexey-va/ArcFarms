package ru.ruscrafting.farms.paper.farm.incident.greenhouse

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.*
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.block
import ru.ruscrafting.farms.paper.farm.FarmIncidentBedProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayRenderer
import ru.ruscrafting.farms.paper.platform.PaperFarmTextDisplayRenderer
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import java.util.UUID
import java.util.logging.Level
import kotlin.math.abs

/** Complete owner of the generated greenhouse, pepper delivery and completion lifecycle. */
internal class FarmHellGreenhouseIncident(
    plugin: Plugin,
    private val settings: () -> ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val state: WorksiteStatePort,
    private val beds: FarmIncidentBedProvider,
    private val transitions: FarmTransitionSink,
    ledger: FarmBlockLedger,
    text: FarmTextDisplayRenderer = PaperFarmTextDisplayRenderer,
) {
    private data class Clock(val sequence: Long, val placement: Long, var ticks: Int = 0)
    private val clocks = mutableMapOf<String, Clock>()
    private val scene = FarmHellGreenhouseScene(plugin, locale, text)
    private val placement = FarmGreenhousePlacement(ledger)
    private val reportedPauses = mutableMapOf<String, String>()

    fun owns(entity: Entity): Boolean = scene.owns(entity)
    fun identity(entity: Entity): HellGreenhouseIdentity? = scene.identity(entity)
    private fun active(runtime: FarmRuntime) = runtime.state.phase == FarmPhase.INCIDENT && runtime.state.incidentType == FarmIncidentType.HELL_GREENHOUSE

    fun initialize(runtime: FarmRuntime, actor: Player? = null): Boolean {
        if (!active(runtime)) return false
        if (runtime.state.hellGreenhouse != null) return true
        val indexed = beds.discover(runtime)
        val middleX = indexed.map { it.x }.average()
        val middleZ = indexed.map { it.z }.average()
        // Sample the entire loaded index if it exceeds the bounded search, never just its central cluster.
        val ordered = indexed.sortedWith(compareBy({ it.x }, { it.z }, { it.y }))
        val candidates = if (ordered.size <= 4096) ordered else List(4096) { ordered[it * ordered.size / 4096] }
        val ranked = candidates.sortedBy { (it.x - middleX) * (it.x - middleX) + (it.z - middleZ) * (it.z - middleZ) }
        val rejections = linkedMapOf<String, Pair<Int, GreenhouseObstruction>>()
        fun reject(issue: GreenhouseObstruction) {
            val previous = rejections[issue.reason]
            rejections[issue.reason] = (previous?.first?.plus(1) ?: 1) to (previous?.second ?: issue)
        }
        var checked = 0
        var chosen: GreenhouseSite? = null
        for (plot in ranked) {
            checked++
            val soil = plot.block()
            if (soil == null) {
                reject(GreenhouseObstruction("unloaded", at = Location(runtime.region.world, plot.x.toDouble(), plot.y.toDouble(), plot.z.toDouble())))
                continue
            }
            val site = placement.inspect(runtime, Location(soil.world, soil.x + 0.5, soil.y + 1.0, soil.z + 0.5))
            if (site.failure == null) { chosen = site; break }
            reject(site.failure)
        }
        if (chosen == null) {
            if (indexed.isEmpty()) reject(GreenhouseObstruction("no-beds"))
            if (candidates.size < indexed.size) reject(GreenhouseObstruction("search-limit"))
            state.log(Level.WARNING, "Farm greenhouse placement rejected: zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                "attempted=HELL_GREENHOUSE indexed=${indexed.size} checked=$checked " +
                "rejections=${rejections.values.joinToString("; ") { (count, issue) -> "count=$count ${issue.describe()}" }}")
            admins(runtime, actor).forEach { player ->
                audience.sendChat(player, MessageKey.ADMIN_GREENHOUSE_FAILED, mapOf(
                    "zone" to locale.text(runtime.settings.id), "candidates" to locale.text(checked), "total" to locale.text(indexed.size)))
                rejections.values.sortedByDescending { it.first }.take(3).forEach { (count, issue) ->
                    reportIssue(player, issue, count)
                }
            }
            return false
        }
        placement.prepare(runtime, chosen)
        val center = chosen.center
        val rules = runtime.settings.specialIncidents.hellGreenhouse
        val rows = if (rules.quota > 8) List(8) { -3.5 + it } else listOf(-3.0, -1.0, 1.0, 3.0)
        val points = listOf(-2, 2).flatMap { x -> rows.map { z ->
            FarmPointPosition(center.world.name, center.x + x, center.y, center.z + z)
        } }
        val initialized = FarmHellGreenhouseEngine.initialize(FarmHellGreenhouseState(points), rules)
        runtime.state = runtime.state.copy(hellGreenhouse = initialized.state, incidentProgress = 0, incidentRequired = rules.quota)
        reportedPauses.remove(runtime.settings.id)
        state.log(Level.INFO, "Farm greenhouse placement accepted: zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
            "indexed=${indexed.size} checked=$checked center=${center.blockX},${center.blockY},${center.blockZ} prepared_beds=${chosen.edits.size}")
        state.persistAsync()
        return true
    }

    private fun admins(runtime: FarmRuntime, actor: Player? = null) =
        (audience.players(runtime.region) + listOfNotNull(actor)).distinctBy(Player::getUniqueId)
            .filter { it.isOnline && it.hasPermission("arcfarms.admin") }

    private fun reportIssue(player: Player, issue: GreenhouseObstruction, count: Int = 1) {
        audience.sendChat(player, MessageKey.ADMIN_GREENHOUSE_REASON, mapOf(
            "reason" to locale.renderPath("admin.greenhouse-reasons.${issue.reason}", player),
            "at" to locale.text(issue.at?.let { "${it.world.name}: ${it.blockX}, ${it.blockY}, ${it.blockZ}" } ?: "—"),
            "material" to locale.text(issue.block?.type?.name ?: "—"), "count" to locale.text(count),
        ))
    }

    private fun reportPause(runtime: FarmRuntime, reason: String, issue: GreenhouseObstruction? = null) {
        val signature = "${runtime.state.sequence}:${runtime.state.placementSequence}:$reason:${issue?.describe()}"
        if (reportedPauses.put(runtime.settings.id, signature) == signature) return
        state.log(Level.WARNING, "Farm greenhouse paused: zone=${runtime.settings.id} sequence=${runtime.state.sequence} reason=$reason ${issue?.describe().orEmpty()}")
        admins(runtime).forEach { player ->
            audience.sendChat(player, MessageKey.ADMIN_GREENHOUSE_PAUSED,
                mapOf("reason" to locale.renderPath("admin.greenhouse-reasons.$reason", player)))
            if (issue != null) reportIssue(player, issue)
        }
    }

    private fun eligible(runtime: FarmRuntime, player: Player): Boolean = player.isOnline && !player.isDead &&
        player.gameMode != GameMode.SPECTATOR && !access.isAdminEditing(player) &&
        runtime.region.contains(player.location) && access.hasAccess(player, runtime.settings.permission)

    private fun inside(runtime: FarmRuntime, player: Player): Boolean {
        val greenhouse = runtime.state.hellGreenhouse ?: return false
        val center = scene.center(runtime, greenhouse)
        val location = player.location
        return location.world === center.world && abs(location.x - center.x) <= 4.8 &&
            abs(location.z - center.z) <= 5.8 && abs(location.y - center.y) <= 3.0
    }

    /** Called by the existing supervised per-tick visual loop; no scheduler is owned here. */
    fun update(runtime: FarmRuntime) {
        if (!active(runtime)) { clear(runtime); reportedPauses.remove(runtime.settings.id); return }
        val viewers = audience.players(runtime.region)
        if (viewers.any(access::isAdminEditing)) { reportPause(runtime, "admin-editing"); clear(runtime); return }
        val eligible = viewers.filter { eligible(runtime, it) }
        if (eligible.isEmpty()) { reportPause(runtime, "no-participants"); clear(runtime); return }
        if (runtime.state.hellGreenhouse == null && !initialize(runtime)) {
            transitions.apply(runtime, FarmShiftEngine.skipUnavailableIncident(runtime.state, FarmIncidentType.HELL_GREENHOUSE), null)
            state.persistAsync()
            return
        }
        val greenhouse = runtime.state.hellGreenhouse ?: return
        val center = scene.center(runtime, greenhouse)
        val participants = eligible.filter { inside(runtime, it) }
        val clock = clocks.getOrPut(runtime.settings.id) { Clock(runtime.state.sequence, runtime.state.placementSequence) }
        if (clock.sequence != runtime.state.sequence || clock.placement != runtime.state.placementSequence) {
            clear(runtime)
            return
        }
        // The footprint is revalidated once per second, not on each visual tick.
        if (clock.ticks % 20 == 0) {
            val site = placement.inspect(runtime, center)
            if (site.failure != null) { reportPause(runtime, site.failure.reason, site.failure); clear(runtime); return }
            placement.prepare(runtime, site)
            reportedPauses.remove(runtime.settings.id)
        }
        clock.ticks++
        if (clock.ticks % 20 == 0 && participants.isNotEmpty()) {
            val result = FarmHellGreenhouseEngine.second(greenhouse, participants.mapTo(linkedSetOf(), Player::getUniqueId), runtime.settings.specialIncidents.hellGreenhouse)
            apply(runtime, result, null)
            if (!active(runtime)) return
        }
        scene.render(runtime, eligible, clock.ticks, settings().particles)
    }

    fun interact(event: PlayerInteractEntityEvent, runtimes: Collection<FarmRuntime>): Boolean {
        val target = scene.identity(event.rightClicked) ?: return false
        event.isCancelled = true
        if (event.hand != EquipmentSlot.HAND) return true
        val runtime = runtimes.firstOrNull { it.settings.id == target.zone } ?: return true
        val player = event.player
        if (!active(runtime) || target.sequence != runtime.state.sequence || target.placement != runtime.state.placementSequence ||
            !event.rightClicked.isValid || !eligible(runtime, player) || !inside(runtime, player) ||
            player.world !== event.rightClicked.world || player.location.distanceSquared(event.rightClicked.location) > 16.0
        ) return true
        val current = runtime.state.hellGreenhouse ?: return true
        val rules = runtime.settings.specialIncidents.hellGreenhouse
        val result = when (target.role) {
            HellGreenhouseRole.PEPPER -> {
                if (target.index !in current.points.indices || target.index in current.harvested) return true
                FarmHellGreenhouseEngine.pick(current, player.uniqueId, target.index, rules)
            }
            HellGreenhouseRole.VAT -> FarmHellGreenhouseEngine.cool(current, player.uniqueId, rules)
            HellGreenhouseRole.SCENE -> return true
        }
        if (!result.accepted) {
            val message = when {
                target.role == HellGreenhouseRole.VAT -> MessageKey.FARM_HELL_GREENHOUSE_EMPTY_HANDS
                player.uniqueId in current.carried -> MessageKey.FARM_HELL_GREENHOUSE_HANDS_FULL
                else -> MessageKey.FARM_HELL_GREENHOUSE_TOO_EARLY
            }
            audience.sendActionBar(player, message, values(runtime))
            return true
        }
        apply(runtime, result, player)
        if (active(runtime)) {
            audience.sendActionBar(player, if (target.role == HellGreenhouseRole.VAT) MessageKey.FARM_HELL_GREENHOUSE_COOLED else MessageKey.FARM_HELL_GREENHOUSE_PICKED, values(runtime))
            scene.render(runtime, audience.players(runtime.region).filter { eligible(runtime, it) }, clocks[runtime.settings.id]?.ticks ?: 0, settings().particles)
        }
        return true
    }

    private fun apply(runtime: FarmRuntime, result: FarmHellGreenhouseResult, actor: Player?) {
        if (!result.accepted) return
        val options = runtime.settings.specialIncidents.hellGreenhouse
        val next = runtime.state.copy(hellGreenhouse = result.state, incidentProgress = result.state.cooled.coerceAtMost(options.quota),
            contributors = if (actor != null && result.contribution > 0) incrementContribution(runtime.state.contributors, actor.uniqueId, result.contribution) else runtime.state.contributors)
        if (result.finished) {
            val message = MessageKey.FARM_HELL_GREENHOUSE_SUCCESS
            runtime.state = next
            audience.broadcast(listOf(runtime.region), message, values(runtime), sound = if (result.successful) Sound.ENTITY_PLAYER_LEVELUP else Sound.BLOCK_FIRE_EXTINGUISH)
            clear(runtime)
            transitions.apply(runtime, FarmShiftEngine.completeIncident(next, result.contribution), actor)
        } else {
            transitions.apply(runtime, EngineResult(next, true, contribution = result.contribution), actor)

        }
        state.persistAsync()
    }

    private fun values(runtime: FarmRuntime) = mapOf(
        "done" to locale.text(runtime.state.hellGreenhouse?.cooled ?: 0),
        "total" to locale.text(runtime.settings.specialIncidents.hellGreenhouse.quota),
        "heat" to locale.text(runtime.state.hellGreenhouse?.heat ?: 0),
        "time" to locale.text(runtime.state.hellGreenhouse?.evacuationSeconds ?: runtime.settings.specialIncidents.hellGreenhouse.hotSeconds),
    )

    fun releasePlayer(playerId: UUID, runtimes: Collection<FarmRuntime>) {
        runtimes.filter(::active).forEach { runtime ->
            runtime.state.hellGreenhouse?.let { current ->
                apply(runtime, FarmHellGreenhouseEngine.release(current, playerId, runtime.settings.specialIncidents.hellGreenhouse), null)
            }
        }
    }
    fun clear(runtime: FarmRuntime) { clocks.remove(runtime.settings.id); scene.clear(runtime.settings.id); placement.clear(runtime.settings.id) }
    fun cleanup() { clocks.clear(); scene.cleanup(); placement.cleanup(); reportedPauses.clear() }
}
