package ru.ruscrafting.farms.paper.farm.expedition

import org.bukkit.entity.Entity
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.FarmCareVisualSettings
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.farm.FarmFieldPoiVisibility
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayRenderer
import org.bukkit.Location
import ru.ruscrafting.farms.paper.worksite.WorksiteEntryMarker

/** Owns the common visible, clickable surface entrance marker. */
internal class FarmUndergroundEntryMarker(
    settings: () -> ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    private val textDisplays: FarmTextDisplayRenderer,
) {
    private val marker = WorksiteEntryMarker(locale, textDisplays)

    fun spawn(
        runtime: FarmRuntime,
        location: Location,
        visual: FarmCareVisualSettings,
        labelPath: String,
        glowing: Boolean,
        mark: (Entity) -> Unit,
    ): List<Entity> {
        val viewRange = FarmFieldPoiVisibility.fullField(runtime.settings.displayViewRange)
        return marker.spawn(location, visual, labelPath, glowing, viewRange, mark)
    }
}
