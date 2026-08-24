package ru.ruscrafting.farms.paper

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

internal object WorldEditToolGuard {
    private val failureReported = AtomicBoolean()

    fun ownsInteraction(player: Player, item: ItemStack?): Boolean {
        if (item == null || item.type.isAir || !Bukkit.getPluginManager().isPluginEnabled("WorldEdit")) return false
        return runCatching {
            val actor = BukkitAdapter.adapt(player)
            val session = WorldEdit.getInstance().sessionManager.get(actor)
            val itemType = BukkitAdapter.asItemType(item.type) ?: return@runCatching false
            WorldEditToolPolicy.ownsInteraction(
                heldItemId = itemType.id(),
                selectionWandId = session.wandItem,
                navigationWandId = session.navWandItem,
                hasBoundTool = session.getTool(itemType) != null,
                superPickaxeActive = session.hasSuperPickAxe() && actor.isHoldingPickAxe,
            )
        }.getOrElse { failure ->
            if (failureReported.compareAndSet(false, true)) {
                Bukkit.getLogger().log(
                    Level.WARNING,
                    "ArcFarms could not inspect the active WorldEdit tool; farm editing is blocked fail-safe",
                    failure,
                )
            }
            true
        }
    }
}
