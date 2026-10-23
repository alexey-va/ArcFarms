package ru.ruscrafting.farms.paper.mine.lift

import org.bukkit.block.BlockFace
import kotlin.math.roundToInt

/** The configured X/Z are the intact wall face; Y is the landing's floor height. */
internal data class MineLiftCallButton(val panel: LiftPoint) {
    val facing: BlockFace = when (Math.floorMod((panel.yaw / 90f).roundToInt(), 4)) {
        0 -> BlockFace.SOUTH
        1 -> BlockFace.WEST
        2 -> BlockFace.NORTH
        else -> BlockFace.EAST
    }
    val yaw: Float = when (facing) {
        BlockFace.SOUTH -> 0f
        BlockFace.WEST -> 90f
        BlockFace.NORTH -> 180f
        else -> -90f
    }
    val center = offset(0.002, 1.5)
    // A vanilla wall button occupies the support-facing edge of a virtual
    // one-block air cell. Its 6x4x2 pixel model stays full-size and never cuts
    // into or replaces the real wall block.
    val displayOrigin = LiftPoint(
        center.x + facing.modX * .5 - .5,
        center.y - .5,
        center.z + facing.modZ * .5 - .5,
    )
    val interaction = offset(.14, 1.3)
    val label = offset(.035, 2.0).copy(yaw = yaw)
    val blockData: String = "minecraft:spruce_button[face=wall,facing=${facing.name.lowercase()},powered=false]"

    private fun offset(normal: Double, height: Double) = LiftPoint(
        panel.x + facing.modX * normal,
        panel.y + height,
        panel.z + facing.modZ * normal,
    )

    companion object {
        const val INTERACTION_WIDTH = .45f
        const val INTERACTION_HEIGHT = .4f
        const val LABEL_SCALE = .65f
    }
}
