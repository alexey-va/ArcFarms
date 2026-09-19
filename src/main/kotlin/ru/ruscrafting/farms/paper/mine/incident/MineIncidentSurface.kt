package ru.ruscrafting.farms.paper.mine.incident

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.BlockFace
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime

/** Keeps generated incident scenes on the mine's structural stone and ores, never on authored decor. */
internal fun MineRuntime.isIncidentSurface(position: WorksitePosition): Boolean =
    position.blockType() in caveCeilingMaterials

internal fun WorksitePosition.blockType(): Material? = Bukkit.getWorld(world)
    ?.takeIf { it.isChunkLoaded(x shr 4, z shr 4) }
    ?.getBlockAt(x, y, z)
    ?.type

/** A bounded lake that starts at an exposed wall breach and follows the actual walkable stone floor. */
internal fun MineRuntime.floodFootprint(anchor: WorksitePosition, limit: Int = 30): List<WorksitePosition> {
    require(limit in 1..64) { "Flood footprint limit is invalid" }
    val world = Bukkit.getWorld(anchor.world) ?: return emptyList()
    // Prefer an exposed horizontal wall opening. Floor anchors remain a compatibility fallback
    // for old indexes, where the opening is the air block directly above the indexed floor.
    val origin = listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)
        .map { face -> anchor.copy(x = anchor.x + face.modX, z = anchor.z + face.modZ) }
        .firstOrNull { opening ->
            world.isChunkLoaded(opening.x shr 4, opening.z shr 4) &&
                world.getBlockAt(opening.x, opening.y, opening.z).type in setOf(Material.AIR, Material.WATER) &&
                isIncidentSurface(opening.copy(y = opening.y - 1))
        }
        ?: anchor.copy(y = anchor.y + 1)
    val queue = ArrayDeque<WorksitePosition>().apply { add(origin) }
    val visited = linkedSetOf<WorksitePosition>()
    val result = mutableListOf<WorksitePosition>()
    val directions = listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)
        .let { values ->
            val offset = ((state.sequence xor anchor.x.toLong() xor anchor.z.toLong()) and 3L).toInt()
            values.drop(offset) + values.take(offset)
        }
    while (queue.isNotEmpty() && result.size < limit) {
        val position = queue.removeFirst()
        if (!visited.add(position)) continue
        if (kotlin.math.abs(position.x - origin.x) + kotlin.math.abs(position.z - origin.z) > 5) continue
        if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) continue
        val water = world.getBlockAt(position.x, position.y, position.z)
        if (!region.contains(water.location) || water.type !in setOf(Material.AIR, Material.WATER)) continue
        if (!isIncidentSurface(position.copy(y = position.y - 1))) continue
        result += position
        directions.forEach { face ->
            queue += position.copy(x = position.x + face.modX, z = position.z + face.modZ)
        }
    }
    return result
}
