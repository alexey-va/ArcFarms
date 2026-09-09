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

    test("preview builds a sealed 9x11x5 room at configured depth") {
        val runtime = runtime(CuboidBounds(-48, 0, -48, 48, 128, 48), sequence = 11)
        val worldAdapter = adapter(runtime, "farm_greenhouse")
        val scene = requireNotNull(worldAdapter.previewGreenhouseChamber(runtime, surface()))
        check(scene.start.blockY in 52..54)
        scene.records.size shouldBe 11 * 13 * 7
        val records = scene.records.associateBy { Triple(it.x, it.y, it.z) }
        for (x in -5..5) for (z in -6..6) for (y in -1..5) {
            val record = requireNotNull(records[Triple(x, scene.start.blockY + y, z)])
            val shell = x == -5 || x == 5 || z == -6 || z == 6 || y == -1 || y == 5
            val lamp = y == 5 && x == 0 && z in setOf(-3, 0, 3)
            (record.burrowData.substringBefore('[') == if (lamp) "minecraft:shroomlight" else "minecraft:stone") shouldBe (shell || lamp)
        }
        scene.records.count { it.marker == FarmMoleBurrowMarker.START } shouldBe 1
        scene.records.count { it.marker == FarmMoleBurrowMarker.LAIR } shouldBe 1
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
        chamber.scene(runtime, 0).shouldBeNull()
    }

    test("mole and greenhouse namespaces reject coordinate collision in both directions") {
        val runtime = runtime(CuboidBounds(-48, 0, -48, 48, 128, 48), sequence = 13)
        val mole = adapter(runtime, "farm_mole_burrow")
        val greenhouse = adapter(runtime, "farm_greenhouse")
        requireNotNull(mole.ensureGreenhouseChamber(runtime, surface()).second)
        greenhouse.previewGreenhouseChamber(runtime, surface()).shouldBeNull()
        greenhouse.previewGreenhouseChamberDetailed(runtime, surface()).rejections shouldBe mapOf("journal_collision" to 1)
        val reverseSurface = FarmPointPosition("greenhouse", 16.5, 64.0, 0.5)
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
