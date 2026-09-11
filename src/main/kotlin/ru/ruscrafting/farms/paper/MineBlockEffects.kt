package ru.ruscrafting.farms.paper

import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** Injectable seam for Paper behavior that MockBukkit cannot model completely. */
internal interface MineBlockEffects {
    fun applyToolWear(
        player: Player,
        toolSlot: Int,
        toolSnapshot: ItemStack,
    )
}

internal object PaperMineBlockEffects : MineBlockEffects {
    override fun applyToolWear(
        player: Player,
        toolSlot: Int,
        toolSnapshot: ItemStack,
    ) {
        if (!player.isOnline || player.gameMode == GameMode.CREATIVE) return
        val currentTool = player.inventory.getItem(toolSlot)
        if (currentTool != null && currentTool.isSimilar(toolSnapshot)) {
            player.inventory.setItem(toolSlot, player.damageItemStack(currentTool, 1))
        }
    }
}
