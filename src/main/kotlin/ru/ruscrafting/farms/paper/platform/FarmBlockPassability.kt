package ru.ruscrafting.farms.paper.platform

import org.bukkit.block.Block

/** Exact Paper passability query used by spatial policies. */
internal fun interface FarmBlockPassability {
    fun isPassable(block: Block): Boolean
}

internal object PaperFarmBlockPassability : FarmBlockPassability {
    override fun isPassable(block: Block): Boolean = block.isPassable
}
