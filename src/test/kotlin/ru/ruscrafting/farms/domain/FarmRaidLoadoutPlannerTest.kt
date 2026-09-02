package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmRaidLoadoutPlannerTest : FunSpec({
    test("occupied first slots move into storage before both raid weapons are issued") {
        val slots = MutableList(37) { FarmRaidInventorySlot.EMPTY }
        slots[0] = FarmRaidInventorySlot(occupied = true, serviceItemId = null)
        slots[1] = FarmRaidInventorySlot(occupied = true, serviceItemId = null)

        val plan = FarmRaidLoadoutPlanner.plan(slots, listOf("raid_gun", "raid_grenade_launcher"))

        requireNotNull(plan)
        plan.moves shouldBe listOf(FarmRaidInventoryMove(0, 9), FarmRaidInventoryMove(1, 10))
        plan.issues shouldBe listOf(
            FarmRaidInventoryIssue("raid_gun", 0),
            FarmRaidInventoryIssue("raid_grenade_launcher", 1),
        )
    }

    test("existing raid weapons are swapped into the first two slots without losing occupied items") {
        val slots = MutableList(37) { FarmRaidInventorySlot.EMPTY }
        slots[0] = FarmRaidInventorySlot(occupied = true, serviceItemId = null)
        slots[1] = FarmRaidInventorySlot(occupied = true, serviceItemId = null)
        slots[15] = FarmRaidInventorySlot(occupied = true, serviceItemId = "raid_gun")
        slots[16] = FarmRaidInventorySlot(occupied = true, serviceItemId = "raid_grenade_launcher")

        val plan = FarmRaidLoadoutPlanner.plan(slots, listOf("raid_gun", "raid_grenade_launcher"))

        requireNotNull(plan)
        plan.swaps shouldBe listOf(FarmRaidInventorySwap(15, 0), FarmRaidInventorySwap(16, 1))
        plan.moves shouldBe emptyList()
        plan.issues shouldBe emptyList()
    }

    test("a full inventory rejects boarding without planning any destructive move") {
        val slots = List(37) { FarmRaidInventorySlot(occupied = true, serviceItemId = null) }

        FarmRaidLoadoutPlanner.plan(slots, listOf("raid_gun", "raid_grenade_launcher")) shouldBe null
    }
})
