package ru.ruscrafting.farms.paper.farm.incident.processing

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmProcessingLayout
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Player-only placement preview; it never creates an entity or changes the world. */
internal class FarmProcessingAdminPreview(private val port: WorksiteRuntimePort) {
    fun show(runtime: FarmRuntime, anchor: FarmPointPosition, player: Player) {
        val layout = FarmProcessingLayout.create(anchor)
        layout.footprint.filter { point ->
            val local = localCoordinates(anchor, point)
            abs(local.first) >= 3.9 || abs(local.second) >= 1.9
        }.forEach { point ->
            port.spawnGuidanceDust(
                player,
                point.location(runtime).add(0.0, 0.15, 0.0),
                Color.fromRGB(255, 178, 36),
                1.0f,
            )
        }
        listOf(
            layout.inputRack to Color.fromRGB(91, 184, 255),
            layout.machine to Color.fromRGB(255, 178, 36),
            layout.outputPallet to Color.fromRGB(92, 214, 116),
        ).forEach { (point, color) ->
            var height = 0.3
            while (height <= 2.7) {
                port.spawnGuidanceDust(player, point.location(runtime).add(0.0, height, 0.0), color, 1.15f)
                height += 0.6
            }
        }
    }

    private fun localCoordinates(anchor: FarmPointPosition, point: FarmPointPosition): Pair<Double, Double> {
        val radians = Math.toRadians(anchor.yaw.toDouble())
        val deltaX = point.x - anchor.x
        val deltaZ = point.z - anchor.z
        val right = deltaX * cos(radians) + deltaZ * sin(radians)
        val forward = deltaX * -sin(radians) + deltaZ * cos(radians)
        return right to forward
    }

    private fun FarmPointPosition.location(runtime: FarmRuntime): Location =
        Location(runtime.region.world, x, y, z, yaw, pitch)
}
