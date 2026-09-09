package ru.ruscrafting.farms.paper.farm.care.mole

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmCareVisualSettings
import ru.ruscrafting.farms.config.FarmItemDisplayTransform
import ru.ruscrafting.farms.config.FarmMoleBurrowSettings
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmRules
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.fixtures.MockBukkitFarmBlockDataDecoder
import ru.ruscrafting.farms.paper.fixtures.MockBukkitMoleBurrowChunkRetention
import ru.ruscrafting.farms.paper.farm.incident.greenhouse.FarmHellRiftRoom

class FarmGreenhouseChamberMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("greenhouse")
        worldRef = world
        paperRef = paper
        pluginRef = paper.createSimplePlugin("FarmGreenhouseChamberTest")
        for (x in -3..3) for (z in -3..3) world.getChunkAt(x, z).load()
        for (x in -48..48) for (z in -48..48) for (y in 35..64) {
            world.getBlockAt(x, y, z).type = Material.STONE
        }
    }

    afterEach { paper.close() }

    test("preview builds a 21x25 underground farming hall at configured depth") {
        val runtime = runtime(CuboidBounds(-48, 0, -48, 48, 128, 48), sequence = 11)
        val worldAdapter = adapter(runtime, "farm_greenhouse")
        val scene = requireNotNull(worldAdapter.previewGreenhouseChamber(runtime, surface()))
        check(scene.start.blockY in 52..54)
        scene.records.size shouldBe (FarmHellRiftRoom.HALF_WIDTH * 2 + 1) *
            (FarmHellRiftRoom.HALF_LENGTH * 2 + 1) * (FarmHellRiftRoom.CEILING + 1 + 1)
        val records = scene.records.associateBy { Triple(it.x, it.y, it.z) }
        for (x in -FarmHellRiftRoom.HALF_WIDTH..FarmHellRiftRoom.HALF_WIDTH) for (z in -FarmHellRiftRoom.HALF_LENGTH..FarmHellRiftRoom.HALF_LENGTH) for (y in -1..FarmHellRiftRoom.CEILING) {
            val record = requireNotNull(records[Triple(x, scene.start.blockY + y, z)])
            val shell = x == -FarmHellRiftRoom.HALF_WIDTH || x == FarmHellRiftRoom.HALF_WIDTH ||
                z == -FarmHellRiftRoom.HALF_LENGTH || z == FarmHellRiftRoom.HALF_LENGTH ||
                y == -1 || y == FarmHellRiftRoom.CEILING
            if (shell) (record.burrowData != "minecraft:air") shouldBe true
            (record.burrowData in setOf("minecraft:lava", "minecraft:fire")) shouldBe false
        }
        scene.records.count { it.burrowData == "minecraft:shroomlight" } shouldBe 17
        FarmHellRiftRoom.bedOffsets.forEach { (bedX, bedZ) ->
            for (dx in -2..2) for (dz in -2..2) {
                records[Triple(bedX + dx, scene.start.blockY, bedZ + dz)]?.burrowData shouldBe "minecraft:soul_sand"
            }
        }
        // Both player headroom and a solid floor must connect the entry to all valve and crop approaches.
        val visited = mutableSetOf(0 to 4)
        val queue = ArrayDeque(visited)
        while (queue.isNotEmpty()) {
            val (x, z) = queue.removeFirst()
            listOf(x - 1 to z, x + 1 to z, x to z - 1, x to z + 1).forEach { point ->
                if (point !in visited && (0..1).all { dy -> records[Triple(point.first, scene.start.blockY + dy, point.second)]?.burrowData == "minecraft:air" } &&
                    records[Triple(point.first, scene.start.blockY - 1, point.second)]?.burrowData?.let { it != "minecraft:air" } == true) {
                    visited += point; queue += point
                }
            }
        }
        listOf(
            -2 to -6, 2 to -6, -2 to 6, 2 to 6, // all valve approaches
            -5 to -3, 5 to -3, -5 to 3, 5 to 3, // bed-edge approaches
        ).all { it in visited } shouldBe true

        scene.records.count { it.marker == FarmMoleBurrowMarker.START } shouldBe 1
        scene.records.count { it.marker == FarmMoleBurrowMarker.LAIR } shouldBe 1
    }

    test("every in-chunk center stays within journal record limits") {
        for (centerX in 0..15) for (centerZ in 0..15) {
            val records = FarmHellRiftRoom.blocks(centerX, 40, centerZ)
            records.size shouldBe 21 * 25 * 8
            records.keys.groupingBy { (x, _, z) -> (x shr 4) to (z shr 4) }
                .eachCount()
                .values
                .forEach { count -> (count <= 2_048) shouldBe true }
            (records.size <= 8_192) shouldBe true
        }
    }

    test("surface-only region permits underground chamber and restore removes journalled build") {
        val runtime = runtime(CuboidBounds(-48, 63, -48, 48, 66, 48), sequence = 12)
        val chamber = adapter(runtime, "farm_greenhouse")
        val (_, scene) = chamber.ensureGreenhouseChamber(runtime, surface())
        val planned = requireNotNull(scene)
        chamber.process(planned.records.size) { runtime.ownsMoleBurrowRecord(it) } shouldBe planned.records.size
        planned.ready shouldBe true
        chamber.beginRestore(world, runtime.settings.id, runtime.state.sequence)
        chamber.process(planned.records.size) { true } shouldBe planned.records.size
        world.getBlockAt(0, 53, 0).type shouldBe Material.STONE
        chamber.scene(runtime, FarmHellRiftRoom.BURROW_ID).shouldBeNull()
    }

    test("mole and greenhouse namespaces reject coordinate collision in both directions") {
        val runtime = runtime(CuboidBounds(-48, 0, -48, 48, 128, 48), sequence = 13)
        val mole = adapter(runtime, "farm_mole_burrow")
        val greenhouse = adapter(runtime, "farm_greenhouse")
        requireNotNull(mole.ensureGreenhouseChamber(runtime, surface()).second)
        greenhouse.previewGreenhouseChamber(runtime, surface()).shouldBeNull()
        greenhouse.previewGreenhouseChamberDetailed(runtime, surface()).rejections shouldBe mapOf("journal_collision" to 1)
        val reverseSurface = FarmPointPosition("greenhouse", 30.5, 64.0, 0.5)
        requireNotNull(greenhouse.ensureGreenhouseChamber(runtime, reverseSurface).second)
        mole.previewGreenhouseChamber(runtime, reverseSurface).shouldBeNull()
        mole.previewGreenhouseChamberDetailed(runtime, reverseSurface).rejections shouldBe mapOf("journal_collision" to 1)
    }
}) {
    companion object {
        private fun surface() = FarmPointPosition("greenhouse", 0.5, 64.0, 0.5)

        private fun runtime(bounds: CuboidBounds, sequence: Long): FarmRuntime {
            val settings = mockk<FarmZoneSettings> {
                every { id } returns "greenhouse_zone"
                every { crops } returns setOf("WHEAT")
                every { moleBurrow } returns FarmMoleBurrowSettings(
                    cells = 4,
                    minDepth = 10,
                    maxDepth = 12,
                    tunnelHeight = 3,
                    blocksPerTick = 64,
                    candidateAttempts = 2,
                    lightSpacing = 4,
                    lightLevel = 10,
                    lairVisual = FarmCareVisualSettings("RABBIT_HIDE", 0, FarmItemDisplayTransform.FIXED, 1.6f, 0.6),
                )
            }
            return FarmRuntime(
                settings = settings,
                region = CuboidActivityRegion(worldRef, "greenhouse", bounds),
                orders = emptyMap(), orderList = emptyList(),
                rules = FarmRules(listOf(50), 1, 1_000),
                state = FarmShiftState(phase = FarmPhase.CARE, sequence = sequence),
            )
        }

        private lateinit var worldRef: WorldMock

        private fun adapter(runtime: FarmRuntime, namespace: String): FarmMoleBurrowWorld {
            return FarmMoleBurrowWorld(
                pluginRef,
                ArcFarmsDebug({ false }) {},
                MockBukkitMoleBurrowChunkRetention(),
                MockBukkitFarmBlockDataDecoder,
                namespace,
            )
        }

        private lateinit var paperRef: MockBukkitTestRuntime
        private lateinit var pluginRef: org.bukkit.plugin.Plugin
    }
}
