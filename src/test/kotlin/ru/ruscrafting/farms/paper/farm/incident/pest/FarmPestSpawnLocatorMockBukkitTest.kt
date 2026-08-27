package ru.ruscrafting.farms.paper.farm.incident.pest

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldNotBe
import org.bukkit.Location
import org.bukkit.Material
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import java.util.Random

class FarmPestSpawnLocatorMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("farm")
        world.getChunkAt(0, 0).load()
        world.getBlockAt(0, 64, 0).type = Material.GRASS_BLOCK
    }

    afterEach { paper.close() }

    test("pest spawn accepts the outdoor surface and rejects a cave floor") {
        val region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 15, 128, 15))
        val locator = FarmPestSpawnLocator(Random(1L), maximumAttempts = 1)
        val anchor = Location(world, 0.5, 65.0, 0.5)

        locator.find(region, anchor, 0) shouldNotBe null

        world.getBlockAt(0, 70, 0).type = Material.STONE
        locator.find(region, anchor, 0).shouldBeNull()
    }
})
