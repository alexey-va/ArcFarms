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
import ru.ruscrafting.farms.domain.FarmRaidBlastPlanner
import ru.ruscrafting.farms.domain.FarmRaidBlastDamage
import ru.ruscrafting.farms.domain.FarmRaidSeatPolicy
import ru.ruscrafting.farms.domain.FarmRaidWeaponAim
import ru.ruscrafting.farms.domain.FarmSpecialIncidentEngine
import ru.ruscrafting.farms.domain.FarmSpecialIncidentState
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.paper.ArcFarmsDebug
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
import ru.ruscrafting.farms.paper.platform.FarmClientBlockPreview
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayRenderer
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayStyle
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
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
    private val beds: FarmIncidentBedProvider,
    private val points: FarmPointProvider,
    private val transitions: FarmTransitionSink,
    private val runtimes: () -> Collection<FarmRuntime>,
    private val entityRayTrace: FarmEntityRayTrace,
    private val mobDespawns: FarmMobDespawnPolicy,
    private val mobNavigation: FarmMobNavigation,
    private val riderVisibility: FarmRaidRiderVisibility,
    private val blockPreviews: FarmClientBlockPreview,
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
        val previewGenerations: MutableMap<FarmPlotPosition, Long> = hashMapOf(),
        var previewGeneration: Long = 0,
        var orbiting: Boolean = false,
        var launched: Boolean = false,
        var orbitAngle: Double = 0.0,
        var lastFlightTick: Long = 0,
        var portalId: UUID? = null,
        var portalLabelId: UUID? = null,
    )
    private data class PortalEntry(
        val zoneId: String,
        val sequence: Long,
        val token: UUID,
        var remainingSeconds: Int,
    )
    private val zoneKey = NamespacedKey(plugin, "farm_raid_zone")
    private val sequenceKey = NamespacedKey(plugin, "farm_raid_sequence")
    private val roleKey = NamespacedKey(plugin, "farm_raid_role")
    private val targetKey = NamespacedKey(plugin, "farm_raid_target")
    private val ownerKey = NamespacedKey(plugin, "farm_raid_owner")
    private val spawnedAtKey = NamespacedKey(plugin, "farm_raid_spawned_at")
    private val raids = mutableMapOf<String, RaidSession>()
    private val portalEntries = mutableMapOf<UUID, PortalEntry>()
    private val damageGate = FarmRaidDamageGate()
    private val workers = FarmRivalRaidWorkers(plugin, locale, beds, mobDespawns, mobNavigation, nightShift)
    private val loadout = FarmRivalRaidLoadout(locale, serviceItems)
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
        ensurePortal(runtime, session)
        if (!session.launched) return
        workers.ensure(runtime, raidGhast, session.fieldPlots)
    }
    fun updateAmbient(runtime: FarmRuntime) {
        ensure(runtime)
        val session = raids[runtime.settings.id] ?: return
        val ghast = session.ghastId?.let(Bukkit::getEntity) as? Ghast ?: return
        if (settings().particles && ghast.world.gameTime % PORTAL_RENDER_INTERVAL_TICKS == 0L) {
            renderPortal(runtime, session)
        }
        ghast.passengers.filterIsInstance<Player>().forEach { board(runtime, session, ghast, it) }
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
                audience.sendActionBar(player, MessageKey.FARM_ACTION_INVENTORY_FULL)
                finishParticipant(runtime.settings.id, session, player)
                return@forEach
            }
            if (!ensurePassenger(session, ghast, player)) finishParticipant(runtime.settings.id, session, player)
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
        if (event.hand != EquipmentSlot.HAND || event.action !in setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)) return false
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

    fun enterPortal(player: Player, destination: Location): Boolean {
        val match = raids.entries.firstNotNullOfOrNull { (zoneId, session) ->
            val runtime = runtimes().firstOrNull { it.settings.id == zoneId } ?: return@firstNotNullOfOrNull null
            val portal = session.portalId?.let(Bukkit::getEntity) as? Interaction ?: return@firstNotNullOfOrNull null
            if (active(runtime, session.sequence) && portal.isValid && contains(portal, destination)) {
                Triple(runtime, session, portal)
            } else null
        }
        if (match == null) {
            cancelPortalEntry(player)
            return false
        }
        val (runtime, session) = match
        if (!access.hasAccess(player, runtime.settings.permission)) {
            cancelPortalEntry(player)
            audience.sendChat(player, MessageKey.ZONE_LOCKED)
            return true
        }
        if (!FarmRaidSeatPolicy.canBoard(
                session.participantIds.size,
                runtime.settings.rivalRaid.maximumRiders,
                player.uniqueId in session.participantIds,
            )
        ) {
            cancelPortalEntry(player)
            audience.sendActionBar(player, MessageKey.FARM_RIVAL_RAID_FULL)
            return true
        }
        val existing = portalEntries[player.uniqueId]
        if (existing?.zoneId == runtime.settings.id && existing.sequence == session.sequence) return true
        cancelPortalEntry(player)
        val entry = PortalEntry(
            runtime.settings.id,
            session.sequence,
            UUID.randomUUID(),
            runtime.settings.rivalRaid.portalActivationSeconds,
        )
        portalEntries[player.uniqueId] = entry
        continuePortalEntry(player.uniqueId, entry.token)
        return true
    }

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
        if (player == null || session == null || player.uniqueId !in session.participantIds ||
            !hasGun(player, zoneId, session) || !damageGate.consume(player.uniqueId, event.entity.uniqueId)
        ) event.isCancelled = true
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
        val ghast = event.dismounted
        if (role(ghast) != ROLE_GHAST) return false
        val zoneId = ghast.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) ?: return true
        val session = raids[zoneId] ?: return true
        if (session.ghastId != ghast.uniqueId || player.uniqueId !in session.participantIds) return true
        tasks.runLater(1L) {
            val current = raids[zoneId]?.takeIf { it === session } ?: return@runLater
            if (player.uniqueId in current.participantIds && player.vehicle?.uniqueId != ghast.uniqueId) {
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
        portalEntries.remove(player.uniqueId)
        raids.forEach { (zoneId, session) ->
            if (player.uniqueId in session.participantIds) {
                returnParticipant(session, player)
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
                Bukkit.getPlayer(playerId)?.let { returnParticipant(session, it) }
            }
            session.participantIds.remove(playerId)
            session.gunShotAt.remove(playerId)
            session.grenadeShotAt.remove(playerId)
        }
    }

    fun clear(zoneId: String, reason: String) {
        val session = raids.remove(zoneId)
        portalEntries.entries.removeIf { (playerId, entry) ->
            if (entry.zoneId != zoneId) return@removeIf false
            Bukkit.getPlayer(playerId)?.let(audience::clearScreenTitle)
            true
        }
        if (session != null) {
            session.participantIds.forEach { playerId ->
                val player = Bukkit.getPlayer(playerId) ?: return@forEach
                restorePreview(player, session.previewGenerations.keys)
                WEAPON_IDS.forEach { itemId ->
                    while (serviceItems.consume(player, identity(zoneId, session, itemId))) Unit
                }
                returnParticipant(session, player)
            }
        }
        nightShift.clearAmbientTime(atmosphereOwner(zoneId))
        workers.clear(zoneId)
        session?.projectileIds.orEmpty().forEach { Bukkit.getEntity(it)?.remove() }
        session?.debrisIds.orEmpty().forEach { Bukkit.getEntity(it)?.remove() }
        session?.portalId?.let(Bukkit::getEntity)?.remove()
        session?.portalLabelId?.let(Bukkit::getEntity)?.remove()
        session?.ghastId?.let(Bukkit::getEntity)?.remove()
        debug.event("farm_rival_raid_cleared", "zone" to zoneId, "reason" to reason)
    }

    fun cleanup(reason: String) {
        raids.keys.toSet().forEach { clear(it, reason) }
        Bukkit.getWorlds().asSequence().flatMap { it.entities.asSequence() }.filter(::owns).forEach(Entity::remove)
    }

    private fun ensurePortal(runtime: FarmRuntime, session: RaidSession) {
        val point = points.resolve(runtime, FarmPointKind.FOOD_DELIVERY_PORTAL)
        val world = Bukkit.getWorld(point.world) ?: return
        val config = runtime.settings.rivalRaid
        val at = Location(world, point.x, point.y, point.z, point.yaw, point.pitch)
        if (!world.isChunkLoaded(at.blockX shr 4, at.blockZ shr 4)) return
        val portal = (session.portalId?.let(Bukkit::getEntity) as? Interaction)?.takeIf(Entity::isValid)
            ?: world.spawn(at, Interaction::class.java).also { session.portalId = it.uniqueId }
        portal.teleport(at)
        portal.interactionWidth = config.portalWidth
        portal.interactionHeight = config.portalHeight
        portal.isResponsive = true
        portal.isPersistent = false
        mark(portal, runtime, ROLE_PORTAL, 0)

        val labelAt = at.clone().add(0.0, config.portalLabelHeight, 0.0).apply {
            yaw = 0f
            pitch = 0f
        }
        val label = (session.portalLabelId?.let(Bukkit::getEntity) as? TextDisplay)?.takeIf(Entity::isValid)
            ?: world.spawn(labelAt, TextDisplay::class.java).also { session.portalLabelId = it.uniqueId }
        label.teleport(labelAt)
        textDisplays.render(
            label,
            locale.render(
                MessageKey.FARM_RIVAL_RAID_PORTAL_LABEL,
                Bukkit.getConsoleSender(),
                mapOf("seconds" to locale.text(config.portalActivationSeconds)),
            ),
            FarmTextDisplayStyle(viewRange = runtime.settings.displayViewRange),
        )
        label.transformation = Transformation(
            Vector3f(),
            label.transformation.leftRotation,
            Vector3f(config.portalLabelScale, config.portalLabelScale, config.portalLabelScale),
            label.transformation.rightRotation,
        )
        mark(label, runtime, ROLE_PORTAL_LABEL, 0)
    }

    private fun renderPortal(runtime: FarmRuntime, session: RaidSession) {
        val portal = session.portalId?.let(Bukkit::getEntity) as? Interaction ?: return
        val viewers = audience.players(runtime.region).filter { it.world === portal.world && !access.isAdminEditing(it) }
        val center = portal.location.clone().add(0.0, 0.12, 0.0)
        repeat(PORTAL_RING_PARTICLES) { index ->
            val angle = Math.PI * 2.0 * index / PORTAL_RING_PARTICLES
            val point = center.clone().add(cos(angle) * PORTAL_RING_RADIUS, 0.0, sin(angle) * PORTAL_RING_RADIUS)
            viewers.forEach { viewer ->
                viewer.spawnParticle(
                    Particle.DUST,
                    point,
                    1,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    Particle.DustOptions(org.bukkit.Color.fromRGB(199, 120, 255), 1.15f),
                )
            }
        }
        repeat(PORTAL_COLUMN_LAYERS) { layer ->
            viewers.forEach { viewer ->
                viewer.spawnParticle(
                    Particle.REVERSE_PORTAL,
                    center.clone().add(0.0, 0.45 + layer * 0.42, 0.0),
                    2,
                    0.32,
                    0.12,
                    0.32,
                    0.01,
                )
            }
        }
    }

    private fun continuePortalEntry(playerId: UUID, token: UUID) {
        val entry = portalEntries[playerId]?.takeIf { it.token == token } ?: return
        val player = Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline) ?: run {
            portalEntries.remove(playerId, entry)
            return
        }
        val runtime = runtimes().firstOrNull { it.settings.id == entry.zoneId }
        val session = raids[entry.zoneId]?.takeIf { it.sequence == entry.sequence }
        val portal = session?.portalId?.let(Bukkit::getEntity) as? Interaction
        val ghast = session?.ghastId?.let(Bukkit::getEntity) as? Ghast
        if (runtime == null || session == null || portal?.isValid != true || ghast?.isValid != true ||
            !active(runtime, entry.sequence) || !contains(portal, player.location) ||
            !access.hasAccess(player, runtime.settings.permission)
        ) {
            cancelPortalEntry(player, token)
            return
        }
        if (!FarmRaidSeatPolicy.canBoard(
                session.participantIds.size,
                runtime.settings.rivalRaid.maximumRiders,
                playerId in session.participantIds,
            )
        ) {
            cancelPortalEntry(player, token)
            audience.sendActionBar(player, MessageKey.FARM_RIVAL_RAID_FULL)
            return
        }
        if (entry.remainingSeconds <= 0) {
            portalEntries.remove(playerId, entry)
            audience.clearScreenTitle(player)
            board(runtime, session, ghast, player)
            return
        }
        audience.showScreenTitle(
            player,
            MessageKey.FARM_RIVAL_RAID_PORTAL_COUNTDOWN,
            mapOf("seconds" to locale.text(entry.remainingSeconds)),
            "raid_portal",
        )
        entry.remainingSeconds--
        if (!tasks.runLater(20L) { continuePortalEntry(playerId, token) }) {
            cancelPortalEntry(player, token)
        }
    }

    private fun cancelPortalEntry(player: Player, token: UUID? = null) {
        val entry = portalEntries[player.uniqueId] ?: return
        if (token != null && entry.token != token) return
        if (portalEntries.remove(player.uniqueId, entry)) audience.clearScreenTitle(player)
    }

    private fun contains(portal: Interaction, location: Location): Boolean =
        location.world === portal.world &&
            abs(location.x - portal.location.x) <= portal.interactionWidth / 2.0 &&
            location.y >= portal.location.y - 0.5 &&
            location.y <= portal.location.y + portal.interactionHeight &&
            abs(location.z - portal.location.z) <= portal.interactionWidth / 2.0

    private fun board(runtime: FarmRuntime, session: RaidSession, ghast: Ghast, player: Player) {
        if (!FarmRaidSeatPolicy.canBoard(
                session.participantIds.size,
                runtime.settings.rivalRaid.maximumRiders,
                player.uniqueId in session.participantIds,
            )
        ) return
        if (!issueWeapons(player, runtime)) {
            audience.sendActionBar(player, MessageKey.FARM_ACTION_INVENTORY_FULL)
            return
        }
        session.participantIds += player.uniqueId
        if (!ensurePassenger(session, ghast, player)) {
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
        val radiusSquared = config.grenadeRadius * config.grenadeRadius
        workers.ids(runtime.settings.id).forEach { workerId ->
            val worker = Bukkit.getEntity(workerId) as? Mob ?: return@forEach
            if (worker.world !== location.world || worker.location.distanceSquared(location) > radiusSquared) return@forEach
            worker.noDamageTicks = 0
            val damage = FarmRaidBlastDamage.lethal(config.grenadeDamage, worker.health, worker.absorptionAmount)
            damageGate.authorize(shooter.uniqueId, worker.uniqueId) { worker.damage(damage, shooter) }
        }
        showBlastPreview(runtime, session, location)
    }

    private fun showBlastPreview(runtime: FarmRuntime, session: RaidSession, location: Location) {
        val config = runtime.settings.rivalRaid
        val plots = FarmRaidBlastPlanner.select(
            beds.discover(runtime) + session.fieldPlots,
            FarmPointPosition(location.world.name, location.x, location.y, location.z),
            config.grenadeRadius,
            config.grenadePreviewBlocks,
        )
        if (plots.isEmpty()) return
        val generation = ++session.previewGeneration
        plots.forEach { session.previewGenerations[it] = generation }
        val scorched = MaterialRules.material(config.grenadePreviewSoilMaterial).createBlockData()
        val fire = Material.FIRE.createBlockData()
        val air = Material.AIR.createBlockData()
        val craterCount = ceil(plots.size * CRATER_SHARE).toInt().coerceIn(1, plots.size)
        val changes = linkedMapOf<Location, org.bukkit.block.data.BlockData>()
        plots.forEachIndexed { index, plot ->
            val soil = plot.block() ?: return@forEachIndexed
            val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
            changes[soil.location] = if (index < craterCount) air else scorched
            changes[crop.location] = if (index < craterCount) air else fire
            if (settings().particles) {
                soil.world.spawnParticle(
                    Particle.BLOCK_CRUMBLE,
                    crop.location.toCenterLocation(),
                    12,
                    0.45,
                    0.35,
                    0.45,
                    soil.blockData,
                )
                soil.world.spawnParticle(Particle.FLAME, crop.location.toCenterLocation(), 5, 0.3, 0.2, 0.3, 0.025)
            }
        }
        spawnBlastDebris(runtime, session, location, plots)
        session.participantIds.mapNotNull(Bukkit::getPlayer).filter(Player::isOnline).forEach { player ->
            blockPreviews.send(player, changes)
        }
        tasks.runLater(config.grenadePreviewTicks.toLong()) {
            val current = raids[runtime.settings.id]?.takeIf { it === session } ?: return@runLater
            val expired = plots.filter { current.previewGenerations[it] == generation }
            if (expired.isEmpty()) return@runLater
            current.participantIds.mapNotNull(Bukkit::getPlayer).filter(Player::isOnline).forEach { player ->
                restorePreview(player, expired)
            }
            expired.forEach(current.previewGenerations::remove)
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

    private fun restorePreview(player: Player, plots: Collection<FarmPlotPosition>) {
        val changes = linkedMapOf<Location, org.bukkit.block.data.BlockData>()
        plots.forEach { plot ->
            val soil = plot.block() ?: return@forEach
            val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
            changes[soil.location] = soil.blockData
            changes[crop.location] = crop.blockData
        }
        if (changes.isNotEmpty()) blockPreviews.send(player, changes)
    }

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

    private fun ensurePassenger(session: RaidSession, ghast: Ghast, player: Player): Boolean {
        if (player.vehicle !== ghast) {
            player.leaveVehicle()
            if (!ghast.addPassenger(player)) return false
        }
        if (session.hiddenRiderIds.add(player.uniqueId)) riderVisibility.setHidden(player, ghast, true)
        return true
    }

    private fun finishParticipant(zoneId: String, session: RaidSession, player: Player) {
        session.participantIds.remove(player.uniqueId)
        session.gunShotAt.remove(player.uniqueId)
        session.grenadeShotAt.remove(player.uniqueId)
        WEAPON_IDS.forEach { itemId ->
            while (serviceItems.consume(player, identity(zoneId, session, itemId))) Unit
        }
        restorePreview(player, session.previewGenerations.keys)
        returnParticipant(session, player)
    }

    private fun returnParticipant(session: RaidSession, player: Player) {
        if (session.hiddenRiderIds.remove(player.uniqueId)) {
            (session.ghastId?.let(Bukkit::getEntity) as? Ghast)?.let { riderVisibility.setHidden(player, it, false) }
        }
        player.leaveVehicle()
        session.returnPoint.location()?.let(player::teleport)
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

    private fun hasGun(player: Player, zoneId: String, session: RaidSession): Boolean =
        serviceItems.identity(player.inventory.itemInMainHand) == identity(zoneId, session, RAID_GUN_ID) ||
            serviceItems.identity(player.inventory.itemInMainHand) == identity(zoneId, session, RAID_GRENADE_ID)

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
        const val PORTAL_RENDER_INTERVAL_TICKS = 10L
        const val PORTAL_RING_PARTICLES = 18
        const val PORTAL_COLUMN_LAYERS = 5
        const val PORTAL_RING_RADIUS = 1.15
        const val CRATER_SHARE = 0.8
        val WEAPON_IDS = setOf(RAID_GUN_ID, RAID_GRENADE_ID)
    }
}
