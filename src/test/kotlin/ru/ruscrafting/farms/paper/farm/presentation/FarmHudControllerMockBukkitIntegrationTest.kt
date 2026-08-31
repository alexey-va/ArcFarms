package ru.ruscrafting.farms.paper.farm.presentation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ActivityBarKey
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmScoreboardPort
import ru.ruscrafting.farms.paper.FarmScoreboardView
import ru.ruscrafting.farms.paper.farm.delivery.FarmDeliveryController
import ru.ruscrafting.farms.paper.farm.harvest.FarmHarvestController
import ru.ruscrafting.farms.paper.farm.incident.route.FarmFoodDeliveryIncident
import ru.ruscrafting.farms.paper.farm.incident.special.FarmSpecialIncidentController
import ru.ruscrafting.farms.paper.fixtures.FarmIncidentScenarioFixture
import ru.ruscrafting.farms.paper.fixtures.requiredMockBukkitScenario
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort

class FarmHudControllerMockBukkitIntegrationTest : FunSpec({
    test("a dismounted food-delivery participant keeps the route bar and scoreboard until the incident releases them") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val player = fixture.paper.addPlayer("WalkingEscort")
            val runtime = fixture.runtime(
                FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 91,
                    orderId = "bakery_supply",
                    incidentType = FarmIncidentType.FOOD_DELIVERY,
                    incidentCrop = "WHEAT",
                    incidentProgress = 7,
                    incidentRequired = 20,
                ),
            )
            val scoreboards = RecordingScoreboards()
            val audience = mockk<WorksiteAudiencePort>(relaxed = true)
            val access = mockk<WorksiteAccessPort>()
            val delivery = mockk<FarmDeliveryController>()
            val foodDelivery = mockk<FarmFoodDeliveryIncident>()
            every { audience.players(runtime.region) } returns emptyList()
            every { access.isAdminEditing(player) } returns false
            every { delivery.isCarrying(runtime.settings.id, player.uniqueId) } returns false
            every { foodDelivery.participants(runtime) } returns listOf(player)
            every { audience.updateBar(any(), any(), any(), any(), any(), any()) } answers {
                val recipient = firstArg<Player>()
                val runtimeKey = secondArg<String>()
                arg<MutableSet<ActivityBarKey>>(5) += ActivityBarKey(recipient.uniqueId, runtimeKey)
            }
            val hud = FarmHudController(
                settings = { fixture.settings },
                locale = fixture.locale,
                debug = ArcFarmsDebug({ false }) {},
                access = access,
                audience = audience,
                tasks = mockk<WorksiteTaskPort>(relaxed = true),
                delivery = delivery,
                foodDelivery = foodDelivery,
                special = mockk<FarmSpecialIncidentController>(relaxed = true),
                harvest = mockk<FarmHarvestController>(relaxed = true),
                clock = { 10_000L },
                scoreboards = scoreboards,
            )

            hud.update(listOf(runtime)) shouldBe mutableSetOf(ActivityBarKey(player.uniqueId, "farm:${runtime.settings.id}"))
            hud.active(player.uniqueId) shouldBe true
            hud.title(player.uniqueId).isNotBlank() shouldBe true
            hud.line(player.uniqueId, 1).isNotBlank() shouldBe true
            scoreboards.views.getValue(player.uniqueId).done shouldBe 7

            every { foodDelivery.participants(runtime) } returns emptyList()

            hud.update(listOf(runtime)) shouldBe emptySet()
            hud.active(player.uniqueId) shouldBe false
            verify(exactly = 1) {
                audience.updateBar(player, "farm:${runtime.settings.id}", any(), 7f / 20f, any(), any())
            }
        } }
    }
})

private class RecordingScoreboards : FarmScoreboardPort {
    val views = mutableMapOf<java.util.UUID, FarmScoreboardView>()

    override fun update(player: Player, zoneId: String, view: FarmScoreboardView) {
        views[player.uniqueId] = view
    }

    override fun active(playerId: java.util.UUID): Boolean = playerId in views

    override fun tabTitle(playerId: java.util.UUID): String = views[playerId]?.orderId.orEmpty()

    override fun tabLine(playerId: java.util.UUID, line: Int): String =
        views[playerId]?.let { "${it.done}/${it.total}" }.orEmpty()

    override fun reconcile(expected: Set<java.util.UUID>) {
        views.keys.retainAll(expected)
    }

    override fun remove(player: Player, reason: String) {
        views.remove(player.uniqueId)
    }

    override fun restoreAll(reason: String) {
        views.clear()
    }
}
