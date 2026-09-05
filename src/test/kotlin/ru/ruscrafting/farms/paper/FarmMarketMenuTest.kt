package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import java.io.InputStreamReader
import java.nio.file.Files

class FarmMarketMenuTest : FunSpec({
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
