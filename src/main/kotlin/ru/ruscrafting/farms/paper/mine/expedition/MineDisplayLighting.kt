package ru.ruscrafting.farms.paper.mine.expedition

import org.bukkit.Material
import org.bukkit.entity.Display

/** Readable underground machinery without the former full daylight override. */
internal object MineDisplayLighting {
    fun brightness(material: Material, activeSignal: Boolean = false): Display.Brightness =
        Display.Brightness(if (material == Material.SEA_LANTERN || activeSignal) 14 else 11, 0)
}
