package ru.ruscrafting.farms.paper.farm.incident.route

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.Horse
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Vector3f
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmFoodDeliveryCompletion
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmRouteGeometry
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.domain.NamedFarmDeliveryRoute
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.FarmNightShiftController
import ru.ruscrafting.farms.paper.worksite.*
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.admin.FarmRouteAdminService
import ru.ruscrafting.farms.paper.farm.placement.FarmSurfacePolicy
import ru.ruscrafting.farms.paper.platform.*
import ru.ruscrafting.farms.paper.farm.presentation.FarmActivityPortal
import ru.ruscrafting.farms.paper.farm.presentation.FarmPortalStyle
import ru.ruscrafting.farms.paper.farm.presentation.FarmPortalDestination
import java.util.random.RandomGenerator
import java.util.logging.Level
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Mounted route incident with a hard geometric corridor independent of WorldGuard. */
internal class FarmFoodDeliveryIncident(
    plugin: Plugin,
    private val settings: () -> ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
    private val access: WorksiteAccessPort, private val audience: WorksiteAudiencePort,
    private val state: WorksiteStatePort, private val tasks: WorksiteTaskPort,
    private val routes: FarmRouteAdminService, private val points: FarmPointProvider,
    private val transitions: FarmTransitionSink,
    private val random: RandomGenerator,
    private val night: FarmNightShiftController,
    entityRayTrace: FarmEntityRayTrace,
    private val blockPassability: FarmBlockPassability,
    private val mobDespawns: FarmMobDespawnPolicy,
    private val vehiclePassengers: FarmVehiclePassengerControl,
    private val textDisplays: FarmTextDisplayRenderer,
    private val chunkLoader: FarmRouteChunkLoader,
) {
    private val zoneKey = NamespacedKey(plugin, "farm_food_route_zone")
    private val sequenceKey = NamespacedKey(plugin, "farm_food_route_sequence")
    private val roleKey = NamespacedKey(plugin, "farm_food_route_role")
    private val gunner = FarmFoodDeliveryGunner(plugin, locale, settings, debug, audience, tasks, entityRayTrace)
    private val safety = FarmFoodDeliverySafety(audience, debug, routes, vehiclePassengers, gunner)
    private val ambush = FarmFoodDeliveryAmbush(random, night, audience, debug, mobDespawns, vehiclePassengers)
    private val sessions = mutableMapOf<String, FarmFoodDeliverySession>()
    private val lastRouteNames = mutableMapOf<String, String>()
    private val portals = FarmActivityPortal(plugin, locale, access, audience, tasks, textDisplays, "farm_food_route")
    fun owns(entity: Entity): Boolean = entity.persistentDataContainer.has(zoneKey, PersistentDataType.STRING)

    fun ownsServiceItem(item: ItemStack?): Boolean = gunner.owns(item)

    fun removeServiceItems(player: Player, reason: String) = gunner.remove(player, reason)
    fun participants(runtime: FarmRuntime): List<Player> {
        if (!active(runtime)) return emptyList()
        val session = sessions[runtime.settings.id]?.takeIf { it.sequence == runtime.state.sequence } ?: return emptyList()
        val horse = session.horseId?.let(Bukkit::getEntity) as? Horse
        val seat = session.gunnerSeatId?.let(Bukkit::getEntity) as? Interaction
        return (session.participantIds().mapNotNull(Bukkit::getPlayer) +
            horse?.passengers.orEmpty().filterIsInstance<Player>() +
            seat?.passengers.orEmpty().filterIsInstance<Player>())
            .filter(Player::isOnline)
            .distinctBy(Player::getUniqueId)
    }

    fun participantRuntime(player: Player, runtimes: Collection<FarmRuntime>): FarmRuntime? =
        runtimes.firstOrNull { runtime -> participants(runtime).any { it.uniqueId == player.uniqueId } }

    fun initialize(runtime: FarmRuntime): Boolean {
        if (!active(runtime)) return false
        val selected = selectedRoute(runtime) ?: run {
            state.log(
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
            state.log(
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
                skipUnavailable(runtime, now),
                null,
            )
            return
        }
        val selected = selectedRoute(runtime) ?: return
        val route = selected.route
        sessions[runtime.settings.id]?.takeIf {
            it.sequence != runtime.state.sequence || it.routeName != selected.name
        }?.let { clear(runtime.settings.id, "route_identity_changed") }
        val session = sessions[runtime.settings.id]
            ?: FarmFoodDeliverySession(runtime.state.sequence, selected.name)
                .also { sessions[runtime.settings.id] = it }
        // Farm entry and ambience must not depend on a distant route chunk being loaded.
        ensurePortal(runtime, session)
        val participants = (
            audience.players(runtime.region) + participants(runtime)
            ).distinctBy(Player::getUniqueId)
        night.syncAmbientTime(
            nightOwner(runtime.settings.id),
            participants,
            runtime.settings.routeDelivery.playerTime,
            runtime.settings.routeDelivery.timeTransitionSeconds,
        )
        refreshAmbushPlan(runtime, session, route.points)
        val horse = (session.horseId?.let(Bukkit::getEntity) as? Horse)?.takeIf { it.isValid && !it.isDead }
        val resumePoint = route.points[(runtime.state.incidentProgress - 1).coerceIn(0, route.points.lastIndex)]
        val resumeWorld = Bukkit.getWorld(resumePoint.world) ?: return
        if (horse == null && !resumeWorld.isChunkLoaded(floor(resumePoint.x).toInt() shr 4, floor(resumePoint.z).toInt() shr 4)) {
            if (!session.resumeChunkPending && now >= session.resumeChunkRetryAt) {
                session.resumeChunkPending = true
                session.resumeChunkRetryAt = now + 10_000L
                val token = tasks.lifecycleToken()
                chunkLoader.load(resumeWorld, floor(resumePoint.x).toInt() shr 4, floor(resumePoint.z).toInt() shr 4)
                    .whenComplete { chunk, failure ->
                        tasks.runSync(token) {
                            if (sessions[runtime.settings.id] !== session) return@runSync
                            session.resumeChunkPending = false
                            if (failure != null || chunk == null) {
                                state.log(Level.WARNING, "Could not resume farm food delivery: zone=${runtime.settings.id} " +
                                    "sequence=${session.sequence} reason=resume_chunk_unavailable")
                            } else {
                                ensure(runtime, System.currentTimeMillis())
                            }
                        }
                    }
            }
            return
        }
        val activeHorse = horse ?: run {
            val resumeLocation = safeSurface(location(resumePoint)) ?: return
            spawnHorse(runtime, resumeLocation).also { session.horseId = it.uniqueId }
        }
        configureHorse(runtime, activeHorse)
        val cart = (session.cartId?.let(Bukkit::getEntity) as? ItemDisplay)?.takeIf(Entity::isValid)
            ?: spawnCart(runtime, activeHorse.location).also { session.cartId = it.uniqueId }
        configureCart(runtime, cart)
        val gunnerSeat = (session.gunnerSeatId?.let(Bukkit::getEntity) as? Interaction)?.takeIf(Entity::isValid)
            ?: spawnGunnerSeat(runtime, activeHorse.location).also { session.gunnerSeatId = it.uniqueId }
        configureGunnerSeat(runtime, gunnerSeat)
        val order = runtime.state.orderId?.let(runtime.orders::get) ?: return
        session.loadIds.removeIf { id -> (Bukkit.getEntity(id) as? ItemDisplay)?.isValid != true }
        while (session.loadIds.size > runtime.settings.routeDelivery.cartLoadCount) {
            Bukkit.getEntity(session.loadIds.removeLast())?.remove()
        }
        while (session.loadIds.size < runtime.settings.routeDelivery.cartLoadCount) {
            session.loadIds += spawnLoad(runtime, activeHorse.location, order.cartLoadMaterial, order.cartLoadCustomModelData).uniqueId
        }
        session.loadIds.mapNotNull { Bukkit.getEntity(it) as? ItemDisplay }.forEach { display ->
            configureLoad(runtime, display, order.cartLoadMaterial, order.cartLoadCustomModelData)
        }
        gunner.reconcile(runtime, session, activeHorse)
        updateProgress(runtime, activeHorse, session, route.points, now)
        if (!active(runtime)) return
        updateMonsters(runtime, activeHorse, session, route.points)
    }

    fun updateVisuals(runtimes: Collection<FarmRuntime>) {
        portals.update(settings().particles)
        sessions.toMap().forEach { (zoneId, session) ->
            val runtime = runtimes.firstOrNull { it.settings.id == zoneId && it.state.sequence == session.sequence }
                ?.takeIf(::active) ?: return@forEach
            val horse = session.horseId?.let(Bukkit::getEntity) as? Horse ?: return@forEach
            val cart = session.cartId?.let(Bukkit::getEntity) as? ItemDisplay ?: return@forEach
            val gunnerSeat = session.gunnerSeatId?.let(Bukkit::getEntity) as? Interaction ?: return@forEach
            val route = selectedRoute(runtime)?.takeIf { it.name == session.routeName }?.route ?: return@forEach
            val rider = horse.passengers.filterIsInstance<Player>().firstOrNull()
            horse.setAI(rider != null && !session.brokenDown)
            if (rider == null || session.brokenDown) horse.velocity = Vector()
            val yaw = Math.toRadians(horse.location.yaw.toDouble())
            val cartBackOffset = runtime.settings.routeDelivery.cartBackOffset
            val behind = horse.location.clone().add(
                sin(yaw) * cartBackOffset,
                runtime.settings.routeDelivery.cartYOffset,
                -cos(yaw) * cartBackOffset,
            )
            behind.yaw = horse.location.yaw
            cart.teleport(behind)
            val seatLocation = behind.clone().add(
                sin(yaw) * runtime.settings.routeDelivery.gunnerSeatBackOffset,
                runtime.settings.routeDelivery.gunnerSeatYOffset,
                -cos(yaw) * runtime.settings.routeDelivery.gunnerSeatBackOffset,
            )
            gunner.moveSeat(runtime, session, gunnerSeat, seatLocation)
            session.loadIds.forEachIndexed { slot, id ->
                (Bukkit.getEntity(id) as? ItemDisplay)?.teleport(
                    loadLocation(
                        behind,
                        slot,
                        runtime.settings.contractCartVisual.loadYOffset,
                        runtime.settings.routeDelivery.cartLoadSpacing,
                    ),
                )
            }
            ambush.updateLights(
                zoneId,
                session,
                runtime.settings.routeDelivery.monsterLightLevel,
                runtime.settings.routeDelivery.monsterMovementSpeed,
            )
            gunner.render(runtime, session, behind)
            if (settings().particles && horse.world.gameTime % TRAIL_INTERVAL_TICKS == 0L) {
                participants(runtime).filter { it.world == horse.world && !access.isAdminEditing(it) }
                    .forEach { viewer -> FarmFoodDeliveryRouteVisual.render(runtime, viewer, route.points) }
            }
        }
    }

    fun interact(event: PlayerInteractEntityEvent, runtimes: Collection<FarmRuntime>): Boolean {
        if (!owns(event.rightClicked)) return false
        val entityRole = role(event.rightClicked)
        if (entityRole !in setOf(ROLE_HORSE, ROLE_CART, ROLE_GUNNER_SEAT, ROLE_PORTAL)) return false
        event.isCancelled = true
        val runtime = runtime(event.rightClicked, runtimes) ?: return true
        if (!access.hasAccess(event.player, runtime.settings.permission)) {
            audience.sendChat(event.player, MessageKey.ZONE_LOCKED)
            return true
        }
        val session = sessions[runtime.settings.id] ?: return true
        if (entityRole == ROLE_PORTAL) {
            enterPortal(event.player, event.player.location, runtimes)
            return true
        }
        val horse = session.horseId?.let(Bukkit::getEntity) as? Horse ?: return true
        return mountAvailableSeat(event.player, runtime, session, horse)
    }

    fun enterPortal(player: Player, destination: Location, runtimes: Collection<FarmRuntime>): Boolean =
        portals.enter(player, destination)

    private fun mountAvailableSeat(
        player: Player,
        runtime: FarmRuntime,
        session: FarmFoodDeliverySession,
        horse: Horse,
    ): Boolean {
        if (session.brokenDown) {
            audience.sendActionBar(player, MessageKey.FARM_ROUTE_BROKEN)
            return true
        }
        val existing = horse.passengers.filterIsInstance<Player>().firstOrNull()
        if (existing != null && existing.uniqueId != player.uniqueId) {
            val seat = session.gunnerSeatId?.let(Bukkit::getEntity) as? Interaction ?: return true
            return gunner.mount(player, runtime, session, seat, horse)
        }
        if (existing == null && !horse.addPassenger(player)) return true
        horse.setAI(true)
        session.riderId = player.uniqueId
        session.registerParticipant(player.uniqueId)
        safety.reset(session, horse.location, runtime.state.incidentProgress, Bukkit.getCurrentTick().toLong())
        session.ambushCrewIds.remove(player.uniqueId)
        session.escortIds.remove(player.uniqueId)
        gunner.armDriver(player, runtime, session)
        audience.showScreenTitle(player, MessageKey.FARM_ROUTE_MOUNTED)
        player.playSound(player.location, Sound.ENTITY_HORSE_SADDLE, 0.8f, 1.05f)
        debug.event(
            "farm_food_driver_mounted", "zone" to runtime.settings.id,
            "sequence" to session.sequence, "player" to player.name,
        )
        return true
    }

    fun onInteract(event: org.bukkit.event.player.PlayerInteractEvent, runtimes: Collection<FarmRuntime>): Boolean {
        val runtime = runtimes.firstOrNull { candidate ->
            val session = sessions[candidate.settings.id] ?: return@firstOrNull false
            (session.riderId == event.player.uniqueId || session.gunnerId == event.player.uniqueId ||
                event.player.uniqueId in session.escortIds || event.player.uniqueId in session.ambushCrewIds) && active(candidate)
        }
        return gunner.interact(event, runtime, runtime?.let { sessions[it.settings.id] })
    }

    fun onDamage(event: EntityDamageEvent, runtimes: Collection<FarmRuntime>): Boolean {
        if (safety.rescueSuffocatingGunner(event, runtimes, sessions, ::safeSurface)) return true
        val incoming = event as? EntityDamageByEntityEvent
        val player = incoming?.entity as? Player
        if (player != null && owns(incoming.damager) && role(incoming.damager) == ROLE_MONSTER) {
            val zone = incoming.damager.persistentDataContainer.get(zoneKey, PersistentDataType.STRING)
            val session = zone?.let(sessions::get)
            event.isCancelled = session?.isParticipant(player.uniqueId) != true
            return true
        }
        if (!owns(event.entity)) return false
        if (role(event.entity) == ROLE_MONSTER) return false
        event.isCancelled = true
        return true
    }

    fun onDeath(event: EntityDeathEvent, runtimes: Collection<FarmRuntime>): Boolean {
        if (!owns(event.entity) || role(event.entity) != ROLE_MONSTER) return false
        val zoneId = event.entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING)
        val session = zoneId?.let(sessions::get)
        session?.monsterIds?.remove(event.entity.uniqueId)
        if (zoneId != null) ambush.releaseLight(zoneId, event.entity.uniqueId)
        val killer = event.entity.killer
        if (zoneId != null && session != null && killer != null) {
            // State ownership is validated by the sequence tag before crediting the defender.
            runtimes.firstOrNull { it.settings.id == zoneId && it.state.sequence == session.sequence && active(it) }?.let { runtime ->
                transitions.apply(runtime, FarmShiftEngine.defendFoodDelivery(runtime.state, killer.uniqueId), killer)
            }
        }
        event.drops.clear()
        event.droppedExp = 0
        return true
    }

    fun onQuit(player: Player) {
        portals.onQuit(player)
        sessions.forEach { (zoneId, session) ->
            val wasRider = session.riderId == player.uniqueId
            if (wasRider || session.gunnerId == player.uniqueId || player.uniqueId in session.escortIds ||
                player.uniqueId in session.ambushCrewIds
            ) {
                gunner.release(player, zoneId, session, "player_quit")
            }
            if (!wasRider) return@forEach
            (session.horseId?.let(Bukkit::getEntity) as? Horse)?.let { horse ->
                horse.setAI(false)
                horse.velocity = Vector()
            }
        }
    }

    fun clear(zoneId: String, reason: String) {
        portals.clear(zoneId)
        sessions.remove(zoneId)?.let { session ->
            removeSessionEntities(session)
            gunner.clear(zoneId, session, reason)
        }
        night.clearAmbientTime(nightOwner(zoneId))
        ambush.clear(zoneId)
        debug.event("farm_food_route_cleared", "zone" to zoneId, "reason" to reason)
    }

    fun cleanup(reason: String) {
        Bukkit.getWorlds().asSequence().flatMap { it.entities.asSequence() }.filter(::owns).forEach(Entity::remove)
        sessions.keys.forEach { night.clearAmbientTime(nightOwner(it)) }
        sessions.clear()
        portals.cleanup()
        gunner.cleanup(reason)
        night.releaseExternalLights("food:")
        debug.event("farm_food_route_cleanup", "reason" to reason)
    }

    fun refresh(runtime: FarmRuntime, reason: String) {
        if (!active(runtime)) return
        val session = sessions[runtime.settings.id] ?: return
        portals.clear(runtime.settings.id)
        ensurePortal(runtime, session)
        debug.event(
            "farm_food_route_portal_refreshed",
            "zone" to runtime.settings.id,
            "sequence" to session.sequence,
            "reason" to reason,
        )
    }

    private fun updateProgress(
        runtime: FarmRuntime,
        horse: Horse,
        session: FarmFoodDeliverySession,
        points: List<FarmPointPosition>,
        now: Long,
    ) {
        val rider = horse.passengers.filterIsInstance<Player>().firstOrNull() ?: return
        if (session.riderId != null && session.riderId != rider.uniqueId) return
        session.riderId = rider.uniqueId
        session.registerParticipant(rider.uniqueId)
        val currentIndex = runtime.state.incidentProgress.coerceIn(1, points.size)
        if (safety.update(runtime, horse, session, rider, points, currentIndex, ::safeSurface)) return
        val projection = FarmRouteGeometry.closest(
            horse.world.name, horse.location.x, horse.location.y, horse.location.z,
            points, (currentIndex - 1).coerceAtLeast(0),
        ) ?: return
        val projectionLocation = Location(horse.world, projection.x, projection.y, projection.z, horse.location.yaw, 0f)
        val distance = projection.distance
        val config = runtime.settings.routeDelivery
        val atDestination = FarmRouteGeometry.atDestination(
            horse.world.name,
            horse.location.x,
            horse.location.y,
            horse.location.z,
            points.last(),
            config.checkpointRadius,
        )
        if (!atDestination && distance > config.hardResetDistance) {
            correctTowardRoute(horse, projectionLocation, config.hardCorrectionStrength)
            audience.sendActionBar(rider, MessageKey.FARM_ROUTE_RETURNED)
            return
        }
        if (!atDestination && distance > config.corridorRadius) {
            correctTowardRoute(horse, projectionLocation, config.corridorCorrectionStrength)
            audience.sendActionBar(rider, MessageKey.FARM_ROUTE_CORRIDOR)
            return
        }
        val reachedByProjection = FarmRouteGeometry.reachedPoint(projection, points.size)
        val projected = maxOf(
            currentIndex,
            when {
                atDestination -> points.size
                reachedByProjection == points.size -> points.lastIndex
                else -> reachedByProjection
            },
        )
        // A fast cart may cross several sampled points between updates. Never let
        // it skip a planned roadside ambush or finish the route past one.
        val reached = session.pendingAmbushCheckpoints.firstOrNull()
            ?.let { checkpoint -> minOf(projected, checkpoint) }
            ?: projected
        if (reached <= currentIndex) return
        val result = FarmShiftEngine.advanceFoodDelivery(
            runtime.state,
            reached,
            rider.uniqueId,
            config.completionContribution,
            completion = completion(runtime),
            rules = runtime.rules,
            now = now,
        )
        val progressListeners = participants(runtime)
        if (result.accepted && result.state.phase != FarmPhase.INCIDENT) {
            scheduleFarmReturn(runtime, horse, session, points.first(), config.returnDelaySeconds)
        }
        transitions.apply(runtime, result, rider)
        if (result.accepted && settings().particles) {
            horse.world.spawnParticle(Particle.HAPPY_VILLAGER, horse.location.add(0.0, 1.0, 0.0), 8, 0.5, 0.4, 0.5, 0.0)
        }
        if (result.accepted && (reached == points.size || reached / ROUTE_SOUND_INTERVAL > currentIndex / ROUTE_SOUND_INTERVAL)) {
            progressListeners.forEach { player ->
                player.playSound(
                    player.location,
                    if (reached == points.size) Sound.ENTITY_PLAYER_LEVELUP else Sound.BLOCK_NOTE_BLOCK_CHIME,
                    if (reached == points.size) 0.8f else 0.45f,
                    if (reached == points.size) 1.15f else 1.45f,
                )
            }
        }
    }

    private fun scheduleFarmReturn(
        runtime: FarmRuntime,
        horse: Horse,
        session: FarmFoodDeliverySession,
        routeStart: FarmPointPosition,
        delaySeconds: Int,
    ) {
        val participants = sessionPlayers(session)
        vehiclePassengers.ejectAll(horse)
        (session.gunnerSeatId?.let(Bukkit::getEntity) as? Interaction)?.let(vehiclePassengers::ejectAll)
        val destination = safeSurface(location(routeStart)) ?: location(routeStart)
        participants.forEach { player ->
            player.fallDistance = 0f
            player.playSound(player.location, Sound.ENTITY_HORSE_STEP_WOOD, 0.75f, 0.9f)
        }
        tasks.runLater(delaySeconds * 20L) {
            participants.filter(Player::isOnline).forEach { player ->
                val target = destination.clone().apply {
                    yaw = player.location.yaw
                    pitch = player.location.pitch
                }
                if (player.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
                    player.fallDistance = 0f
                    player.playSound(target, Sound.ENTITY_ENDERMAN_TELEPORT, 0.55f, 1.25f)
                }
            }
            debug.event(
                "farm_food_route_players_returned",
                "zone" to runtime.settings.id,
                "sequence" to session.sequence,
                "players" to participants.size,
            )
        }
    }

    private fun updateMonsters(
        runtime: FarmRuntime,
        horse: Horse,
        session: FarmFoodDeliverySession,
        points: List<FarmPointPosition>,
    ) {
        ambush.update(
            runtime = runtime,
            horse = horse,
            session = session,
            points = points,
            location = ::location,
            safeSurface = ::safeSurface,
            mark = { monster -> mark(monster, runtime, ROLE_MONSTER) },
        )
    }

    private fun refreshAmbushPlan(
        runtime: FarmRuntime,
        session: FarmFoodDeliverySession,
        points: List<FarmPointPosition>,
    ) {
        val config = runtime.settings.routeDelivery
        val plan = FarmFoodDeliveryAmbushPlan(
            config.ambushDistance,
            config.ambushMaxCount,
            config.ambushAfterFarmDistance,
            config.ambushEndSafeDistance,
            runtime.state.sequence,
        )
        if (session.ambushPlan == plan) return
        val farmExitIndex = points.indexOfFirst { point -> !runtime.region.contains(location(point)) }
        val candidates = farmExitIndex.takeIf { it >= 0 }?.let { exitIndex ->
            FarmFoodDeliveryAmbushPlanner.checkpoints(
                points,
                plan.distance,
                plan.maximum,
                FarmFoodDeliveryAmbushPlanner.distanceAt(points, exitIndex) + plan.afterFarmDistance,
                plan.endSafeDistance,
                plan.placementSeed,
            )
        }.orEmpty()
        session.replaceAmbushCheckpoints(candidates, plan.maximum, runtime.state.incidentProgress)
        session.ambushPlan = plan
    }

    private fun spawnHorse(runtime: FarmRuntime, location: Location): Horse =
        runtime.region.world.spawn(location, Horse::class.java) { horse ->
            horse.isPersistent = false
            mobDespawns.setRemoveWhenFarAway(horse, false)
            horse.isTamed = true
            horse.owner = null
            horse.inventory.saddle = ItemStack(Material.SADDLE)
            configureHorse(runtime, horse)
            mark(horse, runtime, ROLE_HORSE)
        }

    private fun configureHorse(runtime: FarmRuntime, horse: Horse) {
        horse.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = runtime.settings.routeDelivery.horseSpeed
        horse.getAttribute(Attribute.JUMP_STRENGTH)?.baseValue = runtime.settings.routeDelivery.horseJumpStrength
    }

    private fun spawnGunnerSeat(runtime: FarmRuntime, at: Location): Interaction =
        runtime.region.world.spawn(at, Interaction::class.java) { seat ->
            configureGunnerSeat(runtime, seat)
            mark(seat, runtime, ROLE_GUNNER_SEAT)
        }

    private fun configureGunnerSeat(runtime: FarmRuntime, seat: Interaction) {
        seat.interactionWidth = runtime.settings.routeDelivery.gunnerInteractionWidth
        seat.interactionHeight = runtime.settings.routeDelivery.gunnerInteractionHeight
        seat.isResponsive = true
        seat.isPersistent = false
    }

    private fun ensurePortal(runtime: FarmRuntime, session: FarmFoodDeliverySession) {
        val config = runtime.settings.routeDelivery
        portals.ensure(
            runtime, points.resolve(runtime, FarmPointKind.FOOD_DELIVERY_PORTAL),
            FarmPortalStyle(config.portalWidth, config.portalHeight, config.portalLabelHeight,
                config.portalLabelScale, config.portalActivationSeconds, runtime.settings.displayViewRange),
            MessageKey.FARM_ROUTE_PORTAL_LABEL,
            object : FarmPortalDestination {
                override fun enter(player: Player): Boolean {
                    val horse = (session.horseId?.let(Bukkit::getEntity) as? Horse)?.takeIf { it.isValid && !it.isDead }
                    if (horse == null) {
                        ensure(runtime, System.currentTimeMillis())
                        return false
                    }
                    return joinDelivery(player, runtime, session, horse)
                }
            },
        )
    }

    private fun joinDelivery(
        player: Player,
        runtime: FarmRuntime,
        session: FarmFoodDeliverySession,
        horse: Horse,
    ): Boolean {
        val yaw = Math.toRadians(horse.location.yaw.toDouble())
        val side = runtime.settings.routeDelivery.portalArrivalSideOffset
        val beside = horse.location.clone().add(cos(yaw) * side, 0.0, sin(yaw) * side)
        val target = safeSurface(beside) ?: horse.location.clone().add(0.0, 0.25, 0.0)
        if (!player.teleport(target.apply { this.yaw = horse.location.yaw }, PlayerTeleportEvent.TeleportCause.PLUGIN)) return false
        if (!session.brokenDown && hasFreeSeat(session, horse)) {
            mountAvailableSeat(player, runtime, session, horse)
            if (session.riderId == player.uniqueId || session.gunnerId == player.uniqueId) return true
        }
        session.escortIds += player.uniqueId
        session.registerParticipant(player.uniqueId)
        if (!gunner.armEscort(player, runtime, session)) {
            // The portal still joined the player to the activity. Keeping the
            // escort identity preserves route HUD/time and lets them free an
            // inventory slot without being stranded outside the farm context.
            return true
        }
        audience.showScreenTitle(player, MessageKey.FARM_ROUTE_PORTAL_JOINED)
        player.playSound(player.location, Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.3f)
        debug.event(
            "farm_food_route_escort_joined", "zone" to runtime.settings.id,
            "sequence" to session.sequence, "player" to player.name,
        )
        return true
    }

    private fun hasFreeSeat(session: FarmFoodDeliverySession, horse: Horse): Boolean {
        if (horse.passengers.none { it is Player }) return true
        val seat = session.gunnerSeatId?.let(Bukkit::getEntity) as? Interaction ?: return false
        return seat.passengers.none { it is Player }
    }

    private fun spawnCart(runtime: FarmRuntime, at: Location): ItemDisplay =
        runtime.region.world.spawn(at, ItemDisplay::class.java) { display ->
            configureCart(runtime, display)
            mark(display, runtime, ROLE_CART)
        }

    private fun configureCart(runtime: FarmRuntime, display: ItemDisplay) {
        display.isPersistent = false
        display.isInvulnerable = true
        display.interpolationDuration = 2
        display.teleportDuration = 2
        display.viewRange = runtime.settings.contractCartVisual.viewRange
        display.isGlowing = true
        display.glowColorOverride = org.bukkit.Color.fromRGB(0x92, 0xbe, 0xd8)
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
    }

    private fun spawnLoad(
        runtime: FarmRuntime,
        at: Location,
        material: String,
        customModelData: Int,
    ): ItemDisplay = runtime.region.world.spawn(at, ItemDisplay::class.java) { display ->
        configureLoad(runtime, display, material, customModelData)
        mark(display, runtime, ROLE_LOAD)
    }

    private fun configureLoad(
        runtime: FarmRuntime,
        display: ItemDisplay,
        material: String,
        customModelData: Int,
    ) {
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
    }

    private fun loadLocation(cart: Location, slot: Int, yOffset: Double, spacing: Double): Location {
        val front = -spacing / 3.0
        val offsets = listOf(-spacing to front, spacing to front, -spacing to spacing, spacing to spacing)
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

    private fun removeSessionEntities(session: FarmFoodDeliverySession) {
        buildList {
            session.horseId?.let(::add)
            session.cartId?.let(::add)
            session.gunnerSeatId?.let(::add)
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

    private fun sessionPlayers(session: FarmFoodDeliverySession): List<Player> =
        session.participantIds().mapNotNull(Bukkit::getPlayer).filter(Player::isOnline)

    fun skipUnavailable(runtime: FarmRuntime, now: Long) = FarmShiftEngine.skipUnavailableFoodDelivery(
        runtime.state,
        completion(runtime),
        rules = runtime.rules,
        now = now,
    )

    private fun completion(runtime: FarmRuntime): FarmFoodDeliveryCompletion {
        val order = runtime.state.orderId?.let(runtime.orders::get)
        return if (order != null && runtime.state.completed(order) >= order.totalRequired) {
            FarmFoodDeliveryCompletion.SHIFT
        } else FarmFoodDeliveryCompletion.INCIDENT
    }

    private fun selectedRoute(runtime: FarmRuntime): NamedFarmDeliveryRoute? {
        val persistedName = runtime.state.specialIncident?.routeName
        if (persistedName != null) {
            return routes.route(runtime.settings.id, persistedName)?.let {
                lastRouteNames[runtime.settings.id] = persistedName
                NamedFarmDeliveryRoute(persistedName, it)
            }
        }
        val previous = lastRouteNames[runtime.settings.id]
        val selected = routes.select(runtime.settings.id, random, previous) ?: return null
        val name = selected.name
        lastRouteNames[runtime.settings.id] = name
        debug.event(
            "farm_food_route_selected", "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence, "route" to name,
            "available_routes" to routes.names(runtime.settings.id).size, "previous_route" to previous,
        )
        return selected
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

    // Recorded roads can pass under trees or bridges; require clearance, not open sky.
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
                    blockPassability.isPassable(feet) && blockPassability.isPassable(head) &&
                    !feet.isLiquid && !head.isLiquid &&
                    feet.getRelative(0, -1, 0).type.isSolid
                ) {
                    return candidate
                }
            }
        }
        return null
    }
    private companion object {
        const val TRAIL_INTERVAL_TICKS = 10L
        const val ROUTE_SOUND_INTERVAL = 8
        const val ROLE_HORSE = "horse"
        const val ROLE_CART = "cart"
        const val ROLE_GUNNER_SEAT = "gunner_seat"
        const val ROLE_LOAD = "load"
        const val ROLE_MONSTER = "monster"
        const val ROLE_PORTAL = "portal"
        const val ROLE_PORTAL_LABEL = "portal_label"
    }
    private fun nightOwner(zoneId: String): String = "food:$zoneId"
}
