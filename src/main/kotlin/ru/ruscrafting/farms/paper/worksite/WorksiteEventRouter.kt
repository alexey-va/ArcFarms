package ru.ruscrafting.farms.paper.worksite

import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.entity.EntityDeathEvent
import ru.ruscrafting.farms.paper.WorksiteModuleRegistry

/** Application-owned routing for cross-worksite events and participant safety. */
internal class WorksiteEventRouter(
    private val registry: WorksiteModuleRegistry,
    private val serviceItems: WorksiteServiceItemController,
    private val participantSafety: WorksiteParticipantSafety,
) {
    fun protectsTemporaryBlock(location: org.bukkit.Location): Boolean = registry.protectsTemporaryBlock(location)

    fun onBreakHigh(event: BlockBreakEvent): Boolean = registry.onBreakHigh(event)

    fun onBreakMonitor(event: BlockBreakEvent) = registry.onBreakMonitor(event)

    fun onInteract(event: PlayerInteractEvent): Boolean {
        if (registry.onPlayerInteract(event, event.player)) return true
        val clicked = event.clickedBlock ?: return false
        return registry.onInteract(event, clicked, event.player)
    }

    fun onMove(event: PlayerMoveEvent): Boolean {
        val destination = event.to
        if (
            event.from.world == destination.world &&
            event.from.blockX == destination.blockX && event.from.blockY == destination.blockY &&
            event.from.blockZ == destination.blockZ
        ) return false
        return registry.onMove(event.from, destination, event.player)
    }

    fun onTeleport(event: PlayerTeleportEvent): Boolean {
        if (
            event.cause != PlayerTeleportEvent.TeleportCause.DISMOUNT &&
            !registry.retainOnTeleport(event.player, event.to)
        ) {
            release(event.player, WorksitePlayerReleaseReason.TELEPORT_OUT)
        }
        return onMove(event)
    }

    fun onInteractEntity(event: PlayerInteractEntityEvent): Boolean = registry.onInteractEntity(event)

    fun onInteractEntity(event: PlayerInteractEntityEvent, fallback: () -> Unit) {
        if (!onInteractEntity(event)) fallback()
    }

    fun onEntityDeath(event: EntityDeathEvent, fallback: () -> Unit) {
        if (!registry.onEntityDeath(event)) fallback()
    }

    fun updateVisuals(updateFarm: () -> Unit) {
        updateFarm()
        registry.updateVisuals()
    }

    fun onDrop(event: PlayerDropItemEvent): Boolean {
        val guarded = serviceItems.guardDrop(event.itemDrop.itemStack)
        if (guarded) event.isCancelled = true
        return guarded
    }

    fun onInventoryClick(event: InventoryClickEvent): Boolean = serviceItems.guardInventory(event)

    fun onInventoryDrag(event: InventoryDragEvent): Boolean = serviceItems.guardInventory(event)

    fun onDeath(player: Player, drops: MutableList<org.bukkit.inventory.ItemStack>) {
        drops.removeIf(serviceItems::isServiceItem)
        release(player, WorksitePlayerReleaseReason.DEATH)
    }

    fun onJoin(player: Player) {
        release(player, WorksitePlayerReleaseReason.JOIN_STALE)
        registry.recoverPlayer(player)
    }

    fun release(player: Player, reason: WorksitePlayerReleaseReason): WorksiteReleaseReport =
        participantSafety.release(player, reason)

    fun release(players: Iterable<Player>, reason: WorksitePlayerReleaseReason) {
        val failures = players.flatMap { player ->
            runCatching { release(player, reason) }
                .fold(onSuccess = WorksiteReleaseReport::ownerFailures, onFailure = ::listOf)
        }
        if (failures.isNotEmpty()) {
            throw IllegalStateException("Worksite player release failed for ${failures.size} owner(s)", failures.first()).also {
                failures.drop(1).forEach(it::addSuppressed)
            }
        }
    }
}
