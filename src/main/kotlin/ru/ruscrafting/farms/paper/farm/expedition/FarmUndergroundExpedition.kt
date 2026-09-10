package ru.ruscrafting.farms.paper.farm.expedition

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
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
import ru.ruscrafting.farms.paper.worksite.WorksiteExpeditionTravel
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayRenderer
import ru.ruscrafting.farms.persistence.FarmBurrowReturn
import java.nio.file.Path

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
    private val travel = WorksiteExpeditionTravel(plugin, tasks, access, state, variant.returnDirectory)

    fun retains(player: Player): Boolean = travel.retains(player)

    fun record(player: Player): FarmBurrowReturn? = travel.record(player)

    fun records(): Map<java.util.UUID, FarmBurrowReturn> = travel.records()

    fun isAuthorized(player: Player, destination: Location?): Boolean =
        travel.isAuthorized(player, destination)

    /** Commits the return point before moving the player into the temporary room. */
    fun enter(player: Player, runtime: FarmRuntime, room: FarmMoleBurrowScene) {
        if (!room.ready || retains(player) || !variant.active(runtime) || player.isDead || access.isAdminEditing(player) ||
            player.world !== room.world || player.location.distanceSquared(room.surface) > 25.0) return
        val sequence = runtime.state.sequence
        val placement = runtime.state.placementSequence
        val surface = player.location.clone()
        travel.enter(WorksiteExpeditionTravel.EntryRequest(player, runtime.settings.id, sequence,
            runtime.settings.permission, surface, variant.destination(room, player))) {
            variant.active(runtime) && runtime.state.sequence == sequence && runtime.state.placementSequence == placement && room.ready
        }
    }

    fun recover(player: Player) {
        travel.recover(player) { recovered ->
            if (recovered && variant == FarmUndergroundVariant.MOLES) audience.sendChat(player, MessageKey.FARM_MOLE_RECOVERED)
        }
    }

    fun exit(player: Player, room: FarmMoleBurrowScene? = null): Boolean {
        val record = travel.record(player) ?: room?.takeIf { it.contains(player.location) }?.let {
            FarmBurrowReturn(player.uniqueId, it.zoneId, it.sequence, it.surface.world.name,
                it.surface.x, it.surface.y, it.surface.z, player.location.yaw, player.location.pitch, System.currentTimeMillis())
        } ?: return true
        return travel.returnToSurface(player, record)
    }

    /** Recovery fallback for an explorer inside a journalled room whose async session has not loaded yet. */
    fun returnToSurface(player: Player, runtime: FarmRuntime, surface: ru.ruscrafting.farms.domain.FarmPointPosition): Boolean {
        val record = FarmBurrowReturn(player.uniqueId, runtime.settings.id, runtime.state.sequence,
            surface.world, surface.x, surface.y, surface.z, player.location.yaw, player.location.pitch, System.currentTimeMillis())
        return travel.returnToSurface(player, record)
    }

    fun evacuate(zone: String): Boolean {
        return travel.evacuate(zone)
    }

    fun reconcile(player: Player, inside: Boolean) {
        travel.reconcile(player, inside)
    }

    fun quit(player: Player) {
        travel.quit(player)
    }
}
