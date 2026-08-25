package ru.ruscrafting.farms.paper

import com.sk89q.worldedit.IncompleteRegionException
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.FarmPlotPosition

internal class FarmWorldEditSelection(
    val world: String,
    val volume: Long,
    val minimum: FarmPlotPosition,
    val maximum: FarmPlotPosition,
    val cuboid: Boolean,
    private val containsCoordinates: (Int, Int, Int) -> Boolean,
) {
    fun contains(position: FarmPlotPosition): Boolean =
        position.world == world && containsCoordinates(position.x, position.y, position.z)
}

internal sealed interface WorldEditSelectionResult {
    data class Available(val selection: FarmWorldEditSelection) : WorldEditSelectionResult
    data object PluginUnavailable : WorldEditSelectionResult
    data object Incomplete : WorldEditSelectionResult
}

internal object WorldEditSelectionReader {
    fun current(player: Player): WorldEditSelectionResult {
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldEdit")) {
            return WorldEditSelectionResult.PluginUnavailable
        }
        val actor = BukkitAdapter.adapt(player)
        val session = WorldEdit.getInstance().sessionManager.get(actor)
        val selectionWorld = session.selectionWorld ?: return WorldEditSelectionResult.Incomplete
        val region = try {
            session.getSelection(selectionWorld)
        } catch (_: IncompleteRegionException) {
            return WorldEditSelectionResult.Incomplete
        }
        val world = BukkitAdapter.adapt(selectionWorld).name
        val minimum = region.minimumPoint
        val maximum = region.maximumPoint
        return WorldEditSelectionResult.Available(
            FarmWorldEditSelection(
                world = world,
                volume = region.volume,
                minimum = FarmPlotPosition(world, minimum.x(), minimum.y(), minimum.z()),
                maximum = FarmPlotPosition(world, maximum.x(), maximum.y(), maximum.z()),
                cuboid = region is CuboidRegion,
                containsCoordinates = { x, y, z -> region.contains(BlockVector3.at(x, y, z)) },
            ),
        )
    }
}
