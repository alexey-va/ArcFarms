package ru.ruscrafting.farms.paper.worksite

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** Places the primary temporary item in the selected hotbar slot without destroying personal gear. */
internal object PlayerHeldItemLoadout {
    fun place(player: Player, items: List<ItemStack>): Boolean {
        require(items.isNotEmpty()) { "A held loadout needs at least one item" }
        val inventory = player.inventory
        val before = inventory.storageContents.map { it?.clone() }.toTypedArray()
        val selected = inventory.heldItemSlot
        val held = inventory.getItem(selected)?.takeUnless { it.type.isAir }
        if (held != null) {
            val destination = ((9 until inventory.storageContents.size) + (0 until 9))
                .firstOrNull { slot -> slot != selected && inventory.getItem(slot)?.type?.isAir != false }
                ?: return false
            inventory.setItem(destination, held)
            inventory.setItem(selected, null)
        }
        inventory.setItem(selected, items.first().clone())
        items.drop(1).forEach { extra ->
            if (inventory.addItem(extra.clone()).isNotEmpty()) {
                inventory.storageContents = before
                return false
            }
        }
        return true
    }
}
