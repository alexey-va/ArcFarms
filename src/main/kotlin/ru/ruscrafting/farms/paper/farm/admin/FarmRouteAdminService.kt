package ru.ruscrafting.farms.paper.farm.admin

import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerMoveEvent
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmDeliveryRoute
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmRouteState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.persistence.FarmRouteRepository
import java.util.UUID
import kotlin.math.sqrt

internal class FarmRouteAdminService(
    private val repository: FarmRouteRepository,
    private val debug: ArcFarmsDebug,
    private val port: WorksiteRuntimePort,
    private val runtimes: () -> Collection<FarmRuntime>,
) {
    private data class Recording(val zoneId: String, val points: MutableList<FarmPointPosition>)

    private var state = repository.load()
    private val recordings = mutableMapOf<UUID, Recording>()

    fun route(zoneId: String): FarmDeliveryRoute? = state.routes[zoneId]

    fun start(player: Player, zoneId: String): Boolean {
        val runtime = runtime(player, zoneId) ?: return false
        if (player.world != runtime.region.world) {
            port.sendChat(player, MessageKey.ADMIN_ROUTE_WRONG_WORLD)
            return false
        }
        if (!runtime.region.contains(player.location)) {
            port.sendChat(player, MessageKey.ADMIN_ROUTE_START_OUTSIDE)
            return false
        }
        recordings[player.uniqueId] = Recording(zoneId, mutableListOf(player.location.point()))
        port.sendChat(player, MessageKey.ADMIN_ROUTE_STARTED, mapOf("zone" to portText(zoneId)))
        debug.event("farm_route_recording_started", "zone" to zoneId, "player" to player.name)
        return true
    }

    fun onMove(event: PlayerMoveEvent) {
        val recording = recordings[event.player.uniqueId] ?: return
        if (event.player.vehicle != null) return
        val destination = event.to
        val last = recording.points.last()
        if (destination.world.name != last.world) {
            cancel(event.player, "world_changed")
            port.sendChat(event.player, MessageKey.ADMIN_ROUTE_WRONG_WORLD)
            return
        }
        val sampleDistance = runtimes().firstOrNull { it.settings.id == recording.zoneId }
            ?.settings?.routeDelivery?.sampleDistance ?: 2.5
        if (distance(last, destination.point()) < sampleDistance) return
        if (recording.points.size >= 512) {
            port.sendChat(event.player, MessageKey.ADMIN_ROUTE_LIMIT)
            return
        }
        recording.points += destination.point()
        if (recording.points.size % 10 == 0) {
            port.sendActionBar(
                event.player,
                MessageKey.ADMIN_ROUTE_PROGRESS,
                mapOf("points" to portText(recording.points.size)),
            )
        }
    }

    fun finish(player: Player): Boolean {
        val recording = recordings[player.uniqueId] ?: run {
            port.sendChat(player, MessageKey.ADMIN_ROUTE_NOT_RECORDING)
            return false
        }
        if (recording.points.size < 2) {
            port.sendChat(player, MessageKey.ADMIN_ROUTE_TOO_SHORT)
            return false
        }
        val finished = recording.points.toMutableList()
        if (distance(finished.last(), player.location.point()) >= 0.5 && finished.size < 512) finished += player.location.point()
        val route = runCatching { FarmDeliveryRoute(finished) }.getOrElse {
            port.sendChat(player, MessageKey.ADMIN_ROUTE_INVALID)
            return false
        }
        val candidate = state.copy(routes = state.routes + (recording.zoneId to route))
        return runCatching { repository.saveBlocking(candidate) }.fold(
            onSuccess = {
                state = candidate
                recordings.remove(player.uniqueId)
                port.sendChat(
                    player,
                    MessageKey.ADMIN_ROUTE_SAVED,
                    mapOf("zone" to portText(recording.zoneId), "points" to portText(route.points.size)),
                )
                debug.event("farm_route_saved", "zone" to recording.zoneId, "player" to player.name, "points" to route.points.size)
                true
            },
            onFailure = {
                port.sendChat(player, MessageKey.GENERIC_ERROR)
                false
            },
        )
    }

    fun cancel(player: Player, reason: String = "admin_cancel"): Boolean {
        val removed = recordings.remove(player.uniqueId) ?: run {
            port.sendChat(player, MessageKey.ADMIN_ROUTE_NOT_RECORDING)
            return false
        }
        port.sendChat(player, MessageKey.ADMIN_ROUTE_CANCELLED)
        debug.event("farm_route_recording_cancelled", "zone" to removed.zoneId, "player" to player.name, "reason" to reason)
        return true
    }

    fun clear(player: Player, zoneId: String): Boolean {
        runtime(player, zoneId) ?: return false
        if (zoneId !in state.routes) {
            port.sendChat(player, MessageKey.ADMIN_ROUTE_MISSING, mapOf("zone" to portText(zoneId)))
            return false
        }
        val candidate = state.copy(routes = state.routes - zoneId)
        return runCatching { repository.saveBlocking(candidate) }.fold(
            onSuccess = {
                state = candidate
                port.sendChat(player, MessageKey.ADMIN_ROUTE_CLEARED, mapOf("zone" to portText(zoneId)))
                true
            },
            onFailure = { port.sendChat(player, MessageKey.GENERIC_ERROR); false },
        )
    }

    fun status(player: Player, zoneId: String): Boolean {
        runtime(player, zoneId) ?: return false
        val route = state.routes[zoneId]
        port.sendChat(
            player,
            if (route == null) MessageKey.ADMIN_ROUTE_MISSING else MessageKey.ADMIN_ROUTE_STATUS,
            mapOf("zone" to portText(zoneId), "points" to portText(route?.points?.size ?: 0)),
        )
        return route != null
    }

    fun release(player: Player) {
        recordings.remove(player.uniqueId)
    }

    private fun runtime(player: Player, zoneId: String): FarmRuntime? = runtimes().firstOrNull { it.settings.id == zoneId } ?: run {
        port.sendChat(player, MessageKey.ADMIN_ZONE_UNKNOWN, mapOf("zone" to portText(zoneId)))
        null
    }

    private fun org.bukkit.Location.point() = FarmPointPosition(world.name, x, y, z, yaw, pitch)

    private fun distance(left: FarmPointPosition, right: FarmPointPosition): Double {
        if (left.world != right.world) return Double.POSITIVE_INFINITY
        val dx = left.x - right.x
        val dy = left.y - right.y
        val dz = left.z - right.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun portText(value: Any) = net.kyori.adventure.text.Component.text(value.toString())
}
