package ru.ruscrafting.farms.paper.farm.incident.route

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import net.kyori.adventure.text.format.TextDecoration
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.FarmRouteDeliverySettings
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerItems
import java.util.UUID

/** Inventory-safe ownership boundary for the route gunner's temporary rifle. */
internal class FarmFoodDeliveryGear(
    plugin: Plugin,
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
) {
    private val ownerKey = NamespacedKey(plugin, "farm_food_rifle_owner")

    fun give(player: Player, zoneId: String, sequence: Long, settings: FarmRouteDeliverySettings): Boolean {
        val owner = Owner(zoneId, sequence, player.uniqueId)
        find(player, owner)?.let { slot ->
            player.inventory.setItem(slot, rifle(player, owner, settings))
            return true
        }
        val rifle = rifle(player, owner, settings)
        val slot = WorksitePlayerItems.placeSelectedFirst(player, listOf(rifle))?.singleOrNull() ?: return false
        debug.event(
            "farm_food_rifle_given", "zone" to zoneId, "sequence" to sequence,
            "player" to player.name, "slot" to slot, "material" to settings.rifleMaterial,
            "custom_model_data" to settings.rifleCustomModelData, "item_model" to settings.rifleItemModel,
        )
        return true
    }

    private fun rifle(player: Player, owner: Owner, settings: FarmRouteDeliverySettings): ItemStack =
        ItemStack(requireNotNull(Material.matchMaterial(settings.rifleMaterial))).also { rifle ->
            rifle.editMeta { meta ->
                meta.displayName(locale.render(MessageKey.FARM_ROUTE_RIFLE_NAME, player).decoration(TextDecoration.ITALIC, false))
                meta.lore(listOf(locale.render(MessageKey.FARM_ROUTE_RIFLE_LORE, player).decoration(TextDecoration.ITALIC, false)))
                meta.isUnbreakable = true
                if (settings.rifleCustomModelData > 0) {
                    @Suppress("DEPRECATION")
                    meta.setCustomModelData(settings.rifleCustomModelData)
                }
                settings.rifleItemModel?.let { itemModel ->
                    meta.setItemModel(requireNotNull(NamespacedKey.fromString(itemModel)))
                }
                meta.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, owner.encoded())
            }
        }

    fun owns(item: ItemStack?): Boolean = rawOwner(item) != null

    fun owns(item: ItemStack?, zoneId: String, sequence: Long, playerId: UUID): Boolean =
        rawOwner(item) == Owner(zoneId, sequence, playerId).encoded()

    fun isHolding(player: Player, zoneId: String, sequence: Long): Boolean =
        owns(player.inventory.itemInMainHand, zoneId, sequence, player.uniqueId)

    fun remove(player: Player, zoneId: String? = null, sequence: Long? = null, reason: String) {
        val removal = WorksitePlayerItems.removeAll(player) { item ->
            val owner = rawOwner(item)?.let(Owner::decode) ?: return@removeAll false
            (zoneId == null || owner.zoneId == zoneId) && (sequence == null || owner.sequence == sequence)
        }
        if (removal.items.isNotEmpty()) {
            debug.event(
                "farm_food_rifle_removed", "zone" to zoneId, "sequence" to sequence,
                "player" to player.name, "count" to removal.items.size, "reason" to reason,
            )
        }
    }

    private fun find(player: Player, owner: Owner): Int? = WorksitePlayerItems.findInventorySlot(player) { item ->
        rawOwner(item) == owner.encoded()
    }

    private fun rawOwner(item: ItemStack?): String? = item?.itemMeta?.persistentDataContainer
        ?.get(ownerKey, PersistentDataType.STRING)

    private data class Owner(val zoneId: String, val sequence: Long, val playerId: UUID) {
        fun encoded(): String = "$zoneId:$sequence:$playerId"

        companion object {
            fun decode(raw: String): Owner? {
                val parts = raw.split(':', limit = 3)
                if (parts.size != 3) return null
                return Owner(parts[0], parts[1].toLongOrNull() ?: return null, runCatching { UUID.fromString(parts[2]) }.getOrNull() ?: return null)
            }
        }
    }

}
