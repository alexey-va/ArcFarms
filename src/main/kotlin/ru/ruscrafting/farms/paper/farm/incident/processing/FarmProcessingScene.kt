package ru.ruscrafting.farms.paper.farm.incident.processing

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.ruscrafting.farms.config.FarmItemDisplayTransform
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.BukkitFarmEntityLookup
import ru.ruscrafting.farms.paper.FarmEntityLookup
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayRenderer
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayStyle
import java.util.UUID

internal enum class FarmProcessingSceneRole {
    MACHINE,
    WHEEL,
    INPUT_RACK,
    OUTPUT_PALLET,
    RAW_PACKAGE,
    RAW_INTERACTION,
    PRODUCT_PACKAGE,
    PRODUCT_INTERACTION,
    DELIVERED_PACKAGE,
    MACHINE_INTERACTION,
    LABEL,
    CRANK_TRACK,
}

internal data class FarmProcessingSceneIdentity(
    val zoneId: String,
    val sequence: Long,
    val role: FarmProcessingSceneRole,
    val index: Int,
)

internal data class FarmProcessingSceneObject(
    val role: FarmProcessingSceneRole,
    val index: Int,
    val location: Location,
    val item: ItemStack? = null,
    val text: Component? = null,
    val transform: FarmItemDisplayTransform = FarmItemDisplayTransform.FIXED,
    val scale: Float = 1f,
    val yawOffset: Float = 0f,
    val glowing: Boolean = false,
    val interactionWidth: Float = 1.1f,
    val interactionHeight: Float = 1.2f,
    val billboard: Display.Billboard = Display.Billboard.VERTICAL,
    val pitchOffset: Float = 0f,
)

internal data class FarmProcessingSceneSpec(
    val zoneId: String,
    val sequence: Long,
    val viewRange: Float,
    val spawnPerTick: Int,
    val objects: List<FarmProcessingSceneObject>,
)

internal class FarmProcessingSceneSpecCache<R> {
    private data class Entry<R>(
        val revision: R,
        val spec: FarmProcessingSceneSpec,
    )

    private val entries = mutableMapOf<String, Entry<R>>()

    fun resolve(
        zoneId: String,
        revision: R,
        create: () -> FarmProcessingSceneSpec,
    ): FarmProcessingSceneSpec {
        entries[zoneId]?.takeIf { it.revision == revision }?.let { return it.spec }
        return create().also { spec -> entries[zoneId] = Entry(revision, spec) }
    }

    fun invalidate(zoneId: String) {
        entries.remove(zoneId)
    }

    fun clear() {
        entries.clear()
    }
}

/** Reconstructible, non-persistent display scene with bounded creation per tick. */
internal class FarmProcessingScene(
    plugin: Plugin,
    private val debug: ArcFarmsDebug,
    private val textDisplays: FarmTextDisplayRenderer,
    private val entityLookup: FarmEntityLookup = BukkitFarmEntityLookup,
) {
    private val zoneKey = NamespacedKey(plugin, "farm_processing_zone")
    private val sequenceKey = NamespacedKey(plugin, "farm_processing_sequence")
    private val roleKey = NamespacedKey(plugin, "farm_processing_role")
    private val indexKey = NamespacedKey(plugin, "farm_processing_index")
    private val desired = mutableMapOf<String, FarmProcessingSceneSpec>()
    private val desiredTargets = mutableMapOf<String, Map<FarmProcessingSceneIdentity, FarmProcessingSceneObject>>()
    private val tracked = mutableMapOf<FarmProcessingSceneIdentity, UUID>()
    private val reconciled = mutableMapOf<String, Long>()

    fun owns(entity: Entity): Boolean = entity.persistentDataContainer.has(zoneKey, PersistentDataType.STRING)

    fun identity(entity: Entity): FarmProcessingSceneIdentity? = decode(entity)

    fun hasZone(zoneId: String): Boolean = desired.containsKey(zoneId) || tracked.keys.any { it.zoneId == zoneId }

    fun ensure(spec: FarmProcessingSceneSpec) {
        val previous = desired[spec.zoneId]
        val changed = previous != spec
        val activeSpec = if (changed) spec else requireNotNull(previous)
        val targets = if (changed) {
            desired[spec.zoneId] = spec
            spec.objects.associateBy { target -> identity(spec, target) }
                .also { desiredTargets[spec.zoneId] = it }
        } else {
            requireNotNull(desiredTargets[spec.zoneId])
        }
        reconcileSequence(activeSpec)
        if (changed) {
            tracked.keys.filter { it.zoneId == spec.zoneId && it !in targets }.toList().forEach(::remove)
        }

        var spawned = 0
        targets.forEach { (identity, target) ->
            val existing = tracked[identity]?.let(Bukkit::getEntity)
            if (existing != null && existing.isValid && matches(existing, identity.role)) {
                if (changed) normalize(existing, activeSpec, target, identity)
                return@forEach
            }
            tracked.remove(identity)
            if (spawned >= activeSpec.spawnPerTick || !chunkLoaded(target.location)) return@forEach
            tracked[identity] = spawn(activeSpec, target, identity).uniqueId
            spawned++
        }
    }

    fun item(zoneId: String, role: FarmProcessingSceneRole): ItemDisplay? = tracked.entries.firstOrNull { (identity, _) ->
        identity.zoneId == zoneId && identity.role == role
    }?.value?.let(Bukkit::getEntity) as? ItemDisplay

    fun onChunkLoad(chunk: Chunk) {
        chunk.entities.filter(::owns).forEach { entity ->
            decode(entity)?.let { tracked.remove(it) }
            entity.remove()
        }
    }

    fun clear(zoneId: String, reason: String) {
        desired.remove(zoneId)
        desiredTargets.remove(zoneId)
        reconciled.remove(zoneId)
        val identities = tracked.keys.filter { it.zoneId == zoneId }.toList()
        identities.forEach(::remove)
        if (identities.isNotEmpty()) {
            debug.event("farm_processing_scene_cleared", "zone" to zoneId, "count" to identities.size, "reason" to reason)
        }
    }

    fun cleanup(reason: String) {
        val entities = entityLookup.inAllWorlds().filter(::owns)
        entities.forEach(Entity::remove)
        desired.clear()
        desiredTargets.clear()
        tracked.clear()
        reconciled.clear()
        if (entities.isNotEmpty()) debug.event("farm_processing_scene_cleanup", "count" to entities.size, "reason" to reason)
    }

    private fun reconcileSequence(spec: FarmProcessingSceneSpec) {
        if (reconciled[spec.zoneId] == spec.sequence) return
        tracked.keys.removeIf { it.zoneId == spec.zoneId }
        val retained = mutableMapOf<FarmProcessingSceneIdentity, Entity>()
        entityLookup.inWorld(requireNotNull(spec.objects.firstOrNull()?.location?.world)).filter(::owns).forEach { entity ->
            val identity = decode(entity)
            if (
                identity == null || identity.zoneId != spec.zoneId || identity.sequence != spec.sequence ||
                !matches(entity, identity.role) || identity in retained
            ) {
                if (identity?.zoneId == spec.zoneId) entity.remove()
                return@forEach
            }
            retained[identity] = entity
        }
        retained.forEach { (identity, entity) -> tracked[identity] = entity.uniqueId }
        reconciled[spec.zoneId] = spec.sequence
        debug.event(
            "farm_processing_scene_reconciled",
            "zone" to spec.zoneId,
            "sequence" to spec.sequence,
            "retained" to retained.size,
        )
    }

    private fun spawn(
        spec: FarmProcessingSceneSpec,
        target: FarmProcessingSceneObject,
        identity: FarmProcessingSceneIdentity,
    ): Entity {
        val world = requireNotNull(target.location.world)
        val entity = when (target.role) {
            FarmProcessingSceneRole.RAW_INTERACTION,
            FarmProcessingSceneRole.PRODUCT_INTERACTION,
            FarmProcessingSceneRole.MACHINE_INTERACTION,
            -> world.spawn(target.location, Interaction::class.java)
            FarmProcessingSceneRole.LABEL,
            FarmProcessingSceneRole.CRANK_TRACK,
            -> world.spawn(target.location, TextDisplay::class.java)
            else -> world.spawn(target.location, ItemDisplay::class.java)
        }
        normalize(entity, spec, target, identity)
        debug.event(
            "farm_processing_scene_spawned",
            "zone" to identity.zoneId,
            "sequence" to identity.sequence,
            "role" to identity.role,
            "index" to identity.index,
        )
        return entity
    }

    private fun normalize(
        entity: Entity,
        spec: FarmProcessingSceneSpec,
        target: FarmProcessingSceneObject,
        identity: FarmProcessingSceneIdentity,
    ) {
        entity.isPersistent = false
        entity.isInvulnerable = true
        entity.setGravity(false)
        if (entity.world !== target.location.world || entity.location.distanceSquared(target.location) > 0.0004) {
            entity.teleport(target.location)
        }
        mark(entity, identity)
        when (entity) {
            is ItemDisplay -> {
                entity.setItemStack(requireNotNull(target.item).clone())
                entity.itemDisplayTransform = target.transform.bukkit
                entity.transformation = Transformation(
                    Vector3f(),
                    AxisAngle4f(Math.toRadians(target.yawOffset.toDouble()).toFloat(), 0f, 1f, 0f),
                    Vector3f(target.scale, target.scale, target.scale),
                    AxisAngle4f(),
                )
                entity.viewRange = spec.viewRange
                entity.displayWidth = maxOf(1f, target.scale)
                entity.displayHeight = maxOf(1f, target.scale)
                entity.isGlowing = target.glowing
                entity.teleportDuration = 1
                entity.interpolationDuration = 2
            }
            is Interaction -> {
                entity.interactionWidth = target.interactionWidth
                entity.interactionHeight = target.interactionHeight
                entity.isResponsive = true
            }
            is TextDisplay -> {
                textDisplays.render(
                    entity,
                    requireNotNull(target.text),
                    FarmTextDisplayStyle(billboard = target.billboard, viewRange = spec.viewRange),
                )
                entity.transformation = Transformation(
                    Vector3f(),
                    AxisAngle4f(Math.toRadians(target.pitchOffset.toDouble()).toFloat(), 1f, 0f, 0f),
                    Vector3f(target.scale, target.scale, target.scale),
                    AxisAngle4f(),
                )
            }
        }
    }

    private fun remove(identity: FarmProcessingSceneIdentity) {
        tracked.remove(identity)?.let(Bukkit::getEntity)?.remove()
    }

    private fun mark(entity: Entity, identity: FarmProcessingSceneIdentity) {
        entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, identity.zoneId)
        entity.persistentDataContainer.set(sequenceKey, PersistentDataType.LONG, identity.sequence)
        entity.persistentDataContainer.set(roleKey, PersistentDataType.STRING, identity.role.name)
        entity.persistentDataContainer.set(indexKey, PersistentDataType.INTEGER, identity.index)
    }

    private fun decode(entity: Entity): FarmProcessingSceneIdentity? {
        val data = entity.persistentDataContainer
        val zone = data.get(zoneKey, PersistentDataType.STRING) ?: return null
        val sequence = data.get(sequenceKey, PersistentDataType.LONG) ?: return null
        val role = data.get(roleKey, PersistentDataType.STRING)
            ?.let { runCatching { FarmProcessingSceneRole.valueOf(it) }.getOrNull() } ?: return null
        val index = data.get(indexKey, PersistentDataType.INTEGER) ?: return null
        return FarmProcessingSceneIdentity(zone, sequence, role, index)
    }

    private fun identity(spec: FarmProcessingSceneSpec, target: FarmProcessingSceneObject) =
        FarmProcessingSceneIdentity(spec.zoneId, spec.sequence, target.role, target.index)

    private fun matches(entity: Entity, role: FarmProcessingSceneRole): Boolean = when (role) {
        FarmProcessingSceneRole.RAW_INTERACTION,
        FarmProcessingSceneRole.PRODUCT_INTERACTION,
        FarmProcessingSceneRole.MACHINE_INTERACTION,
        -> entity is Interaction
        FarmProcessingSceneRole.LABEL,
        FarmProcessingSceneRole.CRANK_TRACK,
        -> entity is TextDisplay
        else -> entity is ItemDisplay
    }

    private fun chunkLoaded(location: Location): Boolean = location.world?.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4) == true

    private val FarmItemDisplayTransform.bukkit: ItemDisplay.ItemDisplayTransform
        get() = when (this) {
            FarmItemDisplayTransform.GROUND -> ItemDisplay.ItemDisplayTransform.GROUND
            FarmItemDisplayTransform.FIXED -> ItemDisplay.ItemDisplayTransform.FIXED
            FarmItemDisplayTransform.HEAD -> ItemDisplay.ItemDisplayTransform.HEAD
        }
}
