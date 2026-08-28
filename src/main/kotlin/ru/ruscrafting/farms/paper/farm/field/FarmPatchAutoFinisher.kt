package ru.ruscrafting.farms.paper.farm.field

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.type.Farmland
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmSeederStage
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.block

/** Applies the tolerated field remainder gradually after the player-visible quota has completed. */
internal class FarmPatchAutoFinisher(
    private val ledger: FarmBlockLedger,
    private val debug: ArcFarmsDebug,
) {
    private val completedActions = mutableSetOf<ActionKey>()

    fun clear() = completedActions.clear()

    fun hasPending(runtime: FarmRuntime): Boolean {
        val action = action(runtime) ?: return false
        return ActionKey(runtime.settings.id, runtime.state.sequence, action) !in completedActions
    }

    fun process(runtime: FarmRuntime, limit: Int): Int {
        require(limit >= 1) { "Farm patch auto-finish limit must be positive" }
        if (!runtime.state.preparationReleased || runtime.state.preparationPatch.isEmpty()) return 0
        val action = action(runtime) ?: return 0
        val key = ActionKey(runtime.settings.id, runtime.state.sequence, action)
        completedActions.removeIf { it.zoneId == runtime.settings.id && it.sequence != runtime.state.sequence }
        if (key in completedActions) return 0
        val crop = runtime.state.preparationCrop?.let(MaterialRules::material)
        val selected = mutableListOf<Block>()
        for (position in runtime.state.preparationPatch) {
            if (selected.size >= limit) break
            val soil = position.block() ?: continue
            if (action.needsMutation(soil, crop, ledger.record(soil))) selected += soil
        }
        if (selected.isNotEmpty()) {
            ledger.captureAll(selected, runtime.settings.id)
            selected.forEach { soil -> action.mutate(soil, crop, ledger) }
            if (action == Action.PLANT) ledger.updateActiveCrops(selected)
        }
        val complete = selected.size < limit
        if (complete) completedActions += key
        debug.event(
            "farm_patch_auto_finish",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "action" to action.name.lowercase(),
            "processed" to selected.size,
            "complete" to complete,
        )
        return selected.size
    }

    private data class ActionKey(val zoneId: String, val sequence: Long, val action: Action)

    private fun action(runtime: FarmRuntime): Action? = when {
        runtime.state.phase == FarmPhase.PLANTING -> Action.TILL
        runtime.state.phase == FarmPhase.CARE && runtime.state.careType == FarmCareType.SEEDER &&
            runtime.state.seederStage == FarmSeederStage.PLANTING -> Action.TILL
        runtime.state.mechanizedPreparation &&
            runtime.state.plantedPlots.containsAll(runtime.state.preparationPatch) -> Action.RESTORE_RECORDED_CROP
        (runtime.state.phase == FarmPhase.HARVESTING || runtime.state.phase == FarmPhase.CARE) &&
            runtime.state.plantedPlots.containsAll(runtime.state.preparationPatch) -> Action.PLANT
        else -> null
    }

    private enum class Action {
        TILL,
        PLANT,
        RESTORE_RECORDED_CROP;

        fun needsMutation(
            soil: Block,
            crop: Material?,
            record: ru.ruscrafting.farms.paper.ManagedFarmBlockRecord?,
        ): Boolean = when (this) {
            TILL -> soil.type != Material.FARMLAND
            PLANT -> crop != null && soil.getRelative(org.bukkit.block.BlockFace.UP).let { above ->
                (above.type.isAir || above.type == crop) && (soil.type != Material.FARMLAND || above.type != crop)
            }
            RESTORE_RECORDED_CROP -> record?.activeCropData != null &&
                (soil.type != Material.FARMLAND || soil.getRelative(org.bukkit.block.BlockFace.UP).blockData.asString != record.activeCropData)
        }

        fun mutate(soil: Block, crop: Material?, ledger: FarmBlockLedger) {
            if (soil.type != Material.FARMLAND) soil.setType(Material.FARMLAND, false)
            val farmland = (soil.blockData as? Farmland) ?: (Material.FARMLAND.createBlockData() as Farmland)
            farmland.moisture = farmland.maximumMoisture
            soil.setBlockData(farmland, false)
            when (this) {
                PLANT -> soil.getRelative(org.bukkit.block.BlockFace.UP)
                    .setBlockData(requireNotNull(crop).createBlockData(), false)
                RESTORE_RECORDED_CROP -> ledger.restoreActiveCrop(soil)
                TILL -> Unit
            }
        }
    }
}
