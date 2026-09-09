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
import ru.ruscrafting.farms.paper.farm.care.mole.*
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import org.bukkit.Chunk
import org.bukkit.util.Vector
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
    private val rooms: FarmMoleBurrowWorld,
    tasks: WorksiteTaskPort,
) {
    private data class Clock(val sequence: Long, val placement: Long, var ticks: Int = 0, var room: FarmMoleBurrowScene? = null)
    private val clocks = mutableMapOf<String, Clock>()
    private val scene = FarmHellGreenhouseScene(plugin, locale, text)
    private val travel = FarmGreenhouseTravel(plugin, tasks, access, audience, state)
    private val legacyLedger = ledger
    private val activeRuntimes = mutableMapOf<String, FarmRuntime>()
    private val restoreRequested = mutableMapOf<String, Long>()
    private val reportedPauses = mutableMapOf<String, String>()

    fun owns(entity: Entity): Boolean = scene.owns(entity)
    fun identity(entity: Entity): HellGreenhouseIdentity? = scene.identity(entity)
    private fun active(runtime: FarmRuntime) = runtime.state.phase == FarmPhase.INCIDENT && runtime.state.incidentType == FarmIncidentType.HELL_GREENHOUSE

    fun initialize(runtime: FarmRuntime, actor: Player? = null): Boolean {
        if (!active(runtime)) return false
        activeRuntimes[runtime.settings.id] = runtime
        val current = runtime.state.hellGreenhouse
        if (current?.entrance != null) return true
        val indexed = beds.discover(runtime)
        val ordered = indexed.sortedWith(compareBy({ it.x }, { it.z }))
        val sampled = if (ordered.size <= 256) ordered else List(256) { ordered[it * ordered.size / 256] }
        val candidates = if (current != null) listOf(scene.center(runtime, current).let {
            FarmPointPosition(it.world.name, it.x, it.y, it.z)
        }) else sampled.map {
            FarmPointPosition(it.world, it.x + 0.5, it.y + 1.0, it.z + 0.5)
        }
        val rejections = linkedMapOf<String, Int>()
        var chosen: FarmMoleBurrowScene? = null
        for (surface in candidates) {
            val preview = rooms.previewGreenhouseChamberDetailed(runtime, surface)
            if (preview.scene != null) { chosen = preview.scene; break }
            preview.rejections.forEach { (reason, count) -> rejections[reason] = rejections.getOrDefault(reason, 0) + count }
        }
        val room = chosen ?: run {
            state.log(Level.WARNING, "Underground greenhouse unavailable: zone=${runtime.settings.id} " +
                "sequence=${runtime.state.sequence} candidates=${candidates.size} rejections=$rejections")
            return false
        }
        val center = room.start
        val rules = runtime.settings.specialIncidents.hellGreenhouse
        val rows = if (rules.quota > 8) List(8) { -3.5 + it } else listOf(-3.0, -1.0, 1.0, 3.0)
        val points = if (current == null) listOf(-2, 2).flatMap { x -> rows.map { z ->
            FarmPointPosition(center.world.name, center.x + x, center.y, center.z + z)
        } } else {
            val previous = scene.center(runtime, current)
            current.points.map { it.copy(x = center.x + it.x - previous.x, y = center.y, z = center.z + it.z - previous.z) }
        }
        val surface = room.surface.let { FarmPointPosition(it.world.name, it.x, it.y, it.z) }
        val initialized = FarmHellGreenhouseEngine.initialize(
            (current ?: FarmHellGreenhouseState(points)).copy(points = points, entrance = surface), rules)
        runtime.state = runtime.state.copy(hellGreenhouse = initialized.state,
            incidentProgress = initialized.state.cooled, incidentRequired = rules.quota)
        if (current != null) legacyLedger.restoreTemporaryRemovals(indexed.mapNotNull { it.block() }, "greenhouse:${runtime.settings.id}")
        reportedPauses.remove(runtime.settings.id)
        state.log(Level.INFO, "Underground greenhouse planned: zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
            "surface=$surface center=$center preserved_progress=${initialized.state.cooled}")
        state.persistAsync()
        return true
    }

    private fun reportPause(runtime: FarmRuntime, reason: String) {
        val signature = "${runtime.state.sequence}:${runtime.state.placementSequence}:$reason"
        if (reportedPauses.put(runtime.settings.id, signature) == signature) return
        state.log(Level.WARNING, "Farm greenhouse paused: zone=${runtime.settings.id} sequence=${runtime.state.sequence} reason=$reason")

    }

    private fun eligible(runtime: FarmRuntime, player: Player): Boolean = player.isOnline && !player.isDead &&
        player.gameMode != GameMode.SPECTATOR && !access.isAdminEditing(player) &&
        (runtime.region.contains(player.location) || inside(runtime, player)) && access.hasAccess(player, runtime.settings.permission)

    fun inside(runtime: FarmRuntime, player: Player): Boolean = contains(runtime, player.location)

    fun contains(runtime: FarmRuntime, location: Location): Boolean {
        val greenhouse = runtime.state.hellGreenhouse ?: return false
        val center = scene.center(runtime, greenhouse)
        return greenhouse.entrance != null && location.world === center.world && abs(location.x - center.x) <= 4.8 &&
            abs(location.z - center.z) <= 5.8 && abs(location.y - center.y) <= 3.0
    }

    fun protects(runtime: FarmRuntime, location: Location): Boolean {
        val greenhouse = runtime.state.hellGreenhouse ?: return false
        if (greenhouse.entrance == null) return false
        val center = scene.center(runtime, greenhouse)
        return location.world === center.world && location.blockX in center.blockX - 5..center.blockX + 5 &&
            location.blockZ in center.blockZ - 6..center.blockZ + 6 && location.blockY in center.blockY - 1..center.blockY + 5
    }

    /** Called by the existing supervised per-tick visual loop; no scheduler is owned here. */
    fun update(runtime: FarmRuntime) {
        if (!active(runtime)) { clear(runtime); reportedPauses.remove(runtime.settings.id); return }
        activeRuntimes[runtime.settings.id] = runtime
        runtime.state.hellGreenhouse?.takeIf { it.finished || it.cooled >= runtime.settings.specialIncidents.hellGreenhouse.quota }?.let {
            apply(runtime, FarmHellGreenhouseResult(it.copy(finished = true, carried = emptyMap()),
                accepted = true, finished = true, successful = true), null)
            return
        }
        val viewers = (audience.players(runtime.region) + runtime.region.world.players.filter { inside(runtime, it) }).distinctBy(Player::getUniqueId)
        if (viewers.any(access::isAdminEditing)) { reportPause(runtime, "admin-editing"); clear(runtime); return }
        val eligible = viewers.filter { eligible(runtime, it) }
        if (eligible.isEmpty()) { reportPause(runtime, "no-participants"); clear(runtime); return }
        if (!initialize(runtime)) {
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
        val entrance = greenhouse.entrance ?: return
        restoreRequested.remove(runtime.settings.id)
        if (clock.room == null) {
            val (roomStatus, room) = rooms.ensureGreenhouseChamber(runtime, entrance)
            if (roomStatus == FarmMoleBurrowEnsureResult.UNAVAILABLE) {
                reportPause(runtime, "obstruction")
                return
            }
            clock.room = room
        }
        if (clock.room?.ready != true) return
        reportedPauses.remove(runtime.settings.id)
        eligible.forEach { travel.reconcile(it, inside(runtime, it)) }
        clock.ticks++
        if (clock.ticks % 20 == 0 && participants.isNotEmpty()) {
            val result = FarmHellGreenhouseEngine.second(greenhouse, participants.mapTo(linkedSetOf(), Player::getUniqueId), runtime.settings.specialIncidents.hellGreenhouse)
            apply(runtime, result, null)
            if (!active(runtime)) return
            updateHeat(runtime, participants)
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
            !event.rightClicked.isValid || !eligible(runtime, player) ||
            player.world !== event.rightClicked.world || player.location.distanceSquared(event.rightClicked.location) > 16.0
        ) return true
        val current = runtime.state.hellGreenhouse ?: return true
        if (target.role == HellGreenhouseRole.ENTRANCE) {
            current.entrance?.let { entrance ->
                val room = clocks[runtime.settings.id]?.room
                if (room?.ready == true) travel.enter(player, runtime, room)
            }
            return true
        }
        if (!inside(runtime, player)) return true
        if (target.role == HellGreenhouseRole.EXIT) {
            if (travel.exit(player)) releasePlayer(player.uniqueId, listOf(runtime))
            return true
        }
        val rules = runtime.settings.specialIncidents.hellGreenhouse
        val result = when (target.role) {
            HellGreenhouseRole.PEPPER -> {
                if (target.index !in current.points.indices || target.index in current.harvested) return true
                val center = scene.center(runtime, current)
                val point = current.points[target.index]
                val dx = player.location.x - center.x
                val plant = Location(player.world, point.x, point.y, point.z)
                if (player.location.distanceSquared(plant) > 2.56 || abs(dx) < 1.25) return true
                val hazard = FarmHellGreenhouseEngine.hazard(current)
                if (hazard.phase == FarmHellHazardPhase.ACTIVE &&
                    ((dx < 0 && hazard.side == FarmHellHazardSide.LEFT) || (dx > 0 && hazard.side == FarmHellHazardSide.RIGHT))) {
                    audience.sendActionBar(player, MessageKey.FARM_HELL_GREENHOUSE_HOT_BURST)
                    return true
                }
                FarmHellGreenhouseEngine.pick(current, player.uniqueId, target.index, rules)
            }
            HellGreenhouseRole.VAT -> FarmHellGreenhouseEngine.cool(current, player.uniqueId, rules)
            HellGreenhouseRole.SCENE, HellGreenhouseRole.ENTRANCE, HellGreenhouseRole.EXIT -> return true
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
        result.expiredPlayerIds.forEach { id -> org.bukkit.Bukkit.getPlayer(id)?.let {
            audience.sendActionBar(it, MessageKey.FARM_HELL_GREENHOUSE_HOT_BURST)
        } }
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
    fun retains(player: Player) = travel.retains(player)
    fun recoverPlayer(player: Player) = travel.recover(player)
    fun quit(player: Player) = travel.quit(player)
    fun participantRuntime(player: Player, runtimes: Collection<FarmRuntime>) = runtimes.firstOrNull { active(it) && inside(it, player) }

    fun processBlocks(runtimes: Collection<FarmRuntime>, budget: Int) {
        runtimes.forEach { activeRuntimes[it.settings.id] = it }
        rooms.process(budget) { record -> runtimes.any { it.settings.id == record.zoneId && it.ownsMoleBurrowRecord(record) } }
    }
    fun reconcileLoaded(runtimes: Collection<FarmRuntime>) {
        runtimes.forEach { activeRuntimes[it.settings.id] = it }
        rooms.reconcileLoaded { zone, sequence -> runtimes.any { it.settings.id == zone && it.state.sequence == sequence && active(it) } }
    }
    fun onChunkLoad(chunk: Chunk, runtimes: Collection<FarmRuntime>) = rooms.onChunkLoad(chunk) { zone, sequence ->
        runtimes.any { it.settings.id == zone && it.state.sequence == sequence && active(it) }
    }

    private fun updateHeat(runtime: FarmRuntime, participants: List<Player>) {
        val greenhouse = runtime.state.hellGreenhouse ?: return
        val hazard = FarmHellGreenhouseEngine.hazard(greenhouse)
        val center = scene.center(runtime, greenhouse)
        participants.forEach { player ->
            val dx = player.location.x - center.x
            val exposed = abs(player.location.z - center.z) <= 4.0 &&
                ((hazard.side == FarmHellHazardSide.LEFT && dx in -3.0..-1.25) ||
                    (hazard.side == FarmHellHazardSide.RIGHT && dx in 1.25..3.0))
            if (hazard.phase == FarmHellHazardPhase.ACTIVE && exposed &&
                access.allowInteraction("greenhouse-heat:${runtime.settings.id}:${player.uniqueId}", 2_000L)) {
                apply(runtime, FarmHellGreenhouseEngine.release(runtime.state.hellGreenhouse ?: return,
                    player.uniqueId, runtime.settings.specialIncidents.hellGreenhouse), null)
                player.velocity = Vector(if (dx < 0) 0.35 else -0.35, 0.15, 0.0)
                audience.sendActionBar(player, MessageKey.FARM_HELL_GREENHOUSE_HOT_BURST)
                if (settings().sounds) player.playSound(player.location, Sound.BLOCK_FIRE_EXTINGUISH, 0.7f, 1f)
            } else {
                val carried = runtime.state.hellGreenhouse?.carried?.get(player.uniqueId)
                val key = if (carried != null) MessageKey.FARM_HELL_GREENHOUSE_PICKED else when (hazard.phase) {
                    FarmHellHazardPhase.WARNING -> MessageKey.FARM_HELL_GREENHOUSE_HEAT_WARNING
                    FarmHellHazardPhase.ACTIVE -> MessageKey.FARM_HELL_GREENHOUSE_HEAT_ACTIVE
                    FarmHellHazardPhase.REST -> MessageKey.FARM_HELL_GREENHOUSE_REQUIRED
                }
                audience.sendActionBar(player, key, values(runtime) + mapOf(
                    "time" to locale.text(carried?.let { (it.expiresAt - greenhouse.elapsedSeconds).coerceAtLeast(0) } ?: hazard.secondsRemaining),
                    "side" to locale.render(if (hazard.side == FarmHellHazardSide.LEFT) MessageKey.FARM_HELL_GREENHOUSE_LEFT else MessageKey.FARM_HELL_GREENHOUSE_RIGHT, player)))
            }
        }
    }

    fun clear(runtime: FarmRuntime) {
        val sequence = clocks[runtime.settings.id]?.sequence ?: runtime.state.sequence
        if (!travel.evacuate(runtime.settings.id)) return
        clocks.remove(runtime.settings.id)
        scene.clear(runtime.settings.id)
        if (restoreRequested.put(runtime.settings.id, sequence) != sequence) {
            rooms.beginRestore(runtime.region.world, runtime.settings.id, sequence)
        }
    }
    fun cleanup() {
        activeRuntimes.values.forEach(::clear)
        rooms.clearQueues() // Unrestored chunk journals remain durable for the next startup.
        scene.cleanup()
        reportedPauses.clear()
    }
}
