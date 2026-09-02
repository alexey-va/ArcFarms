package ru.ruscrafting.farms.paper.farm.incident.action

import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmRaidInventorySlot
import ru.ruscrafting.farms.domain.FarmRaidLoadoutPlanner
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems

/** Transactionally reserves the active hotbar slots and issues both raid weapons. */
internal class FarmRivalRaidLoadout(
    private val locale: ArcFarmsLocale,
    private val serviceItems: WorksiteServiceItems,
) {
    fun issue(
        player: Player,
        runtime: FarmRuntime,
        expected: Map<String, ServiceItemIdentity>,
    ): Boolean {
        val required = expected.keys.toList()
        val originalStorage = player.inventory.storageContents.map { it?.clone() }.toTypedArray()
        val originalOffhand = player.inventory.itemInOffHand.clone()
        val inventoryItems = originalStorage.toMutableList().apply { add(originalOffhand.takeUnless { it.type.isAir }) }
        val slots = inventoryItems.map { item ->
            val exactId = serviceItems.identity(item)?.let { itemIdentity ->
                required.firstOrNull { expected[it] == itemIdentity }
            }
            FarmRaidInventorySlot(item?.type?.isAir == false, exactId)
        }
        val plan = FarmRaidLoadoutPlanner.plan(slots, required) ?: return false
        fun get(slot: Int): ItemStack? = if (slot == OFFHAND_SLOT) {
            player.inventory.itemInOffHand.takeUnless { it.type.isAir }
        } else player.inventory.getItem(slot)
        fun set(slot: Int, item: ItemStack?) {
            if (slot == OFFHAND_SLOT) player.inventory.setItemInOffHand(item) else player.inventory.setItem(slot, item)
        }
        plan.swaps.forEach { swap ->
            val first = get(swap.first)
            val second = get(swap.second)
            set(swap.first, second)
            set(swap.second, first)
        }
        plan.moves.forEach { move ->
            set(move.to, get(move.from))
            set(move.from, null)
        }
        val issued = plan.issues.all { issue -> issueAt(player, runtime, expected.getValue(issue.itemId), issue.itemId, issue.slot) }
        if (!issued) {
            player.inventory.storageContents = originalStorage
            player.inventory.setItemInOffHand(originalOffhand)
        }
        return issued
    }

    private fun issueAt(
        player: Player,
        runtime: FarmRuntime,
        identity: ServiceItemIdentity,
        itemId: String,
        slot: Int,
    ): Boolean {
        val grenade = itemId == RAID_GRENADE_ID
        val config = runtime.settings.rivalRaid
        val itemModelName = if (grenade) config.grenadeItemModel else config.gunItemModel
        return serviceItems.issueAtSlot(
            player,
            slot,
            identity,
            MaterialRules.material(if (grenade) config.grenadeMaterial else config.gunMaterial),
            locale.render(if (grenade) MessageKey.FARM_RIVAL_RAID_GRENADE else MessageKey.FARM_RIVAL_RAID_GUN, player),
            if (grenade) config.grenadeCustomModelData else config.gunCustomModelData,
            itemModelName?.let { requireNotNull(NamespacedKey.fromString(it)) },
        ) != null
    }

    private companion object {
        const val RAID_GRENADE_ID = "raid_grenade_launcher"
        const val OFFHAND_SLOT = 36
    }
}
