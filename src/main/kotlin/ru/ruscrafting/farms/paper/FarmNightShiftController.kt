package ru.ruscrafting.farms.paper

import net.kyori.adventure.util.TriState
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.FarmSpecialIncidentSettings
import ru.ruscrafting.farms.domain.FarmPointPosition
import java.util.UUID

internal data class FarmNightShiftSyncResult(
    val activePatrols: Int,
    val spawnedPatrols: Int,
    val removedPatrols: Int,
)

/** Owns participant time and every ephemeral patrol entity for a night shift. */
internal class FarmNightShiftController(plugin: Plugin) {
    private data class PatrolKey(val zoneId: String, val index: Int)

    private val playerZones = mutableMapOf<String, MutableSet<UUID>>()
    private val patrols = mutableMapOf<PatrolKey, UUID>()
    private val zoneKey = NamespacedKey(plugin, "farm_night_patrol_zone")
    private val sequenceKey = NamespacedKey(plugin, "farm_night_patrol_sequence")
    private val indexKey = NamespacedKey(plugin, "farm_night_patrol_index")

    fun sync(
        zoneId: String,
        sequence: Long,
        region: ActivityRegion,
        players: Collection<Player>,
        playerTime: Long,
        anchors: Collection<FarmPointPosition>,
        settings: FarmSpecialIncidentSettings,
        particles: Boolean,
    ): FarmNightShiftSyncResult {
        syncPlayerTime(zoneId, players, playerTime)
        if (players.isEmpty() || settings.nightPatrolCount == 0 || anchors.isEmpty()) {
            return FarmNightShiftSyncResult(0, 0, clearPatrols(zoneId))
        }

        val desired = anchors.take(settings.nightPatrolCount)
        var removed = 0
        patrols.keys.filter { it.zoneId == zoneId && it.index !in desired.indices }.forEach { key ->
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
            if (!region.contains(patrol.location) || patrol.world != anchor.world || patrol.location.distanceSquared(anchor) > roamSquared) {
                patrol.teleport(anchor)
                patrol.target = null
            }
            patrol.fireTicks = 0
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

    fun owns(entity: Entity): Boolean = entity.persistentDataContainer.has(zoneKey, PersistentDataType.STRING)

    fun clear(player: Player) {
        val changed = playerZones.values.any { it.remove(player.uniqueId) }
        playerZones.entries.removeIf { it.value.isEmpty() }
        if (changed) player.resetPlayerTime()
    }

    fun clearZone(zoneId: String) {
        playerZones.remove(zoneId).orEmpty().forEach { id -> Bukkit.getPlayer(id)?.resetPlayerTime() }
        clearPatrols(zoneId)
    }

    fun clearAll(players: Collection<Player>) {
        val activePlayers = playerZones.values.flatten().toSet()
        players.filter { it.uniqueId in activePlayers }.forEach(Player::resetPlayerTime)
        playerZones.clear()
        Bukkit.getWorlds().asSequence().flatMap { it.entities.asSequence() }.filter(::owns).forEach(Entity::remove)
        patrols.clear()
    }

    private fun syncPlayerTime(zoneId: String, players: Collection<Player>, playerTime: Long) {
        val active = playerZones.getOrPut(zoneId, ::mutableSetOf)
        val expected = players.mapTo(hashSetOf(), Player::getUniqueId)
        (active - expected).forEach { id -> Bukkit.getPlayer(id)?.resetPlayerTime() }
        players.forEach { player ->
            if (active.add(player.uniqueId)) player.setPlayerTime(playerTime, false)
        }
        active.retainAll(expected)
        if (active.isEmpty()) playerZones.remove(zoneId)
    }

    private fun canSpawn(anchor: Location, players: Collection<Player>, minimumPlayerDistance: Double): Boolean {
        if (!anchor.world.isChunkLoaded(anchor.blockX shr 4, anchor.blockZ shr 4)) return false
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
            patrols.remove(key)?.let(Bukkit::getEntity)?.remove()
            removed++
        }
        Bukkit.getWorlds().asSequence().flatMap { it.entities.asSequence() }.filter { entity ->
            entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) == zoneId
        }.forEach { entity ->
            entity.remove()
            removed++
        }
        return removed
    }

    private fun patrolSequence(entity: Entity): Long? =
        entity.persistentDataContainer.get(sequenceKey, PersistentDataType.LONG)
}
