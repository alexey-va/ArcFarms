package ru.ruscrafting.farms.paper.farm.admin

import org.bukkit.Material
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.ArcFarmsRuntimeValidator
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.care.FarmCarePlanService
import ru.ruscrafting.farms.paper.farm.point.FarmPointService
import ru.ruscrafting.farms.paper.farm.incident.processing.FarmProcessingIncident
import java.util.logging.Level

/** Typed admin facade for viewing and mutating farm point overrides. */
internal class FarmPointAdminService(
    private val settings: () -> ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
    private val port: WorksiteRuntimePort,
    private val pointService: FarmPointService,
    private val points: FarmPointProvider,
    private val carePlans: FarmCarePlanService,
    private val validator: ArcFarmsRuntimeValidator,
    private val processing: FarmProcessingIncident,
    private val runtimes: () -> Collection<FarmRuntime>,
    private val refresh: (FarmRuntime, FarmPointKind, Player, String) -> Unit,
) {
    fun points(zoneId: String): Map<FarmPointKind, FarmPointPosition>? {
        val runtime = runtimes().firstOrNull { it.settings.id == zoneId } ?: return null
        return FarmPointKind.entries.mapNotNull { kind ->
            val configured = pointService.configured(runtime.settings.id, kind)
            val resolved = configured ?: when (kind) {
                FarmPointKind.PROCESSING -> null
                FarmPointKind.PROCESSING_INPUT,
                FarmPointKind.PROCESSING_OUTPUT,
                -> pointService.configured(runtime.settings.id, FarmPointKind.PROCESSING)
                    ?.let { points.resolve(runtime, kind) }
                FarmPointKind.HIVE,
                FarmPointKind.IRRIGATION,
                FarmPointKind.COVERS,
                FarmPointKind.SCARECROWS,
                FarmPointKind.PEN,
                -> carePlans.fixturePoint(runtime, kind)
                else -> points.resolve(runtime, kind)
            }
            resolved?.let { kind to it }
        }.toMap()
    }

    fun overridden(zoneId: String): Set<FarmPointKind>? {
        if (runtimes().none { it.settings.id == zoneId }) return null
        return pointService.overridden(zoneId)
    }

    fun set(player: Player, zoneId: String, kind: FarmPointKind): Boolean {
        val runtime = runtime(zoneId, player) ?: return false
        if (kind != FarmPointKind.TRAVEL && !runtime.region.contains(player.location)) {
            port.sendChat(player, MessageKey.ADMIN_POINT_OUTSIDE)
            return false
        }
        if (kind != FarmPointKind.TRAVEL) {
            val floor = player.location.block.getRelative(org.bukkit.block.BlockFace.DOWN)
            if (floor.type == Material.FARMLAND || player.location.block.type.name in runtime.settings.crops) {
                port.sendChat(player, MessageKey.ADMIN_POINT_ON_BED)
                return false
            }
        }
        val position = FarmPointPosition(
            player.world.name,
            player.location.x,
            player.location.y,
            player.location.z,
            player.location.yaw,
            player.location.pitch,
        )
        if (kind in PROCESSING_POINTS) {
            processing.validate(runtime, position)?.let { failure ->
                port.sendChat(
                    player,
                    MessageKey.ADMIN_POINT_PROCESSING_INVALID,
                    mapOf(
                        "reason" to locale.renderPath(
                            "admin.processing-placement.${failure.name.lowercase()}",
                            player,
                        ),
                    ),
                )
                return false
            }
        }
        try {
            pointService.save(zoneId, kind, position) { candidate -> validator.validateLocations(settings(), candidate) }
        } catch (failure: Exception) {
            port.log(Level.SEVERE, "Could not save farm point $zoneId/$kind", failure)
            port.sendChat(player, MessageKey.GENERIC_ERROR)
            return false
        }
        refresh(runtime, kind, player, "admin_point_changed")
        port.sendChat(
            player,
            MessageKey.ADMIN_POINT_SAVED,
            mapOf(
                "point" to locale.renderPath("admin.point.${kind.name.lowercase()}", player),
                "zone" to locale.text(zoneId),
            ),
        )
        if (kind in PROCESSING_POINTS) {
            processing.preview(runtime, player)
            port.sendChat(player, MessageKey.ADMIN_POINT_PROCESSING_SAVED)
        }
        debug.event(
            "farm_admin_point_saved",
            "player" to player.name,
            "zone" to zoneId,
            "point" to kind,
            "world" to position.world,
            "x" to position.x,
            "y" to position.y,
            "z" to position.z,
        )
        return true
    }

    fun clear(player: Player, zoneId: String, kind: FarmPointKind): Boolean {
        val runtime = runtime(zoneId, player) ?: return false
        if (pointService.configured(zoneId, kind) == null) {
            port.sendChat(
                player,
                MessageKey.ADMIN_POINT_NOT_OVERRIDDEN,
                mapOf("point" to locale.renderPath("admin.point.${kind.name.lowercase()}", player)),
            )
            return false
        }
        try {
            pointService.clear(zoneId, kind) { candidate -> validator.validateLocations(settings(), candidate) }
        } catch (failure: Exception) {
            port.log(Level.SEVERE, "Could not clear farm point $zoneId/$kind", failure)
            port.sendChat(player, MessageKey.GENERIC_ERROR)
            return false
        }
        refresh(runtime, kind, player, "admin_point_cleared")
        port.sendChat(
            player,
            MessageKey.ADMIN_POINT_CLEARED,
            mapOf(
                "point" to locale.renderPath("admin.point.${kind.name.lowercase()}", player),
                "zone" to locale.text(zoneId),
            ),
        )
        debug.event("farm_admin_point_cleared", "player" to player.name, "zone" to zoneId, "point" to kind)
        return true
    }

    private fun runtime(zoneId: String, player: Player): FarmRuntime? =
        runtimes().firstOrNull { it.settings.id == zoneId } ?: run {
            port.sendChat(player, MessageKey.ADMIN_ZONE_UNKNOWN, mapOf("zone" to locale.text(zoneId)))
            null
        }

    private companion object {
        val PROCESSING_POINTS = setOf(
            FarmPointKind.PROCESSING,
            FarmPointKind.PROCESSING_INPUT,
            FarmPointKind.PROCESSING_OUTPUT,
        )
    }
}
