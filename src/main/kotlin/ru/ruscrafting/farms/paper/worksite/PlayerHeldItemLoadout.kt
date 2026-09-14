package ru.ruscrafting.farms.paper.worksite

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** Adds temporary equipment to the selected empty slot first, without moving personal gear. */
internal object PlayerHeldItemLoadout {
    fun place(player: Player, items: List<ItemStack>): Boolean {
        require(items.isNotEmpty()) { "A loadout needs at least one item" }
        val inventory = player.inventory
        val preferredSlots = listOf(inventory.heldItemSlot) +
            inventory.storageContents.indices.filterNot { it == inventory.heldItemSlot }
        val emptySlots = preferredSlots.filter { slot ->
            inventory.getItem(slot)?.type?.isAir != false
        }
        if (emptySlots.size < items.size) return false
        items.zip(emptySlots).forEach { (item, slot) -> inventory.setItem(slot, item.clone()) }
        return true
    }
}
