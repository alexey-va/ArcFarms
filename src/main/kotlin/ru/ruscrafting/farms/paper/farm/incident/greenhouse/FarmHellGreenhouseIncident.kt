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
import ru.ruscrafting.farms.config.FarmCareVisualSettings
import ru.ruscrafting.farms.config.FarmItemDisplayTransform
import ru.ruscrafting.farms.paper.farm.expedition.*
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

/** HELL_RIFT variant of the shared underground expedition; owns only the ritual and its room. */
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
    private data class Clock(val sequence: Long, val placement: Long, var ticks: Int = 0, var room: FarmMoleBurrowScene? = null, var charge: FarmHellRiftCharge = FarmHellRiftCharge())
    private val clocks = mutableMapOf<String, Clock>()
    private val scene = FarmHellGreenhouseScene(plugin, locale, text)
    private val expedition = FarmUndergroundExpedition(plugin, tasks, access, audience, state, FarmUndergroundVariant.HELL_RIFT,
        settings, locale, text)
    private val entrances = mutableMapOf<String, List<Entity>>()
    private val migrated = mutableSetOf<String>()
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
        if (current?.entrance != null && current.layoutVersion == 1) return true
        if (current?.entrance != null && migrated.add(runtime.settings.id)) {
            if (!evacuate(runtime)) { migrated.remove(runtime.settings.id); return true }
            rooms.beginRestore(runtime.region.world, runtime.settings.id, runtime.state.sequence)
        }
        if (rooms.restoring(runtime.settings.id)) return true
        val indexed = beds.discover(runtime)
        val candidates = expedition.surface.candidates(indexed,
            runtime.settings.moleBurrow.entranceMinBoundaryDistance,
            runtime.settings.moleBurrow.candidateAttempts,
            runtime.state.placementSequence xor 0x52494654L)
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
        val offsets = if (rules.quota <= 8) listOf(-2.0 to -3.0, 2.0 to 3.0, -2.0 to 3.0, 2.0 to -3.0,
            -2.0 to -1.0, 2.0 to 1.0, -2.0 to 1.0, 2.0 to -1.0)
        else List(8) { -3.5 + it }.flatMap { z -> listOf(-2.0 to z, 2.0 to -z) }
        val points = offsets.map { (x, z) -> FarmPointPosition(center.world.name, center.x + x, center.y, center.z + z) }
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
        return greenhouse.containsRoom(location.world.name, location.x, location.y, location.z)
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
        if (greenhouse.layoutVersion != 1) return
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
        ensureEntrance(runtime)
        if (clock.room?.ready != true) { clock.ticks++; return }
        reportedPauses.remove(runtime.settings.id)
        eligible.forEach { expedition.reconcile(it, inside(runtime, it)) }
        clock.ticks++
        if (clock.ticks % 20 == 0 && participants.isNotEmpty()) {
            val result = FarmHellGreenhouseEngine.second(greenhouse, participants.mapTo(linkedSetOf(), Player::getUniqueId), runtime.settings.specialIncidents.hellGreenhouse)
            apply(runtime, result, null)
            if (!active(runtime)) return
            updateHeat(runtime, participants)
        }
        advanceRitual(runtime, participants, clock)
        if (active(runtime)) scene.render(runtime, eligible, clock.ticks, settings().particles, clock.charge)
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
                if (room?.ready == true) expedition.enter(player, runtime, room)
                else audience.sendActionBar(player, MessageKey.FARM_MOLE_BUILDING)
            }
            return true
        }
        if (!inside(runtime, player)) return true
        if (target.role == HellGreenhouseRole.EXIT) {
            if (expedition.exit(player)) releasePlayer(player.uniqueId, listOf(runtime))
            return true
        }
        return true
    }

    private fun ensureEntrance(runtime: FarmRuntime) {
        val existing = entrances[runtime.settings.id]
        if (existing != null && existing.all { it.isValid && scene.identity(it)?.sequence == runtime.state.sequence &&
                scene.identity(it)?.placement == runtime.state.placementSequence }) return
        existing?.forEach(Entity::remove)
        val point = runtime.state.hellGreenhouse?.entrance ?: return
        entrances[runtime.settings.id] = expedition.surface.spawnEntry(runtime,
            Location(runtime.region.world, point.x, point.y, point.z),
            FarmCareVisualSettings("CRYING_OBSIDIAN", 0, FarmItemDisplayTransform.FIXED, 0.8f, 0.5),
            "farm.hell-greenhouse.entrance", true) { scene.mark(it, scene.stamp(runtime, HellGreenhouseRole.ENTRANCE)) }
    }

    private fun advanceRitual(runtime: FarmRuntime, players: List<Player>, clock: Clock) {
        val rift = runtime.state.hellGreenhouse ?: return
        val point = rift.points.getOrNull(rift.cooled) ?: return
        val hazard = FarmHellGreenhouseEngine.hazard(rift)
        val center = scene.center(runtime, rift)
        val occupants = players.filter { player ->
            val at = player.location
            abs(at.x - point.x) <= 0.65 && abs(at.z - point.z) <= 0.65 && abs(at.y - point.y) <= 0.4 &&
                !exposed(at, center, hazard)
        }.mapTo(linkedSetOf(), Player::getUniqueId)
        clock.charge = clock.charge.tick(occupants)
        if (clock.charge.ticks > 0 && clock.charge.ticks % 20 == 1) {
            org.bukkit.Bukkit.getPlayer(clock.charge.playerId!!)?.let { player ->
                audience.sendActionBar(player, MessageKey.FARM_HELL_RIFT_CHARGING,
                    mapOf("time" to locale.text(clock.charge.remainingSeconds)))
            }
        }
        if (!clock.charge.complete) return
        val actor = org.bukkit.Bukkit.getPlayer(clock.charge.playerId!!) ?: return
        clock.charge = FarmHellRiftCharge()
        val result = FarmHellGreenhouseEngine.seal(rift, rift.cooled, runtime.settings.specialIncidents.hellGreenhouse)
        apply(runtime, result, actor)
        if (result.accepted) {
            if (settings().sounds) actor.playSound(actor.location, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.8f, 1.2f)
            state.log(Level.INFO, "Hell rift rune sealed: zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                "player=${actor.name} rune=${rift.cooled + 1} quota=${runtime.settings.specialIncidents.hellGreenhouse.quota}")
        }
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
            runtime.region.world.players.filter { inside(runtime, it) }.forEach {
                audience.sendChat(it, message, values(runtime))
            }
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
        runtimes.forEach { runtime -> clocks[runtime.settings.id]?.let { if (it.charge.playerId == playerId) it.charge = FarmHellRiftCharge() } }
    }
    fun retains(player: Player) = expedition.retains(player)
    fun recoverPlayer(player: Player) = expedition.recover(player)
    fun quit(player: Player) = expedition.quit(player)
    fun participantRuntime(player: Player, runtimes: Collection<FarmRuntime>) = runtimes.firstOrNull { active(it) && inside(it, player) }

    fun processBlocks(runtimes: Collection<FarmRuntime>, budget: Int) {
        runtimes.forEach { activeRuntimes[it.settings.id] = it }
        rooms.process(budget) { record -> runtimes.any { it.settings.id == record.zoneId && it.ownsMoleBurrowRecord(record) } }
    }
    fun reconcileLoaded(runtimes: Collection<FarmRuntime>) {
        runtimes.forEach { activeRuntimes[it.settings.id] = it }
        rooms.reconcileLoaded { zone, sequence -> runtimes.any { it.settings.id == zone && it.state.sequence == sequence && active(it) && it.state.hellGreenhouse?.layoutVersion == 1 } }
    }
    fun onChunkLoad(chunk: Chunk, runtimes: Collection<FarmRuntime>) = rooms.onChunkLoad(chunk) { zone, sequence ->
        runtimes.any { it.settings.id == zone && it.state.sequence == sequence && active(it) && it.state.hellGreenhouse?.layoutVersion == 1 }
    }

    private fun updateHeat(runtime: FarmRuntime, participants: List<Player>) {
        val greenhouse = runtime.state.hellGreenhouse ?: return
        val hazard = FarmHellGreenhouseEngine.hazard(greenhouse)
        val center = scene.center(runtime, greenhouse)
        participants.forEach { player ->
            if (exposed(player.location, center, hazard) &&
                access.allowInteraction("rift-heat:${runtime.settings.id}:${player.uniqueId}", 2_000L)) {
                player.velocity = Vector(if (player.location.x < center.x) 0.35 else -0.35, 0.15, 0.0)
                audience.sendActionBar(player, MessageKey.FARM_HELL_GREENHOUSE_HOT_BURST)
                if (settings().sounds) player.playSound(player.location, Sound.BLOCK_FIRE_EXTINGUISH, 0.7f, 1f)
            } else if (clocks[runtime.settings.id]?.charge?.playerId != player.uniqueId) {
                val key = when (hazard.phase) {
                    FarmHellHazardPhase.WARNING -> MessageKey.FARM_HELL_GREENHOUSE_HEAT_WARNING
                    FarmHellHazardPhase.ACTIVE -> MessageKey.FARM_HELL_GREENHOUSE_HEAT_ACTIVE
                    FarmHellHazardPhase.REST -> MessageKey.FARM_HELL_GREENHOUSE_REQUIRED
                }
                audience.sendActionBar(player, key, values(runtime) + mapOf("time" to locale.text(hazard.secondsRemaining),
                    "side" to locale.render(if (hazard.side == FarmHellHazardSide.LEFT) MessageKey.FARM_HELL_GREENHOUSE_LEFT else MessageKey.FARM_HELL_GREENHOUSE_RIGHT, player)))
            }
        }
    }

    private fun exposed(at: Location, center: Location, hazard: FarmHellHazard): Boolean =
        hazard.phase == FarmHellHazardPhase.ACTIVE && abs(at.z - center.z) <= 4.5 &&
            ((hazard.side == FarmHellHazardSide.LEFT && at.x - center.x in -3.5..-1.5) ||
                (hazard.side == FarmHellHazardSide.RIGHT && at.x - center.x in 1.5..3.5))

    private fun evacuate(runtime: FarmRuntime): Boolean {
        var safe = expedition.evacuate(runtime.settings.id)
        val entrance = runtime.state.hellGreenhouse?.entrance
        if (entrance != null) runtime.region.world.players.filter { inside(runtime, it) }.forEach {
            if (!expedition.returnToSurface(it, runtime, entrance)) safe = false
        }
        return safe
    }

    fun clear(runtime: FarmRuntime) {
        val sequence = clocks[runtime.settings.id]?.sequence ?: runtime.state.sequence
        if (!evacuate(runtime)) return
        clocks.remove(runtime.settings.id)
        entrances.remove(runtime.settings.id)?.forEach(Entity::remove)
        scene.clear(runtime.settings.id)
        if (restoreRequested.put(runtime.settings.id, sequence) != sequence) {
            rooms.beginRestore(runtime.region.world, runtime.settings.id, sequence)
        }
    }
    fun cleanup() {
        activeRuntimes.values.forEach(::clear)
        rooms.clearQueues() // Unrestored chunk journals remain durable for the next startup.
        entrances.values.flatten().forEach(Entity::remove)
        entrances.clear()
        scene.cleanup()
        reportedPauses.clear()
    }
}
