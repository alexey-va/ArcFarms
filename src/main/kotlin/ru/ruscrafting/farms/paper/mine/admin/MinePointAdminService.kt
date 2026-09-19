package ru.ruscrafting.farms.paper.mine.admin

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.MineLocationKeys
import ru.ruscrafting.farms.domain.MineLocationPosition
import ru.ruscrafting.farms.domain.MineZoneLocations
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.mine.point.MinePointService
import ru.ruscrafting.farms.config.MineZoneSettings
import ru.ruscrafting.farms.paper.ActivityRegion
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import net.kyori.adventure.text.Component
import java.util.logging.Level

/** Admin capture facade for explicit mine workshop and side-working points. */
internal class MinePointAdminService(
    private val locale: ArcFarmsLocale,
    private val port: WorksiteAudiencePort,
    private val state: WorksiteStatePort,
    private val debug: ArcFarmsDebug,
    private val pointService: MinePointService,
    private val zone: (String) -> MineZoneSettings?,
    private val region: (MineZoneSettings) -> ActivityRegion?,
) {
    fun points(zoneId: String): MineZoneLocations? = zone(zoneId)?.let { configured ->
        region(configured)?.let { pointService.zone(configured.id, it.world.name) }
    }

    fun validate(configuredZones: List<MineZoneSettings>) {
        pointService.snapshot().zones.keys.forEach { zoneId ->
            val configured = requireNotNull(configuredZones.firstOrNull { it.id == zoneId }) {
                "Mine location override references unknown zone $zoneId"
            }
            val activeRegion = requireNotNull(region(configured)) {
                "Mine location override cannot resolve zone $zoneId"
            }
            val effective = pointService.zone(zoneId, activeRegion.world.name) ?: return@forEach
            (effective.workshop.values + effective.workings.values).forEach { point ->
                val world = requireNotNull(Bukkit.getWorld(point.world)) {
                    "Mine point $zoneId world ${point.world} is not loaded"
                }
                require(world === activeRegion.world && activeRegion.contains(Location(world, point.x, point.y, point.z))) {
                    "Mine point $zoneId is outside ${activeRegion.label}"
                }
            }
        }
    }

    fun set(player: Player, zoneId: String, rawKind: String): Boolean {
        val kind = MineLocationKeys.canonical(rawKind)
        if (kind == null) {
            port.sendChat(player, MessageKey.ADMIN_HELP)
            return false
        }
        val active = zone(zoneId)
        if (active == null) {
            port.sendChat(player, MessageKey.ADMIN_ZONE_UNKNOWN, mapOf("zone" to locale.text(zoneId)))
            return false
        }
        val activeRegion = region(active)
        if (activeRegion == null || player.world !== activeRegion.world || !activeRegion.contains(player.location)) {
            port.sendChat(player, MessageKey.ADMIN_POINT_OUTSIDE)
            return false
        }
        val position = try {
            MineLocationPosition.capture(
                world = player.world.name,
                x = player.location.x,
                y = player.location.y,
                z = player.location.z,
                yaw = player.location.yaw,
            )
        } catch (failure: Exception) {
            port.sendChat(player, MessageKey.GENERIC_ERROR)
            return false
        }
        val canonicalZone = active.id
        try {
            pointService.save(canonicalZone, kind, position).whenComplete { _, failure ->
                if (failure != null) state.log(Level.SEVERE, "Could not persist mine point $canonicalZone/$kind", failure)
            }
        } catch (failure: Exception) {
            state.log(Level.SEVERE, "Could not schedule mine point $canonicalZone/$kind", failure)
            port.sendChat(player, MessageKey.GENERIC_ERROR)
            return false
        }
        sendSaved(player, canonicalZone, kind)
        debug.event(
            "mine_admin_point_saved",
            "player" to player.name,
            "zone" to canonicalZone,
            "point" to kind,
            "world" to position.world,
            "x" to position.x,
            "y" to position.y,
            "z" to position.z,
            "yaw" to position.yaw,
        )
        return true
    }

    fun clear(player: Player, zoneId: String, rawKind: String): Boolean {
        val kind = MineLocationKeys.canonical(rawKind)
        if (kind == null) {
            port.sendChat(player, MessageKey.ADMIN_HELP)
            return false
        }
        val active = zone(zoneId)
        if (active == null) {
            port.sendChat(player, MessageKey.ADMIN_ZONE_UNKNOWN, mapOf("zone" to locale.text(zoneId)))
            return false
        }
        if (!pointService.overridden(active.id, kind)) {
            port.sendChat(
                player,
                MessageKey.ADMIN_POINT_NOT_OVERRIDDEN,
                mapOf("point" to pointLabel(player, kind)),
            )
            return false
        }
        try {
            pointService.clear(active.id, kind).whenComplete { _, failure ->
                if (failure != null) state.log(Level.SEVERE, "Could not clear mine point ${active.id}/$kind", failure)
            }
        } catch (failure: Exception) {
            state.log(Level.SEVERE, "Could not schedule mine point clear ${active.id}/$kind", failure)
            port.sendChat(player, MessageKey.GENERIC_ERROR)
            return false
        }
        port.sendChat(
            player,
            MessageKey.ADMIN_POINT_CLEARED,
            mapOf("point" to pointLabel(player, kind), "zone" to locale.text(active.id)),
        )
        debug.event("mine_admin_point_cleared", "player" to player.name, "zone" to active.id, "point" to kind)
        return true
    }

    private fun sendSaved(player: Player, zoneId: String, kind: String) {
        port.sendChat(
            player,
            MessageKey.ADMIN_POINT_SAVED,
            mapOf("point" to pointLabel(player, kind), "zone" to locale.text(zoneId)),
        )
    }

    private fun pointLabel(player: Player, kind: String): Component =
        locale.renderPath("admin.mine-point.$kind", player)
}
