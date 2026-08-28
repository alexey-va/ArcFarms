package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.domain.FarmPointPosition

class FarmEntityLifecycleMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("farm")
        world.getChunkAt(0, 0).load()
    }

    afterEach { paper.close() }

    test("night patrol reconciliation scans once per incident sequence") {
        val lookup = CountingFarmEntityLookup()
        val controller = FarmNightShiftController(paper.createSimplePlugin("NightLifecycleTest"), lookup)
        val region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 31, 128, 31))

        repeat(20) {
            controller.sync(
                "farm",
                7L,
                region,
                emptyList(),
                13_000L,
                emptyList(),
                FarmPointPosition("farm", 15.5, 64.0, 15.5),
                mockk(relaxed = true),
                false,
            )
        }

        lookup.worldScans shouldBe 1
        lookup.globalScans shouldBe 0
    }

    test("special scene keeps one tracked display and inactive clears stay scan free") {
        val lookup = CountingFarmEntityLookup()
        val plugin = paper.createSimplePlugin("SpecialSceneLifecycleTest")
        val manager = FarmSpecialIncidentSceneManager(plugin, ArcFarmsDebug({ false }) {}, lookup)
        val spec = FarmSpecialSceneSpec(
            zoneId = "farm",
            sequence = 4L,
            viewRange = 1f,
            objects = listOf(
                FarmSpecialSceneObject(
                    role = FarmSpecialSceneRole.CHANNEL_BLOCKAGE,
                    index = 0,
                    location = Location(world, 4.5, 65.0, 4.5),
                    item = ItemStack(Material.LEVER),
                ),
            ),
        )

        repeat(20) { manager.ensure(spec) }
        world.entities.count(manager::owns) shouldBe 1
        lookup.worldScans shouldBe 0
        lookup.globalScans shouldBe 0

        manager.clearZone("farm", "test")
        repeat(20) { manager.clearZone("farm", "inactive") }
        world.entities.count(manager::owns) shouldBe 0
        lookup.globalScans shouldBe 0
    }
})
