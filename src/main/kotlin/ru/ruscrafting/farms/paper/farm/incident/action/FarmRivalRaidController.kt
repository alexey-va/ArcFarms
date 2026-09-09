package ru.ruscrafting.farms.paper.farm.incident.action

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Ghast
import org.bukkit.entity.Interaction
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Snowball
import org.bukkit.entity.TextDisplay
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Vector3f
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmMotionVector
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmRaidDamageGate
import ru.ruscrafting.farms.domain.FarmRaidFlight
import ru.ruscrafting.farms.domain.FarmRaidBlastDamage
import ru.ruscrafting.farms.domain.FarmRaidSeatPolicy
import ru.ruscrafting.farms.domain.FarmRaidWeaponAim
import ru.ruscrafting.farms.domain.FarmSpecialIncidentEngine
import ru.ruscrafting.farms.domain.FarmSpecialIncidentState
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmNightShiftController
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.block
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.FarmIncidentBedProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.platform.FarmEntityRayTrace
import ru.ruscrafting.farms.paper.platform.FarmMobDespawnPolicy
import ru.ruscrafting.farms.paper.platform.FarmMobNavigation
import ru.ruscrafting.farms.paper.platform.FarmRaidRiderVisibility
import ru.ruscrafting.farms.paper.platform.FarmRivalRaidSeatMovement
import ru.ruscrafting.farms.paper.platform.PaperFarmRivalRaidSeatMovement
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayRenderer
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayStyle
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import ru.ruscrafting.farms.paper.farm.presentation.FarmActivityPortal
import ru.ruscrafting.farms.paper.farm.presentation.FarmPortalStyle
import ru.ruscrafting.farms.paper.farm.presentation.FarmPortalDestination
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import java.util.logging.Level
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/** Complete owner of the rival-farm raid lifecycle. */
internal class FarmRivalRaidController(
    plugin: Plugin,
    private val settings: () -> ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val state: WorksiteStatePort,
    private val tasks: WorksiteTaskPort,
    private val serviceItems: WorksiteServiceItems,
    private val ledger: FarmBlockLedger,
    private val beds: FarmIncidentBedProvider,
    private val points: FarmPointProvider,
    private val transitions: FarmTransitionSink,
    private val runtimes: () -> Collection<FarmRuntime>,
    private val entityRayTrace: FarmEntityRayTrace,
    private val mobDespawns: FarmMobDespawnPolicy,
    private val mobNavigation: FarmMobNavigation,
    private val riderVisibility: FarmRaidRiderVisibility,
    private val seatMovement: FarmRivalRaidSeatMovement = PaperFarmRivalRaidSeatMovement,
    private val textDisplays: FarmTextDisplayRenderer,
    private val nightShift: FarmNightShiftController,
) {
    private data class RaidSession(
        val sequence: Long,
        val objectiveNonce: Long,
        val returnPoint: FarmPointPosition,
        val fieldPlots: List<FarmPlotPosition>,
        var ghastId: UUID? = null,
        val hiddenRiderIds: MutableSet<UUID> = linkedSetOf(),
        val projectileIds: MutableSet<UUID> = linkedSetOf(),
        val debrisIds: MutableSet<UUID> = linkedSetOf(),
        val participantIds: MutableSet<UUID> = linkedSetOf(),
        val gunShotAt: MutableMap<UUID, Long> = hashMapOf(),
        val grenadeShotAt: MutableMap<UUID, Long> = hashMapOf(),
        val craterGenerations: MutableMap<FarmPlotPosition, Long> = hashMapOf(),
        var craterGeneration: Long = 0,
        var orbiting: Boolean = false,
        var launched: Boolean = false,
        var orbitAngle: Double = 0.0,
        var lastFlightTick: Long = 0,
    )
    private val zoneKey = NamespacedKey(plugin, "farm_raid_zone")
    private val sequenceKey = NamespacedKey(plugin, "farm_raid_sequence")
    private val roleKey = NamespacedKey(plugin, "farm_raid_role")
    private val targetKey = NamespacedKey(plugin, "farm_raid_target")
    private val ownerKey = NamespacedKey(plugin, "farm_raid_owner")
    private val spawnedAtKey = NamespacedKey(plugin, "farm_raid_spawned_at")
    private val raids = mutableMapOf<String, RaidSession>()
    private val portals = FarmActivityPortal(plugin, locale, access, audience, tasks, textDisplays, "farm_raid", rolePrefix = "raid_")
    private val damageGate = FarmRaidDamageGate()
    private val workers = FarmRivalRaidWorkers(plugin, locale, beds, mobDespawns, mobNavigation, nightShift)
    private val loadout = FarmRivalRaidLoadout(locale, serviceItems)
    private val seats = FarmRivalRaidSeats(plugin, seatMovement)
    fun plan(runtime: FarmRuntime): FarmActionIncidentPlanAttempt {
        val rival = points.configured(runtime, FarmPointKind.RIVAL_FARM)
            ?: return FarmActionIncidentPlanAttempt(null, 0, "rival_point_missing")
        val departure = points.resolve(runtime, FarmPointKind.RECEIVING)
        if (rival.world != departure.world) return FarmActionIncidentPlanAttempt(null, 0, "wrong_world")
        val dx = rival.x - departure.x
        val dz = rival.z - departure.z
        if (sqrt(dx * dx + dz * dz) > runtime.settings.rivalRaid.maximumDistance) {
            return FarmActionIncidentPlanAttempt(null, 0, "distance_exceeds_maximum")
        }
        val candidates = workers.fieldPlots(runtime, rival)
        if (candidates.size < runtime.settings.rivalRaid.workerCount) {
            return FarmActionIncidentPlanAttempt(null, candidates.size, "insufficient_outdoor_beds")
        }
        return FarmActionIncidentPlanAttempt(
            FarmSpecialIncidentState(
                points = listOf(departure.copy(pitch = 0f), rival.copy(pitch = 0f)),
                plots = candidates,
            ),
            candidates.size,
        )
    }

    fun start(runtime: FarmRuntime, plan: FarmSpecialIncidentState) {
        val rival = plan.points.getOrNull(1) ?: return
        raids[runtime.settings.id] = RaidSession(
            runtime.state.sequence,
            runtime.state.placementSequence,
            plan.points.first(),
            plan.plots.ifEmpty { workers.fieldPlots(runtime, rival) },
        )
        workers.start(runtime, raids.getValue(runtime.settings.id).fieldPlots)
    }

    fun ensure(runtime: FarmRuntime) {
        val special = runtime.state.specialIncident ?: return
        val session = raids.getOrPut(runtime.settings.id) {
            RaidSession(
                runtime.state.sequence,
                runtime.state.placementSequence,
                special.points.first(),
                special.plots.ifEmpty {
                    special.points.getOrNull(1)?.let { workers.fieldPlots(runtime, it) }.orEmpty()
                },
            )
        }
        if (session.sequence != runtime.state.sequence) {
            clear(runtime.settings.id, "sequence_changed")
            start(runtime, special)
            return
        }
        if (session.fieldPlots.size < runtime.settings.rivalRaid.workerCount) {
            state.log(
                java.util.logging.Level.WARNING,
                "Rival raid has insufficient outdoor beds: zone=${runtime.settings.id} sequence=${session.sequence} " +
                    "candidates=${session.fieldPlots.size} required=${runtime.settings.rivalRaid.workerCount}",
            )
            return
        }
        ensurePortal(runtime, session)
        val departure = special.points.first().location() ?: return
        var ghast = session.ghastId?.let(Bukkit::getEntity) as? Ghast
        if (ghast?.isValid != true && departure.world.isChunkLoaded(departure.blockX shr 4, departure.blockZ shr 4)) {
            ghast = departure.world.spawn(departure.clone().add(0.0, 2.0, 0.0), Ghast::class.java) { entity ->
                entity.isPersistent = false
                mobDespawns.setRemoveWhenFarAway(entity, false)
                entity.isAware = false
                entity.isInvulnerable = true
                entity.setGravity(false)
                entity.isGlowing = true
                mark(entity, runtime, ROLE_GHAST, 0)
            }
            session.ghastId = ghast.uniqueId
        }
        val raidGhast = ghast ?: return
        if (!session.launched) return
        workers.ensure(runtime, raidGhast, session.fieldPlots)
    }
    fun updateAmbient(runtime: FarmRuntime) {
        ensure(runtime)
        val session = raids[runtime.settings.id] ?: return
        portals.update(settings().particles)
        val ghast = session.ghastId?.let(Bukkit::getEntity) as? Ghast ?: return
        val participants = session.participantIds.mapNotNull(Bukkit::getPlayer).filter(Player::isOnline)
        val atmospherePlayers = audience.players(runtime.region).filterNot(access::isAdminEditing)
        nightShift.syncAmbientTime(
            atmosphereOwner(runtime.settings.id),
            atmospherePlayers,
            runtime.settings.rivalRaid.playerTime,
            runtime.settings.rivalRaid.timeTransitionSeconds,
        )
        participants.forEach { player ->
            if (!issueWeapons(player, runtime)) {
                notifyInventoryFull(player)
                return@forEach
            }
            if (!ensurePassenger(runtime, session, ghast, player)) {
                if (access.allowInteraction("farm-rival-raid-seat-failure:${runtime.settings.id}:${player.uniqueId}", 10_000L)) {
                    state.log(
                        Level.WARNING,
                        "Rival raid could not board participant: zone=${runtime.settings.id} " +
                            "sequence=${runtime.state.sequence} player=${player.name} ghast=${ghast.uniqueId}",
                    )
                }
                finishParticipant(runtime.settings.id, session, player)
            }
        }
        val now = ghast.world.gameTime
        if (now % runtime.settings.rivalRaid.workerPatrolIntervalTicks == 0L) workers.patrol(runtime)
    }
    fun updateMotion(runtime: FarmRuntime) {
        if (runtime.state.phase != FarmPhase.INCIDENT || runtime.state.incidentType != FarmIncidentType.RIVAL_RAID) return
        val special = runtime.state.specialIncident ?: return
        val session = raids[runtime.settings.id] ?: return
        expireProjectiles(runtime, session)
        if (!session.launched && session.participantIds.isEmpty()) return
        val ghast = session.ghastId?.let(Bukkit::getEntity) as? Ghast ?: return
        seats.sync(runtime, session.participantIds.toList(), ghast)
        session.launched = true
        val departure = special.points.first()
        val rival = special.points.getOrNull(1) ?: return
        val launch = departure.copy(y = departure.y + runtime.settings.rivalRaid.flightHeight)
        val initialOrbit = FarmRaidFlight.orbitPoint(
            rival,
            runtime.settings.rivalRaid.flightHeight,
            runtime.settings.rivalRaid.orbitRadius,
            session.orbitAngle,
        )
        val current = ghast.point()
        val target = when {
            current.horizontalDistanceSquared(departure) <= 0.25 && current.y < launch.y - 0.1 -> launch
            !session.orbiting -> initialOrbit
            else -> {
                val now = ghast.world.gameTime
                val elapsed = if (session.lastFlightTick <= 0L) 1L else (now - session.lastFlightTick).coerceIn(1L, 5L)
                session.lastFlightTick = now
                session.orbitAngle = FarmRaidFlight.advanceOrbit(
                    session.orbitAngle,
                    elapsed,
                    runtime.settings.rivalRaid.orbitPeriodSeconds,
                )
                FarmRaidFlight.orbitPursuitPoint(
                    rival,
                    runtime.settings.rivalRaid.flightHeight,
                    runtime.settings.rivalRaid.orbitRadius,
                    session.orbitAngle,
                    runtime.settings.rivalRaid.orbitLookAheadDegrees,
                )
            }
        }
        val velocity = FarmRaidFlight.steer(
            current,
            target,
            FarmMotionVector(ghast.velocity.x, ghast.velocity.y, ghast.velocity.z),
            runtime.settings.rivalRaid.flightSpeed,
            runtime.settings.rivalRaid.flightSteering,
        )
        ghast.velocity = Vector(velocity.x, velocity.y, velocity.z)
        if (!session.orbiting && current.distanceSquared(initialOrbit) <= 4.0) {
            session.orbiting = true
            session.lastFlightTick = ghast.world.gameTime
        }
    }
    fun interact(event: PlayerInteractEntityEvent): Boolean {
        val role = role(event.rightClicked) ?: return false
        if (role == ROLE_PORTAL) {
            event.isCancelled = true
            enterPortal(event.player, event.player.location)
            return true
        }
        if (role == ROLE_WORKER) {
            val identity = serviceItems.identity(event.player.inventory.itemInMainHand) ?: return false
            if (identity.activity != ActivityKind.FARM || identity.itemId !in WEAPON_IDS) return false
            event.isCancelled = true
            val runtime = runtimes().firstOrNull { it.settings.id == identity.zoneId } ?: return true
            val session = raids[identity.zoneId] ?: return true
            if (isActive(identity) && event.player.uniqueId in session.participantIds) fire(event.player, runtime, session, identity.itemId)
            return true
        }
        if (role != ROLE_GHAST) return false
        event.isCancelled = true
        val zoneId = event.rightClicked.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) ?: return true
        val runtime = runtimes().firstOrNull { it.settings.id == zoneId } ?: return true
        val session = raids[zoneId] ?: return true
        if (!active(runtime, session.sequence) || !access.hasAccess(event.player, runtime.settings.permission)) return true
        val ghast = event.rightClicked as? Ghast ?: return true
        if (!FarmRaidSeatPolicy.canBoard(
                session.participantIds.size,
                runtime.settings.rivalRaid.maximumRiders,
                event.player.uniqueId in session.participantIds,
            )
        ) {
            audience.sendActionBar(event.player, MessageKey.FARM_RIVAL_RAID_FULL)
            return true
        }
        board(runtime, session, ghast, event.player)
        return true
    }
    fun interact(event: PlayerInteractEvent): Boolean {
        if (event.hand != EquipmentSlot.HAND || event.action !in WEAPON_ACTIONS) return false
        val identity = serviceItems.identity(event.player.inventory.itemInMainHand) ?: return false
        if (identity.activity != ActivityKind.FARM || identity.itemId !in WEAPON_IDS) return false
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
        event.isCancelled = true
        val runtime = runtimes().firstOrNull { it.settings.id == identity.zoneId } ?: return true
        val session = raids[identity.zoneId] ?: return true
        if (isActive(identity) && event.player.uniqueId in session.participantIds) {
            fire(event.player, runtime, session, identity.itemId)
        }
        return true
    }

    fun enterPortal(player: Player, destination: Location): Boolean = portals.enter(player, destination)

    fun onProjectileHit(event: ProjectileHitEvent): Boolean {
        val projectile = event.entity
        if (role(projectile) != ROLE_GRENADE) return false
        val zoneId = projectile.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) ?: return true
        val session = raids[zoneId] ?: return true
        if (!session.projectileIds.remove(projectile.uniqueId)) return true
        val runtime = runtimes().firstOrNull { it.settings.id == zoneId }
        val ownerId = projectile.persistentDataContainer.get(ownerKey, PersistentDataType.STRING)?.let(UUID::fromString)
        val shooter = ownerId?.let(Bukkit::getPlayer)
        if (runtime != null && shooter != null && active(runtime, session.sequence)) explode(runtime, session, projectile.location, shooter)
        projectile.remove()
        return true
    }

    fun onDamage(event: EntityDamageEvent): Boolean {
        val attacker = (event as? EntityDamageByEntityEvent)?.damager
        if (attacker != null && role(attacker) == ROLE_WORKER) {
            event.isCancelled = true
            return true
        }
        val role = role(event.entity) ?: return false
        if (role == ROLE_GHAST || role == ROLE_GRENADE || role == ROLE_DEBRIS) {
            event.isCancelled = true
            return true
        }
        if (role != ROLE_WORKER) return false
        val damage = event as? EntityDamageByEntityEvent
        val player = damage?.damager as? Player
        val zoneId = event.entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING)
        val session = zoneId?.let(raids::get)
        if (player == null || zoneId == null || session == null || player.uniqueId !in session.participantIds) {
            event.isCancelled = true
            return true
        }
        if (damageGate.consume(player.uniqueId, event.entity.uniqueId)) return true
        event.isCancelled = true
        val heldIdentity = serviceItems.identity(player.inventory.itemInMainHand)
        if (heldIdentity != null && heldIdentity.itemId in WEAPON_IDS &&
            heldIdentity == identity(zoneId, session, heldIdentity.itemId)
        ) {
            val runtime = runtimes().firstOrNull { it.settings.id == zoneId }
            if (runtime != null && active(runtime, session.sequence)) fire(player, runtime, session, heldIdentity.itemId)
        }
        return true
    }

    fun onTarget(event: EntityTargetLivingEntityEvent): Boolean {
        if (role(event.entity) != ROLE_WORKER) return false
        event.isCancelled = true
        (event.entity as? Mob)?.target = null
        return true
    }

    fun onDismount(event: EntityDismountEvent): Boolean {
        val player = event.entity as? Player ?: return false
        val seat = event.dismounted
        if (!seats.isSeat(seat)) return false
        val zoneId = seats.zoneId(seat) ?: return true
        val session = raids[zoneId] ?: return true
        if (seats.seatId(zoneId, player.uniqueId) != seat.uniqueId || player.uniqueId !in session.participantIds) return true
        tasks.runLater(1L) {
            val current = raids[zoneId]?.takeIf { it === session } ?: return@runLater
            if (player.uniqueId in current.participantIds && player.vehicle?.uniqueId != seat.uniqueId) {
                finishParticipant(zoneId, current, player)
            }
        }
        return true
    }

    fun onDeath(event: EntityDeathEvent): Boolean {
        if (role(event.entity) != ROLE_WORKER) return false
        event.drops.clear()
        event.droppedExp = 0
        val zoneId = event.entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) ?: return true
        val session = raids[zoneId] ?: return true
        workers.remove(zoneId, event.entity.uniqueId)
        val player = event.entity.killer?.takeIf { it.uniqueId in session.participantIds } ?: return true
        val runtime = runtimes().firstOrNull { it.settings.id == zoneId } ?: return true
        val result = FarmSpecialIncidentEngine.advanceAction(runtime.state, FarmIncidentType.RIVAL_RAID, player.uniqueId)
        if (result.accepted) transitions.apply(runtime, result, player)
        return true
    }

    fun owns(entity: Entity): Boolean = role(entity) != null

    fun participantRuntime(player: Player): FarmRuntime? = raids.entries.firstNotNullOfOrNull { (zoneId, session) ->
        if (player.uniqueId !in session.participantIds) null else runtimes().firstOrNull { it.settings.id == zoneId }
    }

    fun participants(runtime: FarmRuntime): List<Player> = raids[runtime.settings.id]?.participantIds.orEmpty()
        .mapNotNull(Bukkit::getPlayer).filter(Player::isOnline)

    fun onQuit(player: Player) {
        portals.onQuit(player)
        raids.forEach { (zoneId, session) ->
            if (player.uniqueId in session.participantIds) {
                returnParticipant(zoneId, session, player)
                nightShift.clearAmbientPlayer(atmosphereOwner(zoneId), player)
            }
            session.participantIds.remove(player.uniqueId)
            session.gunShotAt.remove(player.uniqueId)
            session.grenadeShotAt.remove(player.uniqueId)
        }
    }

    fun isActive(identity: ServiceItemIdentity): Boolean {
        if (identity.activity != ActivityKind.FARM || identity.itemId !in WEAPON_IDS) return false
        val runtime = runtimes().firstOrNull { it.settings.id == identity.zoneId } ?: return false
        return active(runtime, identity.sequence) && runtime.state.placementSequence == identity.objectiveNonce
    }

    fun release(playerId: UUID, identity: ServiceItemIdentity, reason: WorksitePlayerReleaseReason) {
        if (identity.activity != ActivityKind.FARM || identity.itemId !in WEAPON_IDS) return
        raids[identity.zoneId]?.let { session ->
            val wasParticipant = playerId in session.participantIds
            if ((wasParticipant || reason == WorksitePlayerReleaseReason.JOIN_STALE) && reason.returnsParticipant()) {
                session.participantIds += playerId
                Bukkit.getPlayer(playerId)?.let { returnParticipant(identity.zoneId, session, it) }
            }
            session.participantIds.remove(playerId)
            session.gunShotAt.remove(playerId)
            session.grenadeShotAt.remove(playerId)
        }
    }

    fun clear(zoneId: String, reason: String) {
        val session = raids.remove(zoneId)
        portals.clear(zoneId)
        if (session != null) {
            restoreCrater(zoneId, session, session.craterGenerations.keys)
            session.participantIds.forEach { playerId ->
                val player = Bukkit.getPlayer(playerId) ?: return@forEach
                WEAPON_IDS.forEach { itemId ->
                    while (serviceItems.consume(player, identity(zoneId, session, itemId))) Unit
                }
                returnParticipant(zoneId, session, player)
            }
        }
        nightShift.clearAmbientTime(atmosphereOwner(zoneId))
        workers.clear(zoneId)
        session?.projectileIds.orEmpty().forEach { Bukkit.getEntity(it)?.remove() }
        session?.debrisIds.orEmpty().forEach { Bukkit.getEntity(it)?.remove() }
        seats.clear(zoneId)
        session?.ghastId?.let(Bukkit::getEntity)?.remove()
        debug.event("farm_rival_raid_cleared", "zone" to zoneId, "reason" to reason)
    }

    fun cleanup(reason: String) {
        raids.keys.toSet().forEach { clear(it, reason) }
        Bukkit.getWorlds().asSequence().flatMap { it.entities.asSequence() }.filter(::owns).forEach(Entity::remove)
    }

    private fun ensurePortal(runtime: FarmRuntime, session: RaidSession) {
        val config = runtime.settings.rivalRaid
        portals.ensure(
            runtime, points.resolve(runtime, FarmPointKind.FOOD_DELIVERY_PORTAL),
            FarmPortalStyle(config.portalWidth, config.portalHeight, config.portalLabelHeight,
                config.portalLabelScale, config.portalActivationSeconds, runtime.settings.displayViewRange),
            MessageKey.FARM_RIVAL_RAID_PORTAL_LABEL,
            object : FarmPortalDestination {
                override fun canEnter(player: Player): Boolean {
                    val allowed = FarmRaidSeatPolicy.canBoard(session.participantIds.size, config.maximumRiders,
                        player.uniqueId in session.participantIds)
                    if (!allowed) audience.sendActionBar(player, MessageKey.FARM_RIVAL_RAID_FULL)
                    return allowed
                }
                override fun enter(player: Player): Boolean {
                    val ghast = (session.ghastId?.let(Bukkit::getEntity) as? Ghast)?.takeIf { it.isValid && !it.isDead }
                        ?: return false
                    board(runtime, session, ghast, player)
                    return true
                }
            },
        )
    }

    private fun board(runtime: FarmRuntime, session: RaidSession, ghast: Ghast, player: Player) {
        if (!FarmRaidSeatPolicy.canBoard(
                session.participantIds.size,
                runtime.settings.rivalRaid.maximumRiders,
                player.uniqueId in session.participantIds,
            )
        ) return
        if (!issueWeapons(player, runtime)) {
            session.participantIds += player.uniqueId
            notifyInventoryFull(player)
            return
        }
        session.participantIds += player.uniqueId
        if (!ensurePassenger(runtime, session, ghast, player)) {
            finishParticipant(runtime.settings.id, session, player)
            return
        }
        nightShift.syncAmbientTime(
            atmosphereOwner(runtime.settings.id),
            session.participantIds.mapNotNull(Bukkit::getPlayer).filter(Player::isOnline),
            runtime.settings.rivalRaid.playerTime,
            runtime.settings.rivalRaid.timeTransitionSeconds,
        )
        audience.sendActionBar(player, MessageKey.FARM_RIVAL_RAID_MOUNTED)
        if (settings().sounds) player.playSound(player.location, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.8f, 1.15f)
    }

    private fun fire(player: Player, runtime: FarmRuntime, session: RaidSession, itemId: String) {
        if (itemId == RAID_GUN_ID) fireGun(player, runtime, session) else fireGrenade(player, runtime, session)
    }

    private fun fireGun(player: Player, runtime: FarmRuntime, session: RaidSession) {
        val config = runtime.settings.rivalRaid
        val now = player.world.gameTime
        val previous = session.gunShotAt[player.uniqueId] ?: Long.MIN_VALUE / 2
        if (now - previous < config.gunCooldownTicks) return
        session.gunShotAt[player.uniqueId] = now
        fireGunRound(player, runtime)
        repeat(config.gunBurstRounds - 1) { index ->
            val delay = (index + 1L) * config.gunBurstIntervalTicks
            tasks.runLater(delay) {
                val current = raids[runtime.settings.id]?.takeIf { it === session } ?: return@runLater
                val gunner = Bukkit.getPlayer(player.uniqueId)?.takeIf(Player::isOnline) ?: return@runLater
                if (gunner.uniqueId !in current.participantIds || !active(runtime, current.sequence)) return@runLater
                fireGunRound(gunner, runtime)
            }
        }
    }

    private fun fireGunRound(player: Player, runtime: FarmRuntime) {
        val config = runtime.settings.rivalRaid
        val eye = player.eyeLocation
        val random = ThreadLocalRandom.current()
        val spread = config.gunSpreadDegrees
        val aimed = FarmRaidWeaponAim.spread(
            FarmMotionVector(eye.direction.x, eye.direction.y, eye.direction.z),
            random.nextDouble(-spread, spread),
            random.nextDouble(-spread, spread),
        )
        val direction = Vector(aimed.x, aimed.y, aimed.z)
        val start = eye.clone().add(direction.clone().multiply(config.weaponMuzzleForward))
        val hit = entityRayTrace.trace(start, direction, config.gunRange, config.gunRaySize) { entity ->
            workers.contains(runtime.settings.id, entity.uniqueId) && entity.isValid && !entity.isDead
        }
        val end = hit?.hitPosition?.toLocation(player.world) ?: start.clone().add(direction.clone().multiply(config.gunRange))
        renderShot(start, end)
        if (settings().particles) {
            player.world.spawnParticle(Particle.FLAME, start, 3, 0.04, 0.04, 0.04, 0.015)
            player.world.spawnParticle(Particle.SMOKE, start, 4, 0.08, 0.06, 0.08, 0.025)
        }
        if (settings().sounds) {
            player.world.playSound(start, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.9f, 0.72f)
            player.world.playSound(start, Sound.BLOCK_PISTON_EXTEND, 0.35f, 1.7f)
        }
        val target = hit?.hitEntity as? Mob ?: return
        target.noDamageTicks = 0
        damageGate.authorize(player.uniqueId, target.uniqueId) { target.damage(config.gunDamage, player) }
        target.world.spawnParticle(Particle.CRIT, target.location.add(0.0, target.height * 0.55, 0.0), 10, 0.25, 0.25, 0.25, 0.08)
    }

    private fun fireGrenade(player: Player, runtime: FarmRuntime, session: RaidSession) {
        val config = runtime.settings.rivalRaid
        val now = player.world.gameTime
        val previous = session.grenadeShotAt[player.uniqueId] ?: Long.MIN_VALUE / 2
        if (now - previous < config.grenadeCooldownTicks) return
        session.grenadeShotAt[player.uniqueId] = now
        val direction = player.eyeLocation.direction.normalize()
        val muzzle = player.eyeLocation.clone().add(direction.clone().multiply(config.weaponMuzzleForward))
        val projectile = player.world.spawnEntity(muzzle, EntityType.SNOWBALL) as Snowball
        projectile.shooter = player
        projectile.velocity = direction.multiply(config.grenadeVelocity)
        projectile.item = ItemStack(Material.TNT)
        mark(projectile, runtime, ROLE_GRENADE, 0)
        projectile.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, player.uniqueId.toString())
        projectile.persistentDataContainer.set(spawnedAtKey, PersistentDataType.LONG, now)
        session.projectileIds += projectile.uniqueId
        if (settings().particles) {
            player.world.spawnParticle(Particle.LARGE_SMOKE, muzzle, 5, 0.12, 0.1, 0.12, 0.025)
            player.world.spawnParticle(Particle.FLAME, muzzle, 3, 0.08, 0.08, 0.08, 0.02)
        }
        if (settings().sounds) {
            player.world.playSound(muzzle, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 0.62f)
            player.world.playSound(muzzle, Sound.ENTITY_GENERIC_EXPLODE, 0.28f, 1.75f)
        }
    }

    private fun explode(runtime: FarmRuntime, session: RaidSession, location: Location, shooter: Player) {
        val config = runtime.settings.rivalRaid
        if (settings().particles) {
            location.world.spawnParticle(Particle.EXPLOSION_EMITTER, location, 1)
            location.world.spawnParticle(Particle.LARGE_SMOKE, location, 18, 1.4, 0.8, 1.4, 0.04)
        }
        if (settings().sounds) location.world.playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.9f)
        // Create the journalled crater before damage: a lethal hit can resolve the raid
        // synchronously, and incident cleanup must still restore the terrain.
        showBlastCrater(runtime, session, location)
        val radiusSquared = config.grenadeRadius * config.grenadeRadius
        workers.ids(runtime.settings.id).forEach { workerId ->
            val worker = Bukkit.getEntity(workerId) as? Mob ?: return@forEach
            if (worker.world !== location.world || worker.location.distanceSquared(location) > radiusSquared) return@forEach
            worker.noDamageTicks = 0
            val damage = FarmRaidBlastDamage.lethal(config.grenadeDamage, worker.health, worker.absorptionAmount)
            damageGate.authorize(shooter.uniqueId, worker.uniqueId) { worker.damage(damage, shooter) }
        }
    }

    private fun showBlastCrater(runtime: FarmRuntime, session: RaidSession, location: Location) {
        val config = runtime.settings.rivalRaid
        val plots = workers.blastPlots(runtime, location, config.grenadeRadius, config.grenadePreviewBlocks)
        spawnBlastDebris(runtime, session, location, plots)
        if (plots.isEmpty()) return
        val generation = ++session.craterGeneration
        plots.forEach { session.craterGenerations[it] = generation }
        val soils = plots.mapNotNull(FarmPlotPosition::block)
        ledger.beginTemporaryRemoval(
            soils,
            runtime.settings.id,
            craterOwner(runtime.settings.id, session),
            location.world.gameTime + config.grenadePreviewTicks,
        )
        val scorched = MaterialRules.material(config.grenadePreviewSoilMaterial)
        val craterCount = ceil(plots.size * CRATER_SHARE).toInt().coerceIn(1, plots.size)
        plots.forEachIndexed { index, plot ->
            val soil = plot.block() ?: return@forEachIndexed
            val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
            val debrisData = soil.blockData
            crop.setType(if (index < craterCount) Material.AIR else Material.FIRE, false)
            soil.setType(if (index < craterCount) Material.AIR else scorched, false)
            if (settings().particles) {
                soil.world.spawnParticle(
                    Particle.BLOCK_CRUMBLE,
                    crop.location.toCenterLocation(),
                    12,
                    0.45,
                    0.35,
                    0.45,
                    debrisData,
                )
                soil.world.spawnParticle(Particle.FLAME, crop.location.toCenterLocation(), 5, 0.3, 0.2, 0.3, 0.025)
            }
        }
        tasks.runLater(config.grenadePreviewTicks.toLong()) {
            val current = raids[runtime.settings.id]?.takeIf { it === session } ?: return@runLater
            val expired = plots.filter { current.craterGenerations[it] == generation }
            if (expired.isEmpty()) return@runLater
            restoreCrater(runtime.settings.id, current, expired)
            expired.forEach(current.craterGenerations::remove)
        }
    }

    private fun spawnBlastDebris(
        runtime: FarmRuntime,
        session: RaidSession,
        center: Location,
        plots: List<FarmPlotPosition>,
    ) {
        val spawned = FarmRivalRaidBlastDebris.spawn(
            center,
            plots,
            runtime.settings.rivalRaid.grenadeDebrisBlocks,
        )
        if (spawned.isEmpty()) return
        spawned.forEach { debris ->
            mark(debris, runtime, ROLE_DEBRIS, 0)
            session.debrisIds += debris.uniqueId
        }
        tasks.runLater(runtime.settings.rivalRaid.grenadeDebrisTicks.toLong()) {
            spawned.forEach { debris ->
                session.debrisIds.remove(debris.uniqueId)
                debris.remove()
            }
        }
    }

    private fun restoreCrater(zoneId: String, session: RaidSession, plots: Collection<FarmPlotPosition>) {
        ledger.restoreTemporaryRemovals(plots.mapNotNull(FarmPlotPosition::block), craterOwner(zoneId, session))
    }

    private fun craterOwner(zoneId: String, session: RaidSession): String = "raid:$zoneId:${session.sequence}"

    private fun expireProjectiles(runtime: FarmRuntime, session: RaidSession) {
        val now = runtime.region.world.gameTime
        session.projectileIds.removeIf { projectileId ->
            val projectile = Bukkit.getEntity(projectileId)
            val spawnedAt = projectile?.persistentDataContainer?.get(spawnedAtKey, PersistentDataType.LONG)
            val expired = projectile?.isValid != true || spawnedAt == null || now - spawnedAt >= runtime.settings.rivalRaid.grenadeLifetimeTicks
            if (expired) projectile?.remove()
            expired
        }
    }

    private fun renderShot(start: Location, end: Location) {
        if (!settings().particles) return
        val delta = end.toVector().subtract(start.toVector())
        val length = delta.length()
        if (length <= 0.01) return
        val step = delta.normalize().multiply(1.2)
        val cursor = start.clone()
        repeat((length / 1.2).toInt().coerceAtMost(96)) {
            cursor.world.spawnParticle(Particle.ELECTRIC_SPARK, cursor, 1, 0.0, 0.0, 0.0, 0.0)
            cursor.add(step)
        }
    }

    private fun issueWeapons(player: Player, runtime: FarmRuntime): Boolean {
        val required = listOf(RAID_GUN_ID, RAID_GRENADE_ID)
        return loadout.issue(player, runtime, required.associateWith { identity(runtime, it) })
    }

    private fun notifyInventoryFull(player: Player) {
        if (access.allowInteraction("farm-rival-raid-inventory-full:${player.uniqueId}", 3_000L)) {
            audience.sendChat(player, MessageKey.FARM_ACTION_INVENTORY_FULL)
        }
    }

    private fun ensurePassenger(runtime: FarmRuntime, session: RaidSession, ghast: Ghast, player: Player): Boolean {
        if (!seats.ensure(runtime, ghast, player)) return false
        session.hiddenRiderIds += player.uniqueId
        riderVisibility.setHidden(player, ghast, true)
        return true
    }

    private fun finishParticipant(zoneId: String, session: RaidSession, player: Player) {
        session.participantIds.remove(player.uniqueId)
        session.gunShotAt.remove(player.uniqueId)
        session.grenadeShotAt.remove(player.uniqueId)
        WEAPON_IDS.forEach { itemId ->
            while (serviceItems.consume(player, identity(zoneId, session, itemId))) Unit
        }
        returnParticipant(zoneId, session, player)
    }

    private fun returnParticipant(zoneId: String, session: RaidSession, player: Player) {
        if (session.hiddenRiderIds.remove(player.uniqueId)) {
            (session.ghastId?.let(Bukkit::getEntity) as? Ghast)?.let { riderVisibility.setHidden(player, it, false) }
        }
        player.leaveVehicle()
        seats.remove(zoneId, player)
        val destination = session.returnPoint.location()
        if (destination == null || !player.teleport(destination)) {
            state.log(Level.WARNING, "Rival raid return failed: zone=$zoneId sequence=${session.sequence} " +
                "player=${player.name} reason=${if (destination == null) "world_unavailable" else "teleport_rejected"} " +
                "target=${session.returnPoint}")
        }
    }

    private fun identity(runtime: FarmRuntime, itemId: String) = ServiceItemIdentity(
        ActivityKind.FARM,
        runtime.settings.id,
        runtime.state.sequence,
        runtime.state.placementSequence,
        ObjectiveTargetRole(ACTION_ROLE),
        itemId,
    )

    private fun identity(zoneId: String, session: RaidSession, itemId: String) = ServiceItemIdentity(
        ActivityKind.FARM,
        zoneId,
        session.sequence,
        session.objectiveNonce,
        ObjectiveTargetRole(ACTION_ROLE),
        itemId,
    )

    private fun mark(entity: Entity, runtime: FarmRuntime, role: String, target: Int) {
        entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, runtime.settings.id)
        entity.persistentDataContainer.set(sequenceKey, PersistentDataType.LONG, runtime.state.sequence)
        entity.persistentDataContainer.set(roleKey, PersistentDataType.STRING, role)
        entity.persistentDataContainer.set(targetKey, PersistentDataType.INTEGER, target)
    }

    private fun role(entity: Entity): String? = entity.persistentDataContainer.get(roleKey, PersistentDataType.STRING)

    private fun active(runtime: FarmRuntime, sequence: Long): Boolean =
        runtime.state.phase == FarmPhase.INCIDENT && runtime.state.incidentType == FarmIncidentType.RIVAL_RAID &&
            runtime.state.sequence == sequence && runtime.state.specialIncident != null

    private fun atmosphereOwner(zoneId: String) = "raid:$zoneId"

    private fun WorksitePlayerReleaseReason.returnsParticipant(): Boolean = this in setOf(
        WorksitePlayerReleaseReason.QUIT,
        WorksitePlayerReleaseReason.RELOAD,
        WorksitePlayerReleaseReason.SHUTDOWN,
        WorksitePlayerReleaseReason.JOIN_STALE,
        WorksitePlayerReleaseReason.OBJECTIVE_REPLACED,
    )

    private fun FarmPointPosition.location(): Location? = Bukkit.getWorld(world)?.let { Location(it, x, y, z, yaw, pitch) }
    private fun Ghast.point() = FarmPointPosition(world.name, location.x, location.y, location.z)

    private fun FarmPointPosition.horizontalDistanceSquared(other: FarmPointPosition): Double {
        val dx = x - other.x
        val dz = z - other.z
        return dx * dx + dz * dz
    }

    private fun FarmPointPosition.distanceSquared(other: FarmPointPosition): Double {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return dx * dx + dy * dy + dz * dz
    }
    private companion object {
        const val ROLE_GHAST = "raid_ghast"
        const val ROLE_WORKER = "raid_worker"
        const val ROLE_GRENADE = "raid_grenade"
        const val ROLE_DEBRIS = "raid_debris"
        const val ROLE_PORTAL = "raid_portal"
        const val ROLE_PORTAL_LABEL = "raid_portal_label"
        const val RAID_GUN_ID = "raid_gun"
        const val RAID_GRENADE_ID = "raid_grenade_launcher"
        const val ACTION_ROLE = "farm_action"
        const val CRATER_SHARE = 0.8
        val WEAPON_IDS = setOf(RAID_GUN_ID, RAID_GRENADE_ID)
        val WEAPON_ACTIONS = setOf(
            Action.LEFT_CLICK_AIR,
            Action.LEFT_CLICK_BLOCK,
            Action.RIGHT_CLICK_AIR,
            Action.RIGHT_CLICK_BLOCK,
        )
    }
}
