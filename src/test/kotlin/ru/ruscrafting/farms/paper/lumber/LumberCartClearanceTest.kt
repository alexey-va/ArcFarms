package ru.ruscrafting.farms.paper.lumber

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.paper.display.DisplayOrientedVolume
import ru.arc.paper.scene.PaperSpatialSpace
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.lumber.LumberPose

class LumberCartClearanceTest : FunSpec({
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
            blocked = true
            guard.allows(from, from.copy(x = 140.5, yaw = -80f)) shouldBe false
            guard.allows(from, from.copy(x = 180.0)) shouldBe false
            verify { space.clear(world, any<DisplayOrientedVolume>(), null) }
            world.entities shouldBe emptyList()
        }
    }
})
