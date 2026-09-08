package ru.ruscrafting.farms.paper.farm.perk

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import io.mockk.every
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.domain.*
import ru.ruscrafting.farms.paper.*
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

class FarmShopDialogTest : FunSpec({
    test("real shop content exposes food and premium cards with typed colors and table data") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val plugin = paper.createSimplePlugin("FarmShopDialogTest")
            val root = plugin.dataFolder.toPath()
            Files.createDirectories(root.resolve("lang"))
            listOf("config.yml", "lang/ru.yml", "lang/en.yml").forEach { name ->
                javaClass.classLoader.getResourceAsStream(name)!!.use { Files.copy(it, root.resolve(name)) }
            }
            Files.writeString(root.resolve("config.yml"), Files.readString(root.resolve("config.yml")).replace("use-client-locale: true", "use-client-locale: false"))
            val settings = ArcFarmsConfig.load(root)
            val locale = ArcFarmsLocale(root) { settings }
            val capture = ShopDialogCapture()
            val menus = ArcFarmsMenuPlatform(plugin, capture)
            menus.configureDialogs(locale)
            val player = paper.addPlayer("ShopFarmer")
            val zone = settings.farms.first()
            val runtime = FarmRuntime(settings = zone, region = mockk(relaxed = true), orders = emptyMap(), orderList = emptyList(), rules = FarmRules(listOf(50), 1, 1000), state = FarmShiftState())
            val port = mockk<WorksiteRuntimePort>(relaxed = true)
            var contribution = 2000L
            val controller = FarmPerkController(plugin, { settings }, locale, ArcFarmsDebug({ false }) {},
                port, port, port, port, FarmPointProvider { _, _ -> FarmPointPosition("farm", 0.0, 64.0, 0.0) },
                { listOf(runtime) }, { contribution }, { 107 }, { 1000 }, { CompletableFuture.completedFuture(Unit) }, menus)
            controller.open(player, runtime)
            val catalog = capture.last!!
            catalog.buttons.size shouldBe 13
            catalog.buttons.first { it.id.value == "offers_10" }.label.color()!!.value() shouldBe 0xf4d87a
            catalog.buttons.first { it.id.value == "offers_8" }.label.color()!!.value() shouldBe 0xc4abff
            exportShopScreen("catalog", catalog)
            catalog.buttons.first { it.id.value == "offers_10" }.onClick.handle(mockk<PaperDialogClickContext>(relaxed = true))
            val food = capture.last!!
            val text = food.body.joinToString("\n") { PlainTextComponentSerializer.plainText().serialize(it.text) }
            text shouldContain "20"
            text shouldContain "16"
            PlainTextComponentSerializer.plainText().serialize(food.buttons.single().label) shouldBe "Купить еду"
            food.buttons.single().label.color()!!.value() shouldBe 0x9bd48d
            exportShopScreen("food", food)
            controller.open(player, runtime)
            capture.last!!.buttons.first { it.id.value == "offers_8" }.onClick.handle(mockk<PaperDialogClickContext>(relaxed = true))
            exportShopScreen("premium", capture.last!!)
            contribution = 0
            controller.replace(mapOf(player.uniqueId to FarmPlayerPerks(107, activeUntil = mapOf(FarmPerkType.IRON_FARMER to 999999))))
            controller.open(player, runtime)
            val locked = capture.last!!
            val active = locked.buttons.first { it.id.value == "info_offers_8" }
            active.label.color()!!.value() shouldBe 0x9bd48d
            PlainTextComponentSerializer.plainText().serialize(active.label) shouldContain "✔"
            locked.buttons.first { it.id.value == "info_offers_10" }.label.color()!!.value() shouldBe 0xaaa49a
            exportShopScreen("locked", locked)
            val service = mockk<ArcFarmsService>(relaxed = true) {
                every { playerStats(any()) } returns PlayerActivityStats(contributions = mapOf(ActivityKind.FARM to 2000L, ActivityKind.LUMBER to 480L, ActivityKind.MINE to 720L))
                every { workday() } returns ru.ruscrafting.farms.network.WorkdayState(completed = setOf(ActivityKind.FARM))
                every { canNavigate(any()) } returns true
                every { canAccess(any(), any()) } returns true
                every { isAvailable(any()) } returns true
            }
            ArcFarmsMenu(menus, service, locale) { settings }.open(player)
            exportShopScreen("main", capture.last!!)
            capture.last!!.buttons.first { it.id.value == "farm" }.label.color()!!.value() shouldBe 0x92bed8
        } finally { paper.close() }
    }
})

private class ShopDialogCapture : FarmDialogDisplay {
    var last: PaperDialogScreen? = null
    override fun show(player: Player, screen: PaperDialogScreen) { last = screen }
    override fun close(player: Player) = Unit
    override fun close() = Unit
}

private fun exportShopScreen(name: String, screen: PaperDialogScreen) {
    val directory = System.getProperty("arcfarms.dialogExportDirectory")?.let(Path::of) ?: return
    Files.createDirectories(directory)
    val serializer = LegacyComponentSerializer.builder().character('§').hexColors().build()
    val text = screen.body.joinToString("\n\n") { serializer.serialize(it.text) }
    check(text.any { it.code in 0xE570..0xE59F }) { "Expected actual ARC table texture output; fallback is not a preview" }
    Files.writeString(directory.resolve("$name-body.txt"), text)
    Files.writeString(directory.resolve("$name-title.txt"), serializer.serialize(screen.title))
    Files.writeString(directory.resolve("$name-buttons.txt"), screen.buttons.joinToString("\n") { serializer.serialize(it.label) })
}
