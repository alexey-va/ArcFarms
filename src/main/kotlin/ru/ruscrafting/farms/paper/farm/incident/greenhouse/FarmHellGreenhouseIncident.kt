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

/** HELL_RIFT variant of the shared underground expedition; owns only the plantation and its room. */
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
        if (current?.entrance != null && current.layoutVersion == FarmHellRiftRoom.LAYOUT_VERSION) return true
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
            state.log(Level.WARNING, "Underground plantation unavailable: zone=${runtime.settings.id} " +
                "sequence=${runtime.state.sequence} candidates=${candidates.size} rejections=$rejections")
            return false
        }
        val center = room.start
        val rules = runtime.settings.specialIncidents.hellGreenhouse
        val points = FarmHellRiftRoom.bedOffsets.map { (x, z) ->
            FarmPointPosition(center.world.name, center.x + x, center.y, center.z + z)
        }
        val surface = room.surface.let { FarmPointPosition(it.world.name, it.x, it.y, it.z) }
        current?.let(FarmHellGreenhouseEngine::validate)
        val initialized = FarmHellGreenhouseEngine.initialize(
            FarmHellGreenhouseState(points = points, entrance = surface, cooled = current?.cooled ?: 0,
                layoutVersion = FarmHellRiftRoom.LAYOUT_VERSION,
                plots = current?.takeIf { it.layoutVersion == FarmHellRiftRoom.LAYOUT_VERSION }?.plots
                    ?: List(FarmHellGreenhouseEngine.PHYSICAL_PLOTS) { FarmHellPlantationPlot() }), rules)
        runtime.state = runtime.state.copy(hellGreenhouse = initialized.state,
            incidentProgress = initialized.state.cooled, incidentRequired = rules.quota)
        if (current != null) legacyLedger.restoreTemporaryRemovals(indexed.mapNotNull { it.block() }, "greenhouse:${runtime.settings.id}")
        reportedPauses.remove(runtime.settings.id)
        state.log(Level.INFO, "Underground plantation planned: zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
            "surface=$surface center=$center preserved_progress=${initialized.state.cooled}")
        state.persistAsync()
        return true
    }

    private fun reportPause(runtime: FarmRuntime, reason: String) {
        val signature = "${runtime.state.sequence}:${runtime.state.placementSequence}:$reason"
        if (reportedPauses.put(runtime.settings.id, signature) == signature) return
        state.log(Level.WARNING, "Farm plantation paused: zone=${runtime.settings.id} sequence=${runtime.state.sequence} reason=$reason")

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
        return location.world === center.world && location.blockX in center.blockX - FarmHellRiftRoom.HALF_WIDTH..center.blockX + FarmHellRiftRoom.HALF_WIDTH &&
            location.blockZ in center.blockZ - FarmHellRiftRoom.HALF_LENGTH..center.blockZ + FarmHellRiftRoom.HALF_LENGTH && location.blockY in center.blockY - 1..center.blockY + FarmHellRiftRoom.CEILING
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
        if (greenhouse.layoutVersion != FarmHellRiftRoom.LAYOUT_VERSION) return
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
            if (result.scorchedPlots.isEmpty()) updateInstructions(runtime, participants)
        }
        if (active(runtime)) scene.render(runtime, participants, clock.ticks, settings().particles)
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
        val rules = runtime.settings.specialIncidents.hellGreenhouse
        val result = when (target.role) {
            HellGreenhouseRole.VALVE -> FarmHellGreenhouseEngine.toggleHeat(current, target.index, rules)
            HellGreenhouseRole.CROP -> FarmHellGreenhouseEngine.harvest(current, target.index, rules)
            else -> return true
        }
        apply(runtime, result, player)
        if (result.accepted) {
            if (settings().sounds) player.playSound(player.location,
                if (result.contribution > 0) Sound.BLOCK_CROP_BREAK else Sound.BLOCK_LEVER_CLICK, 0.8f, 1.0f)
            state.log(Level.INFO, "Hell plantation action: zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                "player=${player.name} action=${target.role} plot=${target.index + 1} progress=${result.state.cooled}")
        }
        if (result.contribution > 0) {
            audience.sendActionBar(player, MessageKey.FARM_HELL_GREENHOUSE_COOLED,
                mapOf("done" to locale.text(result.state.cooled), "total" to locale.text(rules.quota)))
        } else result.state.plots.getOrNull(target.index)?.let { plot ->
            audience.sendActionBar(player, FarmHellGreenhouseScene.statusKey(plot),
                FarmHellGreenhouseScene.statusValues(locale, target.index, plot))
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

    private fun apply(runtime: FarmRuntime, result: FarmHellGreenhouseResult, actor: Player?) {
        if (!result.accepted) return
        result.scorchedPlots.forEach { index ->
            runtime.region.world.players.filter { inside(runtime, it) }.forEach { player ->
                audience.sendActionBar(player, MessageKey.FARM_HELL_PLANTATION_SCORCHED,
                    mapOf("point" to locale.text(index + 1)))
            }
            state.log(Level.INFO, "Hell plantation overheat: zone=${runtime.settings.id} sequence=${runtime.state.sequence} plot=${index + 1} progress=${result.state.cooled}")
        }
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

    fun releasePlayer(playerId: UUID, runtimes: Collection<FarmRuntime>) = Unit
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
        rooms.reconcileLoaded { zone, sequence -> runtimes.any { it.settings.id == zone && it.state.sequence == sequence && active(it) && it.state.hellGreenhouse?.layoutVersion == FarmHellRiftRoom.LAYOUT_VERSION } }
    }
    fun onChunkLoad(chunk: Chunk, runtimes: Collection<FarmRuntime>) = rooms.onChunkLoad(chunk) { zone, sequence ->
        runtimes.any { it.settings.id == zone && it.state.sequence == sequence && active(it) && it.state.hellGreenhouse?.layoutVersion == FarmHellRiftRoom.LAYOUT_VERSION }
    }

    private fun updateInstructions(runtime: FarmRuntime, participants: List<Player>) {
        val plantation = runtime.state.hellGreenhouse ?: return
        participants.forEach { player ->
            val nearest = plantation.points.indices.minByOrNull { index ->
                val point = plantation.points[index]
                player.location.distanceSquared(Location(player.world, point.x, point.y, point.z))
            } ?: return@forEach
            val plot = plantation.plots.getOrNull(nearest) ?: return@forEach
            audience.sendActionBar(player, FarmHellGreenhouseScene.statusKey(plot),
                FarmHellGreenhouseScene.statusValues(locale, nearest, plot))
        }
    }

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
