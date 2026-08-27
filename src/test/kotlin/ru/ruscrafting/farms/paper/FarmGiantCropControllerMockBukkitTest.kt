package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds

class FarmGiantCropControllerMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock
    lateinit var controller: FarmGiantCropController

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("farm")
        world.getChunkAt(0, 0).load()
        controller = FarmGiantCropController(paper.createSimplePlugin("GiantCropSurfaceTest"))
    }

    afterEach { paper.close() }

    test("giant crop rejects terrain above any sculpture column") {
        val anchor = world.getBlockAt(8, 65, 8).apply { type = Material.WHEAT }
        val region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 15, 128, 15))

        controller.placementIssue(region, anchor, "WHEAT", setOf("WHEAT")) shouldBe null

        for (x in 6..10) for (z in 6..10) world.getBlockAt(x, 75, z).type = Material.STONE
        controller.placementIssue(region, anchor, "WHEAT", setOf("WHEAT")) shouldBe "covered_target"
    }
})
