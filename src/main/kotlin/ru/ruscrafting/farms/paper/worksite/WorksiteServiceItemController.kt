package ru.ruscrafting.farms.paper.worksite

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import java.util.UUID

internal data class ServiceItemIdentity(
    val activity: ActivityKind,
    val zoneId: String,
    val sequence: Long,
    val objectiveNonce: Long,
    val role: ObjectiveTargetRole,
    val itemId: String,
) {
    init {
        require(zoneId.matches(IDENTIFIER)) { "Invalid service item zone: $zoneId" }
        require(itemId.matches(IDENTIFIER)) { "Invalid service item id: $itemId" }
        require(sequence >= 0 && objectiveNonce >= 0) { "Service item sequence cannot be negative" }
    }

    private companion object {
        val IDENTIFIER = Regex("[a-z0-9][a-z0-9_-]{0,47}")
    }
}

internal interface WorksiteServiceItemOwner {
    fun isActive(identity: ServiceItemIdentity): Boolean
    fun release(playerId: UUID, identity: ServiceItemIdentity, reason: WorksitePlayerReleaseReason)
}

internal interface WorksiteServiceItems {
    fun issue(player: Player, identity: ServiceItemIdentity, material: Material, name: Component): ItemStack?
    fun issue(
        player: Player,
        identity: ServiceItemIdentity,
        material: Material,
        name: Component,
        customModelData: Int,
        itemModel: NamespacedKey?,
    ): ItemStack? = issue(player, identity, material, name)?.also { item ->
        item.editMeta { meta ->
            if (customModelData > 0) {
                @Suppress("DEPRECATION")
                meta.setCustomModelData(customModelData)
            }
            itemModel?.let(meta::setItemModel)
        }
    }
    fun consume(player: Player, expected: ServiceItemIdentity): Boolean
    fun identity(item: ItemStack?): ServiceItemIdentity?
    fun isServiceItem(item: ItemStack?): Boolean
}

internal class LateBoundWorksiteServiceItems : WorksiteServiceItems {
    private var delegate: WorksiteServiceItems? = null

    fun bind(items: WorksiteServiceItems) {
        check(delegate == null) { "Worksite service items are already bound" }
        delegate = items
    }

    override fun issue(player: Player, identity: ServiceItemIdentity, material: Material, name: Component): ItemStack? =
        delegate?.issue(player, identity, material, name)

    override fun issue(
        player: Player,
        identity: ServiceItemIdentity,
        material: Material,
        name: Component,
        customModelData: Int,
        itemModel: NamespacedKey?,
    ): ItemStack? = delegate?.issue(player, identity, material, name, customModelData, itemModel)

    override fun consume(player: Player, expected: ServiceItemIdentity): Boolean = delegate?.consume(player, expected) == true
    override fun identity(item: ItemStack?): ServiceItemIdentity? = delegate?.identity(item)
    override fun isServiceItem(item: ItemStack?): Boolean = delegate?.isServiceItem(item) == true
}

internal class WorksiteServiceItemController(
    plugin: Plugin,
    private val owner: WorksiteServiceItemOwner,
) : WorksiteServiceItems {
    private val markerKey = NamespacedKey(plugin, "worksite_service_item")
    private val activityKey = NamespacedKey(plugin, "worksite_activity")
    private val zoneKey = NamespacedKey(plugin, "worksite_zone")
    private val sequenceKey = NamespacedKey(plugin, "worksite_sequence")
    private val objectiveKey = NamespacedKey(plugin, "worksite_objective")
    private val roleKey = NamespacedKey(plugin, "worksite_role")
    private val itemKey = NamespacedKey(plugin, "worksite_item_id")

    override fun issue(player: Player, identity: ServiceItemIdentity, material: Material, name: Component): ItemStack? =
        issue(player, identity, material, name, 0, null)

    override fun issue(
        player: Player,
        identity: ServiceItemIdentity,
        material: Material,
        name: Component,
        customModelData: Int,
        itemModel: NamespacedKey?,
    ): ItemStack? {
        require(material.isItem && !material.isAir) { "Service item material must be a real item" }
        require(customModelData >= 0) { "Service item custom model data cannot be negative" }
        if (!owner.isActive(identity)) return null
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(name.decoration(TextDecoration.ITALIC, false))
            if (customModelData > 0) {
                @Suppress("DEPRECATION")
                meta.setCustomModelData(customModelData)
            }
            itemModel?.let(meta::setItemModel)
            val pdc = meta.persistentDataContainer
            pdc.set(markerKey, PersistentDataType.INTEGER, MARKER)
            pdc.set(activityKey, PersistentDataType.STRING, identity.activity.name)
            pdc.set(zoneKey, PersistentDataType.STRING, identity.zoneId)
            pdc.set(sequenceKey, PersistentDataType.LONG, identity.sequence)
            pdc.set(objectiveKey, PersistentDataType.LONG, identity.objectiveNonce)
            pdc.set(roleKey, PersistentDataType.STRING, identity.role.value)
            pdc.set(itemKey, PersistentDataType.STRING, identity.itemId)
        }
        return item.takeIf { player.inventory.addItem(it).isEmpty() }
    }

    override fun isServiceItem(item: ItemStack?): Boolean = item?.itemMeta?.persistentDataContainer
        ?.get(markerKey, PersistentDataType.INTEGER) == MARKER

    override fun identity(item: ItemStack?): ServiceItemIdentity? {
        if (!isServiceItem(item)) return null
        val pdc = item?.itemMeta?.persistentDataContainer ?: return null
        return runCatching {
            ServiceItemIdentity(
                activity = ActivityKind.valueOf(requireNotNull(pdc.get(activityKey, PersistentDataType.STRING))),
                zoneId = requireNotNull(pdc.get(zoneKey, PersistentDataType.STRING)),
                sequence = requireNotNull(pdc.get(sequenceKey, PersistentDataType.LONG)),
                objectiveNonce = requireNotNull(pdc.get(objectiveKey, PersistentDataType.LONG)),
                role = ObjectiveTargetRole(requireNotNull(pdc.get(roleKey, PersistentDataType.STRING))),
                itemId = requireNotNull(pdc.get(itemKey, PersistentDataType.STRING)),
            )
        }.getOrNull()
    }

    override fun consume(player: Player, expected: ServiceItemIdentity): Boolean {
        player.inventory.storageContents.forEachIndexed { index, item ->
            if (identity(item) == expected) {
                player.inventory.setItem(index, null)
                return true
            }
        }
        if (identity(player.inventory.itemInOffHand) == expected) {
            player.inventory.setItemInOffHand(null)
            return true
        }
        if (identity(player.itemOnCursor) == expected) {
            player.setItemOnCursor(null)
            return true
        }
        return false
    }

    fun guardDrop(item: ItemStack?): Boolean = isServiceItem(item)

    fun guardInventory(event: InventoryClickEvent): Boolean {
        val player = event.whoClicked as? Player ?: return false
        val hotbar = if (event.click == ClickType.SWAP_OFFHAND) {
            player.inventory.itemInOffHand
        } else {
            event.hotbarButton.takeIf { it >= 0 }?.let(player.inventory::getItem)
        }
        val currentTagged = isServiceItem(event.currentItem)
        val cursorTagged = isServiceItem(event.cursor)
        val hotbarTagged = isServiceItem(hotbar)
        if (!currentTagged && !cursorTagged && !hotbarTagged) return false
        val topSize = event.view.topInventory.size
        val clickedOutside = event.rawSlot < 0
        val clickedTop = event.rawSlot in 0 until topSize
        val cancel = clickedOutside && cursorTagged || clickedTop || event.isShiftClick && currentTagged
        if (cancel) event.isCancelled = true
        return cancel
    }

    fun guardInventory(event: InventoryDragEvent): Boolean {
        if (!isServiceItem(event.oldCursor)) return false
        val topSize = event.view.topInventory.size
        val cancel = event.rawSlots.any { it in 0 until topSize }
        if (cancel) event.isCancelled = true
        return cancel
    }

    fun cleanupPlayer(player: Player, reason: WorksitePlayerReleaseReason): Int {
        val released = linkedSetOf<ServiceItemIdentity>()
        var removed = 0
        fun remove(item: ItemStack?, clear: () -> Unit) {
            if (!isServiceItem(item)) return
            identity(item)?.let(released::add)
            removed += item?.amount ?: 0
            clear()
        }

        player.inventory.storageContents.forEachIndexed { index, item -> remove(item) { player.inventory.setItem(index, null) } }
        player.inventory.armorContents.forEachIndexed { index, item ->
            remove(item) {
                val armor = player.inventory.armorContents
                armor[index] = null
                player.inventory.armorContents = armor
            }
        }
        remove(player.inventory.itemInOffHand) { player.inventory.setItemInOffHand(null) }
        remove(player.itemOnCursor) { player.setItemOnCursor(null) }
        @Suppress("UNNECESSARY_SAFE_CALL")
        player.openInventory.topInventory?.let { top -> removed += removeFrom(top, released) }

        released.forEach { identity -> owner.release(player.uniqueId, identity, reason) }
        return removed
    }

    private fun removeFrom(inventory: Inventory, released: MutableSet<ServiceItemIdentity>): Int {
        var removed = 0
        inventory.contents.forEachIndexed { index, item ->
            if (!isServiceItem(item)) return@forEachIndexed
            identity(item)?.let(released::add)
            removed += item?.amount ?: 0
            inventory.setItem(index, null)
        }
        return removed
    }

    private companion object {
        const val MARKER = 1
    }
}
