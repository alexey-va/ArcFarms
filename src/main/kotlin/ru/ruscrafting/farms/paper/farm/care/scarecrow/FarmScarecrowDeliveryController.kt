package ru.ruscrafting.farms.paper.farm.care.scarecrow

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareTarget
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.BukkitFarmEntityLookup
import ru.ruscrafting.farms.paper.FarmEntityLookup
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.care.bukkit
import java.util.UUID

private data class ScarecrowKey(val zoneId: String, val targetId: Int)
private enum class ScarecrowEntityRole { SUPPLY_DISPLAY, SUPPLY_INTERACTION, CARRIED_DISPLAY, PLACED_DISPLAY }

/** One barn stock, ephemeral carriers, and placed scarecrow visuals. */
internal class FarmScarecrowDeliveryController(
    plugin: Plugin,
    private val settings: () -> ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
    private val port: WorksiteRuntimePort,
    private val points: FarmPointProvider,
    private val transitions: FarmTransitionSink,
    private val runtimes: () -> Collection<FarmRuntime>,
    private val entityLookup: FarmEntityLookup = BukkitFarmEntityLookup,
    private val supplyPoint: ((FarmRuntime) -> FarmPointPosition?)? = null,
) {
    private val zoneKey = NamespacedKey(plugin, "farm_scarecrow_zone")
    private val sequenceKey = NamespacedKey(plugin, "farm_scarecrow_sequence")
    private val targetKey = NamespacedKey(plugin, "farm_scarecrow_target")
    private val roleKey = NamespacedKey(plugin, "farm_scarecrow_role")
    private val supplyEntities = mutableMapOf<String, MutableSet<UUID>>()
    private val carriers = mutableMapOf<ScarecrowKey, UUID>()
    private val carriedDisplays = mutableMapOf<ScarecrowKey, UUID>()
    private val placedDisplays = mutableMapOf<ScarecrowKey, UUID>()
    private val reconciledSequences = mutableMapOf<String, Long>()

    fun owns(entity: Entity): Boolean = entity.persistentDataContainer.has(zoneKey, PersistentDataType.STRING)

    fun interact(player: Player, entity: Entity): Boolean {
        if (!owns(entity)) return false
        val zoneId = entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) ?: return true
        val role = entity.persistentDataContainer.get(roleKey, PersistentDataType.STRING) ?: return true
        if (role != ScarecrowEntityRole.SUPPLY_INTERACTION.name) return true
        val runtime = runtimes().firstOrNull { it.settings.id == zoneId } ?: return true
        if (!active(runtime) || runtime.state.sequence != entity.persistentDataContainer.get(sequenceKey, PersistentDataType.LONG)) {
            return true
        }
        if (!port.hasAccess(player, runtime.settings.permission)) {
            port.sendChat(player, MessageKey.ZONE_LOCKED)
            return true
        }
        if (carriers.values.any { it == player.uniqueId }) {
            port.sendActionBar(player, MessageKey.FARM_CARE_SCARECROW_ALREADY_CARRYING)
            return true
        }
        val target = runtime.state.careTargets.asSequence()
            .filter { it.role == FarmCareRole.SCARECROW && !it.complete }
            .filter { ScarecrowKey(zoneId, it.id) !in carriers }
            .minByOrNull(FarmCareTarget::id)
        if (target == null) {
            port.sendActionBar(player, MessageKey.FARM_CARE_SCARECROW_ALL_ASSIGNED)
            return true
        }
        val key = ScarecrowKey(zoneId, target.id)
        carriers[key] = player.uniqueId
        carriedDisplays[key] = spawnDisplay(runtime, carriedLocation(runtime, player), target.id, ScarecrowEntityRole.CARRIED_DISPLAY)
            ?.uniqueId ?: run {
            carriers.remove(key)
            return true
        }
        port.showScreenTitle(
            player,
            locale.render(MessageKey.FARM_CARE_SCARECROW_PICKED_UP, player),
            locale.render(MessageKey.FARM_CARE_SCARECROW_PICKED_UP_SUBTITLE, player),
        )
        if (settings().sounds) player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 0.9f, 0.8f)
        debug.event("farm_scarecrow_picked_up", "zone" to zoneId, "target" to target.id, "player" to player.name)
        return true
    }

    fun ensure(runtime: FarmRuntime) {
        if (!active(runtime)) {
            if (hasLifecycle(runtime.settings.id)) clear(runtime, "inactive")
            return
        }
        reconcile(runtime)
        val targets = runtime.state.careTargets.filter { it.role == FarmCareRole.SCARECROW }
        targets.filter(FarmCareTarget::complete).forEach { target -> ensurePlaced(runtime, target) }
        placedDisplays.keys.filter { key ->
            key.zoneId == runtime.settings.id && targets.none { it.id == key.targetId && it.complete }
        }.toList().forEach(::removePlaced)
        if (targets.any { !it.complete && ScarecrowKey(runtime.settings.id, it.id) !in carriers }) ensureSupply(runtime)
        else removeSupply(runtime.settings.id)
    }

    fun onMove(event: PlayerMoveEvent) {
        val destination = event.to
        carriers.filterValues { it == event.player.uniqueId }.keys.toList().forEach { key ->
            val runtime = runtimes().firstOrNull { it.settings.id == key.zoneId } ?: return@forEach
            move(runtime, key, event.player, destination)
        }
    }

    fun updateCarriedDisplays() {
        carriers.toList().forEach { (key, playerId) ->
            val runtime = runtimes().firstOrNull { it.settings.id == key.zoneId } ?: return@forEach
            val player = Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline) ?: return@forEach
            val display = carriedDisplays[key]?.let(Bukkit::getEntity) as? ItemDisplay ?: return@forEach
            if (display.world == player.world) display.teleport(carriedLocation(runtime, player))
            move(runtime, key, player, player.location)
        }
    }

    fun releasePlayer(player: Player, reason: String) {
        carriers.filterValues { it == player.uniqueId }.keys.toList().forEach { key ->
            returnScarecrow(key, player, reason, notify = false)
        }
    }

    fun clear(runtime: FarmRuntime, reason: String) {
        val zoneId = runtime.settings.id
        removeSupply(zoneId)
        carriers.keys.filter { it.zoneId == zoneId }.toList().forEach { key ->
            carriers.remove(key)
            removeCarried(key)
        }
        placedDisplays.keys.filter { it.zoneId == zoneId }.toList().forEach(::removePlaced)
        entityLookup.inWorld(runtime.region.world).filter { entity ->
            entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) == zoneId
        }.forEach(Entity::remove)
        reconciledSequences.remove(zoneId)
        debug.event("farm_scarecrow_cleanup", "zone" to zoneId, "reason" to reason)
    }

    fun cleanup(reason: String) {
        val owned = entityLookup.inAllWorlds().filter(::owns)
        owned.forEach(Entity::remove)
        supplyEntities.clear()
        carriers.clear()
        carriedDisplays.clear()
        placedDisplays.clear()
        reconciledSequences.clear()
        if (owned.isNotEmpty()) debug.event("farm_scarecrow_cleanup", "count" to owned.size, "reason" to reason)
    }

    private fun move(runtime: FarmRuntime, key: ScarecrowKey, player: Player, destination: Location) {
        if (!active(runtime) || !runtime.region.contains(destination)) {
            returnScarecrow(key, player, "left_zone")
            return
        }
        val radius = runtime.settings.scarecrowDeliveryRadius
        val target = runtime.state.careTargets.asSequence()
            .filter { it.role == FarmCareRole.SCARECROW && !it.complete && it.position.world == destination.world.name }
            .filter { candidate ->
                val candidateKey = ScarecrowKey(key.zoneId, candidate.id)
                candidateKey == key || candidateKey !in carriers
            }
            .map { candidate ->
                candidate to destination.distanceSquared(
                    Location(destination.world, candidate.position.x, candidate.position.y, candidate.position.z),
                )
            }
            .filter { (_, distanceSquared) -> distanceSquared <= radius * radius }
            .minWithOrNull(compareBy<Pair<FarmCareTarget, Double>> { it.second }.thenBy { it.first.id })
            ?.first ?: return
        val activeKey = if (target.id == key.targetId) key else reassign(key, target.id) ?: run {
            returnScarecrow(key, player, "target_unavailable", notify = false)
            return
        }
        val targetLocation = Location(destination.world, target.position.x, target.position.y, target.position.z)
        carriers.remove(activeKey)
        removeCarried(activeKey)
        if (settings().sounds) player.playSound(player.location, Sound.BLOCK_WOOD_PLACE, 0.9f, 1.1f)
        if (settings().particles) player.spawnParticle(Particle.HAPPY_VILLAGER, targetLocation, 14, 0.55, 0.8, 0.55, 0.02)
        debug.event("farm_scarecrow_placed", "zone" to activeKey.zoneId, "target" to target.id, "player" to player.name)
        transitions.apply(runtime, FarmShiftEngine.advanceCare(runtime.state, target.id, player.uniqueId), player)
    }

    private fun reassign(previous: ScarecrowKey, targetId: Int): ScarecrowKey? {
        val playerId = carriers.remove(previous) ?: return null
        val next = ScarecrowKey(previous.zoneId, targetId)
        if (next in carriers) {
            carriers[previous] = playerId
            return null
        }
        carriers[next] = playerId
        carriedDisplays.remove(previous)?.let { displayId ->
            carriedDisplays[next] = displayId
            Bukkit.getEntity(displayId)?.persistentDataContainer?.set(targetKey, PersistentDataType.INTEGER, targetId)
        }
        return next
    }

    private fun ensureSupply(runtime: FarmRuntime) {
        val active = supplyEntities[runtime.settings.id].orEmpty().mapNotNull(Bukkit::getEntity).filter(Entity::isValid)
        if (active.count { role(it) == ScarecrowEntityRole.SUPPLY_DISPLAY } == 1 &&
            active.count { role(it) == ScarecrowEntityRole.SUPPLY_INTERACTION } == 1 && active.size == 2
        ) {
            supplyEntities[runtime.settings.id] = active.mapTo(mutableSetOf(), Entity::getUniqueId)
            return
        }
        removeSupply(runtime.settings.id)
        val point = supplyPoint?.invoke(runtime) ?: points.resolve(runtime, FarmPointKind.PEN)
        val world = Bukkit.getWorld(point.world) ?: return
        if (!world.isChunkLoaded(point.x.toInt() shr 4, point.z.toInt() shr 4)) return
        val location = Location(world, point.x, point.y, point.z)
        if (!runtime.region.contains(location)) return
        val display = spawnDisplay(runtime, location, -1, ScarecrowEntityRole.SUPPLY_DISPLAY) ?: return
        val interaction = world.spawn(location.clone().add(0.0, 0.5, 0.0), Interaction::class.java) { entity ->
            entity.interactionWidth = 1.5f
            entity.interactionHeight = 2.0f
            entity.isResponsive = true
            entity.isPersistent = false
            mark(entity, runtime, -1, ScarecrowEntityRole.SUPPLY_INTERACTION)
        }
        supplyEntities[runtime.settings.id] = mutableSetOf(display.uniqueId, interaction.uniqueId)
    }

    private fun ensurePlaced(runtime: FarmRuntime, target: FarmCareTarget) {
        val key = ScarecrowKey(runtime.settings.id, target.id)
        val existing = placedDisplays[key]?.let(Bukkit::getEntity) as? ItemDisplay
        if (existing?.isValid == true) return
        val world = Bukkit.getWorld(target.position.world) ?: return
        if (!world.isChunkLoaded(target.position.x.toInt() shr 4, target.position.z.toInt() shr 4)) return
        spawnDisplay(
            runtime,
            Location(world, target.position.x, target.position.y, target.position.z),
            target.id,
            ScarecrowEntityRole.PLACED_DISPLAY,
        )?.let { placedDisplays[key] = it.uniqueId }
    }

    private fun spawnDisplay(
        runtime: FarmRuntime,
        location: Location,
        targetId: Int,
        role: ScarecrowEntityRole,
    ): ItemDisplay? {
        val visual = runtime.settings.careVisuals.getValue(FarmCareRole.SCARECROW)
        val stack = ItemStack(MaterialRules.material(visual.material)).also { item ->
            if (visual.customModelData > 0) item.itemMeta = item.itemMeta.also { it.setCustomModelData(visual.customModelData) }
        }
        val yOffset = if (role == ScarecrowEntityRole.CARRIED_DISPLAY) 0.0 else visual.displayYOffset
        return location.world.spawn(location.clone().add(0.0, yOffset, 0.0), ItemDisplay::class.java) { entity ->
            entity.setItemStack(stack)
            entity.itemDisplayTransform = visual.displayTransform.bukkit
            entity.transformation = Transformation(
                Vector3f(),
                AxisAngle4f(),
                Vector3f(visual.displayScale, visual.displayScale, visual.displayScale),
                AxisAngle4f(),
            )
            entity.viewRange = runtime.settings.displayViewRange
            entity.teleportDuration = 1
            entity.isGlowing = role != ScarecrowEntityRole.PLACED_DISPLAY
            entity.isPersistent = false
            mark(entity, runtime, targetId, role)
        }
    }

    private fun reconcile(runtime: FarmRuntime) {
        val zoneId = runtime.settings.id
        if (reconciledSequences[zoneId] == runtime.state.sequence) return
        entityLookup.inWorld(runtime.region.world).filter { entity ->
            entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) == zoneId
        }.forEach(Entity::remove)
        supplyEntities.remove(zoneId)
        carriers.keys.filter { it.zoneId == zoneId }.toList().forEach(carriers::remove)
        carriedDisplays.keys.filter { it.zoneId == zoneId }.toList().forEach(carriedDisplays::remove)
        placedDisplays.keys.filter { it.zoneId == zoneId }.toList().forEach(placedDisplays::remove)
        reconciledSequences[zoneId] = runtime.state.sequence
    }

    private fun returnScarecrow(key: ScarecrowKey, player: Player?, reason: String, notify: Boolean = true) {
        carriers.remove(key)
        removeCarried(key)
        if (notify && player?.isOnline == true) port.sendActionBar(player, MessageKey.FARM_CARE_SCARECROW_RETURNED)
        debug.event("farm_scarecrow_returned", "zone" to key.zoneId, "target" to key.targetId, "reason" to reason)
    }

    private fun removeSupply(zoneId: String) {
        supplyEntities.remove(zoneId).orEmpty().forEach { id -> Bukkit.getEntity(id)?.remove() }
    }

    private fun removeCarried(key: ScarecrowKey) {
        carriedDisplays.remove(key)?.let(Bukkit::getEntity)?.remove()
    }

    private fun removePlaced(key: ScarecrowKey) {
        placedDisplays.remove(key)?.let(Bukkit::getEntity)?.remove()
    }

    private fun carriedLocation(runtime: FarmRuntime, player: Player): Location {
        val direction = player.location.direction.setY(0.0)
        if (direction.lengthSquared() > 0.001) direction.normalize().multiply(-0.7)
        return player.location.clone().add(direction).add(0.0, runtime.settings.scarecrowCarriedYOffset, 0.0)
    }

    private fun role(entity: Entity): ScarecrowEntityRole? = entity.persistentDataContainer
        .get(roleKey, PersistentDataType.STRING)
        ?.let { runCatching { ScarecrowEntityRole.valueOf(it) }.getOrNull() }

    private fun mark(entity: Entity, runtime: FarmRuntime, targetId: Int, role: ScarecrowEntityRole) {
        entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, runtime.settings.id)
        entity.persistentDataContainer.set(sequenceKey, PersistentDataType.LONG, runtime.state.sequence)
        entity.persistentDataContainer.set(targetKey, PersistentDataType.INTEGER, targetId)
        entity.persistentDataContainer.set(roleKey, PersistentDataType.STRING, role.name)
    }

    private fun hasLifecycle(zoneId: String): Boolean = zoneId in supplyEntities ||
        carriers.keys.any { it.zoneId == zoneId } || placedDisplays.keys.any { it.zoneId == zoneId } ||
        reconciledSequences.containsKey(zoneId)

    private fun active(runtime: FarmRuntime): Boolean =
        runtime.state.phase == FarmPhase.CARE && runtime.state.careType == FarmCareType.SCARECROWS
}
