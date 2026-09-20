package ru.ruscrafting.farms.paper.mine.working

import org.bukkit.Material
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineWorkingPlacement
import ru.ruscrafting.farms.domain.placement.WorksitePlacementPlanner
import ru.ruscrafting.farms.domain.placement.toPlacementPoint
import ru.ruscrafting.farms.domain.worksite.WorksiteDeterministicSeed
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentPlacementReport
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import ru.ruscrafting.farms.paper.mine.lift.MineLiftAccess
import ru.ruscrafting.farms.paper.worksite.WorksiteAsyncBlockScanner
import ru.ruscrafting.farms.paper.worksite.WorksiteChunkCoordinate
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import java.util.logging.Level
import kotlin.math.abs

/** Indexed floor candidates -> bounded snapshots -> background geometry -> final live validation. */
internal class MineWorkingPlacementService(
    private val index: MineBlockIndex,
    private val scanner: WorksiteAsyncBlockScanner,
    private val lift: MineLiftAccess?,
    private val state: WorksiteStatePort,
    private val points: ru.ruscrafting.farms.paper.mine.point.MinePointService? = null,
) {
    private data class Search(val sequence: Long, val cursor: Int, val type: MineIncidentType)
    private val pending = mutableMapOf<String, Search>()
    private val pages = mutableMapOf<String, Pair<Search, Int>>()
    private val reports = mutableMapOf<String, MineIncidentPlacementReport>()
    private val retryAfter = mutableMapOf<String, Long>()

    fun search(runtime: MineRuntime, type: MineIncidentType, now: Long, complete: (MineWorkingPlacement) -> Unit): Boolean {
        val zone = runtime.settings.id
        val search = Search(runtime.state.sequence, runtime.state.incidentCursor, type)
        if (zone in pending) return true
        if (now < retryAfter.getOrDefault(zone, 0L)) return false
        val world = runtime.region.world
        val floors = lift?.floors().orEmpty().filter { it.exit.world === world }
        val seed = WorksiteDeterministicSeed.derive(search.sequence, type.name.hashCode().toLong() + search.cursor)
        val authored = points?.workingPlacements(runtime).orEmpty().mapNotNull { (id, point) ->
            if (point.world != world.name) return@mapNotNull null
            val floor = floors.minByOrNull { abs(it.exit.y - point.y) }?.id ?: id
            point.workingPlacement(floor, layoutSeed = seed)
        }
        val candidates = WorksitePlacementPlanner.seededOrder(authored, seed) { it.entrance.toPlacementPoint() }
        if (candidates.isEmpty()) {
            reports[zone] = MineIncidentPlacementReport(type, 1, 0, 0, mapOf("working_points_missing" to 1))
            return false
        }
        val bounds = runtime.region.bounds
        val exits = floors.map { WorksitePosition(world.name, it.exit.blockX, it.exit.blockY, it.exit.blockZ) }
        val coordinates = candidates.flatMap { candidate ->
            val end = candidate.position(0, 0, FOOTPRINT_RADIUS)
            val minX = minOf(candidate.entrance.x, end.x) - FOOTPRINT_RADIUS / 2
            val maxX = maxOf(candidate.entrance.x, end.x) + FOOTPRINT_RADIUS / 2
            val minZ = minOf(candidate.entrance.z, end.z) - FOOTPRINT_RADIUS / 2
            val maxZ = maxOf(candidate.entrance.z, end.z) + FOOTPRINT_RADIUS / 2
            buildList { for (x in (minX shr 4)..(maxX shr 4)) for (z in (minZ shr 4)..(maxZ shr 4)) {
                if (world.isChunkLoaded(x, z)) add(WorksiteChunkCoordinate(x, z))
            } }
        }.distinct()
        pending[zone] = search
        fun current() = pending[zone] == search && runtime.state.sequence == search.sequence &&
            runtime.state.incidentCursor == search.cursor && runtime.state.incident == null &&
            runtime.state.phase in setOf(MinePhase.PROSPECTING, MinePhase.MINING, MinePhase.LOADING, MinePhase.EXTRACTION)
        val submitted = scanner.submit(world, coordinates, ::current, plan = { snapshot ->
            val rejected = linkedMapOf<String, Int>()
            val selected = mutableListOf<MineWorkingPlacement>()
            var considered = 0
            for (candidate in candidates) {
                considered++
                val plan = MineWorkingLayout.plan(type, candidate)
                val reason = when {
                    !bounds.contains(candidate.entrance.x, candidate.entrance.y, candidate.entrance.z) -> "entrance_outside_region"
                    plan.blocks.keys.any { block -> exits.any { exit ->
                        abs(block.y - exit.y) <= 5 && abs(block.x - exit.x) <= 6 && abs(block.z - exit.z) <= 6
                    } } -> "lift_clearance"
                    else -> MineWorkingPlanner.rejection(plan, snapshot::type)
                }
                if (reason == null) {
                    selected += candidate
                    if (selected.size == MAX_FINALISTS) break
                } else rejected[reason] = rejected.getOrDefault(reason, 0) + 1
            }
            Triple(selected.toList(), considered, rejected.toMap())
        }, complete = { result ->
            if (!current()) return@submit
            pending.remove(zone, search)
            result.fold(onSuccess = { (selected, considered, rejected) ->
                val chosen = selected.firstOrNull { candidate ->
                    val plan = MineWorkingLayout.plan(type, candidate)
                    val occupied = world.players.any { player ->
                        val location = player.location
                        location.blockY in candidate.entrance.y..candidate.entrance.y + 5 &&
                            WorksitePosition(world.name, location.blockX, candidate.entrance.y + 1, location.blockZ) in plan.blocks
                    }
                    !occupied && MineWorkingPlanner.rejection(plan) { position ->
                        if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) null
                        else world.getBlockAt(position.x, position.y, position.z).type
                    } == null
                }
                reports[zone] = MineIncidentPlacementReport(type, 1, if (chosen == null) 0 else 1, considered,
                    if (chosen == null && selected.isNotEmpty()) rejected + ("live_placement_changed" to selected.size) else rejected)
                if (chosen == null) retryAfter[zone] = now + RETRY_MILLIS else {
                    retryAfter.remove(zone)
                    complete(chosen)
                }
            }, onFailure = { failure ->
                retryAfter[zone] = now + RETRY_MILLIS
                state.log(Level.WARNING, "Mine working planning failed zone=$zone type=$type", failure)
            })
        })
        if (!submitted) pending.remove(zone, search)
        return submitted
    }

    fun diagnostics(runtime: MineRuntime, type: MineIncidentType): MineIncidentPlacementReport =
        reports[runtime.settings.id]?.takeIf { it.type == type } ?: MineIncidentPlacementReport(type, 1, 0, 0,
            mapOf((if (runtime.settings.id in pending) "search_pending" else "not_searched") to 1))

    fun cancel(zone: String) { pending.remove(zone); pages.remove(zone) }
    fun clear() { pending.clear(); pages.clear(); reports.clear(); retryAfter.clear() }

    companion object {
        /** Retry scans advance through the whole indexed floor, not the same unlucky prefix. */
        internal fun <T> page(values: List<T>, page: Int, limit: Int): List<T> {
            if (values.size <= limit) return values
            val start = Math.floorMod(page.toLong() * limit, values.size.toLong()).toInt()
            return List(limit) { values[(start + it) % values.size] }
        }

        const val MAX_ANCHORS = 512
        const val MAX_FINALISTS = 2
        const val FOOTPRINT_RADIUS = 46
        const val RETRY_MILLIS = 10_000L
    }
}
