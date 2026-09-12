package ru.ruscrafting.farms.paper.mine.lift

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Interaction
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class MineLiftInteractionGateTest : FunSpec({
    test("deduplicates one player's repeated cabin click in a tick") {
        val gate = MineLiftInteractionGate()
        val player = UUID.randomUUID()

        gate.isDuplicate(player, 12L) shouldBe false
        gate.isDuplicate(player, 12L) shouldBe true
        gate.isDuplicate(player, 13L) shouldBe false
        gate.clear(player)
        gate.isDuplicate(player, 13L) shouldBe false
    }

    test("cabin hitboxes follow configured landing sides and are removed with the scene") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val world = paper.server.addSimpleWorld("mine")
            val plugin = paper.createSimplePlugin("MineLiftInteractionTest")
            val settings = MineLiftSettings(
                id = "main",
                world = world.name,
                x = 0.0,
                z = 0.0,
                width = 5.8,
                depth = 5.8,
                speed = 6.0,
                floors = listOf(
                    MineLiftFloor("top", 100.0, LiftPoint(-8.0, 100.0, 0.0), LiftPoint(-8.0, 100.0, 2.0)),
                    MineLiftFloor("bottom", 90.0, LiftPoint(0.0, 90.0, -8.0), LiftPoint(2.0, 90.0, -8.0)),
                ),
            )
            val scene = MineLiftScene(plugin, settings, world)
            scene.spawn(100.0) { net.kyori.adventure.text.Component.text("floor") }

            scene.entranceSides().values.toSet() shouldContainExactlyInAnyOrder listOf(
                MineLiftDoorSide.WEST,
                MineLiftDoorSide.NORTH,
            )
            val entrances = world.entities.filterIsInstance<Interaction>().filter(scene::ownsEntrance)
            entrances.size shouldBe 2
            val cabinHitbox = world.entities.filterIsInstance<Interaction>().single(scene::ownsCabinHitbox)
            cabinHitbox.interactionWidth shouldBe 6.2f
            cabinHitbox.interactionHeight shouldBe 3.23f
            scene.move(90.0, open = true) shouldBe true
            entrances.forEach { it.location.y shouldBe (90.9 plusOrMinus 0.0001) }
            cabinHitbox.location.y shouldBe (89.76 plusOrMinus 0.0001)

            val doors = world.entities.filterIsInstance<BlockDisplay>().filter { it.block.material == Material.COPPER_GRATE }
            doors.size shouldBe 2
            scene.close()
            entrances.all { !it.isValid } shouldBe true
            doors.all { !it.isValid } shouldBe true
        } finally {
            paper.close()
        }
    }

    test("cabin range is bounded around its live floor") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val world = paper.server.addSimpleWorld("mine")
            val settings = MineLiftSettings(
                id = "main", world = world.name, x = 0.0, z = 0.0, width = 5.8, depth = 5.8, speed = 6.0,
                floors = listOf(
                    MineLiftFloor("top", 100.0, LiftPoint(-8.0, 100.0, 0.0), LiftPoint(-8.0, 100.0, 2.0)),
                    MineLiftFloor("bottom", 90.0, LiftPoint(-8.0, 90.0, 0.0), LiftPoint(-8.0, 90.0, 2.0)),
                ),
            )
            settings.cabinContains(Location(world, 5.9, 100.0, 0.0), 100.0) shouldBe true
            settings.cabinContains(Location(world, 6.0, 100.0, 0.0), 100.0) shouldBe false
            settings.cabinContains(Location(world, 0.0, 103.01, 0.0), 100.0) shouldBe false
            settings.cabinContains(Location(world, 0.0, 100.0, 0.0), 100.0) shouldBe true
        } finally {
            paper.close()
        }
    }
})
