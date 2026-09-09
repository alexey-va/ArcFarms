package ru.ruscrafting.farms.paper.farm.expedition

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.Plugin
import ru.arc.paper.teleport.ScopedTeleportAuthorizer
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowScene
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayRenderer
import ru.ruscrafting.farms.persistence.FarmBurrowReturn
import ru.ruscrafting.farms.persistence.FarmBurrowReturnRepository
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/** Shared activity contract for crash-safe temporary underground travel. */
internal enum class FarmUndergroundVariant(
    val label: String,
    val returnDirectory: Path,
    private val destinationZOffset: Double,
) {
    MOLES("mole", Path.of("data/recovery/farm-burrow-returns"), 0.0),
    HELL_RIFT("hell-rift", Path.of("data/recovery/farm-greenhouse-returns"), 10.0),

    ;

    fun active(runtime: FarmRuntime): Boolean = when (this) {
        MOLES -> runtime.state.phase == FarmPhase.CARE && runtime.state.careType == FarmCareType.MOLES
        HELL_RIFT -> runtime.state.phase == FarmPhase.INCIDENT && runtime.state.incidentType == FarmIncidentType.HELL_GREENHOUSE
    }

    fun destination(scene: FarmMoleBurrowScene, player: Player): Location = scene.start.clone().add(0.0, 0.05, destinationZOffset).apply {
        yaw = if (destinationZOffset == 0.0) player.location.yaw else 180f
        pitch = if (destinationZOffset == 0.0) 0f else player.location.pitch
    }
}

/**
 * Owns entry, return persistence, plugin-authorized teleports and session
 * snapshots. Surface visuals and activity-specific completion stay outside.
 */
internal class FarmUndergroundExpedition(
    plugin: Plugin,
    private val tasks: WorksiteTaskPort,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val state: WorksiteStatePort,
    private val variant: FarmUndergroundVariant,
    settings: () -> ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    textDisplays: FarmTextDisplayRenderer,
) {
    /** Shared surface candidate and marker owner for every underground variant. */
    val surface = FarmUndergroundSurfaceOwner(settings, locale, textDisplays)
    private val returns = FarmBurrowReturnRepository(plugin.dataFolder.toPath(), variant.returnDirectory)
    private val pending = ConcurrentHashMap<UUID, FarmBurrowReturn>()
    private val sessions = ConcurrentHashMap<UUID, FarmBurrowReturn>()
    private val teleports = ScopedTeleportAuthorizer()

    fun retains(player: Player): Boolean = sessions.containsKey(player.uniqueId) || pending.containsKey(player.uniqueId)

    fun record(player: Player): FarmBurrowReturn? = sessions[player.uniqueId]

    fun records(): Map<UUID, FarmBurrowReturn> = sessions.toMap()

    fun isAuthorized(player: Player, destination: Location?): Boolean =
        destination != null && teleports.isAuthorized(player.uniqueId, destination)

    /** Commits the return point before moving the player into the temporary room. */
    fun enter(player: Player, runtime: FarmRuntime, room: FarmMoleBurrowScene) {
        if (!room.ready || retains(player) || !variant.active(runtime) || player.isDead || access.isAdminEditing(player) ||
            player.world !== room.world || player.location.distanceSquared(room.surface) > 25.0) return
        val surface = player.location
        val record = FarmBurrowReturn(
            player.uniqueId, runtime.settings.id, runtime.state.sequence,
            surface.world.name, surface.x, surface.y, surface.z,
            surface.yaw, surface.pitch, System.currentTimeMillis(),
        )
        val placement = runtime.state.placementSequence
        if (pending.putIfAbsent(player.uniqueId, record) != null) return
        val token = tasks.lifecycleToken()
        if (!tasks.runAsync(token) {
            try {
                returns.commit(record)
                val enteredSync = tasks.runSync(token) {
                    try {
                        val currentAttempt = pending.remove(player.uniqueId, record)
                        if (!currentAttempt || !player.isOnline || player.world !== surface.world || player.location.distanceSquared(surface) > 25.0 ||
                            !variant.active(runtime) || runtime.state.sequence != record.sequence || runtime.state.placementSequence != placement || !room.ready ||
                            !access.hasAccess(player, runtime.settings.permission)
                        ) {
                            acknowledge(record)
                            return@runSync
                        }
                        val target = variant.destination(room, player)
                        sessions[player.uniqueId] = record
                        if (authorizeTeleport(player, target)) {
                            player.fallDistance = 0f
                            audience.showScreenTitle(player, if (variant == FarmUndergroundVariant.MOLES) MessageKey.FARM_MOLE_ENTERED else MessageKey.FARM_HELL_GREENHOUSE_STARTED,
                                mapOf("total" to locale.text(runtime.state.incidentRequired)))
                            state.log(Level.INFO, "Underground entry complete: type=${variant.label} zone=${record.zoneId} sequence=${record.sequence} player=${record.playerId} target=$target")
                        } else {
                            sessions.remove(player.uniqueId, record)
                            failure(record, "entry_teleport_rejected")
                            acknowledge(record)
                            audience.sendChat(player, MessageKey.TRAVEL_FAILED)
                        }
                    } catch (error: Exception) {
                        pending.remove(player.uniqueId, record)
                        state.log(Level.SEVERE, "${variant.label} entry callback failed: player=${record.playerId}", error)
                    }
                }
                if (!enteredSync) pending.remove(player.uniqueId, record)
            } catch (error: Exception) {
                pending.remove(player.uniqueId, record)
                state.log(Level.SEVERE, "${variant.label} entry failed: zone=${record.zoneId} sequence=${record.sequence} player=${record.playerId}", error)
            }
        }) pending.remove(player.uniqueId, record)
    }

    fun recover(player: Player) {
        val token = tasks.lifecycleToken()
        tasks.runAsync(token) {
            try {
                val record = returns.load(player.uniqueId) ?: return@runAsync
                tasks.runSync(token) {
                    if (player.isOnline) {
                        sessions[player.uniqueId] = record
                        if (exit(player) && variant == FarmUndergroundVariant.MOLES) audience.sendChat(player, MessageKey.FARM_MOLE_RECOVERED)
                    }
                }
            } catch (error: Exception) {
                state.log(Level.SEVERE, "${variant.label} return read failed: player=${player.uniqueId}", error)
            }
        }
    }

    fun exit(player: Player, room: FarmMoleBurrowScene? = null): Boolean {
        val record = sessions[player.uniqueId] ?: room?.takeIf { it.contains(player.location) }?.let {
            FarmBurrowReturn(player.uniqueId, it.zoneId, it.sequence, it.surface.world.name,
                it.surface.x, it.surface.y, it.surface.z, player.location.yaw, player.location.pitch, System.currentTimeMillis())
        } ?: return true
        sessions[player.uniqueId] = record
        val target = returnLocation(record)
        if (target == null || !authorizeTeleport(player, target)) {
            failure(record, if (target == null) "return_world_unavailable" else "return_teleport_rejected")
            return false
        }
        player.fallDistance = 0f
        sessions.remove(player.uniqueId, record)
        acknowledge(record)
        return true
    }

    /** Recovery fallback for an explorer inside a journalled room whose async session has not loaded yet. */
    fun returnToSurface(player: Player, runtime: FarmRuntime, surface: ru.ruscrafting.farms.domain.FarmPointPosition): Boolean {
        sessions.putIfAbsent(player.uniqueId, FarmBurrowReturn(player.uniqueId, runtime.settings.id, runtime.state.sequence,
            surface.world, surface.x, surface.y, surface.z, player.location.yaw, player.location.pitch, System.currentTimeMillis()))
        return exit(player)
    }

    fun evacuate(zone: String): Boolean {
        var success = true
        sessions.values.filter { it.zoneId == zone }.forEach { record ->
            val player = Bukkit.getPlayer(record.playerId)
            if (player?.isOnline == true) {
                if (!exit(player)) success = false
            } else sessions.remove(record.playerId, record)
        }
        return success
    }

    fun reconcile(player: Player, inside: Boolean) {
        if (inside || pending.containsKey(player.uniqueId)) return
        sessions.remove(player.uniqueId)?.let(::acknowledge)
    }

    fun quit(player: Player) {
        pending.remove(player.uniqueId)
        sessions.remove(player.uniqueId)
    }

    private fun authorizeTeleport(player: Player, destination: Location): Boolean =
        teleports.authorize(player.uniqueId, destination) {
            player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
        }

    private fun returnLocation(record: FarmBurrowReturn): Location? =
        Bukkit.getWorld(record.world)?.let { Location(it, record.x, record.y, record.z, record.yaw, record.pitch) }

    private fun acknowledge(record: FarmBurrowReturn) {
        tasks.runAsync(tasks.lifecycleToken()) {
            try { returns.acknowledge(record) }
            catch (error: Exception) { state.log(Level.SEVERE, "${variant.label} return acknowledgement failed: player=${record.playerId}", error) }
        }
    }

    private fun failure(record: FarmBurrowReturn, reason: String) {
        if (access.allowInteraction("${variant.label}-return:${record.playerId}:$reason", 10_000L)) {
            state.log(Level.WARNING, "${variant.label} travel failed: zone=${record.zoneId} sequence=${record.sequence} " +
                "player=${record.playerId} reason=$reason target=${record.world}:${record.x},${record.y},${record.z}")
        }
    }
}
