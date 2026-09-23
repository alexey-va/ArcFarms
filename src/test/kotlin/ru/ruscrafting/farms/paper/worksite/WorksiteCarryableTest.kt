package ru.ruscrafting.farms.paper.worksite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import ru.arc.paper.testing.MockBukkitTestRuntime

class WorksiteCarryableTest : FunSpec({
    test("carried load stays at body offset when the player looks straight up or down") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val world = paper.server.addSimpleWorld("carry_position")
            val player = paper.server.addPlayer()
            for (yaw in listOf(0f, 90f, 180f, 270f)) {
                player.teleport(Location(world, 10.0, 64.0, 10.0, yaw, 0f))
                val front = WorksiteCarryable.carriedLocation(player, .75, .85)
                val back = WorksiteCarryable.carriedLocation(player, .75, .85, position = WorksiteCarryPosition.BACK)
                for (pitch in listOf(-90f, -89f, 45f, 89f, 90f)) {
                    player.teleport(Location(world, 10.0, 64.0, 10.0, yaw, pitch))
                    WorksiteCarryable.carriedLocation(player, .75, .85) shouldBe front
                    WorksiteCarryable.carriedLocation(player, .75, .85, position = WorksiteCarryPosition.BACK) shouldBe back
                }
            }
        } finally { paper.close() }
    }
})
