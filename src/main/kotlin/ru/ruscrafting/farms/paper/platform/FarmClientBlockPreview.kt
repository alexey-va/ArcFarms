package ru.ruscrafting.farms.paper.platform

import org.bukkit.Location
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player

/** Sends bounded client-only block changes; the authoritative world remains untouched. */
internal fun interface FarmClientBlockPreview {
    fun send(player: Player, changes: Map<Location, BlockData>)
}

internal object PaperFarmClientBlockPreview : FarmClientBlockPreview {
    override fun send(player: Player, changes: Map<Location, BlockData>) {
        changes.forEach { (location, data) -> player.sendBlockChange(location, data) }
    }
}
