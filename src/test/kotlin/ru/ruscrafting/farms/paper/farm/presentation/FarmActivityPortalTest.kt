package ru.ruscrafting.farms.paper.farm.presentation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.*
import ru.ruscrafting.farms.paper.fixtures.*

class FarmActivityPortalTest : FunSpec({
    test("shared portal renders off-phase and invokes only the current completed entry") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val runtime = fixture.runtime(FarmShiftState(phase = FarmPhase.INCIDENT,
                sequence = 903, orderId = "bakery_supply", incidentType = FarmIncidentType.FOOD_DELIVERY))
            val portals = FarmActivityPortal(fixture.plugin, fixture.locale, fixture.port,
                fixture.port, fixture.port, MockBukkitFarmTextDisplays, "test_portal")
            val point = FarmPointPosition(fixture.world.name, 12.5, 65.0, 12.5)
            var available = false
            var attempts = 0
            var arrivals = 0
            portals.ensure(runtime, point, FarmPortalStyle(3.6f, 3.0f, 3.35, 1f, 3, 2f),
                MessageKey.FARM_ROUTE_PORTAL_LABEL, object : FarmPortalDestination {
                    override fun enter(player: Player): Boolean {
                        attempts++
                        if (!available) return false
                        arrivals++
                        return true
                    }
                })
            val player = fixture.paper.addPlayer("PortalVisitor")
            val inside = fixture.location(point)
            val outside = inside.clone().add(5.0, 0.0, 0.0)
            player.teleport(outside)
            // PlayerMoveEvent destination is new while player.location can still be old.
            portals.enter(player, inside) shouldBe true
            verify(exactly = 1) { fixture.port.showScreenTitle(player,
                MessageKey.FARM_ACTIVITY_PORTAL_COUNTDOWN, any(), "activity_portal") }
            player.teleport(inside)
            fixture.runDelayedTasks() shouldBe listOf(20L)
            portals.enter(player, outside) shouldBe false
            portals.enter(player, inside) shouldBe true
            // The old callback must neither finish nor shorten the new countdown.
            fixture.runDelayedTasks() shouldBe listOf(20L, 20L)
            attempts shouldBe 0
            fixture.runDelayedTasks() shouldBe listOf(20L)
            fixture.runDelayedTasks() shouldBe listOf(20L)
            attempts shouldBe 1
            arrivals shouldBe 0
            available = true
            fixture.runDelayedTasks() shouldBe listOf(20L)
            arrivals shouldBe 1
            fixture.runDelayedTasks() shouldBe emptyList()

            mockkObject(FarmPortalRenderer)
            try {
                every { FarmPortalRenderer.render(any(), any()) } just Runs
                fixture.paper.performTicks(1)
                portals.update(true)
                fixture.paper.performTicks(5)
                portals.update(true)
                verify(exactly = 2) { FarmPortalRenderer.render(any(), match { player in it }) }
            } finally { unmockkObject(FarmPortalRenderer) }
            portals.enter(player, inside) shouldBe true
            portals.clear(runtime.settings.id)
            fixture.runDelayedTasks() shouldBe listOf(20L)
            arrivals shouldBe 1
            fixture.world.entities.count(portals::owns) shouldBe 0
        } }
    }
})
