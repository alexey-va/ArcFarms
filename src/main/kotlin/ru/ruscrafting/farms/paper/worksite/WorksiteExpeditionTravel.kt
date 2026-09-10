package ru.ruscrafting.farms.paper.worksite

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.Plugin
import ru.arc.paper.teleport.ScopedTeleportAuthorizer
import ru.ruscrafting.farms.persistence.FarmBurrowReturn
import ru.ruscrafting.farms.persistence.FarmBurrowReturnRepository
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/** Activity-neutral crash-safe travel into a temporary room. */
internal class WorksiteExpeditionTravel(
    plugin: Plugin,
    private val tasks: WorksiteTaskPort,
    private val access: WorksiteAccessPort,
    private val state: WorksiteStatePort,
    returnDirectory: Path,
) {
    data class EntryRequest(
        val player: Player,
        val zoneId: String,
        val sequence: Long,
        val permission: String,
        val surface: Location,
        val destination: Location,
    )

    private val returns = FarmBurrowReturnRepository(plugin.dataFolder.toPath(), returnDirectory)
    private val pending = ConcurrentHashMap<UUID, FarmBurrowReturn>()
    private val sessions = ConcurrentHashMap<UUID, FarmBurrowReturn>()
    private val teleports = ScopedTeleportAuthorizer()

    fun retains(player: Player): Boolean = sessions.containsKey(player.uniqueId) || pending.containsKey(player.uniqueId)
    fun record(player: Player): FarmBurrowReturn? = sessions[player.uniqueId]
    fun records(): Map<UUID, FarmBurrowReturn> = sessions.toMap()
    fun isAuthorized(player: Player, destination: Location?): Boolean =
        destination != null && teleports.isAuthorized(player.uniqueId, destination)

    fun enter(request: EntryRequest, stillValid: () -> Boolean) {
        val player = request.player
        if (!validEntry(request) || retains(player) || !stillValid()) return
        val record = FarmBurrowReturn(
            player.uniqueId, request.zoneId, request.sequence,
            request.surface.world.name, request.surface.x, request.surface.y, request.surface.z,
            request.surface.yaw, request.surface.pitch, System.currentTimeMillis(),
        )
        if (pending.putIfAbsent(player.uniqueId, record) != null) return
        val token = tasks.lifecycleToken()
        if (!tasks.runAsync(token) {
            try {
                returns.commit(record)
                var callbackRan = false
                val entered = tasks.runSync(token) {
                    callbackRan = true
                    val currentAttempt = pending.remove(player.uniqueId, record)
                    if (!currentAttempt || !validEntry(request) || !stillValid()) {
                        acknowledge(record)
                        return@runSync
                    }
                    sessions[player.uniqueId] = record
                    if (!authorizeTeleport(player, request.destination)) {
                        sessions.remove(player.uniqueId, record)
                        failure(record, "entry_teleport_rejected")
                        acknowledge(record)
                    }
                }
                if (!entered) {
                    pending.remove(player.uniqueId, record)
                    if (callbackRan) acknowledge(record)
                }
            } catch (error: Exception) {
                pending.remove(player.uniqueId, record)
                state.log(Level.SEVERE, "expedition entry failed: player=${record.playerId}", error)
            }
        }) pending.remove(player.uniqueId, record)
    }

    fun recover(player: Player, afterReturn: (Boolean) -> Unit = {}) {
        val token = tasks.lifecycleToken()
        tasks.runAsync(token) {
            try {
                returns.load(player.uniqueId)?.let { record ->
                    tasks.runSync(token) {
                        if (player.isOnline) {
                            sessions[player.uniqueId] = record
                            afterReturn(exit(player))
                        }
                    }
                }
            } catch (error: Exception) {
                state.log(Level.SEVERE, "expedition return read failed: player=${player.uniqueId}", error)
            }
        }
    }

    fun exit(player: Player, fallback: FarmBurrowReturn? = null): Boolean {
        val record = sessions[player.uniqueId] ?: fallback ?: return true
        val target = Bukkit.getWorld(record.world)?.let { Location(it, record.x, record.y, record.z, record.yaw, record.pitch) }
        if (target == null || !authorizeTeleport(player, target)) {
            failure(record, if (target == null) "return_world_unavailable" else "return_teleport_rejected")
            return false
        }
        player.fallDistance = 0f
        sessions.remove(player.uniqueId, record)
        acknowledge(record)
        return true
    }

    fun returnToSurface(player: Player, record: FarmBurrowReturn): Boolean {
        sessions.putIfAbsent(player.uniqueId, record)
        return exit(player)
    }

    fun evacuate(zone: String): Boolean {
        var success = true
        sessions.values.filter { it.zoneId == zone }.forEach { record ->
            val player = Bukkit.getPlayer(record.playerId)
            if (player?.isOnline == true) { if (!exit(player)) success = false }
            else sessions.remove(record.playerId, record)
        }
        return success
    }

    fun reconcile(player: Player, inside: Boolean) {
        if (inside || pending.containsKey(player.uniqueId)) return
        sessions.remove(player.uniqueId)?.let(::acknowledge)
    }

    fun quit(player: Player) { pending.remove(player.uniqueId); sessions.remove(player.uniqueId) }

    private fun validEntry(request: EntryRequest): Boolean {
        val player = request.player
        return player.isOnline && !player.isDead && !access.isAdminEditing(player) &&
            player.world === request.surface.world && player.location.distanceSquared(request.surface) <= 25.0 &&
            access.hasAccess(player, request.permission)
    }

    private fun authorizeTeleport(player: Player, destination: Location): Boolean =
        teleports.authorize(player.uniqueId, destination) {
            player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
        }

    private fun acknowledge(record: FarmBurrowReturn) = tasks.runAsync(tasks.lifecycleToken()) {
        runCatching { returns.acknowledge(record) }
            .onFailure { state.log(Level.SEVERE, "expedition return acknowledgement failed: player=${record.playerId}", it) }
    }

    private fun failure(record: FarmBurrowReturn, reason: String) {
        if (access.allowInteraction("expedition-return:${record.playerId}:$reason", 10_000L)) {
            state.log(Level.WARNING, "expedition travel failed: zone=${record.zoneId} sequence=${record.sequence} player=${record.playerId} reason=$reason")
        }
    }
}
