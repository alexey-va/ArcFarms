package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import net.kyori.adventure.text.Component
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.paper.worksite.WorksiteAdminHandler
import ru.ruscrafting.farms.paper.worksite.WorksiteAdminReindexTick
import ru.ruscrafting.farms.paper.worksite.WorksiteAdminRegistry
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentPlacementReport
import ru.ruscrafting.farms.domain.MineIncidentType

class ArcFarmsCommandAdminCoverageTest : FunSpec({
    test("bypass permission exposes edit and its help without granting other admin commands") {
        val player = mockk<Player>(relaxed = true) {
            every { hasPermission("arcfarms.bypass") } returns true
        }
        val service = mockk<ArcFarmsService>(relaxed = true)
        val locale = mockk<ArcFarmsLocale>(relaxed = true)
        val command = mockk<Command>(relaxed = true)
        var reloads = 0
        val handler = ArcFarmsCommand(service, locale, mockk(relaxed = true)) {
            reloads++
            java.util.concurrent.CompletableFuture.completedFuture(Unit)
        }

        handler.onCommand(player, command, "arcfarms", arrayOf("admin", "edit"))
        handler.onCommand(player, command, "arcfarms", arrayOf("admin", "edit", "help"))
        verify(exactly = 1) { service.toggleAdminEdit(player) }
        verify(exactly = 1) { locale.render(MessageKey.ADMIN_HELP_EDIT, player, any()) }
        handler.onTabComplete(player, command, "arcfarms", arrayOf("")) shouldBe
            listOf("status", "top", "travel", "admin")
        handler.onTabComplete(player, command, "arcfarms", arrayOf("admin", "")) shouldBe listOf("edit")
        handler.onTabComplete(player, command, "arcfarms", arrayOf("admin", "edit", "")) shouldBe listOf("help")
        handler.onTabComplete(player, command, "arcfarms", arrayOf("admin", "point", "")) shouldBe emptyList()
        handler.onTabComplete(player, command, "arcfarms", arrayOf("debug", "")) shouldBe emptyList()

        clearMocks(service, locale, answers = false)
        val denied = listOf(
            arrayOf("admin", "help"), arrayOf("admin", "inspect"),
            arrayOf("admin", "point", "communal_farm", "tool"),
            arrayOf("admin", "next", "communal_farm"), arrayOf("admin", "finish", "communal_farm"),
            arrayOf("admin", "stage", "communal_farm", "complete"),
            arrayOf("admin", "backup", "communal_farm", "restore", "latest"),
            arrayOf("admin", "worksite", "mine", "old_shafts", "reindex", "start"),
            arrayOf("admin", "expeditions", "rebuild", "all"),
            arrayOf("reload"), arrayOf("debug", "communal_farm", "reset"),
        )
        denied.forEach { handler.onCommand(player, command, "arcfarms", it) }
        verify(exactly = denied.size) { locale.render(MessageKey.NO_PERMISSION, player, any()) }
        verify { service wasNot Called }
        reloads shouldBe 0
    }

    test("ordinary players cannot toggle or discover edit while administrators retain it") {
        val player = mockk<Player>(relaxed = true)
        val service = mockk<ArcFarmsService>(relaxed = true)
        val locale = mockk<ArcFarmsLocale>(relaxed = true)
        val command = mockk<Command>(relaxed = true)
        val handler = ArcFarmsCommand(service, locale, mockk(relaxed = true)) {
            java.util.concurrent.CompletableFuture.completedFuture(Unit)
        }

        handler.onCommand(player, command, "arcfarms", arrayOf("admin", "edit"))
        verify(exactly = 0) { service.toggleAdminEdit(any()) }
        verify(exactly = 1) { locale.render(MessageKey.NO_PERMISSION, player, any()) }
        handler.onTabComplete(player, command, "arcfarms", arrayOf("admin", "")) shouldBe emptyList()

        every { player.hasPermission("arcfarms.admin") } returns true
        handler.onCommand(player, command, "arcfarms", arrayOf("admin", "edit"))
        verify(exactly = 1) { service.toggleAdminEdit(player) }
        handler.onTabComplete(player, command, "arcfarms", arrayOf("admin", "")) shouldContain "inspect"
    }

    test("firewood point and frost event are executable and suggested to administrators") {
        val player = mockk<Player>(relaxed = true) {
            every { hasPermission("arcfarms.admin") } returns true
        }
        val service = mockk<ArcFarmsService>(relaxed = true) {
            every { adminSetFarmPoint(any(), any(), any()) } returns true
            every { adminSetFarmStageAsync(any(), any(), any()) } returns java.util.concurrent.CompletableFuture.completedFuture(true)
            every { mineZoneIds() } returns listOf("old_shafts")
            every { mineIncidentIds() } returns listOf("GAS_LEAK")
            every { adminSetMineIncidentAsync(any(), any(), any()) } returns java.util.concurrent.CompletableFuture.completedFuture(true)
        }
        val command = mockk<Command>(relaxed = true)
        val handler = ArcFarmsCommand(
            service,
            mockk<ArcFarmsLocale>(relaxed = true),
            mockk<ArcFarmsMenu>(relaxed = true),
        ) { java.util.concurrent.CompletableFuture.completedFuture(Unit) }

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
        handler.onCommand(
            player,
            command,
            "arcfarms",
            arrayOf("admin", "event", "OLD_SHAFTS", "gas_leak"),
        )

        verify(exactly = 1) { service.adminSetFarmPoint(player, "communal_farm", FarmPointKind.FIREWOOD) }
        verify(exactly = 1) { service.adminSetFarmStageAsync(player, "communal_farm", "frost") }
        verify(exactly = 1) { service.adminSetMineIncidentAsync(player, "old_shafts", "GAS_LEAK") }
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
        val handler = ArcFarmsCommand(service, locale, mockk<ArcFarmsMenu>(relaxed = true)) { java.util.concurrent.CompletableFuture.completedFuture(Unit) }

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

    test("rejected mine event reports exact automatic placement requirement without a point command") {
        ru.arc.paper.testing.MockBukkitTestRuntime.open().use { paper ->
        val player = paper.server.addPlayer().apply { isOp = true }
        val report = MineIncidentPlacementReport(
            MineIncidentType.CAVE_IN, required = 1, usable = 0, considered = 17,
            rejected = mapOf("missing_stone_or_ore_ceiling" to 12, "footprint_occupied" to 5),
        )
        val service = mockk<ArcFarmsService>(relaxed = true) {
            every { mineZoneIds() } returns listOf("old_shafts")
            every { mineIncidentIds() } returns listOf("CAVE_IN")
            every { adminSetMineIncidentAsync(any(), any(), any()) } returns java.util.concurrent.CompletableFuture.completedFuture(false)
            every { mineIncidentDiagnostics("old_shafts", MineIncidentType.CAVE_IN) } returns report
        }
        val locale = mockk<ArcFarmsLocale>(relaxed = true) {
            every { text(any()) } returns Component.empty()
            every { renderPath(any(), any(), any()) } returns Component.empty()
        }
        val handler = ArcFarmsCommand(service, locale, mockk<ArcFarmsMenu>(relaxed = true)) { java.util.concurrent.CompletableFuture.completedFuture(Unit) }

        handler.onCommand(player, mockk(relaxed = true), "arcfarms", arrayOf("admin", "event", "old_shafts", "CAVE_IN"))

        verify(exactly = 1) {
            locale.renderPath("admin.mine-placement.reason.missing_stone_or_ore_ceiling", player, any())
        }
        verify(exactly = 1) { locale.renderPath("admin.mine-placement.reason.footprint_occupied", player, any()) }
        verify(exactly = 1) { service.mineIncidentDiagnostics("old_shafts", MineIncidentType.CAVE_IN) }
        verify(exactly = 0) { service.mineIncidentDiagnostics("old_shafts") }
        verify(exactly = 0) { locale.renderPath("admin.mine-point.help", any(), any()) }
    }
    }

    test("an unknown mine placement reason uses localized fallback instead of a raw locale key") {
        ru.arc.paper.testing.MockBukkitTestRuntime.open().use { paper ->
            val player = paper.server.addPlayer().apply { isOp = true }
            val service = mockk<ArcFarmsService>(relaxed = true) {
                every { mineZoneIds() } returns listOf("old_shafts")
                every { mineIncidentIds() } returns listOf("CRYSTAL_RESONANCE")
                every { adminSetMineIncidentAsync(any(), any(), any()) } returns java.util.concurrent.CompletableFuture.completedFuture(false)
                every { mineIncidentDiagnostics("old_shafts", MineIncidentType.CRYSTAL_RESONANCE) } returns
                    MineIncidentPlacementReport(MineIncidentType.CRYSTAL_RESONANCE, 4, 4, 39, mapOf("unknown_guard" to 1))
            }
            val locale = mockk<ArcFarmsLocale>(relaxed = true) {
                every { text(any()) } returns Component.empty()
                every { renderPath(any(), any(), any()) } returns Component.empty()
                every { renderPath("admin.mine-placement.reason.unknown_guard", any(), any()) } returns
                    Component.text("admin.mine-placement.reason.unknown_guard")
            }
            val handler = ArcFarmsCommand(service, locale, mockk(relaxed = true)) { java.util.concurrent.CompletableFuture.completedFuture(Unit) }
            handler.onCommand(player, mockk(relaxed = true), "arcfarms", arrayOf("admin", "event", "old_shafts", "CRYSTAL_RESONANCE"))
            verify(exactly = 1) { locale.renderPath("admin.mine-placement.reason.unsuitable", player, any()) }
        }
    }

    test("factory preset validates the configured zone and queues only the next dead factory") {
        val player = mockk<Player>(relaxed = true) {
            every { hasPermission("arcfarms.admin") } returns true
        }
        val service = mockk<ArcFarmsService>(relaxed = true) {
            every { mineZoneIds() } returns listOf("compact_mine")
            every { factoryExperiments("compact_mine", "rock") } returns true
        }
        val locale = mockk<ArcFarmsLocale>(relaxed = true) {
            every { renderPath(any(), any(), any()) } returns Component.empty()
        }
        val handler = ArcFarmsCommand(service, locale, mockk<ArcFarmsMenu>(relaxed = true)) { java.util.concurrent.CompletableFuture.completedFuture(Unit) }

        handler.onCommand(
            player, mockk(relaxed = true), "arcfarms",
            arrayOf("admin", "expeditions", "factory", "COMPACT_MINE", "rock"),
        )

        verify(exactly = 1) { service.factoryExperiments("compact_mine", "rock") }
        verify(exactly = 1) {
            locale.renderPath("admin.expeditions.factory.queued", player, any())
        }

        handler.onCommand(
            player, mockk(relaxed = true), "arcfarms",
            arrayOf("admin", "expeditions", "factory", "unknown", "random"),
        )

        verify(exactly = 1) {
            locale.renderPath("admin.expeditions.factory.unknown-zone", player, any())
        }
        verify(exactly = 0) { service.factoryExperiments("unknown", "random") }
    }
})
