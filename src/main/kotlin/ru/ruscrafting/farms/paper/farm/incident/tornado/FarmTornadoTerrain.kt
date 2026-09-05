package ru.ruscrafting.farms.paper.farm.incident.tornado

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.block
import ru.ruscrafting.farms.paper.farm.FarmIncidentBedProvider
import ru.ruscrafting.farms.paper.farm.incident.action.FarmRivalRaidBlastDebris
import ru.ruscrafting.farms.paper.farm.placement.FarmSurfacePolicy
import java.util.UUID
import kotlin.math.abs

/** Owns tornado terrain damage; chunk PDC remains authoritative across interrupted restoration. */
internal class FarmTornadoTerrain(
    plugin: Plugin,
    private val ledger: FarmBlockLedger,
    private val beds: FarmIncidentBedProvider,
) {
    private data class Damage(
        val plots: MutableMap<FarmPlotPosition, Long> = linkedMapOf(),
        val debris: MutableMap<UUID, Int> = linkedMapOf(),
    )

    private val zones = mutableMapOf<String, Damage>()
    private val key = NamespacedKey(plugin, "farm_tornado_terrain_zone")

    fun update(runtime: FarmRuntime, center: Location, tick: Int, pursuing: Boolean) {
        val zone = runtime.settings.id
        val damage = zones.getOrPut(zone, ::Damage)
        val now = center.world.gameTime
        restore(zone, damage, damage.plots.filterValues { it <= now }.keys.toList())
        damage.debris.entries.removeIf { (id, deadline) ->
            val entity = Bukkit.getEntity(id)
            if (entity == null || !entity.isValid) true
            else if (tick >= deadline) { entity.remove(); true } else false
        }
        if (!pursuing || tick % PULSE_TICKS != 0 || damage.plots.size >= MAX_DAMAGED_PLOTS) return
        val radius = minOf(4.0, runtime.settings.specialIncidents.tornado.radius / 2.0)
        val plots = beds.discover(runtime).asSequence().filter { plot ->
            if (plot.world != center.world.name || plot in damage.plots) return@filter false
            val dx = plot.x + 0.5 - center.x
            val dz = plot.z + 0.5 - center.z
            if (dx * dx + dz * dz > radius * radius || abs(plot.y + 1 - center.y) > 3.0) return@filter false
            val soil = plot.block() ?: return@filter false
            val record = ledger.record(soil) ?: return@filter false
            record.indexed && record.zoneId == zone && record.temporaryMutation == null &&
                soil.type == Material.FARMLAND && runtime.region.contains(soil.location) &&
                runtime.region.contains(soil.getRelative(BlockFace.UP).location) && FarmSurfacePolicy.isOutdoorBed(soil)
        }.sortedBy { plot ->
            val dx = plot.x + 0.5 - center.x
            val dz = plot.z + 0.5 - center.z
            dx * dx + dz * dz
        }.take(minOf(PLOTS_PER_PULSE, MAX_DAMAGED_PLOTS - damage.plots.size)).toList()
        if (plots.isEmpty()) return
        val soils = plots.mapNotNull(FarmPlotPosition::block)
        val restoreAt = now + RESTORE_TICKS
        // Capture crops and soil before either the authoritative mutation or its debris can occur.
        ledger.beginTemporaryRemoval(soils, zone, owner(zone), restoreAt)
        plots.forEach { damage.plots[it] = restoreAt }
        val budget = (runtime.settings.specialIncidents.tornado.debrisCount - damage.debris.size).coerceAtLeast(0)
        FarmRivalRaidBlastDebris.spawn(center, plots, minOf(plots.size * 2, budget)).forEach { debris ->
            debris.isPersistent = false
            debris.persistentDataContainer.set(key, PersistentDataType.STRING, zone)
            damage.debris[debris.uniqueId] = tick + DEBRIS_TICKS
        }
        soils.forEach { soil ->
            soil.getRelative(BlockFace.UP).setType(Material.AIR, false)
            soil.setType(Material.AIR, false)
        }
    }

    fun owns(entity: Entity): Boolean = entity.persistentDataContainer.has(key, PersistentDataType.STRING)

    fun clear(zone: String) {
        val damage = zones[zone] ?: return
        damage.debris.keys.forEach { Bukkit.getEntity(it)?.remove() }
        damage.debris.clear()
        restore(zone, damage, damage.plots.keys.toList())
        if (damage.plots.isEmpty()) zones.remove(zone)
    }

    fun cleanup() {
        zones.keys.toList().forEach(::clear)
        // Unloaded blocks retain their chunk journal for FarmBlockRegistry reconciliation.
        Bukkit.getWorlds().forEach { world -> world.entities.filter(::owns).forEach(Entity::remove) }
    }

    private fun restore(zone: String, damage: Damage, plots: Collection<FarmPlotPosition>) {
        val loaded = plots.mapNotNull(FarmPlotPosition::block)
        val restored = ledger.restoreTemporaryRemovals(loaded, owner(zone))
        loaded.forEach { soil ->
            // Field maintenance or chunk reconciliation may already have restored this exact journal.
            if (soil in restored || ledger.record(soil)?.temporaryMutation != owner(zone)) {
                damage.plots.remove(FarmPlotPosition(soil.world.name, soil.x, soil.y, soil.z))
            }
        }
    }

    private fun owner(zone: String) = "tornado:$zone"

    private companion object {
        const val PULSE_TICKS = 10
        const val PLOTS_PER_PULSE = 12
        const val MAX_DAMAGED_PLOTS = 144
        const val RESTORE_TICKS = 120L
        const val DEBRIS_TICKS = 40
    }
}
