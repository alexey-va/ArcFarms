package ru.ruscrafting.farms.paper.fixtures

import org.bukkit.Location
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import ru.ruscrafting.farms.paper.platform.FarmClientBlockPreview

internal class RecordingFarmClientBlockPreview : FarmClientBlockPreview {
    data class Batch(val player: java.util.UUID, val changes: Map<Location, BlockData>)

    val batches = mutableListOf<Batch>()

    override fun send(player: Player, changes: Map<Location, BlockData>) {
        batches += Batch(player.uniqueId, changes.mapKeys { it.key.clone() }.mapValues { it.value.clone() })
    }
}
