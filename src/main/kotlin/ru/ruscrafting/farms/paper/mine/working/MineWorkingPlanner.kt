package ru.ruscrafting.farms.paper.mine.working

import org.bukkit.Material
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MineWorkingPlacement
import ru.ruscrafting.farms.domain.worksite.WorksitePosition

/** Pure candidate and final-snapshot checks for a lateral working. */
internal object MineWorkingPlanner {
    const val MAX_BLOCKS = 8_000

    fun rejection(plan: MineWorkingPlan, typeAt: (WorksitePosition) -> Material?): String? {
        MineWorkingLayout.validate(plan).firstOrNull()?.let { return it }
        plan.shell.forEach { position ->
            val material = typeAt(position) ?: return "unknown_shell"
            if (!isNaturalVolume(material)) return "shell_not_geological"
        }
        plan.fixtures.forEach { position ->
            val material = typeAt(position) ?: return "unknown_fixture"
            if (!isNaturalVolume(material)) return "fixture_not_geological"
        }
        plan.walkable.asSequence()
            .forEach { position ->
                val material = typeAt(position) ?: return "unknown_new_volume"
                val depth = distanceAlong(plan.placement, position)
                if (depth <= ENTRY_AIR_DEPTH) {
                    val overheadLamp = position.y >= plan.entrance.y + 3 &&
                        material in setOf(Material.LANTERN, Material.IRON_CHAIN)
                    if (!material.isAir && !overheadLamp) return "entry_not_clear"
                } else if (!isGeological(material) && !material.isAir) {
                    return "new_volume_not_geological"
                }
            }
        plan.cartRoute.forEach { position ->
            val material = typeAt(position) ?: return "unknown_track_volume"
            if (distanceAlong(plan.placement, position) <= ENTRY_AIR_DEPTH) {
                if (!material.isAir) return "track_entry_not_clear"
            } else if (!isGeological(material) && !material.isAir) {
                return "track_volume_not_geological"
            }
        }
        if (plan.type == MineIncidentType.TUNNEL_DRIVE) {
            plan.excavation.forEach { position ->
                val material = typeAt(position) ?: return "unknown_excavation"
                if (!isGeological(material) && !material.isAir) return "excavation_not_geological"
            }
        }
        plan.stations.values.forEach { position ->
            val material = typeAt(position) ?: return "unknown_station"
            if (!isNaturalVolume(material)) return "station_not_geological"
        }
        val surface = plan.placement.position(0, 0, -1)
        val surfaceGround = typeAt(surface) ?: return "unknown_surface"
        if (!isGeological(surfaceGround)) return "surface_not_geological"
        (1..3).forEach { up ->
            val material = typeAt(surface.copy(y = surface.y + up)) ?: return "unknown_surface_clearance"
            if (!material.isAir) return "surface_not_clear"
        }
        return null
    }

    private fun distanceAlong(placement: MineWorkingPlacement, position: WorksitePosition): Int = when (placement.direction) {
        0 -> position.z - placement.entrance.z
        1 -> placement.entrance.x - position.x
        2 -> placement.entrance.z - position.z
        else -> position.x - placement.entrance.x
    }

    fun validateSnapshot(plan: MineWorkingPlan, snapshot: Map<WorksitePosition, Material>): String? =
        rejection(plan) { snapshot[it] }

    private fun isGeological(material: Material): Boolean = material in GEOLOGICAL

    /**
     * Authored points may meet an existing Atelier air pocket or timber
     * dressing. Those blocks can be consumed by the temporary scene; player
     * containers and arbitrary decorative blocks still reject placement.
     */
    private fun isNaturalVolume(material: Material): Boolean =
        isGeological(material) || material.isAir || material in EXISTING_DRESSING

    // Authored compact-mine stubs expose the entrance block and one forward
    // block; the first rock block is the drill face at distance two.
    private const val ENTRY_AIR_DEPTH = 1
    private val GEOLOGICAL = setOf(
        Material.STONE,
        Material.COBBLESTONE,
        Material.DEEPSLATE,
        Material.TUFF,
        Material.ANDESITE,
        Material.DIORITE,
        Material.GRANITE,
        Material.CALCITE,
        Material.SMOOTH_STONE,
        Material.STONE_BRICKS,
        Material.MOSSY_STONE_BRICKS,
        Material.DEEPSLATE_BRICKS,
        Material.DEEPSLATE_TILES,
        Material.GRAVEL,
        Material.COAL_ORE,
        Material.COPPER_ORE,
        Material.IRON_ORE,
        Material.GOLD_ORE,
        Material.REDSTONE_ORE,
        Material.LAPIS_ORE,
        Material.DIAMOND_ORE,
        Material.EMERALD_ORE,
        Material.DEEPSLATE_COAL_ORE,
        Material.DEEPSLATE_COPPER_ORE,
        Material.DEEPSLATE_IRON_ORE,
        Material.DEEPSLATE_GOLD_ORE,
        Material.DEEPSLATE_REDSTONE_ORE,
        Material.DEEPSLATE_LAPIS_ORE,
        Material.DEEPSLATE_DIAMOND_ORE,
        Material.DEEPSLATE_EMERALD_ORE,
    )
    private val EXISTING_DRESSING = setOf(
        Material.SPRUCE_LOG,
        Material.STRIPPED_SPRUCE_LOG,
        Material.SPRUCE_WOOD,
        Material.STRIPPED_SPRUCE_WOOD,
        Material.LANTERN,
        Material.IRON_CHAIN,
    )
}
