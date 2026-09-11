package ru.ruscrafting.farms.paper

import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.logging.Level
import kotlin.math.ceil

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

internal fun mineClientBreakTicks(block: Block, player: Player): Long = mineClientBreakTicks(block.getBreakSpeed(player))

internal fun mineClientBreakTicks(speedPerTick: Float): Long {
    if (!speedPerTick.isFinite() || speedPerTick <= 0.0f) return MAX_CLIENT_BREAK_TICKS
    return ceil(1.0 / speedPerTick.toDouble()).toLong().coerceIn(1L, MAX_CLIENT_BREAK_TICKS)
}

internal fun scheduleMineClientResync(
    tasks: WorksiteTaskPort,
    state: WorksiteStatePort,
    player: Player,
    block: Block,
    recordId: String,
    predictedBreakTicks: Long,
) {
    val delayTicks = (predictedBreakTicks + CLIENT_RESYNC_GRACE_TICKS).coerceAtMost(MAX_CLIENT_BREAK_TICKS)
    val scheduled = tasks.runLater(delayTicks) {
        if (!player.isOnline) return@runLater
        player.sendBlockChange(block.location, block.blockData)
        state.log(
            Level.INFO,
            "Mine client block resynced player=${player.name} uuid=${player.uniqueId} " +
                "position=${block.world.name}:${block.x}:${block.y}:${block.z} material=${block.type} " +
                "predictedBreakTicks=$predictedBreakTicks delayTicks=$delayTicks record=$recordId",
        )
    }
    if (!scheduled) {
        state.log(
            Level.WARNING,
            "Mine client block resync not scheduled player=${player.name} uuid=${player.uniqueId} " +
                "position=${block.world.name}:${block.x}:${block.y}:${block.z} " +
                "predictedBreakTicks=$predictedBreakTicks delayTicks=$delayTicks record=$recordId",
        )
    }
}

private const val CLIENT_RESYNC_GRACE_TICKS = 4L
private const val MAX_CLIENT_BREAK_TICKS = 400L
