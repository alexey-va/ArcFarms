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
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Snowball
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
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
import ru.ruscrafting.farms.domain.FarmRaidSeatPolicy
import ru.ruscrafting.farms.domain.FarmRivalFieldPolicy
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
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import java.util.UUID
import kotlin.math.ceil
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
    private val serviceItems: WorksiteServiceItems,
    private val beds: FarmIncidentBedProvider,
    private val points: FarmPointProvider,
    private val transitions: FarmTransitionSink,
    private val runtimes: () -> Collection<FarmRuntime>,
    private val entityRayTrace: FarmEntityRayTrace,
    private val mobDespawns: FarmMobDespawnPolicy,
    private val mobNavigation: FarmMobNavigation,
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
        var workerSpawnSequence: Int = 0,
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
        if (!session.launched) return
        while (session.workerIds.size < runtime.settings.rivalRaid.workerCount) {
            val index = session.workerSpawnSequence++
            val plot = session.fieldPlots[Math.floorMod(index, session.fieldPlots.size)]
            val spawn = plot.spawnLocation() ?: break
            val mob = spawn.world.spawnEntity(spawn, EntityType.valueOf(runtime.settings.rivalRaid.workerEntity)) as? Mob ?: break
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
        }
        session.workerIds.forEach { workerId ->
            val worker = Bukkit.getEntity(workerId) as? Mob ?: return@forEach
            nightShift.updateExternalLight(
                workerLightOwner(runtime.settings.id, workerId),
                worker,
                runtime.settings.rivalRaid.workerLightLevel,
            )
        }
    }

    fun updateAmbient(runtime: FarmRuntime) {
        ensure(runtime)
        val session = raids[runtime.settings.id] ?: return
        val ghast = session.ghastId?.let(Bukkit::getEntity) as? Ghast ?: return
        ghast.passengers.filterIsInstance<Player>().forEach { board(runtime, session, ghast, it) }
        val participants = session.participantIds.mapNotNull(Bukkit::getPlayer).filter(Player::isOnline)
        nightShift.syncAmbientTime(
            atmosphereOwner(runtime.settings.id),
            participants,
            runtime.settings.rivalRaid.playerTime,
            runtime.settings.rivalRaid.timeTransitionSeconds,
        )
        participants.forEach { player ->
            issueWeapons(player, runtime)
            ensureSeat(runtime, session, ghast, player)
        }
        val now = ghast.world.gameTime
        if (now % runtime.settings.rivalRaid.workerPatrolIntervalTicks == 0L) patrol(runtime, session)
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
                FarmRaidFlight.orbitPoint(
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
        positionSeats(runtime, session, ghast, target)
    }

    fun interact(event: PlayerInteractEntityEvent): Boolean {
        val role = role(event.rightClicked) ?: return false
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
        if (session != null) {
            session.participantIds.forEach { playerId ->
                val player = Bukkit.getPlayer(playerId) ?: return@forEach
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
        session?.ghastId?.let(Bukkit::getEntity)?.remove()
        debug.event("farm_rival_raid_cleared", "zone" to zoneId, "reason" to reason)
    }

    fun cleanup(reason: String) {
        raids.keys.toSet().forEach { clear(it, reason) }
        Bukkit.getWorlds().asSequence().flatMap { it.entities.asSequence() }.filter(::owns).forEach(Entity::remove)
    }

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
            MAX_FIELD_PLOTS,
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

    private fun patrol(runtime: FarmRuntime, session: RaidSession) {
        session.workerIds.forEach { workerId ->
            val worker = Bukkit.getEntity(workerId) as? Mob ?: return@forEach
            worker.target = null
            val current = worker.persistentDataContainer.get(targetKey, PersistentDataType.INTEGER) ?: 0
            val next = Math.floorMod(current + runtime.settings.rivalRaid.workerCount, session.fieldPlots.size)
            worker.persistentDataContainer.set(targetKey, PersistentDataType.INTEGER, next)
            session.fieldPlots[next].spawnLocation()?.let {
                mobNavigation.moveTo(worker, it, runtime.settings.rivalRaid.workerPatrolSpeed)
            }
        }
    }

    private fun board(runtime: FarmRuntime, session: RaidSession, ghast: Ghast, player: Player) {
        if (!FarmRaidSeatPolicy.canBoard(
                session.participantIds.size,
                runtime.settings.rivalRaid.maximumRiders,
                player.uniqueId in session.participantIds,
            )
        ) return
        session.participantIds += player.uniqueId
        val seat = ensureSeat(runtime, session, ghast, player) ?: return
        if (player.vehicle !== seat) {
            player.leaveVehicle()
            seat.addPassenger(player)
        }
        issueWeapons(player, runtime)
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

    private fun issueWeapons(player: Player, runtime: FarmRuntime) {
        issueTool(player, runtime, RAID_GUN_ID, MessageKey.FARM_RIVAL_RAID_GUN)
        issueTool(player, runtime, RAID_GRENADE_ID, MessageKey.FARM_RIVAL_RAID_GRENADE)
    }

    private fun issueTool(player: Player, runtime: FarmRuntime, itemId: String, key: MessageKey) {
        val identity = identity(runtime, itemId)
        if (player.inventory.storageContents.any { serviceItems.identity(it) == identity } ||
            serviceItems.identity(player.inventory.itemInOffHand) == identity
        ) return
        val config = runtime.settings.rivalRaid
        val grenade = itemId == RAID_GRENADE_ID
        val material = MaterialRules.material(if (grenade) config.grenadeMaterial else config.gunMaterial)
        val customModelData = if (grenade) config.grenadeCustomModelData else config.gunCustomModelData
        val itemModelName = if (grenade) config.grenadeItemModel else config.gunItemModel
        val itemModel = itemModelName?.let { requireNotNull(NamespacedKey.fromString(it)) }
        if (serviceItems.issue(player, identity, material, locale.render(key, player), customModelData, itemModel) == null) {
            audience.sendActionBar(player, MessageKey.FARM_ACTION_INVENTORY_FULL)
        }
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

    private fun positionSeats(
        runtime: FarmRuntime,
        session: RaidSession,
        ghast: Ghast,
        targetPoint: FarmPointPosition,
    ) {
        val dx = targetPoint.x - ghast.location.x
        val dz = targetPoint.z - ghast.location.z
        val length = sqrt(dx * dx + dz * dz).coerceAtLeast(1.0e-6)
        val forwardX = dx / length
        val forwardZ = dz / length
        val sideX = -forwardZ
        val sideZ = forwardX
        val live = session.participantIds.sortedBy(UUID::toString).take(runtime.settings.rivalRaid.maximumRiders)
        live.forEachIndexed { index, playerId ->
            val player = Bukkit.getPlayer(playerId)
            val seat = player?.let { ensureSeat(runtime, session, ghast, it) } ?: return@forEachIndexed
            val sideOffset = (index - (live.size - 1) / 2.0) * RAID_SEAT_SPACING
            val target = ghast.location.clone().add(
                forwardX * runtime.settings.rivalRaid.seatForwardOffset + sideX * sideOffset,
                runtime.settings.rivalRaid.seatYOffset,
                forwardZ * runtime.settings.rivalRaid.seatForwardOffset + sideZ * sideOffset,
            )
            val passenger = seat.passengers.singleOrNull { it.uniqueId == playerId }
            if (passenger != null) passenger.leaveVehicle()
            if (seat.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN) && passenger != null) {
                seat.addPassenger(passenger)
            }
        }
    }

    private fun removeSeat(session: RaidSession, playerId: UUID) {
        session.riderSeatIds.remove(playerId)?.let(Bukkit::getEntity)?.remove()
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
        const val RAID_GUN_ID = "raid_gun"
        const val RAID_GRENADE_ID = "raid_grenade_launcher"
        const val ACTION_ROLE = "farm_action"
        const val RAID_SEAT_SPACING = 1.1
        const val MAX_FIELD_PLOTS = 128
        val WEAPON_IDS = setOf(RAID_GUN_ID, RAID_GRENADE_ID)
    }
}
