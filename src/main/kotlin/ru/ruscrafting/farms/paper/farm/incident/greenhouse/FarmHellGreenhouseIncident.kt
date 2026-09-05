package ru.ruscrafting.farms.paper.farm.incident.greenhouse

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
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
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.block
import ru.ruscrafting.farms.paper.farm.FarmIncidentBedProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.placement.FarmSurfacePolicy
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayRenderer
import ru.ruscrafting.farms.paper.platform.PaperFarmTextDisplayRenderer
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import java.util.UUID
import java.util.logging.Level
import kotlin.math.abs

/** Complete owner of the generated greenhouse, virtual hot cargo and evacuation lifecycle. */
internal class FarmHellGreenhouseIncident(
    plugin: Plugin,
    private val settings: () -> ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val state: WorksiteStatePort,
    private val beds: FarmIncidentBedProvider,
    private val transitions: FarmTransitionSink,
    text: FarmTextDisplayRenderer = PaperFarmTextDisplayRenderer,
) {
    private data class Clock(val sequence: Long, val placement: Long, var ticks: Int = 0)
    private val clocks = mutableMapOf<String, Clock>()
    private val scene = FarmHellGreenhouseScene(plugin, locale, text)

    fun owns(entity: Entity): Boolean = scene.owns(entity)
    fun identity(entity: Entity): HellGreenhouseIdentity? = scene.identity(entity)
    private fun active(runtime: FarmRuntime) = runtime.state.phase == FarmPhase.INCIDENT && runtime.state.incidentType == FarmIncidentType.HELL_GREENHOUSE

    fun initialize(runtime: FarmRuntime): Boolean {
        if (!active(runtime)) return false
        if (runtime.state.hellGreenhouse != null) return true
        val indexed = beds.discover(runtime)
        val middleX = indexed.map { it.x }.average()
        val middleZ = indexed.map { it.z }.average()
        val candidates = indexed.sortedBy { (it.x - middleX) * (it.x - middleX) + (it.z - middleZ) * (it.z - middleZ) }.take(32)
        val chosen = candidates.firstOrNull { plot ->
            val soil = plot.block() ?: return@firstOrNull false
            FarmSurfacePolicy.isOutdoorBed(soil) && available(runtime, Location(soil.world, soil.x + 0.5, soil.y + 1.0, soil.z + 0.5))
        }
        if (chosen == null) {
            state.log(Level.WARNING, "Could not plan hell greenhouse: zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                "attempted=HELL_GREENHOUSE candidates=${candidates.size} rejection=no_loaded_clear_flat_9x11_site")
            return false
        }
        val points = listOf(-2, 2).flatMap { x -> listOf(-3, -1, 1, 3).map { z ->
            FarmPointPosition(chosen.world, chosen.x + 0.5 + x, chosen.y + 1.0, chosen.z + 0.5 + z)
        } }
        val rules = runtime.settings.specialIncidents.hellGreenhouse
        val initialized = FarmHellGreenhouseEngine.initialize(FarmHellGreenhouseState(points), rules)
        runtime.state = runtime.state.copy(hellGreenhouse = initialized.state, incidentProgress = 0, incidentRequired = rules.quota)
        state.persistAsync()
        return true
    }

    private fun available(runtime: FarmRuntime, center: Location): Boolean {
        val world = center.world
        if (center.blockY + 5 >= world.maxHeight || center.blockY <= world.minHeight) return false
        for (x in center.blockX - 4..center.blockX + 4) for (z in center.blockZ - 5..center.blockZ + 5) {
            if (!world.isChunkLoaded(x shr 4, z shr 4)) return false
            if (!runtime.region.contains(Location(world, x + 0.5, center.y, z + 0.5))) return false
            val support = world.getBlockAt(x, center.blockY - 1, z)
            if (!support.type.isSolid && !(support.type == Material.WATER &&
                    world.getBlockAt(x, center.blockY - 2, z).type.isSolid)) return false
            for (y in center.blockY..center.blockY + 4) {
                val material = world.getBlockAt(x, y, z).type
                if (!material.isAir && !(y == center.blockY && material.name in runtime.settings.crops)) return false
            }
        }
        return true
    }

    private fun eligible(runtime: FarmRuntime, player: Player): Boolean = player.isOnline && !player.isDead &&
        player.gameMode in setOf(GameMode.SURVIVAL, GameMode.ADVENTURE) && !access.isAdminEditing(player) &&
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
        if (!active(runtime)) { clear(runtime); return }
        val viewers = audience.players(runtime.region)
        if (viewers.any(access::isAdminEditing)) { clear(runtime); return }
        val eligible = viewers.filter { eligible(runtime, it) }
        if (eligible.isEmpty()) { clear(runtime); return }
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
        if (clock.ticks % 20 == 0 && !available(runtime, center)) { clear(runtime); return }
        clock.ticks++
        if (clock.ticks % 20 == 0 && participants.isNotEmpty()) {
            val result = FarmHellGreenhouseEngine.second(greenhouse, participants.mapTo(linkedSetOf(), Player::getUniqueId), runtime.settings.specialIncidents.hellGreenhouse)
            result.expiredPlayerIds.forEach { id ->
                participants.firstOrNull { it.uniqueId == id }?.let { player ->
                    audience.sendActionBar(player, MessageKey.FARM_HELL_GREENHOUSE_HOT_BURST)
                    if (settings().particles) player.world.spawnParticle(Particle.FLAME, player.location.add(0.0, 1.0, 0.0), 10, 0.3, 0.3, 0.3, 0.01)
                    if (settings().sounds) player.playSound(player.location, Sound.ENTITY_GENERIC_EXPLODE, 0.4f, 1.7f)
                }
            }
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
            HellGreenhouseRole.EXIT -> FarmHellGreenhouseEngine.evacuate(current, player.uniqueId, rules)
            HellGreenhouseRole.SCENE -> return true
        }
        if (!result.accepted) {
            val message = when {
                target.role == HellGreenhouseRole.EXIT -> MessageKey.FARM_HELL_GREENHOUSE_QUOTA_REQUIRED
                target.role == HellGreenhouseRole.VAT -> MessageKey.FARM_HELL_GREENHOUSE_EMPTY_HANDS
                current.evacuationSeconds != null -> MessageKey.FARM_HELL_GREENHOUSE_EVACUATE
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
        val previous = runtime.state.hellGreenhouse
        val options = runtime.settings.specialIncidents.hellGreenhouse
        val next = runtime.state.copy(hellGreenhouse = result.state, incidentProgress = result.state.cooled.coerceAtMost(options.quota),
            contributors = if (actor != null && result.contribution > 0) incrementContribution(runtime.state.contributors, actor.uniqueId, result.contribution) else runtime.state.contributors)
        if (result.finished) {
            val message = when { result.timedOut -> MessageKey.FARM_HELL_GREENHOUSE_TIMEOUT; result.successful -> MessageKey.FARM_HELL_GREENHOUSE_SUCCESS; else -> MessageKey.FARM_HELL_GREENHOUSE_PARTIAL }
            runtime.state = next
            audience.broadcast(listOf(runtime.region), message, values(runtime), sound = if (result.successful) Sound.ENTITY_PLAYER_LEVELUP else Sound.BLOCK_FIRE_EXTINGUISH)
            clear(runtime)
            transitions.apply(runtime, FarmShiftEngine.completeIncident(next, result.contribution), actor)
        } else {
            transitions.apply(runtime, EngineResult(next, true, contribution = result.contribution), actor)
            if (previous?.evacuationSeconds == null && result.state.evacuationSeconds != null) {
                audience.broadcast(listOf(runtime.region), MessageKey.FARM_HELL_GREENHOUSE_EVACUATE, values(runtime), sound = Sound.BLOCK_BELL_USE)
            }
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
    fun clear(runtime: FarmRuntime) { clocks.remove(runtime.settings.id); scene.clear(runtime.settings.id) }
    fun cleanup() { clocks.clear(); scene.cleanup() }
}
