package ru.ruscrafting.farms.paper.farm.supply

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Item
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.BukkitFarmEntityLookup
import ru.ruscrafting.farms.paper.FarmEntityLookup
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.worksite.PlayerHeldItemLoadout
import java.util.UUID

internal enum class FarmSupplyKind { TOOL, SEEDS, WATER, ARCHERY, FIRE }

internal data class FarmSupplyInteraction(val zoneId: String, val kind: FarmSupplyKind)

private data class SupplyKey(val zoneId: String, val kind: FarmSupplyKind)

/** Owns farm supply displays and tagged temporary player items. */
internal class FarmSupplyController(
    plugin: Plugin,
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
    private val settings: () -> ArcFarmsConfig,
    private val entityLookup: FarmEntityLookup = BukkitFarmEntityLookup,
) {
    private val zoneKey = NamespacedKey(plugin, "farm_supply_zone")
    private val kindKey = NamespacedKey(plugin, "farm_supply_kind")
    private val serviceItemKey = NamespacedKey(plugin, "farm_service_item")
    private val entities = mutableMapOf<SupplyKey, MutableSet<UUID>>()
    private val visualMaterials = mutableMapOf<SupplyKey, Material>()
    private val reconciledZones = mutableSetOf<String>()

    fun owns(entity: Entity): Boolean = entity.persistentDataContainer.has(zoneKey, PersistentDataType.STRING)

    fun interaction(entity: Entity): FarmSupplyInteraction? {
        val zoneId = entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) ?: return null
        val rawKind = entity.persistentDataContainer.get(kindKey, PersistentDataType.STRING) ?: return null
        val kind = runCatching { FarmSupplyKind.valueOf(rawKind) }.getOrNull() ?: return null
        return FarmSupplyInteraction(zoneId, kind)
    }

    fun ensure(runtime: FarmRuntime, point: (FarmSupplyKind) -> FarmPointPosition) {
        val loadedByKind = if (reconciledZones.add(runtime.settings.id)) {
            entityLookup.inWorld(runtime.region.world).asSequence().filter(::owns).mapNotNull { entity ->
                val identity = interaction(entity)
                if (identity?.zoneId == runtime.settings.id) identity.kind to entity
                else {
                    if (entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) == runtime.settings.id) {
                        entity.remove()
                    }
                    null
                }
            }.groupBy({ it.first }, { it.second })
        } else emptyMap()
        FarmSupplyKind.entries.forEach { kind ->
            val position = point(kind)
            val key = SupplyKey(runtime.settings.id, kind)
            val visual = material(runtime, kind)
            val viewRange = FarmSupplyVisibilityPolicy.viewRange(
                state = runtime.state,
                kind = kind,
                nearbyDistanceBlocks = runtime.settings.supplyNearbyViewDistance,
                configuredFullRange = runtime.settings.displayViewRange,
            )
            val world = Bukkit.getWorld(position.world) ?: return@forEach
            val location = Location(world, position.x, position.y, position.z)
            if (!runtime.region.contains(location) || !world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) {
                return@forEach
            }
            val active = (entities[key].orEmpty().mapNotNull(Bukkit::getEntity) + loadedByKind[kind].orEmpty())
                .distinctBy(Entity::getUniqueId)
                .filter { entity -> entity.isValid && interaction(entity) == FarmSupplyInteraction(runtime.settings.id, kind) }
            entities[key] = active.mapTo(mutableSetOf(), Entity::getUniqueId)
            if (active.size == EXPECTED_ENTITY_COUNT && visualMaterials[key] == visual && matchesLocation(active, location)) {
                active.filterIsInstance<ItemDisplay>().forEach { entity ->
                    entity.setItemStack(serviceItem(runtime, kind))
                    entity.uniformScale(runtime.settings.supplies.itemScale)
                    entity.viewRange = viewRange
                    entity.isGlowing = true
                }
                active.filterIsInstance<TextDisplay>().forEach { entity ->
                    entity.text(label(runtime, kind, visual))
                    entity.viewRange = viewRange
                }
                return@forEach
            }
            removeEntities(key, "refresh")
            val item = world.spawn(location.clone().add(0.0, ITEM_Y_OFFSET, 0.0), ItemDisplay::class.java) { entity ->
                entity.setItemStack(serviceItem(runtime, kind))
                entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
                entity.uniformScale(runtime.settings.supplies.itemScale)
                entity.viewRange = viewRange
                entity.isGlowing = true
                entity.isPersistent = false
                mark(entity, runtime.settings.id, kind)
            }
            val label = world.spawn(location.clone().add(0.0, LABEL_Y_OFFSET, 0.0), TextDisplay::class.java) { entity ->
                entity.text(label(runtime, kind, visual))
                entity.billboard = Display.Billboard.VERTICAL
                entity.alignment = TextDisplay.TextAlignment.CENTER
                entity.lineWidth = 180
                entity.backgroundColor = Color.fromARGB(128, 16, 16, 16)
                entity.isShadowed = true
                entity.viewRange = viewRange
                entity.isPersistent = false
                mark(entity, runtime.settings.id, kind)
            }
            val interaction = world.spawn(
                location.clone().add(0.0, INTERACTION_Y_OFFSET, 0.0),
                Interaction::class.java,
            ) { entity ->
                entity.interactionWidth = 1.35f
                entity.interactionHeight = 1.4f
                entity.isResponsive = true
                entity.isPersistent = false
                mark(entity, runtime.settings.id, kind)
            }
            entities[key] = mutableSetOf(item.uniqueId, label.uniqueId, interaction.uniqueId)
            visualMaterials[key] = visual
            debug.event("farm_supply_spawned", "zone" to runtime.settings.id, "kind" to kind, "material" to visual)
        }
    }

    fun give(runtime: FarmRuntime, kind: FarmSupplyKind, player: Player): Boolean {
        val before = player.inventory.storageContents.map { it?.clone() }.toTypedArray()
        removeServiceItems(player, runtime.settings.id, "replace_supply", kind)
        val items = items(runtime, kind)
        if (!PlayerHeldItemLoadout.place(player, items)) {
            player.inventory.storageContents = before
            return false
        }
        if (settings().sounds) player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 0.7f, 1.2f)
        debug.event(
            "farm_supply_given",
            "zone" to runtime.settings.id,
            "kind" to kind,
            "player" to player.name,
            "material" to items.joinToString(",") { it.type.name },
        )
        return true
    }

    fun isServiceItem(item: ItemStack?): Boolean = item?.itemMeta?.persistentDataContainer
        ?.has(serviceItemKey, PersistentDataType.STRING) == true

    fun isServiceItem(item: ItemStack?, kind: FarmSupplyKind): Boolean =
        taggedValue(item)?.substringAfter(':') == kind.name

    fun isServiceItem(item: ItemStack?, zoneId: String, kind: FarmSupplyKind): Boolean =
        taggedValue(item)?.let { matches(it, zoneId, kind) } == true

    fun serviceItemZone(item: ItemStack?, kind: FarmSupplyKind): String? = taggedValue(item)
        ?.takeIf { it.substringAfter(':') == kind.name }
        ?.substringBefore(':')
        ?.takeIf(String::isNotBlank)

    /**
     * Q is an explicit disposal action for the temporary fire hose. The item has
     * already left the inventory when this event is observed, so removing its
     * entity consumes it without leaving a pickup or returning it to the slot.
     */
    fun discardDroppedFireEquipment(player: Player, dropped: Item): Boolean {
        val value = taggedValue(dropped.itemStack) ?: return false
        if (value.substringAfter(':') != FarmSupplyKind.FIRE.name) return false
        val amount = dropped.itemStack.amount
        dropped.remove()
        debug.event(
            "farm_supply_discarded",
            "player" to player.name,
            "zone" to value.substringBefore(':'),
            "kind" to FarmSupplyKind.FIRE,
            "count" to amount,
            "reason" to "player_drop",
        )
        return true
    }

    fun removeServiceItems(
        player: Player,
        zoneId: String? = null,
        reason: String,
        kind: FarmSupplyKind? = null,
    ) {
        var removed = 0
        player.inventory.storageContents.forEachIndexed { index, item ->
            item ?: return@forEachIndexed
            val value = taggedValue(item) ?: return@forEachIndexed
            if (!matches(value, zoneId, kind)) return@forEachIndexed
            removed += item.amount
            player.inventory.setItem(index, null)
        }
        val offHand = player.inventory.itemInOffHand
        taggedValue(offHand)?.takeIf { matches(it, zoneId, kind) }?.let {
            removed += offHand.amount
            player.inventory.setItemInOffHand(null)
        }
        val cursor = player.itemOnCursor
        taggedValue(cursor)?.takeIf { matches(it, zoneId, kind) }?.let {
            removed += cursor.amount
            player.setItemOnCursor(null)
        }
        @Suppress("UNNECESSARY_SAFE_CALL")
        player.openInventory.topInventory?.let { top ->
            top.contents.forEachIndexed { index, item ->
                item ?: return@forEachIndexed
                val value = taggedValue(item) ?: return@forEachIndexed
                if (!matches(value, zoneId, kind)) return@forEachIndexed
                removed += item.amount
                top.setItem(index, null)
            }
        }
        if (removed > 0) {
            debug.event(
                "farm_supply_removed",
                "player" to player.name,
                "zone" to zoneId,
                "kind" to kind,
                "count" to removed,
                "reason" to reason,
            )
        }
    }

    /** Rebuilds tagged temporary items in place so material/model config changes do not steal player gear. */
    fun reconfigurePlayerItems(runtimes: Collection<FarmRuntime>) {
        val byId = runtimes.associateBy { it.settings.id }
        Bukkit.getOnlinePlayers().forEach { player ->
            player.inventory.storageContents.forEachIndexed { slot, current ->
                refreshed(current, byId)?.let { player.inventory.setItem(slot, it) }
            }
            runCatching { player.openInventory.topInventory }.getOrNull()?.let { top ->
                for (slot in 0 until top.size) refreshed(top.getItem(slot), byId)?.let { top.setItem(slot, it) }
            }
            refreshed(player.inventory.itemInOffHand, byId)?.let(player.inventory::setItemInOffHand)
            refreshed(player.itemOnCursor, byId)?.let(player::setItemOnCursor)
        }
    }

    fun refresh(runtime: FarmRuntime, kind: FarmSupplyKind, point: (FarmSupplyKind) -> FarmPointPosition, reason: String) {
        removeEntities(SupplyKey(runtime.settings.id, kind), reason)
        ensure(runtime, point)
    }

    fun cleanup(reason: String) {
        var removed = 0
        entityLookup.inAllWorlds().filter(::owns).forEach { entity ->
            entity.remove()
            removed++
        }
        entities.clear()
        visualMaterials.clear()
        reconciledZones.clear()
        Bukkit.getOnlinePlayers().forEach { removeServiceItems(it, reason = reason) }
        if (removed > 0) debug.event("farm_supply_entities_cleanup", "count" to removed, "reason" to reason)
    }

    private fun removeEntities(key: SupplyKey, reason: String) {
        val ids = entities.remove(key).orEmpty()
        ids.forEach { id -> Bukkit.getEntity(id)?.remove() }
        visualMaterials.remove(key)
        if (ids.isNotEmpty()) {
            debug.event(
                "farm_supply_removed",
                "zone" to key.zoneId,
                "kind" to key.kind,
                "count" to ids.size,
                "reason" to reason,
            )
        }
    }

    private fun material(runtime: FarmRuntime, kind: FarmSupplyKind): Material = when (kind) {
        FarmSupplyKind.TOOL -> MaterialRules.material(runtime.settings.supplies.toolMaterial)
        FarmSupplyKind.WATER -> Material.WATER_BUCKET
        FarmSupplyKind.ARCHERY -> MaterialRules.material(runtime.settings.supplies.bowMaterial)
        FarmSupplyKind.FIRE -> MaterialRules.material(runtime.settings.supplies.fireEquipmentMaterial)
        FarmSupplyKind.SEEDS -> runtime.state.preparationCrop
            ?.let(MaterialRules::material)
            ?.let(MaterialRules::seedForCrop)
            ?: Material.WHEAT_SEEDS
    }

    private fun label(runtime: FarmRuntime, kind: FarmSupplyKind, material: Material): Component = when (kind) {
        FarmSupplyKind.TOOL -> locale.render(
            MessageKey.FARM_SUPPLY_TOOL,
            values = mapOf("tool" to MaterialRules.itemComponent(material)),
        )
        FarmSupplyKind.SEEDS -> locale.render(
            MessageKey.FARM_SUPPLY_SEEDS,
            values = mapOf("seed" to MaterialRules.itemComponent(material)),
        )
        FarmSupplyKind.WATER -> locale.render(MessageKey.FARM_SUPPLY_WATER)
        FarmSupplyKind.ARCHERY -> locale.render(MessageKey.FARM_SUPPLY_ARCHERY)
        FarmSupplyKind.FIRE -> locale.render(MessageKey.FARM_SUPPLY_FIRE)
    }

    private fun items(runtime: FarmRuntime, kind: FarmSupplyKind): List<ItemStack> {
        val supplies = runtime.settings.supplies
        val raw = when (kind) {
            FarmSupplyKind.ARCHERY -> listOf(
                ItemStack(MaterialRules.material(supplies.bowMaterial)),
                ItemStack(MaterialRules.material(supplies.arrowMaterial), supplies.arrowAmount),
            )
            FarmSupplyKind.SEEDS -> listOf(ItemStack(material(runtime, kind), supplies.seedAmount))
            else -> listOf(serviceItem(runtime, kind))
        }
        return raw.onEach { item ->
            item.editMeta { meta ->
                meta.persistentDataContainer.set(
                    serviceItemKey,
                    PersistentDataType.STRING,
                    "${runtime.settings.id}:${kind.name}",
                )
                if (kind == FarmSupplyKind.TOOL || kind == FarmSupplyKind.FIRE ||
                    (kind == FarmSupplyKind.ARCHERY && item.type.name.endsWith("BOW"))
                ) {
                    meta.isUnbreakable = true
                }
            }
        }
    }

    private fun refreshed(current: ItemStack?, runtimes: Map<String, FarmRuntime>): ItemStack? {
        current ?: return null
        val value = taggedValue(current) ?: return null
        val runtime = runtimes[value.substringBefore(':')] ?: return null
        val kind = runCatching { FarmSupplyKind.valueOf(value.substringAfter(':')) }.getOrNull() ?: return null
        val templates = items(runtime, kind)
        val template = if (kind == FarmSupplyKind.ARCHERY) {
            val bow = current.type.name.endsWith("BOW")
            templates.firstOrNull { it.type.name.endsWith("BOW") == bow }
        } else templates.firstOrNull()
        return template?.clone()?.also { it.amount = current.amount.coerceAtMost(it.maxStackSize) }
    }

    private fun serviceItem(runtime: FarmRuntime, kind: FarmSupplyKind): ItemStack = ItemStack(material(runtime, kind)).also { item ->
        if (kind != FarmSupplyKind.FIRE) return@also
        val supplies = runtime.settings.supplies
        item.editMeta { meta ->
            if (supplies.fireEquipmentCustomModelData > 0) {
                meta.setCustomModelData(supplies.fireEquipmentCustomModelData)
            }
            supplies.fireEquipmentItemModel?.let { model ->
                meta.setItemModel(requireNotNull(NamespacedKey.fromString(model)))
            }
            meta.displayName(locale.render(MessageKey.FARM_SUPPLY_FIRE_ITEM_NAME).decoration(TextDecoration.ITALIC, false))
            meta.lore(listOf(locale.render(MessageKey.FARM_SUPPLY_FIRE_ITEM_LORE).decoration(TextDecoration.ITALIC, false)))
        }
    }

    private fun mark(entity: Entity, zoneId: String, kind: FarmSupplyKind) {
        entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, zoneId)
        entity.persistentDataContainer.set(kindKey, PersistentDataType.STRING, kind.name)
    }

    private fun matchesLocation(owned: Collection<Entity>, base: Location): Boolean {
        fun Entity.near(yOffset: Double): Boolean = world == base.world &&
            location.distanceSquared(base.clone().add(0.0, yOffset, 0.0)) <= 0.04
        return owned.count { it is ItemDisplay && it.near(ITEM_Y_OFFSET) } == 1 &&
            owned.count { it is TextDisplay && it.near(LABEL_Y_OFFSET) } == 1 &&
            owned.count { it is Interaction && it.near(INTERACTION_Y_OFFSET) } == 1
    }

    private fun taggedValue(item: ItemStack?): String? = item?.itemMeta?.persistentDataContainer
        ?.get(serviceItemKey, PersistentDataType.STRING)

    private fun matches(value: String, zoneId: String?, kind: FarmSupplyKind?): Boolean =
        (zoneId == null || value.substringBefore(':') == zoneId) &&
            (kind == null || value.substringAfter(':') == kind.name)

    private fun ItemDisplay.uniformScale(scale: Float) {
        transformation = Transformation(
            Vector3f(),
            AxisAngle4f(),
            Vector3f(scale, scale, scale),
            AxisAngle4f(),
        )
    }

    private companion object {
        const val EXPECTED_ENTITY_COUNT = 3
        const val ITEM_Y_OFFSET = 0.25
        const val LABEL_Y_OFFSET = 1.15
        const val INTERACTION_Y_OFFSET = 0.25
    }
}
