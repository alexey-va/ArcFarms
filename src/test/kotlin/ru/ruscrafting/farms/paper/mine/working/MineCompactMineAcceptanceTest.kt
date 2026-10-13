package ru.ruscrafting.farms.paper.mine.working

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import com.google.gson.Gson
import org.bukkit.Material
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MineWorkingPlacement
import ru.ruscrafting.farms.domain.MineLocationPosition
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import kotlin.math.abs

/**
 * Acceptance checks against the 2026-09-14 compact-mine capture, pinned to
 * geometry v3 and map-points revision 042da9e (as recorded when the fixture
 * was expanded in b167f5a). These checks deliberately do not claim that the
 * current map points or current geometry fit this historical capture.
 */
class MineCompactMineAcceptanceTest : FunSpec({
    test("historical geometry v3 fits all captured NE floors for replayed seeds") {
        HISTORICAL_NE_FLOORS.forEach { floor ->
            SEEDS.forEach { seed ->
                val placement = historicalPlacement(floor, seed)
                ACCEPTED_TYPES.forEach { type ->
                    withClue("seed=$seed floor=${floor.floorId} type=$type") {
                        val plan = MineWorkingLayout.plan(type, placement)

                        plan.blocks.keys.all { it.x in 0..87 && it.y in 64..139 && it.z in 0..87 } shouldBe true
                        MineWorkingLayout.validate(plan).shouldBeEmpty()
                        MineWorkingPlanner.rejection(plan, ::capturedCompactMaterial) shouldBe null
                    }
                }
            }
        }
    }

    test("historical geometry v3 finite seed grid fits the captured map and rail headroom") {
        HISTORICAL_NE_FLOORS.forEach { floor ->
            FINITE_SEEDS.forEach { seed ->
                val placement = historicalPlacement(floor, seed)
                ACCEPTED_TYPES.forEach { type ->
                    withClue("seed=$seed floor=${floor.floorId} type=$type") {
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

    test("geometry v3 keeps its capture route and v4 keeps its extended route") {
        val floor = HISTORICAL_NE_FLOORS.single { it.workingId == "working_2" }
        val historical = historicalPlacement(floor, 42L)
        fun plan(version: Int, seed: Long) = MineWorkingLayout.plan(
            MineIncidentType.RAIL_EXTENSION,
            historical.copy(geometryVersion = version, layoutSeed = seed),
        )
        val v3Seed42 = plan(3, 42L)
        val v3Seed17 = plan(3, 17L)
        val v4 = plan(4, 42L)

        v3Seed42.cartRoute.size shouldBe 12
        v3Seed42.cartRoute shouldBe v3Seed17.cartRoute
        v3Seed42.blocks.keys.maxOf { it.x - historical.entrance.x } shouldBe 14
        v4.cartRoute shouldBe v3Seed42.cartRoute
        v4.blocks.keys.maxOf { it.x - historical.entrance.x } shouldBe 38
    }

    test("historical workshop markers have a solid floor and clear feet/head space") {
        WORKSHOP_MARKERS.forEach { marker ->
            (capturedCompactMaterial(marker.position.copy(y = marker.position.y - 1)) in GEOLOGICAL) shouldBe true
            capturedCompactMaterial(marker.position) shouldBe Material.AIR
            capturedCompactMaterial(marker.position.copy(y = marker.position.y + 1)) shouldBe Material.AIR
        }
    }

    test("historical crusher point has a complete captured radius-two walking ring") {
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
            historicalPlacement(HISTORICAL_NE_FLOORS.single { it.workingId == "working_2" }, 42L),
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
        const val CAPTURED_GEOMETRY_VERSION = 3
        const val HISTORICAL_FIXTURE_ID = "compact-mine-live-2026-09-14-ne-geometry-v3-points-042da9e"
        const val HISTORICAL_MAP_POINTS_REVISION = "042da9e"
        const val HISTORICAL_FIXTURE_REVISION = "b167f5a"
        const val HISTORICAL_SOURCE_SHA256 = "055370ec8473a9cb1012a588930748d38c01f91e6c76dac78e9d66046233954f"
        private val mapPoints: HistoricalMapPointsFixture by lazy {
            val stream = requireNotNull(MineCompactMineAcceptanceTest::class.java
                .getResourceAsStream("/mine/compact-mine-map-points-v3.fixture.json"))
            stream.use {
                val raw = Gson().fromJson(it.reader(), HistoricalMapPointsFixture::class.java)
                require(raw.fixtureId == "compact-mine-map-points-v3-042da9e")
                require(raw.geometryVersion == CAPTURED_GEOMETRY_VERSION)
                require(raw.mapPointsRevision == HISTORICAL_MAP_POINTS_REVISION)
                require(raw.world == WORLD)
                raw
            }
        }
        val HISTORICAL_NE_FLOORS by lazy {
            mapPoints.workings.map { point ->
                HistoricalFloor(
                    point.workingId,
                    point.floorId,
                    MineLocationPosition(WORLD, point.x, point.y, point.z, point.yaw),
                )
            }
        }
        val SEEDS = listOf(0L, 17L, 42L)
        val FINITE_SEEDS = 0L until 64L
        val GEOLOGICAL = setOf(
            Material.STONE, Material.STONE_BRICKS, Material.ANDESITE, Material.DEEPSLATE,
            Material.DEEPSLATE_BRICKS, Material.MOSSY_STONE_BRICKS,
        )
        val CRUSHER by lazy {
            mapPoints.workshop.single { it.role == "ore_crusher" }
                .let { WorksitePosition(WORLD, it.x.toInt(), it.y.toInt(), it.z.toInt()) }
        }
        val RADIUS_TWO_OFFSETS = buildList {
            for (dx in -2..2) for (dz in -2..2) {
                if (dx * dx + dz * dz <= 4) add(dx to dz)
            }
        }
        val WORKSHOP_MARKERS by lazy {
            mapPoints.workshop.sortedBy { it.role }.map { point ->
                Marker(WorksitePosition(WORLD, point.x.toInt(), point.y.toInt(), point.z.toInt()), point.role)
            }
        }

        fun historicalPlacement(floor: HistoricalFloor, seed: Long) = MineWorkingPlacement(
            entrance = floor.point.workingPlacement(floor.floorId, layoutSeed = seed).entrance,
            direction = floor.point.workingDirection(),
            floorId = floor.floorId,
            layoutSeed = seed,
            geometryVersion = CAPTURED_GEOMETRY_VERSION,
        )

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
                require(raw.fixtureId == HISTORICAL_FIXTURE_ID)
                require(raw.geometryVersion == CAPTURED_GEOMETRY_VERSION)
                require(raw.mapPointsRevision == HISTORICAL_MAP_POINTS_REVISION)
                require(raw.fixtureRevision == HISTORICAL_FIXTURE_REVISION)
                require(raw.sourceSha256 == HISTORICAL_SOURCE_SHA256)
                raw
            }
        }

        private data class HistoricalFloor(
            val workingId: String,
            val floorId: String,
            val point: MineLocationPosition,
        )

        private data class HistoricalMapPointsFixture(
            val fixtureId: String,
            val geometryVersion: Int,
            val mapPointsRevision: String,
            val world: String,
            val workings: List<HistoricalWorkingPoint>,
            val workshop: List<HistoricalWorkshopPoint>,
        )

        private data class HistoricalWorkingPoint(
            val workingId: String,
            val floorId: String,
            val x: Double,
            val y: Double,
            val z: Double,
            val yaw: Float,
        )

        private data class HistoricalWorkshopPoint(
            val role: String,
            val x: Double,
            val y: Double,
            val z: Double,
            val yaw: Float,
        )

        private data class CaptureFixture(
            val fixtureId: String,
            val geometryVersion: Int,
            val mapPointsRevision: String,
            val fixtureRevision: String,
            val sourceSha256: String,
            val regions: List<CaptureRegion>,
        )

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
