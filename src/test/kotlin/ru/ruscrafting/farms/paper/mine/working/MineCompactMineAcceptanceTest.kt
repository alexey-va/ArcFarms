package ru.ruscrafting.farms.paper.mine.working

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import com.google.gson.Gson
import org.bukkit.Material
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.point.MinePointDefaults
import kotlin.math.abs

/**
 * Acceptance checks against the trimmed, verified NE opening in
 * compact-mine-live.atelier.json (live-source-20260914). The full schematic
 * stays an ops fixture; this test records only the bounded facts consumed by
 * the placement planner. Workshop clearance is an additional bounded extract
 * from the same live source around the relocated crusher point.
 */
class MineCompactMineAcceptanceTest : FunSpec({
    test("natural working fits all three captured NE floors for replayed seeds") {
        NE_FLOORS.forEach { (workingId, floorId) ->
            SEEDS.forEach { seed ->
                val placement = DEFAULT_ZONE.workings.getValue(workingId)
                    .workingPlacement(floorId, layoutSeed = seed)
                ACCEPTED_TYPES.forEach { type ->
                    withClue("seed=$seed floor=$floorId type=$type") {
                        val plan = MineWorkingLayout.plan(type, placement)

                        plan.blocks.keys.all { it.x in 0..87 && it.y in 64..139 && it.z in 0..87 } shouldBe true
                        MineWorkingLayout.validate(plan).shouldBeEmpty()
                        MineWorkingPlanner.rejection(plan, ::capturedCompactMaterial) shouldBe null
                    }
                }
            }
        }
    }

    test("finite seed grid keeps all real-map placements and rail headroom") {
        NE_FLOORS.forEach { (workingId, floorId) ->
            FINITE_SEEDS.forEach { seed ->
                val placement = DEFAULT_ZONE.workings.getValue(workingId)
                    .workingPlacement(floorId, layoutSeed = seed)
                ACCEPTED_TYPES.forEach { type ->
                    withClue("seed=$seed floor=$floorId type=$type") {
                        val plan = MineWorkingLayout.plan(type, placement)
                        MineWorkingLayout.validate(plan).shouldBeEmpty()
                        MineWorkingPlanner.rejection(plan, ::capturedCompactMaterial) shouldBe null
                        if (type == MineIncidentType.RAIL_EXTENSION || type == MineIncidentType.TRACK_DAMAGE) {
                            plan.cartRoute.forEach { rail ->
                                plan.blocks[rail.copy(y = rail.y + 1)].orEmpty() shouldBe "minecraft:air"
                                plan.blocks[rail.copy(y = rail.y + 2)].orEmpty() shouldBe "minecraft:air"
                            }
                        }
                    }
                }
            }
        }
    }

    test("captured workshop markers have a solid floor and clear feet/head space") {
        WORKSHOP_MARKERS.forEach { marker ->
            (capturedCompactMaterial(marker.position.copy(y = marker.position.y - 1)) in GEOLOGICAL) shouldBe true
            capturedCompactMaterial(marker.position) shouldBe Material.AIR
            capturedCompactMaterial(marker.position.copy(y = marker.position.y + 1)) shouldBe Material.AIR
        }
    }

    test("relocated crusher has a complete real-map radius-two walking ring") {
        val center = CRUSHER
        RADIUS_TWO_OFFSETS.forEach { (dx, dz) ->
            val point = center.copy(x = center.x + dx, z = center.z + dz)
            (capturedCompactMaterial(point.copy(y = point.y - 1)) in GEOLOGICAL) shouldBe true
            capturedCompactMaterial(point) shouldBe Material.AIR
            capturedCompactMaterial(point.copy(y = point.y + 1)) shouldBe Material.AIR
        }
    }

    test("rail route is connected and corner block shapes follow the route") {
        val plan = MineWorkingLayout.plan(
            MineIncidentType.RAIL_EXTENSION,
            DEFAULT_ZONE.workings.getValue("working_2")
                .workingPlacement("middle", layoutSeed = 42L),
        )
        plan.cartRoute.zipWithNext().all { (from, to) -> manhattan(from, to) == 1 } shouldBe true
        reachable(plan.cartRoute) shouldBe plan.cartRoute.toSet()

        var cornerCount = 0
        plan.cartRoute.forEachIndexed { index, point ->
            val data = plan.blocks[point].orEmpty()
            if (!data.startsWith("minecraft:rail[")) return@forEachIndexed
            val directions = buildSet {
                if (index > 0) add(step(point, plan.cartRoute[index - 1]))
                if (index < plan.cartRoute.lastIndex) add(step(point, plan.cartRoute[index + 1]))
            }
            val hasHorizontal = "east" in directions || "west" in directions
            val hasVertical = "north" in directions || "south" in directions
            if (hasHorizontal && hasVertical) cornerCount++
            val expected = when {
                "north" in directions && "east" in directions -> "north_east"
                "north" in directions && "west" in directions -> "north_west"
                "south" in directions && "east" in directions -> "south_east"
                "south" in directions && "west" in directions -> "south_west"
                "east" in directions || "west" in directions -> "east_west"
                else -> "north_south"
            }
            data shouldBe "minecraft:rail[shape=$expected,waterlogged=false]"
        }
        cornerCount shouldBeGreaterThan 0
    }
}) {
    private companion object {
        const val WORLD = "rc_atelier_compact_mine"
        val DEFAULT_ZONE = requireNotNull(MinePointDefaults().zone("old_shafts", WORLD))
        val NE_FLOORS = listOf("working_3" to "bottom", "working_2" to "middle", "working_1" to "top")
        val SEEDS = listOf(0L, 17L, 42L)
        val FINITE_SEEDS = 0L until 64L
        val GEOLOGICAL = setOf(
            Material.STONE, Material.STONE_BRICKS, Material.ANDESITE, Material.DEEPSLATE,
            Material.DEEPSLATE_BRICKS, Material.MOSSY_STONE_BRICKS,
        )
        val CRUSHER = DEFAULT_ZONE.workshop.getValue("ore_crusher").blockPosition()
        val RADIUS_TWO_OFFSETS = buildList {
            for (dx in -2..2) for (dz in -2..2) {
                if (dx * dx + dz * dz <= 4) add(dx to dz)
            }
        }
        val WORKSHOP_MARKERS = DEFAULT_ZONE.workshop.entries
            .sortedBy { it.key }
            .map { (role, position) -> Marker(position.blockPosition(), role) }

        fun capturedCompactMaterial(position: WorksitePosition): Material {
            // This resolver only reads the bounded RLE regions extracted from
            // the live compact-mine Atelier source; uncovered coordinates fail.
            return fixture.regions.asSequence()
                .mapNotNull { it.materialAt(position) }
                .firstOrNull()
                ?: error("Captured compact-mine fixture does not cover $position")
        }

        private val fixture: CaptureFixture by lazy {
            val stream = requireNotNull(MineCompactMineAcceptanceTest::class.java
                .getResourceAsStream("/mine/compact-mine-live-ne.fixture.json"))
            stream.use {
                val raw = Gson().fromJson(it.reader(), CaptureFixture::class.java)
                CaptureFixture(raw.regions.map { region -> region.copy() })
            }
        }

        private data class CaptureFixture(val regions: List<CaptureRegion>)

        private data class CaptureRegion(
            val from: List<Int>,
            val size: List<Int>,
            val palette: List<String>,
            val rle: List<List<Int>>,
        ) {
            private val decoded: IntArray by lazy {
                val cells = IntArray(size[0] * size[1] * size[2])
                var offset = 0
                rle.forEach { run ->
                    require(run.size == 2 && run[0] in palette.indices && run[1] > 0)
                    val end = offset + run[1]
                    require(end <= cells.size)
                    cells.fill(run[0], offset, end)
                    offset = end
                }
                require(offset == cells.size)
                cells
            }
            private val materials: List<Material> by lazy {
                palette.map { block ->
                    Material.matchMaterial(block.substringAfter(':').substringBefore('['))
                        ?: error("Captured fixture block is not a Bukkit material: $block")
                }
            }

            fun materialAt(position: WorksitePosition): Material? {
                val x = position.x - from[0]
                val y = position.y - from[1]
                val z = position.z - from[2]
                if (x !in 0 until size[0] || y !in 0 until size[1] || z !in 0 until size[2]) return null
                val target = (y * size[2] + z) * size[0] + x
                return materials[decoded[target]]
            }
        }

        fun reachable(route: List<WorksitePosition>): Set<WorksitePosition> {
            val routeSet = route.toSet()
            val visited = linkedSetOf(route.first())
            val queue = ArrayDeque<WorksitePosition>().apply { addLast(route.first()) }
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                routeSet.filter { it !in visited && manhattan(current, it) == 1 }
                    .forEach { visited += it; queue.addLast(it) }
            }
            return visited
        }

        fun step(from: WorksitePosition, to: WorksitePosition): String = when {
            to.x > from.x -> "east"
            to.x < from.x -> "west"
            to.z > from.z -> "south"
            else -> "north"
        }

        fun manhattan(first: WorksitePosition, second: WorksitePosition): Int =
            abs(first.x - second.x) + abs(first.y - second.y) + abs(first.z - second.z)

        val ACCEPTED_TYPES = listOf(
            MineIncidentType.TUNNEL_DRIVE,
            MineIncidentType.RAIL_EXTENSION,
            MineIncidentType.TRACK_DAMAGE,
        )
    }

    private data class Marker(val position: WorksitePosition, val role: String)
}
