package ru.ruscrafting.farms.paper

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent

class ArcFarmsListener(
    private val service: ArcFarmsService,
    private val menu: ArcFarmsMenu,
) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBreakHigh(event: BlockBreakEvent) = service.onBreakHigh(event)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreakMonitor(event: BlockBreakEvent) = service.onBreakMonitor(event)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockFade(event: BlockFadeEvent) = service.onBlockFade(event)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) = service.onBlockPlace(event)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) = service.onInteract(event)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) = service.onMove(event)

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) = service.onQuit(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) = service.onJoin(event.player)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityDeath(event: EntityDeathEvent) = service.onEntityDeath(event)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) = service.onEntityChangeBlock(event)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteractEntity(event: PlayerInteractEntityEvent) = service.onInteractEntity(event)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityDamage(event: EntityDamageEvent) = service.onEntityDamage(event)

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) = menu.onClick(event)

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) = menu.onDrag(event)
}
