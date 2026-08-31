package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.command.Command
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.domain.FarmPointKind

class ArcFarmsCommandAdminCoverageTest : FunSpec({
    test("firewood point and frost event are executable and suggested to administrators") {
        val player = mockk<Player>(relaxed = true) {
            every { hasPermission("arcfarms.admin") } returns true
        }
        val service = mockk<ArcFarmsService>(relaxed = true) {
            every { adminSetFarmPoint(any(), any(), any()) } returns true
            every { adminSetFarmStage(any(), any(), any()) } returns true
        }
        val command = mockk<Command>(relaxed = true)
        val handler = ArcFarmsCommand(
            service,
            mockk<ArcFarmsLocale>(relaxed = true),
            mockk<ArcFarmsMenu>(relaxed = true),
        ) { Result.success(Unit) }

        handler.onCommand(
            player,
            command,
            "arcfarms",
            arrayOf("admin", "point", "communal_farm", "firewood"),
        )
        handler.onCommand(
            player,
            command,
            "arcfarms",
            arrayOf("admin", "event", "communal_farm", "frost"),
        )

        verify(exactly = 1) { service.adminSetFarmPoint(player, "communal_farm", FarmPointKind.FIREWOOD) }
        verify(exactly = 1) { service.adminSetFarmStage(player, "communal_farm", "frost") }
        handler.onTabComplete(
            player,
            command,
            "arcfarms",
            arrayOf("admin", "point", "communal_farm", "fire"),
        ) shouldContain "firewood"
        handler.onTabComplete(
            player,
            command,
            "arcfarms",
            arrayOf("admin", "event", "communal_farm", "fro"),
        ) shouldContain "frost"
        handler.onTabComplete(
            player,
            command,
            "arcfarms",
            arrayOf("admin", "point", "communal_farm", "food-delivery"),
        ) shouldContain "food-delivery-portal"
    }
})
