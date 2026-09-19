package ru.ruscrafting.farms.paper.mine.working

import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MineWorkingPlacement
import ru.ruscrafting.farms.domain.worksite.WorksiteCoherentNoise
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import kotlin.math.abs

/**
 * Deterministic natural working geometry. The old generated 3x5x15 recipe
 * encoded one straight corridor; this layout keeps the worksite contract but
 * makes the drive meander through a small noise-shaped rock volume.
 */
internal object MineWorkingLayout {
    fun plan(
        type: MineIncidentType,
        placement: MineWorkingPlacement,
        seed: Long = placement.layoutSeed,
    ): MineWorkingPlan {
        require(type in SUPPORTED_TYPES) { "Mine incident $type is not a lateral working" }
        val path = naturalPath(seed).toMutableList()
        val localBlocks = linkedMapOf<LocalPoint, String>()
        val localWalkable = linkedSetOf<LocalPoint>()
        val localFixtures = linkedSetOf<LocalPoint>()
        val localExcavation = linkedSetOf<LocalPoint>()
        val localSupports = mutableListOf<LocalPoint>()
        val localSupportFrames = mutableListOf<Map<LocalPoint, String>>()
        val route = path.map { it.copy(up = 1) }

        fun put(point: LocalPoint, block: String) {
            localBlocks[point] = block
        }

        // The corridor follows the authored entry for three blocks and then
        // takes deterministic one-block bends. Noise changes the wall ridge
        // and ceiling profile while all movement cells remain connected.
        // Keep the first two slices at the authored three-wide stub. The
        // compact map exposes air at side -1..1 before the first rock face;
        // widening that band would reject an otherwise valid admin point.
        val entry = LocalPoint(0, 1, 0)
        path.add(0, entry)
        path.forEachIndexed { index, center ->
            val width = if (index < 2) 1 else 2 + if (noise(seed, index, 11) > 0.48) 1 else 0
            val roof = 4 + if (noise(seed, index, 17) > 0.57) 1 else 0
            for (side in center.side - width..center.side + width) {
                // The roof is a smooth lateral field, not one flat height per
                // slice. Keep the three-block player spine at least four high.
                val localRoof = if (index < 2) 4 else (roof + when {
                    lateralNoise(seed, center.forward, side, 37) > 0.64 -> 1
                    lateralNoise(seed, center.forward, side, 41) < 0.30 -> -1
                    else -> 0
                }).coerceIn(4, MAX_HEIGHT)
                for (up in 1 until localRoof) {
                    val point = LocalPoint(side, up, center.forward)
                    localWalkable += point
                    val spine = abs(side - center.side) <= 1 && up <= 3
                    put(point, if (spine && type == MineIncidentType.TUNNEL_DRIVE && index >= 3) ROCK else AIR)
                }
                putIfClosed(LocalPoint(side, 0, center.forward), ROCK, localWalkable, ::put)
                putIfClosed(LocalPoint(side, localRoof, center.forward), ROCK, localWalkable, ::put)
            }
            // One boundary column per side. The former nested loop emitted
            // center +/- 2*width slabs instead of a single natural wall.
            val boundary = listOf(center.side - width - 1, center.side + width + 1)
            boundary.forEach { side ->
                putIfClosed(LocalPoint(side, 0, center.forward), ROCK, localWalkable, ::put)
                for (up in 1..roof) putIfClosed(LocalPoint(side, up, center.forward), ROCK, localWalkable, ::put)
            }
            // A sparse ridge makes the boundary irregular without opening a
            // disconnected pocket beside the drive.
            if (noise(seed, index, 23) > 0.68) {
                putIfClosed(LocalPoint(center.side - width - 1, 2, center.forward), ROCK, localWalkable, ::put)
                putIfClosed(LocalPoint(center.side + width + 1, 3, center.forward), ROCK, localWalkable, ::put)
            }
        }

        // Ten three-by-three cut sections preserve the old progress budget,
        // but each section follows the natural path rather than one axis.
        if (type == MineIncidentType.TUNNEL_DRIVE) {
            path.filter { it.forward >= 3 }
                .groupBy { it.forward }
                .toSortedMap()
                .values
                .map { it.last() }
                .take(10)
                .forEach { center ->
                    for (side in -1..1) for (up in 1..3) {
                        val point = LocalPoint(center.side + side, up, center.forward)
                        localExcavation += point
                        put(point, ROCK)
                    }
                }
        }

        listOf(3, 7, 11).forEach { index ->
            val center = path[index]
            val width = if (index < 2) 1 else 2 + if (noise(seed, index, 11) > 0.48) 1 else 0
            val roof = 4 + if (noise(seed, index, 17) > 0.57) 1 else 0
            val wall = width + 1
            val frame = linkedMapOf<LocalPoint, String>()
            // Supports sit in the natural wall ridge, leaving the widened
            // walkable air volume unobstructed.
            for (up in 1..roof) {
                frame[LocalPoint(center.side - wall, up, center.forward)] = SUPPORT_POST
                frame[LocalPoint(center.side + wall, up, center.forward)] = SUPPORT_POST
            }
            for (side in -wall..wall) frame[LocalPoint(center.side + side, roof, center.forward)] = supportBeam(placement)
            val safeFrame = frame.filterKeys { it !in localWalkable }
            safeFrame.forEach { (point, block) ->
                localFixtures += point
                put(point, block)
            }
            localSupports += LocalPoint(center.side, roof, center.forward)
            localSupportFrames += safeFrame
        }

        // Practical lamps are part of the temporary scene, not invisible
        // helper blocks. They hang below the irregular roof and stay above
        // the player clearance band.
        listOf(2, 6, 10).forEach { index ->
            val center = path[index]
            val roof = 4 + if (noise(seed, index, 17) > 0.57) 1 else 0
            val lampSide = center.side + if (lateralNoise(seed, center.forward, center.side, 29) > 0.5) 1 else -1
            val point = LocalPoint(lampSide, roof - 1, center.forward)
            localWalkable.remove(point)
            localFixtures += point
            put(point, LANTERN)
        }

        val stationPoints = linkedMapOf(
            "ore" to LocalPoint(-1, 1, 4),
            "crusher" to LocalPoint(1, 1, 7),
            "furnace" to LocalPoint(-1, 1, 10),
            "output" to LocalPoint(1, 1, 12),
            "shipping" to LocalPoint(-1, 1, 3),
        )
        val stationData = linkedMapOf(
            "ore" to "minecraft:barrel[facing=east,open=false]",
            "crusher" to "minecraft:grindstone[face=floor,facing=north]",
            "furnace" to "minecraft:furnace[facing=north,lit=false]",
            "output" to "minecraft:barrel[facing=west,open=false]",
            "shipping" to "minecraft:chest[facing=east,type=single,waterlogged=false]",
        )
        val stations = if (type == MineIncidentType.ORE_WORKSHOP) stationPoints else emptyMap()
        stations.forEach { (id, point) ->
            localWalkable.remove(point)
            localFixtures.remove(point)
            put(point, rotateFacing(stationData.getValue(id), placement.direction))
        }

        val cartRoute = if (type in TRACK_TYPES) route.take(12).map { placement.absolute(it) } else emptyList()
        val railsAll = if (type in TRACK_TYPES) route.take(12) else emptyList()
        val damageGaps = railsAll.drop(6).take(3)
        val extensionRubble = railsAll.takeLast(3)
        railsAll.forEachIndexed { index, point ->
            localWalkable.remove(point)
            put(point, railState(railsAll, index, placement))
        }
        val rubbleLocal = when (type) {
            MineIncidentType.RAIL_EXTENSION -> extensionRubble
            MineIncidentType.TRACK_DAMAGE -> damageGaps
            else -> emptyList()
        }
        rubbleLocal.forEach { put(it, RUBBLE) }

        val blocks = localBlocks.mapKeys { (point, _) -> placement.absolute(point) }
        val walkable = localWalkable.mapTo(linkedSetOf()) { placement.absolute(it) }
        val fixtures = localFixtures.mapTo(linkedSetOf()) { placement.absolute(it) }
        val excavation = if (type == MineIncidentType.TUNNEL_DRIVE) {
            localExcavation.map { placement.absolute(it) }
        } else emptyList()
        val supportFrames = localSupportFrames.map { frame ->
            frame.entries.associateTo(LinkedHashMap()) { (point, block) -> placement.absolute(point) to block }
        }
        val supports = localSupports.map { placement.absolute(it) }
        val stationLocations = stations.mapValues { (_, point) -> placement.absolute(point) }
        val railPositions = railsAll.map { placement.absolute(it) }
        val rubble = rubbleLocal.map { placement.absolute(it) }
        val shell = blocks.keys.asSequence()
            .filter { blocks.getValue(it) != AIR }
            .filter { it !in walkable && it !in railPositions && it !in rubble && it !in excavation &&
                it !in stationLocations.values && it !in fixtures }
            .toCollection(linkedSetOf())

        return MineWorkingPlan(
            type = type,
            placement = placement,
            blocks = blocks,
            footprint = blocks.keys,
            shell = shell,
            walkable = walkable,
            excavation = excavation,
            supports = supports,
            supportFrames = supportFrames,
            rubble = rubble,
            rails = when (type) {
                MineIncidentType.RAIL_EXTENSION -> railPositions
                MineIncidentType.TRACK_DAMAGE -> damageGaps.map { placement.absolute(it) }
                else -> emptyList()
            },
            cartRoute = cartRoute,
            stations = stationLocations,
            fixtures = fixtures,
            entrance = placement.entrance,
        )
    }

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
        if (plan.rails.any { rail -> plan.cartRoute.none { it == rail } }) add("rail-route-mismatch")
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
        if (plan.type !in TRACK_TYPES && plan.rails.isNotEmpty()) add("unexpected-track-route")
        if (plan.supportFrames.any { frame -> frame.keys.any { it in plan.walkable } }) add("support-crosses-walkable")
    }

    /** Rail data used by both the initial journal and later track projection. */
    internal fun railData(plan: MineWorkingPlan, position: WorksitePosition): String {
        val index = plan.cartRoute.indexOf(position)
        return if (index < 0) railState(plan.placement) else railData(plan.cartRoute, index)
    }

    private fun naturalPath(seed: Long): List<LocalPoint> {
        var side = 0
        var forward = 0
        val points = mutableListOf<LocalPoint>()
        // Fourteen forward blocks after three one-block bends keep the
        // compact NE stub inside x=72..86 while leaving the route organic.
        for (step in 1..17) {
            val turn = if (step >= 4 && step < 16 && step % 4 == 0) {
                val preferred = if (noise(seed, step, 31) < 0.5) -1 else 1
                if (side + preferred in -2..2) preferred else -preferred
            } else 0
            if (turn != 0) side = (side + turn).coerceIn(-2, 2) else forward++
            points += LocalPoint(side, 1, forward)
        }
        return points
    }

    private fun putIfClosed(
        point: LocalPoint,
        block: String,
        walkable: Set<LocalPoint>,
        put: (LocalPoint, String) -> Unit,
    ) {
        if (point !in walkable) put(point, block)
    }

    /** Smooth local geometry noise; adjacent slices share a coherent field. */
    private fun noise(seed: Long, x: Int, salt: Int): Double =
        (WorksiteCoherentNoise.sample(
            seed,
            x * 0.42,
            salt * 0.071,
            salt * 0.013,
        ) + 1.0) * 0.5

    private fun lateralNoise(seed: Long, forward: Int, side: Int, salt: Int): Double =
        (WorksiteCoherentNoise.sample(
            seed,
            forward * 0.42,
            side * 0.33 + salt * 0.071,
            salt * 0.013,
        ) + 1.0) * 0.5

    private fun railState(placement: MineWorkingPlacement): String =
        if (placement.direction % 2 == 0) NORTH_SOUTH_RAIL else EAST_WEST_RAIL

    private fun railState(route: List<LocalPoint>, index: Int, placement: MineWorkingPlacement): String =
        railData(route.map { placement.absolute(it) }, index)

    private fun railData(route: List<WorksitePosition>, index: Int): String {
        val current = route[index]
        // Rail shape stores the connection sides of the current block. The
        // previous edge therefore points current -> previous; using
        // previous -> current mirrors the incoming side and turns north-east
        // corners into the wrong Minecraft shape.
        val directions = listOfNotNull(index.takeIf { it > 0 }?.let { direction(current, route[it - 1]) },
            index.takeIf { it < route.lastIndex }?.let { direction(current, route[it + 1]) }).toSet()
        val shape = when {
            "north" in directions && "east" in directions -> "north_east"
            "north" in directions && "west" in directions -> "north_west"
            "south" in directions && "east" in directions -> "south_east"
            "south" in directions && "west" in directions -> "south_west"
            "east" in directions || "west" in directions -> "east_west"
            else -> "north_south"
        }
        return "minecraft:rail[shape=$shape,waterlogged=false]"
    }

    private fun direction(from: WorksitePosition, to: WorksitePosition): String = when {
        to.x > from.x -> "east"
        to.x < from.x -> "west"
        to.z > from.z -> "south"
        else -> "north"
    }

    private fun supportBeam(placement: MineWorkingPlacement): String =
        "minecraft:spruce_log[axis=${if (placement.direction % 2 == 0) "x" else "z"}]"

    private fun rotateFacing(data: String, turns: Int): String = if (turns == 0) data else
        FACING.replace(data) { match -> "facing=${CARDINALS[(CARDINALS.indexOf(match.groupValues[1]) + turns) % 4]}" }

    private fun manhattan(first: WorksitePosition, second: WorksitePosition): Int =
        abs(first.x - second.x) + abs(first.y - second.y) + abs(first.z - second.z)

    private data class LocalPoint(val side: Int, val up: Int, val forward: Int)

    private fun MineWorkingPlacement.absolute(point: LocalPoint): WorksitePosition =
        position(point.side, point.up, point.forward)

    private const val MAX_HEIGHT = 5
    private val FACING = Regex("facing=(north|east|south|west)")
    private val CARDINALS = listOf("north", "east", "south", "west")
    private const val ROCK = "minecraft:stone"
    private const val AIR = "minecraft:air"
    private const val RUBBLE = "minecraft:cobblestone"
    private const val SUPPORT_POST = "minecraft:spruce_log[axis=y]"
    private const val LANTERN = "minecraft:lantern[hanging=true,waterlogged=false]"
    private const val NORTH_SOUTH_RAIL = "minecraft:rail[shape=north_south,waterlogged=false]"
    private const val EAST_WEST_RAIL = "minecraft:rail[shape=east_west,waterlogged=false]"
    private val STATION_KEYS = setOf("ore", "crusher", "furnace", "output", "shipping")
    private val TRACK_TYPES = setOf(MineIncidentType.RAIL_EXTENSION, MineIncidentType.TRACK_DAMAGE)
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
