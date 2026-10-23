package ru.ruscrafting.farms.paper.mine.incident.crystal

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Directional
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineComponentGraph
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineIndexDefinition
import ru.ruscrafting.farms.paper.mine.index.MineIndexedTarget
import ru.ruscrafting.farms.paper.mine.prewarmIncidentCandidates

/** Real exposed rock, four separate authored crystals and enough natural backing to exclude a pillar. */
internal fun installCrystalTestScene(graph: MineComponentGraph, runtime: MineRuntime, extra: List<MineIndexedTarget> = emptyList()): List<Block> {
    val world = runtime.region.world
    val crystals = listOf(3 to 5, 9 to 5, 3 to 11, 9 to 11).map { (x, z) ->
        world.getBlockAt(x, 64, z).also { block ->
            block.type = Material.AMETHYST_CLUSTER
            block.blockData = (block.blockData as Directional).apply { facing = BlockFace.UP }
        }
    }
    val walls = buildList {
        for (x in 15..18) for (y in 65..69) for (z in 3..14) {
            val block = world.getBlockAt(x, y, z).also { it.type = Material.STONE }
            if (x == 15) add(block)
        }
    }
    val indexed = extra + crystals.map { MineIndexedTarget(it.crystalPosition(), setOf(MineAnchorRole.CRYSTAL)) } +
        walls.map { MineIndexedTarget(it.crystalPosition(), setOf(MineAnchorRole.SUPPORT)) }
    graph.index.replaceZone(MineIndexDefinition(runtime.settings.id, runtime.region, setOf(Material.STONE)),
        listOf(world.getChunkAt(0, 0), world.getChunkAt(1, 0)), indexed)
    graph.prewarmIncidentCandidates(runtime)
    return crystals
}
internal fun Block.crystalPosition() = WorksitePosition(world.name, x, y, z)
