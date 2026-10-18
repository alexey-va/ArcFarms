package ru.ruscrafting.farms.paper.farm.care

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.paper.teleport.ScopedTeleportAuthorizer
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareTarget
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.platform.FarmBlockPassability
import ru.ruscrafting.farms.paper.platform.PaperFarmBlockPassability
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import java.util.logging.Level

/** Owns the journalled, deliberately uneven crop-bed mutation used by procedural ditch rescues. */
internal class FarmDitchRescueWorld(
    private val ledger: FarmBlockLedger,
    private val state: WorksiteStatePort,
    private val debug: ArcFarmsDebug,
    private val passability: FarmBlockPassability = PaperFarmBlockPassability,
) {
    private val teleports = ScopedTeleportAuthorizer()
    private val blockedRestores = mutableSetOf<String>()

    fun isAuthorizedTeleport(player: Player, destination: Location): Boolean =
        teleports.isAuthorized(player.uniqueId, destination)

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

    fun restore(runtime: FarmRuntime): Boolean {
        val columns = columns(runtime)
        if (columns.isEmpty()) return runtime.state.careTargets.none { it.role == FarmCareRole.ANIMAL }
        val soils = columns.map { it.second }
        val carved = columns.flatMap { (cell, soil) ->
            (0 until cell.depth).map { depth -> soil.getRelative(BlockFace.DOWN, depth) }
        }.distinct()
        if (!evacuate(runtime, columns, carved)) return false
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
        blockedRestores.remove(runtime.settings.id)
        return true
    }

    private fun evacuate(runtime: FarmRuntime, columns: List<Pair<FarmDitchLayout.Cell, Block>>, carved: List<Block>): Boolean {
        val world = columns.first().second.world
        val cavities = carved.filter { it.type.isAir && ledger.record(it) != null }.map { block ->
            BoundingBox(block.x.toDouble(), block.y.toDouble(), block.z.toDouble(),
                block.x + 1.0, block.y + 1.0, block.z + 1.0)
        }
        fun inside(player: Player) = cavities.any { it.overlaps(player.boundingBox) }
        val visitors = world.players.filter(::inside)
        if (visitors.isEmpty()) return true
        val footprint = columns.mapTo(hashSetOf()) { it.second.x to it.second.z }
        val centerY = kotlin.math.floor(runtime.state.careTargets.first { it.role == FarmCareRole.ANIMAL }.position.y).toInt()
        val surfaces = footprint.flatMap { (x, z) ->
            listOf(x - 1 to z, x + 1 to z, x to z - 1, x to z + 1)
        }.distinct().filterNot { it in footprint }.mapNotNull { (x, z) ->
            if (!world.isChunkLoaded(x shr 4, z shr 4)) return@mapNotNull null
            FarmDitchLayout.surfaceSearch.firstNotNullOfOrNull { offset ->
                val y = centerY + offset
                if (y < world.minHeight || y + 2 >= world.maxHeight) return@firstNotNullOfOrNull null
                val soil = world.getBlockAt(x, y, z)
                val feet = soil.getRelative(BlockFace.UP)
                val head = feet.getRelative(BlockFace.UP)
                if (soil.type.isSolid && soil.type !in UNSAFE_SURFACES && feet.type !in UNSAFE_SURFACES &&
                    head.type !in UNSAFE_SURFACES && runtime.region.contains(feet.location) &&
                    passability.isPassable(feet) && passability.isPassable(head))
                    Location(world, x + 0.5, soil.y + 1.01, z + 0.5) else null
            }
        }
        for (player in visitors) {
            val destination = surfaces.minByOrNull { it.distanceSquared(player.location) }?.clone()?.apply {
                yaw = player.location.yaw
                pitch = player.location.pitch
            }
            val evacuated = destination != null && teleports.authorize(player.uniqueId, destination) {
                player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN) &&
                    player.world === world && player.location.distanceSquared(destination) < 0.01 && !inside(player)
            }
            if (!evacuated) {
                if (blockedRestores.add(runtime.settings.id)) state.log(Level.WARNING,
                    "Farm ditch restoration deferred: zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                        "player=${player.uniqueId} reason=${if (destination == null) "no_safe_surface" else "teleport_rejected"}")
                return false
            }
            player.fallDistance = 0f
            player.velocity = Vector()
        }
        return world.players.none(::inside)
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
                val soil = FarmDitchLayout.surfaceSearch.firstNotNullOfOrNull { offset ->
                    world.getBlockAt(x, origin.blockY + offset, z).takeIf(::isDitchSurface)
                } ?: return emptyList()
                if (!runtime.region.contains(soil.location)) return emptyList()
                add(cell to soil)
            }
        }
    }

    private fun isDitchSurface(block: Block): Boolean =
        block.type == Material.FARMLAND || ledger.record(block)?.originalSoilData?.let { serialized ->
            runCatching { Bukkit.createBlockData(serialized).material == Material.FARMLAND }.getOrDefault(false)
        } == true

    private companion object {
        val UNSAFE_SURFACES = setOf(Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.FIRE, Material.SOUL_FIRE, Material.LAVA, Material.WATER, Material.BUBBLE_COLUMN,
            Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH)
    }
}
