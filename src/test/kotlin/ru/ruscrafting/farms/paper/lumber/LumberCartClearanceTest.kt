package ru.ruscrafting.farms.paper.lumber

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.util.BoundingBox
import ru.arc.paper.display.DisplayOrientedVolume
import ru.arc.paper.scene.PaperSpatialSpace
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.lumber.LumberPose

class LumberCartClearanceTest : FunSpec({
    test("the recorded stuck pose has a clear escape translation although turning padding meets the captured leaf") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("rc_atelier_cedar_sawmill")
            val leaf = BoundingBox(154.0, 73.0, 194.0, 155.0, 74.0, 195.0)
            val space = mockk<PaperSpatialSpace> {
                every { clear(world, any<DisplayOrientedVolume>(), null) } answers {
                    !secondArg<DisplayOrientedVolume>().overlaps(leaf)
                }
            }
            val guard = LumberCartClearance(space)
            val from = LumberPose(world.name, 157.77768236673901, 73.0, 197.8592397503807, -56.086792f)
            val escape = from.copy(z = from.z + .7)
            guard.allows(from, from) shouldBe true
            guard.allows(from, escape) shouldBe true
            guard.allows(from, escape.copy(yaw = from.yaw - 22f)) shouldBe false
            guard.resolve(from, escape.copy(yaw = from.yaw - 22f)) shouldBe escape
            // The same solid leaf still blocks a translation into it.
            guard.allows(from, from.copy(z = from.z - .7)) shouldBe false
        }
    }

    test("a safe turn in place is preserved when forward translation is blocked") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("cedar")
            val wall = BoundingBox(130.0, 73.0, 211.2, 150.0, 76.0, 212.2)
            val space = mockk<PaperSpatialSpace> {
                every { clear(world, any<DisplayOrientedVolume>(), null) } answers {
                    !secondArg<DisplayOrientedVolume>().overlaps(wall)
                }
            }
            val guard = LumberCartClearance(space)
            val from = LumberPose(world.name, 140.0, 73.0, 207.0, 0f)
            val proposed = from.copy(z = from.z + .7, yaw = 22f)
            guard.allows(from, proposed) shouldBe false
            guard.allows(from, proposed.copy(yaw = from.yaw)) shouldBe false
            guard.resolve(from, proposed) shouldBe from.copy(yaw = 22f)
        }
    }

    test("the shared loaded-chunk collision oracle can stop a short cart sweep and no teleport bypass is admitted") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("cedar")
            var blocked = false
            val space = mockk<PaperSpatialSpace> {
                every { clear(world, any<DisplayOrientedVolume>(), null) } answers { !blocked }
            }
            val guard = LumberCartClearance(space)
            val from = LumberPose("cedar", 140.0, 73.0, 207.0, -90f)
            guard.allows(from, from.copy(x = 140.5, yaw = -80f)) shouldBe true
            guard.resolve(from, from.copy(x = 140.5, yaw = -80f)) shouldBe from.copy(x = 140.5, yaw = -80f)
            guard.resolve(from, from.copy(x = 180.0)) shouldBe null
            guard.resolve(from, from.copy(world = "other")) shouldBe null
            blocked = true
            guard.allows(from, from.copy(x = 140.5, yaw = -80f)) shouldBe false
            guard.resolve(from, from.copy(x = 140.5, yaw = -80f)) shouldBe null
            guard.allows(from, from.copy(x = 180.0)) shouldBe false
            verify { space.clear(world, any<DisplayOrientedVolume>(), null) }
            world.entities shouldBe emptyList()
        }
    }
})
