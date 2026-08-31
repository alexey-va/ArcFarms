package ru.ruscrafting.farms.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.MenuItemVisualSettings
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind

class ArcFarmsMenu(
    private val service: ArcFarmsService,
    private val locale: ArcFarmsLocale,
    private val settings: () -> ArcFarmsConfig,
) {
    private val enterpriseMenu = WorksiteEnterpriseMenu(service, locale, settings, ::open)

    private inner class Holder : ArcFarmsReloadableInventory {
        lateinit var backing: Inventory
        override fun getInventory(): Inventory = backing
        override fun refresh(player: Player) = open(player)
    }

    fun open(player: Player) {
        val current = settings()
        val holder = Holder()
        val inventory = player.server.createInventory(holder, 27, locale.render(MessageKey.MENU_TITLE, player))
        holder.backing = inventory
        inventory.setItem(2, activityItem(player, ActivityKind.FARM, current.mainMenuItems.farm, MessageKey.MENU_FARM_NAME, MessageKey.MENU_FARM_LORE))
        inventory.setItem(4, activityItem(player, ActivityKind.LUMBER, current.mainMenuItems.lumber, MessageKey.MENU_LUMBER_NAME, MessageKey.MENU_LUMBER_LORE))
        inventory.setItem(6, activityItem(player, ActivityKind.MINE, current.mainMenuItems.mine, MessageKey.MENU_MINE_NAME, MessageKey.MENU_MINE_LORE))
        inventory.setItem(19, workdayItem(player, current.mainMenuItems.workday))
        inventory.setItem(
            22,
            item(
                current.enterpriseMenuItems.companies,
                locale.render(MessageKey.MENU_COMPANIES_NAME, player),
                listOf(
                    locale.render(MessageKey.MENU_COMPANIES_LORE, player),
                    Component.empty(),
                    locale.render(MessageKey.MENU_COMPANIES_CLICK, player),
                ),
            ),
        )
        val stats = service.playerStats(player.uniqueId)
        inventory.setItem(
            25,
            item(
                current.mainMenuItems.stats,
                locale.render(MessageKey.MENU_STATS_NAME, player),
                listOf(
                    locale.render(
                        MessageKey.MENU_STATS_LORE,
                        player,
                        mapOf(
                            "farm" to locale.text(stats.contributions[ActivityKind.FARM] ?: 0),
                            "lumber" to locale.text(stats.contributions[ActivityKind.LUMBER] ?: 0),
                            "mine" to locale.text(stats.contributions[ActivityKind.MINE] ?: 0),
                        ),
                    ),
                ),
            ),
        )
        backgroundItem(current)?.let { background ->
            repeat(inventory.size) { slot ->
                if (inventory.getItem(slot) == null) inventory.setItem(slot, background)
            }
        }
        player.openInventory(inventory)
    }

    fun onClick(event: InventoryClickEvent) {
        if (enterpriseMenu.onClick(event)) return
        if (event.view.topInventory.holder !is Holder) return
        event.isCancelled = true
        if (event.clickedInventory !== event.view.topInventory) return
        if (event.click != ClickType.LEFT) return
        val player = event.whoClicked as? Player ?: return
        val expectedTop = event.view.topInventory
        when (event.rawSlot) {
            2 -> navigateIfEnabled(player, ActivityKind.FARM, expectedTop)
            4 -> navigateIfEnabled(player, ActivityKind.LUMBER, expectedTop)
            6 -> navigateIfEnabled(player, ActivityKind.MINE, expectedTop)
            19 -> service.workday()?.recommended()?.let { navigateIfEnabled(player, it, expectedTop) }
            22 -> service.deferInventoryTransition(player, expectedTop) { enterpriseMenu.openOverview(player) }
        }
    }

    fun onDrag(event: InventoryDragEvent) {
        if (enterpriseMenu.onDrag(event)) return
        if (event.view.topInventory.holder is Holder) event.isCancelled = true
    }

    private fun activityItem(
        player: Player,
        kind: ActivityKind,
        visual: MenuItemVisualSettings,
        name: MessageKey,
        lore: MessageKey,
    ): ItemStack {
        val lines = mutableListOf(locale.render(lore, player))
        when {
            !service.canNavigate(kind) -> {
                lines += Component.empty()
                lines += locale.render(MessageKey.MENU_UNAVAILABLE, player)
            }
            !service.canAccess(player, kind) -> {
                lines += Component.empty()
                lines += locale.render(MessageKey.MENU_LOCKED, player)
            }
            else -> {
                if (!service.isAvailable(kind)) {
                    lines += Component.empty()
                    lines += locale.render(MessageKey.MENU_REMOTE, player)
                }
                lines += Component.empty()
                lines += locale.render(MessageKey.MENU_CLICK, player)
            }
        }
        return item(visual, locale.render(name, player), lines)
    }

    private fun workdayItem(player: Player, visual: MenuItemVisualSettings): ItemStack {
        val state = service.workday()
            ?: return item(
                visual,
                locale.render(MessageKey.MENU_WORKDAY_NAME, player),
                listOf(locale.render(MessageKey.MENU_WORKDAY_LOADING, player)),
            )
        val recommended = state.recommended()
        val lore = mutableListOf(
            locale.render(
                MessageKey.MENU_WORKDAY_LORE,
                player,
                mapOf(
                    "cycle" to locale.text(state.cycle),
                    "farm" to seal(player, ActivityKind.FARM, ActivityKind.FARM in state.completed),
                    "lumber" to seal(player, ActivityKind.LUMBER, ActivityKind.LUMBER in state.completed),
                    "mine" to seal(player, ActivityKind.MINE, ActivityKind.MINE in state.completed),
                ),
            ),
        )
        if (service.canNavigate(recommended) && service.canAccess(player, recommended)) {
            lore += Component.empty()
            lore += locale.render(
                MessageKey.MENU_WORKDAY_CLICK,
                player,
                mapOf("activity" to activityName(player, recommended)),
            )
        } else {
            lore += Component.empty()
            lore += locale.render(
                if (service.canNavigate(recommended)) MessageKey.MENU_LOCKED else MessageKey.MENU_UNAVAILABLE,
                player,
            )
        }
        return item(visual, locale.render(MessageKey.MENU_WORKDAY_NAME, player), lore)
    }

    private fun seal(player: Player, kind: ActivityKind, completed: Boolean): Component = locale.render(
        if (completed) MessageKey.NETWORK_SEAL_DONE else MessageKey.NETWORK_SEAL_PENDING,
        player,
        mapOf("activity" to activityName(player, kind)),
    )

    private fun activityName(player: Player, kind: ActivityKind): Component = locale.render(
        when (kind) {
            ActivityKind.FARM -> MessageKey.MENU_FARM_NAME
            ActivityKind.LUMBER -> MessageKey.MENU_LUMBER_NAME
            ActivityKind.MINE -> MessageKey.MENU_MINE_NAME
        },
        player,
    )

    private fun navigate(player: Player, kind: ActivityKind) {
        if (!service.canNavigate(kind)) {
            player.sendMessage(locale.render(MessageKey.ZONE_UNAVAILABLE, player))
            return
        }
        if (!service.canAccess(player, kind)) {
            player.sendMessage(locale.render(MessageKey.ZONE_LOCKED, player))
            return
        }
        player.closeInventory()
        service.travel(player, kind)
    }

    private fun navigateIfEnabled(player: Player, kind: ActivityKind, expectedTop: Inventory) {
        if (service.canNavigate(kind) && service.canAccess(player, kind)) {
            service.deferInventoryTransition(player, expectedTop) { navigate(player, kind) }
        }
    }

    @Suppress("DEPRECATION")
    private fun backgroundItem(current: ArcFarmsConfig): ItemStack? {
        val background = current.menuBackground
        if (!background.enabled) return null
        return ItemStack(MaterialRules.material(background.material)).apply {
            editMeta { meta ->
                if (background.customModelData > 0) meta.setCustomModelData(background.customModelData)
                meta.setHideTooltip(true)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun item(visual: MenuItemVisualSettings, name: Component, lore: List<Component>): ItemStack =
        ItemStack(MaterialRules.material(visual.material)).apply {
            editMeta { meta ->
                if (visual.customModelData > 0) meta.setCustomModelData(visual.customModelData)
                meta.displayName(name.decoration(TextDecoration.ITALIC, false))
                meta.lore(lore.map { it.decoration(TextDecoration.ITALIC, false) })
            }
        }
}
