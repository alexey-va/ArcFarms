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

/** Pages through indexed walls on the crystals' floor; never creates or moves authored crystals. */
internal class MineCrystalWallPlan(private val index: MineBlockIndex, private val recovery: MineBlockRecoveryController) {
    class Search(val crystals: List<WorksitePosition>, val centre: Vector3d,
        val candidates: List<WorksitePosition>, var reason: String? = null) {
        var cursor = 0
        var result: MineCrystalState? = null
        var unloaded = false
        val pending get() = result == null && reason == null
    }

    fun prepare(runtime: MineRuntime, crystals: List<WorksitePosition>): Search {
        val centre = if (crystals.size == 4) MineCrystalGeometry.hub(crystals.map(::tip)) else Vector3d()
        val issue = groupIssue(runtime, crystals, centre)
        if (issue != null) return Search(crystals, centre, emptyList(), issue)
        val at = Location(runtime.region.world, centre.x, centre.y, centre.z)
        val indexed = index.nearbyTargets(runtime.settings.id, MineAnchorRole.SUPPORT, at, 28)
            .filter { it.y in (crystals.minOf { p -> p.y } + 1)..(crystals.minOf { p -> p.y } + 4) }
            .sortedBy { Vector3d(it.x + .5, it.y + .5, it.z + .5).distanceSquared(centre) }
        return Search(crystals, centre, indexed).also { search ->
            val bounds = runtime.region.bounds
            search.unloaded = ((maxOf(bounds.minX, at.blockX - 31) shr 4)..(minOf(bounds.maxX, at.blockX + 31) shr 4)).any { x ->
                ((maxOf(bounds.minZ, at.blockZ - 31) shr 4)..(minOf(bounds.maxZ, at.blockZ + 31) shr 4)).any { z ->
                    !runtime.region.world.isChunkLoaded(x, z)
                }
            }
        }
    }

    /** The existing preparation tick resumes the cursor; nearby decoration must not exhaust the whole search. */
    fun advance(runtime: MineRuntime, search: Search) {
        if (!search.pending) return
        val deadline = System.nanoTime() + 1_000_000L
        repeat(12) {
            if (System.nanoTime() >= deadline) return
            val wall = search.candidates.getOrNull(search.cursor++) ?: return@repeat
            if (wall.blockType() == null) search.unloaded = true
            val plan = selectWall(runtime, search.crystals, search.centre, wall) ?: return@repeat
            search.result = plan
            return
        }
        if (search.cursor >= search.candidates.size) {
            search.reason = if (search.unloaded) "chunk_unloaded" else "crystal_wall_missing"
        }
    }

    private fun selectWall(runtime: MineRuntime, crystals: List<WorksitePosition>, centre: Vector3d,
        wall: WorksitePosition): MineCrystalState? {
        if (!eligible(runtime, wall)) return null
        for (face in FACES) {
            val endpoint = contact(wall, face.modX, face.modZ)
            if (endpoint.distance(centre) !in 2.0..28.0 || !backed(runtime, wall, face) ||
                beamIssue(runtime, centre, endpoint) != null) continue
            val sideX = face.modZ
            val sideZ = -face.modX
            val cuts = listOf(wall, wall.copy(y = wall.y + 1),
                wall.copy(x = wall.x + sideX, z = wall.z + sideZ),
                wall.copy(x = wall.x + sideX, y = wall.y + 1, z = wall.z + sideZ))
                .filter { p -> p.y <= crystals.minOf { it.y } + 4 && eligible(runtime, p) &&
                    p.copy(x = p.x + face.modX, z = p.z + face.modZ).blockType()?.isAir == true &&
                    backed(runtime, p, face) && beamIssue(runtime, centre, contact(p, face.modX, face.modZ)) == null }
                .map { MineCrystalCut(it, requireNotNull(it.blockType()).name) }
            if (cuts.isNotEmpty()) return MineCrystalState(cuts, face.modX, face.modZ)
        }
        return null
    }

    /** A cached plan is checked once against the current world and journal before the state transition. */
    fun issue(runtime: MineRuntime, search: Search): String? {
        val plan = search.result ?: return search.reason ?: "search_pending"
        groupIssue(runtime, search.crystals, search.centre)?.let { return it }
        val face = FACES.single { it.modX == plan.faceX && it.modZ == plan.faceZ }
        for (cut in plan.cuts) {
            eligibilityIssue(runtime, cut.position)?.let { return it }
            if (cut.position.blockType()?.name != cut.material || !backed(runtime, cut.position, face)) return "anchor_changed"
            beamIssue(runtime, search.centre, contact(cut.position, plan.faceX, plan.faceZ))?.let { return it }
        }
        return null
    }

    fun eligible(runtime: MineRuntime, position: WorksitePosition): Boolean = eligibilityIssue(runtime, position) == null

    private fun eligibilityIssue(runtime: MineRuntime, position: WorksitePosition): String? {
        if (position.world != runtime.region.world.name) return "world_unavailable"
        if (!index.allowsEvent(position)) return "near_lift"
        if (recovery.containsPosition("${position.world}:${position.x}:${position.y}:${position.z}")) return "journalled_block"
        val type = position.blockType() ?: return "chunk_unloaded"
        if (!natural(type)) return "anchor_changed"
        val block = runtime.region.world.getBlockAt(position.x, position.y, position.z)
        if (!runtime.region.contains(block.location)) return "outside_region"
        return if (index.contains(runtime.settings.id, block, MineAnchorRole.SUPPORT) &&
            block.blockData.asString == type.createBlockData().asString) null else "anchor_changed"
    }

    private fun groupIssue(runtime: MineRuntime, crystals: List<WorksitePosition>, centre: Vector3d): String? {
        if (crystals.size != 4 || crystals.maxOf { it.y } - crystals.minOf { it.y } > 2) return "crystal_group_missing"
        val nodes = crystals.map(::tip)
        if (nodes.any { it.distance(centre) > 18.0 }) return "crystal_group_missing"
        return nodes.firstNotNullOfOrNull { beamIssue(runtime, it, centre) }
    }

    /** A cave wall has natural rock behind the face; a decorative pillar or free-standing pedestal does not. */
    private fun backed(runtime: MineRuntime, wall: WorksitePosition, face: BlockFace): Boolean = (1..3).all { depth ->
        val p = wall.copy(x = wall.x - face.modX * depth, z = wall.z - face.modZ * depth)
        p.blockType()?.let(::natural) == true && index.allowsEvent(p) &&
            runtime.region.contains(Location(runtime.region.world, p.x.toDouble(), p.y.toDouble(), p.z.toDouble()))
    }

    private fun beamIssue(runtime: MineRuntime, from: Vector3d, to: Vector3d): String? {
        val steps = ceil(from.distance(to) * 3).toInt().coerceIn(1, 64)
        for (i in 1..steps) {
            val p = Vector3d(from).lerp(to, i.toDouble() / steps)
            val position = WorksitePosition(runtime.region.world.name, floor(p.x).toInt(), floor(p.y).toInt(), floor(p.z).toInt())
            if (!index.allowsEvent(position)) return "near_lift"
            if (!runtime.region.contains(Location(runtime.region.world, p.x, p.y, p.z))) return "outside_region"
            val type = position.blockType() ?: return "chunk_unloaded"
            if (type.isSolid) return "crystal_beam_blocked"
        }
        return null
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
