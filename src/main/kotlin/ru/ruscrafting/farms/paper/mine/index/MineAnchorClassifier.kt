package ru.ruscrafting.farms.paper.mine.index

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace

internal enum class MineAnchorRole {
    MINEABLE, PROSPECT, SUPPORT, RAIL, VENT, PUMP, LAMP, CRYSTAL, NEST, POWER, MINER,
}

/** Pure local classifier; it inspects only the candidate and its six direct neighbours. */
internal object MineAnchorClassifier {
    private val faces = listOf(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)

    fun hasUnloadedHorizontalNeighbor(block: Block): Boolean {
        val world = block.world
        val chunkX = block.x shr 4
        val chunkZ = block.z shr 4
        return (block.x and 15 == 0 && !world.isChunkLoaded(chunkX - 1, chunkZ)) ||
            (block.x and 15 == 15 && !world.isChunkLoaded(chunkX + 1, chunkZ)) ||
            (block.z and 15 == 0 && !world.isChunkLoaded(chunkX, chunkZ - 1)) ||
            (block.z and 15 == 15 && !world.isChunkLoaded(chunkX, chunkZ + 1))
    }

    fun classify(
        block: Block,
        mineable: Set<Material>,
        railMaterials: Set<Material> = emptySet(),
    ): Set<MineAnchorRole> = buildSet {
        val material = block.type
        if (material.isAir) return@buildSet
        fun nonSolid(face: BlockFace, distance: Int = 1): Boolean {
            val x = block.x + face.modX * distance
            val y = block.y + face.modY * distance
            val z = block.z + face.modZ * distance
            val world = block.world
            if (y !in world.minHeight until world.maxHeight) return false
            if (!world.isChunkLoaded(x shr 4, z shr 4)) return false
            return !world.getBlockAt(x, y, z).type.isSolid
        }
        val exposed = material.isSolid && faces.any { nonSolid(it) }
        val walkableFloor = material.isSolid && nonSolid(BlockFace.UP) && nonSolid(BlockFace.UP, 2)
        if (block.type in mineable) {
            add(MineAnchorRole.MINEABLE)
            if (exposed) add(MineAnchorRole.PROSPECT)
        }
        if (block.type.isSolid && exposed) {
            add(MineAnchorRole.SUPPORT)
            add(MineAnchorRole.LAMP)
            add(MineAnchorRole.POWER)
        }
        if (walkableFloor && (railMaterials.isEmpty() || block.type in railMaterials)) {
            add(MineAnchorRole.RAIL)
        }
        if (walkableFloor) {
            add(MineAnchorRole.NEST)
            add(MineAnchorRole.MINER)
        }
        if (block.type.name.endsWith("_GRATE") || block.type == Material.IRON_BARS) add(MineAnchorRole.VENT)
        if (block.type == Material.WATER || block.type == Material.WATER_CAULDRON) add(MineAnchorRole.PUMP)
        if (block.type == Material.AMETHYST_BLOCK || block.type.name.endsWith("AMETHYST_BUD") ||
            block.type.name.endsWith("AMETHYST_CLUSTER")) add(MineAnchorRole.CRYSTAL)
    }
}
