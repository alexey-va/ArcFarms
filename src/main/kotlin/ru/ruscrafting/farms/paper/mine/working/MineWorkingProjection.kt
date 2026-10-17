package ru.ruscrafting.farms.paper.mine.working

import ru.ruscrafting.farms.domain.MineDriveProgress
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MineRailProgression
import ru.ruscrafting.farms.domain.MineWorkingStage
import ru.ruscrafting.farms.domain.MineWorkingState
import ru.ruscrafting.farms.domain.worksite.WorksitePosition

/** Detached projection, in the same insertion and overwrite order as the native working renderer. */
internal fun workingProjection(
    plan: MineWorkingPlan,
    type: MineIncidentType,
    working: MineWorkingState,
    previousDrive: MineDriveProgress?,
): Map<WorksitePosition, String> {
    val changes = linkedMapOf<WorksitePosition, String>()
    val drive = MineDriveLayout.machine(type, plan.placement) ||
        (type == MineIncidentType.TUNNEL_DRIVE && MineDriveLayout.enabled(plan.placement))
    fun put(position: WorksitePosition, data: String) {
        if (position in plan.blocks && (!drive || plan.blocks[position] != "minecraft:bedrock")) changes[position] = data
    }
    if (drive) {
        val progress = working.drive ?: return emptyMap()
        (progress.carved - previousDrive?.carved.orEmpty()).forEach { id ->
            if (MineDriveLayout.driveable(MineDriveLayout.side(id, plan.placement.geometryVersion),
                    MineDriveLayout.forward(id, plan.placement.geometryVersion), plan.placement.geometryVersion,
                    MineDriveLayout.rail(plan.type, plan.placement))) {
                for (up in 1..4) put(MineDriveLayout.position(plan.placement, id, up), "minecraft:air")
            }
        }
        (progress.lamps - previousDrive?.lamps.orEmpty()).forEach { id ->
            put(MineDriveLayout.position(plan.placement, id, 5), "minecraft:stripped_spruce_log[axis=y]")
            put(MineDriveLayout.position(plan.placement, id, 4), "minecraft:lantern[hanging=true,waterlogged=false]")
        }
        if (MineDriveLayout.rail(plan.type, plan.placement)) {
            val route = progress.rail?.route.orEmpty().map { MineDriveLayout.position(plan.placement, it) }
            val previous = previousDrive?.rail?.route.orEmpty().map { MineDriveLayout.position(plan.placement, it) }.toSet()
            val laidIds = progress.rail?.let { MineRailProgression.laid(it, progress.checkpoint) }.orEmpty().toSet()
            val laid = route.filterIndexed { index, _ -> progress.rail!!.route[index] in laidIds }
            (previous - laid.toSet()).forEach { put(it, "minecraft:air") }
            val routeIndex = linkedMapOf<WorksitePosition, Int>()
            route.forEachIndexed { index, point -> routeIndex.putIfAbsent(point, index) }
            laid.forEach { p ->
                put(p.copy(y = p.y - 1), "minecraft:polished_andesite")
                put(p, MineWorkingLayout.railData(route, routeIndex.getValue(p)))
            }
        }
        return changes
    }
    when (type) {
        MineIncidentType.TUNNEL_DRIVE -> {
            plan.excavation.forEachIndexed { index, position ->
                if (working.stage != MineWorkingStage.EXCAVATE || index in working.completed) put(position, "minecraft:air")
            }
            plan.supportFrames.forEachIndexed { index, frame ->
                val installed = working.stage == MineWorkingStage.SUPPORT && index in working.completed
                frame.forEach { (position, data) -> put(position, if (installed) data else "minecraft:stone") }
            }
        }
        MineIncidentType.RAIL_EXTENSION, MineIncidentType.TRACK_DAMAGE -> {
            val rubble = plan.rubble.withIndex().associate { it.value to it.index }
            plan.rails.forEachIndexed { index, position ->
                val rubbleIndex = rubble[position] ?: -1
                put(position, when (working.stage) {
                    MineWorkingStage.CLEAR_TRACK -> if (rubbleIndex < 0 || rubbleIndex in working.completed) "minecraft:air" else "minecraft:cobblestone"
                    MineWorkingStage.LAY_TRACK -> if (index in working.completed) MineWorkingLayout.railData(plan, position) else "minecraft:air"
                    MineWorkingStage.TEST_TRACK -> MineWorkingLayout.railData(plan, position)
                    else -> "minecraft:air"
                })
            }
        }
        MineIncidentType.ORE_WORKSHOP -> plan.stations["furnace"]?.let { furnace ->
            val original = plan.blocks.getValue(furnace)
            put(furnace, if (working.stage == MineWorkingStage.HEAT) original.replace("lit=false", "lit=true") else original)
        }
        else -> Unit
    }
    return changes
}
