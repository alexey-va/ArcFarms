package ru.ruscrafting.farms.paper.mine.lift

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.TextDisplay
import org.bukkit.event.entity.CreatureSpawnEvent
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.TextDisplayMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import java.util.function.Consumer
import kotlin.math.abs

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

    test("walk-in menu stays latched while an entry attempt is cancelled and retries after stepping away") {
        val gate = MineLiftInteractionGate()
        val player = UUID.randomUUID()

        gate.shouldOpenWalkInMenu(player, 20L) shouldBe true
        gate.shouldOpenWalkInMenu(player, 21L) shouldBe false
        gate.clearWalkIn(player)
        gate.shouldOpenWalkInMenu(player, 21L) shouldBe true
    }

    test("one cabin body hitbox and four matching iron guard walls are removed with the scene") {
        failOnUnsupportedMockBukkitOperation {
            val paper = MockBukkitTestRuntime.open()
            try {
                val world = LiftSceneWorldMock(paper.server).apply { name = "mine" }
                paper.server.addWorld(world)
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

                val cabinHitbox = world.entities.filterIsInstance<Interaction>().single(scene::ownsCabinHitbox)
                world.entities.filterIsInstance<Interaction>().count(scene::ownsCabinInteraction) shouldBe 1
                scene.seats.size shouldBe 16
                scene.panels.size shouldBe 2
                cabinHitbox.interactionWidth shouldBe 6.2f
                cabinHitbox.interactionHeight shouldBe 3.23f
                scene.isDoorOpenAt(0) shouldBe true
                scene.move(100.0, open = false) shouldBe true
                scene.isDoorOpenAt(0) shouldBe false
                scene.move(100.0, open = true) shouldBe true
                scene.isDoorOpenAt(0) shouldBe true
                scene.isDoorOpenAt(1) shouldBe false
                scene.move(90.0, open = true) shouldBe true
                scene.isDoorOpenAt(0) shouldBe false
                scene.isDoorOpenAt(1) shouldBe true
                cabinHitbox.location.y shouldBe (89.76 plusOrMinus 0.0001)

                val rails = world.entities.filterIsInstance<BlockDisplay>().filter { it.block.material == Material.IRON_BLOCK }
                world.entities.filterIsInstance<BlockDisplay>().count { it.block.material == Material.COPPER_GRATE } shouldBe 0
                rails.size shouldBe 8
                rails.all { !it.isGlowing } shouldBe true
                scene.close()
                cabinHitbox.isValid shouldBe false
                rails.all { !it.isValid } shouldBe true
            } finally {
                paper.close()
            }
        }
    }

    test("casino sliding leaves stay inside the wall pockets without coplanar faces at any door pose") {
        failOnUnsupportedMockBukkitOperation {
            val paper = MockBukkitTestRuntime.open()
            try {
                val world = LiftSceneWorldMock(paper.server).apply { name = "casino" }
                paper.server.addWorld(world)
                val plugin = paper.createSimplePlugin("CasinoLiftInteractionTest")
                for ((exitX, exitZ) in listOf(-5.0 to 0.0, 5.0 to 0.0, 0.0 to -5.0, 0.0 to 5.0)) {
                    val settings = MineLiftSettings(
                        id = "casino", world = world.name, x = 0.0, z = 0.0,
                        width = 4.4, depth = 4.4, speed = 3.2,
                        floors = listOf(
                            MineLiftFloor("casino", 65.0, LiftPoint(exitX, 65.0, exitZ), LiftPoint(4.0, 65.0, 4.0)),
                            MineLiftFloor("casino_bowling", 49.0, LiftPoint(exitX, 49.0, exitZ), LiftPoint(4.0, 49.0, 4.0)),
                        ),
                        theme = MineLiftTheme.CASINO,
                    )
                    val scene = MineLiftScene(plugin, settings, world)
                    scene.spawn(65.0) { net.kyori.adventure.text.Component.text("floor") }
                    val cabin = world.entities.filterIsInstance<BlockDisplay>().filter {
                        abs(it.location.x) < 3 && abs(it.location.z) < 3 && it.transformation.scale.y > 0
                    }
                    cabin.count { it.block.material == Material.GRAY_STAINED_GLASS } shouldBe 0
                    cabin.count { it.block.material == Material.OCHRE_FROGLIGHT } shouldBe 3
                    (cabin.size <= 145) shouldBe true
                    repeat(11) { scene.move(65.0, open = false) shouldBe true }
                    val closed = cabin.associate { it.uniqueId to it.location.clone() }
                    for (pose in 0..11) {
                        val boxes = cabin.map { display ->
                            val at = display.location
                            val scale = display.transformation.scale
                            listOf(at.x, at.y, at.z, at.x + scale.x, at.y + scale.y, at.z + scale.z)
                        }
                        val overlaps = mutableListOf<String>()
                        for (first in boxes.indices) for (second in first + 1 until boxes.size) {
                            val left = boxes[first]
                            val right = boxes[second]
                            for (axis in 0..2) {
                                val projected = (0..2).filter { it != axis }.all {
                                    minOf(left[it + 3], right[it + 3]) - maxOf(left[it], right[it]) > .00001
                                }
                                if (projected && (abs(left[axis] - right[axis]) < .00001 ||
                                        abs(left[axis + 3] - right[axis + 3]) < .00001)) {
                                    overlaps += "pose=$pose side=${settings.openingSide(0)} pair=$first/$second axis=$axis"
                                }
                            }
                        }
                        overlaps shouldBe emptyList()
                        if (pose < 11) scene.move(65.0, open = true) shouldBe true
                    }
                    scene.isDoorOpenAt(0) shouldBe true
                    val moving = cabin.filter { it.location.distanceSquared(closed.getValue(it.uniqueId)) > .5 }
                    moving.size shouldBe 12
                    val horizontal = exitZ != 0.0
                    moving.forEach {
                        val start = if (horizontal) it.location.x else it.location.z
                        val width = if (horizontal) it.transformation.scale.x else it.transformation.scale.z
                        (start >= -2.20001 && start + width <= 2.20001) shouldBe true
                    }
                    scene.close()
                }
            } finally {
                paper.close()
            }
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

/** MockBukkit's TextDisplayMock aborts on billboard; that presentation setting is irrelevant to this scene test. */
private class LiftSceneWorldMock(private val owner: ServerMock) : WorldMock() {
    override fun <T : Entity> spawn(location: Location, clazz: Class<T>, consumer: Consumer<in T>?): T {
        if (clazz != TextDisplay::class.java) return super.spawn(location, clazz, consumer)
        val display = object : TextDisplayMock(owner, UUID.randomUUID()) {
            override fun setBillboard(billboard: Display.Billboard) = Unit
        }
        display.location = location
        display.setSpawnReason(CreatureSpawnEvent.SpawnReason.DEFAULT)
        @Suppress("UNCHECKED_CAST")
        requireNotNull(consumer).accept(display as T)
        owner.registerEntity(display)
        @Suppress("UNCHECKED_CAST")
        return display as T
    }
}
