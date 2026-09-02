package ru.ruscrafting.farms.paper.farm.incident.action

import org.bukkit.Bukkit
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.ArmorStand
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
import ru.ruscrafting.farms.domain.FarmRaidInventorySlot
import ru.ruscrafting.farms.domain.FarmRaidLoadoutPlanner
import ru.ruscrafting.farms.domain.FarmRaidBlastPlanner
import ru.ruscrafting.farms.domain.FarmRaidSeatPolicy
import ru.ruscrafting.farms.domain.FarmRivalFieldPolicy
import ru.ruscrafting.farms.domain.FarmRivalPatrolPlanner
import ru.ruscrafting.farms.domain.FarmSpecialIncidentEngine
import ru.ruscrafting.farms.domain.FarmSpecialIncidentState
import ru.ruscrafting.farms.domain.MAX_FARM_SPECIAL_PLOTS
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
import ru.ruscrafting.farms.paper.platform.FarmRaidSeatMotion
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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class FarmActionIncidentPlanAttempt(
    val plan: FarmSpecialIncidentState?,
    val candidates: Int,
    val rejection: String? = null,
)

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
    private val seatMotion: FarmRaidSeatMotion,
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
        val riderSeatIds: MutableMap<UUID, UUID> = linkedMapOf(),
        val workerIds: MutableSet<UUID> = linkedSetOf(),
        val projectileIds: MutableSet<UUID> = linkedSetOf(),
        val participantIds: MutableSet<UUID> = linkedSetOf(),
        val gunShotAt: MutableMap<UUID, Long> = hashMapOf(),
        val grenadeShotAt: MutableMap<UUID, Long> = hashMapOf(),
        val previewGenerations: MutableMap<FarmPlotPosition, Long> = hashMapOf(),
        var previewGeneration: Long = 0,
        var workerSpawnSequence: Int = 0,
        var workerPatrolCursor: Int = 0,
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
        val candidates = fieldPlots(runtime, rival)
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
            plan.plots.ifEmpty { fieldPlots(runtime, rival) },
        )
    }

    fun ensure(runtime: FarmRuntime) {
        val special = runtime.state.specialIncident ?: return
        val session = raids.getOrPut(runtime.settings.id) {
            RaidSession(
                runtime.state.sequence,
                runtime.state.placementSequence,
                special.points.first(),
                special.plots.ifEmpty {
                    special.points.getOrNull(1)?.let { fieldPlots(runtime, it) }.orEmpty()
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
        session.workerIds.removeIf { Bukkit.getEntity(it)?.isValid != true }
        val raidGhast = ghast ?: return
        ensurePortal(runtime, session)
        if (!session.launched) return
        val spawnBatch = minOf(
            runtime.settings.rivalRaid.workerSpawnBatchSize,
            runtime.settings.rivalRaid.workerCount - session.workerIds.size,
        )
        repeat(spawnBatch) {
            val index = session.workerSpawnSequence++
            val plot = session.fieldPlots[Math.floorMod(index, session.fieldPlots.size)]
            val spawn = plot.spawnLocation() ?: return@repeat
            val mob = spawn.world.spawnEntity(spawn, EntityType.valueOf(runtime.settings.rivalRaid.workerEntity)) as? Mob ?: return@repeat
            mob.isPersistent = false
            mobDespawns.setRemoveWhenFarAway(mob, false)
            mob.target = null
            mob.isGlowing = true
            mob.getAttribute(Attribute.MAX_HEALTH)?.baseValue = runtime.settings.rivalRaid.workerHealth
            mob.health = runtime.settings.rivalRaid.workerHealth
            mob.customName(locale.render(MessageKey.FARM_RIVAL_RAID_WORKER))
            mob.equipment.setItemInMainHand(ItemStack(MaterialRules.material(runtime.settings.rivalRaid.workerHeldItem)), true)
            mob.equipment.itemInMainHandDropChance = 0.0f
            mark(mob, runtime, ROLE_WORKER, index)
            session.workerIds += mob.uniqueId
            moveWorker(runtime, session, mob, raidGhast.point(), index.toLong())
        }
        session.workerIds.forEachIndexed { index, workerId ->
            val worker = Bukkit.getEntity(workerId) as? Mob ?: return@forEachIndexed
            if (index % runtime.settings.rivalRaid.workerLightStride == 0) {
                nightShift.updateExternalLight(
                    workerLightOwner(runtime.settings.id, workerId),
                    worker,
                    runtime.settings.rivalRaid.workerLightLevel,
                )
            } else nightShift.releaseExternalLight(workerLightOwner(runtime.settings.id, workerId))
        }
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
        nightShift.syncAmbientTime(
            atmosphereOwner(runtime.settings.id),
            participants,
            runtime.settings.rivalRaid.playerTime,
            runtime.settings.rivalRaid.timeTransitionSeconds,
        )
        participants.forEach { player ->
            if (!issueWeapons(player, runtime)) {
                audience.sendActionBar(player, MessageKey.FARM_ACTION_INVENTORY_FULL)
                finishParticipant(runtime.settings.id, session, player)
                return@forEach
            }
            ensureSeat(runtime, session, ghast, player)
        }
        val now = ghast.world.gameTime
        if (now % runtime.settings.rivalRaid.workerPatrolIntervalTicks == 0L) patrol(runtime, session, ghast.point())
    }

    fun updateMotion(runtime: FarmRuntime) {
        if (runtime.state.phase != FarmPhase.INCIDENT || runtime.state.incidentType != FarmIncidentType.RIVAL_RAID) return
        val special = runtime.state.specialIncident ?: return
        val session = raids[runtime.settings.id] ?: return
        expireProjectiles(runtime, session)
        if (session.participantIds.isEmpty()) return
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
        positionSeats(runtime, session, ghast)
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
        if (role == ROLE_GHAST || role == ROLE_SEAT || role == ROLE_GRENADE) {
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
        val seat = event.dismounted
        if (role(seat) != ROLE_SEAT) return false
        val zoneId = seat.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) ?: return true
        val session = raids[zoneId] ?: return true
        if (session.riderSeatIds[player.uniqueId] != seat.uniqueId || player.uniqueId !in session.participantIds) return true
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
        session.workerIds.remove(event.entity.uniqueId)
        nightShift.releaseExternalLight(workerLightOwner(zoneId, event.entity.uniqueId))
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
                removeSeat(session, player.uniqueId)
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
                removeSeat(session, playerId)
                returnParticipant(session, player)
            }
        }
        nightShift.clearAmbientTime(atmosphereOwner(zoneId))
        nightShift.releaseExternalLights(workerLightPrefix(zoneId))
        session?.riderSeatIds?.values.orEmpty().forEach { Bukkit.getEntity(it)?.remove() }
        session?.workerIds.orEmpty().forEach { Bukkit.getEntity(it)?.remove() }
        session?.projectileIds.orEmpty().forEach { Bukkit.getEntity(it)?.remove() }
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

    private fun fieldPlots(runtime: FarmRuntime, rival: FarmPointPosition): List<FarmPlotPosition> {
        val world = Bukkit.getWorld(rival.world) ?: return emptyList()
        val radius = ceil(runtime.settings.rivalRaid.workerRadius).toInt()
        val radiusSquared = runtime.settings.rivalRaid.workerRadius * runtime.settings.rivalRaid.workerRadius
        val indexed = if (runtime.region.contains(Location(world, rival.x, rival.y, rival.z))) {
            beds.discover(runtime).asSequence()
                .filter { it.world == rival.world && it.horizontalDistanceSquared(rival) <= radiusSquared }
                .filter { eligiblePlot(runtime, world, it) }
                .toList()
        } else {
            emptyList()
        }
        val eligible = indexed.ifEmpty { buildList {
            for (x in rival.x.toInt() - radius..rival.x.toInt() + radius) {
                for (z in rival.z.toInt() - radius..rival.z.toInt() + radius) {
                    val dx = x + 0.5 - rival.x
                    val dz = z + 0.5 - rival.z
                    if (dx * dx + dz * dz > radiusSquared) continue
                    val chunkX = x shr 4
                    val chunkZ = z shr 4
                    val loaded = world.isChunkLoaded(chunkX, chunkZ) || world.loadChunk(chunkX, chunkZ, true)
                    if (!loaded) continue
                    val highestY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING)
                    val soil = world.getBlockAt(x, highestY, z)
                    val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
                    val head = crop.getRelative(org.bukkit.block.BlockFace.UP)
                    val outdoor = highestY == soil.y
                    val farmland = soil.type == Material.FARMLAND && (crop.type.isAir || crop.type.name in runtime.settings.crops)
                    val headroom = head.isPassable
                    if (FarmRivalFieldPolicy.isEligible(loaded, outdoor, farmland, headroom)) {
                        add(FarmPlotPosition(world.name, x, soil.y, z))
                    }
                }
            }
        } }
        return FarmRivalFieldPolicy.distribute(
            eligible,
            MAX_FARM_SPECIAL_PLOTS,
            runtime.state.placementSequence,
        )
    }

    private fun eligiblePlot(runtime: FarmRuntime, world: org.bukkit.World, plot: FarmPlotPosition): Boolean {
        val loaded = world.isChunkLoaded(plot.x shr 4, plot.z shr 4) || world.loadChunk(plot.x shr 4, plot.z shr 4, true)
        if (!loaded) return false
        val soil = world.getBlockAt(plot.x, plot.y, plot.z)
        val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
        val head = crop.getRelative(org.bukkit.block.BlockFace.UP)
        return FarmRivalFieldPolicy.isEligible(
            loaded = true,
            outdoor = world.getHighestBlockYAt(plot.x, plot.z, HeightMap.MOTION_BLOCKING) == soil.y,
            farmland = soil.type == Material.FARMLAND && (crop.type.isAir || crop.type.name in runtime.settings.crops),
            headroom = head.isPassable,
        )
    }

    private fun FarmPlotPosition.horizontalDistanceSquared(point: FarmPointPosition): Double {
        val dx = x + 0.5 - point.x
        val dz = z + 0.5 - point.z
        return dx * dx + dz * dz
    }

    private fun patrol(runtime: FarmRuntime, session: RaidSession, threat: FarmPointPosition) {
        val workers = session.workerIds.toList()
        if (workers.isEmpty()) return
        repeat(minOf(runtime.settings.rivalRaid.workerPatrolBatchSize, workers.size)) { offset ->
            val workerId = workers[Math.floorMod(session.workerPatrolCursor + offset, workers.size)]
            val worker = Bukkit.getEntity(workerId) as? Mob ?: return@repeat
            worker.target = null
            moveWorker(runtime, session, worker, threat, session.workerPatrolCursor.toLong() + offset)
        }
        session.workerPatrolCursor = Math.floorMod(
            session.workerPatrolCursor + runtime.settings.rivalRaid.workerPatrolBatchSize,
            workers.size,
        )
    }

    private fun moveWorker(
        runtime: FarmRuntime,
        session: RaidSession,
        worker: Mob,
        threat: FarmPointPosition,
        sequence: Long,
    ) {
        worker.target = null
        val target = FarmRivalPatrolPlanner.select(
            session.fieldPlots,
            FarmPointPosition(worker.world.name, worker.location.x, worker.location.y, worker.location.z),
            threat,
            runtime.state.placementSequence * 1_000_003L + sequence * 97L,
        ) ?: return
        val targetIndex = session.fieldPlots.indexOf(target)
        if (targetIndex >= 0) worker.persistentDataContainer.set(targetKey, PersistentDataType.INTEGER, targetIndex)
        target.spawnLocation()?.let { mobNavigation.moveTo(worker, it, runtime.settings.rivalRaid.workerPatrolSpeed) }
    }

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
        val seat = ensureSeat(runtime, session, ghast, player) ?: return
        if (player.vehicle !== seat) {
            player.leaveVehicle()
            seat.teleport(seatTarget(runtime, session, ghast, player.uniqueId))
            seat.addPassenger(player)
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
        val start = player.eyeLocation.clone().add(player.eyeLocation.direction.clone().multiply(0.55))
        val direction = player.eyeLocation.direction.normalize()
        val hit = entityRayTrace.trace(start, direction, config.gunRange, config.gunRaySize) { entity ->
            entity.uniqueId in session.workerIds && entity.isValid && !entity.isDead
        }
        val end = hit?.hitPosition?.toLocation(player.world) ?: start.clone().add(direction.clone().multiply(config.gunRange))
        renderShot(start, end)
        if (settings().sounds) player.world.playSound(start, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.8f, 0.72f)
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
        val projectile = player.world.spawnEntity(player.eyeLocation, EntityType.SNOWBALL) as Snowball
        projectile.shooter = player
        projectile.velocity = player.eyeLocation.direction.normalize().multiply(config.grenadeVelocity)
        projectile.item = ItemStack(Material.TNT)
        mark(projectile, runtime, ROLE_GRENADE, 0)
        projectile.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, player.uniqueId.toString())
        projectile.persistentDataContainer.set(spawnedAtKey, PersistentDataType.LONG, now)
        session.projectileIds += projectile.uniqueId
        if (settings().sounds) player.world.playSound(player.eyeLocation, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.9f, 0.65f)
    }

    private fun explode(runtime: FarmRuntime, session: RaidSession, location: Location, shooter: Player) {
        val config = runtime.settings.rivalRaid
        if (settings().particles) {
            location.world.spawnParticle(Particle.EXPLOSION_EMITTER, location, 1)
            location.world.spawnParticle(Particle.LARGE_SMOKE, location, 18, 1.4, 0.8, 1.4, 0.04)
        }
        if (settings().sounds) location.world.playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.9f)
        val radiusSquared = config.grenadeRadius * config.grenadeRadius
        session.workerIds.forEach { workerId ->
            val worker = Bukkit.getEntity(workerId) as? Mob ?: return@forEach
            if (worker.world !== location.world || worker.location.distanceSquared(location) > radiusSquared) return@forEach
            worker.noDamageTicks = 0
            damageGate.authorize(shooter.uniqueId, worker.uniqueId) { worker.damage(config.grenadeDamage, shooter) }
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
        val expected = required.associateWith { identity(runtime, it) }
        val originalStorage = player.inventory.storageContents.map { it?.clone() }.toTypedArray()
        val originalOffhand = player.inventory.itemInOffHand.clone()
        val inventoryItems = originalStorage.toMutableList().apply { add(originalOffhand.takeUnless { it.type.isAir }) }
        val slots = inventoryItems.map { item ->
            val exactId = serviceItems.identity(item)?.let { itemIdentity ->
                required.firstOrNull { expected[it] == itemIdentity }
            }
            FarmRaidInventorySlot(item?.type?.isAir == false, exactId)
        }
        val plan = FarmRaidLoadoutPlanner.plan(slots, required) ?: return false
        fun get(slot: Int): ItemStack? = if (slot == OFFHAND_SLOT) player.inventory.itemInOffHand.takeUnless { it.type.isAir }
        else player.inventory.getItem(slot)
        fun set(slot: Int, item: ItemStack?) {
            if (slot == OFFHAND_SLOT) player.inventory.setItemInOffHand(item)
            else player.inventory.setItem(slot, item)
        }
        plan.swaps.forEach { swap ->
            val first = get(swap.first)
            val second = get(swap.second)
            set(swap.first, second)
            set(swap.second, first)
        }
        plan.moves.forEach { move ->
            set(move.to, get(move.from))
            set(move.from, null)
        }
        val issued = plan.issues.all { issue -> issueToolAt(player, runtime, issue.itemId, issue.slot) }
        if (!issued) {
            player.inventory.storageContents = originalStorage
            player.inventory.setItemInOffHand(originalOffhand)
        }
        return issued
    }

    private fun issueToolAt(player: Player, runtime: FarmRuntime, itemId: String, slot: Int): Boolean {
        val key = if (itemId == RAID_GRENADE_ID) MessageKey.FARM_RIVAL_RAID_GRENADE else MessageKey.FARM_RIVAL_RAID_GUN
        val config = runtime.settings.rivalRaid
        val grenade = itemId == RAID_GRENADE_ID
        val material = MaterialRules.material(if (grenade) config.grenadeMaterial else config.gunMaterial)
        val customModelData = if (grenade) config.grenadeCustomModelData else config.gunCustomModelData
        val itemModelName = if (grenade) config.grenadeItemModel else config.gunItemModel
        val itemModel = itemModelName?.let { requireNotNull(NamespacedKey.fromString(it)) }
        return serviceItems.issueAtSlot(
            player,
            slot,
            identity(runtime, itemId),
            material,
            locale.render(key, player),
            customModelData,
            itemModel,
        ) != null
    }

    private fun ensureSeat(runtime: FarmRuntime, session: RaidSession, ghast: Ghast, player: Player): ArmorStand? {
        val current = session.riderSeatIds[player.uniqueId]?.let(Bukkit::getEntity) as? ArmorStand
        if (current?.isValid == true && current.world === ghast.world) return current
        current?.remove()
        val seat = ghast.world.spawn(ghast.location, ArmorStand::class.java) { stand ->
            stand.isVisible = false
            stand.isMarker = true
            stand.isSmall = true
            stand.setGravity(false)
            stand.isInvulnerable = true
            stand.isPersistent = false
            mark(stand, runtime, ROLE_SEAT, session.riderSeatIds.size)
        }
        session.riderSeatIds[player.uniqueId] = seat.uniqueId
        return seat
    }

    private fun positionSeats(runtime: FarmRuntime, session: RaidSession, ghast: Ghast) {
        val live = session.participantIds.sortedBy(UUID::toString).take(runtime.settings.rivalRaid.maximumRiders)
        live.forEachIndexed { index, playerId ->
            val player = Bukkit.getPlayer(playerId)
            val seat = player?.let { ensureSeat(runtime, session, ghast, it) } ?: return@forEachIndexed
            val target = seatTarget(runtime, session, ghast, playerId)
            seatMotion.move(seat, target)
        }
    }

    private fun seatTarget(runtime: FarmRuntime, session: RaidSession, ghast: Ghast, playerId: UUID): Location {
        val live = session.participantIds.sortedBy(UUID::toString).take(runtime.settings.rivalRaid.maximumRiders)
        val index = live.indexOf(playerId).coerceAtLeast(0)
        val offset = FarmRaidSeatPolicy.deck(
            live.size.coerceAtLeast(1),
            runtime.settings.rivalRaid.seatSpacing,
            runtime.settings.rivalRaid.seatYOffset,
        )[index.coerceAtMost(live.lastIndex.coerceAtLeast(0))]
        return ghast.location.clone().add(offset.x, offset.y, offset.z)
    }

    private fun removeSeat(session: RaidSession, playerId: UUID) {
        session.riderSeatIds.remove(playerId)?.let(Bukkit::getEntity)?.remove()
    }

    private fun finishParticipant(zoneId: String, session: RaidSession, player: Player) {
        session.participantIds.remove(player.uniqueId)
        session.gunShotAt.remove(player.uniqueId)
        session.grenadeShotAt.remove(player.uniqueId)
        WEAPON_IDS.forEach { itemId ->
            while (serviceItems.consume(player, identity(zoneId, session, itemId))) Unit
        }
        restorePreview(player, session.previewGenerations.keys)
        removeSeat(session, player.uniqueId)
        nightShift.clearAmbientPlayer(atmosphereOwner(zoneId), player)
        returnParticipant(session, player)
    }

    private fun returnParticipant(session: RaidSession, player: Player) {
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
    private fun workerLightPrefix(zoneId: String) = "raid:$zoneId:worker:"
    private fun workerLightOwner(zoneId: String, workerId: UUID) = workerLightPrefix(zoneId) + workerId

    private fun WorksitePlayerReleaseReason.returnsParticipant(): Boolean = this in setOf(
        WorksitePlayerReleaseReason.QUIT,
        WorksitePlayerReleaseReason.RELOAD,
        WorksitePlayerReleaseReason.SHUTDOWN,
        WorksitePlayerReleaseReason.JOIN_STALE,
        WorksitePlayerReleaseReason.OBJECTIVE_REPLACED,
    )

    private fun FarmPlotPosition.spawnLocation(): Location? = block()?.location?.toCenterLocation()?.add(0.0, 1.0, 0.0)
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
        const val ROLE_SEAT = "raid_seat"
        const val ROLE_WORKER = "raid_worker"
        const val ROLE_GRENADE = "raid_grenade"
        const val ROLE_PORTAL = "raid_portal"
        const val ROLE_PORTAL_LABEL = "raid_portal_label"
        const val RAID_GUN_ID = "raid_gun"
        const val RAID_GRENADE_ID = "raid_grenade_launcher"
        const val ACTION_ROLE = "farm_action"
        const val OFFHAND_SLOT = 36
        const val PORTAL_RENDER_INTERVAL_TICKS = 10L
        const val PORTAL_RING_PARTICLES = 18
        const val PORTAL_COLUMN_LAYERS = 5
        const val PORTAL_RING_RADIUS = 1.15
        const val CRATER_SHARE = 0.8
        val WEAPON_IDS = setOf(RAID_GUN_ID, RAID_GRENADE_ID)
    }
}
