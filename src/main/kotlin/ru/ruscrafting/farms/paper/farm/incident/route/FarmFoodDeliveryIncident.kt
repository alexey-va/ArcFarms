package ru.ruscrafting.farms.paper.farm.incident.route

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Horse
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Vector3f
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmRouteGeometry
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.domain.NamedFarmDeliveryRoute
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.FarmNightShiftController
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.admin.FarmRouteAdminService
import ru.ruscrafting.farms.paper.farm.placement.FarmSurfacePolicy
import java.util.UUID
import java.util.random.RandomGenerator
import java.util.logging.Level
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Mounted route incident with a hard geometric corridor independent of WorldGuard. */
internal class FarmFoodDeliveryIncident(
    plugin: Plugin,
    private val settings: () -> ArcFarmsConfig,
    private val debug: ArcFarmsDebug,
    private val port: WorksiteRuntimePort,
    private val routes: FarmRouteAdminService,
    private val transitions: FarmTransitionSink,
    private val random: RandomGenerator,
    private val night: FarmNightShiftController,
) {
    private data class Session(
        val sequence: Long,
        val routeName: String,
        var horseId: UUID? = null,
        var cartId: UUID? = null,
        val loadIds: MutableList<UUID> = mutableListOf(),
        var riderId: UUID? = null,
        var spawnedMonsters: Int = 0,
        val monsterIds: MutableSet<UUID> = linkedSetOf(),
        val monsterGoal: Int,
        var lastWaveAt: Long = 0,
    )

    private val zoneKey = NamespacedKey(plugin, "farm_food_route_zone")
    private val sequenceKey = NamespacedKey(plugin, "farm_food_route_sequence")
    private val roleKey = NamespacedKey(plugin, "farm_food_route_role")
    private val sessions = mutableMapOf<String, Session>()

    fun owns(entity: Entity): Boolean = entity.persistentDataContainer.has(zoneKey, PersistentDataType.STRING)

    fun initialize(runtime: FarmRuntime): Boolean {
        if (!active(runtime)) return false
        val selected = selectedRoute(runtime) ?: run {
            port.log(
                Level.WARNING,
                "Could not start farm food delivery: zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                    "reason=no_eligible_route",
            )
            debug.event(
                "farm_food_delivery_unavailable", "zone" to runtime.settings.id,
                "sequence" to runtime.state.sequence, "reason" to "no_eligible_route",
            )
            return false
        }
        if (selected.route.points.first().world != runtime.region.world.name) {
            port.log(
                Level.WARNING,
                "Could not start farm food delivery: zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                    "reason=route_world_mismatch route=${selected.name} route_world=${selected.route.points.first().world} " +
                    "farm_world=${runtime.region.world.name}",
            )
            return false
        }
        if (runtime.state.specialIncident == null) {
            transitions.apply(
                runtime,
                FarmShiftEngine.initializeFoodDelivery(runtime.state, selected.route.points.size, selected.name),
                null,
            )
        }
        return runtime.state.specialIncident != null
    }

    fun ensure(runtime: FarmRuntime, now: Long) {
        if (!active(runtime)) {
            if (runtime.settings.id in sessions) clear(runtime.settings.id, "inactive")
            return
        }
        if (!initialize(runtime)) {
            clear(runtime.settings.id, "route_unavailable")
            transitions.apply(
                runtime,
                FarmShiftEngine.skipUnavailableIncident(runtime.state, FarmIncidentType.FOOD_DELIVERY),
                null,
            )
            return
        }
        val selected = selectedRoute(runtime) ?: return
        val route = selected.route
        val session = sessions[runtime.settings.id]?.takeIf {
            it.sequence == runtime.state.sequence && it.routeName == selected.name
        }
            ?: Session(
                sequence = runtime.state.sequence,
                routeName = selected.name,
                monsterGoal = random.nextInt(
                    runtime.settings.routeDelivery.monsterMinCount,
                    runtime.settings.routeDelivery.monsterMaxCount + 1,
                ),
            ).also { sessions[runtime.settings.id] = it }
        val horse = session.horseId?.let(Bukkit::getEntity) as? Horse
        val resumePoint = route.points[(runtime.state.incidentProgress - 1).coerceIn(0, route.points.lastIndex)]
        val resumeWorld = Bukkit.getWorld(resumePoint.world) ?: return
        if (horse == null && !resumeWorld.isChunkLoaded(floor(resumePoint.x).toInt() shr 4, floor(resumePoint.z).toInt() shr 4)) return
        val activeHorse = horse?.takeIf { it.isValid && !it.isDead } ?: run {
            val resumeLocation = safeSurface(location(resumePoint)) ?: return
            spawnHorse(runtime, resumeLocation).also { session.horseId = it.uniqueId }
        }
        val cart = session.cartId?.let(Bukkit::getEntity) as? ItemDisplay
        if (cart == null || !cart.isValid) session.cartId = spawnCart(runtime, activeHorse.location).uniqueId
        val order = runtime.state.orderId?.let(runtime.orders::get) ?: return
        session.loadIds.removeIf { id -> (Bukkit.getEntity(id) as? ItemDisplay)?.isValid != true }
        while (session.loadIds.size > runtime.settings.routeDelivery.cartLoadCount) {
            Bukkit.getEntity(session.loadIds.removeLast())?.remove()
        }
        while (session.loadIds.size < runtime.settings.routeDelivery.cartLoadCount) {
            session.loadIds += spawnLoad(runtime, activeHorse.location, order.cartLoadMaterial, order.cartLoadCustomModelData).uniqueId
        }
        session.riderId = activeHorse.passengers.filterIsInstance<Player>().firstOrNull()?.uniqueId
        val participants = (port.players(runtime.region) + activeHorse.passengers.filterIsInstance<Player>()).distinctBy(Player::getUniqueId)
        night.syncAmbientTime(
            nightOwner(runtime.settings.id),
            participants,
            runtime.settings.routeDelivery.playerTime,
            runtime.settings.routeDelivery.timeTransitionSeconds,
        )
        updateProgress(runtime, activeHorse, session, route.points)
        if (!active(runtime)) return
        updateMonsters(runtime, activeHorse, session, route.points, now)
    }

    fun updateVisuals(runtimes: Collection<FarmRuntime>) {
        sessions.toMap().forEach { (zoneId, session) ->
            val runtime = runtimes.firstOrNull { it.settings.id == zoneId && it.state.sequence == session.sequence }
                ?.takeIf(::active) ?: return@forEach
            val horse = session.horseId?.let(Bukkit::getEntity) as? Horse ?: return@forEach
            val cart = session.cartId?.let(Bukkit::getEntity) as? ItemDisplay ?: return@forEach
            val route = selectedRoute(runtime)?.takeIf { it.name == session.routeName }?.route ?: return@forEach
            val rider = horse.passengers.filterIsInstance<Player>().firstOrNull()
            horse.setAI(rider != null)
            if (rider == null) horse.velocity = Vector()
            val yaw = Math.toRadians(horse.location.yaw.toDouble())
            val behind = horse.location.clone().add(sin(yaw) * 2.15, runtime.settings.routeDelivery.cartYOffset, -cos(yaw) * 2.15)
            behind.yaw = horse.location.yaw
            cart.teleport(behind)
            session.loadIds.forEachIndexed { slot, id ->
                (Bukkit.getEntity(id) as? ItemDisplay)?.teleport(loadLocation(behind, slot, runtime.settings.contractCartVisual.loadYOffset))
            }
            session.monsterIds.toList().forEach { id ->
                val monster = Bukkit.getEntity(id) as? Mob
                if (monster == null || !monster.isValid || monster.isDead) {
                    night.releaseExternalLight(monsterLightOwner(zoneId, id))
                } else {
                    night.updateExternalLight(
                        monsterLightOwner(zoneId, id), monster, runtime.settings.routeDelivery.monsterLightLevel,
                    )
                }
            }
            if (settings().particles && horse.world.gameTime % TRAIL_INTERVAL_TICKS == 0L) {
                val viewers = rider?.let(::listOf) ?: port.players(runtime.region)
                viewers.filter { it.world == horse.world && !port.isAdminEditing(it) }
                    .forEach { viewer -> renderTrail(runtime, viewer, route.points) }
            }
        }
    }

    fun interact(event: PlayerInteractEntityEvent, runtimes: Collection<FarmRuntime>): Boolean {
        if (!owns(event.rightClicked) || role(event.rightClicked) != ROLE_HORSE) return false
        event.isCancelled = true
        val runtime = runtime(event.rightClicked, runtimes) ?: return true
        if (!port.hasAccess(event.player, runtime.settings.permission)) {
            port.sendChat(event.player, MessageKey.ZONE_LOCKED)
            return true
        }
        val horse = event.rightClicked as? Horse ?: return true
        val existing = horse.passengers.filterIsInstance<Player>().firstOrNull()
        if (existing != null && existing.uniqueId != event.player.uniqueId) {
            port.sendActionBar(event.player, MessageKey.FARM_ROUTE_OCCUPIED)
            return true
        }
        if (existing == null) horse.addPassenger(event.player)
        horse.setAI(true)
        sessions[runtime.settings.id]?.riderId = event.player.uniqueId
        port.showScreenTitle(event.player, MessageKey.FARM_ROUTE_MOUNTED)
        event.player.playSound(event.player.location, Sound.ENTITY_HORSE_SADDLE, 0.8f, 1.05f)
        return true
    }

    fun onDamage(event: EntityDamageEvent): Boolean {
        val incoming = event as? EntityDamageByEntityEvent
        val player = incoming?.entity as? Player
        if (player != null && owns(incoming.damager) && role(incoming.damager) == ROLE_MONSTER) {
            val zone = incoming.damager.persistentDataContainer.get(zoneKey, PersistentDataType.STRING)
            val session = zone?.let(sessions::get)
            event.isCancelled = session?.riderId != player.uniqueId
            return true
        }
        if (!owns(event.entity)) return false
        if (role(event.entity) == ROLE_MONSTER) return false
        event.isCancelled = true
        return true
    }

    fun onDeath(event: EntityDeathEvent): Boolean {
        if (!owns(event.entity) || role(event.entity) != ROLE_MONSTER) return false
        val zoneId = event.entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING)
        zoneId?.let(sessions::get)?.monsterIds?.remove(event.entity.uniqueId)
        if (zoneId != null) night.releaseExternalLight(monsterLightOwner(zoneId, event.entity.uniqueId))
        event.drops.clear()
        event.droppedExp = 0
        return true
    }

    fun onQuit(player: Player) {
        sessions.values.filter { it.riderId == player.uniqueId }.forEach { session ->
            session.riderId = null
            (session.horseId?.let(Bukkit::getEntity) as? Horse)?.let { horse ->
                horse.setAI(false)
                horse.velocity = Vector()
            }
        }
    }

    fun clear(zoneId: String, reason: String) {
        sessions.remove(zoneId)?.let(::removeSessionEntities)
        night.clearAmbientTime(nightOwner(zoneId))
        night.releaseExternalLights("food:$zoneId:")
        debug.event("farm_food_route_cleared", "zone" to zoneId, "reason" to reason)
    }

    fun cleanup(reason: String) {
        Bukkit.getWorlds().asSequence().flatMap { it.entities.asSequence() }.filter(::owns).forEach(Entity::remove)
        sessions.keys.forEach { night.clearAmbientTime(nightOwner(it)) }
        sessions.clear()
        night.releaseExternalLights("food:")
        debug.event("farm_food_route_cleanup", "reason" to reason)
    }

    private fun updateProgress(
        runtime: FarmRuntime,
        horse: Horse,
        session: Session,
        points: List<FarmPointPosition>,
    ) {
        val rider = horse.passengers.filterIsInstance<Player>().firstOrNull() ?: return
        if (session.riderId != null && session.riderId != rider.uniqueId) return
        session.riderId = rider.uniqueId
        val currentIndex = runtime.state.incidentProgress.coerceIn(1, points.size)
        val projection = FarmRouteGeometry.closest(
            horse.world.name, horse.location.x, horse.location.y, horse.location.z,
            points, (currentIndex - 1).coerceAtLeast(0),
        ) ?: return
        val projectionLocation = Location(horse.world, projection.x, projection.y, projection.z, horse.location.yaw, 0f)
        val distance = projection.distance
        val config = runtime.settings.routeDelivery
        if (distance > config.hardResetDistance) {
            correctTowardRoute(horse, projectionLocation, 0.42)
            port.sendActionBar(rider, MessageKey.FARM_ROUTE_RETURNED)
            return
        }
        if (distance > config.corridorRadius) {
            correctTowardRoute(horse, projectionLocation, 0.24)
            port.sendActionBar(rider, MessageKey.FARM_ROUTE_CORRIDOR)
            return
        }
        val reached = maxOf(currentIndex, FarmRouteGeometry.reachedPoint(projection, points.size))
        if (reached <= currentIndex) return
        val result = FarmShiftEngine.advanceFoodDelivery(
            runtime.state,
            reached,
            rider.uniqueId,
            config.completionContribution,
        )
        transitions.apply(runtime, result, rider)
        if (result.accepted && settings().particles) {
            horse.world.spawnParticle(Particle.HAPPY_VILLAGER, horse.location.add(0.0, 1.0, 0.0), 8, 0.5, 0.4, 0.5, 0.0)
        }
    }

    private fun renderTrail(runtime: FarmRuntime, rider: Player, points: List<FarmPointPosition>) {
        val config = runtime.settings.routeDelivery
        val current = runtime.state.incidentProgress.coerceIn(1, points.size)
        val start = (current - 1).coerceAtLeast(0)
        val endExclusive = (start + config.trailLookaheadPoints).coerceAtMost(points.size)
        val dust = Particle.DustOptions(TRAIL_COLOR, config.trailParticleSize)
        points.subList(start, endExclusive).forEachIndexed { offset, point ->
            val marker = location(point).add(0.0, config.trailHeight, 0.0)
            rider.spawnParticle(Particle.DUST, marker, 1, 0.04, 0.03, 0.04, 0.0, dust)
            if (offset == 1) {
                repeat(4) { layer ->
                    rider.spawnParticle(
                        Particle.DUST,
                        marker.clone().add(0.0, 0.55 + layer * 0.55, 0.0),
                        1,
                        0.04,
                        0.04,
                        0.04,
                        0.0,
                        Particle.DustOptions(NEXT_CHECKPOINT_COLOR, config.trailParticleSize + 0.2f),
                    )
                }
            }
        }
    }

    private fun updateMonsters(
        runtime: FarmRuntime,
        horse: Horse,
        session: Session,
        points: List<FarmPointPosition>,
        now: Long,
    ) {
        val rider = horse.passengers.filterIsInstance<Player>().firstOrNull() ?: return
        val config = runtime.settings.routeDelivery
        if (session.spawnedMonsters >= session.monsterGoal || config.monsterMaxAlive == 0) return
        session.monsterIds.removeIf { entityId ->
            val entity = Bukkit.getEntity(entityId)
            val gone = entity == null || !entity.isValid || entity.isDead
            if (gone) night.releaseExternalLight(monsterLightOwner(runtime.settings.id, entityId))
            gone
        }
        val alive = session.monsterIds.size
        if (alive >= config.monsterMaxAlive || now - session.lastWaveAt < config.monsterIntervalSeconds * 1_000L) return
        val ahead = (runtime.state.incidentProgress + 3).coerceAtMost(points.lastIndex)
        val anchor = location(points[ahead])
        val waveSize = random.nextInt(config.monsterWaveMin, config.monsterWaveMax + 1).coerceAtMost(
            minOf(session.monsterGoal - session.spawnedMonsters, config.monsterMaxAlive - alive),
        )
        repeat(waveSize) { index ->
            val angle = random.nextDouble() * Math.PI * 2 + index * (Math.PI * 2 / waveSize.coerceAtLeast(1))
            val distance = config.monsterSpawnDistance * random.nextDouble(0.8, 1.15)
            val spawn = anchor.clone().add(cos(angle) * distance, 0.0, sin(angle) * distance)
            val ground = safeSurface(spawn) ?: return@repeat
            val monster = spawn.world.spawnEntity(ground, EntityType.HUSK) as Mob
            monster.isPersistent = false
            monster.removeWhenFarAway = true
            monster.target = rider
            monster.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = config.monsterMovementSpeed
            monster.equipment.setItemInMainHand(ItemStack(Material.TORCH), true)
            monster.equipment.itemInMainHandDropChance = 0.0f
            mark(monster, runtime, ROLE_MONSTER)
            session.monsterIds += monster.uniqueId
            session.spawnedMonsters++
            night.updateExternalLight(monsterLightOwner(runtime.settings.id, monster.uniqueId), monster, config.monsterLightLevel)
        }
        if (waveSize == 0) return
        session.lastWaveAt = now
        rider.playSound(rider.location, Sound.ENTITY_HUSK_AMBIENT, 0.8f, 0.75f)
        port.sendActionBar(rider, MessageKey.FARM_ROUTE_ATTACK)
    }

    private fun spawnHorse(runtime: FarmRuntime, location: Location): Horse =
        runtime.region.world.spawn(location, Horse::class.java) { horse ->
            horse.isPersistent = false
            horse.removeWhenFarAway = false
            horse.isTamed = true
            horse.owner = null
            horse.inventory.saddle = ItemStack(Material.SADDLE)
            horse.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = runtime.settings.routeDelivery.horseSpeed
            horse.getAttribute(Attribute.JUMP_STRENGTH)?.baseValue = 0.45
            mark(horse, runtime, ROLE_HORSE)
        }

    private fun spawnCart(runtime: FarmRuntime, at: Location): ItemDisplay =
        runtime.region.world.spawn(at, ItemDisplay::class.java) { display ->
            display.isPersistent = false
            display.isInvulnerable = true
            display.interpolationDuration = 2
            display.teleportDuration = 2
            display.viewRange = runtime.settings.contractCartVisual.viewRange
            val item = ItemStack(MaterialRules.material(runtime.settings.contractCartVisual.material))
            if (runtime.settings.contractCartVisual.customModelData > 0) item.editMeta { meta: ItemMeta ->
                @Suppress("DEPRECATION")
                meta.setCustomModelData(runtime.settings.contractCartVisual.customModelData)
            }
            display.setItemStack(item)
            display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GROUND
            val scale = runtime.settings.routeDelivery.cartScale
            display.transformation = Transformation(
                display.transformation.translation,
                display.transformation.leftRotation,
                Vector3f(scale, scale, scale),
                display.transformation.rightRotation,
            )
            mark(display, runtime, ROLE_CART)
        }

    private fun spawnLoad(
        runtime: FarmRuntime,
        at: Location,
        material: String,
        customModelData: Int,
    ): ItemDisplay = runtime.region.world.spawn(at, ItemDisplay::class.java) { display ->
        display.isPersistent = false
        display.isInvulnerable = true
        display.interpolationDuration = 2
        display.teleportDuration = 2
        display.viewRange = runtime.settings.contractCartVisual.viewRange
        val item = ItemStack(MaterialRules.material(material))
        if (customModelData > 0) item.editMeta { meta: ItemMeta ->
            @Suppress("DEPRECATION")
            meta.setCustomModelData(customModelData)
        }
        display.setItemStack(item)
        display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GROUND
        val scale = runtime.settings.contractCartVisual.loadScale
        display.transformation = Transformation(
            display.transformation.translation,
            display.transformation.leftRotation,
            Vector3f(scale, scale, scale),
            display.transformation.rightRotation,
        )
        mark(display, runtime, ROLE_LOAD)
    }

    private fun loadLocation(cart: Location, slot: Int, yOffset: Double): Location {
        val offsets = listOf(-0.24 to -0.08, 0.24 to -0.08, -0.24 to 0.24, 0.24 to 0.24)
        val (localX, localZ) = offsets[slot.coerceIn(0, offsets.lastIndex)]
        val radians = Math.toRadians(cart.yaw.toDouble())
        val x = localX * cos(radians) - localZ * sin(radians)
        val z = localX * sin(radians) + localZ * cos(radians)
        return cart.clone().add(x, yOffset, z).apply { yaw = cart.yaw }
    }

    private fun mark(entity: Entity, runtime: FarmRuntime, role: String) {
        entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, runtime.settings.id)
        entity.persistentDataContainer.set(sequenceKey, PersistentDataType.LONG, runtime.state.sequence)
        entity.persistentDataContainer.set(roleKey, PersistentDataType.STRING, role)
    }

    private fun removeSessionEntities(session: Session) {
        buildList {
            session.horseId?.let(::add)
            session.cartId?.let(::add)
            addAll(session.loadIds)
            addAll(session.monsterIds)
        }.forEach { id -> Bukkit.getEntity(id)?.remove() }
    }

    private fun runtime(entity: Entity, runtimes: Collection<FarmRuntime>): FarmRuntime? {
        val zone = entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) ?: return null
        val sequence = entity.persistentDataContainer.get(sequenceKey, PersistentDataType.LONG) ?: return null
        return runtimes.firstOrNull { it.settings.id == zone && it.state.sequence == sequence && active(it) }
    }

    private fun role(entity: Entity): String? = entity.persistentDataContainer.get(roleKey, PersistentDataType.STRING)

    private fun selectedRoute(runtime: FarmRuntime): NamedFarmDeliveryRoute? {
        val persistedName = runtime.state.specialIncident?.routeName
        if (persistedName != null) {
            return routes.route(runtime.settings.id, persistedName)?.let { NamedFarmDeliveryRoute(persistedName, it) }
        }
        return routes.select(runtime.settings.id, runtime.state.sequence)
    }

    private fun active(runtime: FarmRuntime) =
        runtime.state.phase == FarmPhase.INCIDENT && runtime.state.incidentType == FarmIncidentType.FOOD_DELIVERY

    private fun location(point: FarmPointPosition): Location = Location(
        requireNotNull(Bukkit.getWorld(point.world)), point.x, point.y, point.z, point.yaw, point.pitch,
    )

    private fun correctTowardRoute(horse: Horse, target: Location, strength: Double) {
        val correction = target.toVector().subtract(horse.location.toVector()).apply { y = 0.0 }
        if (correction.lengthSquared() <= 0.0001) {
            horse.velocity = Vector()
            return
        }
        horse.velocity = correction.normalize().multiply(strength)
    }

    private fun safeSurface(near: Location): Location? {
        val world = near.world
        if (!world.isChunkLoaded(near.blockX shr 4, near.blockZ shr 4)) return null
        val baseY = near.blockY
        for (offset in 0..4) {
            for (y in listOf(baseY + offset, baseY - offset).distinct()) {
                if (y !in world.minHeight + 1 until world.maxHeight - 1) continue
                val feet = world.getBlockAt(near.blockX, y, near.blockZ)
                val head = feet.getRelative(0, 1, 0)
                val candidate = Location(world, near.blockX + 0.5, y.toDouble(), near.blockZ + 0.5)
                if (
                    feet.isPassable && head.isPassable && feet.getRelative(0, -1, 0).type.isSolid &&
                    FarmSurfacePolicy.isSurfaceSpawn(candidate)
                ) {
                    return candidate
                }
            }
        }
        return null
    }

    private companion object {
        const val TRAIL_INTERVAL_TICKS = 10L
        val TRAIL_COLOR: Color = Color.fromRGB(69, 200, 245)
        val NEXT_CHECKPOINT_COLOR: Color = Color.fromRGB(255, 200, 87)
        const val ROLE_HORSE = "horse"
        const val ROLE_CART = "cart"
        const val ROLE_LOAD = "load"
        const val ROLE_MONSTER = "monster"
    }

    private fun nightOwner(zoneId: String): String = "food:$zoneId"

    private fun monsterLightOwner(zoneId: String, id: UUID): String = "food:$zoneId:$id"
}
