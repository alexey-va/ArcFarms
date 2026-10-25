package ru.ruscrafting.farms.paper.lumber.sawmill

import com.sk89q.worldguard.bukkit.WorldGuardPlugin
import com.sk89q.worldguard.bukkit.cause.Cause
import com.sk89q.worldguard.bukkit.event.entity.UseEntityEvent
import com.sk89q.worldguard.bukkit.listener.RegionProtectionListener
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Interaction
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import ru.arc.paper.testing.MockBukkitTestRuntime

class CedarSawmillPublicUseTest : FunSpec({
    test("a normal visitor's valid owned lever is allowed before WorldGuard's region denial without executing the action") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("CedarSawmillPublicUse")
            val player = paper.server.addPlayer("Visitor")
            val target = player.world.spawn(player.location, Interaction::class.java)
            val controller = mockk<CedarSawmillController>(relaxed = true)
            every { controller.allowsPublicUse(player, target) } returns true
            val original = PlayerInteractEntityEvent(player, target, EquipmentSlot.HAND)
            val event = UseEntityEvent(original, Cause.create(player), target)
            CedarSawmillPublicUse(plugin, controller).use { bridge ->
                bridge.onUseEntity(event)
                event.result shouldBe Event.Result.ALLOW
                // Exact 7.0.16 handler returns before its region query and denial message for ALLOW.
                RegionProtectionListener(mockk<WorldGuardPlugin>(relaxed = true)).onUseEntity(event)
                original.isCancelled shouldBe false
                player.nextMessage() shouldBe null
                verify(exactly = 1) { controller.allowsPublicUse(player, target) }
                confirmVerified(controller)
            }
        }
    }

    test("a stale unrelated or physically rejected carrier retains WorldGuard's ordinary decision") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("CedarSawmillRejectedUse")
            val player = paper.server.addPlayer("Visitor")
            val target = player.world.spawn(player.location, Interaction::class.java)
            val controller = mockk<CedarSawmillController>(relaxed = true)
            every { controller.allowsPublicUse(player, target) } returns false
            val original = PlayerInteractEntityEvent(player, target, EquipmentSlot.HAND)
            val event = UseEntityEvent(original, Cause.create(player), target)
            CedarSawmillPublicUse(plugin, controller).use { bridge -> bridge.onUseEntity(event) }
            event.result shouldBe Event.Result.DEFAULT
            original.isCancelled shouldBe false
            verify(exactly = 1) { controller.allowsPublicUse(player, target) }
            confirmVerified(controller)
        }
    }

    test("offhand prior cancellation mismatched entity and mismatched cause never reach the public-use predicate") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("CedarSawmillUseBoundary")
            val player = paper.server.addPlayer("Visitor")
            val other = paper.server.addPlayer("OtherVisitor")
            val target = player.world.spawn(player.location, Interaction::class.java)
            val unrelated = player.world.spawn(player.location, Interaction::class.java)
            val controller = mockk<CedarSawmillController>(relaxed = true)
            val offhand = PlayerInteractEntityEvent(player, target, EquipmentSlot.OFF_HAND)
            val cancelled = PlayerInteractEntityEvent(player, target, EquipmentSlot.HAND).apply { isCancelled = true }
            val mainhand = PlayerInteractEntityEvent(player, target, EquipmentSlot.HAND)
            val events = listOf(
                UseEntityEvent(offhand, Cause.create(player), target),
                UseEntityEvent(cancelled, Cause.create(player), target),
                UseEntityEvent(mainhand, Cause.create(player), unrelated),
                UseEntityEvent(mainhand, Cause.create(other), target),
                UseEntityEvent(mainhand, Cause.create(player), target).apply { isCancelled = true },
            )
            CedarSawmillPublicUse(plugin, controller).use { bridge -> events.forEach(bridge::onUseEntity) }
            events.dropLast(1).all { it.result == Event.Result.DEFAULT } shouldBe true
            events.last().result shouldBe Event.Result.DENY
            verify(exactly = 0) { controller.allowsPublicUse(any(), any()) }
        }
    }

    test("the bridge precedes WorldGuard's NORMAL delegate handler and unregisters during scene cleanup") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("CedarSawmillUseCleanup")
            val bridge = CedarSawmillPublicUse(plugin, mockk(relaxed = true))
            val annotation = CedarSawmillPublicUse::class.java
                .getDeclaredMethod("onUseEntity", UseEntityEvent::class.java).getAnnotation(EventHandler::class.java)
            annotation.priority shouldBe EventPriority.LOWEST
            annotation.ignoreCancelled shouldBe true
            UseEntityEvent.getHandlerList().registeredListeners.any { it.listener === bridge } shouldBe true
            bridge.close()
            bridge.close()
            UseEntityEvent.getHandlerList().registeredListeners.none { it.listener === bridge } shouldBe true
        }
    }
})
