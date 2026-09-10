package ru.ruscrafting.farms.paper.worksite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import java.util.UUID

class WorksiteGuidancePresenterMockBukkitTest : FunSpec({
    val playerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val world = mockk<World>()
    val player = mockk<Player>()
    every { player.uniqueId } returns playerId
    every { player.isOnline } returns true
    every { player.world } returns world
    every { player.location } returns Location(world, 0.0, 64.0, 0.0)

    test("a participant receives an immediate title plus a stalled reminder and only nearest loaded target per role") {
        val audience = mockk<WorksiteAudiencePort>(relaxed = true)
        val access = mockk<WorksiteAccessPort>()
        every { access.isAdminEditing(player) } returns false
        val logRole = ObjectiveTargetRole("log")
        val cartRole = ObjectiveTargetRole("cart")
        val logColor = Color.fromRGB(80, 180, 90)
        val cartColor = Color.fromRGB(230, 160, 60)
        val view = WorksiteGuidanceView(
            runtimeKey = "lumber:sawmill",
            progressVersion = 7L,
            title = Component.text("Carry the log"),
            subtitle = Component.text("Follow the green column"),
            barName = Component.text("Felling 1/3"),
            barProgress = 1f / 3f,
            barColor = BossBar.Color.GREEN,
            targets = listOf(
                WorksiteGuidanceTarget("unloaded", logRole, Location(world, 1.0, 64.0, 0.0), logColor, loaded = false),
                WorksiteGuidanceTarget("near-log", logRole, Location(world, 2.0, 64.0, 0.0), logColor),
                WorksiteGuidanceTarget("far-log", logRole, Location(world, 9.0, 64.0, 0.0), logColor),
                WorksiteGuidanceTarget("cart", cartRole, Location(world, 0.0, 64.0, 3.0), cartColor),
            ),
        )
        val source = guidanceSource(player, view)
        val presenter = WorksiteGuidancePresenter(audience, access, source, stallMillis = 12_000L)

        presenter.updateHud(now = 1_000L)
        presenter.updateHud(now = 13_001L)

        verify(exactly = 2) { audience.showScreenTitle(player, view.title, view.subtitle) }
        verify(exactly = 2) {
            audience.updateBar(player, view.runtimeKey, view.barName, view.barProgress, view.barColor, any())
        }

        presenter.emitParticles()

        verify(exactly = 8) {
            audience.spawnGuidanceDust(player, match { it.blockX == 2 && it.blockZ == 0 }, logColor, 1.1f)
        }
        verify(exactly = 8) {
            audience.spawnGuidanceDust(player, match { it.blockX == 0 && it.blockZ == 3 }, cartColor, 1.1f)
        }
        verify(exactly = 0) {
            audience.spawnGuidanceDust(player, match { it.blockX == 1 || it.blockX == 9 }, any(), any())
        }
    }

    test("accepted progress produces a fresh title and restarts the reminder timer") {
        val audience = mockk<WorksiteAudiencePort>(relaxed = true)
        val access = mockk<WorksiteAccessPort>()
        every { access.isAdminEditing(player) } returns false
        var view = basicView(progressVersion = 1L)
        val source = object : WorksiteGuidanceSource {
            override fun participants(): Collection<Player> = listOf(player)
            override fun view(playerId: UUID): WorksiteGuidanceView = view
        }
        val presenter = WorksiteGuidancePresenter(audience, access, source, stallMillis = 12_000L)

        presenter.updateHud(1_000L)
        view = basicView(progressVersion = 2L)
        presenter.updateHud(13_001L)
        presenter.updateHud(25_002L)

        verify(exactly = 3) { audience.showScreenTitle(player, view.title, view.subtitle) }
    }

    test("quiet mining updates HUD without repeating progress or idle titles") {
        val audience = mockk<WorksiteAudiencePort>(relaxed = true)
        val access = mockk<WorksiteAccessPort>()
        every { access.isAdminEditing(player) } returns false
        var view = basicView().copy(quietProgress = true)
        val source = object : WorksiteGuidanceSource {
            override fun participants() = listOf(player)
            override fun view(playerId: UUID) = view
        }
        val presenter = WorksiteGuidancePresenter(audience, access, source)
        presenter.updateHud(1_000L)
        repeat(3) {
            view = view.copy(progressVersion = view.progressVersion + 1)
            presenter.updateHud(20_000L * (it + 1))
        }
        presenter.updateHud(100_000L)
        verify(exactly = 1) { audience.showScreenTitle(player, any<Component>(), any<Component>()) }
        view = view.copy(progressVersion = 10, subtitle = Component.text("New order"))
        presenter.updateHud(101_000L)
        verify(exactly = 2) { audience.showScreenTitle(player, any<Component>(), any<Component>()) }
    }

    test("release forgets the session and removes every player worksite bar") {
        val audience = mockk<WorksiteAudiencePort>(relaxed = true)
        val access = mockk<WorksiteAccessPort>()
        every { access.isAdminEditing(player) } returns false
        val presenter = WorksiteGuidancePresenter(audience, access, guidanceSource(player, basicView()), 12_000L)

        presenter.updateHud(1_000L)
        presenter.sessionCount shouldBe 1
        presenter.releasePlayer(player)

        presenter.sessionCount shouldBe 0
        verify(exactly = 1) { audience.removePlayerBars(player) }
    }
})

private fun guidanceSource(player: Player, view: WorksiteGuidanceView): WorksiteGuidanceSource =
    object : WorksiteGuidanceSource {
        override fun participants(): Collection<Player> = listOf(player)
        override fun view(playerId: UUID): WorksiteGuidanceView = view
    }

private fun basicView(progressVersion: Long = 1L): WorksiteGuidanceView = WorksiteGuidanceView(
    runtimeKey = "mine:deep",
    progressVersion = progressVersion,
    title = Component.text("Mine the marked ore"),
    subtitle = Component.text("Follow the column"),
    barName = Component.text("Mining 0/4"),
    barProgress = 0f,
    barColor = BossBar.Color.PURPLE,
)
