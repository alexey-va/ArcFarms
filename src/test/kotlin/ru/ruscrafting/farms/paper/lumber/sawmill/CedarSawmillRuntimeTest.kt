package ru.ruscrafting.farms.paper.lumber.sawmill

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeInstance
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.scene.PaperSpatialControls
import ru.arc.paper.scene.SpatialInputProfile
import ru.arc.paper.scene.SpatialReach
import ru.arc.paper.scene.SpatialSceneScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.paper.WorksiteModuleRegistry
import ru.ruscrafting.farms.paper.lumber.LumbermillVersionedModule
import java.util.UUID

class CedarSawmillRuntimeTest : FunSpec({
    test("keyed packet visuals retain all eighteen model handles through cut pause finish and replay") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld(CedarSawmillController.WORLD_NAME)
            val recording = SawmillDisplayRecording()
            val scene = CedarSawmillDisplays(world, recording.displays)
            scene.render(CedarSawmillAnimation.sample(0.0), false)
            val ids = scene.entityIds()
            ids.size shouldBe CedarSawmillGeometry.parts.size
            ids.values.toSet().size shouldBe ids.size
            scene.itemCount shouldBe 18
            recording.items.map { it.itemMeta.customModelData }.toSet() shouldBe setOf(271001, 271002, 271003, 271004, 271005)
            val handles = recording.handles.toList()
            scene.render(CedarSawmillAnimation.sample(0.0), false)
            handles.forEach { handle -> verify(exactly = 1) { handle.transformation = any() } }
            for ((seconds, smooth) in listOf(3.0 to true, 12.0 to true, 12.0 to false, 25.0 to true, 0.0 to false)) {
                scene.render(CedarSawmillAnimation.sample(seconds), smooth)
                scene.entityIds() shouldBe ids
                recording.handles shouldBe handles
            }
            world.entities shouldBe emptyList()
            scene.close()
            scene.close()
            handles.forEach { handle -> verify(exactly = 1) { handle.remove() } }
            verify(exactly = 0) { recording.displays.close() }
        }
    }

    xtest("direct item_model survives carrier copies [unsupported: MockBukkit 4.116.3 ItemMetaMock copy constructor omits itemModel]") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld(CedarSawmillController.WORLD_NAME)
            val recording = SawmillDisplayRecording()
            val scene = CedarSawmillDisplays(world, recording.displays)
            scene.render(CedarSawmillAnimation.sample(0.0), false)
            recording.items.map { it.itemMeta.itemModel.toString() }.toSet() shouldBe setOf(
                "arc_farms:cedar_sawmill_raw", "arc_farms:cedar_sawmill_sawn", "arc_farms:cedar_sawmill_slab",
                "arc_farms:cedar_sawmill_blade", "arc_farms:cedar_sawmill_pulley",
            )
            scene.close()
        }
    }

    test("module uses shared lifecycle and fast hook while lumber travel and shifts remain unavailable") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld(CedarSawmillController.WORLD_NAME)
            val plugin = paper.createSimplePlugin("CedarSawmillLifecycle")
            val recording = SawmillDisplayRecording()
            val module = LumbermillVersionedModule(plugin, "spawn", recording.displays)
            val registry = WorksiteModuleRegistry(listOf(module))
            val player = paper.server.addPlayer("Observer")
            registry.activateLoadedState()
            module.sawmillSnapshot()["active"] shouldBe true
            module.sawmillSnapshot()["item_parts"] shouldBe 18
            module.sawmillSnapshot()["desired_native_targets"] shouldBe 1
            registry.isAvailable(ActivityKind.LUMBER) shouldBe false
            registry.canAccess(player, ActivityKind.LUMBER) shouldBe false
            registry.statuses() shouldBe emptyList()
            module.states() shouldBe emptyMap()
            val handles = recording.handles.toList()
            repeat(12) { registry.updateVisuals() }
            registry.reconcileChunk(world.getChunkAt(11, 11))
            recording.handles shouldBe handles
            registry.beforeChunkUnload(world.getChunkAt(11, 11))
            registry.updateVisuals()
            registry.updateVisuals()
            recording.handles shouldBe handles
            registry.beforeReload("test")
            handles.forEach { verify(exactly = 1) { it.remove() } }
            repeat(12) { registry.updateVisuals() }
            recording.handles shouldBe handles
            registry.activateLoadedState()
            module.sawmillSnapshot()["phase"] shouldBe "IDLE"
            module.sawmillSnapshot()["elapsed_ticks"] shouldBe 0
            recording.handles.size shouldBe handles.size * 2
            registry.cleanup("test")
            verify(exactly = 0) { recording.displays.close() }
        }
    }

    test("an unrelated server never creates the fixed spawn demonstration") {
        MockBukkitTestRuntime.open().use { paper ->
            paper.addSimpleWorld(CedarSawmillController.WORLD_NAME)
            val recording = SawmillDisplayRecording()
            val module = LumbermillVersionedModule(paper.createSimplePlugin("OtherSawmillNode"), "survival", recording.displays)
            module.activateLoadedState()
            repeat(20) { module.updateVisuals() }
            module.sawmillSnapshot()["active"] shouldBe false
            recording.handles shouldBe emptyList()
            module.cleanup("test")
        }
    }

    test("shared input uses current lever faces native reach occlusion public scope and duplicate suppression") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld(CedarSawmillController.WORLD_NAME)
            val plugin = paper.createSimplePlugin("CedarSawmillInput")
            val recording = SawmillDisplayRecording()
            val controller = CedarSawmillController(plugin, "spawn", recording.displays)
            controller.activateLoadedState()
            val rays = mutableListOf<Double>()
            var blocked = false
            val rayWorld = spyk(world)
            every { rayWorld.isChunkLoaded(any<Int>(), any<Int>()) } returns true
            every { rayWorld.rayTraceBlocks(any(), any(), any(), any(), any(), any()) } answers {
                val maximum = thirdArg<Double>()
                rays += maximum
                if (blocked && maximum >= 1.0) RayTraceResult(firstArg<Location>().toVector().add(secondArg<Vector>())) else null
            }
            val lever = CedarSawmillAnimation.sample(0.0).poses.single { it.partId == "lever-handle-1" }.center
            val origin = CedarSawmillGeometry.origin
            val target = Vector((origin.x + lever.x).toDouble(), (origin.y + lever.y).toDouble(), (origin.z + lever.z).toDouble())
            fun actor(distance: Double = 2.0, side: Double = 0.0): Player {
                val at = target.clone().add(Vector(side, 0.0, distance)).toLocation(rayWorld)
                at.direction = Vector(0.0, 0.0, -1.0)
                val reach = mockk<AttributeInstance> { every { value } returns 3.0 }
                return mockk<Player>(relaxed = true) {
                    every { uniqueId } returns UUID.randomUUID()
                    every { this@mockk.world } returns rayWorld
                    every { isOnline } returns true
                    every { isDead } returns false
                    every { eyeLocation } answers { at.clone() }
                    every { isChunkSent(any<Long>()) } returns true
                    every { getAttribute(Attribute.ENTITY_INTERACTION_RANGE) } returns reach
                }
            }
            val player = actor()
            val key = CedarSawmillController.SCENE_KEY
            val frame = requireNotNull(controller.current(player, key))
            frame.scope shouldBe SpatialSceneScope.World
            frame.input shouldBe SpatialInputProfile.ENTITY_RIGHT
            frame.reach shouldBe SpatialReach.NativeInteraction
            frame.controls.count { it.nativeTarget != null } shouldBe 1
            frame.controls.all { it.planes.size == 6 } shouldBe true
            // This exercises the shared input boundary; the recording display owner never spawns a native fixture.
            val input = PaperSpatialControls(plugin, recording.displays, controller)
            input.apply(key, frame)
            input.intercept(key, actor(3.3)) shouldBe false
            input.intercept(key, actor(side = .25)) shouldBe false
            blocked = true
            input.intercept(key, player) shouldBe false
            blocked = false
            input.intercept(key, player) shouldBe true
            controller.snapshot()["phase"] shouldBe "RUNNING"
            input.apply(key, requireNotNull(controller.current(player, key)))
            input.intercept(key, player) shouldBe true
            controller.snapshot()["phase"] shouldBe "RUNNING"
            controller.snapshot()["cycles_started"] shouldBe 1
            val handles = recording.handles.toList()
            repeat(41) { controller.updateVisuals() }
            controller.snapshot()["elapsed_ticks"] shouldBe 41
            controller.activate(player, key, Unit, Unit)
            repeat(20) { controller.updateVisuals() }
            controller.snapshot()["phase"] shouldBe "PAUSED"
            controller.snapshot()["elapsed_ticks"] shouldBe 41
            controller.activate(player, key, Unit, Unit)
            repeat(2) { controller.updateVisuals() }
            controller.snapshot()["phase"] shouldBe "RUNNING"
            controller.snapshot()["elapsed_ticks"] shouldBe 43
            controller.snapshot()["cycles_started"] shouldBe 1
            recording.handles shouldBe handles
            rays.isNotEmpty() shouldBe true
            rays.all { it <= 3.0 } shouldBe true
            controller.cleanup("test")
            input.close()
        }
    }
})

private class SawmillDisplayRecording {
    val displays = mockk<PaperPacketDisplays>(relaxed = true)
    val handles = mutableListOf<PacketDisplay>()
    val items = mutableListOf<ItemStack>()
    private var nextId = 1000

    init {
        every { displays.spawnBlock(any(), any()) } answers {
            val at = firstArg<Location>().clone()
            mockk<PacketBlockDisplay>(relaxed = true).also { prepare(it, at) }
        }
        every { displays.spawnItem(any(), any()) } answers {
            val at = firstArg<Location>().clone()
            items += secondArg<ItemStack>().clone()
            mockk<PacketItemDisplay>(relaxed = true).also { prepare(it, at) }
        }
    }

    private fun prepare(handle: PacketDisplay, at: Location) {
        val id = nextId++
        every { handle.entityId } returns id
        every { handle.isValid } returns true
        every { handle.location } answers { at.clone() }
        handles += handle
    }
}
