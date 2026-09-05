package ru.ruscrafting.farms.paper.farm.incident.greenhouse

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.block

internal data class GreenhouseObstruction(val reason: String, val block: Block? = null, val at: Location? = block?.location) {
    fun describe() = "$reason at=${at?.let { "${it.world.name}:${it.blockX},${it.blockY},${it.blockZ}" } ?: "none"} material=${block?.type ?: "none"}"
}

internal data class GreenhouseBedEdit(val soil: Block, val floorY: Int)
internal data class GreenhouseSite(val center: Location, val edits: List<GreenhouseBedEdit>, val failure: GreenhouseObstruction? = null)

/** Prepares only indexed beds; the shared chunk journal restores soil and crops after interruption. */
internal class FarmGreenhousePlacement(private val ledger: FarmBlockLedger) {
    private val pending = mutableMapOf<String, MutableSet<FarmPlotPosition>>()

    fun inspect(runtime: FarmRuntime, center: Location): GreenhouseSite {
        val world = center.world
        val edits = mutableListOf<GreenhouseBedEdit>()
        fun reject(reason: String, block: Block? = null, at: Location? = block?.location ?: center) =
            GreenhouseSite(center, emptyList(), GreenhouseObstruction(reason, block, at))
        if (center.blockY + 5 >= world.maxHeight || center.blockY - 2 < world.minHeight) return reject("height")
        val floorY = center.blockY - 1
        for (x in center.blockX - 4..center.blockX + 4) for (z in center.blockZ - 5..center.blockZ + 5) {
            val at = Location(world, x + 0.5, center.y, z + 0.5)
            if (!world.isChunkLoaded(x shr 4, z shr 4)) return reject("unloaded", at = at)
            if (!runtime.region.contains(at) || !runtime.region.contains(at.clone().add(0.0, 4.0, 0.0))) return reject("region", at = at)
            val support = world.getBlockAt(x, floorY, z)
            val beds = (floorY - 1..floorY + 1).map { world.getBlockAt(x, it, z) }.filter { soil ->
                val record = ledger.record(soil)
                record?.indexed == true && record.zoneId == runtime.settings.id && runtime.region.contains(soil.location)
            }
            val foreign = beds.firstOrNull { soil -> ledger.record(soil)?.temporaryMutation?.let { it != owner(runtime.settings.id) } == true }
            if (foreign != null) return reject("journal", foreign)
            fun removable(material: Material) = material.isAir || material.name in runtime.settings.crops ||
                material in setOf(Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
                    Material.SWEET_BERRY_BUSH, Material.PUMPKIN_STEM, Material.MELON_STEM,
                    Material.ATTACHED_PUMPKIN_STEM, Material.ATTACHED_MELON_STEM)
            val lower = beds.firstOrNull { it.y == floorY - 1 && it.type == Material.FARMLAND }
            if (!support.type.isSolid && !(support.type == Material.WATER && support.getRelative(BlockFace.DOWN).type.isSolid)) {
                if (lower == null || !removable(support.type)) return reject("support", support)
                edits += GreenhouseBedEdit(lower, floorY)
            }
            for (y in center.blockY..center.blockY + 4) {
                val block = world.getBlockAt(x, y, z)
                if (block.type.isAir) continue
                val bed = beds.firstOrNull { it.y + 1 == y && removable(block.type) } ?: beds.firstOrNull {
                    it.y == y && y == center.blockY && it.type == Material.FARMLAND
                }
                if (bed == null) return reject("obstruction", block)
                edits += GreenhouseBedEdit(bed, floorY)
            }
        }
        return GreenhouseSite(center, edits.distinctBy { it.soil })
    }

    fun prepare(runtime: FarmRuntime, site: GreenhouseSite) {
        require(site.failure == null)
        val zone = runtime.settings.id
        val soils = site.edits.map { it.soil }
        if (soils.isEmpty()) return
        ledger.beginTemporaryRemoval(soils, zone, owner(zone), Long.MAX_VALUE)
        val owned = pending.getOrPut(zone) { linkedSetOf() }
        soils.forEach { owned += FarmPlotPosition(it.world.name, it.x, it.y, it.z) }
        site.edits.forEach { (soil, floorY) ->
            val crop = soil.getRelative(BlockFace.UP)
            when {
                soil.y < floorY -> crop.setType(Material.FARMLAND, false)
                soil.y > floorY -> { crop.setType(Material.AIR, false); soil.setType(Material.AIR, false) }
                else -> crop.setType(Material.AIR, false)
            }
        }
    }

    fun clear(zone: String) {
        val plots = pending[zone] ?: return
        val loaded = plots.mapNotNull(FarmPlotPosition::block)
        val restored = ledger.restoreTemporaryRemovals(loaded, owner(zone))
        loaded.filter { it in restored || ledger.record(it)?.temporaryMutation != owner(zone) }.forEach {
            plots.remove(FarmPlotPosition(it.world.name, it.x, it.y, it.z))
        }
        if (plots.isEmpty()) pending.remove(zone)
    }

    fun cleanup() = pending.keys.toList().forEach(::clear)
    private fun owner(zone: String) = "greenhouse:$zone"
}
