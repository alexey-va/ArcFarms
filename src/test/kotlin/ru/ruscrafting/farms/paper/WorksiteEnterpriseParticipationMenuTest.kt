package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.enterprise.*
import java.nio.file.Files

class WorksiteEnterpriseParticipationMenuTest : FunSpec({
    test("candidate opens confirmation and only confirmed LEFT submits the selected plan") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val plugin = paper.createSimplePlugin("ParticipationMenuTest")
            copyResources(plugin)
            val config = ArcFarmsConfig.inspect(plugin.dataFolder.toPath())
            val locale = ArcFarmsLocale(plugin.dataFolder.toPath()) { config }
            val menus = ArcFarmsMenuPlatform(plugin)
            val service = mockk<ArcFarmsService>(relaxed = true)
            val player = paper.addPlayer("Participant")
            val view = participationView()
            var personal = personalView(canVote = true)
            every { service.enterpriseParticipation(player.uniqueId) } returns view
            every { service.enterprisePlayerView(player.uniqueId) } answers { personal }
            every { service.deferInventoryTransition(any(), any(), any()) } answers {
                arg<() -> Unit>(2).invoke(); true
            }
            val menu = WorksiteEnterpriseParticipationMenu(service, locale, { config }, menus) {}
            menu.open(player)

            click(paper, player, 10, ClickType.LEFT)
            menus.session(player)?.menuId shouldBe ArcFarmsMenuPlatform.ENTERPRISE_CONFIRM
            click(paper, player, 13, ClickType.RIGHT)
            verify(exactly = 0) { service.voteEnterprise(any(), any(), any(), any()) }
            click(paper, player, 13, ClickType.LEFT)
            verify(exactly = 1) {
                service.voteEnterprise(player.uniqueId, WorksiteEnterprisePlan.STEADY, 107L, any())
            }

            personal = personalView(canVote = false)
            menu.open(player)
            click(paper, player, 10, ClickType.LEFT)
            click(paper, player, 13, ClickType.LEFT)
            verify(exactly = 1) { service.voteEnterprise(any(), any(), any(), any()) }
        } finally {
            paper.close()
        }
    }
})

private fun click(paper: MockBukkitTestRuntime, player: org.bukkit.entity.Player, slot: Int, click: ClickType) {
    val event = InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER, slot, click, InventoryAction.PICKUP_ALL)
    paper.server.pluginManager.callEvent(event)
    event.isCancelled shouldBe true
}

private fun participationView() = WorksiteEnterpriseParticipationView(
    ActivityKind.FARM, "communal_farm", 100L, WorksiteEnterprisePlan.TEAM,
    WorksiteEnterprisePlanTotals(WorksiteEnterprisePlan.entries.associateWith { 0 }, WorksiteEnterprisePlan.entries.associateWith { 0 }),
    WorksiteEnterpriseProjectProgress(0, 0, 10),
)

private fun personalView(canVote: Boolean) = WorksiteEnterprisePlayerView(
    0, 0, 0, 100, 0, 0, 0, 12, 0, 0, 10, "team", canVote, false,
)

private fun copyResources(plugin: org.bukkit.plugin.Plugin) {
    listOf("config.yml", "lang/ru.yml", "lang/en.yml").forEach { name ->
        val source = requireNotNull(WorksiteEnterpriseParticipationMenuTest::class.java.classLoader.getResourceAsStream(name))
        val target = plugin.dataFolder.toPath().resolve(name)
        Files.createDirectories(target.parent)
        source.use { Files.copy(it, target) }
    }
}
