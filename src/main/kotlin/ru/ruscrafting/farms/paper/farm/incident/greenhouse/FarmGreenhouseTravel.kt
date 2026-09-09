package ru.ruscrafting.farms.paper.farm.incident.greenhouse

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowScene
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import ru.ruscrafting.farms.persistence.FarmBurrowReturn
import ru.ruscrafting.farms.persistence.FarmBurrowReturnRepository
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/** Commits a return before descent; failed exits retain the durable record. */
internal class FarmGreenhouseTravel(
    plugin: Plugin,
    private val tasks: WorksiteTaskPort,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val state: WorksiteStatePort,
) {
    private val returns = FarmBurrowReturnRepository(plugin.dataFolder.toPath(), Path.of("data/recovery/farm-greenhouse-returns"))
    private val pending = ConcurrentHashMap.newKeySet<UUID>()
    private val sessions = ConcurrentHashMap<UUID, FarmBurrowReturn>()

    fun retains(player: Player) = sessions.containsKey(player.uniqueId) || player.uniqueId in pending

    fun enter(player: Player, runtime: FarmRuntime, room: FarmMoleBurrowScene) {
        if (!room.ready || !pending.add(player.uniqueId)) return
        val surface = player.location
        val record = FarmBurrowReturn(player.uniqueId, runtime.settings.id, runtime.state.sequence,
            surface.world.name, surface.x, surface.y, surface.z, surface.yaw, surface.pitch, System.currentTimeMillis())
        val token = tasks.lifecycleToken()
        if (!tasks.runAsync(token) {
            try {
                returns.commit(record)
                tasks.runSync(token) {
                    pending.remove(player.uniqueId)
                    if (!player.isOnline || player.world !== surface.world || player.location.distanceSquared(surface) > 25.0 ||
                        runtime.state.phase != FarmPhase.INCIDENT || runtime.state.incidentType != FarmIncidentType.HELL_GREENHOUSE ||
                        runtime.state.sequence != record.sequence || !room.ready || !access.hasAccess(player, runtime.settings.permission)) {
                        acknowledge(record)
                        return@runSync
                    }
                    sessions[player.uniqueId] = record
                    val target = room.start.clone().add(0.0, 0.05, 4.0).apply { yaw = 180f }
                    if (player.teleport(target)) {
                        player.fallDistance = 0f
                    } else {
                        sessions.remove(player.uniqueId, record)
                        failure(record, "entry_teleport_rejected")
                        acknowledge(record)
                        audience.sendChat(player, MessageKey.TRAVEL_FAILED)
                    }
                }
            } catch (error: Exception) {
                pending.remove(player.uniqueId)
                state.log(Level.SEVERE, "Greenhouse entry failed: zone=${record.zoneId} sequence=${record.sequence} player=${record.playerId}", error)
            }
        }) pending.remove(player.uniqueId)
    }

    fun recover(player: Player) {
        val token = tasks.lifecycleToken()
        tasks.runAsync(token) {
            try {
                val record = returns.load(player.uniqueId) ?: return@runAsync
                tasks.runSync(token) {
                    if (player.isOnline) {
                        sessions[player.uniqueId] = record
                        exit(player)
                    }
                }
            } catch (error: Exception) {
                state.log(Level.SEVERE, "Greenhouse return read failed: player=${player.uniqueId}", error)
            }
        }
    }

    fun exit(player: Player): Boolean {
        val record = sessions[player.uniqueId] ?: return true
        val world = Bukkit.getWorld(record.world)
        val target = world?.let { Location(it, record.x, record.y, record.z, record.yaw, record.pitch) }
        if (target == null || !player.teleport(target)) {
            failure(record, if (target == null) "return_world_unavailable" else "return_teleport_rejected")
            return false
        }
        player.fallDistance = 0f
        sessions.remove(player.uniqueId, record)
        acknowledge(record)
        return true
    }

    fun evacuate(zone: String): Boolean {
        var success = true
        sessions.values.filter { it.zoneId == zone }.forEach { record ->
            val player = Bukkit.getPlayer(record.playerId)
            if (player?.isOnline == true) {
                if (!exit(player)) success = false
            } else sessions.remove(record.playerId, record) // Durable return survives logout.
        }
        return success
    }

    fun reconcile(player: Player, inside: Boolean) {
        if (inside || player.uniqueId in pending) return
        sessions.remove(player.uniqueId)?.let(::acknowledge)
    }

    fun quit(player: Player) { pending.remove(player.uniqueId); sessions.remove(player.uniqueId) }

    private fun acknowledge(record: FarmBurrowReturn) {
        tasks.runAsync(tasks.lifecycleToken()) {
            try { returns.acknowledge(record) }
            catch (error: Exception) { state.log(Level.SEVERE, "Greenhouse return acknowledgement failed: player=${record.playerId}", error) }
        }
    }

    private fun failure(record: FarmBurrowReturn, reason: String) {
        if (access.allowInteraction("greenhouse-return:${record.playerId}:$reason", 10_000L)) {
            state.log(Level.WARNING, "Greenhouse travel failed: zone=${record.zoneId} sequence=${record.sequence} " +
                "player=${record.playerId} reason=$reason target=${record.world}:${record.x},${record.y},${record.z}")
        }
    }
}
