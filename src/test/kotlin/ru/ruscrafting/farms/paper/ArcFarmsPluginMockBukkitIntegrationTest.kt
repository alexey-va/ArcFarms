@file:Suppress("DEPRECATION")

package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path

class ArcFarmsPluginMockBukkitIntegrationTest : FunSpec({
    test("complete plugin boot registers commands, remains entity-stable, and shuts down cleanly") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val world = paper.server.addSimpleWorld("sp11")
            paper.server.addSimpleWorld("world")
            val plugin = paper.server.pluginManager.loadPlugin(ArcFarmsPlugin::class.java) as ArcFarmsPlugin
            preparePluginData(plugin.dataFolder.toPath())
            paper.server.pluginManager.enablePlugin(plugin)
            plugin.isEnabled shouldBe true
            paper.server.getPluginCommand("arcfarms")?.plugin shouldBe plugin

            val operator = paper.addPlayer("FarmOperator")
            operator.isOp = true
            operator.performCommand("arcfarms status") shouldBe true
            PlainTextComponentSerializer.plainText().serialize(requireNotNull(operator.nextComponentMessage())) shouldContain
                "Current work shifts"

            operator.performCommand("arcfarms") shouldBe true
            val root = operator.openInventory.topInventory
            root.size shouldBe 27
            root.getItem(2)?.type shouldBe Material.GRAY_STAINED_GLASS_PANE
            root.getItem(3)?.type shouldBe Material.CARROT
            root.getItem(4)?.type shouldBe Material.DIAMOND_AXE
            root.getItem(6)?.type shouldBe Material.DIAMOND_PICKAXE
            root.getItem(19)?.type shouldBe Material.COMPASS
            root.getItem(22)?.type shouldBe Material.DIAMOND
            root.getItem(25)?.type shouldBe Material.ENCHANTED_BOOK
            root.getItem(3)?.itemMeta?.customModelData shouldBe 31_001
            root.getItem(22)?.itemMeta?.customModelData shouldBe 31_002
            root.assertVisibleComponentsAreNonItalic()
            root.getItem(22).plainLore() shouldContain "LMB — open companies"
            root.getItem(3).plainLoreLines().apply {
                size shouldBe 3
                this[1] shouldBe ""
                this[2] shouldContain "LMB — travel to the shift"
            }

            clickTopInventory(paper, operator, 22, ClickType.RIGHT).isCancelled shouldBe true
            operator.openInventory.topInventory shouldBe root
            clickTopInventory(paper, operator, 22).isCancelled shouldBe true
            operator.openInventory.topInventory shouldBe root
            val unrelated = paper.server.createInventory(null, 9, Component.text("Unrelated"))
            operator.openInventory(unrelated)
            paper.performTicks(1)
            operator.openInventory.topInventory shouldBe unrelated

            operator.performCommand("arcfarms") shouldBe true
            val currentRoot = operator.openInventory.topInventory
            clickTopInventory(paper, operator, 22).isCancelled shouldBe true
            operator.openInventory.topInventory shouldBe currentRoot
            paper.performTicks(1)
            val companies = operator.openInventory.topInventory
            companies.size shouldBe 27
            companies.getItem(2)?.type shouldBe Material.HAY_BLOCK
            companies.getItem(4)?.type shouldBe Material.GOLDEN_AXE
            companies.getItem(6)?.type shouldBe Material.CHEST_MINECART
            companies.getItem(18)?.type shouldBe Material.ARROW
            companies.getItem(2).plainLore() shouldContain "Shadow mode"
            companies.getItem(2).plainLoreLines().apply {
                count(String::isEmpty) shouldBe 3
                last() shouldContain "LMB — open company"
            }
            companies.getItem(4).plainLoreLines().apply {
                this[1] shouldBe ""
                none { "LMB —" in it } shouldBe true
            }
            companies.assertVisibleComponentsAreNonItalic()

            clickTopInventory(paper, operator, 4).isCancelled shouldBe true
            operator.openInventory.topInventory shouldBe companies
            clickTopInventory(paper, operator, 2, ClickType.RIGHT).isCancelled shouldBe true
            operator.openInventory.topInventory shouldBe companies
            clickTopInventory(paper, operator, 2).isCancelled shouldBe true
            operator.openInventory.topInventory shouldBe companies
            paper.performTicks(1)
            val farmCompany = operator.openInventory.topInventory
            farmCompany.size shouldBe 45
            farmCompany.getItem(4)?.type shouldBe Material.BREAD
            farmCompany.getItem(10)?.type shouldBe Material.MAP
            farmCompany.getItem(12)?.type shouldBe Material.GOLDEN_HOE
            farmCompany.getItem(14)?.type shouldBe Material.KNOWLEDGE_BOOK
            farmCompany.getItem(16)?.type shouldBe Material.NAME_TAG
            farmCompany.getItem(28)?.type shouldBe Material.EMERALD_BLOCK
            farmCompany.getItem(30)?.type shouldBe Material.GOLD_BLOCK
            farmCompany.getItem(10)?.itemMeta?.customModelData shouldBe 31_003
            farmCompany.getItem(36)?.type shouldBe Material.ARROW
            farmCompany.getItem(10).plainLore() shouldContain "Pool estimate, not a promise"
            farmCompany.getItem(28).plainLore().contains("LMB —") shouldBe false
            farmCompany.assertVisibleComponentsAreNonItalic()

            clickTopInventory(paper, operator, 36, ClickType.RIGHT).isCancelled shouldBe true
            operator.openInventory.topInventory shouldBe farmCompany
            clickTopInventory(paper, operator, 36).isCancelled shouldBe true
            operator.openInventory.topInventory shouldBe farmCompany
            paper.performTicks(1)
            operator.openInventory.topInventory.size shouldBe 27
            clickTopInventory(paper, operator, 18).isCancelled shouldBe true
            paper.performTicks(1)
            operator.openInventory.topInventory.size shouldBe 27
            operator.openInventory.topInventory.getItem(22)?.type shouldBe Material.DIAMOND

            updatePluginConfig(
                plugin.dataFolder.toPath(),
                "ui.menus.templates.farm.material" to "BEETROOT",
                "ui.menus.templates.farm.custom-model-data" to 31_099,
                "ui.enterprise-menu.items.market.material" to "IRON_INGOT",
            )
            operator.performCommand("arcfarms reload") shouldBe true
            val reloadedRoot = operator.openInventory.topInventory
            reloadedRoot.getItem(3)?.type shouldBe Material.BEETROOT
            reloadedRoot.getItem(3)?.itemMeta?.customModelData shouldBe 31_099
            clickTopInventory(paper, operator, 22)
            paper.performTicks(1)
            clickTopInventory(paper, operator, 2)
            paper.performTicks(1)
            operator.openInventory.topInventory.getItem(30)?.type shouldBe Material.IRON_INGOT

            updatePluginConfig(
                plugin.dataFolder.toPath(),
                "ui.enterprise-menu.items.market.material" to "NETHERITE_INGOT",
                "ui.enterprise-menu.items.report.custom-model-data" to 31_100,
            )
            operator.performCommand("arcfarms reload") shouldBe true
            val reloadedCompany = operator.openInventory.topInventory
            reloadedCompany.size shouldBe 45
            reloadedCompany.getItem(10)?.itemMeta?.customModelData shouldBe 31_100
            reloadedCompany.getItem(30)?.type shouldBe Material.NETHERITE_INGOT
            val acceptedMarketName = reloadedCompany.getItem(30).plainName()
            val originalMarketLocale = updatePluginLocale(
                plugin.dataFolder.toPath(),
                "companies.farm-detail.market.name",
                "<color:#ff5555>REJECTED LOCALE GENERATION</color>",
            )

            updatePluginConfig(
                plugin.dataFolder.toPath(),
                "ui.enterprise-menu.items.market.material" to "AIR",
            )
            operator.performCommand("arcfarms reload") shouldBe true
            clickTopInventory(paper, operator, 36)
            paper.performTicks(1)
            clickTopInventory(paper, operator, 2)
            paper.performTicks(1)
            operator.openInventory.topInventory.getItem(30)?.type shouldBe Material.NETHERITE_INGOT
            operator.openInventory.topInventory.getItem(30).plainName() shouldBe acceptedMarketName

            updatePluginConfig(
                plugin.dataFolder.toPath(),
                "ui.enterprise-menu.items.market.material" to "NETHERITE_INGOT",
            )
            updatePluginLocale(
                plugin.dataFolder.toPath(),
                "companies.farm-detail.market.name",
                originalMarketLocale,
            )
            operator.performCommand("arcfarms reload") shouldBe true

            updatePluginConfig(
                plugin.dataFolder.toPath(),
                "enterprises.farm.company-id" to "renamed_farm_company",
                "ui.enterprise-menu.items.market.material" to "GOLD_INGOT",
            )
            updatePluginLocale(
                plugin.dataFolder.toPath(),
                "companies.farm-detail.market.name",
                "<color:#ff5555>REJECTED COMPANY GENERATION</color>",
            )
            operator.performCommand("arcfarms reload") shouldBe true
            clickTopInventory(paper, operator, 36)
            paper.performTicks(1)
            clickTopInventory(paper, operator, 2)
            paper.performTicks(1)
            operator.openInventory.topInventory.getItem(30)?.type shouldBe Material.NETHERITE_INGOT
            operator.openInventory.topInventory.getItem(30).plainName() shouldBe acceptedMarketName

            updatePluginConfig(
                plugin.dataFolder.toPath(),
                "enterprises.farm.company-id" to "communal_farm",
                "ui.enterprise-menu.items.market.material" to "NETHERITE_INGOT",
            )
            updatePluginLocale(
                plugin.dataFolder.toPath(),
                "companies.farm-detail.market.name",
                originalMarketLocale,
            )

            val inside = world.getBlockAt(200, 65, 450)
            inside.type = Material.SHULKER_BOX
            val place = BlockPlaceEvent(
                inside,
                inside.state,
                world.getBlockAt(200, 64, 450),
                ItemStack(Material.SHULKER_BOX),
                operator,
                true,
                EquipmentSlot.HAND,
            )
            paper.server.pluginManager.callEvent(place)
            place.isCancelled shouldBe true

            paper.performTicks(25)
            val settledEntityCount = world.entities.size
            paper.performTicks(240)
            world.entities.size shouldBe settledEntityCount

            paper.server.pluginManager.disablePlugin(plugin)
            plugin.isEnabled shouldBe false
            world.entities.count { it !is Player } shouldBe 0
        } finally {
            paper.close()
        }
    }
})

private fun preparePluginData(dataRoot: Path) {
    Files.createDirectories(dataRoot.resolve("lang"))
    listOf("lang/ru.yml", "lang/en.yml").forEach { name ->
        val input = requireNotNull(ArcFarmsPluginMockBukkitIntegrationTest::class.java.classLoader.getResourceAsStream(name))
        input.use { Files.copy(it, dataRoot.resolve(name)) }
    }

    val input = requireNotNull(ArcFarmsPluginMockBukkitIntegrationTest::class.java.classLoader.getResourceAsStream("config.yml"))
    val config = input.use { YamlConfiguration.loadConfiguration(InputStreamReader(it)) }
    config.set("network.enabled", false)
    config.set("ui.bossbars", false)
    config.set("ui.particles", false)
    config.set("ui.sounds", false)
    config.set("ui.farm-scoreboard.enabled", false)
    config.set("ui.menus.layouts.main.background.template", "background")
    config.set("ui.menus.layouts.main.elements.farm.slot", 3)
    config.set("ui.menus.templates.background.material", "GRAY_STAINED_GLASS_PANE")
    config.set("ui.menus.templates.background.custom-model-data", 0)
    config.set("ui.menu-back.material", "ARROW")
    config.set("ui.menu-back.custom-model-data", 0)
    config.set("ui.menus.templates.farm.material", "CARROT")
    config.set("ui.menus.templates.farm.custom-model-data", 31_001)
    config.set("ui.menus.templates.lumber.material", "DIAMOND_AXE")
    config.set("ui.menus.templates.mine.material", "DIAMOND_PICKAXE")
    config.set("ui.menus.templates.workday.material", "COMPASS")
    config.set("ui.menus.templates.stats.material", "ENCHANTED_BOOK")
    config.set("ui.menus.templates.companies.material", "DIAMOND")
    config.set("ui.menus.templates.companies.custom-model-data", 31_002)
    config.set("ui.enterprise-menu.items.overview-farm.material", "HAY_BLOCK")
    config.set("ui.enterprise-menu.items.overview-lumber.material", "GOLDEN_AXE")
    config.set("ui.enterprise-menu.items.overview-mine.material", "CHEST_MINECART")
    config.set("ui.enterprise-menu.items.farm-header.material", "BREAD")
    config.set("ui.enterprise-menu.items.report.material", "MAP")
    config.set("ui.enterprise-menu.items.report.custom-model-data", 31_003)
    config.set("ui.enterprise-menu.items.workers.material", "GOLDEN_HOE")
    config.set("ui.enterprise-menu.items.policy.material", "KNOWLEDGE_BOOK")
    config.set("ui.enterprise-menu.items.license.material", "NAME_TAG")
    config.set("ui.enterprise-menu.items.shares.material", "EMERALD_BLOCK")
    config.set("ui.enterprise-menu.items.market.material", "GOLD_BLOCK")
    config.set("enterprises.farm.mode", "SHADOW")
    config.set("farm-zones.communal_farm.region", null)
    config.set("farm-zones.communal_farm.bounds.min", listOf(185, 35, 420))
    config.set("farm-zones.communal_farm.bounds.max", listOf(230, 80, 490))
    config.set("lumber-zones.communal_lumbermill.enabled", false)
    config.getConfigurationSection("mine-zones")?.getKeys(false).orEmpty().forEach { id ->
        config.set("mine-zones.$id.enabled", false)
    }
    config.save(dataRoot.resolve("config.yml").toFile())
}

private fun updatePluginConfig(dataRoot: Path, vararg entries: Pair<String, Any>) {
    val file = dataRoot.resolve("config.yml").toFile()
    val config = YamlConfiguration.loadConfiguration(file)
    entries.forEach { (path, value) -> config.set(path, value) }
    config.save(file)
}

private fun updatePluginLocale(dataRoot: Path, path: String, value: String): String {
    val file = dataRoot.resolve("lang/en.yml").toFile()
    val config = YamlConfiguration.loadConfiguration(file)
    val previous = requireNotNull(config.getString(path))
    config.set(path, value)
    config.save(file)
    return previous
}

private fun clickTopInventory(
    paper: MockBukkitTestRuntime,
    player: Player,
    slot: Int,
    click: ClickType = ClickType.LEFT,
): InventoryClickEvent = InventoryClickEvent(
    player.openInventory,
    InventoryType.SlotType.CONTAINER,
    slot,
    click,
    if (click == ClickType.LEFT) InventoryAction.PICKUP_ALL else InventoryAction.PICKUP_HALF,
).also(paper.server.pluginManager::callEvent)

private fun Inventory.assertVisibleComponentsAreNonItalic() {
    contents.filterNotNull().map(ItemStack::getItemMeta).filter { it.hasDisplayName() }.forEach { meta ->
        meta.displayName()?.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
        meta.lore().orEmpty().all { it.decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE } shouldBe true
    }
}

private fun ItemStack?.plainLore(): String = this?.itemMeta?.lore().orEmpty().joinToString(" ") {
    PlainTextComponentSerializer.plainText().serialize(it)
}

private fun ItemStack?.plainLoreLines(): List<String> = this?.itemMeta?.lore().orEmpty().map {
    PlainTextComponentSerializer.plainText().serialize(it)
}

private fun ItemStack?.plainName(): String = PlainTextComponentSerializer.plainText().serialize(
    this?.itemMeta?.displayName() ?: net.kyori.adventure.text.Component.empty(),
)
