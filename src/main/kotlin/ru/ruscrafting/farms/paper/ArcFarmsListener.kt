package ru.ruscrafting.farms.paper

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
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
    fun onInteract(event: PlayerInteractEvent) = service.onInteract(event)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) = service.onMove(event)

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) = service.onQuit(event.player)

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) = menu.onClick(event)

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) = menu.onDrag(event)
}
