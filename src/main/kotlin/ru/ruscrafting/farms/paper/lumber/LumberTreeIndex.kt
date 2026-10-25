package ru.ruscrafting.farms.paper.lumber

import org.bukkit.Material
import org.bukkit.World
import ru.ruscrafting.farms.config.LumbermillSettings
import ru.ruscrafting.farms.domain.lumber.LumberBlockSnapshot
import ru.ruscrafting.farms.domain.lumber.LumberTreeSnapshot
import ru.ruscrafting.farms.domain.placement.WorksitePlacementPlanner
import ru.ruscrafting.farms.domain.placement.toPlacementPoint
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.lumber.scene.LumberSceneGeometry
import ru.ruscrafting.farms.paper.worksite.WorksiteAsyncBlockScanner
import ru.ruscrafting.farms.paper.worksite.WorksiteBlockSnapshot
import ru.ruscrafting.farms.paper.worksite.WorksiteChunkCoordinate
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort

/** Bounded immutable forest index. No block search occurs in a tick or on the Paper thread. */
internal class LumberTreeIndex(private val tasks: WorksiteTaskPort) {
    data class Report(val roots: Int = 0, val tallTrunks: Int = 0, val clearCorridors: Int = 0, val usableCarts: Int = 0,
        val rejectedFallSamples: List<String> = emptyList())
    private data class Discovery(val candidates: List<Candidate>, val report: Report)
    private data class Candidate(val root: WorksitePosition, val logs: List<WorksitePosition>, val leaves: List<WorksitePosition>,
        val fallX: Int, val fallZ: Int, val cart: ru.ruscrafting.farms.domain.lumber.LumberPose, val clearance: List<WorksitePosition>,
        val cartFloor: List<WorksitePosition>, val cartSpace: List<WorksitePosition>)
    private data class CartPlacement(val pose: ru.ruscrafting.farms.domain.lumber.LumberPose,
        val floor: List<WorksitePosition>, val space: List<WorksitePosition>)
    private var candidates: List<Candidate> = emptyList()
    private var generation: Any = Any()
    var ready: Boolean = false
        private set
    var report: Report = Report()
        private set

    fun rebuild(world: World, settings: LumbermillSettings, complete: (Result<Int>) -> Unit) {
        clear()
        val request = generation
        val bounds = settings.forest
        val chunks = (((bounds.minX - 16) shr 4)..((bounds.maxX + 16) shr 4)).flatMap { x ->
            (((bounds.minZ - 16) shr 4)..((bounds.maxZ + 16) shr 4)).map { z -> WorksiteChunkCoordinate(x, z) }
        }
        WorksiteAsyncBlockScanner(tasks).submit(world, chunks, { generation === request },
            { snapshot -> discover(snapshot, settings) },
            { result ->
                result.onSuccess { candidates = it.candidates; report = it.report; ready = true }
                complete(result.map { it.candidates.size })
            })
    }

    fun select(world: World, roundId: Long, complete: (Result<LumberTreeSnapshot?>) -> Unit) {
        val request = generation
        val ordered = WorksitePlacementPlanner.seededOrder(candidates, roundId) { it.root.toPlacementPoint() }.iterator()
        val token = tasks.lifecycleToken()
        fun attempt() {
            if (!ordered.hasNext()) { complete(Result.success(null)); return }
            val candidate = ordered.next()
            val positions = (candidate.logs + candidate.leaves + candidate.clearance + candidate.cartFloor + candidate.cartSpace).distinct().iterator()
            val captured = mutableListOf<LumberBlockSnapshot>()
            var valid = true
            tasks.capture(token, { generation === request }) {
                if (!positions.hasNext() || !valid) true else {
                    val point = positions.next()
                    if (!world.isChunkLoaded(point.x shr 4, point.z shr 4)) valid = false
                    else {
                        val block = world.getBlockAt(point.x, point.y, point.z)
                        when {
                            // Cart constraints take priority if a cell also belongs to the decorative fall sweep.
                            point in candidate.cartFloor -> if (!block.type.isSolid) valid = false
                            point in candidate.cartSpace -> if (!walkable(block.type)) valid = false
                            point in candidate.clearance -> if (!fallPassable(block.type)) valid = false
                            point in candidate.logs -> if (!isLog(block.type)) valid = false else captured += LumberBlockSnapshot(point, block.blockData.asString)
                            point in candidate.leaves -> if (!isLeaves(block.type)) valid = false else captured += LumberBlockSnapshot(point, block.blockData.asString)
                            block.type.isSolid -> valid = false
                        }
                    }
                    !valid || !positions.hasNext()
                }
            }.whenComplete { _, failure -> tasks.runSync(token) {
                if (generation !== request) return@runSync
                if (failure != null) complete(Result.failure(failure))
                else if (!valid) attempt()
                else complete(Result.success(LumberTreeSnapshot(candidate.root,
                    captured.take(candidate.logs.size), captured.drop(candidate.logs.size), candidate.fallX, candidate.fallZ, candidate.cart)))
            } }
        }
        attempt()
    }

    fun clear() { generation = Any(); candidates = emptyList(); ready = false; report = Report() }

    private fun discover(snapshot: WorksiteBlockSnapshot, settings: LumbermillSettings): Discovery {
        val b = settings.forest
        val found = mutableListOf<Candidate>()
        val covered = mutableSetOf<WorksitePosition>()
        val roots = mutableListOf<WorksitePosition>()
        var tallTrunks = 0
        var clearCorridors = 0
        var usableCarts = 0
        val rejectedFallSamples = mutableListOf<String>()
        for (x in b.minX..b.maxX) for (z in b.minZ..b.maxZ) for (y in b.minY..b.maxY) {
            val point = WorksitePosition(settings.world, x, y, z)
            if (isLog(snapshot.type(point)) && snapshot.type(point.copy(y = y - 1)) in GROUND) roots += point
        }
        fun owner(point: WorksitePosition): WorksitePosition? = roots.minWithOrNull(compareBy<WorksitePosition>(
            { root -> val dx = point.x - root.x; val dz = point.z - root.z; dx * dx + dz * dz }, { it.x }, { it.z }, { it.y }))
        for (x in b.minX..b.maxX) for (z in b.minZ..b.maxZ) for (y in b.minY..b.maxY) {
            val root = WorksitePosition(settings.world, x, y, z)
            if (root in covered || !isLog(snapshot.type(root))) continue
            if (snapshot.type(root.copy(y = y - 1)) !in GROUND) continue
            val trunk = (0..39).map { root.copy(y = y + it) }.takeWhile { isLog(snapshot.type(it)) }
            if (trunk.size < 10) continue // Two untouched stump blocks plus the machine's full-length stock.
            tallTrunks++
            val logs = linkedSetOf<WorksitePosition>()
            val queue = ArrayDeque<WorksitePosition>().apply { addAll(trunk) }
            while (queue.isNotEmpty() && logs.size < MAX_LOGS) {
                val point = queue.removeFirst()
                if (!logs.add(point)) continue
                for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                    val next = point.copy(x = point.x + dx, y = point.y + dy, z = point.z + dz)
                    if (next !in logs && kotlin.math.abs(next.x - x) <= 4 && kotlin.math.abs(next.z - z) <= 4 &&
                        next.y in y..(y + 39) && isLog(snapshot.type(next)) && owner(next) == root) queue.addLast(next)
                }
            }
            if (queue.isNotEmpty()) continue
            val leaves = mutableListOf<WorksitePosition>()
            for (lx in x - 4..x + 4) for (lz in z - 4..z + 4) for (ly in y + 2..trunk.last().y + 5) {
                val point = WorksitePosition(settings.world, lx, ly, lz)
                if (isLeaves(snapshot.type(point)) && owner(point) == root) leaves += point
            }
            if (leaves.isEmpty() || leaves.size > MAX_LEAVES) continue
            // Detached planning needs only the captured material and position; exact BlockData is revalidated on Paper.
            fun plannedBlock(point: WorksitePosition) = LumberBlockSnapshot(point,
                "minecraft:${requireNotNull(snapshot.type(point)).name.lowercase()}")
            val tree = LumberTreeSnapshot(root, logs.map(::plannedBlock), leaves.map(::plannedBlock))
            val masked = tree.maskedBlocks.mapTo(hashSetOf()) { it.position }
            val selected = DIRECTIONS.firstNotNullOfOrNull { (dx, dz) ->
                val directedTree = tree.copy(fallX = dx, fallZ = dz)
                val clearance = LumberSceneGeometry.fallCollisionCells(directedTree).filterNot(masked::contains)
                val blocked = clearance.firstOrNull { point -> !fallPassable(snapshot.type(point)) }
                if (blocked != null) {
                    if (rejectedFallSamples.size < 8) rejectedFallSamples +=
                        "root=$root fall=$dx,$dz logs=${logs.size} leaves=${leaves.size} blocked=$blocked material=${snapshot.type(blocked)}"
                    return@firstNotNullOfOrNull null
                }
                clearCorridors++
                val cart = cartPose(snapshot, directedTree) ?: return@firstNotNullOfOrNull null
                usableCarts++
                Candidate(root, logs.toList(), leaves, dx, dz, cart.pose, clearance, cart.floor, cart.space)
            } ?: continue
            covered += logs
            found += selected
            if (found.size == MAX_TREES) return Discovery(found, Report(roots.size, tallTrunks, clearCorridors, usableCarts, rejectedFallSamples.toList()))
        }
        return Discovery(found, Report(roots.size, tallTrunks, clearCorridors, usableCarts, rejectedFallSamples.toList()))
    }

    private fun cartPose(snapshot: WorksiteBlockSnapshot, tree: LumberTreeSnapshot): CartPlacement? {
        val root = tree.root
        val dx = tree.fallX; val dz = tree.fallZ
        val stock = LumberSceneGeometry.felledStock(tree)
        for (side in listOf(3.0, -3.0, 4.0, -4.0, 5.0, -5.0)) {
            val x = stock.x - dz * side
            val z = stock.z + dx * side
            val floorY = root.y - 1
            val floor = (-4..4).flatMap { along -> (-1..1).map { cross ->
                WorksitePosition(root.world, kotlin.math.floor(x + dx * along - dz * cross).toInt(),
                    floorY, kotlin.math.floor(z + dz * along + dx * cross).toInt())
            } }
            // Feet are floorY+1; the 1.524 m hull and 1.8 m worker fit below floorY+3.
            val space = floor.flatMap { point -> (1..2).map { point.copy(y = floorY + it) } }
            if (floor.all { snapshot.type(it)?.isSolid == true } && space.all { walkable(snapshot.type(it)) })
                return CartPlacement(ru.ruscrafting.farms.domain.lumber.LumberPose(root.world, x, root.y.toDouble(), z,
                    stock.yaw), floor, space)
        }
        return null
    }

    private fun isLog(type: Material?): Boolean = type in LOGS
    private fun isLeaves(type: Material?): Boolean = type?.name?.endsWith("_LEAVES") == true
    // Decorative falling timber may brush through soft crowns. Foreign leaves are never claimed, masked or removed.
    // This policy is deliberately separate from strict cart/native collision and placement clearance.
    private fun fallPassable(type: Material?) = type != null && (!type.isSolid || isLeaves(type))
    private fun walkable(type: Material?) = type != null && !type.isSolid && type != Material.WATER && type != Material.LAVA

    private companion object {
        const val MAX_TREES = 64
        const val MAX_LOGS = 256
        const val MAX_LEAVES = 768
        val DIRECTIONS = listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0)
        val LOGS = setOf(Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG, Material.JUNGLE_LOG,
            Material.ACACIA_LOG, Material.DARK_OAK_LOG, Material.CHERRY_LOG, Material.PALE_OAK_LOG)
        val GROUND = setOf(Material.GRASS_BLOCK, Material.DIRT, Material.PODZOL, Material.COARSE_DIRT,
            Material.ROOTED_DIRT, Material.MOSS_BLOCK)
    }
}
