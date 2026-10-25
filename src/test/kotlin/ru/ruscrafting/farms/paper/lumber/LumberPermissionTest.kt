package ru.ruscrafting.farms.paper.lumber

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerInteractEvent
import ru.ruscrafting.farms.config.LumbermillSettings
import ru.ruscrafting.farms.paper.lumber.scene.LumberPresentation

class LumberPermissionTest : FunSpec({
    test("missing beta permission exits every event capability before reading scene or inventory or mutating cancellation") {
        val player = mockk<Player> { every { hasPermission(LumbermillSettings.BETA_PERMISSION) } returns false }
        val scene = mockk<LumberPresentation>()
        val items = mockk<ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems>()
        val controller = LumberController(LumbermillSettings(), "spawn", mockk(), mockk(), mockk(), mockk(), mockk(), items, scene)
        val breaking = mockk<BlockBreakEvent>(relaxed = true) { every { this@mockk.player } returns player }
        val damage = mockk<BlockDamageEvent>(relaxed = true) { every { this@mockk.player } returns player }
        val interact = mockk<PlayerInteractEvent>(relaxed = true) { every { this@mockk.player } returns player }
        val swing = mockk<PlayerAnimationEvent>(relaxed = true) { every { this@mockk.player } returns player }
        controller.onBreakLowest(breaking) shouldBe false
        controller.onBreakHigh(breaking) shouldBe false
        controller.onDamage(damage) shouldBe false
        controller.onInteract(interact) shouldBe false
        controller.onSwing(swing) shouldBe false
        verify { scene wasNot Called; items wasNot Called }
        verify(exactly = 0) { breaking.isCancelled = any(); damage.isCancelled = any(); interact.isCancelled = any() }
        verify(exactly = 0) { player.inventory }
    }
})
