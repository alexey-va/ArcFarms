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
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareTarget
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmRules
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmRuntime

class FarmMoleBurrowWorldMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("sp11")
        for (chunkX in -1..1) for (chunkZ in -1..1) world.getChunkAt(chunkX, chunkZ).load()
        for (x in -16..16) for (z in -16..16) for (y in 45..64) world.getBlockAt(x, y, z).type = Material.STONE
    }

    afterEach { paper.close() }

    test("preview finds a bounded underground maze without mutating the world") {
        val burrow = FarmMoleBurrowSettings(
            cells = 7,
            minDepth = 10,
            maxDepth = 12,
            tunnelHeight = 3,
            blocksPerTick = 48,
            candidateAttempts = 4,
            lightSpacing = 5,
            lightLevel = 11,
            replaceableMaterials = setOf("STONE"),
            lairVisual = FarmCareVisualSettings("RABBIT_HIDE", 0, FarmItemDisplayTransform.FIXED, 1.6f, 0.6),
        )
        val settings = mockk<FarmZoneSettings> {
            every { id } returns "communal_farm"
            every { crops } returns setOf("WHEAT")
            every { moleBurrow } returns burrow
        }
        val runtime = FarmRuntime(
            settings = settings,
            region = CuboidActivityRegion(world, "farm", CuboidBounds(-16, 0, -16, 16, 128, 16)),
            orders = emptyMap(),
            orderList = emptyList(),
            rules = FarmRules(listOf(50), 1, 1_000),
            state = FarmShiftState(phase = FarmPhase.CARE, sequence = 5),
        )
        val controller = FarmMoleBurrowWorld(
            paper.createSimplePlugin("FarmMoleBurrowWorldTest"),
            ArcFarmsDebug({ false }) {},
        )

        val scene = controller.preview(runtime, FarmPointPosition(world.name, 0.5, 65.0, 0.5))

        scene?.records?.isNotEmpty() shouldBe true
        scene?.records?.all { record ->
            world.getBlockAt(record.x, record.y, record.z).blockData.asString == record.originalData
        } shouldBe true
    }

    test("a managed crop bed can become the journalled entrance and is restored exactly") {
        val soil = world.getBlockAt(0, 64, 0).apply { type = Material.FARMLAND }
        val crop = world.getBlockAt(0, 65, 0).apply {
            type = Material.WHEAT
            blockData = blockData.also { data ->
                (data as org.bukkit.block.data.Ageable).age = 5
            }
        }
        val originalSoil = soil.blockData.asString
        val originalCrop = crop.blockData.asString
        val burrow = FarmMoleBurrowSettings(
            cells = 7,
            minDepth = 10,
            maxDepth = 12,
            tunnelHeight = 3,
            blocksPerTick = 48,
            candidateAttempts = 4,
            lightSpacing = 5,
            lightLevel = 11,
            replaceableMaterials = setOf("STONE"),
            lairVisual = FarmCareVisualSettings("RABBIT_HIDE", 0, FarmItemDisplayTransform.FIXED, 1.6f, 0.6),
        )
        val settings = mockk<FarmZoneSettings> {
            every { id } returns "communal_farm"
            every { crops } returns setOf("WHEAT")
            every { moleBurrow } returns burrow
        }
        val runtime = FarmRuntime(
            settings = settings,
            region = CuboidActivityRegion(world, "farm", CuboidBounds(-16, 0, -16, 16, 128, 16)),
            orders = emptyMap(),
            orderList = emptyList(),
            rules = FarmRules(listOf(50), 1, 1_000),
            state = FarmShiftState(phase = FarmPhase.CARE, sequence = 8),
        )
        val controller = FarmMoleBurrowWorld(
            paper.createSimplePlugin("FarmMoleBedEntranceTest"),
            ArcFarmsDebug({ false }) {},
        )
        val surface = FarmPointPosition(world.name, 0.5, 65.05, 0.5)

        val (_, scene) = controller.ensure(runtime, surface)
        val records = requireNotNull(scene).records
        controller.process(records.size) { true } shouldBe records.size
        soil.type shouldBe Material.BARRIER
        crop.type shouldBe Material.AIR

        controller.beginRestore(world, runtime.settings.id, runtime.state.sequence)
        controller.process(records.size) { true } shouldBe records.size
        soil.blockData.asString shouldBe originalSoil
        crop.blockData.asString shouldBe originalCrop
    }

    test("preview accepts natural cavities intersecting the tunnel") {
        for (x in -16..16) for (z in -16..16) for (y in 50..60) {
            world.getBlockAt(x, y, z).type = Material.AIR
        }
        val burrow = FarmMoleBurrowSettings(
            cells = 7,
            minDepth = 10,
            maxDepth = 12,
            tunnelHeight = 3,
            blocksPerTick = 48,
            candidateAttempts = 4,
            lightSpacing = 5,
            lightLevel = 11,
            replaceableMaterials = setOf("STONE"),
            lairVisual = FarmCareVisualSettings("RABBIT_HIDE", 0, FarmItemDisplayTransform.FIXED, 1.6f, 0.6),
        )
        val settings = mockk<FarmZoneSettings> {
            every { id } returns "communal_farm"
            every { crops } returns setOf("WHEAT")
            every { moleBurrow } returns burrow
        }
        val runtime = FarmRuntime(
            settings = settings,
            region = CuboidActivityRegion(world, "farm", CuboidBounds(-16, 0, -16, 16, 128, 16)),
            orders = emptyMap(),
            orderList = emptyList(),
            rules = FarmRules(listOf(50), 1, 1_000),
            state = FarmShiftState(phase = FarmPhase.CARE, sequence = 5),
        )
        val controller = FarmMoleBurrowWorld(
            paper.createSimplePlugin("FarmMoleBurrowFloorTest"),
            ArcFarmsDebug({ false }) {},
        )

        controller.preview(runtime, FarmPointPosition(world.name, 0.5, 65.0, 0.5))?.records?.isNotEmpty() shouldBe true
    }

    test("preview treats the configured farm as a horizontal footprint below its vertical region") {
        val burrow = FarmMoleBurrowSettings(
            cells = 7,
            minDepth = 10,
            maxDepth = 12,
            tunnelHeight = 3,
            blocksPerTick = 48,
            candidateAttempts = 4,
            lightSpacing = 5,
            lightLevel = 11,
            replaceableMaterials = setOf("STONE"),
            lairVisual = FarmCareVisualSettings("RABBIT_HIDE", 0, FarmItemDisplayTransform.FIXED, 1.6f, 0.6),
        )
        val settings = mockk<FarmZoneSettings> {
            every { id } returns "communal_farm"
            every { moleBurrow } returns burrow
        }
        val runtime = FarmRuntime(
            settings = settings,
            region = CuboidActivityRegion(world, "surface-only-farm", CuboidBounds(-16, 63, -16, 16, 66, 16)),
            orders = emptyMap(),
            orderList = emptyList(),
            rules = FarmRules(listOf(50), 1, 1_000),
            state = FarmShiftState(phase = FarmPhase.CARE, sequence = 9),
        )
        val controller = FarmMoleBurrowWorld(
            paper.createSimplePlugin("FarmMoleVerticalRegionTest"),
            ArcFarmsDebug({ false }) {},
        )

        val surface = FarmPointPosition(world.name, 0.5, 65.0, 0.5)
        val (_, scene) = controller.ensure(runtime, surface)
        val records = requireNotNull(scene).records

        controller.process(records.size) { runtime.ownsMoleBurrowRecord(it) } shouldBe records.size
        scene.ready shouldBe true
    }

    test("prepares and builds two independent crash-safe burrows") {
        for (chunkX in -3..3) for (chunkZ in -2..2) world.getChunkAt(chunkX, chunkZ).load()
        for (x in -40..40) for (z in -32..32) for (y in 42..64) {
            world.getBlockAt(x, y, z).type = Material.STONE
        }
        val burrow = FarmMoleBurrowSettings(
            cells = 5,
            maxBurrows = 3,
            minDepth = 10,
            maxDepth = 12,
            tunnelHeight = 3,
            blocksPerTick = 256,
            candidateAttempts = 8,
            lightSpacing = 5,
            lightLevel = 11,
            replaceableMaterials = setOf("STONE"),
            lairVisual = FarmCareVisualSettings("RABBIT_HIDE", 0, FarmItemDisplayTransform.FIXED, 1.6f, 0.6),
        )
        val settings = mockk<FarmZoneSettings> {
            every { id } returns "communal_farm"
            every { crops } returns setOf("WHEAT")
            every { moleBurrow } returns burrow
        }
        val targets = listOf(
            FarmCareTarget(0, FarmCareRole.MOLE_MOUND, FarmPointPosition(world.name, -18.5, 65.0, 0.5)),
            FarmCareTarget(1, FarmCareRole.MOLE_MOUND, FarmPointPosition(world.name, 18.5, 65.0, 0.5)),
        )
        val runtime = FarmRuntime(
            settings = settings,
            region = CuboidActivityRegion(world, "surface-only-farm", CuboidBounds(-40, 63, -32, 40, 66, 32)),
            orders = emptyMap(),
            orderList = emptyList(),
            rules = FarmRules(listOf(50), 1, 1_000),
            state = FarmShiftState(
                phase = FarmPhase.CARE,
                sequence = 12,
                placementSequence = 4,
                careType = ru.ruscrafting.farms.domain.FarmCareType.MOLES,
                careTargets = targets,
                careGoal = 2,
            ),
        )
        val controller = FarmMoleBurrowWorld(
            paper.createSimplePlugin("FarmMultiMoleBurrowTest"),
            ArcFarmsDebug({ false }) {},
        )

        controller.prepare(runtime, targets, runtime.state.placementSequence) shouldBe true
        val scenes = controller.scenes(runtime)
        scenes.size shouldBe 2
        scenes.map { it.burrowId }.toSet() shouldBe setOf(0, 1)
        val total = scenes.sumOf { it.records.size }
        controller.process(total) { runtime.ownsMoleBurrowRecord(it) } shouldBe total
        scenes.all(FarmMoleBurrowScene::ready) shouldBe true
    }

    test("preview does not carve through unconfigured building materials") {
        for (x in -16..16) for (z in -16..16) for (y in 45..64) {
            world.getBlockAt(x, y, z).type = Material.OAK_PLANKS
        }
        val burrow = FarmMoleBurrowSettings(
            cells = 7,
            minDepth = 10,
            maxDepth = 12,
            tunnelHeight = 3,
            blocksPerTick = 48,
            candidateAttempts = 4,
            lightSpacing = 5,
            lightLevel = 11,
            replaceableMaterials = setOf("STONE"),
            lairVisual = FarmCareVisualSettings("RABBIT_HIDE", 0, FarmItemDisplayTransform.FIXED, 1.6f, 0.6),
        )
        val settings = mockk<FarmZoneSettings> {
            every { id } returns "communal_farm"
            every { crops } returns setOf("WHEAT")
            every { moleBurrow } returns burrow
        }
        val runtime = FarmRuntime(
            settings = settings,
            region = CuboidActivityRegion(world, "farm", CuboidBounds(-16, 0, -16, 16, 128, 16)),
            orders = emptyMap(),
            orderList = emptyList(),
            rules = FarmRules(listOf(50), 1, 1_000),
            state = FarmShiftState(phase = FarmPhase.CARE, sequence = 5),
        )
        val controller = FarmMoleBurrowWorld(
            paper.createSimplePlugin("FarmMoleBurrowBuildingTest"),
            ArcFarmsDebug({ false }) {},
        )

        val preview = controller.previewDetailed(runtime, FarmPointPosition(world.name, 0.5, 65.0, 0.5))
        preview.scene.shouldBeNull()
        preview.rejections.keys.any { it == "material:OAK_PLANKS" } shouldBe true
    }
})
