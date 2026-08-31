package ru.ruscrafting.farms.paper.farm.delivery

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.FarmItemDisplayTransform
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.FarmDeliveryPosition
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.BukkitFarmEntityLookup
import ru.ruscrafting.farms.paper.FarmEntityLookup
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.placement.FarmPlacementService
import java.util.UUID

internal data class FarmDeliveryIdentity(val zoneId: String, val sequence: Long, val index: Int)

private data class DeliveryKey(val zoneId: String, val index: Int)

private data class DeliveryLayout(
    val sequence: Long,
    val anchor: FarmDeliveryPosition,
    val locations: List<Location?>,
)

private enum class DeliveryEntityRole { GROUND_DISPLAY, GROUND_INTERACTION, CARRIED_DISPLAY }

/** Owns every crate entity, carrier association, movement and delivery cleanup. */
internal class FarmDeliveryController(
    private val plugin: Plugin,
    private val settings: () -> ArcFarmsConfig,
    private val debug: ArcFarmsDebug,
    private val port: WorksiteAudiencePort,
    private val points: FarmPointProvider,
    private val placement: FarmPlacementService,
    private val transitions: FarmTransitionSink,
    private val entityLookup: FarmEntityLookup = BukkitFarmEntityLookup,
) {
    private val zoneKey = NamespacedKey(plugin, "farm_delivery_zone")
    private val sequenceKey = NamespacedKey(plugin, "farm_delivery_sequence")
    private val indexKey = NamespacedKey(plugin, "farm_delivery_index")
    private val roleKey = NamespacedKey(plugin, "farm_delivery_role")
    private val groundEntities = mutableMapOf<DeliveryKey, MutableSet<UUID>>()
    private val carriers = mutableMapOf<DeliveryKey, UUID>()
    private val carriedDisplays = mutableMapOf<DeliveryKey, UUID>()
    private val reconciledSequences = mutableMapOf<String, Long>()
    private val layouts = mutableMapOf<String, DeliveryLayout>()

    fun owns(entity: Entity): Boolean = entity.persistentDataContainer.has(zoneKey, PersistentDataType.STRING)

    fun identity(entity: Entity): FarmDeliveryIdentity? {
        val data = entity.persistentDataContainer
        val zoneId = data.get(zoneKey, PersistentDataType.STRING) ?: return null
        val sequence = data.get(sequenceKey, PersistentDataType.LONG) ?: return null
        val index = data.get(indexKey, PersistentDataType.INTEGER) ?: return null
        return FarmDeliveryIdentity(zoneId, sequence, index)
    }

    fun isGroundInteraction(entity: Entity): Boolean =
        entity is Interaction && role(entity) == DeliveryEntityRole.GROUND_INTERACTION

    fun carrierCount(zoneId: String): Int = carriers.keys.count { it.zoneId == zoneId }

    fun isCarrying(zoneId: String, playerId: UUID): Boolean =
        carriers.any { (key, carrierId) -> key.zoneId == zoneId && carrierId == playerId }

    fun uncarriedLocations(runtime: FarmRuntime): List<Location> {
        val anchor = runtime.state.deliveryPosition ?: return emptyList()
        return (0 until runtime.settings.delivery.crates)
            .filterNot(runtime.state.deliveredCrates::contains)
            .filterNot { index -> DeliveryKey(runtime.settings.id, index) in carriers }
            .mapNotNull { index -> crateLocations(runtime, anchor).getOrNull(index)?.clone() }
    }

    fun ensure(runtime: FarmRuntime) {
        if (runtime.state.phase != FarmPhase.DELIVERY) {
            if (hasTrackedLifecycle(runtime.settings.id)) {
                clear(runtime, "phase_inactive")
            }
            return
        }
        val position = runtime.state.deliveryPosition ?: placement.selectDeliveryAnchor(
            runtime,
            port.players(runtime.region).firstOrNull()?.location,
        ).also { selected ->
            transitions.apply(runtime, EngineResult(runtime.state.copy(deliveryPosition = selected), accepted = true), null)
        }
        reconcileLifecycle(runtime)
        crateLocations(runtime, position)
        repeat(runtime.settings.delivery.crates) { index ->
            val key = DeliveryKey(runtime.settings.id, index)
            if (index in runtime.state.deliveredCrates) {
                removeGround(key, "already_delivered")
                carriers.remove(key)
                removeCarriedDisplay(key)
                return@repeat
            }
            carriers[key]?.let { playerId ->
                val carrier = Bukkit.getPlayer(playerId)
                if (carrier == null || !carrier.isOnline) {
                    returnCrate(runtime, key, carrier, "carrier_offline", notify = false)
                } else {
                    move(runtime, key, carrier, carrier.location)
                    updateCarriedDisplay(runtime, key, carrier)
                }
                return@repeat
            }
            reconcileGround(runtime, key, position)
        }
    }

    fun pickup(runtime: FarmRuntime, identity: FarmDeliveryIdentity, player: Player): Boolean {
        val key = DeliveryKey(identity.zoneId, identity.index)
        if (
            runtime.settings.id != identity.zoneId || runtime.state.phase != FarmPhase.DELIVERY ||
            runtime.state.sequence != identity.sequence || identity.index !in 0 until runtime.settings.delivery.crates ||
            identity.index in runtime.state.deliveredCrates || key in carriers || player.uniqueId in carriers.values
        ) return false
        removeGround(key, "picked_up")
        removeLoaded(runtime, identity.index, "picked_up")
        carriers[key] = player.uniqueId
        val display = player.world.spawn(carriedDisplayLocation(runtime, player), ItemDisplay::class.java) { entity ->
            entity.setItemStack(itemStack(runtime))
            entity.itemDisplayTransform = runtime.settings.delivery.displayTransform.bukkit
            entity.uniformScale(runtime.settings.delivery.carriedScale)
            entity.viewRange = runtime.settings.delivery.displayViewRange
            entity.teleportDuration = 1
            entity.isGlowing = true
            entity.isPersistent = false
            mark(entity, runtime, identity.index, DeliveryEntityRole.CARRIED_DISPLAY)
        }
        carriedDisplays[key] = display.uniqueId
        port.showScreenTitle(player, MessageKey.FARM_DELIVERY_PICKED_UP)
        if (settings().sounds) player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 0.9f, 0.8f)
        if (settings().particles) {
            player.spawnParticle(
                Particle.DUST,
                player.location.clone().add(0.0, 1.0, 0.0),
                4,
                0.3,
                0.35,
                0.3,
                0.0,
                Particle.DustOptions(DELIVERY_COLOR, 1.1f),
            )
        }
        debug.event(
            "farm_delivery_picked_up",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "crate" to identity.index,
            "player" to player.name,
        )
        move(runtime, key, player, player.location)
        return true
    }

    fun moveCarried(runtimes: Collection<FarmRuntime>, player: Player, destination: Location) {
        carriers.filterValues { it == player.uniqueId }.keys.toList().forEach { key ->
            runtimes.firstOrNull { it.settings.id == key.zoneId }?.let { runtime -> move(runtime, key, player, destination) }
        }
    }

    fun releasePlayer(runtimes: Collection<FarmRuntime>, player: Player, reason: String) {
        carriers.filterValues { it == player.uniqueId }.keys.toList().forEach { key ->
            runtimes.firstOrNull { it.settings.id == key.zoneId }?.let { runtime ->
                returnCrate(runtime, key, player, reason, notify = false)
            }
        }
    }

    fun updateCarriedDisplays(runtimes: Collection<FarmRuntime>) {
        carriers.forEach { (key, playerId) ->
            val runtime = runtimes.firstOrNull { it.settings.id == key.zoneId } ?: return@forEach
            Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline)?.let { player ->
                updateCarriedDisplay(runtime, key, player)
            }
        }
    }

    fun clear(runtime: FarmRuntime, reason: String) {
        val zoneId = runtime.settings.id
        keys(zoneId).forEach { key ->
            removeGround(key, reason)
            carriers.remove(key)
            removeCarriedDisplay(key)
        }
        if (reconciledSequences.remove(zoneId) != null) {
            entityLookup.inWorld(runtime.region.world).filter { entity -> identity(entity)?.zoneId == zoneId }.forEach(Entity::remove)
        }
        layouts.remove(zoneId)
    }

    fun cleanup(reason: String) {
        val owned = entityLookup.inAllWorlds().filter(::owns)
        owned.forEach(Entity::remove)
        groundEntities.clear()
        carriers.clear()
        carriedDisplays.clear()
        reconciledSequences.clear()
        layouts.clear()
        if (owned.isNotEmpty()) debug.event("farm_delivery_cleanup", "count" to owned.size, "reason" to reason)
    }

    private fun reconcileGround(runtime: FarmRuntime, key: DeliveryKey, anchor: FarmDeliveryPosition) {
        val expected = FarmDeliveryIdentity(runtime.settings.id, runtime.state.sequence, key.index)
        val active = groundEntities[key].orEmpty().mapNotNull(Bukkit::getEntity)
            .distinctBy(Entity::getUniqueId)
            .filter { entity -> identity(entity) == expected && role(entity) != DeliveryEntityRole.CARRIED_DISPLAY }
        groundEntities[key] = active.mapTo(mutableSetOf(), Entity::getUniqueId)
        val correct = active.count { it is ItemDisplay && role(it) == DeliveryEntityRole.GROUND_DISPLAY } == 1 &&
            active.count { it is Interaction && role(it) == DeliveryEntityRole.GROUND_INTERACTION } == 1 &&
            active.size == GROUND_ENTITY_COUNT
        if (correct) return
        removeGround(key, "reconcile")
        active.forEach(Entity::remove)
        spawnGround(runtime, key, anchor)
    }

    private fun spawnGround(runtime: FarmRuntime, key: DeliveryKey, anchor: FarmDeliveryPosition) {
        val location = crateLocations(runtime, anchor).getOrNull(key.index)?.clone() ?: run {
            plugin.logger.severe("Farm ${runtime.settings.id} has no indexed bed for delivery crate ${key.index}")
            return
        }
        if (!location.world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) return
        if (!runtime.region.contains(location)) {
            plugin.logger.severe(
                "Farm delivery position left ${runtime.region.label} for ${runtime.settings.id}; crate ${key.index} was not spawned",
            )
            return
        }
        if (!placement.isOpenToSky(location)) {
            plugin.logger.warning(
                "Farm delivery position is covered for ${runtime.settings.id}; crate ${key.index} was not spawned",
            )
            return
        }
        val display = location.world.spawn(
            location.clone().add(0.0, runtime.settings.delivery.displayYOffset, 0.0),
            ItemDisplay::class.java,
        ) { entity ->
            entity.setItemStack(itemStack(runtime))
            entity.itemDisplayTransform = runtime.settings.delivery.displayTransform.bukkit
            entity.uniformScale(runtime.settings.delivery.displayScale)
            entity.viewRange = runtime.settings.delivery.displayViewRange
            entity.isGlowing = true
            entity.isPersistent = false
            mark(entity, runtime, key.index, DeliveryEntityRole.GROUND_DISPLAY)
        }
        val interaction = location.world.spawn(location, Interaction::class.java) { entity ->
            entity.interactionWidth = 1.35f
            entity.interactionHeight = 1.45f
            entity.isResponsive = true
            entity.isPersistent = false
            mark(entity, runtime, key.index, DeliveryEntityRole.GROUND_INTERACTION)
        }
        groundEntities[key] = mutableSetOf(display.uniqueId, interaction.uniqueId)
        debug.event(
            "farm_delivery_spawned",
            "zone" to key.zoneId,
            "sequence" to runtime.state.sequence,
            "crate" to key.index,
            "x" to location.x,
            "y" to location.y,
            "z" to location.z,
        )
    }

    private fun move(runtime: FarmRuntime, key: DeliveryKey, player: Player, destination: Location) {
        if (carriers[key] != player.uniqueId) return
        if (runtime.state.phase != FarmPhase.DELIVERY) {
            clear(runtime, "state_changed")
            return
        }
        if (!runtime.region.contains(destination)) {
            returnCrate(runtime, key, player, "left_zone")
            return
        }
        updateCarriedDisplay(runtime, key, player)
        val receiving = points.resolve(runtime, FarmPointKind.RECEIVING)
        if (destination.world.name != receiving.world) return
        val target = Location(destination.world, receiving.x, receiving.y, receiving.z)
        if (destination.distanceSquared(target) > runtime.settings.delivery.radius * runtime.settings.delivery.radius) return
        carriers.remove(key)
        removeCarriedDisplay(key)
        if (settings().sounds) player.playSound(player.location, Sound.BLOCK_BARREL_CLOSE, 0.85f, 0.9f)
        debug.event(
            "farm_delivery_completed",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "crate" to key.index,
            "player" to player.name,
        )
        transitions.apply(
            runtime,
            FarmShiftEngine.deliver(
                runtime.state,
                key.index,
                runtime.settings.delivery.crates,
                player.uniqueId,
            ),
            player,
        )
    }

    private fun returnCrate(
        runtime: FarmRuntime,
        key: DeliveryKey,
        player: Player?,
        reason: String,
        notify: Boolean = true,
    ) {
        carriers.remove(key)
        removeCarriedDisplay(key)
        if (notify && player?.isOnline == true) port.sendActionBar(player, MessageKey.FARM_DELIVERY_RETURNED)
        debug.event(
            "farm_delivery_returned",
            "zone" to key.zoneId,
            "sequence" to runtime.state.sequence,
            "crate" to key.index,
            "player" to player?.name,
            "reason" to reason,
        )
    }

    private fun updateCarriedDisplay(runtime: FarmRuntime, key: DeliveryKey, player: Player) {
        val display = carriedDisplays[key]?.let(Bukkit::getEntity) as? ItemDisplay ?: return
        if (display.world != player.world) return
        display.teleport(carriedDisplayLocation(runtime, player))
    }

    private fun carriedDisplayLocation(runtime: FarmRuntime, player: Player): Location {
        val direction = player.location.direction.setY(0)
        if (direction.lengthSquared() > 0.001) direction.normalize().multiply(-runtime.settings.deliveryCarriedForwardOffset)
        return player.location.clone().add(direction).add(0.0, runtime.settings.delivery.carriedYOffset, 0.0)
    }

    private fun removeGround(key: DeliveryKey, reason: String) {
        val ids = groundEntities.remove(key).orEmpty()
        ids.forEach { id -> Bukkit.getEntity(id)?.remove() }
        if (ids.isNotEmpty()) {
            debug.event(
                "farm_delivery_entities_removed",
                "zone" to key.zoneId,
                "crate" to key.index,
                "count" to ids.size,
                "reason" to reason,
            )
        }
    }

    private fun removeLoaded(runtime: FarmRuntime, index: Int, reason: String) {
        val removed = entityLookup.inWorld(runtime.region.world).filter {
            identity(it)?.let { identity -> identity.zoneId == runtime.settings.id && identity.index == index } == true
        }
        removed.forEach(Entity::remove)
        if (removed.isNotEmpty()) {
            debug.event(
                "farm_delivery_orphans_removed",
                "zone" to runtime.settings.id,
                "crate" to index,
                "count" to removed.size,
                "reason" to reason,
            )
        }
    }

    private fun removeCarriedDisplay(key: DeliveryKey) {
        carriedDisplays.remove(key)?.let(Bukkit::getEntity)?.remove()
    }

    private fun reconcileLifecycle(runtime: FarmRuntime) {
        val zoneId = runtime.settings.id
        if (reconciledSequences[zoneId] == runtime.state.sequence) return
        groundEntities.keys.removeIf { it.zoneId == zoneId }
        carriers.keys.removeIf { it.zoneId == zoneId }
        carriedDisplays.keys.removeIf { it.zoneId == zoneId }
        entityLookup.inWorld(runtime.region.world).filter { entity -> identity(entity)?.zoneId == zoneId }.forEach { entity ->
            val identity = identity(entity)
            val entityRole = role(entity)
            if (
                identity == null || identity.sequence != runtime.state.sequence ||
                identity.index !in 0 until runtime.settings.delivery.crates
            ) {
                entity.remove()
                return@forEach
            }
            val key = DeliveryKey(zoneId, identity.index)
            if (entityRole == DeliveryEntityRole.CARRIED_DISPLAY) {
                // Carrier ownership is intentionally ephemeral. A hard restart returns
                // every formerly carried crate to its cached ground layout.
                entity.remove()
            } else {
                groundEntities.getOrPut(key, ::linkedSetOf) += entity.uniqueId
            }
        }
        reconciledSequences[zoneId] = runtime.state.sequence
    }

    private fun crateLocations(runtime: FarmRuntime, anchor: FarmDeliveryPosition): List<Location?> {
        val current = layouts[runtime.settings.id]
        if (current != null && current.sequence == runtime.state.sequence && current.anchor == anchor) return current.locations
        val selected = placement.deliveryCrateLocations(runtime, anchor)
        val locations = List(runtime.settings.delivery.crates) { index -> selected.getOrNull(index)?.clone() }
        layouts[runtime.settings.id] = DeliveryLayout(runtime.state.sequence, anchor, locations)
        return locations
    }

    private fun hasTrackedLifecycle(zoneId: String): Boolean =
        zoneId in reconciledSequences || keys(zoneId).isNotEmpty() || zoneId in layouts

    private fun keys(zoneId: String): Set<DeliveryKey> = buildSet {
        groundEntities.keys.filterTo(this) { it.zoneId == zoneId }
        carriers.keys.filterTo(this) { it.zoneId == zoneId }
        carriedDisplays.keys.filterTo(this) { it.zoneId == zoneId }
    }

    private fun mark(entity: Entity, runtime: FarmRuntime, index: Int, role: DeliveryEntityRole) {
        entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, runtime.settings.id)
        entity.persistentDataContainer.set(sequenceKey, PersistentDataType.LONG, runtime.state.sequence)
        entity.persistentDataContainer.set(indexKey, PersistentDataType.INTEGER, index)
        entity.persistentDataContainer.set(roleKey, PersistentDataType.STRING, role.name)
    }

    private fun role(entity: Entity): DeliveryEntityRole? = entity.persistentDataContainer
        .get(roleKey, PersistentDataType.STRING)
        ?.let { runCatching { DeliveryEntityRole.valueOf(it) }.getOrNull() }

    @Suppress("DEPRECATION")
    private fun itemStack(runtime: FarmRuntime): ItemStack =
        ItemStack(MaterialRules.material(runtime.settings.delivery.itemMaterial)).also { item ->
            if (runtime.settings.delivery.itemCustomModelData > 0) {
                val meta = item.itemMeta
                meta.setCustomModelData(runtime.settings.delivery.itemCustomModelData)
                item.itemMeta = meta
            }
        }

    private fun ItemDisplay.uniformScale(scale: Float) {
        transformation = Transformation(Vector3f(), AxisAngle4f(), Vector3f(scale, scale, scale), AxisAngle4f())
    }

    private val FarmItemDisplayTransform.bukkit: ItemDisplay.ItemDisplayTransform
        get() = when (this) {
            FarmItemDisplayTransform.GROUND -> ItemDisplay.ItemDisplayTransform.GROUND
            FarmItemDisplayTransform.FIXED -> ItemDisplay.ItemDisplayTransform.FIXED
            FarmItemDisplayTransform.HEAD -> ItemDisplay.ItemDisplayTransform.HEAD
        }

    private companion object {
        const val GROUND_ENTITY_COUNT = 2
        val DELIVERY_COLOR = org.bukkit.Color.fromRGB(199, 120, 255)
    }
}
