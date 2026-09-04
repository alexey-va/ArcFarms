package ru.ruscrafting.farms.paper.farm.care

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareTarget
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
        val columns = columns(runtime)
        if (columns.size != FarmDitchLayout.cells(runtime.state.placementSequence).size) return
        val soils = columns.map { it.second }
        val carved = columns.flatMap { (cell, soil) ->
            (0 until cell.depth).map { depth -> soil.getRelative(BlockFace.DOWN, depth) }
        }.distinct()
        val pendingSoils = soils.filter { it.type != Material.AIR }
        if (pendingSoils.isEmpty()) {
            if (soils.all { ledger.record(it) != null }) {
                soils.forEach { it.getRelative(BlockFace.UP).setType(Material.AIR, false) }
                carved.forEach { it.setType(Material.AIR, false) }
            }
            return
        }
        if (pendingSoils.any { it.type != Material.FARMLAND }) {
            state.log(
                Level.WARNING,
                "Could not create farm ditch: zone=${runtime.settings.id} sequence=${runtime.state.sequence} reason=bed_changed",
            )
            return
        }
        if (carved.any { it.y <= it.world.minHeight || !runtime.region.contains(it.location) }) {
            state.log(
                Level.WARNING,
                "Could not create farm ditch: zone=${runtime.settings.id} sequence=${runtime.state.sequence} reason=depth_outside_region",
            )
            return
        }
        ledger.captureActiveCrops(pendingSoils, runtime.settings.id)
        ledger.captureAll(carved, runtime.settings.id)
        soils.forEach { soil ->
            soil.getRelative(BlockFace.UP).setType(Material.AIR, false)
        }
        carved.forEach { it.setType(Material.AIR, false) }
        debug.event(
            "farm_care_ditch_created",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "surface_blocks" to soils.size,
            "carved_blocks" to carved.size,
        )
    }

    fun restore(runtime: FarmRuntime) {
        val columns = columns(runtime)
        if (columns.isEmpty()) return
        val soils = columns.map { it.second }
        val carved = columns.flatMap { (cell, soil) ->
            (0 until cell.depth).map { depth -> soil.getRelative(BlockFace.DOWN, depth) }
        }.distinct()
        var restored = 0
        carved.sortedBy(Block::getY).forEach { block ->
            val record = ledger.record(block) ?: return@forEach
            block.setBlockData(Bukkit.createBlockData(record.originalSoilData), false)
            if (block !in soils) ledger.removeTransient(block)
            restored++
        }
        soils.forEach { soil -> ledger.record(soil)?.let { ledger.restoreActiveCrop(soil, it) } }
        if (restored > 0) {
            debug.event(
                "farm_care_ditch_restored",
                "zone" to runtime.settings.id,
                "sequence" to runtime.state.sequence,
                "blocks" to restored,
            )
        }
    }

    fun spawnLocation(runtime: FarmRuntime, target: FarmCareTarget): Location? {
        val world = Bukkit.getWorld(target.position.world) ?: return null
        val center = runtime.state.careTargets.firstOrNull { it.role == FarmCareRole.ANIMAL }?.position ?: return null
        val cell = FarmDitchLayout.cells(runtime.state.placementSequence).firstOrNull { cell ->
            target.position.x.toInt() == center.x.toInt() + cell.x &&
                target.position.z.toInt() == center.z.toInt() + cell.z
        } ?: return null
        return Location(world, target.position.x, target.position.y - cell.depth + 1.05, target.position.z)
    }

    private fun columns(runtime: FarmRuntime): List<Pair<FarmDitchLayout.Cell, Block>> {
        val center = runtime.state.careTargets.firstOrNull { it.role == FarmCareRole.ANIMAL }?.position ?: return emptyList()
        val world = Bukkit.getWorld(center.world) ?: return emptyList()
        val origin = Location(world, center.x, center.y, center.z)
        val cells = FarmDitchLayout.cells(runtime.state.placementSequence)
        return buildList {
            cells.forEach { cell ->
                val x = origin.blockX + cell.x
                val z = origin.blockZ + cell.z
                if (!world.isChunkLoaded(x shr 4, z shr 4)) return emptyList()
                val soil = world.getBlockAt(x, origin.blockY, z)
                if (!runtime.region.contains(soil.location)) return emptyList()
                add(cell to soil)
            }
        }
    }
}
