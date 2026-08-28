package ru.ruscrafting.farms.paper.farm.incident.route

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Horse
import org.bukkit.entity.Interaction
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import java.util.UUID

/** Cooperative cart seat, temporary rifle and bounded hitscan visuals. */
internal class FarmFoodDeliveryGunner(
    plugin: Plugin,
    locale: ArcFarmsLocale,
    private val settings: () -> ArcFarmsConfig,
    private val debug: ArcFarmsDebug,
    private val port: WorksiteRuntimePort,
) {
    private val gear = FarmFoodDeliveryGear(plugin, locale, debug)
    private val shotAt = mutableMapOf<UUID, Long>()
    private val inventoryWarnings = mutableSetOf<UUID>()
    private val pendingRemounts = mutableSetOf<UUID>()

    fun owns(item: ItemStack?): Boolean = gear.owns(item)

    fun remove(player: Player, reason: String) = gear.remove(player, reason = reason)

    fun armDriver(player: Player, runtime: FarmRuntime, session: FarmFoodDeliverySession) {
        equip(player, runtime, session)
    }

    fun armEscort(player: Player, runtime: FarmRuntime, session: FarmFoodDeliverySession): Boolean =
        equip(player, runtime, session)

    fun mount(
        player: Player,
        runtime: FarmRuntime,
        session: FarmFoodDeliverySession,
        seat: Interaction,
        horse: Horse,
    ): Boolean {
        val driver = horse.passengers.filterIsInstance<Player>().firstOrNull()
        if (driver == null || session.brokenDown) {
            port.sendActionBar(player, if (session.brokenDown) MessageKey.FARM_ROUTE_BROKEN else MessageKey.FARM_ROUTE_GUNNER_NEEDS_DRIVER)
            return true
        }
        if (driver.uniqueId == player.uniqueId) return true
        val current = seat.passengers.filterIsInstance<Player>().firstOrNull()
        if (current != null && current.uniqueId != player.uniqueId) {
            port.sendActionBar(player, MessageKey.FARM_ROUTE_GUNNER_OCCUPIED)
            return true
        }
        if (!equip(player, runtime, session)) return true
        if (current == null && !seat.addPassenger(player)) {
            gear.remove(player, runtime.settings.id, session.sequence, "mount_failed")
            return true
        }
        session.gunnerId = player.uniqueId
        session.ambushCrewIds.remove(player.uniqueId)
        port.sendActionBar(player, MessageKey.FARM_ROUTE_GUNNER_MOUNTED)
        player.playSound(player.location, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.75f, 1.15f)
        debug.event(
            "farm_food_gunner_mounted", "zone" to runtime.settings.id,
            "sequence" to session.sequence, "player" to player.name,
        )
        return true
    }

    fun reconcile(runtime: FarmRuntime, session: FarmFoodDeliverySession, horse: Horse) {
        val seat = session.gunnerSeatId?.let(Bukkit::getEntity) as? Interaction
        val mountedDriver = horse.passengers.filterIsInstance<Player>().firstOrNull()
        val mountedGunner = seat?.passengers?.filterIsInstance<Player>()?.firstOrNull()
        val previous = setOfNotNull(session.riderId, session.gunnerId)
        val current = setOfNotNull(mountedDriver?.uniqueId, mountedGunner?.uniqueId)
        (previous - current - session.ambushCrewIds).forEach { playerId ->
            Bukkit.getPlayer(playerId)?.let {
                gear.remove(it, runtime.settings.id, session.sequence, "crew_dismounted")
            }
            shotAt.remove(playerId)
            inventoryWarnings.remove(playerId)
            pendingRemounts.remove(playerId)
        }
        current.forEach(session.ambushCrewIds::remove)
        session.riderId = mountedDriver?.uniqueId ?: session.riderId?.takeIf(session.ambushCrewIds::contains)
        session.gunnerId = mountedGunner?.uniqueId ?: session.gunnerId?.takeIf(session.ambushCrewIds::contains)
        mountedDriver?.let { equip(it, runtime, session) }
        if (mountedGunner != null && !equip(mountedGunner, runtime, session)) {
            seat.eject()
            session.gunnerId = null
        }
        session.ambushCrewIds.mapNotNull(Bukkit::getPlayer).filter(Player::isOnline).forEach { equip(it, runtime, session) }
        session.escortIds.mapNotNull(Bukkit::getPlayer).filter(Player::isOnline).forEach { equip(it, runtime, session) }
    }

    fun interact(event: PlayerInteractEvent, runtime: FarmRuntime?, session: FarmFoodDeliverySession?): Boolean {
        if (event.hand != EquipmentSlot.HAND || event.action !in setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)) return false
        val player = event.player
        if (!gear.owns(player.inventory.itemInMainHand)) return false
        event.isCancelled = true
        val participant = session != null && player.uniqueId in buildSet {
            session.riderId?.let(::add)
            session.gunnerId?.let(::add)
            addAll(session.escortIds)
            addAll(session.ambushCrewIds)
        }
        if (runtime == null || session == null || !participant || !gear.owns(
                player.inventory.itemInMainHand,
                runtime.settings.id,
                session.sequence,
                player.uniqueId,
            )
        ) {
            gear.remove(player, reason = "orphaned_rifle")
            return true
        }
        val mounted = player.uniqueId in session.escortIds || player.uniqueId in session.ambushCrewIds ||
            when (player.uniqueId) {
                session.riderId -> (session.horseId?.let(Bukkit::getEntity) as? Horse)
                    ?.passengers?.filterIsInstance<Player>()?.any { it.uniqueId == player.uniqueId } == true
                session.gunnerId -> (session.gunnerSeatId?.let(Bukkit::getEntity) as? Interaction)
                    ?.passengers?.filterIsInstance<Player>()?.any { it.uniqueId == player.uniqueId } == true
                else -> false
            }
        if (!mounted) {
            gear.remove(player, runtime.settings.id, session.sequence, "crew_dismounted")
            if (session.riderId == player.uniqueId) session.riderId = null
            if (session.gunnerId == player.uniqueId) session.gunnerId = null
            return true
        }
        fire(player, runtime, session)
        return true
    }

    fun render(runtime: FarmRuntime, session: FarmFoodDeliverySession, cart: Location) {
        if (!settings().particles || cart.world.gameTime % TRAIL_INTERVAL_TICKS != 0L) return
        val gunner = session.gunnerId?.let(Bukkit::getPlayer)
        if (gunner == null || !gear.isHolding(gunner, runtime.settings.id, session.sequence)) {
            session.gunnerTrail.clear()
            return
        }
        val limit = runtime.settings.routeDelivery.gunnerTrailLength
        if (limit <= 0) return
        if (session.gunnerTrail.peekLast()?.distanceSquared(cart) ?: Double.MAX_VALUE >= TRAIL_POINT_DISTANCE_SQUARED) {
            session.gunnerTrail.addLast(cart.clone().add(0.0, 0.35, 0.0))
        }
        while (session.gunnerTrail.size > limit) session.gunnerTrail.removeFirst()
        session.gunnerTrail.forEachIndexed { index, point ->
            val alpha = (index + 1).toDouble() / session.gunnerTrail.size.coerceAtLeast(1)
            point.world.spawnParticle(
                Particle.DUST,
                point,
                1,
                0.02,
                0.02,
                0.02,
                0.0,
                Particle.DustOptions(
                    Color.fromRGB(
                        (130 + 80 * alpha).toInt(),
                        (80 + 80 * alpha).toInt(),
                        (45 + 35 * alpha).toInt(),
                    ),
                    0.8f,
                ),
            )
        }
    }

    /** Keeps the gunner mounted while the synthetic cart seat follows the moving horse. */
    fun moveSeat(
        runtime: FarmRuntime,
        session: FarmFoodDeliverySession,
        seat: Interaction,
        destination: Location,
    ) {
        val passenger = seat.passengers.filterIsInstance<Player>().firstOrNull()
        // Paper 1.21.10+ retains passengers by default. Verify the relationship
        // because a client dismount racing this synthetic seat can still desync.
        val moved = seat.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
        if (!moved || passenger == null || passenger.vehicle === seat) return
        if (!passenger.isOnline) return
        if (seat.addPassenger(passenger)) return
        if (!pendingRemounts.add(passenger.uniqueId)) return
        port.runLater(1L) {
            try {
                if (!seat.isValid || !passenger.isOnline || session.gunnerId != passenger.uniqueId) return@runLater
                if (passenger.vehicle === seat || (passenger.vehicle == null && seat.addPassenger(passenger))) return@runLater
                debug.event(
                    "farm_food_gunner_remount_failed", "zone" to runtime.settings.id,
                    "sequence" to session.sequence, "player" to passenger.name,
                )
            } finally {
                pendingRemounts.remove(passenger.uniqueId)
            }
        }
    }

    fun release(player: Player, zoneId: String, session: FarmFoodDeliverySession, reason: String) {
        val wasCrew = session.riderId == player.uniqueId || session.gunnerId == player.uniqueId ||
            player.uniqueId in session.escortIds || player.uniqueId in session.ambushCrewIds
        if (session.riderId == player.uniqueId) session.riderId = null
        if (session.gunnerId == player.uniqueId) session.gunnerId = null
        session.escortIds.remove(player.uniqueId)
        session.ambushCrewIds.remove(player.uniqueId)
        if (wasCrew) {
            gear.remove(player, zoneId, session.sequence, reason)
        }
        shotAt.remove(player.uniqueId)
        inventoryWarnings.remove(player.uniqueId)
        pendingRemounts.remove(player.uniqueId)
    }

    fun clear(zoneId: String, session: FarmFoodDeliverySession, reason: String) {
        buildSet {
            session.riderId?.let(::add)
            session.gunnerId?.let(::add)
            addAll(session.escortIds)
            addAll(session.ambushCrewIds)
        }.forEach { playerId ->
            Bukkit.getPlayer(playerId)?.let { gear.remove(it, zoneId, session.sequence, reason) }
            shotAt.remove(playerId)
            inventoryWarnings.remove(playerId)
            pendingRemounts.remove(playerId)
        }
        session.riderId = null
        session.gunnerId = null
        session.escortIds.clear()
        session.ambushCrewIds.clear()
        session.gunnerTrail.clear()
    }

    fun cleanup(reason: String) {
        Bukkit.getOnlinePlayers().forEach { gear.remove(it, reason = reason) }
        shotAt.clear()
        inventoryWarnings.clear()
        pendingRemounts.clear()
    }

    private fun equip(player: Player, runtime: FarmRuntime, session: FarmFoodDeliverySession): Boolean {
        if (gear.give(player, runtime.settings.id, session.sequence, runtime.settings.routeDelivery)) {
            inventoryWarnings.remove(player.uniqueId)
            return true
        }
        if (inventoryWarnings.add(player.uniqueId)) {
            port.sendActionBar(player, MessageKey.FARM_ROUTE_GUNNER_INVENTORY_FULL)
        }
        return false
    }

    private fun fire(player: Player, runtime: FarmRuntime, session: FarmFoodDeliverySession) {
        val config = runtime.settings.routeDelivery
        val nowTick = player.world.gameTime
        val previous = shotAt[player.uniqueId] ?: Long.MIN_VALUE / 2
        if (nowTick - previous < config.rifleCooldownTicks) return
        shotAt[player.uniqueId] = nowTick
        val start = player.eyeLocation.clone().add(player.eyeLocation.direction.multiply(0.55))
        val direction = player.eyeLocation.direction.normalize()
        val hit = player.world.rayTraceEntities(start, direction, config.rifleRange, RAY_SIZE) { entity ->
            entity.uniqueId in session.monsterIds && entity.isValid && !entity.isDead
        }
        val end = hit?.hitPosition?.toLocation(player.world) ?: start.clone().add(direction.clone().multiply(config.rifleRange))
        renderShot(start, end)
        player.world.playSound(start, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.85f, 0.65f)
        val target = hit?.hitEntity as? Mob ?: return
        target.damage(config.rifleDamage, player)
        target.world.spawnParticle(Particle.CRIT, target.location.add(0.0, target.height * 0.55, 0.0), 12, 0.25, 0.25, 0.25, 0.08)
        player.playSound(player.location, Sound.ENTITY_ARROW_HIT_PLAYER, 0.75f, 1.25f)
    }

    private fun renderShot(start: Location, end: Location) {
        val delta = end.toVector().subtract(start.toVector())
        val length = delta.length()
        if (length <= 0.01) return
        val step = delta.normalize().multiply(PARTICLE_SPACING)
        val cursor = start.clone()
        repeat((length / PARTICLE_SPACING).toInt().coerceAtMost(MAX_PARTICLES)) {
            cursor.world.spawnParticle(Particle.ELECTRIC_SPARK, cursor, 1, 0.0, 0.0, 0.0, 0.0)
            cursor.add(step)
        }
    }

    private companion object {
        const val RAY_SIZE = 0.65
        const val PARTICLE_SPACING = 1.2
        const val MAX_PARTICLES = 72
        const val TRAIL_POINT_DISTANCE_SQUARED = 0.16
        const val TRAIL_INTERVAL_TICKS = 2L
    }
}
