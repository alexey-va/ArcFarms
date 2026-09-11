package ru.ruscrafting.farms.paper

import io.papermc.paper.event.entity.EntityLoadCrossbowEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockGrowEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.block.MoistureChangeEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.event.world.ChunkLoadEvent

class ArcFarmsListener(
    private val service: ArcFarmsService,
    private val menu: ArcFarmsMenu,
    private val isInternalTransport: (PlayerTeleportEvent) -> Boolean = { false },
) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTemporaryEntityExplosion(event: org.bukkit.event.entity.EntityExplodeEvent) {
        event.blockList().removeIf { service.protectsTemporaryBlock(it.location) }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTemporaryBlockExplosion(event: org.bukkit.event.block.BlockExplodeEvent) {
        event.blockList().removeIf { service.protectsTemporaryBlock(it.location) }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTemporaryFluid(event: org.bukkit.event.block.BlockFromToEvent) {
        if (service.protectsTemporaryBlock(event.block.location) || service.protectsTemporaryBlock(event.toBlock.location)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTemporaryBucket(event: org.bukkit.event.player.PlayerBucketEmptyEvent) {
        if (service.protectsTemporaryBlock(event.block.location)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTemporaryBucketFill(event: org.bukkit.event.player.PlayerBucketFillEvent) {
        if (service.protectsTemporaryBlock(event.block.location)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onChunkLoad(event: ChunkLoadEvent) = service.onChunkLoad(event.chunk)

    @EventHandler(priority = EventPriority.LOWEST)
    fun onBreakLowest(event: BlockBreakEvent) = service.onBreakLowest(event)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onBlockDamage(event: BlockDamageEvent) {
        service.onBlockDamage(event)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onBreakHigh(event: BlockBreakEvent) {
        service.onBreakHigh(event)
        if (service.protectsTemporaryBlock(event.block.location)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreakMonitor(event: BlockBreakEvent) = service.onBreakMonitor(event)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onBlockDrop(event: BlockDropItemEvent) = service.onBlockDrop(event)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockFade(event: BlockFadeEvent) = service.onBlockFade(event)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBurn(event: BlockBurnEvent) {
        if (service.protectsTemporaryBlock(event.block.location)) event.isCancelled = true
        else service.onBlockBurn(event)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockIgnite(event: BlockIgniteEvent) {
        if (service.protectsTemporaryBlock(event.block.location)) event.isCancelled = true
        else service.onBlockIgnite(event)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockSpread(event: BlockSpreadEvent) = service.onBlockSpread(event)

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockGrow(event: BlockGrowEvent) = service.onBlockGrow(event)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onBlockPlace(event: BlockPlaceEvent) = service.onBlockPlace(event)

    @EventHandler(priority = EventPriority.LOWEST)
    fun onInteractLowest(event: PlayerInteractEvent) = service.onInteractLowest(event)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInteract(event: PlayerInteractEvent) {
        service.onInteract(event)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onMoistureChange(event: MoistureChangeEvent) = service.onMoistureChange(event)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onBlockFromTo(event: BlockFromToEvent) = service.onBlockFromTo(event)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (event !is PlayerTeleportEvent) service.onMove(event)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        if (event !is PlayerPortalEvent && !isInternalTransport(event)) service.onTeleport(event)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPortal(event: PlayerPortalEvent) = service.onPortal(event)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onQuit(event: PlayerQuitEvent) = service.onQuit(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) = service.onJoin(event.player)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityDeath(event: EntityDeathEvent) = service.onEntityDeath(event)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) = service.onEntityChangeBlock(event)

    @EventHandler(priority = EventPriority.LOWEST)
    fun onInteractEntityLowest(event: PlayerInteractEntityEvent) = service.onInteractEntityLowest(event)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInteractEntity(event: PlayerInteractEntityEvent) = service.onInteractEntity(event)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onVehicleEnter(event: VehicleEnterEvent) = service.onVehicleEnter(event)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDismount(event: EntityDismountEvent) {
        service.onDismount(event)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityDamage(event: EntityDamageEvent) = service.onEntityDamage(event)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityTarget(event: EntityTargetLivingEntityEvent) = service.onEntityTarget(event)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onProjectileHit(event: ProjectileHitEvent) = service.onProjectileHit(event)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onLoadCrossbow(event: EntityLoadCrossbowEvent) {
        service.onLoadCrossbow(event)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onShootBow(event: EntityShootBowEvent) {
        service.onShootBow(event)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrop(event: PlayerDropItemEvent) = service.onDrop(event)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onFish(event: PlayerFishEvent) = service.onFish(event)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDeath(event: PlayerDeathEvent) = service.onDeath(event)

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        service.onInventoryClick(event)
        menu.onClick(event)
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        service.onInventoryDrag(event)
        menu.onDrag(event)
    }
}
