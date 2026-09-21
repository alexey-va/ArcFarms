package ru.ruscrafting.farms.paper.mine.working

import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MineWorkingPlacement
import ru.ruscrafting.farms.domain.worksite.WorksiteCoherentNoise
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import kotlin.math.abs

/**
 * Deterministic natural working geometry. The old generated 3x5x15 recipe
 * encoded one straight corridor; this layout keeps the authored entry but
 * opens an extended serpentine cave with a long side branch and coherent rock.
 */
internal object MineWorkingLayout {
    fun plan(
        type: MineIncidentType,
        placement: MineWorkingPlacement,
        seed: Long = placement.layoutSeed,
    ): MineWorkingPlan {
        require(type in SUPPORTED_TYPES) { "Mine incident $type is not a lateral working" }
        if (type == MineIncidentType.TUNNEL_DRIVE && MineDriveLayout.enabled(placement)) return MineDriveLayout.plan(placement)
        val path = naturalPath().toMutableList()
        val localBlocks = linkedMapOf<LocalPoint, String>()
        val localWalkable = linkedSetOf<LocalPoint>()
        val localFixtures = linkedSetOf<LocalPoint>()
        val localExcavation = linkedSetOf<LocalPoint>()
        val localSupports = mutableListOf<LocalPoint>()
        val localSupportFrames = mutableListOf<Map<LocalPoint, String>>()
        val route = path.map { it.copy(up = 1) }
        val excavationCenters = if (type == MineIncidentType.TUNNEL_DRIVE) {
            path.filter { it.forward >= 3 }
                .groupBy { it.forward }
                .toSortedMap()
                .values
                .map { it.last() }
                .take(DRILL_SECTIONS)
                .toSet()
        } else emptySet()

        fun put(point: LocalPoint, block: String) {
            localBlocks[point] = block
        }

        // The first three slices remain the authored three-wide stub. After
        // that the route opens to a seven-to-nine-wide cave, bends through a
        // side branch, and keeps every future spine cell as air. Only the ten
        // radial faces represented by excavationCenters start as rock; this is
        // what lets the player walk the full preview while the drill still has
        // a concrete, bounded action list.
        val entry = LocalPoint(0, 1, 0)
        path.add(0, entry)
        path.forEachIndexed { index, center ->
            val width = if (index < ENTRY_SLICES) 1 else 3 + if (noise(seed, index, 11) > 0.48) 1 else 0
            val roof = if (index < ENTRY_SLICES) 4 else 5 + if (noise(seed, index, 17) > 0.57) 1 else 0
            val pocket = if (index >= ENTRY_SLICES &&
                lateralNoise(seed, center.forward, center.side, 53) > 0.63) 1 else 0
            for (side in center.side - width - pocket..center.side + width + pocket) {
                // The roof is a smooth lateral field, not one flat height per
                // slice. Keep the three-block player spine at least five high
                // in the cave while the authored entry stays four high.
                val localRoof = actualRoof(seed, index, center.forward, side, roof)
                for (up in 1 until localRoof) {
                    val point = LocalPoint(side, up, center.forward)
                    localWalkable += point
                    val spine = abs(side - center.side) <= 1 && up <= 3
                    val blockedSpine = spine && center in excavationCenters && type == MineIncidentType.TUNNEL_DRIVE
                    put(point, if (blockedSpine) rockMaterial(seed, point) else AIR)
                }
                putIfClosed(LocalPoint(side, 0, center.forward), rockMaterial(seed, LocalPoint(side, 0, center.forward)), localWalkable, ::put)
                putIfClosed(LocalPoint(side, localRoof, center.forward), rockMaterial(seed, LocalPoint(side, localRoof, center.forward)), localWalkable, ::put)
            }
            // One boundary column per side. Pockets move only one boundary
            // cell at a time, avoiding the old thick slabs while preserving a
            // continuous geological shoulder around the route.
            val boundary = listOf(center.side - width - pocket - 1, center.side + width + pocket + 1)
            boundary.forEach { side ->
                putIfClosed(LocalPoint(side, 0, center.forward), rockMaterial(seed, LocalPoint(side, 0, center.forward)), localWalkable, ::put)
                for (up in 1..roof) {
                    val point = LocalPoint(side, up, center.forward)
                    putIfClosed(point, rockMaterial(seed, point), localWalkable, ::put)
                }
            }
            // A sparse ridge makes the boundary irregular without opening a
            // disconnected pocket beside the drive.
            if (noise(seed, index, 23) > 0.68) {
                val left = LocalPoint(center.side - width - pocket - 1, 2, center.forward)
                val right = LocalPoint(center.side + width + pocket + 1, 3, center.forward)
                putIfClosed(left, rockMaterial(seed, left), localWalkable, ::put)
                putIfClosed(right, rockMaterial(seed, right), localWalkable, ::put)
            }
        }

        // Reinforce the exact target faces after the shell pass. This keeps
        // the blocked volume and the persisted excavation list identical even
        // where two noisy cave slices overlap at a bend.
        excavationCenters.forEach { center ->
            for (side in -1..1) for (up in 1..3) {
                val point = LocalPoint(center.side + side, up, center.forward)
                localExcavation += point
                put(point, rockMaterial(seed, point))
            }
        }

        listOf(3, 12, 20).forEach { index ->
            val center = path[index]
            val width = if (index < ENTRY_SLICES) 1 else 3 + if (noise(seed, index, 11) > 0.48) 1 else 0
            val roof = if (index < ENTRY_SLICES) 4 else 5 + if (noise(seed, index, 17) > 0.57) 1 else 0
            val pocket = if (index >= ENTRY_SLICES &&
                lateralNoise(seed, center.forward, center.side, 53) > 0.63) 1 else 0
            val wall = width + pocket + 1
            val airSides = center.side - width - pocket..center.side + width + pocket
            // The beam must clear every noisy roof cell in this slice. Using
            // the base roof here made filterKeys discard parts of the beam
            // wherever the coherent roof rose above its 5/6-block baseline.
            val beamUp = airSides.maxOf { side -> actualRoof(seed, index, center.forward, side, roof) }
            val frame = linkedMapOf<LocalPoint, String>()
            // Supports sit in the natural wall ridge, leaving the widened
            // walkable air volume unobstructed.
            for (up in 1..beamUp) {
                frame[LocalPoint(center.side - wall, up, center.forward)] = SUPPORT_POST
                frame[LocalPoint(center.side + wall, up, center.forward)] = SUPPORT_POST
            }
            for (side in -wall..wall) frame[LocalPoint(center.side + side, beamUp, center.forward)] = supportBeam(placement)
            val safeFrame = frame.filterKeys { it !in localWalkable }
            safeFrame.forEach { (point, block) ->
                localFixtures += point
                put(point, block)
            }
            // Marker/action target stays on the left post at player height;
            // the previous roof-center target could float in noisy air.
            localSupports += LocalPoint(center.side - wall, 2, center.forward)
            localSupportFrames += safeFrame
        }

        // Practical lamps are part of the temporary scene, not invisible
        // helper blocks. They hang below the irregular roof and stay above
        // the player clearance band.
        listOf(2, 8, 15, 22).forEach { index ->
            val center = path[index]
            val width = if (index < ENTRY_SLICES) 1 else 3 + if (noise(seed, index, 11) > 0.48) 1 else 0
            val pocket = if (index >= ENTRY_SLICES &&
                lateralNoise(seed, center.forward, center.side, 53) > 0.63) 1 else 0
            val roof = if (index < ENTRY_SLICES) 4 else 5 + if (noise(seed, index, 17) > 0.57) 1 else 0
            val lampSide = center.side + (width + pocket) *
                if (lateralNoise(seed, center.forward, center.side, 29) > 0.5) 1 else -1
            val lampRoof = actualRoof(seed, index, center.forward, lampSide, roof)
            // Attach the short chain to this side's actual noisy roof. The
            // hanging lantern occupies the next block below it.
            val chain = LocalPoint(lampSide, lampRoof - 1, center.forward)
            if (chain.up > 0) {
                localWalkable.remove(chain)
                localFixtures += chain
                put(chain, CHAIN)
            }
            val point = LocalPoint(lampSide, lampRoof - 2, center.forward)
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
        val maxHeight = if (plan.type == MineIncidentType.TUNNEL_DRIVE && plan.placement.geometryVersion >= 6) 9 else MAX_HEIGHT
        if (plan.blocks.keys.any { it.y !in plan.entrance.y..(plan.entrance.y + maxHeight) }) add("height-out-of-range")
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

    private fun naturalPath(): List<LocalPoint> {
        val points = mutableListOf<LocalPoint>()
        // The authored three-block entrance opens into two bends and a long rear gallery.
        // Every rotation is validated against a captured world snapshot before placement.
        for (nextForward in 1..7) points += LocalPoint(0, 1, nextForward)
        for (nextSide in 1..3) points += LocalPoint(nextSide, 1, 7)
        for (nextForward in 8..10) points += LocalPoint(3, 1, nextForward)
        for (nextSide in 2 downTo -3) points += LocalPoint(nextSide, 1, 10)
        for (nextForward in 11..23) points += LocalPoint(-3, 1, nextForward)
        for (nextSide in -2..4) points += LocalPoint(nextSide, 1, 23)
        for (nextForward in 24..38) points += LocalPoint(4, 1, nextForward)
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

    private fun actualRoof(seed: Long, index: Int, forward: Int, side: Int, baseRoof: Int): Int =
        if (index < ENTRY_SLICES) 4 else (baseRoof + when {
            lateralNoise(seed, forward, side, 37) > 0.64 -> 1
            lateralNoise(seed, forward, side, 41) < 0.30 -> -1
            else -> 0
        }).coerceIn(5, MAX_HEIGHT)

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

    private fun rockMaterial(seed: Long, point: LocalPoint): String {
        val strata = WorksiteCoherentNoise.sample(seed, point.forward * 0.17, point.up * 0.12, point.side * 0.19)
        val vein = WorksiteCoherentNoise.sample(seed + 17L, point.forward * 0.41, point.up * 0.37, point.side * 0.43)
        return when {
            vein > 0.88 && point.up in 1..4 -> COPPER_ORE
            vein < -0.88 && point.up in 1..4 -> COAL_ORE
            strata > 0.42 -> TUFF
            strata < -0.42 -> ANDESITE
            strata < -0.05 -> DEEPSLATE
            else -> STONE
        }
    }

    private const val ENTRY_SLICES = 3
    private const val DRILL_SECTIONS = 10
    private const val MAX_HEIGHT = 7
    private val FACING = Regex("facing=(north|east|south|west)")
    private val CARDINALS = listOf("north", "east", "south", "west")
    private const val AIR = "minecraft:air"
    private const val STONE = "minecraft:stone"
    private const val DEEPSLATE = "minecraft:deepslate"
    private const val TUFF = "minecraft:tuff"
    private const val ANDESITE = "minecraft:andesite"
    private const val COAL_ORE = "minecraft:coal_ore"
    private const val COPPER_ORE = "minecraft:copper_ore"
    private const val RUBBLE = "minecraft:cobblestone"
    private const val SUPPORT_POST = "minecraft:spruce_log[axis=y]"
    private const val LANTERN = "minecraft:lantern[hanging=true,waterlogged=false]"
    private const val CHAIN = "minecraft:iron_chain[axis=y,waterlogged=false]"
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
