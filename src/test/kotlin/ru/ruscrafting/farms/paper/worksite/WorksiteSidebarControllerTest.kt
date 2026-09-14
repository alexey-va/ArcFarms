package ru.ruscrafting.farms.paper.worksite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import ru.arc.paper.api.ArcSidebarFrame
import ru.arc.paper.api.ArcSidebarHandle
import java.util.UUID

class WorksiteSidebarControllerTest : FunSpec({
    test("publishes exact dynamic rows including duplicate blanks") {
        val sidebar = RecordingSidebar()
        val player = player()
        val controller = WorksiteSidebarController(sidebar)
        val rows = listOf(Component.empty(), Component.empty(), Component.text("Harvest"))

        controller.update(player, "farm:communal_farm", Component.text("Farm"), rows, replaceExisting = false)

        sidebar.frames.getValue(player.uniqueId).rows shouldContainExactly rows
    }

    test("farm cleanup is scoped while mine remains") {
        val sidebar = RecordingSidebar()
        val player = player()
        val controller = WorksiteSidebarController(sidebar)
        controller.update(player, "farm:communal_farm", Component.text("Farm"), listOf(Component.text("Field")), false)
        controller.update(player, "mine:mine1", Component.text("Mine"), listOf(Component.text("Ore")), false)

        controller.remove(player, owner = "farm")
        sidebar.frames.containsKey(player.uniqueId) shouldBe true
        controller.remove(player, owner = "mine")
        sidebar.frames.containsKey(player.uniqueId) shouldBe false
    }

    test("reconcile and restoreAll release stale claims") {
        val sidebar = RecordingSidebar()
        val first = player()
        val second = player()
        val controller = WorksiteSidebarController(sidebar)
        controller.update(first, "farm:a", Component.text("Farm"), listOf(Component.text("A")), false)
        controller.update(second, "farm:b", Component.text("Farm"), listOf(Component.text("B")), false)

        controller.reconcile("farm", setOf(first.uniqueId))
        sidebar.frames.keys shouldContainExactly listOf(first.uniqueId)
        controller.restoreAll()
        sidebar.frames.isEmpty() shouldBe true
    }
})

private class RecordingSidebar : ArcSidebarHandle {
    val frames = linkedMapOf<UUID, ArcSidebarFrame>()

    override fun show(player: Player, frame: ArcSidebarFrame) {
        frames[player.uniqueId] = frame
    }

    override fun hide(playerId: UUID) {
        frames.remove(playerId)
    }

    override fun close() {
        frames.clear()
    }
}

private fun player(): Player = mockk<Player>().also { player ->
    every { player.uniqueId } returns UUID.randomUUID()
}
