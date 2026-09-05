package ru.ruscrafting.farms.paper

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import ru.arc.menu.MenuContract
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuId
import ru.arc.paper.menu.PaperMenuConfiguration
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind

/** Main activity menu. Layout and item sources live in config; code owns domain actions only. */
class ArcFarmsMenu(
    private val menus: ArcFarmsMenuPlatform,
    private val service: ArcFarmsService,
    private val locale: ArcFarmsLocale,
    private val settings: () -> ArcFarmsConfig,
) : AutoCloseable {
    init { menus.configureDialogs(locale) }
    private val participationMenu: WorksiteEnterpriseParticipationMenu = WorksiteEnterpriseParticipationMenu(service, locale, settings, menus) { player -> enterpriseMenu.openCompany(player) }
    private val enterpriseMenu: WorksiteEnterpriseMenu = WorksiteEnterpriseMenu(service, locale, settings, menus, ::open, participationMenu::open)

    fun open(player: Player) {
        menus.open(player, MENU, { open(player) }) { content(player, menus.current()) }
    }

    /** Enterprise screens still route through their holder until their dedicated controller is replaced. */
    fun onClick(event: InventoryClickEvent) {
        enterpriseMenu.onClick(event)
    }

    fun onDrag(event: InventoryDragEvent) {
        enterpriseMenu.onDrag(event)
    }

    fun prepareReload(): PaperMenuConfiguration = loadConfiguration()

    fun publishReload(candidate: PaperMenuConfiguration) = menus.replace(candidate)

    private fun content(player: Player, configuration: PaperMenuConfiguration): FarmMenuContent {
        val stats = service.playerStats(player.uniqueId)
        return FarmMenuContent(
            title = locale.render(MessageKey.MENU_TITLE, player),
            background = configuration.catalog.require(MENU).backgroundTemplate?.let { template ->
                menus.item(template.value, Component.empty(), emptyList())
            },
            elements = mapOf(
                FARM to activityEntry(player, configuration, FARM, ActivityKind.FARM, MessageKey.MENU_FARM_NAME, MessageKey.MENU_FARM_LORE),
                LUMBER to activityEntry(
                    player,
                    configuration,
                    LUMBER,
                    ActivityKind.LUMBER,
                    MessageKey.MENU_LUMBER_NAME,
                    MessageKey.MENU_LUMBER_LORE,
                ),
                MINE to activityEntry(player, configuration, MINE, ActivityKind.MINE, MessageKey.MENU_MINE_NAME, MessageKey.MENU_MINE_LORE),
                WORKDAY to entry(
                    configuration,
                    WORKDAY,
                    locale.render(MessageKey.MENU_WORKDAY_NAME, player),
                    workdayLore(player),
                    enabled = !menus.usesDialogs,
                ) { context ->
                    service.workday()?.recommended()?.let { navigateIfEnabled(context.player, it, context.session) }
                },
                COMPANIES to entry(
                    configuration,
                    COMPANIES,
                    locale.render(MessageKey.MENU_COMPANIES_NAME, player),
                    listOf(
                        locale.render(MessageKey.MENU_COMPANIES_LORE, player),
                        Component.empty(),
                        locale.render(MessageKey.MENU_COMPANIES_CLICK, player),
                    ),
                ) { context ->
                    menus.transition(context.player, context.session) {
                        enterpriseMenu.openCompany(context.player)
                    }
                },
                STATS to entry(
                    configuration,
                    STATS,
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
                    enabled = false,
                ),
            ),
        )
    }

    private fun activityEntry(
        player: Player,
        configuration: PaperMenuConfiguration,
        element: MenuElementId,
        kind: ActivityKind,
        name: MessageKey,
        lore: MessageKey,
    ): FarmMenuEntry {
        val lines = mutableListOf(locale.render(lore, player))
        when {
            !service.canNavigate(kind) -> lines += listOf(Component.empty(), locale.render(MessageKey.MENU_UNAVAILABLE, player))
            !service.canAccess(player, kind) -> lines += listOf(Component.empty(), locale.render(MessageKey.MENU_LOCKED, player))
            else -> {
                if (!service.isAvailable(kind)) lines += listOf(Component.empty(), locale.render(MessageKey.MENU_REMOTE, player))
                lines += listOf(Component.empty(), locale.render(MessageKey.MENU_CLICK, player))
            }
        }
        return entry(configuration, element, locale.render(name, player), lines, enabled = service.canNavigate(kind) && service.canAccess(player, kind)) { context ->
            navigateIfEnabled(context.player, kind, context.session)
        }
    }

    private fun workdayLore(player: Player): List<Component> {
        val state = service.workday() ?: return listOf(locale.render(MessageKey.MENU_WORKDAY_LOADING, player))
        val recommended = state.recommended()
        return buildList {
            add(
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
            add(Component.empty())
            if (service.canNavigate(recommended) && service.canAccess(player, recommended)) {
                add(
                    locale.render(
                        MessageKey.MENU_WORKDAY_CLICK,
                        player,
                        mapOf("activity" to activityName(player, recommended)),
                    ),
                )
            } else {
                add(locale.render(if (service.canNavigate(recommended)) MessageKey.MENU_LOCKED else MessageKey.MENU_UNAVAILABLE, player))
            }
        }
    }

    private fun entry(
        configuration: PaperMenuConfiguration,
        element: MenuElementId,
        name: Component,
        lore: List<Component>,
        enabled: Boolean = true,
        click: (FarmMenuClickContext) -> Unit = {},
    ): FarmMenuEntry = FarmMenuEntry(
        item = menus.item(MENU, element, name, lore),
        enabled = enabled,
        acceptedClicks = setOf(ClickType.LEFT),
        onClick = click,
    )

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
        menus.close(player)
        service.travel(player, kind)
    }

    private fun navigateIfEnabled(player: Player, kind: ActivityKind, expectedTop: FarmMenuSession) {
        if (service.canNavigate(kind) && service.canAccess(player, kind)) {
            menus.transition(player, expectedTop) { navigate(player, kind) }
        }
    }

    private fun loadConfiguration(): PaperMenuConfiguration = menus.prepareReload()

    override fun close() {
        menus.close()
    }

    private companion object {
        val MENU = ArcFarmsMenuPlatform.MAIN
        val FARM = MenuElementId.of("farm")
        val LUMBER = MenuElementId.of("lumber")
        val MINE = MenuElementId.of("mine")
        val WORKDAY = MenuElementId.of("workday")
        val COMPANIES = MenuElementId.of("companies")
        val STATS = MenuElementId.of("stats")
        val CONTRACT = MenuContract(requiredElements = setOf(FARM, LUMBER, MINE, WORKDAY, COMPANIES, STATS))
    }
}
