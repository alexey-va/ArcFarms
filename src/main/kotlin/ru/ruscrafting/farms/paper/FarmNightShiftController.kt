package ru.ruscrafting.farms.paper

import net.kyori.adventure.util.TriState
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.attribute.Attribute
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Light
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.FarmSpecialIncidentSettings
import ru.ruscrafting.farms.domain.FarmPlayerTimeTransition
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.paper.farm.placement.FarmSurfacePolicy
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

internal data class FarmNightShiftSyncResult(
    val activePatrols: Int,
    val spawnedPatrols: Int,
    val removedPatrols: Int,
)

private val FARM_LIGHT_REPLACEABLE = setOf(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR, Material.LIGHT)

/** Owns participant time and every ephemeral patrol entity for a night shift. */
internal class FarmNightShiftController(
    plugin: Plugin,
    private val entityLookup: FarmEntityLookup = BukkitFarmEntityLookup,
) {
    private data class PatrolKey(val zoneId: String, val index: Int)
    private data class LightCell(val world: String, val x: Int, val y: Int, val z: Int)
    private data class ReceivingSafeZone(val world: String, val x: Double, val z: Double, val radiusSquared: Double) {
        fun contains(location: Location): Boolean {
            if (location.world.name != world) return false
            val dx = location.x - x
            val dz = location.z - z
            return dx * dx + dz * dz <= radiusSquared
        }
    }
    private data class PlayerTimeState(
        var zoneId: String,
        var current: Long,
        var target: Long,
        var maximumStep: Long,
        var returning: Boolean = false,
    )

    private val playerTimes = mutableMapOf<UUID, PlayerTimeState>()
    private val patrols = mutableMapOf<PatrolKey, UUID>()
    private val patrolRouteSteps = mutableMapOf<PatrolKey, Int>()
    private val patrolNextRouteAt = mutableMapOf<PatrolKey, Long>()
    private val patrolLights = mutableMapOf<PatrolKey, LightCell>()
    private val externalLights = mutableMapOf<String, LightCell>()
    private val lightOwners = mutableMapOf<LightCell, MutableSet<String>>()
    private val reconciledSequences = mutableMapOf<String, Long>()
    private val receivingSafeZones = mutableMapOf<String, ReceivingSafeZone>()
    private val zoneKey = NamespacedKey(plugin, "farm_night_patrol_zone")
    private val sequenceKey = NamespacedKey(plugin, "farm_night_patrol_sequence")
    private val indexKey = NamespacedKey(plugin, "farm_night_patrol_index")
    private val lightRecoveryKey = NamespacedKey(plugin, "farm_night_lights_v1")

    fun sync(
        zoneId: String,
        sequence: Long,
        region: ActivityRegion,
        players: Collection<Player>,
        playerTime: Long,
        anchors: Collection<FarmPointPosition>,
        receiving: FarmPointPosition,
        settings: FarmSpecialIncidentSettings,
        particles: Boolean,
    ): FarmNightShiftSyncResult {
        reconcileLifecycle(zoneId, sequence, region)
        if (players.isEmpty() || anchors.isEmpty()) {
            return FarmNightShiftSyncResult(0, 0, clearPatrols(zoneId))
        }
        syncPlayerTime(zoneId, players, playerTime, settings.nightTimeTransitionSeconds)
        val safeZone = ReceivingSafeZone(
            receiving.world,
            receiving.x,
            receiving.z,
            settings.nightPatrolReceivingSafeRadius * settings.nightPatrolReceivingSafeRadius,
        )
        receivingSafeZones[zoneId] = safeZone

        val desired = anchors.filter { point ->
            val location = Location(region.world, point.x, point.y, point.z)
            FarmSurfacePolicy.isSurfaceSpawn(location) && !safeZone.contains(location)
        }
        var removed = 0
        patrols.keys.filter { it.zoneId == zoneId && it.index !in desired.indices }.forEach { key ->
            releaseLight(key)
            clearRoute(key)
            patrols.remove(key)?.let(Bukkit::getEntity)?.remove()
            removed++
        }
        var spawned = 0
        var active = 0
        desired.forEachIndexed { index, point ->
            val key = PatrolKey(zoneId, index)
            val anchor = Location(region.world, point.x, point.y, point.z)
            var patrol = patrols[key]?.let(Bukkit::getEntity) as? Monster
            if (patrol != null && (!patrol.isValid || patrol.isDead || patrolSequence(patrol) != sequence)) {
                releaseLight(key)
                clearRoute(key)
                patrol.remove()
                patrols.remove(key)
                patrol = null
                removed++
            }
            if (patrol == null && canSpawn(anchor, players, settings.nightPatrolSpawnMinPlayerDistance)) {
                patrol = spawnPatrol(zoneId, sequence, index, anchor, settings)
                if (patrol != null) {
                    patrols[key] = patrol.uniqueId
                    spawned++
                }
            }
            patrol ?: return@forEachIndexed
            val roamSquared = settings.nightPatrolRoamRadius * settings.nightPatrolRoamRadius
            if (patrol.world != anchor.world) {
                releaseLight(key)
                clearRoute(key)
                patrol.remove()
                patrols.remove(key)
                removed++
                return@forEachIndexed
            }
            if ((patrol.target as? Player)?.location?.let(safeZone::contains) == true) patrol.target = null
            val outsidePatrolArea = !region.contains(patrol.location) || safeZone.contains(patrol.location) ||
                patrol.location.distanceSquared(anchor) > roamSquared
            if (outsidePatrolArea) {
                patrol.target = null
                patrol.pathfinder.findPath(anchor)?.let { path -> patrol.pathfinder.moveTo(path, 1.0) }
                patrolNextRouteAt[key] = System.currentTimeMillis() + settings.nightPatrolPathRefreshSeconds * 1_000L
            } else if (patrol.target == null &&
                (System.currentTimeMillis() >= patrolNextRouteAt.getOrDefault(key, 0L) || !patrol.pathfinder.hasPath())
            ) {
                routePatrol(key, patrol, anchor, region, sequence, settings, safeZone)
            }
            patrol.fireTicks = 0
            updateLight(key, patrol, region, settings.nightPatrolLightLevel)
            if (particles) {
                players.filter { it.world == patrol.world }.forEach { player ->
                    player.spawnParticle(
                        Particle.SMALL_FLAME,
                        patrol.location.clone().add(0.25, 1.25, 0.0),
                        1,
                        0.04,
                        0.06,
                        0.04,
                        0.0,
                    )
                }
            }
            active++
        }
        return FarmNightShiftSyncResult(active, spawned, removed)
    }

    /** Refreshes actual LIGHT blocks more often than the one-second incident reconciliation. */
    fun updateLights(zoneId: String, region: ActivityRegion, level: Int) {
        patrols.filterKeys { it.zoneId == zoneId }.forEach { (key, id) ->
            val patrol = Bukkit.getEntity(id) as? Monster
            if (patrol == null || !patrol.isValid || patrol.isDead) releaseLight(key)
            else updateLight(key, patrol, region, level)
        }
    }

    /** Removes crash-left light before the active incident is reconciled again. */
    fun onChunkLoad(chunk: Chunk) {
        val recorded = recordedLights(chunk)
        if (recorded.isEmpty()) return
        val retained = recorded.filterTo(linkedSetOf()) { cell ->
            if (lightOwners[cell].isNullOrEmpty()) {
                val block = chunk.world.getBlockAt(cell.x, cell.y, cell.z)
                if (block.type == Material.LIGHT) block.setType(Material.AIR, false)
                false
            } else true
        }
        writeRecordedLights(chunk, retained)
    }

    fun owns(entity: Entity): Boolean = entity.persistentDataContainer.has(zoneKey, PersistentDataType.STRING)

    /** Cancels attacks by an owned patrol after a player reaches the receiving sanctuary. */
    fun protectReceiving(event: EntityDamageByEntityEvent): Boolean {
        val patrol = event.damager as? Monster ?: return false
        if (!owns(patrol) || event.entity !is Player) return false
        val zoneId = patrol.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) ?: return false
        val safeZone = receivingSafeZones[zoneId] ?: return false
        if (!safeZone.contains(event.entity.location)) return false
        event.isCancelled = true
        patrol.target = null
        patrol.pathfinder.stopPathfinding()
        return true
    }

    fun clear(player: Player) {
        playerTimes[player.uniqueId]?.returning = true
    }

    fun syncAmbientTime(
        ownerId: String,
        players: Collection<Player>,
        playerTime: Long,
        transitionSeconds: Int,
    ) = syncPlayerTime(ownerId, players, playerTime, transitionSeconds)

    fun clearAmbientTime(ownerId: String) {
        playerTimes.values.filter { it.zoneId == ownerId }.forEach { it.returning = true }
    }

    fun updateExternalLight(ownerId: String, entity: Entity, level: Int) {
        if (!entity.isValid || level <= 0) {
            releaseExternalLight(ownerId)
            return
        }
        val desired = findLightBlock(entity.location.block) { true }?.let { block ->
            LightCell(block.world.name, block.x, block.y, block.z)
        }
        if (desired == externalLights[ownerId]) {
            desired?.block()?.let { setLight(it, level) }
            return
        }
        releaseExternalLight(ownerId)
        desired ?: return
        val block = desired.block() ?: return
        val owners = lightOwners.getOrPut(desired, ::linkedSetOf)
        owners += externalOwner(ownerId)
        externalLights[ownerId] = desired
        rememberLight(desired)
        setLight(block, level)
    }

    fun releaseExternalLight(ownerId: String) = releaseOwned(externalOwner(ownerId), externalLights.remove(ownerId))

    fun releaseExternalLights(prefix: String) {
        externalLights.keys.filter { it.startsWith(prefix) }.toList().forEach(::releaseExternalLight)
    }

    fun clearZone(zoneId: String) {
        playerTimes.values.filter { it.zoneId == zoneId }.forEach { it.returning = true }
        clearPatrols(zoneId)
        reconciledSequences.remove(zoneId)
        receivingSafeZones.remove(zoneId)
    }

    fun clearAll(players: Collection<Player>) {
        players.filter { it.uniqueId in playerTimes }.forEach(Player::resetPlayerTime)
        playerTimes.clear()
        patrolLights.keys.toList().forEach(::releaseLight)
        externalLights.keys.toList().forEach(::releaseExternalLight)
        entityLookup.inAllWorlds().asSequence().filter(::owns).forEach(Entity::remove)
        patrols.clear()
        patrolRouteSteps.clear()
        patrolNextRouteAt.clear()
        reconciledSequences.clear()
        receivingSafeZones.clear()
        Bukkit.getWorlds().forEach { world -> world.loadedChunks.forEach(::onChunkLoad) }
    }

    fun updatePlayerTimes() {
        val iterator = playerTimes.iterator()
        while (iterator.hasNext()) {
            val (playerId, state) = iterator.next()
            val player = Bukkit.getPlayer(playerId)
            if (player == null || !player.isOnline) {
                iterator.remove()
                continue
            }
            val target = if (state.returning) player.world.time else state.target
            val step = FarmPlayerTimeTransition.step(state.current, target, state.maximumStep)
            state.current = step.time
            player.setPlayerTime(step.time, false)
            if (state.returning && step.reachedTarget) {
                player.resetPlayerTime()
                iterator.remove()
            }
        }
    }

    private fun syncPlayerTime(
        zoneId: String,
        players: Collection<Player>,
        playerTime: Long,
        transitionSeconds: Int,
    ) {
        val expected = players.mapTo(hashSetOf(), Player::getUniqueId)
        playerTimes.filterValues { it.zoneId == zoneId }.filterKeys { it !in expected }.values.forEach {
            it.returning = true
        }
        val maximumStep = FarmPlayerTimeTransition.maximumStep(transitionSeconds, updatesPerSecond = 20)
        players.forEach { player ->
            val existing = playerTimes[player.uniqueId]
            if (existing == null) {
                playerTimes[player.uniqueId] = PlayerTimeState(
                    zoneId = zoneId,
                    current = FarmPlayerTimeTransition.normalize(player.playerTime),
                    target = FarmPlayerTimeTransition.normalize(playerTime),
                    maximumStep = maximumStep,
                )
            } else {
                existing.zoneId = zoneId
                existing.target = FarmPlayerTimeTransition.normalize(playerTime)
                existing.maximumStep = maximumStep
                existing.returning = false
            }
        }
    }

    private fun canSpawn(anchor: Location, players: Collection<Player>, minimumPlayerDistance: Double): Boolean {
        if (!anchor.world.isChunkLoaded(anchor.blockX shr 4, anchor.blockZ shr 4)) return false
        if (!FarmSurfacePolicy.isSurfaceSpawn(anchor)) return false
        val minimumSquared = minimumPlayerDistance * minimumPlayerDistance
        return players.none { player -> player.world == anchor.world && player.location.distanceSquared(anchor) < minimumSquared }
    }

    private fun spawnPatrol(
        zoneId: String,
        sequence: Long,
        index: Int,
        anchor: Location,
        settings: FarmSpecialIncidentSettings,
    ): Monster? {
        val entity = anchor.world.spawnEntity(anchor, EntityType.valueOf(settings.nightPatrolEntity)) as? Monster ?: return null
        entity.isPersistent = false
        entity.removeWhenFarAway = false
        entity.setDespawnInPeacefulOverride(TriState.FALSE)
        entity.setCanPickupItems(false)
        entity.setAI(true)
        entity.isAware = true
        entity.isInvulnerable = true
        entity.isCollidable = true
        entity.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = settings.nightPatrolMovementSpeed
        entity.getAttribute(Attribute.FOLLOW_RANGE)?.baseValue = settings.nightPatrolFollowRange
        entity.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = settings.nightPatrolAttackDamage
        entity.equipment.setItemInMainHand(ItemStack(MaterialRules.material(settings.nightPatrolHeldItem)), true)
        entity.equipment.itemInMainHandDropChance = 0.0f
        entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, zoneId)
        entity.persistentDataContainer.set(sequenceKey, PersistentDataType.LONG, sequence)
        entity.persistentDataContainer.set(indexKey, PersistentDataType.INTEGER, index)
        return entity
    }

    private fun clearPatrols(zoneId: String): Int {
        var removed = 0
        patrols.keys.filter { it.zoneId == zoneId }.forEach { key ->
            releaseLight(key)
            clearRoute(key)
            patrols.remove(key)?.let(Bukkit::getEntity)?.remove()
            removed++
        }
        return removed
    }

    private fun reconcileLifecycle(zoneId: String, sequence: Long, region: ActivityRegion) {
        if (reconciledSequences[zoneId] == sequence) return
        patrols.keys.removeIf { it.zoneId == zoneId }
        entityLookup.inWorld(region.world).filter { entity ->
            entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) == zoneId
        }.groupBy { entity ->
            val currentSequence = entity.persistentDataContainer.get(sequenceKey, PersistentDataType.LONG)
            val index = entity.persistentDataContainer.get(indexKey, PersistentDataType.INTEGER)
            if (entity is Monster && currentSequence == sequence && index != null && index >= 0) {
                PatrolKey(zoneId, index)
            } else null
        }.forEach { (key, entities) ->
            if (key == null) {
                entities.forEach(Entity::remove)
                return@forEach
            }
            val keep = entities.minByOrNull { it.uniqueId.toString() }
            entities.filter { it !== keep }.forEach(Entity::remove)
            if (keep != null) patrols[key] = keep.uniqueId
        }
        reconciledSequences[zoneId] = sequence
    }

    private fun routePatrol(
        key: PatrolKey,
        patrol: Monster,
        anchor: Location,
        region: ActivityRegion,
        sequence: Long,
        settings: FarmSpecialIncidentSettings,
        safeZone: ReceivingSafeZone,
    ) {
        val step = patrolRouteSteps.getOrDefault(key, 0)
        val destination = findPatrolDestination(
            anchor,
            region,
            sequence,
            key.index,
            step,
            settings.nightPatrolRoamRadius,
            safeZone,
        )
        val moved = destination?.let { point ->
            patrol.pathfinder.findPath(point)?.let { path -> patrol.pathfinder.moveTo(path, 1.0) }
        } == true
        patrolRouteSteps[key] = step + 1
        patrolNextRouteAt[key] = System.currentTimeMillis() +
            (if (moved) settings.nightPatrolPathRefreshSeconds else 2).coerceAtLeast(2) * 1_000L
    }

    private fun findPatrolDestination(
        anchor: Location,
        region: ActivityRegion,
        sequence: Long,
        patrolIndex: Int,
        step: Int,
        roamRadius: Double,
        safeZone: ReceivingSafeZone,
    ): Location? {
        val random = Random(sequence xor (patrolIndex.toLong() shl 32) xor step.toLong())
        repeat(16) {
            val angle = random.nextDouble(0.0, Math.PI * 2.0)
            val radius = roamRadius * random.nextDouble(0.35, 0.95)
            val x = kotlin.math.floor(anchor.x + cos(angle) * radius).toInt()
            val z = kotlin.math.floor(anchor.z + sin(angle) * radius).toInt()
            if (!anchor.world.isChunkLoaded(x shr 4, z shr 4)) return@repeat
            for (floorY in anchor.blockY + 2 downTo anchor.blockY - 3) {
                val floor = anchor.world.getBlockAt(x, floorY, z)
                val feet = floor.getRelative(BlockFace.UP)
                val head = feet.getRelative(BlockFace.UP)
                if (
                    !floor.isPassable && feet.isPassable && head.isPassable && region.contains(feet.location) &&
                    FarmSurfacePolicy.isSurfaceSpawn(feet.location) && !safeZone.contains(feet.location)
                ) {
                    return feet.location.add(0.5, 0.0, 0.5)
                }
            }
        }
        return null
    }

    private fun clearRoute(key: PatrolKey) {
        patrolRouteSteps.remove(key)
        patrolNextRouteAt.remove(key)
    }

    private fun updateLight(key: PatrolKey, patrol: Monster, region: ActivityRegion, level: Int) {
        if (level <= 0) {
            releaseLight(key)
            return
        }
        val desired = findLightBlock(patrol.location.block) { block -> region.contains(block.location) }?.let { block ->
            LightCell(block.world.name, block.x, block.y, block.z)
        }
        if (desired == patrolLights[key]) {
            desired?.block()?.let { setLight(it, level) }
            return
        }
        releaseLight(key)
        desired ?: return
        val block = desired.block() ?: return
        if (block.type !in FARM_LIGHT_REPLACEABLE) return
        val owners = lightOwners.getOrPut(desired, ::linkedSetOf)
        owners += patrolOwner(key)
        patrolLights[key] = desired
        rememberLight(desired)
        setLight(block, level)
    }

    private fun findLightBlock(origin: Block, allowed: (Block) -> Boolean): Block? = listOf(
        origin.getRelative(BlockFace.UP),
        origin.getRelative(BlockFace.UP, 2),
        origin.getRelative(BlockFace.NORTH).getRelative(BlockFace.UP),
        origin.getRelative(BlockFace.SOUTH).getRelative(BlockFace.UP),
        origin.getRelative(BlockFace.EAST).getRelative(BlockFace.UP),
        origin.getRelative(BlockFace.WEST).getRelative(BlockFace.UP),
    ).firstOrNull { block ->
        allowed(block) && block.world.isChunkLoaded(block.x shr 4, block.z shr 4) &&
            block.type in FARM_LIGHT_REPLACEABLE
    }

    private fun setLight(block: Block, level: Int) {
        val data = (block.blockData as? Light) ?: (Material.LIGHT.createBlockData() as Light)
        if (block.type != Material.LIGHT || data.level != level) {
            data.level = level
            block.setBlockData(data, false)
        }
    }

    private fun releaseLight(key: PatrolKey) {
        releaseOwned(patrolOwner(key), patrolLights.remove(key))
    }

    private fun releaseOwned(owner: String, cell: LightCell?) {
        cell ?: return
        val owners = lightOwners[cell] ?: return
        owners.remove(owner)
        if (owners.isNotEmpty()) return
        lightOwners.remove(cell)
        cell.block()?.takeIf { it.type == Material.LIGHT }?.setType(Material.AIR, false)
        forgetLight(cell)
    }

    private fun patrolOwner(key: PatrolKey): String = "patrol:${key.zoneId}:${key.index}"

    private fun externalOwner(ownerId: String): String = "external:$ownerId"

    private fun rememberLight(cell: LightCell) {
        val chunk = cell.chunk() ?: return
        writeRecordedLights(chunk, recordedLights(chunk) + cell)
    }

    private fun forgetLight(cell: LightCell) {
        val chunk = cell.chunk() ?: return
        writeRecordedLights(chunk, recordedLights(chunk) - cell)
    }

    private fun recordedLights(chunk: Chunk): Set<LightCell> {
        val raw = chunk.persistentDataContainer.get(lightRecoveryKey, PersistentDataType.STRING) ?: return emptySet()
        if (raw.length > 4_096) return emptySet()
        return raw.split(';').asSequence().take(32).mapNotNull { entry ->
            val parts = entry.split(',')
            if (parts.size != 3) return@mapNotNull null
            val x = parts[0].toIntOrNull() ?: return@mapNotNull null
            val y = parts[1].toIntOrNull() ?: return@mapNotNull null
            val z = parts[2].toIntOrNull() ?: return@mapNotNull null
            if (x shr 4 != chunk.x || z shr 4 != chunk.z || y !in chunk.world.minHeight until chunk.world.maxHeight) return@mapNotNull null
            LightCell(chunk.world.name, x, y, z)
        }.toCollection(linkedSetOf())
    }

    private fun writeRecordedLights(chunk: Chunk, cells: Collection<LightCell>) {
        if (cells.isEmpty()) chunk.persistentDataContainer.remove(lightRecoveryKey)
        else chunk.persistentDataContainer.set(
            lightRecoveryKey,
            PersistentDataType.STRING,
            cells.take(32).joinToString(";") { "${it.x},${it.y},${it.z}" },
        )
    }

    private fun LightCell.block(): Block? {
        val loadedWorld = Bukkit.getWorld(world) ?: return null
        if (!loadedWorld.isChunkLoaded(x shr 4, z shr 4)) return null
        return loadedWorld.getBlockAt(x, y, z)
    }

    private fun LightCell.chunk(): Chunk? = Bukkit.getWorld(world)?.takeIf { it.isChunkLoaded(x shr 4, z shr 4) }
        ?.getChunkAt(x shr 4, z shr 4)

    private fun patrolSequence(entity: Entity): Long? =
        entity.persistentDataContainer.get(sequenceKey, PersistentDataType.LONG)
}
