package ru.ruscrafting.farms.paper.farm.incident.processing

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Chicken
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.domain.FarmProcessingCrankSampleStatus
import ru.ruscrafting.farms.domain.FarmProcessingCrankState
import ru.ruscrafting.farms.domain.FarmProcessingCrankTracker
import ru.ruscrafting.farms.domain.FarmProcessingStage
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.BukkitFarmEntityLookup
import ru.ruscrafting.farms.paper.FarmEntityLookup
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import java.util.UUID
import java.util.logging.Level
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Owns physical millstone walking progress and the visible player tethers. */
internal class FarmProcessingCrankController(
    plugin: Plugin,
    private val settings: () -> ArcFarmsConfig,
    private val debug: ArcFarmsDebug,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val state: WorksiteStatePort,
    private val transitions: FarmTransitionSink,
    private val entityLookup: FarmEntityLookup = BukkitFarmEntityLookup,
) {
    private val tetherKey = NamespacedKey(plugin, "farm_processing_crank_tether")
    private val states = mutableMapOf<Pair<String, UUID>, FarmProcessingCrankState>()
    private val radians = mutableMapOf<String, Double>()
    private val tethers = mutableMapOf<Pair<String, UUID>, UUID>()

    fun owns(entity: Entity): Boolean = entity.persistentDataContainer.has(tetherKey, PersistentDataType.STRING)

    fun participantCount(zoneId: String): Int = states.keys.count { it.first == zoneId }

    fun progressDegrees(zoneId: String): Int = Math.toDegrees(radians.getOrDefault(zoneId, 0.0)).toInt()

    fun hasZone(zoneId: String): Boolean = states.keys.any { it.first == zoneId } || tethers.keys.any { it.first == zoneId }

    fun releasePlayer(player: Player, reason: String) {
        states.keys.filter { it.second == player.uniqueId }.toList().forEach { key -> removeParticipant(key, reason) }
    }

    fun update(runtime: FarmRuntime, tick: Long, machine: Location, machineDisplay: ItemDisplay?) {
        val processingState = runtime.state.processing
        if (processingState?.stage != FarmProcessingStage.OPERATING) {
            machineDisplay?.isGlowing = false
            clear(runtime.settings.id, "stage_${processingState?.stage?.name?.lowercase() ?: "missing"}")
            return
        }
        val configured = runtime.settings.processing
        val players = audience.players(runtime.region)
            .filterNot(access::isAdminEditing)
            .filter { player -> access.hasAccess(player, runtime.settings.permission) }
            .associateBy(Player::getUniqueId)
        states.keys.filter { it.first == runtime.settings.id && it.second !in players }.toList().forEach { key ->
            removeParticipant(key, "player_unavailable")
        }
        players.values.forEach { player ->
            if (runtime.state.processing?.stage != FarmProcessingStage.OPERATING) return@forEach
            sample(runtime, player, machine, tick)
        }
        render(runtime, tick, machine, machineDisplay, players.values)
    }

    fun clear(zoneId: String, reason: String) {
        val keys = (states.keys + tethers.keys).filter { it.first == zoneId }.distinct()
        keys.forEach { key -> removeParticipant(key, reason) }
        radians.remove(zoneId)
    }

    fun cleanup(reason: String) {
        val entities = entityLookup.inAllWorlds().filter(::owns)
        entities.forEach(Entity::remove)
        states.clear()
        radians.clear()
        tethers.clear()
        if (entities.isNotEmpty()) {
            debug.event("farm_processing_crank_cleanup", "count" to entities.size, "reason" to reason)
        }
    }

    private fun sample(runtime: FarmRuntime, player: Player, machine: Location, tick: Long) {
        val configured = runtime.settings.processing
        val key = runtime.settings.id to player.uniqueId
        if (player.world !== machine.world || kotlin.math.abs(player.location.y - machine.y) > configured.crankVerticalTolerance) {
            if (key in states) removeParticipant(key, "wrong_level")
            return
        }
        val sample = FarmProcessingCrankTracker.sample(
            previous = states[key],
            x = player.location.x,
            z = player.location.z,
            centerX = machine.x,
            centerZ = machine.z,
            innerRadius = configured.crankInnerRadius,
            outerRadius = configured.crankOuterRadius,
            radiusTolerance = configured.crankRadiusTolerance,
            maxStepDistance = configured.crankMaxStepDistance,
        )
        if (sample.state == null) {
            if (key in states) removeParticipant(key, "left_ring")
            return
        }
        states[key] = sample.state
        ensureTether(runtime, player, machine)
        when (sample.status) {
            FarmProcessingCrankSampleStatus.ENTERED -> {
                showHint(runtime, player)
                debug.event(
                    "farm_processing_crank_joined",
                    "zone" to runtime.settings.id,
                    "sequence" to runtime.state.sequence,
                    "player" to player.name,
                    "radius" to player.location.distance(machine),
                )
            }
            FarmProcessingCrankSampleStatus.TELEPORTED -> if (
                access.allowInteraction("farm-processing-crank-teleport:${runtime.settings.id}:${player.uniqueId}", 5_000)
            ) {
                debug.event(
                    "farm_processing_crank_step_rejected",
                    "zone" to runtime.settings.id,
                    "sequence" to runtime.state.sequence,
                    "player" to player.name,
                    "reason" to "step_too_large",
                )
            }
            else -> Unit
        }
        if (tick % (configured.crankTitleReminderSeconds * 20L) == 0L) showHint(runtime, player)
        if (sample.acceptedRadians <= 0.0) return
        var accumulated = radians.getOrDefault(runtime.settings.id, 0.0) + sample.acceptedRadians
        if (accumulated + LAP_EPSILON < FarmProcessingCrankTracker.FULL_LAP_RADIANS) {
            radians[runtime.settings.id] = accumulated
            return
        }
        accumulated = (accumulated - FarmProcessingCrankTracker.FULL_LAP_RADIANS).coerceAtLeast(0.0)
        radians[runtime.settings.id] = accumulated
        completeLap(runtime, player, accumulated)
    }

    private fun completeLap(runtime: FarmRuntime, player: Player, carryRadians: Double) {
        val state = requireNotNull(runtime.state.processing)
        val result = FarmShiftEngine.advanceProcessing(runtime.state, player.uniqueId, FarmProcessingStage.OPERATING)
        transitions.apply(runtime, result, player)
        if (!result.accepted) return
        debug.event(
            "farm_processing_crank_lap_completed",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "player" to player.name,
            "cycles" to (result.state.processing?.cyclesCompleted ?: state.cyclesRequired),
            "required" to state.cyclesRequired,
            "carry_radians" to carryRadians,
        )
        if (settings().sounds) {
            player.playSound(player.location, Sound.BLOCK_PISTON_EXTEND, 0.85f, 0.9f)
            player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.65f, 1.35f)
        }
        if (settings().particles) {
            player.world.spawnParticle(Particle.COMPOSTER, player.location.clone().add(0.0, 1.0, 0.0), 12, 0.5, 0.6, 0.5, 0.04)
        }
        if (runtime.state.processing?.stage != FarmProcessingStage.OPERATING) clear(runtime.settings.id, "stage_completed")
    }

    private fun showHint(runtime: FarmRuntime, player: Player) {
        audience.sendActionBar(player, ru.ruscrafting.farms.config.MessageKey.FARM_PROCESSING_OPERATING_HINT)
        val cooldown = runtime.settings.processing.crankTitleReminderSeconds * 1_000L
        if (access.allowInteraction("farm-processing-title:${runtime.settings.id}:${player.uniqueId}", cooldown)) {
            audience.showScreenTitle(
                player,
                ru.ruscrafting.farms.config.MessageKey.FARM_PROCESSING_OPERATING_TITLE,
                scope = "processing_hint",
            )
        }
    }

    private fun ensureTether(runtime: FarmRuntime, player: Player, machine: Location) {
        val key = runtime.settings.id to player.uniqueId
        val anchor = machine.clone().add(0.0, TETHER_Y_OFFSET, 0.0)
        val existing = tethers[key]?.let(Bukkit::getEntity) as? Mob
        if (existing != null && existing.isValid) {
            if (existing.world !== anchor.world || existing.location.distanceSquared(anchor) > 0.01) existing.teleport(anchor)
            if (!existing.isLeashed || runCatching { existing.leashHolder }.getOrNull() != player) {
                runCatching { existing.setLeashHolder(player) }
            }
            return
        }
        tethers.remove(key)
        val tether = runCatching { anchor.world.spawn(anchor, Chicken::class.java) }.getOrElse { failure ->
            tetherFailure(runtime, player, failure, "create")
            return
        }
        tether.persistentDataContainer.set(tetherKey, PersistentDataType.STRING, runtime.settings.id)
        tether.isPersistent = false
        tether.isInvulnerable = true
        runCatching { tether.isSilent = true }
        runCatching { tether.isCollidable = false }
        runCatching { tether.isInvisible = true }
        runCatching { tether.setAI(false) }
        runCatching { tether.setGravity(false) }
        runCatching { tether.setRemoveWhenFarAway(false) }
        runCatching { tether.setLeashHolder(player) }.exceptionOrNull()?.let { failure ->
            tether.remove()
            tetherFailure(runtime, player, failure, "attach")
            return
        }
        tethers[key] = tether.uniqueId
        debug.event(
            "farm_processing_crank_tether_attached",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "player" to player.name,
        )
    }

    private fun tetherFailure(runtime: FarmRuntime, player: Player, failure: Throwable, action: String) {
        if (!access.allowInteraction("farm-processing-crank-tether-failed:${runtime.settings.id}", 30_000)) return
        state.log(Level.WARNING, "Could not $action processing crank tether for ${runtime.settings.id}: ${failure.message}")
        debug.event(
            "farm_processing_crank_tether_failed",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "player" to player.name,
            "action" to action,
            "error" to failure.javaClass.simpleName,
        )
    }

    private fun removeParticipant(key: Pair<String, UUID>, reason: String) {
        states.remove(key)
        tethers.remove(key)?.let(Bukkit::getEntity)?.remove()
        debug.event("farm_processing_crank_left", "zone" to key.first, "player" to key.second, "reason" to reason)
    }

    private fun render(
        runtime: FarmRuntime,
        tick: Long,
        machine: Location,
        machineDisplay: ItemDisplay?,
        viewers: Collection<Player>,
    ) {
        val activePlayers = participantCount(runtime.settings.id)
        machineDisplay?.isGlowing = activePlayers > 0
        if (!settings().particles || tick % 5L != 0L) return
        val configured = runtime.settings.processing
        val radius = (configured.crankInnerRadius + configured.crankOuterRadius) / 2.0
        val color = if (activePlayers > 0) GREEN else TRACK
        val size = if (activePlayers > 0) GREEN_SIZE else TRACK_SIZE
        repeat(RING_POINTS) { index ->
            val angle = 2.0 * PI * index / RING_POINTS
            val point = machine.clone().add(radius * cos(angle), RING_Y_OFFSET, radius * sin(angle))
            viewers.forEach { viewer -> audience.spawnGuidanceDust(viewer, point, color, size) }
        }
    }

    private companion object {
        const val TETHER_Y_OFFSET = 0.35
        const val RING_Y_OFFSET = 0.08
        const val RING_POINTS = 40
        const val LAP_EPSILON = 0.0001
        const val GREEN_SIZE = 1.0f
        const val TRACK_SIZE = 0.9f
        val GREEN: Color = Color.fromRGB(92, 214, 116)
        val TRACK: Color = Color.fromRGB(69, 200, 245)
    }
}
