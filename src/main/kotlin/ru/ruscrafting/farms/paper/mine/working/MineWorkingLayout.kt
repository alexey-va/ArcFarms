package ru.ruscrafting.farms.paper.mine.working

import com.google.gson.Gson
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MineWorkingPlacement
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import kotlin.math.abs

/**
 * The compiled lateral-working recipe is the only geometry source used by the runtime.
 * The editable Atelier recipe and this generated JSON are kept together under resources.
 */
internal object MineWorkingLayout {
    private const val RESOURCE = "/mine/workings/lateral-working.layout.json"
    private val compiled: CompiledLayout by lazy {
        val stream = requireNotNull(MineWorkingLayout::class.java.getResourceAsStream(RESOURCE)) {
            "Missing compiled mine working layout: $RESOURCE"
        }
        stream.use { Gson().fromJson(it.reader(), CompiledLayout::class.java) }
    }

    fun plan(type: MineIncidentType, placement: MineWorkingPlacement): MineWorkingPlan {
        require(type in SUPPORTED_TYPES) { "Mine incident $type is not a lateral working" }
        val blocks = linkedMapOf<WorksitePosition, String>()
        val supportPoints = compiled.supportFrames.flatten().toSet()
        compiled.blocks.forEach { block ->
            if (!isWorkingCell(type, block, supportPoints)) return@forEach
            blocks[placement.position(block.side, block.up, block.forward)] =
                rotateFacing(projectedBlock(type, block), placement.direction)
        }
        val excavation = if (type == MineIncidentType.TUNNEL_DRIVE) {
            compiled.excavation.map { placement.absolute(it) }
        } else emptyList()
        val supports = if (type == MineIncidentType.TUNNEL_DRIVE) {
            compiled.supports.map { placement.absolute(it) }
        } else emptyList()
        val rails = when (type) {
            MineIncidentType.RAIL_EXTENSION -> compiled.rails.map { placement.absolute(it) }
            MineIncidentType.TRACK_DAMAGE -> compiled.trackDamageGaps.map { placement.absolute(it) }
            else -> emptyList()
        }
        val cartRoute = if (type in TRACK_TYPES) compiled.cartRoute.map { placement.absolute(it) } else emptyList()
        val rubble = when (type) {
            MineIncidentType.TRACK_DAMAGE -> compiled.trackDamageGaps.map { placement.absolute(it) }
            MineIncidentType.RAIL_EXTENSION -> compiled.extensionRubble.map { placement.absolute(it) }
            else -> emptyList()
        }
        val stations = if (type == MineIncidentType.ORE_WORKSHOP) {
            compiled.stations.mapValues { (_, point) -> placement.absolute(point) }
        } else emptyMap()
        val fixturePoints = compiled.fixtures.filterNot { type == MineIncidentType.TUNNEL_DRIVE && it in compiled.excavation }
        val fixtures = (fixturePoints + supportPoints)
            .mapTo(linkedSetOf()) { placement.absolute(it) }
        val railTargets = if (type in TRACK_TYPES) compiled.rails.toSet() else emptySet()
        val railPositions = if (type in TRACK_TYPES) {
            compiled.rails.map { placement.absolute(it) }.toSet()
        } else emptySet()
        val entryPocket = compiled.blocks
            .filter { it.up in 1..3 && it.forward in 0..ENTRY_AIR_DEPTH }
            .map { RelativePoint(it.side, it.up, it.forward) }
        val walkable = (compiled.walkable + entryPocket)
            .filter { abs(it.side) <= 1 }
            .filter { it.forward <= OPEN_DRIVE_END }
            .filter { it !in railTargets }
            .map { placement.absolute(it) }
            .filter { it !in stations.values && it !in fixtures }
            .toCollection(linkedSetOf())
        val shell = blocks.keys.asSequence()
            .filter { blocks.getValue(it) != AIR }
            .filter { it !in walkable && it !in railPositions && it !in rubble && it !in excavation &&
                it !in stations.values && it !in fixtures }
            .toCollection(linkedSetOf())
        val supportFrames = if (type == MineIncidentType.TUNNEL_DRIVE) {
            compiled.supportFrames.map { frame ->
                frame.map { position -> placement.absolute(position) to SUPPORT }
                    .toMap(LinkedHashMap())
            }
        } else emptyList()

        when (type) {
            MineIncidentType.TUNNEL_DRIVE -> excavation.forEach { blocks[it] = ROCK }
            MineIncidentType.RAIL_EXTENSION -> rubble.forEach { blocks[it] = RUBBLE }
            MineIncidentType.TRACK_DAMAGE -> {
                compiled.rails.forEach { blocks[placement.absolute(it)] = railState(placement) }
                rubble.forEach { blocks[it] = RUBBLE }
            }
            MineIncidentType.ORE_WORKSHOP -> Unit
            else -> Unit
        }

        return MineWorkingPlan(
            type = type,
            placement = placement,
            blocks = blocks,
            footprint = blocks.keys.toSet(),
            shell = shell,
            walkable = walkable,
            excavation = excavation,
            supports = supports,
            supportFrames = supportFrames,
            rubble = rubble,
            rails = rails,
            cartRoute = cartRoute,
            stations = stations,
            fixtures = fixtures,
            entrance = placement.entrance,
        )
    }

    /** Project the shared recipe into only the blocks owned by this working type. */
    private fun isWorkingCell(
        type: MineIncidentType,
        block: RelativeBlock,
        supportPoints: Set<RelativePoint>,
    ): Boolean = when {
        block.up in 1..3 && abs(block.side) <= 1 -> true
        block.forward >= ENTRY_AIR_DEPTH + 1 && block.up in 0..4 && abs(block.side) <= 1 -> true
        type == MineIncidentType.TUNNEL_DRIVE && RelativePoint(block.side, block.up, block.forward) in supportPoints -> true
        else -> false
    }

    private fun projectedBlock(type: MineIncidentType, block: RelativeBlock): String {
        val point = RelativePoint(block.side, block.up, block.forward)
        if (point in compiled.fixtures && !(type == MineIncidentType.TUNNEL_DRIVE && point in compiled.excavation)) {
            return block.block
        }
        if (block.up in 1..3 && block.forward <= ENTRY_AIR_DEPTH) return AIR
        if (type == MineIncidentType.ORE_WORKSHOP || block.up !in 1..3) return block.block
        return when {
            type == MineIncidentType.TUNNEL_DRIVE && block.forward >= EXCAVATION_START -> ROCK
            abs(block.side) <= 1 && block.forward <= OPEN_DRIVE_END -> AIR
            else -> ROCK
        }
    }

    /** Static geometry checks; live material checks belong to MineWorkingPlanner.rejection. */
    fun validate(plan: MineWorkingPlan): List<String> = buildList {
        if (plan.blocks.isEmpty()) add("empty-footprint")
        if (plan.blocks.size > MineWorkingPlanner.MAX_BLOCKS) add("footprint-too-large:${plan.blocks.size}")
        if (plan.footprint != plan.blocks.keys) add("footprint-map-mismatch")
        if (plan.blocks.keys.any { it.world != plan.entrance.world }) add("mixed-world")
        if (plan.blocks.keys.any { it.y !in plan.entrance.y..(plan.entrance.y + MAX_HEIGHT) }) add("height-out-of-range")
        if (plan.shell.any { it !in plan.blocks }) add("shell-outside-footprint")
        if (plan.walkable.any { it !in plan.blocks }) add("walkable-outside-footprint")
        if (plan.fixtures.any { it !in plan.blocks }) add("fixture-outside-footprint")
        if (plan.fixtures.any { it in plan.walkable }) add("fixture-crosses-walkable")
        if (plan.excavation.any { it !in plan.blocks }) add("excavation-outside-footprint")
        if (plan.supports.size != plan.supportFrames.size) add("support-frame-count-mismatch")
        plan.supportFrames.forEachIndexed { index, frame ->
            if (frame.keys.any { it !in plan.blocks }) add("support-frame-outside:$index")
        }
        val expectedStations = if (plan.type == MineIncidentType.ORE_WORKSHOP) STATION_KEYS else emptySet()
        if (plan.stations.keys != expectedStations) add("station-keys-mismatch")
        if (plan.stations.values.any { it !in plan.blocks || it in plan.shell }) add("station-outside-room")
        if (plan.cartRoute.zipWithNext().any { (a, b) -> manhattan(a, b) != 1 }) add("cart-route-gap")
        if (plan.rails.any { rail -> plan.cartRoute.none { it.x == rail.x && it.z == rail.z && it.y == rail.y } }) {
            add("rail-route-mismatch")
        }
        if (plan.cartRoute.any { it.y != plan.entrance.y + 1 }) add("cart-route-height")
        if (plan.type == MineIncidentType.TUNNEL_DRIVE && plan.excavation.any { plan.blocks[it] == AIR }) {
            add("tunnel-excavation-is-air")
        }
        if (plan.type != MineIncidentType.TUNNEL_DRIVE && plan.excavation.any { plan.blocks[it] != AIR }) {
            add("open-working-excavation-blocked")
        }
        if (plan.type == MineIncidentType.TRACK_DAMAGE && plan.rubble.size != 3) add("track-damage-must-have-three-gaps")
        if (plan.type == MineIncidentType.TRACK_DAMAGE && plan.rails.size != plan.rubble.size) add("track-damage-rails-must-match-gaps")
        if (plan.type == MineIncidentType.RAIL_EXTENSION && plan.rubble.isEmpty()) add("rail-extension-without-rubble")
        if (plan.type == MineIncidentType.TUNNEL_DRIVE && plan.excavation.isEmpty()) add("tunnel-without-excavation")
        if (plan.type in TRACK_TYPES && (plan.rails.isEmpty() || plan.cartRoute.isEmpty())) add("track-without-route")
        if (plan.type !in TRACK_TYPES && (plan.rails.isNotEmpty() || plan.cartRoute.isNotEmpty())) add("unexpected-track-route")
        if (plan.supportFrames.any { frame -> frame.keys.any { it in plan.walkable } }) add("support-crosses-walkable")
    }

    private fun railState(placement: MineWorkingPlacement): String =
        if (placement.direction % 2 == 0) NORTH_SOUTH_RAIL else EAST_WEST_RAIL

    private fun rotateFacing(data: String, turns: Int): String = if (turns == 0) data else
        FACING.replace(data) { match -> "facing=${CARDINALS[(CARDINALS.indexOf(match.groupValues[1]) + turns) % 4]}" }

    private fun manhattan(first: WorksitePosition, second: WorksitePosition): Int =
        kotlin.math.abs(first.x - second.x) + kotlin.math.abs(first.y - second.y) + kotlin.math.abs(first.z - second.z)

    private data class CompiledLayout(
        val blocks: List<RelativeBlock> = emptyList(),
        val shell: List<RelativePoint> = emptyList(),
        val walkable: List<RelativePoint> = emptyList(),
        val excavation: List<RelativePoint> = emptyList(),
        val supports: List<RelativePoint> = emptyList(),
        val supportFrames: List<List<RelativePoint>> = emptyList(),
        val rails: List<RelativePoint> = emptyList(),
        val cartRoute: List<RelativePoint> = emptyList(),
        val extensionRubble: List<RelativePoint> = emptyList(),
        val trackDamageGaps: List<RelativePoint> = emptyList(),
        val stations: Map<String, RelativePoint> = emptyMap(),
        val fixtures: List<RelativePoint> = emptyList(),
    )

    private data class RelativeBlock(val side: Int, val up: Int, val forward: Int, val block: String)
    private data class RelativePoint(val side: Int, val up: Int, val forward: Int)

    private fun MineWorkingPlacement.absolute(point: RelativePoint): WorksitePosition =
        position(point.side, point.up, point.forward)

    private const val MAX_HEIGHT = 4
    private val FACING = Regex("facing=(north|east|south|west)")
    private val CARDINALS = listOf("north", "east", "south", "west")
    private const val ROCK = "minecraft:stone"
    private const val AIR = "minecraft:air"
    private const val RUBBLE = "minecraft:cobblestone"
    private const val SUPPORT = "minecraft:spruce_log[axis=y]"
    private const val NORTH_SOUTH_RAIL = "minecraft:rail[shape=north_south,waterlogged=false]"
    private const val EAST_WEST_RAIL = "minecraft:rail[shape=east_west,waterlogged=false]"
    private val STATION_KEYS = setOf("ore", "crusher", "furnace", "output", "shipping")
    private val TRACK_TYPES = setOf(MineIncidentType.RAIL_EXTENSION, MineIncidentType.TRACK_DAMAGE)
    private const val EXCAVATION_START = 4
    private const val OPEN_DRIVE_END = 13
    private const val ENTRY_AIR_DEPTH = 2
    private val SUPPORTED_TYPES = setOf(
        MineIncidentType.TUNNEL_DRIVE,
        MineIncidentType.RAIL_EXTENSION,
        MineIncidentType.TRACK_DAMAGE,
        MineIncidentType.ORE_WORKSHOP,
    )
}

internal data class MineWorkingPlan(
    val type: MineIncidentType,
    val placement: MineWorkingPlacement,
    val blocks: Map<WorksitePosition, String>,
    val footprint: Set<WorksitePosition>,
    val shell: Set<WorksitePosition>,
    val walkable: Set<WorksitePosition>,
    val excavation: List<WorksitePosition>,
    val supports: List<WorksitePosition>,
    val supportFrames: List<Map<WorksitePosition, String>>,
    val rubble: List<WorksitePosition>,
    val rails: List<WorksitePosition>,
    val cartRoute: List<WorksitePosition>,
    val stations: Map<String, WorksitePosition>,
    val fixtures: Set<WorksitePosition>,
    val entrance: WorksitePosition,
) {
    fun inside(position: WorksitePosition): Boolean = position in footprint

    fun supportBlocks(index: Int): Map<WorksitePosition, String> = supportFrames.getOrNull(index).orEmpty()
}
