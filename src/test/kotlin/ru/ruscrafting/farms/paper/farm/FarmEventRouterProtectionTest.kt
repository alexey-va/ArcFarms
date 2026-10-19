package ru.ruscrafting.farms.paper.farm

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.event.block.BlockBreakEvent
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.farm.admin.FarmWorldAdminService
import ru.ruscrafting.farms.paper.farm.harvest.FarmHarvestController
import ru.ruscrafting.farms.paper.farm.incident.farmEventRouter
import ru.ruscrafting.farms.paper.farm.incident.special.FarmSpecialIncidentController
import ru.ruscrafting.farms.paper.fixtures.FarmIncidentScenarioFixture

class FarmEventRouterProtectionTest : FunSpec({
    test("denied farm breaking preserves protection and offers the edit hint only within its region") {
        FarmIncidentScenarioFixture.open().use { fixture ->
            val runtime = fixture.runtime(FarmShiftState())
            val player = fixture.paper.addPlayer("FarmBuilder")
            val admin = mockk<FarmWorldAdminService>(relaxed = true)
            val router = farmEventRouter(fixture, runtime, worldAdmin = admin)
            val block = fixture.world.getBlockAt(8, 64, 8).also { it.type = Material.STONE }
            val denied = BlockBreakEvent(block, player)

            router.onBreakLowest(denied)
            denied.isCancelled shouldBe true
            router.onBreakHigh(denied) shouldBe true
            denied.isCancelled shouldBe true
            block.type shouldBe Material.STONE
            verify(exactly = 1) { admin.remindEdit(player) }

            val outside = BlockBreakEvent(fixture.world.getBlockAt(80, 64, 80), player)
            router.onBreakLowest(outside)
            router.onBreakHigh(outside) shouldBe false
            outside.isCancelled shouldBe false
            verify(exactly = 1) { admin.remindEdit(player) }
        }
    }

    test("accepted harvests and incident actions do not suggest leaving gameplay for edit mode") {
        FarmIncidentScenarioFixture.open().use { fixture ->
            val runtime = fixture.runtime(FarmShiftState())
            val player = fixture.paper.addPlayer("FarmHarvester")
            val admin = mockk<FarmWorldAdminService>(relaxed = true)
            val harvest = mockk<FarmHarvestController>(relaxed = true)
            val special = mockk<FarmSpecialIncidentController>(relaxed = true)
            val router = farmEventRouter(fixture, runtime, worldAdmin = admin, harvest = harvest, special = special)
            val block = fixture.world.getBlockAt(8, 64, 8).also { it.type = Material.WHEAT }
            for (fixedCrop in listOf(false, true)) {
                every { harvest.onBreak(any(), runtime) } answers {
                    firstArg<BlockBreakEvent>().apply {
                        isCancelled = fixedCrop
                        isDropItems = false
                    }
                }
                val event = BlockBreakEvent(block, player)
                router.onBreakLowest(event)
                router.onBreakHigh(event) shouldBe true
                event.isCancelled shouldBe fixedCrop
            }
            verify(exactly = 0) { admin.remindEdit(any()) }

            clearMocks(harvest, answers = false)
            every { special.handleChannelBreak(runtime, player, block) } returns true
            router.onBreakHigh(BlockBreakEvent(block, player)) shouldBe true
            verify(exactly = 0) { harvest.onBreak(any(), any()) }
            verify(exactly = 0) { admin.remindEdit(any()) }
        }
    }
})
