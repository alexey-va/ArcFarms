package ru.ruscrafting.farms.paper.platform

import io.papermc.paper.entity.TeleportFlag
import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.util.Vector
import ru.ruscrafting.farms.domain.FarmMotionVector
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmRaidSeatFollower

/** Moves raid seats without ejecting and re-adding their rider every tick. */
internal fun interface FarmRaidSeatMotion {
    fun move(seat: ArmorStand, target: Location, leaderVelocity: Vector)
}

internal object PaperFarmRaidSeatMotion : FarmRaidSeatMotion {
    override fun move(seat: ArmorStand, target: Location, leaderVelocity: Vector) {
        if (seat.world !== target.world || seat.location.distanceSquared(target) > MAX_SMOOTH_CORRECTION_SQUARED) {
            seat.teleport(
                target,
                PlayerTeleportEvent.TeleportCause.PLUGIN,
                TeleportFlag.EntityState.RETAIN_PASSENGERS,
            )
            seat.velocity = Vector()
            return
        }
        val current = seat.location
        val velocity = FarmRaidSeatFollower.velocity(
            FarmPointPosition(current.world.name, current.x, current.y, current.z),
            FarmPointPosition(target.world.name, target.x, target.y, target.z),
            FarmMotionVector(leaderVelocity.x, leaderVelocity.y, leaderVelocity.z),
            CORRECTION_FACTOR,
            MAXIMUM_CORRECTION,
        )
        seat.velocity = Vector(velocity.x, velocity.y, velocity.z)
    }

    private const val MAX_SMOOTH_CORRECTION_SQUARED = 36.0
    private const val CORRECTION_FACTOR = 0.18
    private const val MAXIMUM_CORRECTION = 0.06
}
