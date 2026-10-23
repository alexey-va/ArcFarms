package ru.ruscrafting.farms.paper.mine.incident.crystal

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Directional
import org.joml.Vector3d
import ru.ruscrafting.farms.domain.MineResource
import ru.ruscrafting.farms.domain.mine.crystal.MineCrystalCut
import ru.ruscrafting.farms.domain.mine.crystal.MineCrystalState
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.incident.blockType
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import ru.ruscrafting.farms.paper.mine.recovery.MineBlockRecoveryController
import kotlin.math.ceil
import kotlin.math.floor

/** Selects an exposed indexed wall on the crystals' own floor; never creates or moves authored crystals. */
internal class MineCrystalWallPlan(private val index: MineBlockIndex, private val recovery: MineBlockRecoveryController) {
    fun select(runtime: MineRuntime, crystals: List<WorksitePosition>): MineCrystalState? {
        if (crystals.size != 4 || crystals.maxOf { it.y } - crystals.minOf { it.y } > 2) return null
        val nodes = crystals.map(::tip)
        val centre = MineCrystalGeometry.hub(nodes)
        if (nodes.any { it.distance(centre) > 18.0 } || nodes.any { !clear(runtime, it, centre) }) return null
        val at = Location(runtime.region.world, centre.x, centre.y, centre.z)
        val indexed = index.nearbyTargets(runtime.settings.id, MineAnchorRole.SUPPORT, at, 28)
            .filter { it.y in (crystals.minOf { p -> p.y } + 1)..(crystals.minOf { p -> p.y } + 4) }
            .sortedBy { Vector3d(it.x + .5, it.y + .5, it.z + .5).distanceSquared(centre) }
        // ponytail: at most 12 indexed candidates x 4 faces x 64 path samples; wider scenes need prewarmed wall plans.
        for (wall in indexed.take(12)) {
            if (!eligible(runtime, wall)) continue
            for (face in FACES) {
                val endpoint = contact(wall, face.modX, face.modZ)
                if (endpoint.distance(centre) !in 2.0..28.0 || !clear(runtime, centre, endpoint) || !backed(runtime, wall, face)) continue
                val sideX = face.modZ
                val sideZ = -face.modX
                val cuts = listOf(wall, wall.copy(y = wall.y + 1),
                    wall.copy(x = wall.x + sideX, z = wall.z + sideZ),
                    wall.copy(x = wall.x + sideX, y = wall.y + 1, z = wall.z + sideZ))
                    .filter { p -> p.y <= crystals.minOf { it.y } + 4 && eligible(runtime, p) &&
                        p.copy(x = p.x + face.modX, z = p.z + face.modZ).blockType()?.isAir == true }
                    .map { MineCrystalCut(it, requireNotNull(it.blockType()).name) }
                if (cuts.isNotEmpty()) return MineCrystalState(cuts, face.modX, face.modZ)
            }
        }
        return null
    }

    fun eligible(runtime: MineRuntime, position: WorksitePosition): Boolean {
        if (position.world != runtime.region.world.name || !index.allowsEvent(position) ||
            recovery.containsPosition("${position.world}:${position.x}:${position.y}:${position.z}")) return false
        val type = position.blockType() ?: return false
        if (!natural(type)) return false
        val block = runtime.region.world.getBlockAt(position.x, position.y, position.z)
        return runtime.region.contains(block.location) && index.contains(runtime.settings.id, block, MineAnchorRole.SUPPORT) &&
            block.blockData.asString == type.createBlockData().asString
    }

    /** A cave wall has natural rock behind the face; a decorative pillar or free-standing pedestal does not. */
    private fun backed(runtime: MineRuntime, wall: WorksitePosition, face: BlockFace): Boolean = (1..3).all { depth ->
        val p = wall.copy(x = wall.x - face.modX * depth, z = wall.z - face.modZ * depth)
        p.blockType()?.let(::natural) == true && index.allowsEvent(p) &&
            runtime.region.contains(Location(runtime.region.world, p.x.toDouble(), p.y.toDouble(), p.z.toDouble()))
    }

    private fun clear(runtime: MineRuntime, from: Vector3d, to: Vector3d): Boolean {
        val steps = ceil(from.distance(to) * 3).toInt().coerceIn(1, 64)
        for (i in 1..steps) {
            val p = Vector3d(from).lerp(to, i.toDouble() / steps)
            val position = WorksitePosition(runtime.region.world.name, floor(p.x).toInt(), floor(p.y).toInt(), floor(p.z).toInt())
            if (!index.allowsEvent(position) || position.blockType()?.let { !it.isSolid } != true ||
                !runtime.region.contains(Location(runtime.region.world, p.x, p.y, p.z))) return false
        }
        return true
    }

    companion object {
        private val FACES = listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)
        fun natural(type: Material): Boolean = type == Material.STONE || type == Material.DEEPSLATE ||
            type.name.endsWith("_ORE") && MineResource.fromMaterial(type.name) in MineResource.knownResources

        fun tip(position: WorksitePosition): Vector3d {
            val world = org.bukkit.Bukkit.getWorld(position.world)
            val face = world?.takeIf { it.isChunkLoaded(position.x shr 4, position.z shr 4) }
                ?.getBlockAt(position.x, position.y, position.z)?.blockData
                ?.let { (it as? Directional)?.facing } ?: BlockFace.UP
            return Vector3d(position.x + .5 + face.modX * .58, position.y + .5 + face.modY * .58,
                position.z + .5 + face.modZ * .58)
        }

        fun contact(position: WorksitePosition, faceX: Int, faceZ: Int): Vector3d =
            Vector3d(position.x + .5 + faceX * .515, position.y + .5, position.z + .5 + faceZ * .515)
    }
}
