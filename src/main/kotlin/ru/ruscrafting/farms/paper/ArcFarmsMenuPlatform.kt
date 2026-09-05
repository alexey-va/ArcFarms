package ru.ruscrafting.farms.paper

import net.kyori.adventure.text.Component
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
import ru.arc.paper.menu.PaperMenuEntry
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperMenuItemFactory
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.paper.menu.PaperMenuRuntime
import ru.arc.paper.menu.PaperMenuTextContract
import java.nio.file.Path
import java.util.UUID
import ru.arc.core.LifecycleTaskScope
import org.bukkit.event.Listener
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryOpenEvent

/** Shared menu actions with native dialogs by default and an explicit inventory presentation. */
class ArcFarmsMenuPlatform(
    private val plugin: Plugin,
    dialogDisplay: FarmDialogDisplay? = null,
) : AutoCloseable, Listener {
    private val items = PaperMenuItemFactory()
    private val runtime = PaperMenuRuntime(plugin, BukkitTaskScheduler(plugin), loadConfiguration(plugin.dataFolder.toPath()))
    private val sessions = mutableMapOf<UUID, FarmMenuSession>()
    private val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    private var generation = 0L
    private val dialogs: FarmDialogDisplay by lazy {
        dialogDisplay ?: object : FarmDialogDisplay {
            private val delegate = PaperDialogRuntime(plugin)
            override fun show(player: Player, screen: ru.arc.paper.menu.PaperDialogScreen) = delegate.open(player, screen)
            override fun close(player: Player) = player.closeDialog()
            override fun close() = delegate.close()
        }
    }
    private var dialogsUsed = false
    private var dialogMode = readDialogMode()
    init { plugin.server.pluginManager.registerEvents(this, plugin) }
    private fun readDialogMode(): Boolean {
        val mode = Config(plugin.dataFolder.toPath(), "config.yml").stringOrNull("ui.menu-presentation") ?: "DIALOG"
        require(mode in setOf("DIALOG", "INVENTORY")) { "ui.menu-presentation must be DIALOG or INVENTORY" }
        return mode == "DIALOG"
    }
    internal var dialogText: (Player, String) -> Component = { _, _ -> Component.empty() }
    fun configureDialogs(locale: ru.ruscrafting.farms.config.ArcFarmsLocale) {
        dialogText = { player, key -> locale.renderPath("dialog.$key", player) }
    }

    fun current(): PaperMenuConfiguration = runtime.current()

    fun prepareReload(): PaperMenuConfiguration { readDialogMode(); return loadConfiguration(plugin.dataFolder.toPath()) }

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
        content: () -> FarmMenuContent,
    ): FarmMenuSession {
        close(player)
        val session = FarmMenuSession(player, menu, this, content,
            reopenView ?: { if (player.isOnline) open(player, menu, content = content) })
        sessions[player.uniqueId] = session
        if (dialogMode) {
            player.closeInventory()
            refresh(session)
        } else {
            session.delegate = runtime.open(player, menu) { inventoryContent(session, content()) }
        }
        return session
    }

    private fun inventoryContent(session: FarmMenuSession, content: FarmMenuContent): PaperMenuContent {
        fun entry(value: FarmMenuEntry) = PaperMenuEntry(value.item, value.enabled, value.acceptedClicks,
            { context -> value.onClick.handle(FarmMenuClickContext(context.player, session, context.event.rawSlot)) })
        return PaperMenuContent(content.title, content.background,
            content.elements.mapValues { entry(it.value) }, content.regions.mapValues { it.value.map(::entry) })
    }

    fun session(player: Player): FarmMenuSession? = sessions[player.uniqueId]?.takeIf {
        it.delegate == null || runtime.session(player) === it.delegate
    }

    internal fun refresh(session: FarmMenuSession) {
        if (sessions[session.player.uniqueId] !== session || !session.player.isOnline) return
        session.pending = false
        session.revision++
        if (session.delegate != null) session.delegate!!.requestRefresh()
        else {
            dialogsUsed = true
            dialogs.show(session.player, FarmDialogScreens.screen(session, current(), dialogText))
        }
    }

    /** Main-thread transition guarded against reload, a replaced view and duplicate clicks. */
    fun transition(player: Player, expected: FarmMenuSession, action: () -> Unit): Boolean {
        if (session(player) !== expected || expected.pending) return false
        expected.pending = true
        val revision = expected.revision
        val epoch = generation
        tasks.runLater(1L) {
            if (generation != epoch || session(player) !== expected || !player.isOnline || expected.revision != revision) return@runLater
            expected.pending = false
            action()
        }
        return true
    }

    fun close(player: Player) {
        val old = sessions.remove(player.uniqueId) ?: return
        old.revision++
        old.delegate?.close() ?: if (dialogsUsed) dialogs.close(player) else Unit
    }

    fun owns(event: InventoryClickEvent, menus: Set<MenuId>): Boolean {
        val player = event.whoClicked as? Player ?: return false
        val session = session(player) ?: return false
        return event.view.topInventory === session.inventory && session.menuId in menus
    }
    fun owns(event: InventoryDragEvent, menus: Set<MenuId>): Boolean {
        val player = event.whoClicked as? Player ?: return false
        val session = session(player) ?: return false
        return event.view.topInventory === session.inventory && session.menuId in menus
    }
    @EventHandler fun onQuit(event: PlayerQuitEvent) { close(event.player) }
    @EventHandler fun onInventoryClose(event: InventoryCloseEvent) {
        val active = sessions[event.player.uniqueId] ?: return
        if (active.inventory === event.inventory) sessions.remove(event.player.uniqueId)
    }
    @EventHandler fun onInventoryOpen(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        val active = sessions[player.uniqueId] ?: return
        if (active.delegate == null && dialogMode) close(player)
    }

    fun replace(candidate: PaperMenuConfiguration) {
        val nextMode = readDialogMode()
        val active = sessions.values.filter { it.player.isOnline }.map { it.reopen }
        generation++
        sessions.values.toList().forEach { close(it.player) }
        runtime.replace(candidate)
        dialogMode = nextMode
        active.forEach { it() }
    }
    override fun close() {
        generation++
        sessions.values.toList().forEach { close(it.player) }
        tasks.close()
        if (dialogsUsed) dialogs.close()
        runtime.close()
        HandlerList.unregisterAll(this)
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
        val ENTERPRISE_PARTICIPATION = MenuId.of("enterprise-participation")
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
            ENTERPRISE_PARTICIPATION to MenuContract(requiredElements = ids("summary", "steady", "team", "challenge", "back")),
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
            "enterprise-plan-steady",
            "enterprise-plan-team",
            "enterprise-plan-challenge",
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
            "perk-strength",
            "perk-resistance",
            "perk-fire-resistance",
            "perk-jump-boost",
        ).associateWith { TEXT_CONTRACT }

        fun loadConfiguration(dataRoot: Path): PaperMenuConfiguration {
            val config = Config(dataRoot, "config.yml")
            val loaded = PaperMenuConfigurationParser.require(
                config,
                "ui.menus.layouts",
                "ui.menus.templates",
                CONTRACTS,
                requiredTemplates = ru.ruscrafting.farms.domain.FarmPerkType.entries
                    .map { "perk-${it.name.lowercase().replace('_', '-')}" }.toSet(),
                textContracts = TEXT_CONTRACTS,
            )
            require(loaded.catalog.require(FARM_PERKS).region(PERK_OFFERS).size >=
                ru.ruscrafting.farms.domain.FarmPerkType.entries.size) {
                "ui.menus.layouts.farm-perks.regions.offers.slots must fit all 8 farm perks"
            }
            if (config.booleanOrNull("ui.menu-background.enabled") != true) return loaded
            val template = requireNotNull(loaded.templates["background"]) {
                "Enabled ui.menu-background requires the ARC menu background template"
            }
            val customModelData = config.intOrNull("ui.menu-background.custom-model-data") ?: 0
            return loaded.copy(
                templates = loaded.templates + (
                    "background" to template.copy(
                        customModelData = customModelData,
                    )
                ),
            )
        }
    }
}
