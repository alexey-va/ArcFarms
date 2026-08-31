package ru.ruscrafting.farms.paper

import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort

/**
 * Defers a Paper inventory-view change out of InventoryClickEvent and drops it
 * when reload or another player action has already replaced the clicked view.
 */
internal fun WorksiteTaskPort.deferInventoryTransition(
    player: Player,
    expectedTop: Inventory,
    transition: () -> Unit,
): Boolean {
    val lifecycle = lifecycleToken()
    return runLater(lifecycle, 1L) {
        if (!player.isOnline || player.openInventory.topInventory !== expectedTop) return@runLater
        transition()
    }
}
