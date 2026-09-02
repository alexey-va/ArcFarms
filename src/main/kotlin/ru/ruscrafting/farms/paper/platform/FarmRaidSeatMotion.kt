package ru.ruscrafting.farms.paper.platform

import io.papermc.paper.entity.TeleportFlag
import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.event.player.PlayerTeleportEvent

/** Moves raid seats without ejecting and re-adding their rider every tick. */
internal fun interface FarmRaidSeatMotion {
    fun move(seat: ArmorStand, target: Location)
}

internal object PaperFarmRaidSeatMotion : FarmRaidSeatMotion {
    override fun move(seat: ArmorStand, target: Location) {
        if (seat.world !== target.world || seat.location.distanceSquared(target) > MAX_SMOOTH_CORRECTION_SQUARED) {
            seat.teleport(
                target,
                PlayerTeleportEvent.TeleportCause.PLUGIN,
                TeleportFlag.EntityState.RETAIN_PASSENGERS,
            )
            seat.velocity = org.bukkit.util.Vector()
            return
        }
        seat.velocity = target.toVector().subtract(seat.location.toVector())
    }

    private const val MAX_SMOOTH_CORRECTION_SQUARED = 36.0
}
