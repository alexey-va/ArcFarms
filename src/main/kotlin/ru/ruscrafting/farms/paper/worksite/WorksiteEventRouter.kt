package ru.ruscrafting.farms.paper.worksite

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.inventory.EquipmentSlot
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.paper.WorksiteModuleRegistry

/** Application-owned routing for cross-worksite events and participant safety. */
internal class WorksiteEventRouter(
    private val registry: WorksiteModuleRegistry,
    private val serviceItems: WorksiteServiceItemController,
    private val participantSafety: WorksiteParticipantSafety,
) {
    fun protectsTemporaryBlock(location: org.bukkit.Location): Boolean = registry.protectsTemporaryBlock(location)

    fun onBreakHigh(event: BlockBreakEvent): Boolean = registry.onBreakHigh(event)

    fun onBreakLowest(event: BlockBreakEvent): Boolean = registry.onBreakLowest(event)

    fun onBlockDamage(event: BlockDamageEvent): Boolean = registry.onBlockDamage(event)
    fun onBucketFill(event: org.bukkit.event.player.PlayerBucketFillEvent): Boolean = registry.onBucketFill(event)

    fun onBlockPlace(event: BlockPlaceEvent): Boolean = registry.onBlockPlace(event)

    fun onBreakMonitor(event: BlockBreakEvent) = registry.onBreakMonitor(event)

    fun onInteract(event: PlayerInteractEvent): Boolean {
        if (registry.onPlayerInteract(event, event.player)) return true
        val clicked = event.clickedBlock ?: return false
        return registry.onInteract(event, clicked, event.player)
    }

    fun guardMovement(event: PlayerMoveEvent): Boolean = registry.guardMovement(event)

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

    fun onAttackEntity(event: PrePlayerAttackEntityEvent): Boolean {
        // Paper pre-cancels non-attackable Interaction hitboxes. Preserve cancellation
        // from other plugins for entities that would otherwise receive an attack.
        if (event.isCancelled && event.willAttack()) return false
        val interact = PlayerInteractEntityEvent(event.player, event.attacked, EquipmentSlot.HAND)
        if (!registry.onInteractEntity(ActivityKind.MINE, interact)) return false
        event.isCancelled = true
        return true
    }

    fun onInteractEntity(event: PlayerInteractEntityEvent, fallback: () -> Unit) {
        if (!onInteractEntity(event)) fallback()
    }

    fun onEntityDeath(event: EntityDeathEvent, fallback: () -> Unit) {
        if (!registry.onEntityDeath(event)) fallback()
    }

    fun onEntityDamage(event: EntityDamageEvent, fallback: () -> Unit) {
        if (!registry.onEntityDamage(event)) fallback()
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
