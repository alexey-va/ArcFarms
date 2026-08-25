package ru.ruscrafting.farms.paper

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.data.type.Farmland
import ru.ruscrafting.farms.domain.FarmPlotPosition

internal data class FarmMachineBlockResult(
    val processed: Set<FarmPlotPosition>,
)

/** Applies a bounded machine swath without owning shift or entity lifecycle. */
internal class FarmMachineBlockProcessor(
    private val ledger: FarmBlockLedger,
) {
    fun till(
        zoneId: String,
        plots: Collection<FarmPlotPosition>,
        limit: Int,
    ): FarmMachineBlockResult = process(plots, limit) { position ->
        val soil = position.loadedBlock() ?: return@process false
        val above = soil.getRelative(org.bukkit.block.BlockFace.UP)
        if (!above.type.isAir) {
            return@process false
        }
        ledger.capture(soil, zoneId)
        setWetFarmland(soil)
        true
    }

    fun plant(
        zoneId: String,
        crop: Material,
        plots: Collection<FarmPlotPosition>,
        limit: Int,
    ): FarmMachineBlockResult = process(plots, limit) { position ->
        val soil = position.loadedBlock() ?: return@process false
        val above = soil.getRelative(org.bukkit.block.BlockFace.UP)
        if (!above.type.isAir && above.type != crop) {
            return@process false
        }
        ledger.capture(soil, zoneId)
        setWetFarmland(soil)
        above.setBlockData(crop.createBlockData(), false)
        ledger.captureActiveCrop(soil, zoneId)
        true
    }

    private fun process(
        plots: Collection<FarmPlotPosition>,
        limit: Int,
        mutation: (FarmPlotPosition) -> Boolean,
    ): FarmMachineBlockResult {
        require(limit in 1..256) { "Farm machine mutation limit must be in 1..256" }
        val processed = linkedSetOf<FarmPlotPosition>()
        for (position in plots.distinct()) {
            if (processed.size >= limit) break
            if (mutation(position)) processed += position
        }
        return FarmMachineBlockResult(processed)
    }

    private fun FarmPlotPosition.loadedBlock() = Bukkit.getWorld(world)?.let { loadedWorld ->
        if (!loadedWorld.isChunkLoaded(x shr 4, z shr 4)) null else loadedWorld.getBlockAt(x, y, z)
    }

    private fun setWetFarmland(block: org.bukkit.block.Block) {
        if (block.type != Material.FARMLAND) block.setType(Material.FARMLAND, false)
        val farmland = (block.blockData as? Farmland) ?: (Material.FARMLAND.createBlockData() as Farmland)
        farmland.moisture = farmland.maximumMoisture
        block.setBlockData(farmland, false)
    }
}
