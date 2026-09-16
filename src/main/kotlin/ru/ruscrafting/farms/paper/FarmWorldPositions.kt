package ru.ruscrafting.farms.paper

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.Block
import ru.ruscrafting.farms.domain.FarmPlotPosition

internal fun Block.toFarmPlotPosition(): FarmPlotPosition = FarmPlotPosition(world.name, x, y, z)

internal fun Location.toFarmPlotPosition(): FarmPlotPosition = FarmPlotPosition(world.name, blockX, blockY, blockZ)

internal fun FarmPlotPosition.location(): Location? =
    Bukkit.getWorld(world)?.let { Location(it, x.toDouble(), y.toDouble(), z.toDouble()) }

internal fun FarmPlotPosition.block(): Block? {
    val loadedWorld = Bukkit.getWorld(world) ?: return null
    if (!loadedWorld.isChunkLoaded(x shr 4, z shr 4)) return null
    return loadedWorld.getBlockAt(x, y, z)
}

/** True only when every durable preparation plot can be inspected without loading chunks. */
internal fun FarmRuntime.preparationChunksLoaded(): Boolean = state.preparationPatch.all { position ->
    val world = Bukkit.getWorld(position.world) ?: return@all false
    world.isChunkLoaded(position.x shr 4, position.z shr 4)
}
