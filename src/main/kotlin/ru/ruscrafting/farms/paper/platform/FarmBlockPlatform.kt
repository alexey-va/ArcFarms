package ru.ruscrafting.farms.paper.platform

import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData

/**
 * Narrow Paper boundary for block APIs that platform test doubles may not model.
 * Gameplay owns the decision; this port only performs the verified Bukkit call.
 */
internal interface FarmBlockPlatform {
    fun isPassable(block: Block): Boolean

    fun createBlockData(serialized: String): BlockData
}

internal object PaperFarmBlockPlatform : FarmBlockPlatform {
    override fun isPassable(block: Block): Boolean = block.isPassable

    override fun createBlockData(serialized: String): BlockData = Bukkit.createBlockData(serialized)
}
