package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.block.BlockGrowEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.world.ChunkLoadEvent

class ArcFarmsListenerContractTest : FunSpec({
    test("farm-owned interactions run before WorldGuard protection feedback") {
        handler("onInteractLowest", PlayerInteractEvent::class.java).priority shouldBe EventPriority.LOWEST
        handler("onBreakLowest", BlockBreakEvent::class.java).priority shouldBe EventPriority.LOWEST
    }

    test("farm ground spread is cancelled before another plugin can commit it") {
        val handler = handler("onBlockSpread", BlockSpreadEvent::class.java)

        handler.priority shouldBe EventPriority.LOWEST
        handler.ignoreCancelled shouldBe true
    }

    test("natural fixed crop growth is cancelled before the world changes") {
        val handler = handler("onBlockGrow", BlockGrowEvent::class.java)

        handler.priority shouldBe EventPriority.LOWEST
        handler.ignoreCancelled shouldBe true
    }

    test("contract scene reconciliation runs as soon as a chunk becomes available") {
        handler("onChunkLoad", ChunkLoadEvent::class.java).priority shouldBe EventPriority.LOWEST
    }

    test("teleports and portals cannot bypass farm exit cleanup") {
        listOf(
            handler("onTeleport", PlayerTeleportEvent::class.java),
            handler("onPortal", PlayerPortalEvent::class.java),
        ).forEach { handler ->
            handler.priority shouldBe EventPriority.MONITOR
            handler.ignoreCancelled shouldBe true
        }
    }
})

private fun handler(name: String, eventType: Class<*>): EventHandler =
    requireNotNull(ArcFarmsListener::class.java.getDeclaredMethod(name, eventType).getAnnotation(EventHandler::class.java))
