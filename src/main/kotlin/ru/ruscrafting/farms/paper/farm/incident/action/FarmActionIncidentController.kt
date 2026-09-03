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
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.FarmBoarShieldPolicy
import ru.ruscrafting.farms.domain.FarmCropDamage
import ru.ruscrafting.farms.domain.FarmIncidentPlanner
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmSpecialIncidentEngine
import ru.ruscrafting.farms.domain.FarmSpecialIncidentState
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.FarmNightShiftController
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.block
import ru.ruscrafting.farms.paper.farm.FarmIncidentBedProvider
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.platform.FarmEntityRayTrace
import ru.ruscrafting.farms.paper.platform.FarmMobDespawnPolicy
import ru.ruscrafting.farms.paper.platform.FarmMobNavigation
import ru.ruscrafting.farms.paper.platform.FarmRaidRiderVisibility
import ru.ruscrafting.farms.paper.platform.FarmClientBlockPreview
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayRenderer
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.UUID
import java.util.logging.Level
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sin

internal val ACTION_FARM_INCIDENT_TYPES = setOf(FarmIncidentType.BOAR_BREAKOUT, FarmIncidentType.RIVAL_RAID)

internal data class FarmActionIncidentPlanAttempt(
    val plan: FarmSpecialIncidentState?,
    val candidates: Int,
    val rejection: String? = null,
)

/** Facade for action incidents; boar state stays here while rival-raid state has its own owner. */
internal class FarmActionIncidentController(
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
    private val blockPreviews: FarmClientBlockPreview,
    private val textDisplays: FarmTextDisplayRenderer,
    private val nightShift: FarmNightShiftController,
) {
    private val zoneKey = NamespacedKey(plugin, "farm_action_zone")
    private val sequenceKey = NamespacedKey(plugin, "farm_action_sequence")
    private val roleKey = NamespacedKey(plugin, "farm_action_role")
    private val targetKey = NamespacedKey(plugin, "farm_action_target")
    private val boars = mutableMapOf<String, MutableSet<UUID>>()
    private val raid = FarmRivalRaidController(
        plugin,
        settings,
        locale,
        debug,
        access,
        audience,
        state,
        tasks,
        serviceItems,
        beds,
        points,
        transitions,
        runtimes,
        entityRayTrace,
        mobDespawns,
        mobNavigation,
        riderVisibility,
        blockPreviews,
        textDisplays,
        nightShift,
    )

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
                FarmIncidentType.RIVAL_RAID -> raid.plan(runtime)
                else -> FarmActionIncidentPlanAttempt(null, 0, "unsupported_type")
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
            raid.start(runtime, plan)
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
            FarmIncidentType.RIVAL_RAID -> raid.ensure(runtime)
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
            FarmIncidentType.RIVAL_RAID -> raid.updateAmbient(runtime)
            else -> Unit
        }
    }

    fun updateRaidMotion(runtime: FarmRuntime) = raid.updateMotion(runtime)

    fun interact(event: PlayerInteractEntityEvent): Boolean {
        return raid.interact(event)
    }

    fun interact(event: PlayerInteractEvent): Boolean {
        return raid.interact(event)
    }

    fun onDamage(event: EntityDamageEvent): Boolean {
        if (role(event.entity) == ROLE_BOAR) {
            event.isCancelled = true
            return true
        }
        return raid.onDamage(event)
    }

    fun onDeath(event: EntityDeathEvent): Boolean = raid.onDeath(event)

    fun onTarget(event: EntityTargetLivingEntityEvent): Boolean = raid.onTarget(event)

    fun onDismount(event: EntityDismountEvent): Boolean = raid.onDismount(event)

    fun onProjectileHit(event: org.bukkit.event.entity.ProjectileHitEvent): Boolean = raid.onProjectileHit(event)

    fun owns(entity: Entity): Boolean = role(entity) == ROLE_BOAR || raid.owns(entity)

    fun participantRuntime(player: Player): FarmRuntime? = raid.participantRuntime(player)

    fun participants(runtime: FarmRuntime): List<Player> = raid.participants(runtime)

    fun enterPortal(player: Player, destination: Location): Boolean = raid.enterPortal(player, destination)

    fun onQuit(player: Player) = raid.onQuit(player)

    fun leaveZone(player: Player, runtime: FarmRuntime) {
        if (runtime.state.incidentType != FarmIncidentType.BOAR_BREAKOUT) return
        val shield = identity(runtime, BOAR_SHIELD_ID)
        while (serviceItems.consume(player, shield)) Unit
    }

    fun isActive(identity: ServiceItemIdentity): Boolean {
        if (identity.itemId != BOAR_SHIELD_ID) return raid.isActive(identity)
        if (identity.activity != ActivityKind.FARM) return false
        val runtime = runtimes().firstOrNull { it.settings.id == identity.zoneId } ?: return false
        return active(runtime, FarmIncidentType.BOAR_BREAKOUT, identity.sequence) &&
            runtime.state.placementSequence == identity.objectiveNonce
    }

    fun release(playerId: UUID, identity: ServiceItemIdentity, reason: WorksitePlayerReleaseReason) {
        if (identity.itemId != BOAR_SHIELD_ID) {
            raid.release(playerId, identity, reason)
            return
        }
        if (identity.activity != ActivityKind.FARM) return
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
        boars.keys.toSet().forEach { clearZone(it, reason) }
        raid.cleanup(reason)
        Bukkit.getWorlds().asSequence().flatMap { it.entities.asSequence() }
            .filter { role(it) == ROLE_BOAR }.forEach(Entity::remove)
    }

    private fun planBoars(runtime: FarmRuntime): FarmActionIncidentPlanAttempt {
        val candidates = beds.discover(runtime).filter { plot ->
            val soil = plot.block() ?: return@filter false
            val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
            runtime.region.contains(soil.location) && crop.type.name in runtime.settings.crops && !crop.type.isAir
        }
        val wanted = (runtime.settings.boarBreakout.activeBoars * 16).coerceIn(16, 64).coerceAtMost(candidates.size)
        if (wanted == 0) return FarmActionIncidentPlanAttempt(null, 0, "no_eligible_crops")
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
            return FarmActionIncidentPlanAttempt(null, candidates.size, "insufficient_spaced_plots")
        }
        return FarmActionIncidentPlanAttempt(
            FarmSpecialIncidentState(
                points = selected.map { FarmPointPosition(it.world, it.x + 0.5, it.y + 1.0, it.z + 0.5) },
                plots = selected,
            ),
            candidates.size,
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
                mobDespawns.setRemoveWhenFarAway(entity, false)
                entity.isImmuneToZombification = true
                entity.isGlowing = true
                entity.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = runtime.settings.boarBreakout.movementSpeed
                entity.getAttribute(Attribute.FOLLOW_RANGE)?.baseValue = runtime.settings.boarBreakout.aggroRadius
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
        val players = audience.players(runtime.region).filterNot(access::isAdminEditing)
        val fieldBeds = beds.discover(runtime)
        var cropsChanged = false
        ids.forEach { id ->
            val boar = Bukkit.getEntity(id) as? Hoglin ?: return@forEach
            val index = boar.persistentDataContainer.get(targetKey, PersistentDataType.INTEGER) ?: 0
            val target = special.plots.getOrNull(index) ?: return@forEach
            val blocker = players.firstOrNull { player ->
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
                val knockback = FarmBoarShieldPolicy.knockback(
                    boar.location.x,
                    boar.location.z,
                    blocker.location.x,
                    blocker.location.z,
                    -boar.location.direction.x,
                    -boar.location.direction.z,
                    runtime.settings.boarBreakout.shieldKnockbackHorizontal,
                    runtime.settings.boarBreakout.shieldKnockbackVertical,
                )
                blocker.velocity = org.bukkit.util.Vector(knockback.x, knockback.y, knockback.z)
                boars[runtime.settings.id]?.remove(id)
                boar.world.spawnParticle(Particle.CRIT, boar.location.add(0.0, 1.0, 0.0), 14, 0.4, 0.3, 0.4, 0.08)
                if (settings().sounds) blocker.playSound(blocker.location, Sound.ITEM_SHIELD_BLOCK, 1.0f, 0.85f)
                boar.remove()
                val result = FarmSpecialIncidentEngine.advanceAction(runtime.state, FarmIncidentType.BOAR_BREAKOUT, blocker.uniqueId)
                if (result.accepted) transitions.apply(runtime, result, blocker)
                return@forEach
            }
            val aggroSquared = runtime.settings.boarBreakout.aggroRadius * runtime.settings.boarBreakout.aggroRadius
            val targetPlayer = players.asSequence()
                .filter { it.world === boar.world && it.location.distanceSquared(boar.location) <= aggroSquared }
                .minByOrNull { it.location.distanceSquared(boar.location) }
            val targetLocation = target.cropLocation() ?: return@forEach
            if (targetPlayer != null) {
                boar.target = targetPlayer
                mobNavigation.moveTo(boar, targetPlayer, 1.25)
            } else if (boar.world === targetLocation.world && boar.location.distanceSquared(targetLocation) <=
                runtime.settings.boarBreakout.cropReachRadius * runtime.settings.boarBreakout.cropReachRadius
            ) {
                cropsChanged = damageCrop(runtime, target) || cropsChanged
                val next = nextBoarTarget(runtime, index)
                boar.persistentDataContainer.set(targetKey, PersistentDataType.INTEGER, next)
            } else {
                boar.target = null
                mobNavigation.moveTo(boar, targetLocation, 1.25)
            }
            cropsChanged = trampleAround(runtime, boar, fieldBeds) || cropsChanged
            emitBoarChargeParticles(runtime, boar)
        }
        if (cropsChanged) state.persistAsync()
    }

    private fun nextBoarTarget(runtime: FarmRuntime, current: Int): Int {
        val size = runtime.state.specialIncident?.plots?.size ?: return current
        return Math.floorMod(current + 1 + runtime.state.incidentProgress, size)
    }

    private fun trampleAround(runtime: FarmRuntime, boar: Hoglin, fieldBeds: Set<FarmPlotPosition>): Boolean {
        val radiusSquared = runtime.settings.boarBreakout.trampleRadius * runtime.settings.boarBreakout.trampleRadius
        var changed = false
        fieldBeds.asSequence()
            .filter { plot ->
                if (plot.world != boar.world.name) return@filter false
                val dx = plot.x + 0.5 - boar.location.x
                val dz = plot.z + 0.5 - boar.location.z
                dx * dx + dz * dz <= radiusSquared
            }
            .sortedBy { plot ->
                val dx = plot.x + 0.5 - boar.location.x
                val dz = plot.z + 0.5 - boar.location.z
                dx * dx + dz * dz
            }
            .take(runtime.settings.boarBreakout.trampleCropsPerUpdate)
            .forEach { plot -> changed = damageCrop(runtime, plot) || changed }
        return changed
    }

    private fun emitBoarChargeParticles(runtime: FarmRuntime, boar: Hoglin) {
        if (!settings().particles || runtime.settings.boarBreakout.trampleParticleCount <= 0) return
        val ground = boar.location.block.getRelative(org.bukkit.block.BlockFace.DOWN)
        boar.world.spawnParticle(
            Particle.DUST_PLUME,
            boar.location.clone().add(0.0, 0.15, 0.0),
            runtime.settings.boarBreakout.trampleParticleCount,
            0.55,
            0.08,
            0.55,
            0.025,
        )
        if (!ground.type.isAir) boar.world.spawnParticle(
            Particle.BLOCK_CRUMBLE,
            boar.location.clone().add(0.0, 0.1, 0.0),
            runtime.settings.boarBreakout.trampleParticleCount,
            0.5,
            0.12,
            0.5,
            ground.blockData,
        )
    }

    private fun damageCrop(runtime: FarmRuntime, position: FarmPlotPosition): Boolean {
        if (runtime.state.specialDamagedCrops.any { it.position == position }) return false
        if (runtime.state.specialDamagedCrops.size >= runtime.settings.boarBreakout.cropDamageMaximum) return false
        val soil = position.block() ?: return false
        val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
        if (crop.type.isAir || crop.type.name !in runtime.settings.crops) return false
        ledger.captureActiveCrop(soil, runtime.settings.id)
        val damage = FarmCropDamage(position, crop.type.name)
        crop.world.spawnParticle(Particle.BLOCK, crop.location.toCenterLocation(), 10, 0.3, 0.25, 0.3, crop.blockData)
        crop.setType(Material.AIR, false)
        soil.setType(Material.DIRT, false)
        runtime.state = runtime.state.copy(specialDamagedCrops = runtime.state.specialDamagedCrops + damage)
        return true
    }

    private fun issueTool(player: Player, runtime: FarmRuntime, itemId: String, material: Material, key: MessageKey) {
        val identity = identity(runtime, itemId)
        if (player.inventory.storageContents.any { serviceItems.identity(it) == identity } ||
            serviceItems.identity(player.inventory.itemInOffHand) == identity
        ) return
        if (serviceItems.issueHeld(player, identity, material, locale.render(key, player), 0, null) == null) {
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

    private fun hasShield(player: Player, runtime: FarmRuntime): Boolean {
        val expected = identity(runtime, BOAR_SHIELD_ID)
        return serviceItems.identity(player.inventory.itemInMainHand) == expected ||
            serviceItems.identity(player.inventory.itemInOffHand) == expected
    }

    private fun clearZone(zoneId: String, reason: String) {
        boars.remove(zoneId).orEmpty().forEach { Bukkit.getEntity(it)?.remove() }
        raid.clear(zoneId, reason)
        val runtime = runtimes().firstOrNull { it.settings.id == zoneId }
        if (runtime != null) {
            val shield = identity(runtime, BOAR_SHIELD_ID)
            Bukkit.getOnlinePlayers().forEach { player -> while (serviceItems.consume(player, shield)) Unit }
        }
        debug.event("farm_action_incident_cleared", "zone" to zoneId, "reason" to reason)
    }

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
        const val BOAR_SHIELD_ID = "boar_shield"
    }
}
