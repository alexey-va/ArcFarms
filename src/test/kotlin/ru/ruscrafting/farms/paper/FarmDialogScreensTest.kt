package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.event.inventory.ClickType
import org.bukkit.entity.Player
import ru.arc.menu.MenuElementId
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files

class FarmDialogScreensTest : FunSpec({
    test("default DIALOG shows confirmation terms and dispatches an action only once") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val plugin = paper.createSimplePlugin("FarmDialogScreensTest")
            copyConfig(plugin)
            val capture = CapturingDialog()
            val menus = ArcFarmsMenuPlatform(plugin, capture)
            val player = paper.addPlayer("DialogFarmer")
            var dispatches = 0
            menus.open(player, ArcFarmsMenuPlatform.ENTERPRISE_CONFIRM) {
                FarmMenuContent(
                    title = Component.text("Confirm"),
                    elements = mapOf(
                        MenuElementId.of("confirm") to FarmMenuEntry(
                            menus.item(ArcFarmsMenuPlatform.ENTERPRISE_CONFIRM, MenuElementId.of("confirm"), Component.text("Buy"), listOf(Component.text("Price 100"))),
                            acceptedClicks = setOf(ClickType.LEFT),
                            onClick = FarmMenuClickHandler { dispatches++ },
                        ),
                        MenuElementId.of("back") to FarmMenuEntry(
                            menus.item(ArcFarmsMenuPlatform.ENTERPRISE_CONFIRM, MenuElementId.of("back"), Component.text("Back"), emptyList()),
                        ),
                    ),
                )
            }
            menus.session(player)!!.inventory shouldBe null
            capture.last!!.title.color()?.value() shouldBe 0xf4bd6a
            capture.last!!.buttons.first().label.color()?.value() shouldBe 0x92bed8
            capture.last!!.body.joinToString(" ") { it.text.toString() } shouldContain "Price 100"
            val confirm = capture.last!!.buttons.first { it.id.value == "confirm" }
            val context = mockk<PaperDialogClickContext>(relaxed = true)
            confirm.onClick.handle(context)
            confirm.onClick.handle(context)
            dispatches shouldBe 1

            val old = capture.last!!
            menus.replace(menus.prepareReload())
            old.buttons.first { it.id.value == "confirm" }.onClick.handle(context)
            dispatches shouldBe 1
        } finally {
            paper.close()
        }
    }

    test("pending transition is invalidated when the dialog session closes") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val plugin = paper.createSimplePlugin("FarmDialogTransitionTest")
            copyConfig(plugin)
            val menus = ArcFarmsMenuPlatform(plugin, CapturingDialog())
            val player = paper.addPlayer("DialogTransition")
            val session = menus.open(player, ArcFarmsMenuPlatform.ENTERPRISE_CONFIRM) {
                FarmMenuContent(title = Component.text("x"))
            }
            var dispatched = 0
            menus.transition(player, session) { dispatched++ } shouldBe true
            menus.close(player)
            paper.server.scheduler.performTicks(2)
            dispatched shouldBe 0
        } finally {
            paper.close()
        }
    }

    test("farm report groups information and keeps the selected action visible") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val plugin = paper.createSimplePlugin("FarmDialogDetailTest")
            copyConfig(plugin)
            val capture = CapturingDialog()
            val menus = ArcFarmsMenuPlatform(plugin, capture)
            val player = paper.addPlayer("DialogDetail")
            menus.open(player, ArcFarmsMenuPlatform.ENTERPRISE_FARM) {
                FarmMenuContent(
                    title = Component.text("Report"),
                    elements = mapOf(
                        MenuElementId.of("report") to FarmMenuEntry(menus.item(ArcFarmsMenuPlatform.ENTERPRISE_FARM, MenuElementId.of("report"), Component.text("Report"), listOf(Component.text("Revenue 100"))), enabled = false),
                        MenuElementId.of("workers") to FarmMenuEntry(menus.item(ArcFarmsMenuPlatform.ENTERPRISE_FARM, MenuElementId.of("workers"), Component.text("Workers"), listOf(Component.text("Workers 4"))), enabled = false),
                        MenuElementId.of("policy") to FarmMenuEntry(menus.item(ArcFarmsMenuPlatform.ENTERPRISE_FARM, MenuElementId.of("policy"), Component.text("Policy"), listOf(Component.text("Plan steady"))), enabled = false),
                        MenuElementId.of("license") to FarmMenuEntry(menus.item(ArcFarmsMenuPlatform.ENTERPRISE_FARM, MenuElementId.of("license"), Component.text("License"), listOf(Component.text("12 weeks"))), enabled = false),
                        MenuElementId.of("back") to FarmMenuEntry(menus.item(ArcFarmsMenuPlatform.ENTERPRISE_CONFIRM, MenuElementId.of("back"), Component.text("Back"), emptyList())),
                    ),
                )
            }
            capture.last!!.buttons.map { it.id.value } shouldContain "details"
            capture.last!!.buttons.first { it.id.value == "details" }.onClick.handle(mockk<PaperDialogClickContext>(relaxed = true))
            capture.last!!.buttons.single().id.value shouldBe "detail_back"
            listOf("Revenue 100", "Workers 4", "Plan steady", "12 weeks").forEach {
                capture.last!!.bodyText() shouldContain it
            }
            capture.last!!.buttons.single().onClick.handle(mockk<PaperDialogClickContext>(relaxed = true))
            capture.last!!.buttons.map { it.id.value } shouldContain "details"
        } finally {
            paper.close()
        }
    }

    test("disabled share action exposes an explanation and money details remain visible") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val plugin = paper.createSimplePlugin("FarmDialogMoneyTest")
            copyConfig(plugin)
            val capture = CapturingDialog()
            val menus = ArcFarmsMenuPlatform(plugin, capture)
            val player = paper.addPlayer("DialogMoney")
            menus.open(player, ArcFarmsMenuPlatform.ENTERPRISE_SHARES) {
                FarmMenuContent(
                    title = Component.text("Shares"),
                    elements = mapOf(
                        MenuElementId.of("status") to FarmMenuEntry(menus.item(ArcFarmsMenuPlatform.ENTERPRISE_SHARES, MenuElementId.of("status"), Component.text("Status"), listOf(Component.text("Issued 20 / 100"))), enabled = false),
                        MenuElementId.of("holding") to FarmMenuEntry(menus.item(ArcFarmsMenuPlatform.ENTERPRISE_SHARES, MenuElementId.of("holding"), Component.text("Holding"), listOf(Component.text("Owned 7 / 100"))), enabled = false),
                        MenuElementId.of("account") to FarmMenuEntry(menus.item(ArcFarmsMenuPlatform.ENTERPRISE_SHARES, MenuElementId.of("account"), Component.text("Account"), listOf(Component.text("Balance 1,234¢"))), enabled = false),
                        MenuElementId.of("withdraw") to FarmMenuEntry(menus.item(ArcFarmsMenuPlatform.ENTERPRISE_SHARES, MenuElementId.of("withdraw"), Component.text("Withdraw"), listOf(Component.text("Unavailable"))), enabled = false),
                        MenuElementId.of("back") to FarmMenuEntry(menus.item(ArcFarmsMenuPlatform.ENTERPRISE_SHARES, MenuElementId.of("back"), Component.text("Back"), emptyList())),
                    ),
                )
            }
            capture.last!!.bodyText() shouldContain "Issued 20 / 100"
            capture.last!!.bodyText() shouldContain "Owned 7 / 100"
            capture.last!!.bodyText() shouldContain "Balance 1,234¢"
            capture.last!!.buttons.map { it.id.value } shouldContain "info_withdraw"
            capture.last!!.buttons.first { it.id.value == "info_withdraw" }.onClick.handle(mockk<PaperDialogClickContext>(relaxed = true))
            capture.last!!.exitButton!!.id.value shouldBe "detail_close"
            capture.last!!.exitButton!!.onClick.handle(mockk<PaperDialogClickContext>(relaxed = true))
            menus.session(player) shouldBe null
        } finally {
            paper.close()
        }
    }

    test("perk offer opens terms before purchase and dispatches buy once") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val plugin = paper.createSimplePlugin("FarmDialogPerkTest")
            copyConfig(plugin)
            val capture = CapturingDialog()
            val menus = ArcFarmsMenuPlatform(plugin, capture)
            val player = paper.addPlayer("DialogPerk")
            var purchases = 0
            menus.open(player, ArcFarmsMenuPlatform.FARM_PERKS) {
                FarmMenuContent(
                    title = Component.text("Perks"),
                    elements = mapOf(
                        MenuElementId.of("balance") to FarmMenuEntry(
                            menus.item(ArcFarmsMenuPlatform.FARM_PERKS, MenuElementId.of("balance"), Component.text("Points"), emptyList()),
                            enabled = false,
                        ),
                    ),
                    regions = mapOf(
                        ArcFarmsMenuPlatform.PERK_OFFERS to listOf(
                            FarmMenuEntry(
                                menus.item("perk-speed", Component.text("Speed"), listOf(Component.text("Price 25"), Component.text("Duration 6 hours"))),
                                acceptedClicks = setOf(ClickType.LEFT),
                                onClick = FarmMenuClickHandler { purchases++ },
                            ),
                        ),
                    ),
                )
            }
            val offer = capture.last!!.buttons.first { it.id.value == "offers_0" }
            offer.onClick.handle(mockk<PaperDialogClickContext>(relaxed = true))
            purchases shouldBe 0
            capture.last!!.bodyText() shouldContain "Price 25"
            capture.last!!.bodyText() shouldContain "Duration 6 hours"
            val buy = capture.last!!.buttons.first { it.id.value == "buy_perk" }
            buy.onClick.handle(mockk<PaperDialogClickContext>(relaxed = true))
            buy.onClick.handle(mockk<PaperDialogClickContext>(relaxed = true))
            purchases shouldBe 1
        } finally {
            paper.close()
        }
    }
})

private class CapturingDialog : FarmDialogDisplay {
    var last: PaperDialogScreen? = null
    override fun show(player: Player, screen: PaperDialogScreen) { last = screen }
    override fun close(player: Player) = Unit
    override fun close() = Unit
}

private fun PaperDialogScreen.bodyText(): String = body.joinToString(" ") { it.text.toString() }

private fun copyConfig(plugin: org.bukkit.plugin.Plugin) {
    val source = requireNotNull(FarmDialogScreensTest::class.java.classLoader.getResourceAsStream("config.yml"))
    Files.createDirectories(plugin.dataFolder.toPath())
    source.use { Files.copy(it, plugin.dataFolder.toPath().resolve("config.yml")) }
}
