package ru.ruscrafting.farms.paper.farm.presentation

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.Ageable
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.paper.FarmHarvestCropIndex
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules

internal data class FarmHarvestGuidanceTarget(
    val crop: Material,
    val position: FarmPlotPosition,
    val color: Color,
)

/** Resolves player-specific harvest targets strictly from the durable block indexes. */
internal class FarmHarvestGuidance(
    private val index: FarmHarvestCropIndex,
) {
    fun targets(runtime: FarmRuntime, origin: Location): List<FarmHarvestGuidanceTarget> {
        if (runtime.state.phase != FarmPhase.HARVESTING) return emptyList()
        val order = runtime.state.orderId?.let(runtime.orders::get) ?: return emptyList()
        val required = order.required.asSequence()
            .filter { (crop, quota) -> (runtime.state.progress[crop] ?: 0) < quota }
            .map { (crop) -> MaterialRules.material(crop) }
            .toSet()
        if (required.isEmpty()) return emptyList()

        val nearest = mutableMapOf<Material, Candidate>()
        index.beds(runtime.settings.id).forEach { soil ->
            val world = Bukkit.getWorld(soil.world)?.takeIf { it === origin.world } ?: return@forEach
            if (!world.isChunkLoaded(soil.x shr 4, soil.z shr 4)) return@forEach
            val crop = world.getBlockAt(soil.x, soil.y + 1, soil.z)
            val material = crop.type.takeIf(required::contains) ?: return@forEach
            val age = crop.blockData as? Ageable ?: return@forEach
            if (age.age != age.maximumAge) return@forEach
            retainNearest(nearest, material, soil, origin)
        }
        index.fixedCrops(runtime.settings.id).forEach { position ->
            val world = Bukkit.getWorld(position.world)?.takeIf { it === origin.world } ?: return@forEach
            if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) return@forEach
            val material = world.getBlockAt(position.x, position.y, position.z).type
            if (material !in required || !MaterialRules.isFixedBlockCrop(material)) return@forEach
            retainNearest(nearest, material, position, origin)
        }
        return nearest.entries.sortedBy { it.key.name }.map { (crop, candidate) ->
            FarmHarvestGuidanceTarget(crop, candidate.position, color(crop))
        }
    }

    private fun retainNearest(
        nearest: MutableMap<Material, Candidate>,
        crop: Material,
        position: FarmPlotPosition,
        origin: Location,
    ) {
        val dx = position.x + 0.5 - origin.x
        val dy = position.y + 0.5 - origin.y
        val dz = position.z + 0.5 - origin.z
        val candidate = Candidate(position, dx * dx + dy * dy + dz * dz)
        val current = nearest[crop]
        if (current == null || candidate.distanceSquared < current.distanceSquared ||
            candidate.distanceSquared == current.distanceSquared && POSITION_ORDER.compare(position, current.position) < 0
        ) {
            nearest[crop] = candidate
        }
    }

    private fun color(crop: Material): Color = when (crop) {
        Material.WHEAT -> Color.fromRGB(255, 213, 79)
        Material.CARROTS -> Color.fromRGB(255, 143, 48)
        Material.POTATOES -> Color.fromRGB(183, 139, 91)
        Material.BEETROOTS -> Color.fromRGB(220, 67, 89)
        Material.SWEET_BERRY_BUSH -> Color.fromRGB(210, 70, 170)
        Material.MELON -> Color.fromRGB(103, 194, 92)
        Material.PUMPKIN -> Color.fromRGB(245, 116, 32)
        else -> error("Unsupported indexed farm crop: $crop")
    }

    private data class Candidate(val position: FarmPlotPosition, val distanceSquared: Double)

    private companion object {
        val POSITION_ORDER = compareBy<FarmPlotPosition>(FarmPlotPosition::y, FarmPlotPosition::x, FarmPlotPosition::z)
    }
}
