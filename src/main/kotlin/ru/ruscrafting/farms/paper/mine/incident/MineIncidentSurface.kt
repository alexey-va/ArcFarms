package ru.ruscrafting.farms.paper.mine.incident

import org.bukkit.Bukkit
import org.bukkit.Material
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime

/** Keeps generated incident scenes on the mine's structural stone and ores, never on authored decor. */
internal fun MineRuntime.isIncidentSurface(position: WorksitePosition): Boolean =
    position.blockType() in caveCeilingMaterials

internal fun WorksitePosition.blockType(): Material? = Bukkit.getWorld(world)
    ?.takeIf { it.isChunkLoaded(x shr 4, z shr 4) }
    ?.getBlockAt(x, y, z)
    ?.type

internal fun WorksitePosition.floodFootprint(): List<WorksitePosition> = FLOOD_OFFSETS.map { (dx, dz) ->
    copy(x = x + dx, y = y + 1, z = z + dz)
}

private val FLOOD_OFFSETS = listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1)
