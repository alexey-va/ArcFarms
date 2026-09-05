package ru.ruscrafting.farms.paper

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import ru.arc.menu.MenuElementId
import ru.arc.paper.menu.PaperMenuContent
import ru.arc.paper.menu.PaperMenuEntry
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterprisePlan
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** One weekly decision, advisory input and project progress in the existing menu runtime. */
internal class WorksiteEnterpriseParticipationMenu(
    private val service: ArcFarmsService,
    private val locale: ArcFarmsLocale,
    private val settings: () -> ArcFarmsConfig,
    private val menus: ArcFarmsMenuPlatform,
    private val back: (Player) -> Unit,
) {
    fun open(player: Player) {
        val view = service.enterpriseParticipation(player.uniqueId) ?: return back(player)
        val personal = service.enterprisePlayerView(player.uniqueId) ?: return back(player)
        val week = personal.currentWeekStartEpochDay + 7
        menus.open(player, MENU, { open(player) }) {
            val elements = linkedMapOf<MenuElementId, PaperMenuEntry>()
            val summary = listOf(
                text(player, "summary", mapOf("plan" to text(player, "${view.targetPlan.name.lowercase()}.name"))),
                text(player, "voting", mapOf("week" to locale.text(date(week)))),
                text(player, "quorum"), Component.empty(),
                text(player, "project-progress", mapOf("orders" to locale.text(personal.projectOrders),
                    "target" to locale.text(personal.projectTarget), "stage" to locale.text(personal.projectStage))),
                text(player, if (personal.projectStage == 3) "project-complete" else "project-purpose"),
            )
            elements[id("summary")] = entry(menus.item(MENU, id("summary"), text(player, "summary-name"), summary), enabled = false)
            WorksiteEnterprisePlan.entries.forEach { plan ->
                val key = plan.name.lowercase()
                val canSubmit = personal.canVote || personal.canAdvise
                val lore = mutableListOf(text(player, "$key.lore"), Component.empty(),
                    text(player, "candidate", mapOf("votes" to locale.text(view.ballots.shareholderWeights[plan] ?: 0),
                        "workers" to locale.text(view.ballots.advisoryCounts[plan] ?: 0))))
                if (plan == view.targetPlan) lore += text(player, "selected")
                if (canSubmit) {
                    lore += text(player, if (personal.canVote) "eligibility" else "advisory")
                    lore += Component.empty()
                    lore += text(player, if (personal.canVote) "vote-click" else "advise-click")
                } else lore += text(player, "unavailable")
                elements[id(key)] = entry(menus.item(MENU, id(key), text(player, "$key.name"), lore),
                    enabled = canSubmit, acceptedClicks = setOf(ClickType.LEFT)) { context ->
                    service.deferInventoryTransition(player, context.session.inventory) { confirm(player, plan, week) }
                }
            }
            elements[id("back")] = backEntry(player, MENU, back)
            PaperMenuContent(title = text(player, "title"), background = menus.background(MENU), elements = elements)
        }
    }

    private fun confirm(player: Player, plan: WorksiteEnterprisePlan, week: Long) {
        val personal = service.enterprisePlayerView(player.uniqueId) ?: return open(player)
        if (week != personal.currentWeekStartEpochDay + 7 || !(personal.canVote || personal.canAdvise)) return open(player)
        val menu = ArcFarmsMenuPlatform.ENTERPRISE_CONFIRM
        menus.open(player, menu, { confirm(player, plan, week) }) {
            PaperMenuContent(title = text(player, "confirm-title"), background = menus.background(menu), elements = mapOf(
                id("confirm") to entry(menus.item(menu, id("confirm"), text(player, "confirm-name", mapOf("plan" to text(player, "${plan.name.lowercase()}.name"))), listOf(
                    text(player, "confirm-lore", mapOf("plan" to text(player, "${plan.name.lowercase()}.name"), "week" to locale.text(date(week)))),
                    text(player, if (personal.canVote) "eligibility" else "advisory"),
                    Component.empty(), text(player, "confirm-click"),
                )), acceptedClicks = setOf(ClickType.LEFT)) { context ->
                    service.deferInventoryTransition(player, context.session.inventory) {
                        service.voteEnterprise(player.uniqueId, plan, week) { saved ->
                            if (player.isOnline) {
                                player.sendMessage(text(player, if (saved) "saved" else "failed"))
                                if (menus.session(player)?.menuId == MENU) open(player)
                            }
                        }
                        open(player)
                    }
                },
                id("back") to backEntry(player, menu, ::open),
            ))
        }
    }

    private fun backEntry(player: Player, menu: ru.arc.menu.MenuId, action: (Player) -> Unit) = entry(
        menus.item(menu, id("back"), locale.render(MessageKey.COMPANIES_BACK_NAME, player), listOf(
            locale.render(MessageKey.COMPANIES_BACK_LORE, player), Component.empty(), locale.render(MessageKey.COMPANIES_BACK_CLICK, player),
        )), acceptedClicks = setOf(ClickType.LEFT),
    ) { context -> service.deferInventoryTransition(player, context.session.inventory) { action(player) } }

    private fun entry(
        item: org.bukkit.inventory.ItemStack,
        enabled: Boolean = true,
        acceptedClicks: Set<ClickType> = setOf(ClickType.LEFT),
        click: (ru.arc.paper.menu.PaperMenuClickContext) -> Unit = {},
    ) = PaperMenuEntry(item, enabled, acceptedClicks, ru.arc.paper.menu.PaperMenuClickHandler(click))

    private fun text(player: Player, key: String, values: Map<String, Component> = emptyMap()) =
        locale.renderPath("companies.participation.$key", player, values)
    private fun date(week: Long) = LocalDate.ofEpochDay(week).format(DateTimeFormatter.ofPattern("dd.MM"))
    private fun id(value: String) = MenuElementId.of(value)
    private companion object { val MENU = ArcFarmsMenuPlatform.ENTERPRISE_PARTICIPATION }
}
