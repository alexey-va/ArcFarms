package ru.ruscrafting.farms.paper

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

/** Applies night only to participants; the shared world clock is untouched. */
internal class FarmNightShiftController {
    private val zones = mutableMapOf<String, MutableSet<UUID>>()

    fun sync(zoneId: String, players: Collection<Player>, playerTime: Long) {
        val active = zones.getOrPut(zoneId, ::mutableSetOf)
        val expected = players.mapTo(hashSetOf(), Player::getUniqueId)
        (active - expected).forEach { id ->
            Bukkit.getPlayer(id)?.resetPlayerTime()
        }
        players.forEach { player ->
            if (active.add(player.uniqueId)) player.setPlayerTime(playerTime, false)
        }
        active.retainAll(expected)
        if (active.isEmpty()) zones.remove(zoneId)
    }

    fun clear(player: Player) {
        val changed = zones.values.any { it.remove(player.uniqueId) }
        zones.entries.removeIf { it.value.isEmpty() }
        if (changed) player.resetPlayerTime()
    }

    fun clearZone(zoneId: String) {
        zones.remove(zoneId).orEmpty().forEach { id -> Bukkit.getPlayer(id)?.resetPlayerTime() }
    }

    fun clearAll(players: Collection<Player>) {
        val active = zones.values.flatten().toSet()
        players.filter { it.uniqueId in active }.forEach(Player::resetPlayerTime)
        zones.clear()
    }
}
