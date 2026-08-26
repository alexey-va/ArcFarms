package ru.ruscrafting.farms.paper.farm.supply

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
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
import java.util.UUID

internal enum class FarmSupplyKind { TOOL, SEEDS, WATER }

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
                return@forEach
            }
            removeEntities(key, "refresh")
            val item = world.spawn(location.clone().add(0.0, ITEM_Y_OFFSET, 0.0), ItemDisplay::class.java) { entity ->
                entity.setItemStack(ItemStack(visual))
                entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
                entity.uniformScale(ITEM_SCALE)
                entity.viewRange = runtime.settings.displayViewRange
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
                entity.viewRange = 0.5f
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
        removeServiceItems(player, runtime.settings.id, "replace_supply", kind)
        val material = material(runtime, kind)
        val amount = if (kind == FarmSupplyKind.SEEDS) runtime.settings.supplies.seedAmount else 1
        val item = ItemStack(material, amount)
        val meta = item.itemMeta
        meta.persistentDataContainer.set(serviceItemKey, PersistentDataType.STRING, "${runtime.settings.id}:${kind.name}")
        if (kind == FarmSupplyKind.TOOL) meta.isUnbreakable = true
        item.itemMeta = meta
        if (player.inventory.firstEmpty() < 0) return false
        player.inventory.addItem(item)
        if (settings().sounds) player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 0.7f, 1.2f)
        debug.event(
            "farm_supply_given",
            "zone" to runtime.settings.id,
            "kind" to kind,
            "player" to player.name,
            "material" to material,
        )
        return true
    }

    fun isServiceItem(item: ItemStack?): Boolean = item?.itemMeta?.persistentDataContainer
        ?.has(serviceItemKey, PersistentDataType.STRING) == true

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
        const val ITEM_SCALE = 1.35f
    }
}
