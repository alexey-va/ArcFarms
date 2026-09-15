package ru.ruscrafting.farms.paper.worksite

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** Explicit player inventory surfaces owned by a temporary worksite item lifecycle. */
internal data class WorksitePlayerItemScope(
    val armor: Boolean = false,
    val offHand: Boolean = true,
    val cursor: Boolean = true,
    val openInventory: Boolean = false,
) {
    companion object {
        val OWNED = WorksitePlayerItemScope()
        val OWNED_WITH_OPEN_INVENTORY = WorksitePlayerItemScope(openInventory = true)
        val EVERYWHERE = WorksitePlayerItemScope(armor = true, openInventory = true)
    }
}

internal data class WorksiteRemovedItems(val items: List<ItemStack>) {
    val amount: Int = items.sumOf(ItemStack::getAmount)
}

/**
 * Canonical inventory owner for temporary worksite equipment.
 *
 * It centralizes selected-slot-first delivery, multi-surface discovery,
 * transactional loadout replacement, cleanup and in-place reconfiguration.
 */
internal object WorksitePlayerItems {
    fun placeSelectedFirst(player: Player, items: List<ItemStack>): List<Int>? {
        require(items.isNotEmpty()) { "A loadout needs at least one item" }
        val inventory = player.inventory
        val preferredSlots = listOf(inventory.heldItemSlot) +
            inventory.storageContents.indices.filterNot { it == inventory.heldItemSlot }
        val emptySlots = preferredSlots.filter { slot -> inventory.getItem(slot).isEmpty() }
        if (emptySlots.size < items.size) return null
        return items.zip(emptySlots).map { (item, slot) ->
            inventory.setItem(slot, item.clone())
            slot
        }
    }

    fun contains(
        player: Player,
        scope: WorksitePlayerItemScope = WorksitePlayerItemScope.OWNED,
        predicate: (ItemStack?) -> Boolean,
    ): Boolean = visit(player, scope).any { predicate(it.item()) }

    fun items(
        player: Player,
        scope: WorksitePlayerItemScope = WorksitePlayerItemScope.OWNED,
    ): List<ItemStack> = visit(player, scope).mapNotNull { it.item() }.toList()

    fun findInventorySlot(player: Player, predicate: (ItemStack?) -> Boolean): Int? =
        player.inventory.contents.indices.firstOrNull { predicate(player.inventory.getItem(it)) }

    fun removeFirst(
        player: Player,
        scope: WorksitePlayerItemScope = WorksitePlayerItemScope.OWNED,
        predicate: (ItemStack?) -> Boolean,
    ): ItemStack? {
        val slot = visit(player, scope).firstOrNull { predicate(it.item()) } ?: return null
        val removed = slot.item()?.clone()
        slot.clear()
        return removed
    }

    fun removeAll(
        player: Player,
        scope: WorksitePlayerItemScope = WorksitePlayerItemScope.OWNED,
        predicate: (ItemStack?) -> Boolean,
    ): WorksiteRemovedItems {
        val removed = mutableListOf<ItemStack>()
        visit(player, scope).forEach { slot ->
            val item = slot.item()
            if (!predicate(item)) return@forEach
            item?.clone()?.let(removed::add)
            slot.clear()
        }
        return WorksiteRemovedItems(removed)
    }

    fun replaceLoadout(
        player: Player,
        items: List<ItemStack>,
        scope: WorksitePlayerItemScope = WorksitePlayerItemScope.OWNED,
        remove: (ItemStack?) -> Boolean,
    ): List<Int>? {
        val snapshot = Snapshot.capture(player, scope)
        removeAll(player, scope, remove)
        return placeSelectedFirst(player, items) ?: run {
            snapshot.restore(player)
            null
        }
    }

    fun transform(
        player: Player,
        scope: WorksitePlayerItemScope = WorksitePlayerItemScope.OWNED,
        replacement: (ItemStack?) -> ItemStack?,
    ): Int {
        var changed = 0
        visit(player, scope).forEach { slot ->
            val current = slot.item()
            val updated = replacement(current) ?: return@forEach
            slot.set(updated)
            changed++
        }
        return changed
    }

    private fun visit(player: Player, scope: WorksitePlayerItemScope): Sequence<MutableItemSlot> = sequence {
        player.inventory.storageContents.indices.forEach { index ->
            yield(MutableItemSlot({ player.inventory.getItem(index) }, { player.inventory.setItem(index, it) }))
        }
        if (scope.armor) {
            player.inventory.armorContents.indices.forEach { index ->
                yield(MutableItemSlot(
                    { player.inventory.armorContents[index] },
                    { item ->
                        val armor = player.inventory.armorContents
                        armor[index] = item
                        player.inventory.armorContents = armor
                    },
                ))
            }
        }
        if (scope.offHand) {
            yield(MutableItemSlot(player.inventory::getItemInOffHand, player.inventory::setItemInOffHand))
        }
        if (scope.cursor) {
            yield(MutableItemSlot(player::getItemOnCursor, player::setItemOnCursor))
        }
        if (scope.openInventory) {
            val top = runCatching { player.openInventory.topInventory }.getOrNull()
            if (top != null) {
                (0 until top.size).forEach { index ->
                    yield(MutableItemSlot({ top.getItem(index) }, { top.setItem(index, it) }))
                }
            }
        }
    }

    private data class MutableItemSlot(
        val item: () -> ItemStack?,
        val set: (ItemStack?) -> Unit,
    ) {
        fun clear() = set(null)
    }

    private data class Snapshot(
        val storage: Array<ItemStack?>,
        val armor: Array<ItemStack?>?,
        val offHand: ItemStack?,
        val cursor: ItemStack?,
        val top: org.bukkit.inventory.Inventory?,
        val topContents: Array<ItemStack?>?,
    ) {
        fun restore(player: Player) {
            player.inventory.storageContents = storage
            armor?.let { player.inventory.armorContents = it }
            offHand?.let(player.inventory::setItemInOffHand)
            cursor?.let(player::setItemOnCursor)
            if (top != null && topContents != null) top.contents = topContents
        }

        companion object {
            fun capture(player: Player, scope: WorksitePlayerItemScope): Snapshot {
                val top = if (scope.openInventory) {
                    runCatching { player.openInventory.topInventory }.getOrNull()
                } else null
                return Snapshot(
                    storage = player.inventory.storageContents.map { it?.clone() }.toTypedArray(),
                    armor = player.inventory.armorContents.map { it?.clone() }.toTypedArray().takeIf { scope.armor },
                    offHand = player.inventory.itemInOffHand.clone().takeIf { scope.offHand },
                    cursor = player.itemOnCursor.clone().takeIf { scope.cursor },
                    top = top,
                    topContents = top?.contents?.map { it?.clone() }?.toTypedArray(),
                )
            }
        }
    }

    private fun ItemStack?.isEmpty(): Boolean = this == null || type.isAir
}
