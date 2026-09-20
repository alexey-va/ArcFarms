package ru.ruscrafting.farms.paper.mine.expedition

import org.bukkit.entity.BlockDisplay
import org.bukkit.util.Transformation

/** Restart interpolation only with a new pose, never on an unchanged parked casting. */
internal object MineDisplayPose {
    fun apply(display: BlockDisplay, desired: Transformation) {
        if (display.transformation == desired) return
        display.interpolationDelay = 0
        display.transformation = desired
    }
}
