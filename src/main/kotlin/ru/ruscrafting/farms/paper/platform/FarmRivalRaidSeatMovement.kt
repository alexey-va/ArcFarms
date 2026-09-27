package ru.ruscrafting.farms.paper.platform

import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.event.player.PlayerTeleportEvent

/** One-purpose Paper boundary for moving a live rider seat. */
internal fun interface FarmRivalRaidSeatMovement {
    fun move(seat: ArmorStand, destination: Location): Boolean
}

internal object PaperFarmRivalRaidSeatMovement : FarmRivalRaidSeatMovement {
    // Paper 1.21.11 retains passengers by default; EntityState.RETAIN_PASSENGERS is deprecated since 1.21.10.
    override fun move(seat: ArmorStand, destination: Location): Boolean =
        seat.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
}
