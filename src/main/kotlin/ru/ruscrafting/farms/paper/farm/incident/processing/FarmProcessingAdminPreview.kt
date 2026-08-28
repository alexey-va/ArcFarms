package ru.ruscrafting.farms.paper.farm.incident.processing

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmProcessingLayout
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort

/** Player-only placement preview; it never creates an entity or changes the world. */
internal class FarmProcessingAdminPreview(private val port: WorksiteRuntimePort) {
    fun show(runtime: FarmRuntime, layout: FarmProcessingLayout, player: Player) {
        (layout.inputRacks.map { it to Color.fromRGB(91, 184, 255) } + listOf(
            layout.machine to Color.fromRGB(255, 178, 36),
            layout.outputPallet to Color.fromRGB(92, 214, 116),
        )).forEach { (point, color) ->
            var height = 0.3
            while (height <= 2.7) {
                port.spawnGuidanceDust(player, point.location(runtime).add(0.0, height, 0.0), color, 1.15f)
                height += 0.6
            }
        }
    }

    private fun FarmPointPosition.location(runtime: FarmRuntime): Location =
        Location(runtime.region.world, x, y, z, yaw, pitch)
}
