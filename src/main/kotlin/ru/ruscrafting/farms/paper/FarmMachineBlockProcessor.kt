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
    ): FarmMachineBlockResult {
        val selected = select(plots, limit) { soil -> soil.getRelative(org.bukkit.block.BlockFace.UP).type.isAir }
        ledger.captureAll(selected.map { it.second }, zoneId)
        selected.forEach { (_, soil) -> setWetFarmland(soil) }
        return FarmMachineBlockResult(selected.mapTo(linkedSetOf()) { it.first })
    }

    fun plant(
        zoneId: String,
        crop: Material,
        plots: Collection<FarmPlotPosition>,
        limit: Int,
    ): FarmMachineBlockResult {
        val selected = select(plots, limit) { soil ->
            val above = soil.getRelative(org.bukkit.block.BlockFace.UP)
            above.type.isAir || above.type == crop
        }
        val soils = selected.map { it.second }
        ledger.captureAll(soils, zoneId)
        soils.forEach { soil ->
            setWetFarmland(soil)
            soil.getRelative(org.bukkit.block.BlockFace.UP).setBlockData(crop.createBlockData(), false)
        }
        ledger.updateActiveCrops(soils)
        return FarmMachineBlockResult(selected.mapTo(linkedSetOf()) { it.first })
    }

    private fun select(
        plots: Collection<FarmPlotPosition>,
        limit: Int,
        eligible: (org.bukkit.block.Block) -> Boolean,
    ): List<Pair<FarmPlotPosition, org.bukkit.block.Block>> {
        require(limit in 1..256) { "Farm machine mutation limit must be in 1..256" }
        val selected = mutableListOf<Pair<FarmPlotPosition, org.bukkit.block.Block>>()
        for (position in plots.distinct()) {
            if (selected.size >= limit) break
            val soil = position.loadedBlock() ?: continue
            if (eligible(soil)) selected += position to soil
        }
        return selected
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
