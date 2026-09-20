package ru.ruscrafting.farms.paper.worksite

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Chicken
import org.bukkit.entity.Entity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/** A real vanilla leash from an invisible, fixed anchor to its operator. Owned on the Paper thread. */
internal class WorksiteCrankTethers(private val key: NamespacedKey) {
    private val anchors = mutableMapOf<Pair<String, UUID>, UUID>()

    fun owns(entity: Entity) = entity.persistentDataContainer.has(key, PersistentDataType.STRING)
    fun participants(scope: String) = anchors.keys.filter { it.first == scope }.map { it.second }
    fun hasScope(scope: String) = anchors.keys.any { it.first == scope }

    /** Returns true only when a new anchor was attached. Attachment failure leaves no hidden mob. */
    fun attach(scope: String, player: Player, at: Location): Result<Boolean> = runCatching {
        val id = scope to player.uniqueId
        val existing = anchors[id]?.let(Bukkit::getEntity) as? Mob
        if (existing != null && existing.isValid) {
            if (existing.world !== at.world || existing.location.distanceSquared(at) > .01) existing.teleport(at)
            if (!existing.isLeashed || runCatching { existing.leashHolder }.getOrNull() != player) {
                check(existing.setLeashHolder(player)) { "Leash attachment was rejected" }
            }
            return@runCatching false
        }
        anchors.remove(id)
        val anchor = at.world.spawn(at, Chicken::class.java)
        try {
            anchor.persistentDataContainer.set(key, PersistentDataType.STRING, scope)
            anchor.isPersistent = false
            anchor.isInvulnerable = true
            anchor.isSilent = true
            anchor.isCollidable = false
            anchor.isInvisible = true
            anchor.setAI(false)
            anchor.setGravity(false)
            // The owner releases this passive anchor as soon as its nearby operator leaves.
            check(anchor.setLeashHolder(player)) { "Leash attachment was rejected" }
            anchors[id] = anchor.uniqueId
            true
        } catch (failure: Throwable) {
            anchor.remove()
            throw failure
        }
    }

    fun release(scope: String, playerId: UUID) { anchors.remove(scope to playerId)?.let(Bukkit::getEntity)?.remove() }
    fun clear(scope: String) { participants(scope).forEach { release(scope, it) } }
    fun removeOrphans(entities: Iterable<Entity>) {
        val live = anchors.values.toHashSet()
        entities.filter { owns(it) && it.uniqueId !in live }.forEach(Entity::remove)
    }
    fun cleanup(entities: Iterable<Entity>) {
        anchors.values.toList().forEach { Bukkit.getEntity(it)?.remove() }
        anchors.clear()
        removeOrphans(entities)
    }
}
