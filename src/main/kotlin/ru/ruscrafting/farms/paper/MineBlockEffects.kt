package ru.ruscrafting.farms.paper

import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** Injectable seam for Paper behavior that MockBukkit cannot model completely. */
internal interface MineBlockEffects {
    fun completeExtraction(
        player: Player,
        block: Block,
        original: Material,
        toolSlot: Int,
        toolSnapshot: ItemStack,
    )
}

internal object PaperMineBlockEffects : MineBlockEffects {
    override fun completeExtraction(
        player: Player,
        block: Block,
        original: Material,
        toolSlot: Int,
        toolSnapshot: ItemStack,
    ) {
        val center = block.location.toCenterLocation()
        player.sendBlockChange(block.location, block.blockData)
        player.spawnParticle(Particle.BLOCK, center, 18, 0.28, 0.28, 0.28, 0.08, original.createBlockData())
        player.playSound(center, Sound.BLOCK_STONE_BREAK, 0.75f, 1.15f)
        player.playSound(center, Sound.ENTITY_ITEM_PICKUP, 0.22f, 1.65f)
        if (!player.isOnline || player.gameMode == GameMode.CREATIVE) return
        val currentTool = player.inventory.getItem(toolSlot)
        if (currentTool != null && currentTool.isSimilar(toolSnapshot)) {
            player.inventory.setItem(toolSlot, player.damageItemStack(currentTool, 1))
        }
    }
}
