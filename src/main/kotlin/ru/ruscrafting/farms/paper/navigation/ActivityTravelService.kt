package ru.ruscrafting.farms.paper.navigation

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.network.ActivityNetworkGateway
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.BackendTransfer
import ru.ruscrafting.farms.paper.WorksiteModuleRegistry
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmRuntimeRegistry
import ru.ruscrafting.farms.paper.farm.point.FarmPointService

/** Owns local and cross-server activity navigation, including ticket claims. */
internal class ActivityTravelService(
    private val settings: () -> ArcFarmsConfig,
    private val debug: ArcFarmsDebug,
    private val port: WorksiteRuntimePort,
    private val network: ActivityNetworkGateway,
    private val transfer: BackendTransfer,
    private val points: FarmPointService,
    private val farms: FarmRuntimeRegistry,
    private val auxiliary: WorksiteModuleRegistry,
) {
    fun canNavigate(kind: ActivityKind): Boolean = kind.configKey in settings().destinations

    fun isAvailable(kind: ActivityKind): Boolean = when (kind) {
        ActivityKind.FARM -> farms.size > 0
        ActivityKind.LUMBER, ActivityKind.MINE -> auxiliary.isAvailable(kind)
    }

    fun canAccess(player: Player, kind: ActivityKind): Boolean = if (!isAvailable(kind)) {
        settings().network.enabled
    } else when (kind) {
        ActivityKind.FARM -> farms.snapshot().any { port.hasAccess(player, it.settings.permission) }
        ActivityKind.LUMBER, ActivityKind.MINE -> auxiliary.canAccess(player, kind)
    }

    fun travel(player: Player, kind: ActivityKind) {
        if (!canAccess(player, kind)) {
            port.sendChat(player, MessageKey.ZONE_LOCKED)
            return
        }
        val destination = points.destination(kind, farms.snapshot())
        debug.event(
            "travel_requested", "player" to player.name, "activity" to kind,
            "from_server" to settings().serverId, "to_server" to destination.server, "world" to destination.world,
        )
        if (destination.server == settings().serverId) {
            teleportLocal(player, kind)
            return
        }
        port.sendActionBar(player, MessageKey.TRAVEL_PREPARING)
        val lifecycle = port.lifecycleToken()
        network.createTravelTicket(player.uniqueId, kind, destination.server).whenComplete { created, failure ->
            if (!port.isOperational()) return@whenComplete
            port.runSync(lifecycle) {
                if (!port.isOperational() || !player.isOnline) return@runSync
                if (failure != null || created != true || !transfer.connect(player, destination.server)) {
                    debug.event(
                        "travel_transfer_failed", "player" to player.name, "activity" to kind,
                        "destination" to destination.server,
                        "reason" to (failure?.javaClass?.simpleName ?: "transfer_rejected"),
                    )
                    port.sendChat(player, MessageKey.TRAVEL_FAILED)
                    return@runSync
                }
                debug.event("travel_transfer_sent", "player" to player.name, "activity" to kind, "destination" to destination.server)
            }
        }
    }

    fun claimJoin(player: Player) {
        if (!settings().network.enabled) return
        val lifecycle = port.lifecycleToken()
        network.claimTravelTicket(player.uniqueId, settings().serverId).whenComplete { ticket, failure ->
            if (!port.isOperational()) return@whenComplete
            port.runLater(lifecycle, 1L) {
                if (!port.isOperational() || !player.isOnline) return@runLater
                if (failure != null) {
                    debug.event("travel_claim_failed", "player" to player.name, "reason" to failure.javaClass.simpleName)
                    return@runLater
                }
                ticket?.let {
                    debug.event("travel_claimed", "player" to player.name, "activity" to it.activity, "server" to settings().serverId)
                    teleportLocal(player, it.activity)
                }
            }
        }
    }

    private fun teleportLocal(player: Player, kind: ActivityKind) {
        val destination = points.destination(kind, farms.snapshot())
        val world = Bukkit.getWorld(destination.world)
        if (world == null) {
            debug.event("travel_local_failed", "player" to player.name, "activity" to kind, "reason" to "world_unloaded")
            port.sendChat(player, MessageKey.TRAVEL_FAILED)
            return
        }
        val location = Location(world, destination.x, destination.y, destination.z, destination.yaw, destination.pitch)
        val lifecycle = port.lifecycleToken()
        player.teleportAsync(location).whenComplete { success, failure ->
            if (!port.isOperational()) return@whenComplete
            port.runSync(lifecycle) {
                if (!port.isOperational() || !player.isOnline) return@runSync
                if (failure != null || success != true) {
                    debug.event(
                        "travel_local_failed", "player" to player.name, "activity" to kind,
                        "reason" to (failure?.javaClass?.simpleName ?: "teleport_rejected"),
                    )
                    port.sendChat(player, MessageKey.TRAVEL_FAILED)
                } else {
                    debug.event(
                        "travel_arrived", "player" to player.name, "activity" to kind,
                        "server" to destination.server, "world" to destination.world,
                        "x" to destination.x, "y" to destination.y, "z" to destination.z,
                    )
                    port.sendActionBar(player, MessageKey.TRAVEL_ARRIVED)
                }
            }
        }
    }

    private val ActivityKind.configKey: String
        get() = when (this) {
            ActivityKind.FARM -> "farm"
            ActivityKind.LUMBER -> "lumber"
            ActivityKind.MINE -> "mine"
        }
}
