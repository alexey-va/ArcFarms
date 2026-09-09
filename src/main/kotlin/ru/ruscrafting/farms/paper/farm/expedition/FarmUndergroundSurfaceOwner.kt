package ru.ruscrafting.farms.paper.farm.expedition

import org.bukkit.Location
import org.bukkit.Color
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.FarmCareVisualSettings
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayRenderer

/** Common surface entry owner composed by every underground expedition type. */
internal class FarmUndergroundSurfaceOwner(
    settings: () -> ArcFarmsConfig,
    locale: ArcFarmsLocale,
    textDisplays: FarmTextDisplayRenderer,
) {
    private val marker = FarmUndergroundEntryMarker(settings, locale, textDisplays)

    fun candidates(
        beds: Collection<FarmPlotPosition>,
        minimumBoundaryDistance: Int,
        candidateAttempts: Int,
        salt: Long,
        hasRegionClearance: (FarmPlotPosition, Int) -> Boolean = { _, _ -> true },
    ): List<FarmPointPosition> = FarmUndergroundEntrySelection.candidates(
        beds, minimumBoundaryDistance, candidateAttempts, salt, hasRegionClearance,
    )

    fun spawnEntry(
        runtime: FarmRuntime,
        location: Location,
        visual: FarmCareVisualSettings,
        labelPath: String,
        glowing: Boolean,
        mark: (Entity) -> Unit,
    ): List<Entity> = marker.spawn(runtime, location, visual, labelPath, glowing, mark)

    fun renderPillar(player: Player, base: Location, color: Color, markerHeight: Int) =
        FarmUndergroundSurfacePillar.render(player, base, color, markerHeight)
}
