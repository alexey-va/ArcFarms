package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.paper.worksite.WorksiteAdminHandler
import ru.ruscrafting.farms.paper.worksite.WorksiteAdminReindexTick
import ru.ruscrafting.farms.paper.worksite.WorksiteAdminRegistry

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

    test("console can inspect and reindex worksites while actor-bound operations stay player-only") {
        val console = mockk<CommandSender>(relaxed = true) {
            every { hasPermission("arcfarms.admin") } returns true
        }
        val mine = mockk<WorksiteAdminHandler>(relaxed = true) {
            every { kind } returns ActivityKind.MINE
            every { startReindex("old_shafts") } returns true
            every { tickReindex("old_shafts", 262_144) } returns WorksiteAdminReindexTick(true, 12, 4)
            every { cancelReindex("old_shafts") } returns true
        }
        val service = mockk<ArcFarmsService>(relaxed = true) {
            every { worksiteAdmins } returns WorksiteAdminRegistry(listOf(mine))
        }
        val locale = mockk<ArcFarmsLocale>(relaxed = true)
        val command = mockk<Command>(relaxed = true)
        val handler = ArcFarmsCommand(service, locale, mockk<ArcFarmsMenu>(relaxed = true)) { Result.success(Unit) }

        handler.onCommand(console, command, "arcfarms", arrayOf("admin", "worksite", "mine", "old_shafts", "status"))
        handler.onCommand(console, command, "arcfarms", arrayOf("admin", "worksite", "mine", "old_shafts", "reindex", "start"))
        handler.onCommand(console, command, "arcfarms", arrayOf("admin", "worksite", "mine", "old_shafts", "reindex", "tick"))
        handler.onCommand(console, command, "arcfarms", arrayOf("admin", "worksite", "mine", "old_shafts", "reindex", "cancel"))
        handler.onCommand(console, command, "arcfarms", arrayOf("admin", "worksite", "mine", "old_shafts", "start"))
        handler.onCommand(console, command, "arcfarms", arrayOf("admin", "worksite", "mine", "old_shafts", "incident", "CAVE_IN"))

        verify(exactly = 1) { mine.status("old_shafts") }
        verify(exactly = 1) { mine.startReindex("old_shafts") }
        verify(exactly = 1) { mine.tickReindex("old_shafts", 262_144) }
        verify(exactly = 1) { mine.cancelReindex("old_shafts") }
        verify(exactly = 0) { mine.start(any(), any()) }
        verify(exactly = 0) { mine.forceIncident(any(), any(), any()) }
    }
})
