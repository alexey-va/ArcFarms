package ru.ruscrafting.farms.paper.farm.incident.action

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Ghast
import org.bukkit.entity.Hoglin
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.FarmBoarShieldPolicy
import ru.ruscrafting.farms.domain.FarmCropDamage
import ru.ruscrafting.farms.domain.FarmDamageBudget
import ru.ruscrafting.farms.domain.FarmIncidentPlanner
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmRaidFlight
import ru.ruscrafting.farms.domain.FarmRaidDamageGate
import ru.ruscrafting.farms.domain.FarmSpecialIncidentEngine
import ru.ruscrafting.farms.domain.FarmSpecialIncidentState
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.block
import ru.ruscrafting.farms.paper.farm.FarmIncidentBedProvider
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.platform.FarmEntityRayTrace
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import java.util.UUID
import java.util.logging.Level
import kotlin.math.cos
import kotlin.math.sin

internal val ACTION_FARM_INCIDENT_TYPES = setOf(FarmIncidentType.BOAR_BREAKOUT, FarmIncidentType.RIVAL_RAID)

/** Owns the shield-driven boar breach and the autonomous ghast rival-farm raid. */
internal class FarmActionIncidentController(
    plugin: Plugin,
    private val settings: () -> ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val state: WorksiteStatePort,
    private val serviceItems: WorksiteServiceItems,
    private val ledger: FarmBlockLedger,
    private val beds: FarmIncidentBedProvider,
    private val points: FarmPointProvider,
    private val transitions: FarmTransitionSink,
    private val runtimes: () -> Collection<FarmRuntime>,
    private val entityRayTrace: FarmEntityRayTrace,
) {
    private data class RaidSession(
        val sequence: Long,
        val objectiveNonce: Long,
        val returnPoint: FarmPointPosition,
        var ghastId: UUID? = null,
        val workerIds: MutableSet<UUID> = linkedSetOf(),
        val participantIds: MutableSet<UUID> = linkedSetOf(),
        val shotAt: MutableMap<UUID, Long> = hashMapOf(),
    )

    private data class PlanAttempt(
        val plan: FarmSpecialIncidentState?,
        val candidates: Int,
        val rejection: String? = null,
    )

    private val zoneKey = NamespacedKey(plugin, "farm_action_zone")
    private val sequenceKey = NamespacedKey(plugin, "farm_action_sequence")
    private val roleKey = NamespacedKey(plugin, "farm_action_role")
    private val targetKey = NamespacedKey(plugin, "farm_action_target")
    private val boars = mutableMapOf<String, MutableSet<UUID>>()
    private val raids = mutableMapOf<String, RaidSession>()
    private val raidDamage = FarmRaidDamageGate()

    fun initialize(runtime: FarmRuntime, type: FarmIncidentType): FarmIncidentType? {
        if (runtime.state.phase != FarmPhase.INCIDENT || runtime.state.incidentType != type ||
            runtime.state.specialIncident != null || type !in ACTION_FARM_INCIDENT_TYPES
        ) return null
        val order = runtime.state.orderId?.let(runtime.orders::get)
        val configuredTypes = order?.incidentTypes.orEmpty().filter(ACTION_FARM_INCIDENT_TYPES::contains)
        val scheduledTypes = order?.let { configured ->
            FarmIncidentPlanner.sequence(
                configured.incidentTypes,
                runtime.rules.incidentTargetCount(runtime.state.sequence),
                runtime.state.sequence,
            )
        }.orEmpty()
        val candidates = (listOf(type) + configuredTypes.filterNot(scheduledTypes::contains) + configuredTypes).distinct()
        val attempts = candidates.map { candidate ->
            candidate to when (candidate) {
                FarmIncidentType.BOAR_BREAKOUT -> planBoars(runtime)
                FarmIncidentType.RIVAL_RAID -> planRaid(runtime)
                else -> PlanAttempt(null, 0, "unsupported_type")
            }
        }
        val selected = attempts.firstNotNullOfOrNull { (candidate, attempt) ->
            attempt.plan?.let { candidate to it }
        } ?: run {
            state.log(
                Level.WARNING,
                "Could not plan farm action incident: zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                    "requested=$type attempts=${attempts.joinToString { (candidate, attempt) ->
                        "$candidate:${attempt.rejection ?: "unknown"}(candidates=${attempt.candidates})"
                    }}",
            )
            return null
        }
        val (activeType, plan) = selected
        if (activeType != type) {
            val retargeted = FarmSpecialIncidentEngine.retargetUninitialized(runtime.state, activeType)
            if (!retargeted.accepted) return null
            runtime.state = retargeted.state
            debug.event(
                "farm_action_incident_retargeted",
                "zone" to runtime.settings.id,
                "requested_type" to type,
                "active_type" to activeType,
            )
        }
        val required = when (activeType) {
            FarmIncidentType.BOAR_BREAKOUT -> runtime.settings.boarBreakout.requiredDeflections
            FarmIncidentType.RIVAL_RAID -> runtime.settings.rivalRaid.requiredKills
            else -> return null
        }
        val initialized = FarmSpecialIncidentEngine.initialize(runtime.state, activeType, plan, required)
        if (!initialized.accepted) return null
        runtime.state = initialized.state
        if (activeType == FarmIncidentType.RIVAL_RAID) {
            raids[runtime.settings.id] = RaidSession(
                runtime.state.sequence,
                runtime.state.placementSequence,
                plan.points.first(),
            )
        }
        debug.event(
            "farm_action_incident_initialized",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "type" to activeType,
            "points" to plan.points.size,
            "plots" to plan.plots.size,
            "required" to required,
        )
        state.persistAsync()
        return activeType
    }

    fun ensure(runtime: FarmRuntime) {
        val type = runtime.state.incidentType
        if (runtime.state.phase != FarmPhase.INCIDENT || type !in ACTION_FARM_INCIDENT_TYPES) {
            clearZone(runtime.settings.id, "inactive")
            return
        }
        when (type) {
            FarmIncidentType.BOAR_BREAKOUT -> ensureBoars(runtime)
            FarmIncidentType.RIVAL_RAID -> ensureRaid(runtime)
            else -> Unit
        }
    }

    fun announce(runtime: FarmRuntime) {
        val key = when (runtime.state.incidentType) {
            FarmIncidentType.BOAR_BREAKOUT -> MessageKey.FARM_BOAR_BREAKOUT_STARTED
            FarmIncidentType.RIVAL_RAID -> MessageKey.FARM_RIVAL_RAID_STARTED
            else -> return
        }
        audience.broadcast(
            listOf(runtime.region),
            key,
            mapOf("total" to locale.text(runtime.state.incidentRequired)),
            if (runtime.state.incidentType == FarmIncidentType.BOAR_BREAKOUT) Sound.ENTITY_HOGLIN_ANGRY else Sound.ENTITY_GHAST_AMBIENT,
            title = true,
        )
    }

    fun update(runtime: FarmRuntime) {
        when (runtime.state.incidentType) {
            FarmIncidentType.BOAR_BREAKOUT -> updateBoars(runtime)
            FarmIncidentType.RIVAL_RAID -> updateRaid(runtime)
            else -> Unit
        }
    }

    fun interact(event: PlayerInteractEntityEvent): Boolean {
        val role = role(event.rightClicked) ?: return false
        if (role == ROLE_WORKER) {
            val identity = serviceItems.identity(event.player.inventory.itemInMainHand) ?: return false
            if (identity.activity != ActivityKind.FARM || identity.itemId != RAID_GUN_ID) return false
            event.isCancelled = true
            val runtime = runtimes().firstOrNull { it.settings.id == identity.zoneId } ?: return true
            val session = raids[identity.zoneId] ?: return true
            if (isActive(identity) && event.player.uniqueId in session.participantIds) fire(event.player, runtime, session)
            return true
        }
        if (role != ROLE_GHAST) return false
        event.isCancelled = true
        val zoneId = event.rightClicked.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) ?: return true
        val runtime = runtimes().firstOrNull { it.settings.id == zoneId } ?: return true
        val session = raids[zoneId] ?: return true
        if (!active(runtime, FarmIncidentType.RIVAL_RAID, session.sequence) || !access.hasAccess(event.player, runtime.settings.permission)) {
            return true
        }
        val ghast = event.rightClicked as? Ghast ?: return true
        if (event.player.vehicle !== ghast && !ghast.addPassenger(event.player)) return true
        session.participantIds += event.player.uniqueId
        issueTool(event.player, runtime, RAID_GUN_ID, MaterialRules.material(runtime.settings.rivalRaid.gunMaterial), MessageKey.FARM_RIVAL_RAID_GUN)
        audience.sendActionBar(event.player, MessageKey.FARM_RIVAL_RAID_MOUNTED)
        if (settings().sounds) event.player.playSound(event.player.location, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.8f, 1.15f)
        return true
    }

    fun interact(event: PlayerInteractEvent): Boolean {
        if (event.hand != EquipmentSlot.HAND || event.action !in setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)) return false
        val identity = serviceItems.identity(event.player.inventory.itemInMainHand) ?: return false
        if (identity.activity != ActivityKind.FARM || identity.itemId != RAID_GUN_ID) return false
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
        event.isCancelled = true
        val runtime = runtimes().firstOrNull { it.settings.id == identity.zoneId } ?: return true
        val session = raids[identity.zoneId] ?: return true
        if (!isActive(identity) || event.player.uniqueId !in session.participantIds) return true
        fire(event.player, runtime, session)
        return true
    }

    fun onDamage(event: EntityDamageEvent): Boolean {
        val role = role(event.entity) ?: return false
        if (role == ROLE_BOAR || role == ROLE_GHAST) {
            event.isCancelled = true
            return true
        }
        if (role != ROLE_WORKER) return false
        val damage = event as? EntityDamageByEntityEvent
        val player = damage?.damager as? Player
        val zoneId = event.entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING)
        val session = zoneId?.let(raids::get)
        if (player == null || session == null || player.uniqueId !in session.participantIds ||
            !isGun(player, zoneId, session) || !raidDamage.consume(player.uniqueId, event.entity.uniqueId)
        ) {
            event.isCancelled = true
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
        raids.values.forEach { session ->
            if (player.uniqueId in session.participantIds) returnParticipant(session, player)
            session.participantIds.remove(player.uniqueId)
            session.shotAt.remove(player.uniqueId)
        }
    }

    fun leaveZone(player: Player, runtime: FarmRuntime) {
        if (runtime.state.incidentType != FarmIncidentType.BOAR_BREAKOUT) return
        val shield = identity(runtime, BOAR_SHIELD_ID)
        while (serviceItems.consume(player, shield)) Unit
    }

    fun isActive(identity: ServiceItemIdentity): Boolean {
        if (identity.activity != ActivityKind.FARM || identity.itemId !in setOf(BOAR_SHIELD_ID, RAID_GUN_ID)) return false
        val runtime = runtimes().firstOrNull { it.settings.id == identity.zoneId } ?: return false
        val expectedType = if (identity.itemId == BOAR_SHIELD_ID) FarmIncidentType.BOAR_BREAKOUT else FarmIncidentType.RIVAL_RAID
        return active(runtime, expectedType, identity.sequence) && runtime.state.placementSequence == identity.objectiveNonce
    }

    fun release(playerId: UUID, identity: ServiceItemIdentity, reason: WorksitePlayerReleaseReason) {
        if (identity.activity != ActivityKind.FARM || identity.itemId !in setOf(BOAR_SHIELD_ID, RAID_GUN_ID)) return
        if (identity.itemId == RAID_GUN_ID) raids[identity.zoneId]?.let { session ->
            val wasParticipant = playerId in session.participantIds
            if ((wasParticipant || reason == WorksitePlayerReleaseReason.JOIN_STALE) && reason.returnsRaidParticipant()) {
                session.participantIds += playerId
                Bukkit.getPlayer(playerId)?.let { player -> returnParticipant(session, player) }
            }
            session.participantIds.remove(playerId)
            session.shotAt.remove(playerId)
        }
        debug.event(
            "farm_action_service_item_released",
            "zone" to identity.zoneId,
            "item" to identity.itemId,
            "player" to playerId,
            "reason" to reason,
        )
    }

    fun clear(runtime: FarmRuntime, reason: String) = clearZone(runtime.settings.id, reason)

    fun cleanup(reason: String) {
        (boars.keys + raids.keys).toSet().forEach { clearZone(it, reason) }
        Bukkit.getWorlds().asSequence().flatMap { it.entities.asSequence() }.filter(::owns).forEach(Entity::remove)
    }

    private fun planBoars(runtime: FarmRuntime): PlanAttempt {
        val candidates = beds.discover(runtime).filter { plot ->
            val soil = plot.block() ?: return@filter false
            val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
            runtime.region.contains(soil.location) && crop.type.name in runtime.settings.crops && !crop.type.isAir
        }
        val wanted = (runtime.settings.boarBreakout.activeBoars * 4).coerceIn(4, 16).coerceAtMost(candidates.size)
        if (wanted == 0) return PlanAttempt(null, 0, "no_eligible_crops")
        val selected = FarmIncidentPlanner.centralDispersedCenters(
            candidates,
            wanted,
            minimumSpacing = 6.0,
            selectionIndex = runtime.state.placementSequence,
        )
        debug.event(
            "farm_boar_candidates",
            "zone" to runtime.settings.id,
            "candidates" to candidates.size,
            "requested" to wanted,
            "selected" to selected.size,
        )
        if (selected.size < runtime.settings.boarBreakout.activeBoars) {
            return PlanAttempt(null, candidates.size, "insufficient_spaced_plots")
        }
        return PlanAttempt(
            FarmSpecialIncidentState(
                points = selected.map { FarmPointPosition(it.world, it.x + 0.5, it.y + 1.0, it.z + 0.5) },
                plots = selected,
            ),
            candidates.size,
        )
    }

    private fun planRaid(runtime: FarmRuntime): PlanAttempt {
        val rival = points.configured(runtime, FarmPointKind.RIVAL_FARM)
            ?: return PlanAttempt(null, 0, "rival_point_missing")
        val departure = points.resolve(runtime, FarmPointKind.RECEIVING)
        if (rival.world != departure.world) return PlanAttempt(null, 1, "wrong_world")
        val dx = rival.x - departure.x
        val dz = rival.z - departure.z
        val distance = kotlin.math.sqrt(dx * dx + dz * dz)
        if (distance > runtime.settings.rivalRaid.maximumDistance) return PlanAttempt(null, 1, "distance_exceeds_maximum")
        return PlanAttempt(
            FarmSpecialIncidentState(points = listOf(departure.copy(pitch = 0f), rival.copy(pitch = 0f))),
            1,
        )
    }

    private fun ensureBoars(runtime: FarmRuntime) {
        val special = runtime.state.specialIncident ?: return
        audience.players(runtime.region).filterNot(access::isAdminEditing).forEach { player ->
            issueTool(
                player,
                runtime,
                BOAR_SHIELD_ID,
                MaterialRules.material(runtime.settings.boarBreakout.shieldMaterial),
                MessageKey.FARM_BOAR_BREAKOUT_SHIELD,
            )
        }
        val active = boars.getOrPut(runtime.settings.id, ::linkedSetOf)
        active.removeIf { Bukkit.getEntity(it)?.isValid != true }
        while (active.size < runtime.settings.boarBreakout.activeBoars) {
            val targetIndex = Math.floorMod(active.size + runtime.state.incidentProgress, special.plots.size)
            val spawnIndex = Math.floorMod(targetIndex + special.points.size / 2, special.points.size)
            val spawn = special.points[spawnIndex].location() ?: break
            if (!spawn.chunk.isLoaded) break
            val hoglin = spawn.world.spawn(spawn, Hoglin::class.java) { entity ->
                entity.isPersistent = false
                entity.removeWhenFarAway = false
                entity.isImmuneToZombification = true
                entity.isGlowing = true
                entity.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = runtime.settings.boarBreakout.movementSpeed
                mark(entity, runtime, ROLE_BOAR, targetIndex)
            }
            active += hoglin.uniqueId
            debug.event("farm_boar_spawned", "zone" to runtime.settings.id, "target" to targetIndex)
        }
    }

    private fun updateBoars(runtime: FarmRuntime) {
        ensureBoars(runtime)
        val special = runtime.state.specialIncident ?: return
        val ids = boars[runtime.settings.id]?.toList().orEmpty()
        ids.forEach { id ->
            val boar = Bukkit.getEntity(id) as? Hoglin ?: return@forEach
            val index = boar.persistentDataContainer.get(targetKey, PersistentDataType.INTEGER) ?: 0
            val target = special.plots.getOrNull(index) ?: return@forEach
            val blocker = audience.players(runtime.region).filterNot(access::isAdminEditing).firstOrNull { player ->
                if (player.world !== boar.world) return@firstOrNull false
                val offsetX = boar.location.x - player.location.x
                val offsetZ = boar.location.z - player.location.z
                FarmBoarShieldPolicy.canDeflect(
                    player.isBlocking,
                    hasShield(player, runtime),
                    player.location.distanceSquared(boar.location),
                    runtime.settings.boarBreakout.interceptRadius,
                    runtime.settings.boarBreakout.facingDot,
                    player.eyeLocation.direction.x,
                    player.eyeLocation.direction.z,
                    offsetX,
                    offsetZ,
                )
            }
            if (blocker != null) {
                boars[runtime.settings.id]?.remove(id)
                boar.world.spawnParticle(Particle.CRIT, boar.location.add(0.0, 1.0, 0.0), 14, 0.4, 0.3, 0.4, 0.08)
                if (settings().sounds) blocker.playSound(blocker.location, Sound.ITEM_SHIELD_BLOCK, 1.0f, 0.85f)
                boar.remove()
                val result = FarmSpecialIncidentEngine.advanceAction(runtime.state, FarmIncidentType.BOAR_BREAKOUT, blocker.uniqueId)
                if (result.accepted) transitions.apply(runtime, result, blocker)
                return@forEach
            }
            val targetLocation = target.cropLocation() ?: return@forEach
            if (boar.world === targetLocation.world && boar.location.distanceSquared(targetLocation) <=
                runtime.settings.boarBreakout.cropReachRadius * runtime.settings.boarBreakout.cropReachRadius
            ) {
                damageCrop(runtime, target)
                val next = nextBoarTarget(runtime, index)
                boar.persistentDataContainer.set(targetKey, PersistentDataType.INTEGER, next)
            } else boar.pathfinder.moveTo(targetLocation, 1.0)
        }
    }

    private fun nextBoarTarget(runtime: FarmRuntime, current: Int): Int {
        val size = runtime.state.specialIncident?.plots?.size ?: return current
        return Math.floorMod(current + 1 + runtime.state.incidentProgress, size)
    }

    private fun damageCrop(runtime: FarmRuntime, position: FarmPlotPosition) {
        if (runtime.state.specialDamagedCrops.any { it.position == position }) return
        val total = beds.discover(runtime).size
        val remaining = FarmDamageBudget.remaining(
            total,
            runtime.state.specialDamagedCrops.size,
            runtime.settings.damageSafety.maximumPercent,
            runtime.settings.damageSafety.minimumRemaining,
            runtime.settings.boarBreakout.cropDamageMaximum,
        )
        if (remaining <= 0) return
        val soil = position.block() ?: return
        val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
        if (crop.type.isAir || crop.type.name !in runtime.settings.crops) return
        ledger.captureActiveCrop(soil, runtime.settings.id)
        val damage = FarmCropDamage(position, crop.type.name)
        crop.world.spawnParticle(Particle.BLOCK, crop.location.toCenterLocation(), 10, 0.3, 0.25, 0.3, crop.blockData)
        crop.setType(Material.AIR, false)
        runtime.state = runtime.state.copy(specialDamagedCrops = runtime.state.specialDamagedCrops + damage)
        state.persistAsync()
    }

    private fun ensureRaid(runtime: FarmRuntime) {
        val special = runtime.state.specialIncident ?: return
        val session = raids.getOrPut(runtime.settings.id) {
            RaidSession(runtime.state.sequence, runtime.state.placementSequence, special.points.first())
        }
        if (session.sequence != runtime.state.sequence) {
            clearZone(runtime.settings.id, "sequence_changed")
            raids[runtime.settings.id] = RaidSession(runtime.state.sequence, runtime.state.placementSequence, special.points.first())
            return
        }
        val departure = special.points.first().location() ?: return
        var ghast = session.ghastId?.let(Bukkit::getEntity) as? Ghast
        if (ghast?.isValid != true && departure.world.isChunkLoaded(departure.blockX shr 4, departure.blockZ shr 4)) {
            ghast = departure.world.spawn(departure.clone().add(0.0, 2.0, 0.0), Ghast::class.java) { entity ->
                entity.isPersistent = false
                entity.removeWhenFarAway = false
                entity.isAware = false
                entity.isInvulnerable = true
                entity.setGravity(false)
                entity.isGlowing = true
                mark(entity, runtime, ROLE_GHAST, 0)
            }
            session.ghastId = ghast.uniqueId
        }
        session.workerIds.removeIf { Bukkit.getEntity(it)?.isValid != true }
        val rival = special.points.getOrNull(1)?.location() ?: return
        if (!rival.world.isChunkLoaded(rival.blockX shr 4, rival.blockZ shr 4)) return
        val raidGhast = ghast ?: return
        val workerActivationRadius = maxOf(runtime.settings.rivalRaid.gunRange, runtime.settings.rivalRaid.workerRadius * 2.0)
        if (raidGhast.world !== rival.world || raidGhast.location.distanceSquared(rival) > workerActivationRadius * workerActivationRadius) {
            return
        }
        while (session.workerIds.size < runtime.settings.rivalRaid.workerCount) {
            val index = session.workerIds.size
            val angle = index * Math.PI * 2.0 / runtime.settings.rivalRaid.workerCount
            val radius = runtime.settings.rivalRaid.workerRadius * (0.45 + (index % 3) * 0.2)
            val spawn = rival.clone().add(cos(angle) * radius, 0.0, sin(angle) * radius)
            val mob = spawn.world.spawnEntity(spawn, EntityType.valueOf(runtime.settings.rivalRaid.workerEntity)) as? Mob ?: break
            mob.isPersistent = false
            mob.removeWhenFarAway = false
            mob.isGlowing = true
            mob.getAttribute(Attribute.MAX_HEALTH)?.baseValue = runtime.settings.rivalRaid.workerHealth
            mob.health = runtime.settings.rivalRaid.workerHealth
            mob.customName(locale.render(MessageKey.FARM_RIVAL_RAID_WORKER))
            mark(mob, runtime, ROLE_WORKER, index)
            session.workerIds += mob.uniqueId
        }
    }

    private fun updateRaid(runtime: FarmRuntime) {
        ensureRaid(runtime)
        val special = runtime.state.specialIncident ?: return
        val session = raids[runtime.settings.id] ?: return
        val ghast = session.ghastId?.let(Bukkit::getEntity) as? Ghast ?: return
        ghast.passengers.filterIsInstance<Player>().forEach { player ->
            session.participantIds += player.uniqueId
            issueTool(player, runtime, RAID_GUN_ID, MaterialRules.material(runtime.settings.rivalRaid.gunMaterial), MessageKey.FARM_RIVAL_RAID_GUN)
        }
        if (ghast.passengers.none { it is Player }) return
        val departure = special.points.first()
        val rival = special.points.getOrNull(1) ?: return
        val launch = departure.copy(y = departure.y + runtime.settings.rivalRaid.flightHeight)
        val cruiseTarget = rival.copy(y = rival.y + runtime.settings.rivalRaid.flightHeight)
        val current = FarmPointPosition(ghast.world.name, ghast.location.x, ghast.location.y, ghast.location.z)
        val target = if (current.horizontalDistanceSquared(departure) <= 0.25 && current.y < launch.y - 0.1) {
            launch
        } else cruiseTarget
        val next = FarmRaidFlight.step(current, target, runtime.settings.rivalRaid.flightSpeed)
        val passengers = ghast.passengers.toList()
        ghast.teleport(next.location() ?: return, PlayerTeleportEvent.TeleportCause.PLUGIN)
        passengers.filter { it.vehicle !== ghast && it.isValid }.forEach(ghast::addPassenger)
    }

    private fun fire(player: Player, runtime: FarmRuntime, session: RaidSession) {
        val config = runtime.settings.rivalRaid
        val now = player.world.gameTime
        val previous = session.shotAt[player.uniqueId] ?: Long.MIN_VALUE / 2
        if (now - previous < config.gunCooldownTicks) return
        session.shotAt[player.uniqueId] = now
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
        raidDamage.authorize(player.uniqueId, target.uniqueId) {
            target.damage(config.gunDamage, player)
        }
        target.world.spawnParticle(Particle.CRIT, target.location.add(0.0, target.height * 0.55, 0.0), 10, 0.25, 0.25, 0.25, 0.08)
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

    private fun issueTool(player: Player, runtime: FarmRuntime, itemId: String, material: Material, key: MessageKey) {
        val identity = identity(runtime, itemId)
        if (player.inventory.storageContents.any { serviceItems.identity(it) == identity } ||
            serviceItems.identity(player.inventory.itemInOffHand) == identity
        ) return
        if (serviceItems.issue(player, identity, material, locale.render(key, player)) == null) {
            audience.sendActionBar(player, MessageKey.FARM_ACTION_INVENTORY_FULL)
        }
    }

    private fun identity(runtime: FarmRuntime, itemId: String) = ServiceItemIdentity(
        ActivityKind.FARM,
        runtime.settings.id,
        runtime.state.sequence,
        runtime.state.placementSequence,
        ObjectiveTargetRole("farm_action"),
        itemId,
    )

    private fun isGun(player: Player, zoneId: String, session: RaidSession): Boolean =
        serviceItems.identity(player.inventory.itemInMainHand) == ServiceItemIdentity(
            ActivityKind.FARM,
            zoneId,
            session.sequence,
            session.objectiveNonce,
            ObjectiveTargetRole("farm_action"),
            RAID_GUN_ID,
        )

    private fun hasShield(player: Player, runtime: FarmRuntime): Boolean {
        val expected = identity(runtime, BOAR_SHIELD_ID)
        return serviceItems.identity(player.inventory.itemInMainHand) == expected ||
            serviceItems.identity(player.inventory.itemInOffHand) == expected
    }

    private fun clearZone(zoneId: String, reason: String) {
        boars.remove(zoneId).orEmpty().forEach { Bukkit.getEntity(it)?.remove() }
        val session = raids[zoneId]
        if (session != null) {
            session.participantIds.forEach { playerId ->
                val player = Bukkit.getPlayer(playerId) ?: return@forEach
                while (serviceItems.consume(player, ServiceItemIdentity(
                        ActivityKind.FARM,
                        zoneId,
                        session.sequence,
                        session.objectiveNonce,
                        ObjectiveTargetRole("farm_action"),
                        RAID_GUN_ID,
                    ))) Unit
                returnParticipant(session, player)
            }
            raids.remove(zoneId, session)
        }
        session?.workerIds.orEmpty().forEach { Bukkit.getEntity(it)?.remove() }
        session?.ghastId?.let(Bukkit::getEntity)?.remove()
        val runtime = runtimes().firstOrNull { it.settings.id == zoneId }
        if (runtime != null) {
            val shield = identity(runtime, BOAR_SHIELD_ID)
            Bukkit.getOnlinePlayers().forEach { player -> while (serviceItems.consume(player, shield)) Unit }
        }
        debug.event("farm_action_incident_cleared", "zone" to zoneId, "reason" to reason)
    }

    private fun returnParticipant(session: RaidSession, player: Player) {
        session.returnPoint.location()?.let(player::teleport)
    }

    private fun WorksitePlayerReleaseReason.returnsRaidParticipant(): Boolean = this in setOf(
        WorksitePlayerReleaseReason.QUIT,
        WorksitePlayerReleaseReason.RELOAD,
        WorksitePlayerReleaseReason.SHUTDOWN,
        WorksitePlayerReleaseReason.JOIN_STALE,
        WorksitePlayerReleaseReason.OBJECTIVE_REPLACED,
    )

    private fun mark(entity: Entity, runtime: FarmRuntime, role: String, target: Int) {
        entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, runtime.settings.id)
        entity.persistentDataContainer.set(sequenceKey, PersistentDataType.LONG, runtime.state.sequence)
        entity.persistentDataContainer.set(roleKey, PersistentDataType.STRING, role)
        entity.persistentDataContainer.set(targetKey, PersistentDataType.INTEGER, target)
    }

    private fun role(entity: Entity): String? = entity.persistentDataContainer.get(roleKey, PersistentDataType.STRING)

    private fun active(runtime: FarmRuntime, type: FarmIncidentType, sequence: Long): Boolean =
        runtime.state.phase == FarmPhase.INCIDENT && runtime.state.incidentType == type &&
            runtime.state.sequence == sequence && runtime.state.specialIncident != null

    private fun FarmPointPosition.location(): Location? = Bukkit.getWorld(world)?.let { Location(it, x, y, z, yaw, pitch) }

    private fun FarmPlotPosition.cropLocation(): Location? = block()?.getRelative(org.bukkit.block.BlockFace.UP)?.location?.toCenterLocation()

    private fun FarmPointPosition.horizontalDistanceSquared(other: FarmPointPosition): Double {
        val dx = x - other.x
        val dz = z - other.z
        return dx * dx + dz * dz
    }

    private companion object {
        const val ROLE_BOAR = "boar"
        const val ROLE_GHAST = "raid_ghast"
        const val ROLE_WORKER = "raid_worker"
        const val BOAR_SHIELD_ID = "boar_shield"
        const val RAID_GUN_ID = "raid_gun"
    }
}
