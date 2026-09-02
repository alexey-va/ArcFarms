package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.configuration.file.YamlConfiguration
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuId
import ru.arc.menu.MenuRegionId
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.io.InputStreamReader
import java.nio.file.Files

class ArcFarmsMenuLayoutTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("all ArcFarms menu families accept operator-owned fixed and dynamic slots") {
        val root = Files.createTempDirectory("arcfarms-menu-layout")
        val input = requireNotNull(ArcFarmsMenuLayoutTest::class.java.classLoader.getResourceAsStream("config.yml"))
        val yaml = input.use { YamlConfiguration.loadConfiguration(InputStreamReader(it)) }
        yaml.set("ui.menus.layouts.market.elements.accept.slot", 12)
        yaml.set("ui.menus.layouts.enterprise-overview.elements.farm.slot", 3)
        yaml.set("ui.menus.layouts.enterprise-shares.regions.buy-options.slots", listOf(18, 19, 20, 21))
        yaml.set("ui.menus.layouts.farm-perks.regions.offers.slots", listOf(11, 13, 15, 17))
        yaml.save(root.resolve("config.yml").toFile())

        val configuration = ArcFarmsMenuPlatform.loadConfiguration(root)

        configuration.catalog.require(MenuId.of("market")).slot(MenuElementId.of("accept")).index shouldBe 12
        configuration.catalog.require(MenuId.of("enterprise-overview")).slot(MenuElementId.of("farm")).index shouldBe 3
        configuration.catalog.require(MenuId.of("enterprise-shares")).region(MenuRegionId.of("buy-options"))
            .map { it.index } shouldBe listOf(18, 19, 20, 21)
        configuration.catalog.require(MenuId.of("farm-perks")).region(MenuRegionId.of("offers"))
            .map { it.index } shouldBe listOf(11, 13, 15, 17)
    }

    test("invalid overlap is rejected before a generation can be published") {
        val root = Files.createTempDirectory("arcfarms-menu-overlap")
        val input = requireNotNull(ArcFarmsMenuLayoutTest::class.java.classLoader.getResourceAsStream("config.yml"))
        val yaml = input.use { YamlConfiguration.loadConfiguration(InputStreamReader(it)) }
        yaml.set("ui.menus.layouts.market.elements.accept.slot", 13)
        yaml.save(root.resolve("config.yml").toFile())

        runCatching { ArcFarmsMenuPlatform.loadConfiguration(root) }.isFailure shouldBe true
    }

    test("every configured menu item delegates name and lore composition to ARC Core") {
        val root = Files.createTempDirectory("arcfarms-menu-text")
        val input = requireNotNull(ArcFarmsMenuLayoutTest::class.java.classLoader.getResourceAsStream("config.yml"))
        input.use { Files.copy(it, root.resolve("config.yml")) }

        val configuration = ArcFarmsMenuPlatform.loadConfiguration(root)

        configuration.templates.filterValues { it.text == null }.keys.sorted() shouldBe emptyList()
    }
})
