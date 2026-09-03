package ru.ruscrafting.farms.paper.farm.care

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import java.util.logging.Level

/** Owns the journalled, deliberately uneven crop-bed mutation used by procedural ditch rescues. */
internal class FarmDitchRescueWorld(
    private val ledger: FarmBlockLedger,
    private val state: WorksiteStatePort,
    private val debug: ArcFarmsDebug,
) {
    fun ensure(runtime: FarmRuntime) {
        val soils = soils(runtime)
        if (soils.size != FarmDitchLayout.offsets(runtime.state.placementSequence).size) return
        val center = soils.first()
        if (center.type == Material.AIR && ledger.record(center) == null) return
        val pending = soils.filter { it.type != Material.AIR }
        if (pending.isEmpty()) return
        if (pending.any { it.type != Material.FARMLAND }) {
            state.log(
                Level.WARNING,
                "Could not create farm ditch: zone=${runtime.settings.id} sequence=${runtime.state.sequence} reason=bed_changed",
            )
            return
        }
        ledger.captureActiveCrops(pending, runtime.settings.id)
        pending.forEach { soil ->
            soil.getRelative(BlockFace.UP).setType(Material.AIR, false)
            soil.setType(Material.AIR, false)
        }
        debug.event(
            "farm_care_ditch_created",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "blocks" to pending.size,
        )
    }

    fun restore(runtime: FarmRuntime) {
        val soils = soils(runtime)
        if (soils.isEmpty() || ledger.record(soils.first()) == null) return
        var restored = 0
        soils.forEach { soil ->
            val record = ledger.record(soil) ?: return@forEach
            soil.setBlockData(Bukkit.createBlockData(record.originalSoilData), false)
            ledger.restoreActiveCrop(soil, record)
            restored++
        }
        if (restored > 0) {
            debug.event(
                "farm_care_ditch_restored",
                "zone" to runtime.settings.id,
                "sequence" to runtime.state.sequence,
                "blocks" to restored,
            )
        }
    }

    private fun soils(runtime: FarmRuntime): List<Block> {
        val center = runtime.state.careTargets.firstOrNull { it.role == FarmCareRole.ANIMAL }?.position ?: return emptyList()
        val world = Bukkit.getWorld(center.world) ?: return emptyList()
        val origin = Location(world, center.x, center.y, center.z)
        val offsets = FarmDitchLayout.offsets(runtime.state.placementSequence)
        return buildList {
            offsets.forEach { offset ->
                val x = origin.blockX + offset.x
                val z = origin.blockZ + offset.z
                if (!world.isChunkLoaded(x shr 4, z shr 4)) return emptyList()
                val soil = world.getBlockAt(x, origin.blockY, z)
                if (!runtime.region.contains(soil.location)) return emptyList()
                add(soil)
            }
        }
    }
}
