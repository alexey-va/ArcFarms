package ru.ruscrafting.farms.paper.worksite

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** Adds temporary equipment without moving personal gear or changing the selected slot. */
internal object PlayerHeldItemLoadout {
    fun place(player: Player, items: List<ItemStack>): Boolean {
        require(items.isNotEmpty()) { "A loadout needs at least one item" }
        val inventory = player.inventory
        val emptySlots = inventory.storageContents.indices.filter { slot ->
            inventory.getItem(slot)?.type?.isAir != false
        }
        if (emptySlots.size < items.size) return false
        items.zip(emptySlots).forEach { (item, slot) -> inventory.setItem(slot, item.clone()) }
        return true
    }
}
