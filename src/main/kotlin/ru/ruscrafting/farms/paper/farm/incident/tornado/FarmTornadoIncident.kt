package ru.ruscrafting.farms.paper.farm.incident.tornado

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.domain.FarmIncidentPlanner
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmTornadoEngine
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.block
import ru.ruscrafting.farms.paper.farm.FarmIncidentBedProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.placement.FarmSurfacePolicy
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import java.util.UUID
import java.util.logging.Level
import kotlin.math.abs
import kotlin.math.sqrt

/** Owns the warning, pursuit, survival clock and complete transient scene lifecycle. */
internal class FarmTornadoIncident(
    plugin: Plugin,
    private val settings: () -> ArcFarmsConfig,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val state: WorksiteStatePort,
    private val beds: FarmIncidentBedProvider,
    private val transitions: FarmTransitionSink,
) {
    private data class Session(
        val sequence: Long,
        val placement: Long,
        val center: Location,
        var ticks: Int = 0,
        val lastHits: MutableMap<UUID, Int> = mutableMapOf(),
    )

    private val sessions = mutableMapOf<String, Session>()
    private val scene = FarmTornadoScene(plugin)

    fun initialize(runtime: FarmRuntime): Boolean {
        if (!FarmTornadoEngine.active(runtime.state)) return false
        if (runtime.state.specialIncident != null) return true
        val candidates = beds.discover(runtime).filter { position ->
            val soil = position.block() ?: return@filter false
            runtime.region.contains(soil.location.add(0.5, 1.0, 0.5)) && FarmSurfacePolicy.isOutdoorBed(soil) &&
                soil.y + 1 + runtime.settings.specialIncidents.tornado.height + 2 < soil.world.maxHeight
        }
        val chosen = FarmIncidentPlanner.centralDispersedCenters(
            candidates, minOf(32, candidates.size).coerceAtLeast(1), minimumSpacing = 3.0, selectionIndex = runtime.state.placementSequence,
        )
        if (chosen.isEmpty()) {
            state.log(Level.WARNING, "Could not plan farm tornado: zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                "attempted=TORNADO candidates=${candidates.size} rejection=no_loaded_outdoor_beds_with_height")
            return false
        }
        val result = FarmTornadoEngine.initialize(runtime.state, chosen.map {
            FarmPointPosition(it.world, it.x + 0.5, it.y + 1.0, it.z + 0.5)
        }, runtime.settings.specialIncidents.tornado.durationSeconds)
        if (result.accepted) {
            runtime.state = result.state
            state.persistAsync()
        }
        return result.accepted
    }

    /** Called by the existing supervised visual tick, once per server tick. */
    fun update(runtime: FarmRuntime) {
        val zone = runtime.settings.id
        if (!FarmTornadoEngine.active(runtime.state)) {
            clear(runtime)
            return
        }
        val options = runtime.settings.specialIncidents.tornado
        val players = audience.players(runtime.region)
        if (players.any(access::isAdminEditing)) {
            clear(runtime)
            return
        }
        val participants = players.filter {
            it.isOnline && !it.isDead && runtime.region.contains(it.location) && it.gameMode in setOf(GameMode.SURVIVAL, GameMode.ADVENTURE) &&
                access.hasAccess(it, runtime.settings.permission)
        }
        if (participants.isEmpty()) {
            clear(runtime)
            return
        }
        if (runtime.state.specialIncident == null && !initialize(runtime)) {
            transitions.apply(runtime, ru.ruscrafting.farms.domain.FarmShiftEngine.skipUnavailableIncident(
                runtime.state, ru.ruscrafting.farms.domain.FarmIncidentType.TORNADO,
            ), null)
            state.persistAsync()
            return
        }
        val anchors = runtime.state.specialIncident?.points.orEmpty().mapNotNull { point ->
            if (point.world != runtime.region.world.name) return@mapNotNull null
            Location(runtime.region.world, point.x, point.y, point.z).takeIf {
                it.world.isChunkLoaded(it.blockX shr 4, it.blockZ shr 4) && runtime.region.contains(it)
            }
        }
        if (anchors.isEmpty()) {
            clear(runtime)
            return
        }
        var session = sessions[zone]
        if (session == null || session.sequence != runtime.state.sequence || session.placement != runtime.state.placementSequence) {
            clear(runtime)
            val start = anchors.minBy { anchor -> abs(sqrt(participants.minOf { it.location.distanceSquared(anchor) }) - 14.0) }
            session = Session(runtime.state.sequence, runtime.state.placementSequence, start.clone())
            sessions[zone] = session
        }
        val active = session
        if (!active.center.world.isChunkLoaded(active.center.blockX shr 4, active.center.blockZ shr 4)) {
            clear(runtime)
            return
        }
        active.ticks++
        active.lastHits.keys.retainAll(participants.mapTo(mutableSetOf(), Player::getUniqueId))
        val warningTicks = options.warningSeconds * 20
        val pursuing = active.ticks > warningTicks
        if (pursuing) {
            val target = participants.minBy { it.location.distanceSquared(active.center) }
            val waypoint = anchors.minBy { it.distanceSquared(target.location) }
            val offset = Vector(target.location.x, waypoint.y, target.location.z).subtract(active.center.toVector())
            val distance = offset.length()
            if (distance > 0.01) {
                val next = active.center.clone().add(offset.multiply(minOf(options.speed / 20, distance) / distance))
                if (next.world.isChunkLoaded(next.blockX shr 4, next.blockZ shr 4) && runtime.region.contains(next)) {
                    active.center.set(next.x, next.y, next.z)
                }
            }
            participants.forEach { player -> affect(player, active, options.hitDamage) }
        }
        val fade = ((runtime.state.incidentRequired - runtime.state.incidentProgress) / 4.0).coerceIn(0.25, 1.0)
        val strength = if (pursuing) fade else (active.ticks.toDouble() / warningTicks).coerceAtLeast(0.15)
        scene.render(zone, active.center, options, active.ticks, strength, players, settings().particles)
        if (settings().sounds && active.ticks % 40 == 0) players.forEach { player ->
            player.playSound(active.center, Sound.ENTITY_BREEZE_IDLE_GROUND, 1.4f, 0.55f)
            player.playSound(active.center, Sound.ITEM_ELYTRA_FLYING, 0.8f, 0.6f)
        }
        if (pursuing && (active.ticks - warningTicks) % 20 == 0) {
            val result = FarmTornadoEngine.second(runtime.state, participants.mapTo(mutableSetOf(), Player::getUniqueId))
            if (result.accepted) {
                transitions.apply(runtime, result, null)
                state.persistAsync()
            }
        }
    }

    private fun affect(player: Player, session: Session, damage: Double) {
        val location = player.location
        if (abs(location.y - session.center.y) > 3.0) return
        val dx = location.x - session.center.x
        val dz = location.z - session.center.z
        val distance = sqrt(dx * dx + dz * dz)
        if (distance > 5.0) return
        val nx = if (distance > 0.01) dx / distance else 1.0
        val nz = if (distance > 0.01) dz / distance else 0.0
        if (distance <= 2.8) {
            val last = session.lastHits[player.uniqueId]
            if (last != null && session.ticks - last < 20) return
            session.lastHits[player.uniqueId] = session.ticks
            if (damage > 0) player.damage(damage)
            player.velocity = Vector(nx * 0.85 - nz * 0.25, 0.38, nz * 0.85 + nx * 0.25)
        } else if (session.ticks % 4 == 0) {
            val velocity = player.velocity
            // Gentle inward wind; sprinting outwards remains faster than the pursuit and pull.
            player.velocity = velocity.add(Vector(-nx * 0.06 - nz * 0.025, 0.0, -nz * 0.06 + nx * 0.025))
        }
    }

    fun owns(entity: Entity): Boolean = scene.owns(entity)

    fun clear(runtime: FarmRuntime) {
        sessions.remove(runtime.settings.id)
        scene.clear(runtime.settings.id)
    }

    fun cleanup() {
        sessions.clear()
        scene.cleanup()
    }
}
