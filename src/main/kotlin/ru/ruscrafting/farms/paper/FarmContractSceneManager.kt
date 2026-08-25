package ru.ruscrafting.farms.paper

import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Villager
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.ruscrafting.farms.config.FarmItemDisplayTransform
import ru.ruscrafting.farms.domain.FarmCustomerType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

internal enum class FarmContractSceneRole { CART, CART_INTERACTION, CART_LOAD, CUSTOMER }

internal data class FarmContractSceneIdentity(
    val zoneId: String,
    val sequence: Long,
    val role: FarmContractSceneRole,
    val slot: Int = 0,
)

internal data class FarmContractSceneTarget(
    val identity: FarmContractSceneIdentity,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
)

internal data class FarmContractSceneCandidate(
    val id: String,
    val identity: FarmContractSceneIdentity?,
    val role: FarmContractSceneRole?,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
)

internal data class FarmContractScenePlan(
    val keep: Map<FarmContractSceneIdentity, String>,
    val remove: Set<String>,
)

internal object FarmContractSceneReconciler {
    fun plan(
        targets: Collection<FarmContractSceneTarget>,
        candidates: Collection<FarmContractSceneCandidate>,
        maxDistanceSquared: Double = 4.0,
    ): FarmContractScenePlan {
        val keep = linkedMapOf<FarmContractSceneIdentity, String>()
        val retained = hashSetOf<String>()
        targets.forEach { target ->
            candidates.asSequence()
                .filter { candidate ->
                    candidate.identity == target.identity &&
                        candidate.role == target.identity.role &&
                        candidate.world == target.world
                }
                .map { candidate -> candidate to distanceSquared(candidate, target) }
                .filter { (_, distance) -> distance <= maxDistanceSquared }
                .minWithOrNull(compareBy<Pair<FarmContractSceneCandidate, Double>> { it.second }.thenBy { it.first.id })
                ?.first
                ?.let { candidate ->
                    keep[target.identity] = candidate.id
                    retained += candidate.id
                }
        }
        return FarmContractScenePlan(
            keep = keep,
            remove = candidates.mapTo(linkedSetOf(), FarmContractSceneCandidate::id) - retained,
        )
    }

    private fun distanceSquared(candidate: FarmContractSceneCandidate, target: FarmContractSceneTarget): Double {
        val dx = candidate.x - target.x
        val dy = candidate.y - target.y
        val dz = candidate.z - target.z
        return dx * dx + dy * dy + dz * dz
    }
}

internal data class FarmContractSceneSpec(
    val zoneId: String,
    val sequence: Long,
    val customerType: FarmCustomerType,
    val customerLocation: Location,
    val cartLocation: Location,
    val cartItem: ItemStack,
    val cartDisplayTransform: FarmItemDisplayTransform,
    val cartScale: Float,
    val loadItem: ItemStack,
    val loadCount: Int,
    val loadYOffset: Double,
    val loadScale: Float,
    val viewRange: Float,
) {
    init {
        require(loadCount in 0..8) { "Farm contract cart load count must be between 0 and 8" }
        require(customerLocation.world === cartLocation.world) { "Farm contract scene must stay in one world" }
    }
}

/**
 * Owns the reconstructible contract scene. Scene entities are deliberately not
 * persistent: the shift state is the source of truth and a loaded chunk can
 * always recreate the visual scene.
 */
internal class FarmContractSceneManager(
    plugin: Plugin,
    private val debug: ArcFarmsDebug,
) {
    private val zoneKey = NamespacedKey(plugin, "farm_contract_scene_zone")
    private val sequenceKey = NamespacedKey(plugin, "farm_contract_scene_sequence")
    private val roleKey = NamespacedKey(plugin, "farm_contract_scene_role")
    private val slotKey = NamespacedKey(plugin, "farm_contract_scene_slot")
    private val desired = ConcurrentHashMap<String, FarmContractSceneSpec>()
    private val tracked = mutableMapOf<FarmContractSceneIdentity, UUID>()

    fun owns(entity: Entity): Boolean = entity.persistentDataContainer.has(zoneKey, PersistentDataType.STRING)

    fun metadata(entity: Entity): FarmContractSceneIdentity? = decode(entity)

    fun ensure(spec: FarmContractSceneSpec) {
        val previous = desired.put(spec.zoneId, spec)
        val targets = targets(spec)
        val expected = targets.mapTo(hashSetOf(), FarmContractSceneTarget::identity)
        tracked.keys.filter { it.zoneId == spec.zoneId && it !in expected }.toList().forEach { identity ->
            removeTracked(identity, "target_inactive")
        }

        if (previous == spec && targets.all(::trackedEntityIsCurrent)) return
        targets.mapNotNull(::loadedChunk).distinctBy { it.world.uid to (it.x to it.z) }.forEach { chunk ->
            reconcileChunk(chunk, "ensure")
        }
        targets.filter { target -> loadedChunk(target) != null && !trackedEntityIsCurrent(target) }
            .forEach { target -> spawn(spec, target) }
    }

    fun clearZone(zoneId: String, reason: String) {
        val hadDesired = desired.remove(zoneId) != null
        val identities = tracked.keys.filter { it.zoneId == zoneId }.toList()
        if (!hadDesired && identities.isEmpty()) return
        identities.forEach { identity -> removeTracked(identity, reason) }
        loadedOwnedEntities().filter { entity -> decode(entity)?.zoneId == zoneId }.forEach(Entity::remove)
    }

    fun onChunkLoad(chunk: Chunk) {
        reconcileChunk(chunk, "chunk_load")
    }

    fun cleanupLoaded(reason: String) {
        desired.clear()
        tracked.clear()
        var removed = 0
        org.bukkit.Bukkit.getWorlds().asSequence().flatMap { it.entities.asSequence() }.filter(::owns).forEach { entity ->
            entity.remove()
            removed++
        }
        if (removed > 0) debug.event("farm_contract_scene_cleanup", "count" to removed, "reason" to reason)
    }

    private fun reconcileChunk(chunk: Chunk, reason: String) {
        val entities = chunk.entities.filter(::owns)
        if (entities.isEmpty()) return
        val targets = desired.values.asSequence()
            .flatMap { targets(it).asSequence() }
            .filter { target ->
                target.world == chunk.world.name && chunkCoordinate(target.x) == chunk.x && chunkCoordinate(target.z) == chunk.z
            }
            .toList()
        val byId = entities.associateBy { it.uniqueId.toString() }
        val plan = FarmContractSceneReconciler.plan(targets, entities.map(::candidate))
        plan.remove.forEach { id -> byId[id]?.remove() }
        plan.keep.forEach { (identity, id) ->
            val entity = byId[id] ?: return@forEach
            tracked[identity] = entity.uniqueId
            desired[identity.zoneId]?.let { normalize(entity, it, identity) }
        }
        if (plan.remove.isNotEmpty()) {
            debug.event(
                "farm_contract_scene_reconciled",
                "world" to chunk.world.name,
                "chunk" to "${chunk.x},${chunk.z}",
                "kept" to plan.keep.size,
                "removed" to plan.remove.size,
                "reason" to reason,
            )
        }
    }

    private fun spawn(spec: FarmContractSceneSpec, target: FarmContractSceneTarget) {
        val location = Location(
            requireNotNull(spec.customerLocation.world),
            target.x,
            target.y,
            target.z,
            if (target.identity.role == FarmContractSceneRole.CUSTOMER) spec.customerLocation.yaw else spec.cartLocation.yaw,
            0f,
        )
        val entity = when (target.identity.role) {
            FarmContractSceneRole.CUSTOMER -> location.world.spawn(location, Villager::class.java) { customer ->
                normalize(customer, spec, target.identity)
            }
            FarmContractSceneRole.CART, FarmContractSceneRole.CART_LOAD ->
                location.world.spawn(location, ItemDisplay::class.java) { display ->
                    normalize(display, spec, target.identity)
                }
            FarmContractSceneRole.CART_INTERACTION -> location.world.spawn(location, Interaction::class.java) { interaction ->
                normalize(interaction, spec, target.identity)
            }
        }
        mark(entity, target.identity)
        tracked[target.identity] = entity.uniqueId
        debug.event(
            "farm_contract_scene_spawned",
            "zone" to target.identity.zoneId,
            "sequence" to target.identity.sequence,
            "role" to target.identity.role,
            "slot" to target.identity.slot,
        )
    }

    private fun normalize(entity: Entity, spec: FarmContractSceneSpec, identity: FarmContractSceneIdentity) {
        entity.isPersistent = false
        entity.setGravity(false)
        entity.isInvulnerable = true
        mark(entity, identity)
        when (entity) {
            is Villager -> {
                entity.profession = when (spec.customerType) {
                    FarmCustomerType.BAKER -> Villager.Profession.FARMER
                    FarmCustomerType.MINE_SUPPLIER -> Villager.Profession.TOOLSMITH
                    FarmCustomerType.MARKET_TRADER -> Villager.Profession.CARTOGRAPHER
                }
                entity.villagerType = Villager.Type.PLAINS
                entity.setAI(false)
                entity.isAware = false
                entity.isCollidable = false
                entity.removeWhenFarAway = false
                entity.setCanPickupItems(false)
                entity.isSilent = true
                entity.setRotation(spec.customerLocation.yaw, 0f)
            }
            is Interaction -> {
                entity.interactionWidth = 1.8f
                entity.interactionHeight = 1.6f
                entity.isResponsive = true
                entity.setRotation(spec.cartLocation.yaw, 0f)
            }
            is ItemDisplay -> when (identity.role) {
                FarmContractSceneRole.CART -> {
                    entity.setItemStack(spec.cartItem.clone())
                    entity.itemDisplayTransform = spec.cartDisplayTransform.bukkit
                    entity.transformation = Transformation(
                        Vector3f(),
                        AxisAngle4f(),
                        Vector3f(spec.cartScale, spec.cartScale, spec.cartScale),
                        AxisAngle4f(),
                    )
                    entity.displayWidth = maxOf(1.0f, spec.cartScale)
                    entity.displayHeight = maxOf(1.0f, spec.cartScale * 0.75f)
                    entity.viewRange = spec.viewRange
                    entity.setRotation(spec.cartLocation.yaw, 0f)
                }
                FarmContractSceneRole.CART_LOAD -> {
                    entity.setItemStack(spec.loadItem.clone())
                    entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GROUND
                    entity.transformation = Transformation(
                        Vector3f(),
                        AxisAngle4f(),
                        Vector3f(spec.loadScale, spec.loadScale, spec.loadScale),
                        AxisAngle4f(),
                    )
                    entity.viewRange = spec.viewRange
                    entity.setRotation(spec.cartLocation.yaw, 0f)
                }
                else -> return
            }
        }
    }

    private fun targets(spec: FarmContractSceneSpec): List<FarmContractSceneTarget> = buildList {
        add(target(spec, FarmContractSceneRole.CUSTOMER, 0, spec.customerLocation))
        add(target(spec, FarmContractSceneRole.CART, 0, spec.cartLocation))
        add(target(spec, FarmContractSceneRole.CART_INTERACTION, 0, spec.cartLocation))
        repeat(spec.loadCount) { slot -> add(target(spec, FarmContractSceneRole.CART_LOAD, slot, cartLoadLocation(spec, slot))) }
    }

    private fun target(
        spec: FarmContractSceneSpec,
        role: FarmContractSceneRole,
        slot: Int,
        location: Location,
    ): FarmContractSceneTarget = FarmContractSceneTarget(
        identity = FarmContractSceneIdentity(spec.zoneId, spec.sequence, role, slot),
        world = location.world.name,
        x = location.x,
        y = location.y,
        z = location.z,
    )

    private fun cartLoadLocation(spec: FarmContractSceneSpec, slot: Int): Location {
        val cart = spec.cartLocation
        val offsets = listOf(
            -0.24 to -0.16,
            0.24 to -0.16,
            -0.24 to 0.10,
            0.24 to 0.10,
            -0.24 to 0.36,
            0.24 to 0.36,
            -0.24 to 0.62,
            0.24 to 0.62,
        )
        val (localX, localZ) = offsets[slot.coerceIn(0, offsets.lastIndex)]
        val radians = Math.toRadians(cart.yaw.toDouble())
        val x = localX * kotlin.math.cos(radians) - localZ * kotlin.math.sin(radians)
        val z = localX * kotlin.math.sin(radians) + localZ * kotlin.math.cos(radians)
        return cart.clone().add(x, spec.loadYOffset + (slot / 4) * 0.13, z)
    }

    private fun candidate(entity: Entity): FarmContractSceneCandidate {
        val identity = decode(entity)
        val role = when {
            entity is Villager && identity?.role == FarmContractSceneRole.CUSTOMER -> FarmContractSceneRole.CUSTOMER
            entity is Interaction && identity?.role == FarmContractSceneRole.CART_INTERACTION -> FarmContractSceneRole.CART_INTERACTION
            entity is ItemDisplay && identity?.role == FarmContractSceneRole.CART -> FarmContractSceneRole.CART
            entity is ItemDisplay && identity?.role == FarmContractSceneRole.CART_LOAD -> FarmContractSceneRole.CART_LOAD
            else -> null
        }
        return FarmContractSceneCandidate(
            id = entity.uniqueId.toString(),
            identity = identity,
            role = role,
            world = entity.world.name,
            x = entity.location.x,
            y = entity.location.y,
            z = entity.location.z,
        )
    }

    private fun decode(entity: Entity): FarmContractSceneIdentity? {
        val data = entity.persistentDataContainer
        val zoneId = data.get(zoneKey, PersistentDataType.STRING) ?: return null
        val sequence = data.get(sequenceKey, PersistentDataType.LONG) ?: return null
        val role = data.get(roleKey, PersistentDataType.STRING)
            ?.let { raw -> runCatching { FarmContractSceneRole.valueOf(raw) }.getOrNull() }
            ?: return null
        val slot = data.get(slotKey, PersistentDataType.INTEGER) ?: 0
        return FarmContractSceneIdentity(zoneId, sequence, role, slot)
    }

    private fun mark(entity: Entity, identity: FarmContractSceneIdentity) {
        entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, identity.zoneId)
        entity.persistentDataContainer.set(sequenceKey, PersistentDataType.LONG, identity.sequence)
        entity.persistentDataContainer.set(roleKey, PersistentDataType.STRING, identity.role.name)
        entity.persistentDataContainer.set(slotKey, PersistentDataType.INTEGER, identity.slot)
    }

    private fun trackedEntityIsCurrent(target: FarmContractSceneTarget): Boolean {
        val entity = tracked[target.identity]?.let(org.bukkit.Bukkit::getEntity) ?: return false
        return entity.isValid && candidate(entity).let { candidate ->
            candidate.identity == target.identity && candidate.role == target.identity.role &&
                candidate.world == target.world &&
                FarmContractSceneReconciler.plan(listOf(target), listOf(candidate)).keep[target.identity] == candidate.id
        }
    }

    private fun loadedChunk(target: FarmContractSceneTarget): Chunk? {
        val world = org.bukkit.Bukkit.getWorld(target.world) ?: return null
        val chunkX = chunkCoordinate(target.x)
        val chunkZ = chunkCoordinate(target.z)
        if (!world.isChunkLoaded(chunkX, chunkZ)) return null
        return world.getChunkAt(chunkX, chunkZ)
    }

    private fun removeTracked(identity: FarmContractSceneIdentity, reason: String) {
        val id = tracked.remove(identity) ?: return
        org.bukkit.Bukkit.getEntity(id)?.remove()
        debug.event(
            "farm_contract_scene_removed",
            "zone" to identity.zoneId,
            "role" to identity.role,
            "slot" to identity.slot,
            "reason" to reason,
        )
    }

    private fun loadedOwnedEntities(): Sequence<Entity> = org.bukkit.Bukkit.getWorlds().asSequence()
        .flatMap { world -> world.entities.asSequence() }
        .filter(::owns)

    private fun chunkCoordinate(coordinate: Double): Int = floor(coordinate).toInt() shr 4

    private val FarmItemDisplayTransform.bukkit: ItemDisplay.ItemDisplayTransform
        get() = when (this) {
            FarmItemDisplayTransform.GROUND -> ItemDisplay.ItemDisplayTransform.GROUND
            FarmItemDisplayTransform.FIXED -> ItemDisplay.ItemDisplayTransform.FIXED
            FarmItemDisplayTransform.HEAD -> ItemDisplay.ItemDisplayTransform.HEAD
        }
}
