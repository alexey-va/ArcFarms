package ru.ruscrafting.farms.paper.platform

import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.event.player.PlayerTeleportEvent

/** One-purpose Paper boundary for moving a live rider seat. */
internal fun interface FarmRivalRaidSeatMovement {
    fun move(seat: ArmorStand, destination: Location): Boolean
}

internal object PaperFarmRivalRaidSeatMovement : FarmRivalRaidSeatMovement {
    override fun move(seat: ArmorStand, destination: Location): Boolean =
        seat.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
}
