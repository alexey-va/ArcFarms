package ru.ruscrafting.farms.paper.farm.perk

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.mockbukkit.mockbukkit.ServerMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmPerkOfferSettings
import ru.ruscrafting.farms.config.FarmPerkSettings
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.config.MenuBackgroundSettings
import ru.ruscrafting.farms.domain.FarmPerkType
import ru.ruscrafting.farms.domain.FarmPlayerPerks
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmRules
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.ArcFarmsReloadableInventory
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

class FarmPerkControllerMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var server: ServerMock

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        server = paper.server
    }

    afterEach { paper.close() }

    test("active perk offer is non-italic and inert until it expires") {
        val world = server.addSimpleWorld("farm")
        world.getChunkAt(0, 0).load()
        val player = server.addPlayer("PerkFarmer")
        var now = 10_000L
        val activeUntil = now + 7_200_000
        val config = mockk<ArcFarmsConfig> {
            every { defaultLocale } returns "ru"
            every { useClientLocale } returns false
            every { menuBackground } returns MenuBackgroundSettings(true, "GRAY_STAINED_GLASS_PANE", 11_000)
        }
        val locale = ArcFarmsLocale(localeRoot()) { config }
        val offers = FarmPerkSettings(
            harvestArea = FarmPerkOfferSettings(250, 72),
            speed = FarmPerkOfferSettings(180, 72),
            sustenance = FarmPerkOfferSettings(150, 72),
            rewardBoost = FarmPerkOfferSettings(400, 72),
            rewardBonusPercent = 25,
            sustainIntervalSeconds = 5,
        )
        val zone = mockk<FarmZoneSettings>(relaxed = true) {
            every { id } returns "communal_farm"
            every { permission } returns "arcfarms.farm"
            every { perks } returns offers
        }
        val runtime = FarmRuntime(
            settings = zone,
            region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 31, 128, 31)),
            orders = emptyMap(),
            orderList = emptyList(),
            rules = FarmRules(listOf(50), 1, 1_000),
            state = FarmShiftState(),
        )
        val plugin = paper.createSimplePlugin("FarmPerkMenuTest")
        val port = mockk<WorksiteRuntimePort>(relaxed = true) {
            every { runLater(any(), any()) } answers {
                server.scheduler.runTaskLater(plugin, secondArg<() -> Unit>(), firstArg())
                true
            }
        }
        val controller = FarmPerkController(
            plugin = plugin,
            settings = { config },
            locale = locale,
            debug = ArcFarmsDebug({ false }) {},
            access = port,
            audience = port,
            state = port,
            tasks = port,
            points = FarmPointProvider { _, _ -> FarmPointPosition(world.name, 1.5, 65.0, 1.5) },
            runtimes = { listOf(runtime) },
            weeklyContribution = { 2_000 },
            currentWeekStart = { 107 },
            clock = { now },
            persistAsync = { CompletableFuture.completedFuture(Unit) },
        )
        controller.replace(mapOf(
            player.uniqueId to FarmPlayerPerks(
                weekStartEpochDay = 107,
                spentPoints = 250,
                activeUntil = mapOf(FarmPerkType.HARVEST_AREA to activeUntil),
            ),
        ))
        controller.open(player, runtime)

        val inventory = player.openInventory.topInventory
        inventory.getItem(0)?.itemMeta?.customModelData shouldBe 11_000
        val offer = requireNotNull(inventory.getItem(10))
        offer.type shouldBe Material.DIAMOND_HOE
        offer.itemMeta.enchantmentGlintOverride shouldBe true
        offer.itemMeta.displayName()?.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
        offer.itemMeta.lore()?.all { it.decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE } shouldBe true
        offer.itemMeta.lore()?.joinToString(" ") {
            PlainTextComponentSerializer.plainText().serialize(it)
        }?.contains("Активно ещё около 2 ч.") shouldBe true
        val click = InventoryClickEvent(
            player.openInventory,
            InventoryType.SlotType.CONTAINER,
            10,
            ClickType.LEFT,
            InventoryAction.PICKUP_ALL,
        )

        controller.handleClick(click) shouldBe true
        controller.handleClick(click) shouldBe true

        PlainTextComponentSerializer.plainText().serialize(requireNotNull(inventory.getItem(10)?.itemMeta?.displayName())) shouldBe
            "Широкий взмах"
        controller.snapshot().getValue(player.uniqueId).spentPoints shouldBe 250

        val beforeRefresh = player.openInventory.topInventory
        now = activeUntil + 1
        (beforeRefresh.holder as ArcFarmsReloadableInventory).refresh(player)
        val refreshed = player.openInventory.topInventory
        (refreshed === beforeRefresh) shouldBe false
        PlainTextComponentSerializer.plainText().serialize(requireNotNull(refreshed.getItem(10)?.itemMeta?.displayName())) shouldBe
            "Широкий взмах"
        refreshed.getItem(10)?.itemMeta?.lore()?.joinToString(" ") {
            PlainTextComponentSerializer.plainText().serialize(it)
        }?.contains("Активно") shouldBe false
        refreshed.getItem(10)?.type shouldBe Material.DIAMOND_HOE
        refreshed.getItem(10)?.itemMeta?.enchantmentGlintOverride shouldBe false
        val beforeRightClick = controller.snapshot()
        controller.handleClick(InventoryClickEvent(
            player.openInventory,
            InventoryType.SlotType.CONTAINER,
            10,
            ClickType.RIGHT,
            InventoryAction.PICKUP_HALF,
        )) shouldBe true
        controller.snapshot() shouldBe beforeRightClick

    }

    test("an open perk menu removes the active mark and glint when the perk expires") {
        val world = server.addSimpleWorld("farm")
        world.getChunkAt(0, 0).load()
        val player = server.addPlayer("ExpiringPerkFarmer")
        var now = 10_000L
        val activeUntil = now + 50L
        val config = mockk<ArcFarmsConfig> {
            every { defaultLocale } returns "ru"
            every { useClientLocale } returns false
            every { menuBackground } returns MenuBackgroundSettings(false, "AIR", 0)
        }
        val locale = ArcFarmsLocale(localeRoot()) { config }
        val offers = FarmPerkSettings(
            harvestArea = FarmPerkOfferSettings(250, 72),
            speed = FarmPerkOfferSettings(180, 72),
            sustenance = FarmPerkOfferSettings(150, 72),
            rewardBoost = FarmPerkOfferSettings(400, 72),
            rewardBonusPercent = 25,
            sustainIntervalSeconds = 5,
        )
        val zone = mockk<FarmZoneSettings>(relaxed = true) {
            every { id } returns "communal_farm"
            every { permission } returns "arcfarms.farm"
            every { perks } returns offers
        }
        val runtime = FarmRuntime(
            settings = zone,
            region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 31, 128, 31)),
            orders = emptyMap(),
            orderList = emptyList(),
            rules = FarmRules(listOf(50), 1, 1_000),
            state = FarmShiftState(),
        )
        val plugin = paper.createSimplePlugin("FarmPerkExpiryTest")
        val port = mockk<WorksiteRuntimePort>(relaxed = true) {
            every { runLater(any(), any()) } answers {
                server.scheduler.runTaskLater(plugin, secondArg<() -> Unit>(), firstArg())
                true
            }
        }
        val controller = FarmPerkController(
            plugin = plugin,
            settings = { config },
            locale = locale,
            debug = ArcFarmsDebug({ false }) {},
            access = port,
            audience = port,
            state = port,
            tasks = port,
            points = FarmPointProvider { _, _ -> FarmPointPosition(world.name, 1.5, 65.0, 1.5) },
            runtimes = { listOf(runtime) },
            weeklyContribution = { 2_000 },
            currentWeekStart = { 107 },
            clock = { now },
            persistAsync = { CompletableFuture.completedFuture(Unit) },
        )
        controller.replace(mapOf(
            player.uniqueId to FarmPlayerPerks(
                weekStartEpochDay = 107,
                activeUntil = mapOf(FarmPerkType.HARVEST_AREA to activeUntil),
            ),
        ))
        controller.open(player, runtime)

        player.openInventory.topInventory.getItem(10)?.itemMeta?.enchantmentGlintOverride shouldBe true
        now = activeUntil + 1L
        server.scheduler.performTicks(2L)

        val expired = requireNotNull(player.openInventory.topInventory.getItem(10))
        expired.type shouldBe Material.DIAMOND_HOE
        expired.itemMeta.enchantmentGlintOverride shouldBe false
        expired.itemMeta.lore()?.joinToString(" ") {
            PlainTextComponentSerializer.plainText().serialize(it)
        }?.contains("Активно") shouldBe false
    }
}) {
    companion object {
        private fun localeRoot() = Files.createTempDirectory("arcfarms-perk-locale-test").also { root ->
            Files.createDirectories(root.resolve("lang"))
            listOf("lang/ru.yml", "lang/en.yml").forEach { name ->
                val input = requireNotNull(FarmPerkControllerMockBukkitTest::class.java.classLoader.getResourceAsStream(name))
                input.use { Files.copy(it, root.resolve(name)) }
            }
        }
    }
}
