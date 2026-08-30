package ru.ruscrafting.farms.paper.lumber.skidding

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEntityEvent
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.lumber.LumberRuntimeRegistry
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason

internal class LumberSkiddingController(
    private val registry: LumberRuntimeRegistry,
    private val scene: LumberBundleScene,
    private val access: WorksiteAccessPort,
) {
    fun onInteractEntity(event: PlayerInteractEntityEvent): Boolean {
        val identity = scene.identity(event.rightClicked) ?: return false
        val runtime = registry.byId(identity.zoneId) ?: return false
        if (runtime.state.sequence != identity.sequence) return false
        event.isCancelled = true
        if (!access.allowInteraction("lumber-bundle:${identity.zoneId}:${event.player.uniqueId}", 350L)) return true
        scene.pickup(runtime, identity.targetId, event.player)
        return true
    }

    fun onMove(from: Location, to: Location, player: Player): Boolean {
        val carry = scene.carrying(player.uniqueId) ?: return false
        val runtime = registry.byId(carry.zoneId) ?: return false
        if (runtime.station.contains(to)) return scene.deliver(runtime, player)
        if (!runtime.region.contains(to) && !runtime.station.contains(to)) {
            return scene.releasePlayer(player, WorksitePlayerReleaseReason.ZONE_EXIT)
        }
        return false
    }

    fun releasePlayer(player: Player, reason: WorksitePlayerReleaseReason) = scene.releasePlayer(player, reason)

    fun updateVisuals() = scene.updateCarried()

    fun cleanup() = scene.cleanup()
}
