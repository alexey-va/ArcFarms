package ru.ruscrafting.farms.paper

internal object FarmServiceInventoryPolicy {
    fun cancelClick(
        playerCraftingView: Boolean,
        rawSlot: Int,
        topSize: Int,
        shiftClick: Boolean,
        currentTagged: Boolean,
        cursorTagged: Boolean,
        hotbarTagged: Boolean,
    ): Boolean {
        if (!currentTagged && !cursorTagged && !hotbarTagged) return false
        if (!playerCraftingView) return true
        val clickedTop = rawSlot in 0 until topSize
        return (clickedTop && (currentTagged || cursorTagged || hotbarTagged)) || (shiftClick && currentTagged)
    }

    fun cancelDrag(cursorTagged: Boolean, rawSlots: Set<Int>, topSize: Int): Boolean =
        cursorTagged && rawSlots.any { it in 0 until topSize }
}
