package ru.ruscrafting.farms.paper.farm.incident.route

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Horse
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerTeleportEvent
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmStallAction
import ru.ruscrafting.farms.domain.FarmStallWatchdog
import ru.ruscrafting.farms.domain.FarmStallWatchdogState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.farm.admin.FarmRouteAdminService
import ru.ruscrafting.farms.paper.platform.FarmVehiclePassengerControl

/** Geometry-blind stall recovery plus exact suffocation rescue for the rear passenger. */
internal class FarmFoodDeliverySafety(
    private val port: WorksiteAudiencePort,
    private val debug: ArcFarmsDebug,
    private val routes: FarmRouteAdminService,
    private val vehiclePassengers: FarmVehiclePassengerControl,
    private val gunner: FarmFoodDeliveryGunner,
) {
    fun update(
        runtime: FarmRuntime,
        horse: Horse,
        session: FarmFoodDeliverySession,
        rider: Player,
        points: List<FarmPointPosition>,
        currentIndex: Int,
        safeSurface: (Location) -> Location?,
    ): Boolean {
        val tick = Bukkit.getCurrentTick().toLong()
        val config = runtime.settings.routeDelivery
        if (session.brokenDown) {
            reset(session, horse.location, currentIndex, tick)
            return false
        }
        val previous = session.stallWatchdog
        if (previous == null) {
            reset(session, horse.location, currentIndex, tick)
            return false
        }
        val anchor = session.stallAnchor
        val moved = anchor == null || anchor.world !== horse.world ||
            anchor.distanceSquared(horse.location) >= config.inactivityMovementDistance * config.inactivityMovementDistance
        val progressed = moved || currentIndex > session.stallRouteProgress
        if (progressed) {
            session.stallAnchor = horse.location.clone()
            session.stallRouteProgress = currentIndex
        }
        val result = FarmStallWatchdog.observe(
            previous,
            tick,
            progressed,
            config.inactivityReminderSeconds * 20L,
            config.inactivityResetSeconds * 20L,
        )
        session.stallWatchdog = result.state
        return when (result.action) {
            FarmStallAction.NONE -> false
            FarmStallAction.REMIND -> {
                port.showScreenTitle(rider, MessageKey.FARM_ROUTE_STALLED, scope = "route_watchdog")
                false
            }
            FarmStallAction.RELEASE -> {
                recover(runtime, horse, session, points, currentIndex, tick, safeSurface)
                true
            }
        }
    }

    fun rescueSuffocatingGunner(
        event: EntityDamageEvent,
        runtimes: Collection<FarmRuntime>,
        sessions: Map<String, FarmFoodDeliverySession>,
        safeSurface: (Location) -> Location?,
    ): Boolean {
        if (event.cause != EntityDamageEvent.DamageCause.SUFFOCATION) return false
        val player = event.entity as? Player ?: return false
        val (zoneId, session) = sessions.entries.firstOrNull { (_, activeSession) ->
            activeSession.gunnerId == player.uniqueId
        }?.toPair() ?: return false
        val runtime = runtimes.firstOrNull {
            it.settings.id == zoneId && it.state.sequence == session.sequence &&
                it.state.phase == FarmPhase.INCIDENT && it.state.incidentType == FarmIncidentType.FOOD_DELIVERY
        } ?: return false
        event.isCancelled = true
        (session.gunnerSeatId?.let(Bukkit::getEntity) as? Interaction)?.let(vehiclePassengers::ejectAll)
        gunner.release(player, zoneId, session, "suffocation_rescue")
        session.escortIds += player.uniqueId
        val route = routes.route(zoneId, session.routeName)
        val point = route?.points?.getOrNull((runtime.state.incidentProgress - 1).coerceAtLeast(0))
        val fallback = session.horseId?.let(Bukkit::getEntity)?.location ?: player.location
        val destination = point?.let(::location)?.let(safeSurface) ?: safeSurface(fallback) ?: fallback
        player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
        player.fallDistance = 0f
        port.showScreenTitle(player, MessageKey.FARM_ROUTE_PASSENGER_RESCUED, scope = "route_suffocation")
        debug.event(
            "farm_food_gunner_suffocation_rescued",
            "zone" to zoneId,
            "sequence" to session.sequence,
            "player" to player.name,
        )
        return true
    }

    private fun recover(
        runtime: FarmRuntime,
        horse: Horse,
        session: FarmFoodDeliverySession,
        points: List<FarmPointPosition>,
        currentIndex: Int,
        tick: Long,
        safeSurface: (Location) -> Location?,
    ) {
        val rider = horse.passengers.filterIsInstance<Player>().firstOrNull()
        val seat = session.gunnerSeatId?.let(Bukkit::getEntity) as? Interaction
        val passenger = seat?.passengers?.filterIsInstance<Player>()?.firstOrNull()
        vehiclePassengers.ejectAll(horse)
        seat?.let(vehiclePassengers::ejectAll)
        val point = points[(currentIndex - 1).coerceIn(0, points.lastIndex)]
        val destination = safeSurface(location(point)) ?: location(point)
        horse.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
        rider?.takeIf(Player::isOnline)?.let { player ->
            player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
            horse.addPassenger(player)
            port.sendActionBar(player, MessageKey.FARM_ROUTE_RETURNED)
        }
        seat?.takeIf(Entity::isValid)?.let { gunnerSeat ->
            gunnerSeat.teleport(destination.clone().add(0.0, 0.5, 0.0), PlayerTeleportEvent.TeleportCause.PLUGIN)
            passenger?.takeIf(Player::isOnline)?.let { player ->
                player.teleport(gunnerSeat.location, PlayerTeleportEvent.TeleportCause.PLUGIN)
                gunnerSeat.addPassenger(player)
            }
        }
        reset(session, destination, currentIndex, tick)
        debug.event(
            "farm_food_route_stall_recovered",
            "zone" to runtime.settings.id,
            "sequence" to session.sequence,
            "checkpoint" to currentIndex,
        )
    }

    fun reset(session: FarmFoodDeliverySession, anchor: Location, routeProgress: Int, tick: Long) {
        session.stallWatchdog = FarmStallWatchdogState(tick)
        session.stallAnchor = anchor.clone()
        session.stallRouteProgress = routeProgress
    }

    private fun location(point: FarmPointPosition): Location = Location(
        requireNotNull(Bukkit.getWorld(point.world)), point.x, point.y, point.z, point.yaw, point.pitch,
    )
}
