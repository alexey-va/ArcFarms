package ru.ruscrafting.farms.paper.farm.placement

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import java.util.Random

class FarmPlacementServiceMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock
    lateinit var placement: FarmPlacementService

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("farm")
        world.getChunkAt(0, 0).load()
        placement = FarmPlacementService(
            plugin = paper.createSimplePlugin("FarmPlacementTest"),
            blockRegistry = mockk<FarmBlockRegistry>(relaxed = true),
            points = mockk<FarmPointProvider>(relaxed = true),
            debug = mockk<ArcFarmsDebug>(relaxed = true),
            random = Random(1L),
        )
    }

    afterEach { paper.close() }

    test("open sky accepts surface positions and rejects a roof") {
        world.getBlockAt(0, 64, 0).type = Material.GRASS_BLOCK
        val spawn = Location(world, 0.5, 65.0, 0.5)

        placement.isOpenToSky(spawn) shouldBe true

        world.getBlockAt(0, 70, 0).type = Material.OAK_PLANKS
        placement.isOpenToSky(spawn) shouldBe false
    }
})
