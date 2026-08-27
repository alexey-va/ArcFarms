package ru.ruscrafting.farms.paper.farm.care

import org.bukkit.entity.ItemDisplay
import ru.ruscrafting.farms.config.FarmCareVisualSettings
import ru.ruscrafting.farms.config.FarmItemDisplayTransform

/** Keeps ItemsAdder display identity and Bukkit transform mapping out of the care state controller. */
internal fun FarmCareVisualSettings.matches(display: ItemDisplay): Boolean {
    val stack = display.itemStack
    return stack.type.name == material &&
        (customModelData <= 0 || stack.itemMeta.customModelData == customModelData)
}

internal val FarmItemDisplayTransform.bukkit: ItemDisplay.ItemDisplayTransform
    get() = when (this) {
        FarmItemDisplayTransform.GROUND -> ItemDisplay.ItemDisplayTransform.GROUND
        FarmItemDisplayTransform.FIXED -> ItemDisplay.ItemDisplayTransform.FIXED
        FarmItemDisplayTransform.HEAD -> ItemDisplay.ItemDisplayTransform.HEAD
    }
