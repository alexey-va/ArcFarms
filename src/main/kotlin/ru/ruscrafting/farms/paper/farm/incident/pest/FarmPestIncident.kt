package ru.ruscrafting.farms.paper.farm.incident.pest

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.FarmCropDamage
import ru.ruscrafting.farms.domain.FarmIncidentPlanner
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPestNest
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.FarmPestDamagePolicy
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.block
import ru.ruscrafting.farms.paper.farm.FarmIncidentBedProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.location
import ru.ruscrafting.farms.paper.toFarmPlotPosition
import java.util.UUID
import java.util.random.RandomGenerator

private data class PestNestKey(val zoneId: String, val position: FarmPlotPosition)

private enum class PestNestRole { DISPLAY, HITBOX }

/** Complete classic pest incident: nests, mobs, crop damage, recovery intent and interactions. */
internal class FarmPestIncident(
    private val plugin: Plugin,
    private val settings: () -> ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
    private val port: WorksiteRuntimePort,
    private val blockLedger: FarmBlockLedger,
    private val blockRegistry: FarmBlockRegistry,
    private val beds: FarmIncidentBedProvider,
    private val transitions: FarmTransitionSink,
    private val random: RandomGenerator,
) {
    private val pestZoneKey = NamespacedKey(plugin, "farm_pest_zone")
    private val pestSequenceKey = NamespacedKey(plugin, "farm_pest_sequence")
    private val nestZoneKey = NamespacedKey(plugin, "farm_pest_nest_zone")
    private val nestSequenceKey = NamespacedKey(plugin, "farm_pest_nest_sequence")
    private val nestXKey = NamespacedKey(plugin, "farm_pest_nest_x")
    private val nestYKey = NamespacedKey(plugin, "farm_pest_nest_y")
    private val nestZKey = NamespacedKey(plugin, "farm_pest_nest_z")
    private val nestRoleKey = NamespacedKey(plugin, "farm_pest_nest_role")
    private val pestIds = mutableMapOf<String, MutableSet<UUID>>()
    private val nestEntities = mutableMapOf<PestNestKey, MutableSet<UUID>>()

    fun ownsPest(entity: Entity): Boolean = entity.persistentDataContainer.has(pestZoneKey, PersistentDataType.STRING)

    fun ownsNest(entity: Entity): Boolean = entity.persistentDataContainer.has(nestZoneKey, PersistentDataType.STRING)

    fun nestZoneId(entity: Entity): String? = entity.persistentDataContainer.get(nestZoneKey, PersistentDataType.STRING)

    fun activeCount(runtime: FarmRuntime): Int = activePests(runtime).size

    fun handlePestDamage(event: EntityDamageEvent, runtimes: Collection<FarmRuntime>): Boolean {
        val entity = event.entity
        val zoneId = entity.persistentDataContainer.get(pestZoneKey, PersistentDataType.STRING) ?: return false
        event.isCancelled = true
        val sequence = entity.persistentDataContainer.get(pestSequenceKey, PersistentDataType.LONG) ?: return true
        val attacker = (event as? EntityDamageByEntityEvent)?.let { damage ->
            when (val damager = damage.damager) {
                is Player -> damager
                is Projectile -> damager.shooter as? Player
                else -> null
            }
        } ?: return true
        val runtime = runtimes.firstOrNull { it.settings.id == zoneId } ?: return true
        val allowed = FarmPestDamagePolicy.allows(
            hasAccess = port.hasAccess(attacker, runtime.settings.permission),
            insideRegion = runtime.region.contains(entity.location),
            pestIncidentActive = active(runtime),
            sequenceMatches = runtime.state.sequence == sequence,
        )
        if (allowed) event.isCancelled = false
        debug.event(
            if (allowed) "farm_pest_damage_allowed" else "farm_pest_damage_rejected",
            "zone" to zoneId,
            "player" to attacker.name,
            "entity" to entity.type,
        )
        return true
    }

    fun onDeath(event: EntityDeathEvent, runtimes: Collection<FarmRuntime>): Boolean {
        val entity = event.entity
        val zoneId = entity.persistentDataContainer.get(pestZoneKey, PersistentDataType.STRING) ?: return false
        val sequence = entity.persistentDataContainer.get(pestSequenceKey, PersistentDataType.LONG) ?: return false
        pestIds[zoneId]?.remove(entity.uniqueId)
        event.drops.clear()
        event.droppedExp = 0
        val runtime = runtimes.firstOrNull { it.settings.id == zoneId }
        val killer = entity.killer
        if (
            runtime == null || !active(runtime) || runtime.state.sequence != sequence || killer == null ||
            !port.hasAccess(killer, runtime.settings.permission) || !runtime.region.contains(entity.location)
        ) {
            debug.event(
                "farm_pest_death_ignored",
                "zone" to zoneId,
                "sequence" to sequence,
                "killer" to killer?.name,
                "reason" to "inactive_or_environment",
            )
            return true
        }
        if (runtime.state.orderId !in runtime.orders) return true
        debug.event(
            "farm_pest_killed",
            "zone" to zoneId,
            "sequence" to sequence,
            "player" to killer.name,
            "entity" to entity.type,
        )
        transitions.apply(runtime, FarmShiftEngine.defeatPest(runtime.state, killer.uniqueId), killer)
        return true
    }

    fun damageNest(runtime: FarmRuntime, entity: Entity, attacker: Player): Boolean {
        val identity = nestIdentity(entity) ?: return false
        if (identity.first != runtime.settings.id || !active(runtime) || !runtime.region.contains(entity.location)) return true
        if (!port.hasAccess(attacker, runtime.settings.permission) || runtime.state.orderId !in runtime.orders) return true
        val position = identity.second
        val result = FarmShiftEngine.damagePestNest(runtime.state, position, attacker.uniqueId)
        if (!result.accepted) return true
        val remainingHealth = result.state.pestNests.firstOrNull { it.position == position }?.health ?: 0
        if (remainingHealth == 0) {
            removeNest(PestNestKey(runtime.settings.id, position), "destroyed")
            if (settings().sounds) attacker.playSound(entity.location, Sound.BLOCK_WOOD_BREAK, 0.9f, 0.75f)
        } else if (settings().sounds) {
            attacker.playSound(entity.location, Sound.BLOCK_WOOD_HIT, 0.7f, 0.85f)
        }
        if (settings().particles) {
            attacker.spawnParticle(
                Particle.BLOCK,
                entity.location.clone().add(0.0, 0.5, 0.0),
                8,
                0.35,
                0.3,
                0.35,
                0.04,
                Material.MANGROVE_ROOTS.createBlockData(),
            )
        }
        port.sendActionBar(
            attacker,
            if (remainingHealth == 0) MessageKey.FARM_PEST_NEST_DESTROYED else MessageKey.FARM_PEST_NEST_DAMAGED,
            mapOf("health" to locale.text(remainingHealth)),
        )
        debug.event(
            "farm_pest_nest_damaged",
            "zone" to runtime.settings.id,
            "player" to attacker.name,
            "health" to remainingHealth,
            "x" to position.x,
            "y" to position.y,
            "z" to position.z,
        )
        transitions.apply(runtime, result, attacker)
        return true
    }

    fun ensure(runtime: FarmRuntime) {
        if (!active(runtime)) {
            clear(runtime, "incident_inactive")
            return
        }
        ensureNests(runtime)
        ensureNestEntities(runtime)
        val nearbyPlayers = port.players(runtime.region)
        var pests = activePests(runtime).toMutableList()
        if (nearbyPlayers.isEmpty()) {
            pests.filterIsInstance<Mob>().forEach { pest ->
                pest.target = null
                pest.isAware = false
            }
            return
        }
        pests.forEach { pest ->
            (pest as? Mob)?.let { mob ->
                mob.isAware = true
                mob.target = nearbyPlayers.minByOrNull { it.location.distanceSquared(pest.location) }
            }
            if (!runtime.region.contains(pest.location)) {
                pestIds[runtime.settings.id]?.remove(pest.uniqueId)
                pest.remove()
                debug.event(
                    "farm_pest_replaced",
                    "zone" to runtime.settings.id,
                    "uuid" to pest.uniqueId,
                    "reason" to "outside_region",
                )
            }
        }
        pests = activePests(runtime).toMutableList()
        if (pests.size > runtime.state.pestAlive) {
            removePests(runtime, pests.drop(runtime.state.pestAlive), "surplus")
            pests = pests.take(runtime.state.pestAlive).toMutableList()
        }
        repeat((runtime.state.pestAlive - pests.size).coerceAtLeast(0)) { index ->
            val nest = runtime.state.pestNests.getOrNull(index % runtime.state.pestNests.size.coerceAtLeast(1))
            val preferred = nest?.position?.location()?.add(0.5, 1.0, 0.5) ?: nearbyPlayers[index % nearbyPlayers.size].location
            spawnPest(runtime, preferred, nearbyPlayers[index % nearbyPlayers.size])?.let(pests::add)
        }
        if (!port.allowInteraction("farm-pest-nest-spawn:${runtime.settings.id}", runtime.settings.pestSpawnIntervalSeconds * 1_000L)) {
            return
        }
        if (pests.size >= runtime.settings.pestMaxAlive) return
        val nest = runtime.state.pestNests
            .filter { it.spawned < runtime.settings.pestSpawnsPerNest }
            .shuffled(kotlin.random.Random(runtime.state.sequence + runtime.state.incidentProgress + pests.size))
            .firstOrNull { random.nextInt(100) < runtime.settings.pestSpawnChancePercent }
            ?: return
        val target = nearbyPlayers.minByOrNull { player ->
            nest.position.location()?.distanceSquared(player.location) ?: Double.MAX_VALUE
        }
        val location = nest.position.location()?.add(0.5, 1.0, 0.5) ?: return
        if (spawnPest(runtime, location, target) != null) {
            val next = runtime.state.copy(
                pestAlive = runtime.state.pestAlive + 1,
                pestNests = runtime.state.pestNests.map {
                    if (it.position == nest.position) it.copy(spawned = it.spawned + 1) else it
                },
            )
            transitions.apply(runtime, EngineResult(next, accepted = true), null)
            debug.event(
                "farm_pest_spawned_from_nest",
                "zone" to runtime.settings.id,
                "x" to nest.position.x,
                "y" to nest.position.y,
                "z" to nest.position.z,
                "spawned" to nest.spawned + 1,
                "limit" to runtime.settings.pestSpawnsPerNest,
            )
        }
    }

    fun eatCrops(runtime: FarmRuntime) {
        var state = runtime.state
        var changed = false
        activePests(runtime).forEach { pest ->
            if (!port.allowInteraction("farm-pest-eat:${pest.uniqueId}", PEST_EAT_INTERVAL_MILLIS)) return@forEach
            val radius = runtime.settings.pestEatRadius
            val targets = buildList {
                for (x in pest.location.blockX - radius..pest.location.blockX + radius) {
                    for (z in pest.location.blockZ - radius..pest.location.blockZ + radius) {
                        for (y in pest.location.blockY - 1..pest.location.blockY + 1) {
                            val crop = runtime.region.world.getBlockAt(x, y, z)
                            if (
                                runtime.region.contains(crop.location) && crop.type.name in runtime.settings.crops &&
                                !MaterialRules.isFixedBlockCrop(crop.type)
                            ) add(crop)
                        }
                    }
                }
            }.distinctBy { "${it.world.name}:${it.x}:${it.y}:${it.z}" }
                .sortedBy { it.location.distanceSquared(pest.location) }
                .take(runtime.settings.pestEatPerPulse)
            targets.forEach { target ->
                val soil = target.getRelative(org.bukkit.block.BlockFace.DOWN)
                val position = soil.toFarmPlotPosition()
                val crop = target.type.name
                blockLedger.captureActiveCropIfPresent(soil, runtime.settings.id)
                if (state.pestDamagedCrops.size < MAX_DAMAGED_CROPS && state.pestDamagedCrops.none { it.position == position }) {
                    state = state.copy(pestDamagedCrops = state.pestDamagedCrops + FarmCropDamage(position, crop))
                    changed = true
                }
                blockRegistry.addBeds(runtime.settings.id, listOf(position))
                val cropData = target.blockData
                target.setType(Material.AIR, false)
                if (settings().particles) {
                    runtime.region.world.spawnParticle(
                        Particle.BLOCK,
                        target.location.toCenterLocation().add(0.0, 0.65, 0.0),
                        4,
                        0.22,
                        0.3,
                        0.22,
                        0.03,
                        cropData,
                    )
                }
                debug.event(
                    "farm_pest_ate_crop",
                    "zone" to runtime.settings.id,
                    "sequence" to runtime.state.sequence,
                    "pest" to pest.uniqueId,
                    "crop" to crop,
                    "x" to target.x,
                    "y" to target.y,
                    "z" to target.z,
                )
            }
            if (targets.isNotEmpty() && settings().sounds) {
                runtime.region.world.playSound(pest.location, Sound.ENTITY_SILVERFISH_AMBIENT, 0.7f, 0.75f)
            }
        }
        if (changed) transitions.apply(runtime, EngineResult(state, accepted = true), null)
    }

    fun removeNestAt(runtime: FarmRuntime, position: FarmPlotPosition, reason: String) {
        removeNest(PestNestKey(runtime.settings.id, position), reason)
    }

    fun clear(runtime: FarmRuntime, reason: String) {
        removePests(runtime, activePests(runtime), reason)
        nestEntities.keys.filter { it.zoneId == runtime.settings.id }.toList().forEach { removeNest(it, reason) }
        loadedNests(runtime.settings.id).forEach(Entity::remove)
    }

    fun cleanup(reason: String) {
        val owned = Bukkit.getWorlds().flatMap { it.entities }.filter { ownsPest(it) || ownsNest(it) }
        owned.forEach(Entity::remove)
        pestIds.clear()
        nestEntities.clear()
        if (owned.isNotEmpty()) debug.event("farm_pest_cleanup", "count" to owned.size, "reason" to reason)
    }

    private fun ensureNests(runtime: FarmRuntime) {
        if (runtime.state.pestNestsInitialized) return
        val candidates = beds.discover(runtime).filter { position ->
            position.block()?.getRelative(org.bukkit.block.BlockFace.UP)?.type?.name?.let(runtime.settings.crops::contains) == true
        }
        val centers = FarmIncidentPlanner.dispersedCenters(
            candidates,
            runtime.settings.pestNestCount,
            runtime.state.sequence * 53L + 11L,
        )
        val damages = runtime.state.pestDamagedCrops.toMutableList()
        val nests = centers.mapNotNull { position ->
            val soil = position.block() ?: return@mapNotNull null
            val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
            if (crop.type.name !in runtime.settings.crops) return@mapNotNull null
            blockLedger.captureActiveCropIfPresent(soil, runtime.settings.id)
            if (damages.none { it.position == position }) damages += FarmCropDamage(position, crop.type.name)
            crop.setType(Material.AIR, false)
            FarmPestNest(position, runtime.settings.pestNestHealth)
        }
        var next = runtime.state.copy(
            pestNestsInitialized = true,
            pestNests = nests,
            pestDamagedCrops = damages,
            incidentRequired = nests.size + runtime.settings.pestSpawnsPerNest * nests.size,
        )
        transitions.apply(runtime, EngineResult(next, accepted = true), null)
        debug.event(
            "farm_pest_nests_created",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "nests" to nests.size,
        )
        if (nests.isEmpty()) {
            if (runtime.state.orderId in runtime.orders) {
                transitions.apply(runtime, FarmShiftEngine.finishPestIncidentIfClear(runtime.state), null)
            }
            return
        }
        val players = port.players(runtime.region)
        var spawnedState = runtime.state
        nests.take(runtime.settings.pestMaxAlive).forEachIndexed { index, nest ->
            val location = nest.position.location()?.add(0.5, 1.0, 0.5) ?: return@forEachIndexed
            if (spawnPest(runtime, location, players.getOrNull(index % players.size.coerceAtLeast(1))) != null) {
                spawnedState = spawnedState.copy(
                    pestAlive = spawnedState.pestAlive + 1,
                    pestNests = spawnedState.pestNests.map {
                        if (it.position == nest.position) it.copy(spawned = 1) else it
                    },
                )
            }
        }
        transitions.apply(runtime, EngineResult(spawnedState, accepted = true), null)
    }

    private fun ensureNestEntities(runtime: FarmRuntime) {
        val activeKeys = runtime.state.pestNests.mapTo(mutableSetOf()) { PestNestKey(runtime.settings.id, it.position) }
        nestEntities.keys.filter { it.zoneId == runtime.settings.id && it !in activeKeys }.toList().forEach {
            removeNest(it, "state_removed")
        }
        loadedNests(runtime.settings.id).filter { nestIdentity(it)?.third != runtime.state.sequence }.forEach(Entity::remove)
        runtime.state.pestNests.forEach { nest ->
            val key = PestNestKey(runtime.settings.id, nest.position)
            val expected = Triple(runtime.settings.id, nest.position, runtime.state.sequence)
            val activeEntities = (nestEntities[key].orEmpty().mapNotNull(Bukkit::getEntity) + loadedNests(runtime.settings.id))
                .distinctBy(Entity::getUniqueId)
                .filter { nestIdentity(it) == expected }
            nestEntities[key] = activeEntities.mapTo(mutableSetOf(), Entity::getUniqueId)
            val correct = activeEntities.count { it is ItemDisplay && nestRole(it) == PestNestRole.DISPLAY } == 1 &&
                activeEntities.count { it is ArmorStand && nestRole(it) == PestNestRole.HITBOX } == 1 &&
                activeEntities.size == NEST_ENTITY_COUNT
            if (correct) return@forEach
            removeNest(key, "reconcile")
            activeEntities.forEach(Entity::remove)
            val base = nest.position.location()?.add(0.5, 1.0, 0.5) ?: return@forEach
            if (!base.world.isChunkLoaded(base.blockX shr 4, base.blockZ shr 4)) return@forEach
            val display = runtime.region.world.spawn(base.clone().add(0.0, 0.25, 0.0), ItemDisplay::class.java) { entity ->
                entity.setItemStack(ItemStack(Material.MANGROVE_ROOTS))
                entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
                entity.viewRange = runtime.settings.displayViewRange
                entity.isGlowing = true
                entity.isPersistent = false
                markNest(entity, runtime, nest.position, PestNestRole.DISPLAY)
            }
            val hitbox = runtime.region.world.spawn(base, ArmorStand::class.java) { entity ->
                entity.isInvisible = true
                entity.setGravity(false)
                entity.isSmall = false
                entity.isPersistent = false
                entity.isInvulnerable = false
                entity.customName(locale.render(MessageKey.FARM_PEST_NEST_NAME))
                entity.isCustomNameVisible = true
                markNest(entity, runtime, nest.position, PestNestRole.HITBOX)
            }
            nestEntities[key] = mutableSetOf(display.uniqueId, hitbox.uniqueId)
        }
    }

    private fun activePests(runtime: FarmRuntime): List<LivingEntity> {
        val matching = runtime.region.world.entities.asSequence().filterIsInstance<LivingEntity>().filter { entity ->
            entity.isValid && entity.persistentDataContainer.get(pestZoneKey, PersistentDataType.STRING) == runtime.settings.id &&
                entity.persistentDataContainer.get(pestSequenceKey, PersistentDataType.LONG) == runtime.state.sequence
        }.sortedBy { it.uniqueId.toString() }.toList()
        pestIds[runtime.settings.id] = matching.mapTo(mutableSetOf(), LivingEntity::getUniqueId)
        return matching
    }

    private fun spawnPest(runtime: FarmRuntime, preferred: Location, target: Player?): LivingEntity? {
        val location = findSpawn(runtime, preferred) ?: run {
            debug.event("farm_pest_spawn_failed", "zone" to runtime.settings.id, "reason" to "no_safe_location")
            return null
        }
        val entity = runtime.region.world.spawnEntity(location, EntityType.valueOf(runtime.settings.pestEntity)) as? LivingEntity
            ?: return null
        entity.persistentDataContainer.set(pestZoneKey, PersistentDataType.STRING, runtime.settings.id)
        entity.persistentDataContainer.set(pestSequenceKey, PersistentDataType.LONG, runtime.state.sequence)
        entity.isPersistent = false
        entity.removeWhenFarAway = false
        entity.isGlowing = true
        (entity as? Mob)?.target = target
        entity.customName(locale.render(MessageKey.FARM_PEST_NAME, target))
        entity.isCustomNameVisible = true
        pestIds.getOrPut(runtime.settings.id) { mutableSetOf() } += entity.uniqueId
        debug.event(
            "farm_pest_spawned",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "entity" to entity.type,
            "uuid" to entity.uniqueId,
            "x" to location.blockX,
            "y" to location.blockY,
            "z" to location.blockZ,
        )
        return entity
    }

    private fun findSpawn(runtime: FarmRuntime, anchor: Location): Location? {
        repeat(MAX_SPAWN_ATTEMPTS) {
            val radius = runtime.settings.pestSpawnRadius
            val x = anchor.blockX + random.nextInt(-radius, radius + 1)
            val z = anchor.blockZ + random.nextInt(-radius, radius + 1)
            if (!runtime.region.world.isChunkLoaded(x shr 4, z shr 4)) return@repeat
            for (y in anchor.blockY - 2..anchor.blockY + 2) {
                val feet = runtime.region.world.getBlockAt(x, y, z)
                val head = runtime.region.world.getBlockAt(x, y + 1, z)
                val floor = runtime.region.world.getBlockAt(x, y - 1, z)
                val candidate = Location(runtime.region.world, x + 0.5, y.toDouble(), z + 0.5)
                if (runtime.region.contains(candidate) && feet.isPassable && head.isPassable && floor.type.isSolid) return candidate
            }
        }
        return null
    }

    private fun removePests(runtime: FarmRuntime, entities: Collection<LivingEntity>, reason: String) {
        entities.forEach { entity ->
            pestIds[runtime.settings.id]?.remove(entity.uniqueId)
            entity.remove()
        }
        if (entities.isNotEmpty()) {
            debug.event("farm_pests_removed", "zone" to runtime.settings.id, "count" to entities.size, "reason" to reason)
        }
    }

    private fun removeNest(key: PestNestKey, reason: String) {
        val tracked = nestEntities.remove(key).orEmpty()
        tracked.forEach { Bukkit.getEntity(it)?.remove() }
        loadedNests(key.zoneId).filter { nestIdentity(it)?.second == key.position }.forEach(Entity::remove)
        if (tracked.isNotEmpty()) {
            debug.event(
                "farm_pest_nest_entities_removed",
                "zone" to key.zoneId,
                "count" to tracked.size,
                "reason" to reason,
            )
        }
    }

    private fun markNest(entity: Entity, runtime: FarmRuntime, position: FarmPlotPosition, role: PestNestRole) {
        entity.persistentDataContainer.set(nestZoneKey, PersistentDataType.STRING, runtime.settings.id)
        entity.persistentDataContainer.set(nestSequenceKey, PersistentDataType.LONG, runtime.state.sequence)
        entity.persistentDataContainer.set(nestXKey, PersistentDataType.INTEGER, position.x)
        entity.persistentDataContainer.set(nestYKey, PersistentDataType.INTEGER, position.y)
        entity.persistentDataContainer.set(nestZKey, PersistentDataType.INTEGER, position.z)
        entity.persistentDataContainer.set(nestRoleKey, PersistentDataType.STRING, role.name)
    }

    private fun nestIdentity(entity: Entity): Triple<String, FarmPlotPosition, Long>? {
        val data = entity.persistentDataContainer
        val zoneId = data.get(nestZoneKey, PersistentDataType.STRING) ?: return null
        val sequence = data.get(nestSequenceKey, PersistentDataType.LONG) ?: return null
        val x = data.get(nestXKey, PersistentDataType.INTEGER) ?: return null
        val y = data.get(nestYKey, PersistentDataType.INTEGER) ?: return null
        val z = data.get(nestZKey, PersistentDataType.INTEGER) ?: return null
        return Triple(zoneId, FarmPlotPosition(entity.world.name, x, y, z), sequence)
    }

    private fun nestRole(entity: Entity): PestNestRole? = entity.persistentDataContainer
        .get(nestRoleKey, PersistentDataType.STRING)
        ?.let { runCatching { PestNestRole.valueOf(it) }.getOrNull() }

    private fun loadedNests(zoneId: String): List<Entity> = Bukkit.getWorlds().asSequence()
        .flatMap { it.entities.asSequence() }
        .filter { entity -> entity.persistentDataContainer.get(nestZoneKey, PersistentDataType.STRING) == zoneId }
        .toList()

    private fun active(runtime: FarmRuntime): Boolean =
        runtime.state.phase == FarmPhase.INCIDENT &&
            (runtime.state.incidentType ?: FarmIncidentType.PESTS) == FarmIncidentType.PESTS

    private companion object {
        const val NEST_ENTITY_COUNT = 2
        const val MAX_DAMAGED_CROPS = 4_096
        const val MAX_SPAWN_ATTEMPTS = 24
        const val PEST_EAT_INTERVAL_MILLIS = 1_000L
    }
}
