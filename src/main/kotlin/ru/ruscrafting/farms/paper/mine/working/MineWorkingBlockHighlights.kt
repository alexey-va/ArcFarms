package ru.ruscrafting.farms.paper.mine.working

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.domain.MineWorkingStage
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.worksite.WorksiteBlockGlow
import java.util.UUID

/** Outlines actual blocks and the exact shape of the next support or rail. */
internal class MineWorkingBlockHighlights(plugin: Plugin) {
    private val tag = NamespacedKey(plugin, "mine_working_block_glow")
    private val displays = mutableMapOf<String, MutableMap<WorksitePosition, UUID>>()

    fun reconcile(runtime: MineRuntime, scene: MineWorkingScene) {
        val working = runtime.state.incident?.working ?: return
        val expected = linkedMapOf<WorksitePosition, BlockData>()
        val world = runtime.region.world
        fun add(position: WorksitePosition, fallback: Material? = null) {
            val data = world.getBlockAt(position.x, position.y, position.z).blockData
            if (!data.material.isAir) expected[position] = data
            else if (fallback != null) expected[position] = fallback.createBlockData()
        }
        val drive = MineDriveLayout.machine(scene.plan.type, working.placement)
        if (drive) MineDriveLayout.goalOres(scene.plan).forEach { add(it) }
        // Trace the entrance for on-foot work; a drive has its glowing vehicle and diamond destination.
        if (!drive) for (side in -2..2) for (up in 1..4) {
            if (kotlin.math.abs(side) == 2 || up == 4) add(working.placement.position(side, up, 0))
        }
        when (working.stage) {
            MineWorkingStage.CLEAR_TRACK -> scene.plan.rubble.forEachIndexed { index, position ->
                if (index !in working.completed) add(position)
            }
            MineWorkingStage.SUPPORT -> scene.plan.supportFrames.forEachIndexed { index, frame ->
                if (index !in working.completed) frame.forEach { (position, _) ->
                    // Keep the preview identical to the canonical support recipe:
                    // roof beams are directional logs while the side posts stay vertical.
                    expected[position] = Bukkit.createBlockData(frame.getValue(position))
                }
            }
            MineWorkingStage.LAY_TRACK -> scene.plan.rails.getOrNull(working.completed.size)?.let { add(it, Material.RAIL) }
            else -> Unit
        }
        val current = displays.getOrPut(runtime.settings.id) { mutableMapOf() }
        current.keys.toList().filter { it !in expected }.forEach { current.remove(it)?.let(Bukkit::getEntity)?.remove() }
        expected.forEach { (position, data) ->
            val display = (current[position]?.let(Bukkit::getEntity) as? BlockDisplay)?.takeIf { it.isValid }
                ?: world.spawn(org.bukkit.Location(world, position.x.toDouble(), position.y.toDouble(), position.z.toDouble()), BlockDisplay::class.java)
                    .also { current[position] = it.uniqueId; it.persistentDataContainer.set(tag, PersistentDataType.STRING, runtime.settings.id) }
            WorksiteBlockGlow.apply(display, data, if (drive) Color.fromRGB(80, 235, 255) else Color.fromRGB(255, 194, 84))
        }
    }

    fun cleanup(zone: String) { displays.remove(zone)?.values?.forEach { Bukkit.getEntity(it)?.remove() } }

    fun reconcileLoaded() {
        Bukkit.getWorlds().forEach { world -> world.loadedChunks.forEach { chunk ->
            chunk.entities.filter { it.persistentDataContainer.has(tag, PersistentDataType.STRING) }.forEach { it.remove() }
        } }
        displays.clear()
    }
}
