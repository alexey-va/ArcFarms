package ru.ruscrafting.farms.paper.mine.incident.rescue

import org.bukkit.Sound
import org.bukkit.entity.Player

/** Small, stable feedback contract for the two maze actions. */
internal object MineLostMinerMazeSounds {
    fun playEntry(player: Player) {
        player.playSound(player.location, Sound.BLOCK_ROOTED_DIRT_BREAK, 0.85f, 0.72f)
        player.playSound(player.location, Sound.BLOCK_PORTAL_TRAVEL, 0.35f, 1.35f)
    }

    fun playFound(player: Player) {
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.15f)
        player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 0.35f, 1.4f)
    }
}
