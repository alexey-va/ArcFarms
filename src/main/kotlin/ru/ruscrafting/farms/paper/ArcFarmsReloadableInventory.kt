package ru.ruscrafting.farms.paper

import org.bukkit.entity.Player
import org.bukkit.inventory.InventoryHolder

/** Marker and redraw contract for every inventory owned by ArcFarms. */
internal interface ArcFarmsReloadableInventory : InventoryHolder {
    fun refresh(player: Player)
}
