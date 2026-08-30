package ru.ruscrafting.farms.paper.farm.recovery

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.type.Farmland
import ru.ruscrafting.farms.domain.FarmCropDamage
import ru.ruscrafting.farms.domain.FarmIncidentRecovery
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.block
import java.util.concurrent.TimeUnit
import java.util.logging.Level

/** Bounded, restart-safe recovery for every incident-owned crop mutation. */
internal class FarmIncidentRecoveryController(
    private val ledger: FarmBlockLedger,
    private val access: WorksiteAccessPort,
    private val state: WorksiteStatePort,
) {
    fun pending(runtime: FarmRuntime): Boolean = FarmIncidentRecovery.pending(runtime.state)

    fun remaining(runtime: FarmRuntime): Int = runtime.state.droughtDamagedPlots.size +
        runtime.state.pestDamagedCrops.size + runtime.state.diseaseDamagedCrops.orEmpty().size +
        runtime.state.specialDamagedCrops.size

    fun restore(runtime: FarmRuntime, limit: Int = Int.MAX_VALUE, waterActive: Boolean = false): Int {
        if (waterActive) return 0
        val before = runtime.state
        val beforeCount = remaining(runtime)
        runtime.state = FarmIncidentRecovery.recover(
            before,
            restoreDrought = { position -> restoreDrought(runtime, position) },
            restorePest = { damage -> restoreCrop(runtime, damage) },
            restoreDisease = { damage -> restoreCrop(runtime, damage) },
            restoreSpecial = { damage -> restoreCrop(runtime, damage) },
            limit = limit,
        )
        if (runtime.state != before) state.persistAsync()
        val afterCount = remaining(runtime)
        if (pending(runtime) && access.allowInteraction("farm-incident-recovery:${runtime.settings.id}", TimeUnit.MINUTES.toMillis(1))) {
            state.log(
                Level.WARNING,
                "Farm incident recovery in ${runtime.settings.id} is waiting for $afterCount loaded plot(s)",
            )
        }
        return (beforeCount - afterCount).coerceAtLeast(0)
    }

    private fun restoreDrought(runtime: FarmRuntime, position: FarmPlotPosition): Boolean {
        val soil = position.block() ?: return false
        return runCatching {
            wet(soil)
            val restored = ledger.restoreActiveCrop(soil)
            if (restored && position !in runtime.state.preparationPatch) ledger.removeTransient(soil)
            restored
        }.getOrElse { failure ->
            state.log(Level.SEVERE, "Could not restore drought-damaged farm plot $position", failure)
            false
        }
    }

    private fun restoreCrop(runtime: FarmRuntime, damage: FarmCropDamage): Boolean {
        val soil = damage.position.block() ?: return false
        return runCatching {
            wet(soil)
            val crop = MaterialRules.material(damage.crop)
            val above = soil.getRelative(org.bukkit.block.BlockFace.UP)
            val restored = when {
                above.type == crop -> true
                above.type == Material.CAMPFIRE -> ledger.restoreActiveCrop(soil)
                above.type.isAir || above.type == Material.WATER -> {
                    above.setBlockData(crop.createBlockData(), false)
                    ledger.captureActiveCrop(soil, runtime.settings.id)
                    true
                }
                else -> false
            }
            if (restored && damage.position !in runtime.state.preparationPatch) ledger.removeTransient(soil)
            restored
        }.getOrElse { failure ->
            state.log(Level.SEVERE, "Could not restore incident-damaged farm plot ${damage.position}", failure)
            false
        }
    }

    private fun wet(block: Block) {
        if (block.type != Material.FARMLAND) block.setType(Material.FARMLAND, false)
        val farmland = (block.blockData as? Farmland) ?: (Material.FARMLAND.createBlockData() as Farmland)
        farmland.moisture = farmland.maximumMoisture
        block.setBlockData(farmland, false)
    }
}
