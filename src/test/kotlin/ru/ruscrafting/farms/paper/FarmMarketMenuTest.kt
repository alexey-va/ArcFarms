package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import io.papermc.paper.connection.PlayerGameConnection
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmOrder
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmRules
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.domain.FarmSpecialIncidentState
import ru.ruscrafting.farms.paper.farm.incident.special.FarmSpecialIncidentController
import ru.ruscrafting.farms.paper.farm.FarmEventRouter
import ru.ruscrafting.farms.paper.farm.scene.FarmContractSceneController
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

class FarmMarketMenuTest : FunSpec({
    test("market buyer clicks reopen after native root Close with either escape preference") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("FarmMarketDialogReopen")
            listOf("config.yml", "lang/ru.yml", "lang/en.yml").forEach { name ->
                val target = plugin.dataFolder.toPath().resolve(name)
                Files.createDirectories(target.parent)
                requireNotNull(javaClass.classLoader.getResourceAsStream(name)).use { Files.copy(it, target) }
            }
            val settings = ArcFarmsConfig.inspect(plugin.dataFolder.toPath())
            val locale = ArcFarmsLocale(plugin.dataFolder.toPath()) { settings }
            val player = spyk(paper.addPlayer("MarketVisitor"))
            every { player.closeDialog() } just Runs
            var shown = 0
            lateinit var displayed: PaperDialogScreen
            lateinit var registration: Any
            // The same native-presenter seam as FarmShopDialogTest keeps Core's real history.
            val presenter: (Player, PaperDialogScreen, Any) -> Unit = { _, screen, registered ->
                shown++
                displayed = screen
                registration = registered
            }
            val native = PaperDialogRuntime::class.java
                .getDeclaredConstructor(org.bukkit.plugin.Plugin::class.java, kotlin.jvm.functions.Function3::class.java)
                .newInstance(plugin, presenter)
            val display = object : FarmDialogDisplay {
                override fun show(player: Player, screen: PaperDialogScreen) = native.open(player, screen)
                override fun show(player: Player, screen: PaperDialogScreen, reopen: (() -> Unit)?, onDismiss: () -> Unit, closeOnEscape: Boolean) =
                    native.open(player, screen, reopen, onDismiss, closeOnEscape)
                override fun beginFlow(player: Player) = native.beginFlow(player)
                override fun close(player: Player) = native.close(player)
                override fun close() = native.close()
            }
            var closeOnEscape = false
            ArcFarmsMenuPlatform(plugin, display, escapeCloses = { closeOnEscape }).use { menus ->
                menus.configureDialogs(locale)
                val order = FarmOrder("market_order", mapOf("WHEAT" to 64))
                val runtime = FarmRuntime(
                    settings = settings.farms.first(), region = mockk { every { contains(any()) } returns true },
                    orders = mapOf(order.id to order), orderList = listOf(order), rules = FarmRules(listOf(50), 1, 1_000),
                    state = FarmShiftState(phase = FarmPhase.INCIDENT, sequence = 42, orderId = order.id,
                        incidentType = FarmIncidentType.MARKET, incidentRequired = 64),
                )
                val port = mockk<WorksiteRuntimePort>(relaxed = true)
                every { port.hasAccess(player, runtime.settings.permission) } returns true
                var now = 10_000L
                val lastInteraction = mutableMapOf<String, Long>()
                // Model the access port's configured 700 ms feedback throttle.
                every { port.allowInteraction(any(), any()) } answers {
                    val key = firstArg<String>()
                    val previous = lastInteraction[key]
                    if (previous != null && now - previous < secondArg<Long>()) false
                    else { lastInteraction[key] = now; true }
                }
                val controller = FarmSpecialIncidentController(
                    plugin = plugin, settings = { settings }, locale = locale, debug = ArcFarmsDebug({ false }) {},
                    access = port, audience = port, state = port, tasks = port, serviceItems = mockk(),
                    ledger = mockk(), registry = mockk(), beds = mockk(), points = mockk(), transitions = mockk(),
                    runtimes = { listOf(runtime) }, clock = { now }, nightShift = mockk(), menus = menus,
                )
                val scene = FarmContractSceneController(
                    plugin, { settings }, locale, ArcFarmsDebug({ false }) {}, port, port, port,
                    mockk(), controller, mockk(), { listOf(runtime) },
                )
                val router = FarmEventRouter(
                    locale = locale, debug = ArcFarmsDebug({ false }) {}, access = port, audience = port, state = port,
                    runtimes = { listOf(runtime) }, worldAdmin = mockk(relaxed = true), ledger = mockk(), registry = mockk(),
                    fixedCrops = mockk(), field = mockk(), care = mockk(relaxed = true), drought = mockk(), pests = mockk(),
                    birds = mockk(), foodDelivery = mockk(relaxed = true), routeAdmin = mockk(), perks = mockk(relaxed = true),
                    special = controller, processing = mockk(relaxed = true), barnFire = mockk(), frost = mockk(relaxed = true),
                    greenhouse = mockk(relaxed = true), actionIncidents = mockk(relaxed = true), delivery = mockk(relaxed = true),
                    enterprise = mockk(), supplies = mockk(relaxed = true), scene = scene, harvest = mockk(), hud = mockk(),
                    transitions = mockk(), shiftStartPending = { false }, persistAsync = { CompletableFuture.completedFuture(Unit) },
                    clock = { now },
                )
                val targets = listOf(
                    player.world.spawn(player.location, Villager::class.java) to FarmContractSceneRole.CUSTOMER,
                    player.world.spawn(player.location, Interaction::class.java) to FarmContractSceneRole.CUSTOMER_INTERACTION,
                )
                targets.forEach { (entity, role) ->
                    entity.persistentDataContainer.apply {
                        set(NamespacedKey(plugin, "farm_contract_scene_zone"), PersistentDataType.STRING, runtime.settings.id)
                        set(NamespacedKey(plugin, "farm_contract_scene_sequence"), PersistentDataType.LONG, runtime.state.sequence)
                        set(NamespacedKey(plugin, "farm_contract_scene_role"), PersistentDataType.STRING, role.name)
                    }
                }
                val connection = mockk<PlayerGameConnection> { every { this@mockk.player } returns player }
                for (closePreference in listOf(false, true)) {
                    closeOnEscape = closePreference
                    for (accepted in listOf(false, true)) {
                        val special = FarmSpecialIncidentState(crop = "WHEAT", marketAccepted = accepted)
                        runtime.state = runtime.state.copy(specialIncident = special)
                        for ((target, _) in targets) {
                            lastInteraction.clear()
                            val openings = List(6) {
                                val before = shown
                                val offhand = PlayerInteractEntityEvent(player, target, EquipmentSlot.OFF_HAND)
                                router.onInteractEntityLowest(offhand)
                                router.onInteractEntity(offhand)
                                offhand.isCancelled shouldBe true
                                shown shouldBe before

                                val click = PlayerInteractEntityEvent(player, target, EquipmentSlot.HAND)
                                router.onInteractEntityLowest(click)
                                router.onInteractEntity(click)
                                click.isCancelled shouldBe true
                                val opened = shown == before + 1
                                if (opened) {
                                    menus.session(player)?.menuId shouldBe ArcFarmsMenuPlatform.MARKET
                                    val footer = requireNotNull(displayed.exitButton)
                                    if (closeOnEscape) {
                                        PlainTextComponentSerializer.plainText().serialize(footer.label) shouldBe "Закрыть"
                                    } else {
                                        // Direct NPC entry starts a flow; Core owns the client-localized root Close.
                                        footer.label shouldBe Component.translatable("mco.selectServer.close", NamedTextColor.WHITE)
                                        footer.tooltip shouldBe footer.label
                                    }
                                    footer.closeDialogBeforeAction shouldBe closeOnEscape
                                    val key = registration.javaClass.methods.single { it.name.startsWith("key-") }
                                        .invoke(registration, footer.id.value) as String
                                    native.onCustomClick(mockk {
                                        every { commonConnection } returns connection
                                        every { identifier } returns Key.key(key)
                                        every { dialogResponseView } returns null
                                    })
                                    menus.session(player) shouldBe null
                                }
                                now += 350L
                                opened
                            }
                            openings shouldBe List(6) { true }
                        }
                    }
                }
                val openedMarkets = shown
                lastInteraction.clear()
                runtime.state = runtime.state.copy(phase = FarmPhase.HARVESTING, incidentType = null, specialIncident = null)
                repeat(2) { scene.interact(player, targets.first().first) }
                verify(exactly = 1) { port.sendChat(player, MessageKey.FARM_CUSTOMER_EXPLANATION, any()) }
                shown shouldBe openedMarkets

                runtime.state = runtime.state.copy(phase = FarmPhase.INCIDENT, incidentType = FarmIncidentType.MARKET,
                    specialIncident = FarmSpecialIncidentState(crop = "WHEAT"))
                every { port.hasAccess(player, runtime.settings.permission) } returns false
                scene.interact(player, targets.first().first)
                verify(exactly = 1) { port.sendChat(player, MessageKey.ZONE_LOCKED, any()) }
                every { port.hasAccess(player, runtime.settings.permission) } returns true
                runtime.state = runtime.state.copy(sequence = runtime.state.sequence + 1)
                scene.interact(player, targets.first().first)
                shown shouldBe openedMarkets
            }
        }
    }

    test("pending market decision follows the configured semantic slot") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val plugin = paper.createSimplePlugin("FarmMarketMenuTest")
            Files.createDirectories(plugin.dataFolder.toPath().resolve("lang"))
            listOf("config.yml", "lang/ru.yml", "lang/en.yml").forEach { name ->
                val input = requireNotNull(FarmMarketMenuTest::class.java.classLoader.getResourceAsStream(name))
                val target = plugin.dataFolder.toPath().resolve(name)
                Files.createDirectories(target.parent)
                input.use { Files.copy(it, target) }
            }
            val configFile = plugin.dataFolder.toPath().resolve("config.yml").toFile()
            val yaml = YamlConfiguration.loadConfiguration(configFile)
            yaml.set("ui.menus.layouts.market.elements.accept.slot", 12)
            yaml.set("ui.menu-background.enabled", true)
            yaml.set("ui.menu-background.custom-model-data", 11_000)
            yaml.set("ui.menus.templates.background.custom-model-data", 0)
            yaml.set("ui.menu-presentation", "INVENTORY")
            yaml.save(configFile)
            val settings = ArcFarmsConfig.inspect(plugin.dataFolder.toPath())
            val locale = ArcFarmsLocale(plugin.dataFolder.toPath()) { settings }
            val menus = ArcFarmsMenuPlatform(plugin)
            var decision: FarmMarketClick? = null
            val menu = FarmMarketMenu(locale, menus, { _, _, _ -> }) { _, _, selected -> decision = selected }
            val player = paper.addPlayer("MarketFarmer")

            menu.openPending(player, "communal_farm", 42, Material.WHEAT, 64, 25, "5 мин.")

            player.openInventory.topInventory.getItem(12)?.type shouldBe Material.EMERALD
            player.openInventory.topInventory.getItem(11)?.type shouldBe Material.GRAY_STAINED_GLASS_PANE
            @Suppress("DEPRECATION")
            player.openInventory.topInventory.getItem(11)?.itemMeta?.customModelData shouldBe 11_000
            val event = InventoryClickEvent(
                player.openInventory,
                InventoryType.SlotType.CONTAINER,
                12,
                ClickType.LEFT,
                InventoryAction.PICKUP_ALL,
            )
            paper.server.pluginManager.callEvent(event)
            decision shouldBe FarmMarketClick(
                "communal_farm", 42, FarmMarketMode.PENDING, FarmMarketDecision.ACCEPT,
            )
            event.isCancelled shouldBe true
        } finally {
            paper.close()
        }
    }
})
