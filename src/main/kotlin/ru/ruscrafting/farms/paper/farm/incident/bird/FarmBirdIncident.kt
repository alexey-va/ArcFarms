package ru.ruscrafting.farms.paper.farm.incident.bird

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Parrot
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmBirdPlanner
import ru.ruscrafting.farms.domain.FarmCropDamage
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
import ru.ruscrafting.farms.paper.farm.FarmIncidentBedProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.placement.FarmSurfacePolicy
import java.util.UUID

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
    private val ids = mutableMapOf<String, MutableSet<UUID>>()
    private val reconciledSequences = mutableMapOf<String, Long>()

    fun owns(entity: Entity): Boolean = entity.persistentDataContainer.has(zoneKey, PersistentDataType.STRING)

    fun initialize(runtime: FarmRuntime): Boolean {
        if (!active(runtime)) return false
        if (runtime.state.specialIncident != null && runtime.state.incidentRequired > 0) return true
        val available = beds.discover(runtime)
        val total = runtime.settings.specialIncidents.birdCount(available.size)
        val anchors = FarmBirdPlanner.select(available, total, runtime.state.sequence)
        if (anchors.isEmpty()) return false
        runtime.state = runtime.state.copy(
            incidentRequired = anchors.size,
            specialIncident = FarmSpecialIncidentState(plots = anchors),
        )
        port.persistAsync()
        debug.event(
            "farm_birds_initialized",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "birds" to anchors.size,
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
        val desired = (runtime.state.incidentRequired - runtime.state.incidentProgress).coerceAtLeast(0)
        val used = active.mapNotNullTo(mutableSetOf()) { index(it) }
        val anchors = runtime.state.specialIncident?.plots.orEmpty()
        for (index in 0 until runtime.state.incidentRequired) {
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
        val already = runtime.state.specialDamagedCrops.mapTo(hashSetOf()) { it.position }
        val available = beds.discover(runtime).filterNot(already::contains).toMutableSet()
        val damage = mutableListOf<FarmCropDamage>()
        activeBirds(runtime).forEach { bird ->
            val radius = runtime.settings.specialIncidents.birdEatRadius
            val plot = available.minByOrNull { candidate -> horizontalDistanceSquared(bird.location, candidate) }
                ?.takeIf { horizontalDistanceSquared(bird.location, it) <= radius * radius }
                ?: return@forEach
            val soil = plot.block() ?: return@forEach
            val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
            if (crop.type.name !in runtime.settings.crops) return@forEach
            ledger.captureActiveCrop(soil, runtime.settings.id)
            damage += FarmCropDamage(plot, crop.type.name)
            available.remove(plot)
            crop.setType(org.bukkit.Material.AIR, false)
            if (settings().particles) crop.world.spawnParticle(Particle.CLOUD, crop.location.add(0.5, 0.6, 0.5), 3, 0.2, 0.15, 0.2, 0.01)
        }
        if (damage.isNotEmpty()) {
            runtime.state = runtime.state.copy(specialDamagedCrops = runtime.state.specialDamagedCrops + damage)
            port.persistAsync()
            debug.event("farm_birds_ate_crops", "zone" to runtime.settings.id, "count" to damage.size)
        }
    }

    fun onDamage(event: EntityDamageEvent, runtimes: Collection<FarmRuntime>): Boolean {
        if (!owns(event.entity)) return false
        event.isCancelled = true
        val damage = event as? EntityDamageByEntityEvent ?: return true
        val player = when (val damager = damage.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        } ?: return true
        val runtime = runtime(event.entity, runtimes) ?: return true
        if (active(runtime) && port.hasAccess(player, runtime.settings.permission) && runtime.region.contains(event.entity.location)) {
            event.isCancelled = false
        }
        return true
    }

    fun onDeath(event: EntityDeathEvent, runtimes: Collection<FarmRuntime>): Boolean {
        if (!owns(event.entity)) return false
        event.drops.clear()
        event.droppedExp = 0
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
        val ranged = damage?.damager is Projectile
        val contribution = if (ranged) runtime.settings.specialIncidents.birdRangedContribution
        else runtime.settings.specialIncidents.birdMeleeContribution
        transitions.apply(runtime, FarmShiftEngine.defeatBird(runtime.state, player.uniqueId, contribution), player)
        if (settings().sounds) player.playSound(player.location, Sound.ENTITY_PARROT_DEATH, 0.75f, if (ranged) 1.25f else 0.9f)
        debug.event(
            "farm_bird_defeated",
            "zone" to runtime.settings.id,
            "player" to player.name,
            "ranged" to ranged,
            "contribution" to contribution,
        )
        return true
    }

    fun clear(zoneId: String, reason: String) {
        entityLookup.inAllWorlds().filter { entity ->
            entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) == zoneId
        }.forEach(Entity::remove)
        ids.remove(zoneId)
        reconciledSequences.remove(zoneId)
        debug.event("farm_birds_cleared", "zone" to zoneId, "reason" to reason)
    }

    fun cleanup(reason: String) {
        entityLookup.inAllWorlds().filter(::owns).forEach(Entity::remove)
        ids.clear()
        reconciledSequences.clear()
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
            entity.isCollidable = false
            entity.isGlowing = true
            entity.customName(locale.render(MessageKey.FARM_BIRD_NAME))
            entity.isCustomNameVisible = false
            entity.getAttribute(Attribute.MAX_HEALTH)?.baseValue = runtime.settings.specialIncidents.birdHealth
            entity.health = runtime.settings.specialIncidents.birdHealth
            entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, runtime.settings.id)
            entity.persistentDataContainer.set(sequenceKey, PersistentDataType.LONG, runtime.state.sequence)
            entity.persistentDataContainer.set(indexKey, PersistentDataType.INTEGER, index)
        }
        return bird
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
}
