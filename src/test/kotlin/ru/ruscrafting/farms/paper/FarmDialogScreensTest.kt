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

    test("informational detail has a back action that restores the screen") {
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
                        MenuElementId.of("report") to FarmMenuEntry(menus.item(ArcFarmsMenuPlatform.ENTERPRISE_FARM, MenuElementId.of("report"), Component.text("Info"), listOf(Component.text("Terms"))), enabled = false),
                        MenuElementId.of("back") to FarmMenuEntry(menus.item(ArcFarmsMenuPlatform.ENTERPRISE_CONFIRM, MenuElementId.of("back"), Component.text("Back"), emptyList())),
                    ),
                )
            }
            capture.last!!.buttons.single().onClick.handle(mockk<PaperDialogClickContext>(relaxed = true))
            capture.last!!.buttons.single().id.value shouldBe "detail_back"
            capture.last!!.buttons.single().onClick.handle(mockk<PaperDialogClickContext>(relaxed = true))
            capture.last!!.buttons.map { it.id.value } shouldContain "info_report"
            capture.last!!.buttons.single().onClick.handle(mockk<PaperDialogClickContext>(relaxed = true))
            capture.last!!.exitButton!!.id.value shouldBe "detail_close"
            capture.last!!.exitButton!!.onClick.handle(mockk<PaperDialogClickContext>(relaxed = true))
            menus.session(player) shouldBe null
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

private fun copyConfig(plugin: org.bukkit.plugin.Plugin) {
    val source = requireNotNull(FarmDialogScreensTest::class.java.classLoader.getResourceAsStream("config.yml"))
    Files.createDirectories(plugin.dataFolder.toPath())
    source.use { Files.copy(it, plugin.dataFolder.toPath().resolve("config.yml")) }
}
