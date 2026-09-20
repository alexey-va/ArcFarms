package ru.ruscrafting.farms.paper.mine.presentation

import org.bukkit.Color
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineResource
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import ru.ruscrafting.farms.paper.worksite.WorksiteGuidanceTarget

/** Visible order hints over the existing block index, with live quota and exposure checks. */
internal class MineOrderOreGuidance(private val index: MineBlockIndex) {
    fun targets(runtime: MineRuntime, player: Player): List<WorksiteGuidanceTarget> {
        if (runtime.state.phase != MinePhase.MINING || !runtime.settings.miningOnly || player.world !== runtime.region.world) return emptyList()
        val order = runtime.currentOrder() ?: return emptyList()
        val needed = order.requestedResources.filterTo(hashSetOf()) { resource ->
            order.normalizedRequirements[resource]?.let { (runtime.state.minedByMaterial[resource] ?: 0) < it } ?: true
        }
        return index.nearbyTargets(runtime.settings.id, MineAnchorRole.MINEABLE, player.location, RADIUS)
            .asSequence().mapNotNull { point ->
                val block = player.world.getBlockAt(point.x, point.y, point.z)
                if (block.type !in runtime.mineableMaterials || MineResource.fromMaterial(block.type.name) !in needed) return@mapNotNull null
                val visible = FACES.mapNotNull face@ { face ->
                    val x = point.x + face.modX
                    val y = point.y + face.modY
                    val z = point.z + face.modZ
                    if (!player.world.isChunkLoaded(x shr 4, z shr 4) || y !in player.world.minHeight until player.world.maxHeight ||
                        !player.world.getBlockAt(x, y, z).type.isAir) return@face null
                    block.location.toCenterLocation().add(face.modX * 0.65, face.modY * 0.65, face.modZ * 0.65)
                }.minByOrNull { it.distanceSquared(player.eyeLocation) } ?: return@mapNotNull null
                WorksiteGuidanceTarget("ore:${point.x}:${point.y}:${point.z}", ObjectiveTargetRole("mineable"),
                    visible, Color.WHITE, columnParticles = 4, columnStep = 0.09, bright = true)
            }.sortedBy { it.position.distanceSquared(player.location) }.take(4).toList()
    }

    private companion object {
        const val RADIUS = 12
        val FACES = listOf(BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST, BlockFace.DOWN)
    }
}
