package ru.ruscrafting.farms.paper

import org.bukkit.GameMode
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** Injectable seam for Paper behavior that MockBukkit cannot model completely. */
internal interface MineBlockEffects {
    fun captureDrops(block: Block, tool: ItemStack, player: Player): List<ItemStack>

    fun deliverRewards(
        player: Player,
        block: Block,
        drops: List<ItemStack>,
        experience: Int,
        toolSlot: Int,
        toolSnapshot: ItemStack,
    )
}

internal object PaperMineBlockEffects : MineBlockEffects {
    override fun captureDrops(block: Block, tool: ItemStack, player: Player): List<ItemStack> =
        block.getDrops(tool, player).map(ItemStack::clone)

    override fun deliverRewards(
        player: Player,
        block: Block,
        drops: List<ItemStack>,
        experience: Int,
        toolSlot: Int,
        toolSnapshot: ItemStack,
    ) {
        drops.forEach { block.world.dropItemNaturally(block.location.toCenterLocation(), it) }
        if (experience > 0 && player.isOnline) player.giveExp(experience)
        if (!player.isOnline || player.gameMode == GameMode.CREATIVE) return
        val currentTool = player.inventory.getItem(toolSlot)
        if (currentTool != null && currentTool.isSimilar(toolSnapshot)) {
            player.inventory.setItem(toolSlot, player.damageItemStack(currentTool, 1))
        }
    }
}
