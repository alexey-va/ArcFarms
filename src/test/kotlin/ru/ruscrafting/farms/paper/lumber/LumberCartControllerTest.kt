package ru.ruscrafting.farms.paper.lumber

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.util.BoundingBox
import ru.arc.paper.display.DisplayOrientedVolume
import ru.arc.paper.scene.PaperSpatialSpace
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
    test("the carrier can translate out of the recorded stuck pose without the rejected turn") {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = CartFixture(paper)
            val leaf = BoundingBox(154.0, 73.0, 194.0, 155.0, 74.0, 195.0)
            every { fixture.space.clear(fixture.world, any<DisplayOrientedVolume>(), null) } answers {
                !secondArg<DisplayOrientedVolume>().overlaps(leaf)
            }
            fixture.takeCart()
            val from = requireNotNull(fixture.controller.state.cart)
            val at = fixture.player.location
            val frame = WorksiteCartMotion.Frame(from.x, from.y, from.z, from.yaw - 180f, 0f,
                at.x, at.z, 1.0, 0.0)
            val moved = WorksiteCartMotion.advance(frame, at.x, at.y, at.z, at.yaw, LumberSceneGeometry.CART_FOLLOW_DISTANCE)
            val proposed = LumberPose(from.world, moved.x, moved.y, moved.z, moved.yaw + 180f)
            fixture.clearance.allows(from, proposed) shouldBe false
            fixture.clearance.allows(from, proposed.copy(yaw = from.yaw)) shouldBe true

            fixture.controller.updateVisuals()

            fixture.controller.state.cart shouldBe proposed.copy(yaw = from.yaw)
            fixture.controller.state.cartCarrier shouldBe fixture.player.uniqueId
            fixture.controller.state.phase shouldBe LumberPhase.HAULING
            fixture.world.entities.filterNot { it is Player } shouldBe emptyList()
        }
    }

    test("holding a cart replaces the pickup hint and does not repeat screen titles while hauling") {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = CartFixture(paper)
            fixture.controller.onMove(fixture.player)
            fixture.controller.view(fixture.player.uniqueId)?.subtitle shouldBe Component.text("lumber.hints.hauling")
            fixture.takeCart()
            fixture.controller.updateGuidance(mutableSetOf())
            fixture.now += 12_001L
            fixture.controller.updateGuidance(mutableSetOf())
            fixture.now += 12_001L
            fixture.controller.updateGuidance(mutableSetOf())

            fixture.titles shouldBe listOf(Component.text("lumber.hints.hauling-held"))
            fixture.controller.view(fixture.player.uniqueId)?.quietProgress shouldBe true
        }
    }

    test("blocked movement retains the cart but consumes carrier input and shows a recovery hint") {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = CartFixture(paper)
            fixture.takeCart()
            val from = requireNotNull(fixture.controller.state.cart)
            every { fixture.space.clear(fixture.world, any<DisplayOrientedVolume>(), null) } returns false
            fixture.controller.updateVisuals()
            fixture.player.teleport(fixture.player.location.add(.3, 0.0, 0.0))
            fixture.controller.updateVisuals()
            fixture.controller.state.cart shouldBe from
            verify { fixture.audience.sendActionBar(fixture.player, MessageKey.LUMBER_CART_BLOCKED, any()) }

            // Looking around while stationary must not replay the previously rejected walking input.
            fixture.player.teleport(fixture.player.location.apply { yaw = 0f })
            every { fixture.space.clear(fixture.world, any<DisplayOrientedVolume>(), null) } returns true
            val at = fixture.player.location
            val frame = WorksiteCartMotion.Frame(from.x, from.y, from.z, from.yaw - 180f, 0f,
                at.x, at.z, 1.0, 0.0)
            val moved = WorksiteCartMotion.advance(frame, at.x, at.y, at.z, at.yaw, LumberSceneGeometry.CART_FOLLOW_DISTANCE)
            fixture.controller.updateVisuals()
            fixture.controller.state.cart shouldBe LumberPose(from.world, moved.x, moved.y, moved.z, moved.yaw + 180f)

            fixture.controller.releasePlayer(fixture.player)
            fixture.controller.onMove(fixture.player)
            fixture.controller.state.cartCarrier shouldBe null
            fixture.controller.view(fixture.player.uniqueId)?.subtitle shouldBe Component.text("lumber.hints.hauling")
            fixture.controller.view(fixture.player.uniqueId)?.quietProgress shouldBe false
        }
    }
})

private class CartFixture(paper: MockBukkitTestRuntime) {
    val rules = LumbermillSettings()
    val world = paper.server.addSimpleWorld(rules.world)
    // Last documented southward escape attempt in protocol-evidence-4.json.
    val player = paper.server.addPlayer().apply {
        teleport(Location(world, 154.67, 73.0, 201.71, -90f, 0f))
        addAttachment(paper.createSimplePlugin("LumberCart"), LumbermillSettings.BETA_PERMISSION, true)
    }
    var now = 1_000L
    val titles = mutableListOf<Component>()
    val space = mockk<PaperSpatialSpace> {
        every { clear(world, any<DisplayOrientedVolume>(), null) } returns true
    }
    val clearance = LumberCartClearance(space)
    private val token = mockk<RuntimeTaskSupervisor.Token>()
    private val tasks = mockk<WorksiteTaskPort>(relaxed = true) {
        every { lifecycleToken() } returns token
        every { runSync(token, any()) } answers { secondArg<() -> Unit>().invoke(); true }
    }
    private val locale = mockk<ArcFarmsLocale>(relaxed = true) {
        every { render(any<MessageKey>(), any(), any()) } answers { Component.text(firstArg<MessageKey>().path) }
    }
    val audience = mockk<WorksiteAudiencePort>(relaxed = true) {
        every { showScreenTitle(player, any<Component>(), any<Component>()) } answers { titles += thirdArg<Component>() }
    }
    val controller = LumberController(rules, "spawn", locale,
        mockk<WorksiteAccessPort>(relaxed = true) { every { allowInteraction(any(), any()) } returns true }, audience,
        mockk<WorksiteStatePort>(relaxed = true) { every { persistAsync() } returns CompletableFuture.completedFuture(Unit) },
        tasks, mockk(relaxed = true),
        mockk<LumberPresentation>(relaxed = true) { every { target(player) } returns LumberInput(LumberAction.TAKE_CART) },
        LumberState(roundId = 1, phase = LumberPhase.HAULING,
            cart = LumberPose(rules.world, 157.77768236673901, 73.0, 197.8592397503807, -56.086792f)),
        { now }, mockk(relaxed = true), mockk(relaxed = true), clearance,
    ).also { it.activate() }

    fun takeCart() {
        now += LumberTiming.CART_LOAD_TICKS * 50L
        controller.onInteract(mockk<PlayerInteractEvent>(relaxed = true) {
            every { this@mockk.player } returns this@CartFixture.player
            every { hand } returns EquipmentSlot.HAND
            every { action } returns Action.RIGHT_CLICK_AIR
        }) shouldBe true
        controller.state.cartCarrier shouldBe player.uniqueId
    }
}
