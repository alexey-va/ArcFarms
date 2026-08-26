package ru.ruscrafting.farms.paper

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

internal enum class FarmSpecialSceneRole { CHANNEL_GATE, CHANNEL_HITBOX }

internal data class FarmSpecialSceneIdentity(
    val zoneId: String,
    val sequence: Long,
    val role: FarmSpecialSceneRole,
    val index: Int,
)

internal data class FarmSpecialSceneObject(
    val role: FarmSpecialSceneRole,
    val index: Int,
    val location: Location,
    val item: ItemStack? = null,
    val scale: Float = 1f,
    val active: Boolean = false,
)

internal data class FarmSpecialSceneSpec(
    val zoneId: String,
    val sequence: Long,
    val viewRange: Float,
    val objects: List<FarmSpecialSceneObject>,
)

/**
 * Reconstructible visuals for special farm incidents. Entities are never
 * persistent: the persisted shift state is authoritative and loaded chunks
 * are reconciled before anything is spawned.
 */
internal class FarmSpecialIncidentSceneManager(
    plugin: Plugin,
    private val debug: ArcFarmsDebug,
) {
    private val zoneKey = NamespacedKey(plugin, "farm_special_scene_zone")
    private val sequenceKey = NamespacedKey(plugin, "farm_special_scene_sequence")
    private val roleKey = NamespacedKey(plugin, "farm_special_scene_role")
    private val indexKey = NamespacedKey(plugin, "farm_special_scene_index")
    private val desired = ConcurrentHashMap<String, FarmSpecialSceneSpec>()

    fun owns(entity: Entity): Boolean = entity.persistentDataContainer.has(zoneKey, PersistentDataType.STRING)

    fun metadata(entity: Entity): FarmSpecialSceneIdentity? = decode(entity)

    fun ensure(spec: FarmSpecialSceneSpec) {
        desired[spec.zoneId] = spec
        val targetChunks = spec.objects.mapNotNull { loadedChunk(it.location) }.distinctBy { it.world.uid to (it.x to it.z) }
        targetChunks.forEach { reconcileChunk(it, "ensure") }
    }

    fun clearZone(zoneId: String, reason: String) {
        desired.remove(zoneId)
        var removed = 0
        loadedOwnedEntities().filter { decode(it)?.zoneId == zoneId }.forEach {
            it.remove()
            removed++
        }
        if (removed > 0) debug.event("farm_special_scene_cleared", "zone" to zoneId, "count" to removed, "reason" to reason)
    }

    fun onChunkLoad(chunk: Chunk) = reconcileChunk(chunk, "chunk_load")

    fun cleanupLoaded(reason: String) {
        desired.clear()
        var removed = 0
        loadedOwnedEntities().forEach {
            it.remove()
            removed++
        }
        if (removed > 0) debug.event("farm_special_scene_cleanup", "count" to removed, "reason" to reason)
    }

    private fun reconcileChunk(chunk: Chunk, reason: String) {
        val targets = desired.values.asSequence().flatMap { spec ->
            spec.objects.asSequence().filter { objectTarget ->
                objectTarget.location.world === chunk.world &&
                    chunkCoordinate(objectTarget.location.x) == chunk.x &&
                    chunkCoordinate(objectTarget.location.z) == chunk.z
            }.map { spec to it }
        }.toList()
        val targetByIdentity = targets.associateBy { (spec, target) ->
            FarmSpecialSceneIdentity(spec.zoneId, spec.sequence, target.role, target.index)
        }
        val candidates = chunk.entities.filter(::owns)
        val retained = hashSetOf<Entity>()
        targetByIdentity.forEach { (identity, pair) ->
            val matching = candidates.filter { decode(it) == identity && entityMatchesRole(it, identity.role) }
            val keep = matching.minByOrNull { it.uniqueId.toString() }
            matching.filter { it !== keep }.forEach(Entity::remove)
            val entity = keep ?: spawn(pair.first, pair.second, identity)
            normalize(entity, pair.first, pair.second, identity)
            retained += entity
        }
        candidates.filter { it !in retained }.forEach(Entity::remove)
        if (candidates.size != retained.size) {
            debug.event(
                "farm_special_scene_reconciled",
                "world" to chunk.world.name,
                "chunk" to "${chunk.x},${chunk.z}",
                "targets" to targets.size,
                "removed" to (candidates.size - retained.size).coerceAtLeast(0),
                "reason" to reason,
            )
        }
    }

    private fun spawn(
        spec: FarmSpecialSceneSpec,
        target: FarmSpecialSceneObject,
        identity: FarmSpecialSceneIdentity,
    ): Entity {
        val world = requireNotNull(target.location.world)
        val entity = when (target.role) {
            FarmSpecialSceneRole.CHANNEL_GATE -> world.spawn(target.location, ItemDisplay::class.java)
            FarmSpecialSceneRole.CHANNEL_HITBOX -> world.spawn(target.location, Interaction::class.java)
        }
        normalize(entity, spec, target, identity)
        debug.event(
            "farm_special_scene_spawned",
            "zone" to identity.zoneId,
            "sequence" to identity.sequence,
            "role" to identity.role,
            "index" to identity.index,
        )
        return entity
    }

    private fun normalize(
        entity: Entity,
        spec: FarmSpecialSceneSpec,
        target: FarmSpecialSceneObject,
        identity: FarmSpecialSceneIdentity,
    ) {
        entity.isPersistent = false
        entity.setGravity(false)
        entity.isInvulnerable = true
        val currentWorld = entity.world
        if (currentWorld !== target.location.world || entity.location.distanceSquared(target.location) > 0.0001) {
            entity.teleport(target.location)
        }
        mark(entity, identity)
        when (entity) {
            is ItemDisplay -> {
                entity.setItemStack(requireNotNull(target.item).clone())
                entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
                entity.transformation = Transformation(
                    Vector3f(),
                    AxisAngle4f(),
                    Vector3f(target.scale, target.scale, target.scale),
                    AxisAngle4f(),
                )
                entity.displayWidth = maxOf(1f, target.scale)
                entity.displayHeight = maxOf(1f, target.scale)
                entity.viewRange = spec.viewRange
                entity.isGlowing = target.active
            }
            is Interaction -> {
                entity.interactionWidth = 1.25f
                entity.interactionHeight = 1.45f
                entity.isResponsive = true
            }
        }
    }

    private fun entityMatchesRole(entity: Entity, role: FarmSpecialSceneRole): Boolean = when (role) {
        FarmSpecialSceneRole.CHANNEL_GATE -> entity is ItemDisplay
        FarmSpecialSceneRole.CHANNEL_HITBOX -> entity is Interaction
    }

    private fun decode(entity: Entity): FarmSpecialSceneIdentity? {
        val data = entity.persistentDataContainer
        val zone = data.get(zoneKey, PersistentDataType.STRING) ?: return null
        val sequence = data.get(sequenceKey, PersistentDataType.LONG) ?: return null
        val role = data.get(roleKey, PersistentDataType.STRING)
            ?.let { runCatching { FarmSpecialSceneRole.valueOf(it) }.getOrNull() } ?: return null
        val index = data.get(indexKey, PersistentDataType.INTEGER) ?: return null
        return FarmSpecialSceneIdentity(zone, sequence, role, index)
    }

    private fun mark(entity: Entity, identity: FarmSpecialSceneIdentity) {
        entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, identity.zoneId)
        entity.persistentDataContainer.set(sequenceKey, PersistentDataType.LONG, identity.sequence)
        entity.persistentDataContainer.set(roleKey, PersistentDataType.STRING, identity.role.name)
        entity.persistentDataContainer.set(indexKey, PersistentDataType.INTEGER, identity.index)
    }

    private fun loadedChunk(location: Location): Chunk? {
        val world = location.world ?: return null
        val x = chunkCoordinate(location.x)
        val z = chunkCoordinate(location.z)
        if (!world.isChunkLoaded(x, z)) return null
        return world.getChunkAt(x, z)
    }

    private fun loadedOwnedEntities(): Sequence<Entity> = Bukkit.getWorlds().asSequence()
        .flatMap { it.entities.asSequence() }
        .filter(::owns)

    private fun chunkCoordinate(coordinate: Double): Int = floor(coordinate).toInt() shr 4
}
