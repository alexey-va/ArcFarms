package ru.ruscrafting.farms.paper.worksite

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Chicken
import org.bukkit.entity.Entity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID

/** A real vanilla leash from an invisible, fixed anchor to its operator. Owned on the Paper thread. */
internal class WorksiteCrankTethers(private val key: NamespacedKey, private val plugin: Plugin? = null) {
    private val anchors = mutableMapOf<Pair<String, UUID>, UUID>()
    private val privateViewers = mutableMapOf<UUID, Set<UUID>>()

    fun owns(entity: Entity) = entity.persistentDataContainer.has(key, PersistentDataType.STRING)
    fun participants(scope: String) = anchors.keys.filter { it.first == scope }.map { it.second }
    fun hasScope(scope: String) = anchors.keys.any { it.first == scope }

    /** Returns true only when a new anchor was attached. Attachment failure leaves no hidden mob. */
    fun attach(scope: String, player: Player, at: Location, viewers: Collection<Player>? = null): Result<Boolean> = runCatching {
        require(viewers == null || plugin != null) { "Private tethers require their owning plugin" }
        val id = scope to player.uniqueId
        val existing = anchors[id]?.let(Bukkit::getEntity) as? Mob
        if (existing != null && existing.isValid) {
            if (existing.world !== at.world || existing.location.distanceSquared(at) > .01) existing.teleport(at)
            if (!existing.isLeashed || runCatching { existing.leashHolder }.getOrNull() != player) {
                check(existing.setLeashHolder(player)) { "Leash attachment was rejected" }
            }
            syncViewers(existing, viewers)
            return@runCatching false
        }
        anchors.remove(id)?.let(privateViewers::remove)
        val anchor = at.world.spawn(at, Chicken::class.java) { mob ->
            mob.persistentDataContainer.set(key, PersistentDataType.STRING, scope)
            mob.isPersistent = false
            mob.isInvulnerable = true
            mob.isSilent = true
            mob.isCollidable = false
            mob.isInvisible = true
            mob.setAI(false)
            mob.setGravity(false)
            if (viewers != null) mob.isVisibleByDefault = false
        }
        try {
            // The owner releases this passive anchor as soon as its nearby operator leaves.
            check(anchor.setLeashHolder(player)) { "Leash attachment was rejected" }
            anchors[id] = anchor.uniqueId
            syncViewers(anchor, viewers)
            true
        } catch (failure: Throwable) {
            anchors.remove(id)
            privateViewers.remove(anchor.uniqueId)
            anchor.remove()
            throw failure
        }
    }

    private fun syncViewers(anchor: Entity, viewers: Collection<Player>?) {
        if (viewers == null) return
        val owner = requireNotNull(plugin)
        val next = viewers.associateBy(Player::getUniqueId)
        val previous = privateViewers.put(anchor.uniqueId, next.keys) ?: emptySet()
        (previous - next.keys).mapNotNull(Bukkit::getPlayer).forEach { it.hideEntity(owner, anchor) }
        (next.keys - previous).forEach { next.getValue(it).showEntity(owner, anchor) }
    }

    fun release(scope: String, playerId: UUID) { anchors.remove(scope to playerId)?.let { id ->
        privateViewers.remove(id)
        Bukkit.getEntity(id)?.remove()
    } }
    fun clear(scope: String) { participants(scope).forEach { release(scope, it) } }
    fun removeOrphans(entities: Iterable<Entity>) {
        val live = anchors.values.toHashSet()
        entities.filter { owns(it) && it.uniqueId !in live }.forEach(Entity::remove)
    }
    fun cleanup(entities: Iterable<Entity>) {
        anchors.values.toList().forEach { Bukkit.getEntity(it)?.remove() }
        anchors.clear()
        privateViewers.clear()
        removeOrphans(entities)
    }
}
