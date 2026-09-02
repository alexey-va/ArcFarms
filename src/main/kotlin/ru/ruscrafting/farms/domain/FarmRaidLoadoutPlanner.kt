package ru.ruscrafting.farms.domain

data class FarmRaidInventorySlot(
    val occupied: Boolean,
    val serviceItemId: String?,
) {
    companion object {
        val EMPTY = FarmRaidInventorySlot(occupied = false, serviceItemId = null)
    }
}

data class FarmRaidInventorySwap(val first: Int, val second: Int)
data class FarmRaidInventoryMove(val from: Int, val to: Int)
data class FarmRaidInventoryIssue(val itemId: String, val slot: Int)

data class FarmRaidLoadoutPlan(
    val swaps: List<FarmRaidInventorySwap>,
    val moves: List<FarmRaidInventoryMove>,
    val issues: List<FarmRaidInventoryIssue>,
)

/** Plans an atomic, lossless two-item raid loadout for hotbar slots 0 and 1. */
object FarmRaidLoadoutPlanner {
    fun plan(slots: List<FarmRaidInventorySlot>, requiredItemIds: List<String>): FarmRaidLoadoutPlan? {
        require(slots.size == INVENTORY_SLOT_COUNT)
        require(requiredItemIds.size == RAID_SLOT_COUNT)
        require(requiredItemIds.distinct().size == requiredItemIds.size)
        val state = slots.toMutableList()
        val swaps = mutableListOf<FarmRaidInventorySwap>()
        val moves = mutableListOf<FarmRaidInventoryMove>()
        val issues = mutableListOf<FarmRaidInventoryIssue>()

        requiredItemIds.forEachIndexed { targetSlot, itemId ->
            if (state[targetSlot].serviceItemId == itemId) return@forEachIndexed
            val existing = state.indices.firstOrNull { state[it].serviceItemId == itemId }
            if (existing != null) {
                val displaced = state[targetSlot]
                state[targetSlot] = state[existing]
                state[existing] = displaced
                swaps += FarmRaidInventorySwap(existing, targetSlot)
                return@forEachIndexed
            }
            if (state[targetSlot].occupied) {
                val empty = EMPTY_SLOT_ORDER.firstOrNull { !state[it].occupied } ?: return null
                state[empty] = state[targetSlot]
                state[targetSlot] = FarmRaidInventorySlot.EMPTY
                moves += FarmRaidInventoryMove(targetSlot, empty)
            }
            state[targetSlot] = FarmRaidInventorySlot(occupied = true, serviceItemId = itemId)
            issues += FarmRaidInventoryIssue(itemId, targetSlot)
        }
        return FarmRaidLoadoutPlan(swaps, moves, issues)
    }

    private const val INVENTORY_SLOT_COUNT = 37
    private const val RAID_SLOT_COUNT = 2
    private val EMPTY_SLOT_ORDER = (9..35).toList() + (2..8).toList() + 36
}
