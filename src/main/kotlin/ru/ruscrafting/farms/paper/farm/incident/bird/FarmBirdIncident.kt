package ru.ruscrafting.farms.paper.farm.incident.bird

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Parrot
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmBirdPlanner
import ru.ruscrafting.farms.domain.FarmAsyncPlanGate
import ru.ruscrafting.farms.domain.FarmCropDamage
import ru.ruscrafting.farms.domain.FarmDamageBudget
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.domain.FarmSpecialIncidentState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.BukkitFarmEntityLookup
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmEntityLookup
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.block
import ru.ruscrafting.farms.paper.toFarmPlotPosition
import ru.ruscrafting.farms.paper.farm.FarmIncidentBedProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.placement.FarmSurfacePolicy
import java.util.UUID
import java.util.logging.Level

/** Bounded, non-persistent flock whose crop damage is restored by the shared incident journal. */
internal class FarmBirdIncident(
    plugin: Plugin,
    private val settings: () -> ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
    private val port: WorksiteRuntimePort,
    private val ledger: FarmBlockLedger,
    private val beds: FarmIncidentBedProvider,
    private val transitions: FarmTransitionSink,
    private val entityLookup: FarmEntityLookup = BukkitFarmEntityLookup,
) {
    private val zoneKey = NamespacedKey(plugin, "farm_bird_zone")
    private val sequenceKey = NamespacedKey(plugin, "farm_bird_sequence")
    private val indexKey = NamespacedKey(plugin, "farm_bird_index")
    private val defeatedKey = NamespacedKey(plugin, "farm_bird_defeated")
    private val ids = mutableMapOf<String, MutableSet<UUID>>()
    private val reconciledSequences = mutableMapOf<String, Long>()
    private val pendingDamagePlans = FarmAsyncPlanGate()

    fun owns(entity: Entity): Boolean = entity.persistentDataContainer.has(zoneKey, PersistentDataType.STRING)

    fun initialize(runtime: FarmRuntime): Boolean {
        if (!active(runtime)) return false
        if (runtime.state.specialIncident != null && runtime.state.incidentRequired > 0) return true
        val available = beds.discover(runtime)
        val required = runtime.settings.specialIncidents.birdCount(available.size)
        val requested = (required * runtime.settings.specialIncidents.birdSpawnMultiplier).coerceAtMost(64)
        val anchors = FarmBirdPlanner.select(available, requested, runtime.state.sequence)
        if (anchors.isEmpty()) {
            port.log(
                Level.WARNING,
                "Could not start farm bird incident: zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                    "reason=no_bird_anchors discovered_beds=${available.size} requested=$requested",
            )
            debug.event(
                "farm_birds_unavailable", "zone" to runtime.settings.id, "sequence" to runtime.state.sequence,
                "reason" to "no_bird_anchors", "beds" to available.size, "requested" to requested,
            )
            return false
        }
        runtime.state = runtime.state.copy(
            incidentRequired = required.coerceAtMost(anchors.size),
            specialIncident = FarmSpecialIncidentState(plots = anchors),
        )
        port.persistAsync()
        debug.event(
            "farm_birds_initialized",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "birds" to anchors.size,
            "required" to required.coerceAtMost(anchors.size),
            "beds" to available.size,
        )
        return true
    }

    fun ensure(runtime: FarmRuntime) {
        if (!active(runtime)) {
            if (hasLifecycle(runtime.settings.id)) clear(runtime.settings.id, "inactive")
            return
        }
        if (!initialize(runtime)) return
        reconcile(runtime)
        val players = port.players(runtime.region)
        val active = activeBirds(runtime).filter { bird ->
            if (runtime.region.contains(bird.location)) true else {
                bird.remove()
                ids[runtime.settings.id]?.remove(bird.uniqueId)
                false
            }
        }.toMutableList()
        if (players.isEmpty()) {
            active.filterIsInstance<Mob>().forEach { it.isAware = false }
            return
        }
        active.filterIsInstance<Mob>().forEach { it.isAware = true }
        val desired = (runtime.state.specialIncident?.plots.orEmpty().size - runtime.state.incidentProgress).coerceAtLeast(0)
        val used = active.mapNotNullTo(mutableSetOf()) { index(it) }
        val anchors = runtime.state.specialIncident?.plots.orEmpty()
        for (index in anchors.indices) {
            if (active.size >= desired) break
            if (index in used) continue
            val anchor = anchors.getOrNull(index) ?: continue
            spawn(runtime, index, anchor)?.let { bird ->
                ids.getOrPut(runtime.settings.id, ::mutableSetOf) += bird.uniqueId
                active += bird
            }
        }
    }

    fun eatCrops(runtime: FarmRuntime) {
        if (!active(runtime) || !port.allowInteraction(
                "farm-birds-eat:${runtime.settings.id}:${runtime.state.sequence}",
                runtime.settings.specialIncidents.birdEatIntervalSeconds * 1_000L,
            )
        ) return
        val sequence = runtime.state.sequence
        val zoneId = runtime.settings.id
        if (!pendingDamagePlans.acquire(zoneId, sequence)) return
        val already = runtime.state.specialDamagedCrops.mapTo(hashSetOf()) { it.position }
        val allBeds = beds.discover(runtime)
        val safety = runtime.settings.damageSafety
        val remaining = FarmDamageBudget.remaining(
            allBeds.size, already.size, safety.maximumPercent, safety.minimumRemaining, safety.birdMaximum,
        )
        if (remaining == 0) {
            pendingDamagePlans.release(zoneId, sequence)
            return
        }
        val radiusSquared = runtime.settings.specialIncidents.birdEatRadius.let { it * it }
        val birdLocations = activeBirds(runtime).map { Triple(it.location.x, it.location.y, it.location.z) }
        val candidates = allBeds.filterNot(already::contains)
        val token = port.lifecycleToken()
        val scheduled = port.runAsync(token) {
            val selected = runCatching {
                val available = candidates.toMutableSet()
                birdLocations.mapNotNull { location ->
                    available.minByOrNull { plot -> horizontalDistanceSquared(location.first, location.third, plot) }
                        ?.takeIf { horizontalDistanceSquared(location.first, location.third, it) <= radiusSquared }
                        ?.also(available::remove)
                }.take(remaining)
            }.getOrElse { failure ->
                pendingDamagePlans.release(zoneId, sequence)
                port.log(Level.SEVERE, "Could not plan farm bird crop damage for $zoneId", failure)
                return@runAsync
            }
            val returned = port.runSync(token) {
                pendingDamagePlans.release(zoneId, sequence)
                if (!active(runtime) || runtime.state.sequence != sequence) return@runSync
                val soils = selected.mapNotNull(FarmPlotPosition::block)
                ledger.captureActiveCrops(soils, runtime.settings.id)
                val damage = soils.mapNotNull { soil ->
                    val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
                    if (crop.type.name !in runtime.settings.crops) return@mapNotNull null
                    val entry = FarmCropDamage(soil.toFarmPlotPosition(), crop.type.name)
                    crop.setType(org.bukkit.Material.AIR, false)
                    if (settings().particles) crop.world.spawnParticle(
                        Particle.CLOUD, crop.location.add(0.5, 0.6, 0.5), 3, 0.2, 0.15, 0.2, 0.01,
                    )
                    entry
                }
                if (damage.isNotEmpty()) {
                    runtime.state = runtime.state.copy(specialDamagedCrops = runtime.state.specialDamagedCrops + damage)
                    port.persistAsync()
                    debug.event("farm_birds_ate_crops", "zone" to runtime.settings.id, "count" to damage.size)
                }
            }
            if (!returned) pendingDamagePlans.release(zoneId, sequence)
        }
        if (!scheduled) pendingDamagePlans.release(zoneId, sequence)
    }

    fun onDamage(event: EntityDamageEvent, runtimes: Collection<FarmRuntime>): Boolean {
        if (!owns(event.entity)) return false
        val cancelledBeforeResolution = event.isCancelled
        event.isCancelled = true
        val damage = event as? EntityDamageByEntityEvent ?: return true
        val ranged = damage.damager is Projectile
        val player = when (val damager = damage.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        } ?: return true
        val runtime = runtime(event.entity, runtimes) ?: return true
        val allowed = active(runtime) && port.hasAccess(player, runtime.settings.permission) &&
            runtime.region.contains(event.entity.location)
        debug.event(
            "farm_bird_damage_hit",
            "zone" to runtime.settings.id,
            "player" to player.name,
            "allowed" to allowed,
            "ranged" to ranged,
            "cancelled_before_resolution" to cancelledBeforeResolution,
        )
        if (!allowed) return true
        if (defeat(event.entity, runtime, player, ranged)) {
            (damage.damager as? AbstractArrow)?.remove()
        }
        return true
    }

    /**
     * Resolve ranged hits at the projectile collision boundary instead of relying on
     * the later damage event. Protected farm regions may cancel entity damage after
     * the projectile has visibly hit, which previously made the flock impossible to
     * shoot down. Cancelling the vanilla hit also prevents a second contribution from
     * a subsequent death event.
     */
    fun onProjectileHit(event: ProjectileHitEvent, runtimes: Collection<FarmRuntime>): Boolean {
        val bird = event.hitEntity ?: return false
        if (!owns(bird)) return false
        event.isCancelled = true
        val arrow = event.entity as? AbstractArrow ?: return true
        val player = arrow.shooter as? Player ?: return true
        val runtime = runtime(bird, runtimes) ?: return true
        val allowed = active(runtime) && port.hasAccess(player, runtime.settings.permission) &&
            runtime.region.contains(bird.location)
        debug.event(
            "farm_bird_projectile_hit",
            "zone" to runtime.settings.id,
            "player" to player.name,
            "allowed" to allowed,
            "projectile" to arrow.type.name,
        )
        if (!allowed) return true
        if (defeat(bird, runtime, player, ranged = true)) arrow.remove()
        return true
    }

    fun onDeath(event: EntityDeathEvent, runtimes: Collection<FarmRuntime>): Boolean {
        if (!owns(event.entity)) return false
        event.drops.clear()
        event.droppedExp = 0
        if (event.entity.persistentDataContainer.has(defeatedKey, PersistentDataType.BYTE)) return true
        val zoneId = event.entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) ?: return true
        ids[zoneId]?.remove(event.entity.uniqueId)
        val runtime = runtime(event.entity, runtimes) ?: return true
        val damage = event.entity.lastDamageCause as? EntityDamageByEntityEvent
        val player = when (val damager = damage?.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        } ?: return true
        if (!active(runtime) || !port.hasAccess(player, runtime.settings.permission)) return true
        event.entity.persistentDataContainer.set(defeatedKey, PersistentDataType.BYTE, 1)
        recordDefeat(runtime, player, ranged = damage?.damager is Projectile)
        return true
    }

    fun clear(zoneId: String, reason: String) {
        ids.remove(zoneId).orEmpty().forEach { id -> Bukkit.getEntity(id)?.remove() }
        reconciledSequences.remove(zoneId)
        pendingDamagePlans.clearZone(zoneId)
        debug.event("farm_birds_cleared", "zone" to zoneId, "reason" to reason)
    }

    fun cleanup(reason: String) {
        entityLookup.inAllWorlds().filter(::owns).forEach(Entity::remove)
        ids.clear()
        reconciledSequences.clear()
        pendingDamagePlans.clear()
        debug.event("farm_birds_cleanup", "reason" to reason)
    }

    private fun reconcile(runtime: FarmRuntime) {
        if (reconciledSequences[runtime.settings.id] == runtime.state.sequence) return
        val retained = mutableSetOf<UUID>()
        val indices = mutableSetOf<Int>()
        entityLookup.inWorld(runtime.region.world).filter(::owns).forEach { entity ->
            val same = entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) == runtime.settings.id &&
                entity.persistentDataContainer.get(sequenceKey, PersistentDataType.LONG) == runtime.state.sequence
            val index = index(entity)
            if (!same || index == null || index !in 0 until runtime.state.incidentRequired || !indices.add(index)) entity.remove()
            else retained += entity.uniqueId
        }
        ids[runtime.settings.id] = retained
        reconciledSequences[runtime.settings.id] = runtime.state.sequence
    }

    private fun spawn(runtime: FarmRuntime, index: Int, plot: FarmPlotPosition): LivingEntity? {
        val soil = plot.block() ?: return null
        if (!FarmSurfacePolicy.isOutdoorBed(soil)) return null
        val base = soil.location.add(0.5, runtime.settings.specialIncidents.birdSpawnHeight, 0.5)
        val location = (0..8).asSequence().map { base.clone().add(0.0, it.toDouble(), 0.0) }
            .firstOrNull { it.block.isPassable && it.clone().add(0.0, 1.0, 0.0).block.isPassable } ?: return null
        val bird = runtime.region.world.spawn(location, Parrot::class.java) { entity ->
            entity.isPersistent = false
            entity.removeWhenFarAway = false
            // Projectile collision is part of this objective. A non-collidable parrot can
            // be rendered normally while arrows pass through it on Paper/Purpur.
            entity.isCollidable = true
            entity.isGlowing = true
            entity.customName(locale.render(MessageKey.FARM_BIRD_NAME))
            entity.isCustomNameVisible = false
            entity.getAttribute(Attribute.MAX_HEALTH)?.baseValue = runtime.settings.specialIncidents.birdHealth
            entity.getAttribute(Attribute.FLYING_SPEED)?.baseValue = runtime.settings.specialIncidents.birdFlyingSpeed
            entity.health = runtime.settings.specialIncidents.birdHealth
            entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, runtime.settings.id)
            entity.persistentDataContainer.set(sequenceKey, PersistentDataType.LONG, runtime.state.sequence)
            entity.persistentDataContainer.set(indexKey, PersistentDataType.INTEGER, index)
        }
        return bird
    }

    /** Resolve the objective ourselves so a later protection listener cannot undo it. */
    private fun defeat(bird: Entity, runtime: FarmRuntime, player: Player, ranged: Boolean): Boolean {
        val data = bird.persistentDataContainer
        if (data.has(defeatedKey, PersistentDataType.BYTE)) return false
        data.set(defeatedKey, PersistentDataType.BYTE, 1)
        ids[runtime.settings.id]?.remove(bird.uniqueId)
        if (settings().particles) {
            bird.world.spawnParticle(Particle.CRIT, bird.location.add(0.0, 0.35, 0.0), 12, 0.25, 0.2, 0.25, 0.08)
            bird.world.spawnParticle(Particle.CLOUD, bird.location, 5, 0.2, 0.15, 0.2, 0.03)
        }
        if (settings().sounds) player.playSound(bird.location, Sound.ENTITY_ARROW_HIT_PLAYER, 0.75f, 1.35f)
        bird.remove()
        recordDefeat(runtime, player, ranged)
        return true
    }

    private fun recordDefeat(runtime: FarmRuntime, player: Player, ranged: Boolean) {
        val contribution = if (ranged) runtime.settings.specialIncidents.birdRangedContribution
        else runtime.settings.specialIncidents.birdMeleeContribution
        transitions.apply(runtime, FarmShiftEngine.defeatBird(runtime.state, player.uniqueId, contribution), player)
        if (settings().sounds) {
            player.playSound(player.location, Sound.ENTITY_PARROT_DEATH, 0.75f, if (ranged) 1.25f else 0.9f)
        }
        debug.event(
            "farm_bird_defeated",
            "zone" to runtime.settings.id,
            "player" to player.name,
            "ranged" to ranged,
            "contribution" to contribution,
        )
    }

    private fun active(runtime: FarmRuntime): Boolean =
        runtime.state.phase == FarmPhase.INCIDENT && runtime.state.incidentType == FarmIncidentType.BIRDS

    private fun activeBirds(runtime: FarmRuntime): List<LivingEntity> = ids[runtime.settings.id].orEmpty().mapNotNull { id ->
        Bukkit.getEntity(id) as? LivingEntity
    }.filter { it.isValid && !it.isDead }

    private fun runtime(entity: Entity, runtimes: Collection<FarmRuntime>): FarmRuntime? {
        val zoneId = entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) ?: return null
        val sequence = entity.persistentDataContainer.get(sequenceKey, PersistentDataType.LONG) ?: return null
        return runtimes.firstOrNull { it.settings.id == zoneId && it.state.sequence == sequence }
    }

    private fun index(entity: Entity): Int? = entity.persistentDataContainer.get(indexKey, PersistentDataType.INTEGER)

    private fun hasLifecycle(zoneId: String): Boolean = zoneId in ids || zoneId in reconciledSequences

    private fun horizontalDistanceSquared(location: Location, plot: FarmPlotPosition): Double {
        if (location.world?.name != plot.world) return Double.POSITIVE_INFINITY
        val dx = location.x - (plot.x + 0.5)
        val dz = location.z - (plot.z + 0.5)
        return dx * dx + dz * dz
    }

    private fun horizontalDistanceSquared(x: Double, z: Double, plot: FarmPlotPosition): Double {
        val dx = x - (plot.x + 0.5)
        val dz = z - (plot.z + 0.5)
        return dx * dx + dz * dz
    }
}
