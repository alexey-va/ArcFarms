package ru.ruscrafting.farms.paper.lumber

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.LumbermillSettings
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.lumber.*
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.lumber.scene.LumberPresentation
import ru.ruscrafting.farms.paper.lumber.scene.LumberSceneGeometry
import ru.ruscrafting.farms.paper.worksite.*
import java.util.concurrent.CompletableFuture

class LumberCartControllerTest : FunSpec({
    test("native display clicks use the same beta gate and durable pickup") {
        MockBukkitTestRuntime.open().use { paper ->
            val f = HaulingFixture(paper)
            val denied = f.player.addAttachment(paper.createSimplePlugin("LumberDenied"), LumbermillSettings.BETA_PERMISSION, false)
            f.controller.onDisplayInteract(f.player) shouldBe false
            f.controller.state.cartCarrier shouldBe null
            f.player.removeAttachment(denied)
            f.controller.onDisplayInteract(f.player) shouldBe true
            f.controller.state.cartCarrier shouldBe f.player.uniqueId
            requireNotNull(f.player.getAttribute(Attribute.MOVEMENT_SPEED)).value shouldBe (.055 plusOrMinus .00001)
        }
    }

    test("the log follows its carrier through decorative obstacles without changing terrain") {
        MockBukkitTestRuntime.open().use { paper ->
            val f = HaulingFixture(paper)
            f.world.getBlockAt(150, 73, 196).type = Material.STONE
            f.takeLog()
            val initial = requireNotNull(f.controller.state.cart)
            f.controller.updateVisuals()
            val moved = requireNotNull(f.controller.state.cart)
            (moved.z > initial.z) shouldBe true
            (moved.z < f.player.location.z - 5.0) shouldBe true
            f.world.getBlockAt(150, 73, 196).type shouldBe Material.STONE
            f.controller.state.cartCarrier shouldBe f.player.uniqueId
            f.controller.state.phase shouldBe LumberPhase.HAULING
        }
    }

    test("entering the outdoor square receives a misaligned trailing log without moving it first") {
        MockBukkitTestRuntime.open().use { paper ->
            val center = LumberSceneGeometry.receivingCenter()
            val log = center.copy(z = center.z + 8.0, yaw = 47f)
            val f = HaulingFixture(paper, log = log,
                playerAt = center.copy(x = center.x - 2.8, z = center.z + 2.8, yaw = -90f))
            f.takeLog()
            f.controller.updateVisuals()
            f.controller.state.phase shouldBe LumberPhase.LOADING_SAWMILL
            f.controller.state.cart shouldBe log
            f.controller.state.cartCarrier shouldBe null
            requireNotNull(f.player.getAttribute(Attribute.MOVEMENT_SPEED)).value shouldBe (.1 plusOrMinus .00001)
            val accepted = f.controller.state
            f.controller.updateVisuals()
            f.controller.state shouldBe accepted
        }
    }

    test("hauling slows only its holder and releasing preserves later base speed changes") {
        MockBukkitTestRuntime.open().use { paper ->
            val f = HaulingFixture(paper)
            val speed = requireNotNull(f.player.getAttribute(Attribute.MOVEMENT_SPEED))
            f.controller.onMove(f.player)
            f.controller.view(f.player.uniqueId)?.subtitle shouldBe Component.text("lumber.hints.hauling")
            f.takeLog()
            speed.value shouldBe (.055 plusOrMinus .00001)
            f.controller.updateGuidance(mutableSetOf())
            f.now += 12_001L
            f.controller.updateGuidance(mutableSetOf())
            f.titles shouldBe listOf(Component.text("lumber.hints.hauling-held"))
            speed.baseValue = .2
            f.controller.releasePlayer(f.player)
            speed.value shouldBe (.2 plusOrMinus .00001)
            f.controller.state.cartCarrier shouldBe null
        }
    }
})

private class HaulingFixture(paper: MockBukkitTestRuntime,
    log: LumberPose = LumberPose(LumbermillSettings().world, 150.5, 73.0, 196.5),
    playerAt: LumberPose = log.copy(z = log.z + 7.0),
) {
    val rules = LumbermillSettings()
    val world = paper.server.addSimpleWorld(rules.world)
    val player = paper.server.addPlayer().apply {
        teleport(Location(world, playerAt.x, playerAt.y, playerAt.z, playerAt.yaw, 0f))
        addAttachment(paper.createSimplePlugin("LumberHauling"), LumbermillSettings.BETA_PERMISSION, true)
        requireNotNull(getAttribute(Attribute.MOVEMENT_SPEED)).baseValue = .1
    }
    var now = 1_000L
    val titles = mutableListOf<Component>()
    private val token = mockk<RuntimeTaskSupervisor.Token>()
    private val tasks = mockk<WorksiteTaskPort>(relaxed = true) {
        every { lifecycleToken() } returns token
        every { runSync(token, any()) } answers { secondArg<() -> Unit>().invoke(); true }
    }
    private val locale = mockk<ArcFarmsLocale>(relaxed = true) {
        every { render(any<MessageKey>(), any(), any()) } answers { Component.text(firstArg<MessageKey>().path) }
    }
    private val audience = mockk<WorksiteAudiencePort>(relaxed = true) {
        every { showScreenTitle(player, any<Component>(), any<Component>()) } answers { titles += thirdArg<Component>() }
    }
    val controller = LumberController(rules, "spawn", locale,
        mockk<WorksiteAccessPort>(relaxed = true) { every { allowInteraction(any(), any()) } returns true }, audience,
        mockk<WorksiteStatePort>(relaxed = true) { every { persistAsync() } returns CompletableFuture.completedFuture(Unit) },
        tasks, mockk(relaxed = true),
        mockk<LumberPresentation>(relaxed = true) { every { target(player) } returns LumberInput(LumberAction.TAKE_CART) },
        LumberState(roundId = 1, phase = LumberPhase.HAULING, cart = log),
        { now }, mockk(relaxed = true), mockk(relaxed = true),
    ).also { it.activate() }

    fun takeLog() {
        controller.onInteract(mockk<PlayerInteractEvent>(relaxed = true) {
            every { this@mockk.player } returns this@HaulingFixture.player
            every { hand } returns EquipmentSlot.HAND
            every { action } returns Action.RIGHT_CLICK_AIR
        }) shouldBe true
        controller.state.cartCarrier shouldBe player.uniqueId
    }
}
