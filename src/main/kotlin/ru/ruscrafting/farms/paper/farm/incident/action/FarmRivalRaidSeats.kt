package ru.ruscrafting.farms.paper.farm.incident.action

import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.Ghast
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.domain.FarmRaidSeatPolicy
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.platform.FarmRivalRaidSeatMovement
import java.util.UUID

/** Owns the non-native seat entities required for distinct Ghast rider positions. */
internal class FarmRivalRaidSeats(
    plugin: Plugin,
    private val movement: FarmRivalRaidSeatMovement,
) {
    private val zoneKey = NamespacedKey(plugin, "farm_raid_zone")
    private val sequenceKey = NamespacedKey(plugin, "farm_raid_sequence")
    private val roleKey = NamespacedKey(plugin, "farm_raid_role")
    private val seats = mutableMapOf<String, MutableMap<UUID, UUID>>()

    fun ensure(runtime: FarmRuntime, ghast: Ghast, player: Player): Boolean {
        val byPlayer = seats.getOrPut(runtime.settings.id) { linkedMapOf() }
        val seat = byPlayer[player.uniqueId]?.let { Bukkit.getEntity(it) as? ArmorStand }
            ?: ghast.world.spawn(ghast.location, ArmorStand::class.java) { entity ->
                entity.isPersistent = false
                entity.isVisible = false
                entity.isMarker = true
                entity.setGravity(false)
                entity.isInvulnerable = true
                entity.isSilent = true
                entity.isCollidable = false
                entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, runtime.settings.id)
                entity.persistentDataContainer.set(sequenceKey, PersistentDataType.LONG, runtime.state.sequence)
                entity.persistentDataContainer.set(roleKey, PersistentDataType.STRING, ROLE)
            }.also { byPlayer[player.uniqueId] = it.uniqueId }
        if (player.vehicle !== seat) {
            player.leaveVehicle()
            if (!seat.addPassenger(player)) return false
        }
        return true
    }

    fun sync(runtime: FarmRuntime, participantIds: List<UUID>, ghast: Ghast) {
        if (participantIds.isEmpty()) return
        val byPlayer = seats[runtime.settings.id] ?: return
        val deck = FarmRaidSeatPolicy.deck(
            participantIds.size,
            runtime.settings.rivalRaid.seatSpacing,
            runtime.settings.rivalRaid.seatYOffset,
        )
        participantIds.forEachIndexed { index, playerId ->
            val seat = byPlayer[playerId]?.let { Bukkit.getEntity(it) as? ArmorStand } ?: return@forEachIndexed
            val offset = deck.getOrNull(index) ?: return@forEachIndexed
            movement.move(seat, ghast.location.clone().add(offset.x, offset.y, offset.z))
        }
    }

    fun seatId(zoneId: String, playerId: UUID): UUID? = seats[zoneId]?.get(playerId)

    fun remove(zoneId: String, player: Player) {
        player.leaveVehicle()
        seats[zoneId]?.remove(player.uniqueId)?.let { Bukkit.getEntity(it)?.remove() }
    }

    fun clear(zoneId: String) {
        seats.remove(zoneId)?.values?.forEach { id ->
            org.bukkit.Bukkit.getEntity(id)?.remove()
        }
    }

    fun isSeat(entity: Entity): Boolean = entity.persistentDataContainer.get(roleKey, PersistentDataType.STRING) == ROLE

    fun zoneId(entity: Entity): String? = entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING)

    companion object {
        const val ROLE = "raid_rider_seat"
    }
}
