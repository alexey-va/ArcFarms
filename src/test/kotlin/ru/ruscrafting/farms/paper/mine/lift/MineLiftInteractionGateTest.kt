package ru.ruscrafting.farms.paper.mine.lift

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
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
import ru.ruscrafting.farms.domain.mine.lift.MineLiftMotion
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

                world.entities.filterIsInstance<TextDisplay>().sortedByDescending { it.y }.forEachIndexed { index, label ->
                    label.billboard shouldBe Display.Billboard.VERTICAL
                    label.location shouldBe settings.floors[index].panel.location(world).add(0.0, 1.6, 0.0)
                    label.transformation.scale.x shouldBe 1f
                }

                val cabinHitbox = world.entities.filterIsInstance<Interaction>().single(scene::ownsCabinHitbox)
                world.entities.filterIsInstance<Interaction>().count(scene::ownsCabinInteraction) shouldBe 1
                scene.seats.size shouldBe 16
                scene.seats.all { it is Interaction } shouldBe true
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
                val previewStates = mutableListOf<Map<String, Any>>()
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
                    val facing = org.bukkit.util.Vector(exitX, 0.0, exitZ).normalize()
                    val labels = world.entities.filterIsInstance<TextDisplay>().sortedByDescending { it.y }
                    labels.forEachIndexed { index, label ->
                        label.billboard shouldBe Display.Billboard.FIXED
                        label.location.direction.dot(facing) shouldBe (1.0 plusOrMinus .000001)
                        val expected = settings.floors[index].panel.location(world).add(0.0, 1.6, 0.0).add(facing.clone().multiply(.16))
                        label.location.distanceSquared(expected) shouldBe (0.0 plusOrMinus .000001)
                        label.transformation.scale.x shouldBe .8f
                        label.lineWidth shouldBe 128
                    }
                    val labelLocations = labels.map { it.location.clone() }
                    val horizontal = exitZ != 0.0
                    val sceneBlocks = world.entities.filterIsInstance<BlockDisplay>().filter {
                        it.block.material != Material.AIR && it.transformation.scale.y > 0
                    }
                    val landing = sceneBlocks.filter {
                        // Normal-track band excludes the call panels; along-track positions
                        // reach exactly -3.0 when open and must not be clipped by a cabin box.
                        abs(if(horizontal) it.location.z else it.location.x) in 2.45..2.8
                    }
                    landing.size shouldBe 40 // Two floors, two tiled sliding leaves per floor.
                    val cabin = (sceneBlocks - landing.toSet()).filter { abs(it.x) < 3 && abs(it.z) < 3 }
                    cabin.count { it.block.material == Material.GRAY_STAINED_GLASS } shouldBe 0
                    cabin.count { it.block.material == Material.OCHRE_FROGLIGHT } shouldBe 3
                    (cabin.size <= 145) shouldBe true
                    repeat(11) { scene.move(65.0, open = false) shouldBe true }
                    val closed = cabin.associate { it.uniqueId to it.location.clone() }
                    val landingClosed = landing.associate { it.uniqueId to it.location.clone() }
                    fun snapshot(id: String) {
                        if(exitX >= 0) return
                        previewStates += mapOf("id" to id, "title" to id,
                            "camera" to mapOf("position" to listOf(-7.5,17.62,0.0),
                                "target" to listOf(-2.2,17.3,0.0), "fovDegrees" to 70),
                            "pieces" to (cabin + landing).mapIndexed { i, display ->
                                val at=display.location;val scale=display.transformation.scale
                                mapOf("key" to "native-part-$i", "material" to "minecraft:${display.block.material.name.lowercase()}",
                                    "x" to at.x+scale.x/2, "y" to at.y-49.0+scale.y/2, "z" to at.z+scale.z/2,
                                    "width" to scale.x, "height" to scale.y, "depth" to scale.z)
                            })
                    }
                    for (pose in 0..11) {
                        // Include the fixed landing leaves in the same coplanarity check.
                        val boxes = (cabin + landing).map { display ->
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
                        snapshot("upper-door-$pose")
                        if (pose < 11) scene.move(65.0, open = true) shouldBe true
                    }
                    scene.isDoorOpenAt(0) shouldBe true
                    labels.map { it.location } shouldBe labelLocations
                    val moving = cabin.filter { it.location.distanceSquared(closed.getValue(it.uniqueId)) > .5 }
                    moving.size shouldBe 12
                    moving.forEach {
                        val start = if (horizontal) it.location.x else it.location.z
                        val width = if (horizontal) it.transformation.scale.x else it.transformation.scale.z
                        (start >= -2.20001 && start + width <= 2.20001) shouldBe true
                    }
                    val upperLanding = landing.filter { it.y >= 65.0 }
                    val lowerLanding = landing - upperLanding.toSet()
                    upperLanding.count { it.location.distanceSquared(landingClosed.getValue(it.uniqueId)) > .5 } shouldBe 20
                    lowerLanding.all { it.location == landingClosed.getValue(it.uniqueId) } shouldBe true
                    repeat(11) { scene.move(65.0, open=false) shouldBe true }
                    scene.move(57.0, open=false) shouldBe true
                    snapshot("travelling-doors-closed")
                    landing.all { it.location == landingClosed.getValue(it.uniqueId) } shouldBe true
                    repeat(11) { scene.move(49.0, open=true) shouldBe true }
                    snapshot("lower-floor-open-upper-closed")
                    upperLanding.all { it.location == landingClosed.getValue(it.uniqueId) } shouldBe true
                    lowerLanding.count { it.location.distanceSquared(landingClosed.getValue(it.uniqueId)) > .5 } shouldBe 20
                    landing.forEach { display ->
                        val start = if(horizontal) display.x else display.z
                        val width = if(horizontal) display.transformation.scale.x else display.transformation.scale.z
                        (start >= -3.00001 && start + width <= 3.00001) shouldBe true
                    }
                    scene.close()
                    landing.all { !it.isValid } shouldBe true
                }
                val previewPath=Path.of("build/casino-lift-preview/scene.json")
                Files.createDirectories(previewPath.parent)
                Files.writeString(previewPath,GsonBuilder().setPrettyPrinting().create().toJson(mapOf("states" to previewStates)))
            } finally {
                paper.close()
            }
        }
    }

    test("casino finished floor clears the fixed landing throughout arrival and door opening") {
        failOnUnsupportedMockBukkitOperation {
            val paper = MockBukkitTestRuntime.open()
            try {
                val world = LiftSceneWorldMock(paper.server).apply { name = "casino" }
                paper.server.addWorld(world)
                val plugin = paper.createSimplePlugin("CasinoLiftLandingTest")
                val settings = MineLiftSettings(
                    id = "casino", world = world.name, x = 49.5, z = 35.5,
                    width = 4.4, depth = 4.4, speed = 6.0,
                    floors = listOf(
                        MineLiftFloor("casino", 65.0, LiftPoint(44.5, 65.0, 35.5), LiftPoint(45.92, 65.0, 38.5)),
                        MineLiftFloor("casino_bowling", 49.0, LiftPoint(39.5, 49.0, 35.5), LiftPoint(40.92, 49.0, 38.5)),
                    ),
                    theme = MineLiftTheme.CASINO,
                )
                // The surveyed shaft keeps its solid bottom at Y=48. Only the
                // upper landing is cut out under the cabin's swept footprint.
                val fixedFloor = buildList {
                    for (x in 47..51) for (z in 33..37) add(world.getBlockAt(x, 48, z).apply {
                        type = Material.DEEPSLATE_BRICKS
                    })
                    for (y in listOf(48, 64)) for (z in 34..36) add(world.getBlockAt(46, y, z).apply {
                        type = Material.WAXED_CUT_COPPER
                    })
                }
                val scene = MineLiftScene(plugin, settings, world)
                scene.spawn(65.0) { net.kyori.adventure.text.Component.text("floor") }
                val floor = world.entities.filterIsInstance<BlockDisplay>().filter {
                    abs(it.location.y - (65.0 - .22)) < .00001
                }
                floor.size shouldBe 20
                fun checkLandingClearance() {
                    for (display in floor) {
                        val at = display.location
                        val scale = display.transformation.scale
                        for (support in fixedFloor) {
                            val overlaps = minOf(at.x + scale.x, support.x + 1.0) > maxOf(at.x, support.x.toDouble()) &&
                                minOf(at.z + scale.z, support.z + 1.0) > maxOf(at.z, support.z.toDouble())
                            if (overlaps) (at.y + scale.y - (support.y + 1.0) >= .01) shouldBe true
                        }
                    }
                }
                val motion = MineLiftMotion(settings.floors.map { it.y }, settings.speed)
                for (destination in listOf(1, 0)) {
                    motion.board(destination) shouldBe true
                    var arrived = false
                    repeat(150) {
                        if (!arrived) {
                            arrived = motion.tick()
                            scene.move(motion.y, motion.phase == MineLiftMotion.Phase.DOCKED) shouldBe true
                            checkLandingClearance()
                        }
                    }
                    arrived shouldBe true
                    repeat(11) {
                        scene.move(motion.y, open = true) shouldBe true
                        checkLandingClearance()
                    }
                }
                scene.close()
            } finally {
                paper.close()
            }
        }
    }

    test("casino passenger carriers share the cabin interpolation and preserve the riding plane throughout round trips") {
        failOnUnsupportedMockBukkitOperation {
            val paper = MockBukkitTestRuntime.open()
            try {
                val world = LiftSceneWorldMock(paper.server).apply { name = "casino" }
                paper.server.addWorld(world)
                val plugin = paper.createSimplePlugin("CasinoLiftMotionTest")
                val settings = MineLiftSettings(
                    id = "casino", world = world.name, x = 49.5, z = 35.5,
                    width = 4.4, depth = 4.4, speed = 6.0,
                    floors = listOf(65.0, 49.0, 33.0).mapIndexed { index, y ->
                        MineLiftFloor("floor_$index", y, LiftPoint(44.5, y, 35.5), LiftPoint(44.94, y, 38.5))
                    },
                    theme = MineLiftTheme.CASINO,
                )
                val scene = MineLiftScene(plugin, settings, world)
                scene.spawn(65.0) { net.kyori.adventure.text.Component.text("floor") }
                val seats = scene.seats.toList()
                seats.size shouldBe 9
                // Interaction has no native position interpolator. An AIR display
                // shares the visible cabin's interpolator without adding geometry.
                seats.all { it is BlockDisplay } shouldBe true
                val carriers = seats.filterIsInstance<BlockDisplay>()
                val floor = world.entities.filterIsInstance<BlockDisplay>().first {
                    abs(it.y - (65.0 - .22)) < .00001
                }
                carriers.forEach {
                    it.block.material shouldBe Material.AIR
                    it.teleportDuration shouldBe 1
                    it.teleportDuration shouldBe floor.teleportDuration
                    it.isPersistent shouldBe false
                }
                val player = paper.addPlayer("LiftRider")
                seats.first().addPassenger(player) shouldBe true
                player.vehicle shouldBe seats.first()
                // MockBukkit still rejects mounted teleports using the old Paper
                // contract. Native passenger retention requires server acceptance.
                player.leaveVehicle() shouldBe true
                val motion = MineLiftMotion(settings.floors.map { it.y }, settings.speed)
                for (destination in listOf(2, 1, 0)) {
                    motion.board(destination) shouldBe true
                    var arrived = false
                    repeat(240) {
                        if (!arrived) {
                            arrived = motion.tick()
                            scene.move(motion.y, motion.phase == MineLiftMotion.Phase.DOCKED) shouldBe true
                            scene.seats shouldBe seats
                            carriers.forEach { carrier ->
                                // The old seat's origin was Y+.02 and its native
                                // passenger attachment was its .01 interaction height.
                                carrier.y shouldBe (motion.y + .03 plusOrMinus .0000001)
                                (carrier.y - floor.y) shouldBe (.25 plusOrMinus .0000001)
                                carrier.teleportDuration shouldBe floor.teleportDuration
                            }
                        }
                    }
                    arrived shouldBe true
                    motion.y shouldBe settings.floors[destination].y
                }
                scene.close()
                carriers.all { !it.isValid } shouldBe true
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

/** MockBukkit's TextDisplayMock does not store billboard mode; retain the requested native setting for scene assertions. */
private class LiftSceneWorldMock(private val owner: ServerMock) : WorldMock() {
    override fun <T : Entity> spawn(location: Location, clazz: Class<T>, consumer: Consumer<in T>?): T {
        if (clazz != TextDisplay::class.java) return super.spawn(location, clazz, consumer)
        val display = object : TextDisplayMock(owner, UUID.randomUUID()) {
            private var mode = Display.Billboard.FIXED
            override fun setBillboard(billboard: Display.Billboard) { mode = billboard }
            override fun getBillboard(): Display.Billboard = mode
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
