package ru.ruscrafting.farms.paper.farm.incident.processing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Interaction
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.CountingFarmEntityLookup
import ru.ruscrafting.farms.paper.fixtures.MockBukkitFarmTextDisplays
import ru.ruscrafting.farms.paper.fixtures.mockFarmPacketTextDisplay

class FarmProcessingSceneMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("farm")
        world.getChunkAt(0, 0).load()
    }

    afterEach { paper.close() }

    test("bounded packet props retain their handles and native input identity until removed") {
        val plugin = paper.createSimplePlugin("ProcessingSceneTest")
        val lookup = CountingFarmEntityLookup()
        val handles = mutableListOf<PacketItemDisplay>()
        val labels = mutableListOf<PacketTextDisplay>()
        val displays = mockk<PaperPacketDisplays>(relaxed = true)
        every { displays.spawnItem(any(), any()) } answers {
            val at = firstArg<Location>().clone()
            mockk<PacketItemDisplay>(relaxed = true) {
                every { isValid } returns true
                every { location } returns at
            }.also(handles::add)
        }
        every { displays.spawnText(any(), any()) } answers {
            mockFarmPacketTextDisplay(firstArg()).also(labels::add)
        }
        val scene = FarmProcessingScene(plugin, ArcFarmsDebug({ false }) {}, MockBukkitFarmTextDisplays,
            lookup, displays)
        val input = FarmProcessingSceneObject(FarmProcessingSceneRole.RAW_INTERACTION, 0,
            Location(world, 4.5, 65.0, 4.5))
        val objects = listOf(input) + (0 until 5).map { index ->
            FarmProcessingSceneObject(FarmProcessingSceneRole.RAW_PACKAGE, index,
                Location(world, 4.5 + index, 65.0, 4.5), item = ItemStack(Material.WHEAT))
        } + FarmProcessingSceneObject(FarmProcessingSceneRole.LABEL, 0,
            Location(world, 4.5, 66.25, 4.5), text = Component.text("Output"), scale = 1.5f)
        val spec = FarmProcessingSceneSpec("farm", 7L, 2f, 2, objects)

        scene.ensure(spec)
        handles.size shouldBe 1
        val nativeInput = world.entities.filterIsInstance<Interaction>().single(scene::owns)
        val inputId = nativeInput.uniqueId
        scene.identity(nativeInput) shouldBe FarmProcessingSceneIdentity("farm", 7L, FarmProcessingSceneRole.RAW_INTERACTION, 0)
        scene.ensure(spec)
        handles.size shouldBe 3
        scene.ensure(spec)
        handles.size shouldBe 5
        scene.ensure(spec)
        handles.size shouldBe 5
        val label = labels.single()
        verify(exactly = 1) { label.text(Component.text("Output")) }
        verify(exactly = 1) { label.transformation = match { it.scale.x == 1.5f && it.scale.y == 1.5f && it.scale.z == 1.5f } }
        scene.item("farm", FarmProcessingSceneRole.RAW_PACKAGE) shouldBe handles.first()
        world.entities.filter(scene::owns).map { it.uniqueId } shouldBe listOf(inputId)
        lookup.worldScans shouldBe 1
        lookup.globalScans shouldBe 0

        scene.ensure(spec.copy(objects = objects.take(3)))
        handles.drop(2).forEach { handle -> verify(exactly = 1) { handle.remove() } }
        verify(exactly = 1) { label.remove() }
        world.entities.filter(scene::owns).map { it.uniqueId } shouldBe listOf(inputId)
        scene.clear("farm", "test")
        handles.forEach { handle -> verify(exactly = 1) { handle.remove() } }
        verify(exactly = 1) { label.remove() }
        world.entities.count(scene::owns) shouldBe 0
        lookup.globalScans shouldBe 0
    }
})
