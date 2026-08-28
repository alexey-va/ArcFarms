package ru.ruscrafting.farms.paper.farm.incident.processing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.CountingFarmEntityLookup

class FarmProcessingSceneMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("farm")
        world.getChunkAt(0, 0).load()
    }

    afterEach { paper.close() }

    test("processing scene spawns in bounded batches and reconciles across controller restart") {
        val plugin = paper.createSimplePlugin("ProcessingSceneTest")
        val firstLookup = CountingFarmEntityLookup()
        val first = FarmProcessingScene(plugin, ArcFarmsDebug({ false }) {}, firstLookup)
        val objects = (0 until 5).map { index ->
            FarmProcessingSceneObject(
                role = FarmProcessingSceneRole.RAW_PACKAGE,
                index = index,
                location = Location(world, 4.5 + index, 65.0, 4.5),
                item = ItemStack(Material.WHEAT),
            )
        }
        val spec = FarmProcessingSceneSpec("farm", 7L, 2f, 2, objects)

        first.ensure(spec)
        world.entities.count(first::owns) shouldBe 2
        first.ensure(spec)
        world.entities.count(first::owns) shouldBe 4
        first.ensure(spec)
        world.entities.count(first::owns) shouldBe 5
        firstLookup.worldScans shouldBe 1
        firstLookup.globalScans shouldBe 0

        val secondLookup = CountingFarmEntityLookup()
        val restarted = FarmProcessingScene(plugin, ArcFarmsDebug({ false }) {}, secondLookup)
        restarted.ensure(spec)

        world.entities.count(restarted::owns) shouldBe 5
        secondLookup.worldScans shouldBe 1
        secondLookup.globalScans shouldBe 0

        restarted.ensure(spec.copy(objects = objects.take(2)))
        world.entities.count(restarted::owns) shouldBe 2
        restarted.clear("farm", "test")
        world.entities.count(restarted::owns) shouldBe 0
        secondLookup.globalScans shouldBe 0
    }
})
