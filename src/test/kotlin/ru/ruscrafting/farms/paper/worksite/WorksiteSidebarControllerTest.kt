package ru.ruscrafting.farms.paper.worksite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Score
import org.bukkit.scoreboard.Scoreboard
import org.bukkit.scoreboard.ScoreboardManager
import java.util.UUID

class WorksiteSidebarControllerTest : FunSpec({
    test("incrementally shrinks and grows duplicate blank rows on one board") {
        val fake = SidebarMocks()
        try {
            val controller = WorksiteSidebarController()
            val blank = Component.empty()
            val title = Component.text("Farm")
            val first = listOf(blank, blank, Component.text("Harvest"))
            controller.update(fake.player, "farm:communal_farm", title, first, replaceExisting = true)
            controller.update(fake.player, "farm:communal_farm", title, listOf(Component.text("Harvest")), true)
            controller.update(fake.player, "farm:communal_farm", title, listOf(blank, Component.text("Harvest"), blank, Component.text("Next")), true)

            fake.currentBoard shouldBe fake.workBoard
            verify(exactly = 1) { fake.manager.newScoreboard }
            verify { fake.workBoard.resetScores("§1") }
            verify { fake.workBoard.resetScores("§2") }
            verify { fake.score("§0").customName(blank) }
            verify { fake.score("§1").customName(Component.text("Harvest")) }
            verify { fake.score("§2").customName(blank) }
            verify { fake.score("§3").customName(Component.text("Next")) }
        } finally {
            unmockkAll()
            fake.paper.close()
        }
    }

    test("farm cleanup is scoped while mine remains and remove restores the previous board") {
        val fake = SidebarMocks()
        try {
            val controller = WorksiteSidebarController()
            controller.update(fake.player, "farm:communal_farm", Component.text("Farm"), listOf(Component.text("Field")), true)
            controller.update(fake.player, "mine:mine1", Component.text("Mine"), listOf(Component.text("Ore")), true)

            controller.remove(fake.player, owner = "farm")
            fake.currentBoard shouldBe fake.workBoard
            controller.reconcile("farm", emptySet())
            fake.currentBoard shouldBe fake.workBoard
            controller.remove(fake.player, owner = "mine")
            fake.currentBoard shouldBe fake.previousBoard
        } finally {
            unmockkAll()
            fake.paper.close()
        }
    }

    test("reconcile and restoreAll restore previous board, and replaceExisting false yields") {
        val fake = SidebarMocks(existingSidebar = true)
        try {
            val controller = WorksiteSidebarController()
            controller.update(fake.player, "farm:communal_farm", Component.text("Ignored"), listOf(Component.text("Ignored")), false)
            fake.currentBoard shouldBe fake.previousBoard
            verify(exactly = 0) { fake.manager.newScoreboard }

            controller.update(fake.player, "farm:communal_farm", Component.text("Farm"), listOf(Component.text("Field")), true)
            controller.reconcile("farm", emptySet())
            fake.currentBoard shouldBe fake.previousBoard

            controller.update(fake.player, "mine:mine1", Component.text("Mine"), listOf(Component.text("Ore")), true)
            controller.restoreAll()
            fake.currentBoard shouldBe fake.previousBoard
        } finally {
            unmockkAll()
            fake.paper.close()
        }
    }
})

private class SidebarMocks(existingSidebar: Boolean = false) {
    val paper = ru.arc.paper.testing.MockBukkitTestRuntime.open()
    private val initializedCriteria = Criteria.DUMMY
    val manager = mockk<ScoreboardManager>(relaxed = true)
    val previousBoard = mockk<Scoreboard>(relaxed = true)
    val workBoard = mockk<Scoreboard>(relaxed = true)
    private val objective = mockk<Objective>(relaxed = true)
    private val scores = mutableMapOf<String, Score>()
    val player = mockk<Player>(relaxed = true)
    val playerId = UUID.randomUUID()
    var currentBoard: Scoreboard = previousBoard

    init {
        mockkStatic(Bukkit::class)
        every { Bukkit.getScoreboardManager() } returns manager
        every { manager.newScoreboard } returns workBoard
        every { Bukkit.getPlayer(playerId) } returns player
        every { player.uniqueId } returns playerId
        every { player.scoreboard } answers { currentBoard }
        every { player.scoreboard = any() } answers { currentBoard = firstArg() }
        every { previousBoard.getObjective(DisplaySlot.SIDEBAR) } returns if (existingSidebar) objective else null
        every { workBoard.getObjective("arcfarms_work") } returns objective
        every { workBoard.registerNewObjective("arcfarms_work", any<Criteria>(), any<Component>()) } returns objective
        every { objective.displaySlot = DisplaySlot.SIDEBAR } just runs
        every { objective.numberFormat(any()) } just runs
        every { objective.displayName() } returns Component.text("Farm")
        every { objective.displayName(any()) } just runs
        every { workBoard.resetScores(any<String>()) } just runs
        every { objective.getScore(any<String>()) } answers { scores.getOrPut(firstArg()) { mockk(relaxed = true) } }
    }

    fun score(entry: String): Score = scores.getValue(entry)
}
