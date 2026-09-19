package ru.ruscrafting.farms.paper.worksite

import org.bukkit.Color
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display

/** Copies the real block shape, including directional buds, rails and fences. */
internal object WorksiteBlockGlow {
    fun apply(display: BlockDisplay, data: BlockData, color: Color) = with(display) {
        block = data.clone()
        isPersistent = false
        isGlowing = true
        glowColorOverride = color
        brightness = Display.Brightness(15, 15)
        viewRange = 8f
        transformation = transformation.also {
            it.translation.set(-0.001f, -0.001f, -0.001f)
            it.scale.set(1.002f)
        }
    }
}
