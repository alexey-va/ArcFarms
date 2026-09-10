package ru.ruscrafting.farms.paper.mine.incident.scenario

import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Material
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MineScenarioAction
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.worksite.WorksiteCarriedDisplayRenderer
import java.util.UUID

/** Objective leases own cargo; the farm's renderer only shows what is currently being carried. */
internal class MineScenarioCargo(plugin: Plugin) {
    private val renderer = WorksiteCarriedDisplayRenderer()
    private val marker = NamespacedKey(plugin, "mine_scenario_cargo")
    private val displays = mutableMapOf<String, Pair<UUID, UUID>>()

    fun reconcile(runtime: MineRuntime, action: MineScenarioAction, material: String) {
        val prefix = "${runtime.settings.id}:"
        val expected = if (action == MineScenarioAction.CARRY && runtime.state.incident?.type != MineIncidentType.INJURED_MINER)
            runtime.state.objective?.targets.orEmpty().filter { it.status == ObjectiveTargetStatus.LEASED }
                .mapNotNull { target -> target.leasedBy?.let(Bukkit::getPlayer)?.takeIf { it.isOnline && !it.isDead }
                    ?.let { "$prefix${runtime.state.sequence}:${target.id}" to it } }.toMap()
        else emptyMap()
        displays.keys.filter { it.startsWith(prefix) && it !in expected }.toList().forEach(::remove)
        expected.forEach { (key, player) ->
            val display = displays[key]?.second?.let(Bukkit::getEntity) as? ItemDisplay
            if (display?.isValid == true) renderer.move(display, player, .7, 1.0)
            else {
                val created = renderer.spawn(player, ItemStack(Material.valueOf(material)),
                    ItemDisplay.ItemDisplayTransform.FIXED, .55f, 1.5f, .7, 1.0) {
                    it.persistentDataContainer.set(marker, PersistentDataType.STRING, key)
                }
                displays[key] = player.uniqueId to created.uniqueId
            }
        }
    }

    fun release(player: Player) {
        displays.filterValues { it.first == player.uniqueId }.keys.toList().forEach(::remove)
    }

    fun cleanup(runtime: MineRuntime) {
        displays.keys.filter { it.startsWith("${runtime.settings.id}:") }.toList().forEach(::remove)
    }

    private fun remove(key: String) {
        (displays.remove(key)?.second?.let(Bukkit::getEntity) as? ItemDisplay)?.let(renderer::remove)
    }
}
