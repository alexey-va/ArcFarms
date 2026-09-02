package ru.ruscrafting.farms.paper

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.plugin.Plugin
import ru.arc.config.Config
import ru.arc.core.BukkitTaskScheduler
import ru.arc.menu.MenuContract
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuId
import ru.arc.menu.MenuRegionId
import ru.arc.menu.MenuTemplateId
import ru.arc.paper.menu.PaperMenuConfiguration
import ru.arc.paper.menu.PaperMenuConfigurationParser
import ru.arc.paper.menu.PaperMenuContent
import ru.arc.paper.menu.PaperMenuItemFactory
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.paper.menu.PaperMenuRuntime
import ru.arc.paper.menu.PaperMenuSession
import ru.arc.paper.menu.PaperMenuTextContract
import java.nio.file.Path
import java.util.UUID

/** One validated layout generation and one Inventory Framework listener for every ArcFarms screen. */
class ArcFarmsMenuPlatform(
    private val plugin: Plugin,
) : AutoCloseable {
    private val items = PaperMenuItemFactory()
    private val runtime = PaperMenuRuntime(plugin, BukkitTaskScheduler(plugin), loadConfiguration(plugin.dataFolder.toPath()))
    private val reopen = mutableMapOf<UUID, () -> Unit>()

    fun current(): PaperMenuConfiguration = runtime.current()

    fun prepareReload(): PaperMenuConfiguration = loadConfiguration(plugin.dataFolder.toPath())

    fun item(menu: MenuId, element: MenuElementId, name: Component, lore: List<Component>) =
        items.create(current().template(menu, element), text(name, lore))

    fun item(template: String, name: Component, lore: List<Component>) =
        items.create(current().template(MenuTemplateId.of(template)), text(name, lore))

    fun background(menu: MenuId) = current().catalog.require(menu).backgroundTemplate?.let { template ->
        items.create(current().template(template), text(Component.empty(), emptyList()))
    }

    fun open(
        player: Player,
        menu: MenuId,
        reopenView: (() -> Unit)? = null,
        content: () -> PaperMenuContent,
    ): PaperMenuSession {
        reopen[player.uniqueId] = reopenView ?: { if (player.isOnline) open(player, menu, content = content) }
        return runtime.open(player, menu, content)
    }

    fun session(player: Player): PaperMenuSession? = runtime.session(player)

    fun owns(event: InventoryClickEvent, menus: Set<MenuId>): Boolean {
        val player = event.whoClicked as? Player ?: return false
        val session = runtime.session(player) ?: return false
        return event.view.topInventory === session.inventory && session.menuId in menus
    }

    fun owns(event: InventoryDragEvent, menus: Set<MenuId>): Boolean {
        val player = event.whoClicked as? Player ?: return false
        val session = runtime.session(player) ?: return false
        return event.view.topInventory === session.inventory && session.menuId in menus
    }

    fun replace(candidate: PaperMenuConfiguration) {
        val active = Bukkit.getOnlinePlayers().mapNotNull { player ->
            if (runtime.session(player) == null) null else reopen[player.uniqueId]
        }
        runtime.replace(candidate)
        active.forEach { it() }
    }

    override fun close() {
        reopen.clear()
        runtime.close()
    }

    private fun text(name: Component, lore: List<Component>) = PaperMenuItemRenderContext(
        values = mapOf("name" to name),
        repeats = mapOf("lore" to lore.map { line -> mapOf("line" to line) }),
    )

    companion object {
        val MAIN = MenuId.of("main")
        val MARKET = MenuId.of("market")
        val ENTERPRISE_OVERVIEW = MenuId.of("enterprise-overview")
        val ENTERPRISE_FARM = MenuId.of("enterprise-farm")
        val ENTERPRISE_SHARES = MenuId.of("enterprise-shares")
        val ENTERPRISE_CONFIRM = MenuId.of("enterprise-confirm")
        val FARM_PERKS = MenuId.of("farm-perks")

        val BUY_OPTIONS = MenuRegionId.of("buy-options")
        val PERK_OFFERS = MenuRegionId.of("offers")

        private fun ids(vararg values: String) = values.mapTo(linkedSetOf(), MenuElementId::of)

        internal val CONTRACTS = linkedMapOf(
            MAIN to MenuContract(requiredElements = ids("farm", "lumber", "mine", "workday", "companies", "stats")),
            MARKET to MenuContract(requiredElements = ids("order", "accept", "decline")),
            ENTERPRISE_OVERVIEW to MenuContract(requiredElements = ids("farm", "lumber", "mine", "back")),
            ENTERPRISE_FARM to MenuContract(
                requiredElements = ids("header", "report", "workers", "policy", "license", "shares", "market", "back"),
            ),
            ENTERPRISE_SHARES to MenuContract(
                requiredElements = ids("status", "holding", "account", "withdraw", "back"),
                requiredRegions = setOf(BUY_OPTIONS),
            ),
            ENTERPRISE_CONFIRM to MenuContract(requiredElements = ids("confirm", "back")),
            FARM_PERKS to MenuContract(requiredElements = ids("balance"), requiredRegions = setOf(PERK_OFFERS)),
        )

        private val TEXT_CONTRACT = PaperMenuTextContract(
            values = setOf("name"),
            repeats = mapOf("lore" to setOf("line")),
        )

        private val TEXT_CONTRACTS = setOf(
            "background",
            "back",
            "farm",
            "lumber",
            "mine",
            "workday",
            "companies",
            "stats",
            "market-order",
            "market-accept",
            "market-decline",
            "enterprise-overview-farm",
            "enterprise-overview-lumber",
            "enterprise-overview-mine",
            "enterprise-farm-header",
            "enterprise-report",
            "enterprise-workers",
            "enterprise-policy",
            "enterprise-license",
            "enterprise-shares",
            "enterprise-market",
            "enterprise-share-status",
            "enterprise-share-holding",
            "enterprise-share-account",
            "enterprise-share-buy",
            "enterprise-share-confirm",
            "enterprise-share-withdraw",
            "perk-balance",
            "perk-harvest-area",
            "perk-speed",
            "perk-sustenance",
            "perk-reward-boost",
        ).associateWith { TEXT_CONTRACT }

        fun loadConfiguration(dataRoot: Path): PaperMenuConfiguration = PaperMenuConfigurationParser.require(
            Config(dataRoot, "config.yml"),
            "ui.menus.layouts",
            "ui.menus.templates",
            CONTRACTS,
            requiredTemplates = setOf("perk-harvest-area", "perk-speed", "perk-sustenance", "perk-reward-boost"),
            textContracts = TEXT_CONTRACTS,
        )
    }
}
