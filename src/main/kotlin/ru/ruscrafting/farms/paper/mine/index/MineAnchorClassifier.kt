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

    fun classify(
        block: Block,
        mineable: Set<Material>,
        railMaterials: Set<Material> = emptySet(),
    ): Set<MineAnchorRole> = buildSet {
        val exposed = faces.any { !block.getRelative(it).type.isSolid }
        val walkableFloor = block.type.isSolid && !block.getRelative(BlockFace.UP).type.isSolid &&
            !block.getRelative(BlockFace.UP, 2).type.isSolid
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
