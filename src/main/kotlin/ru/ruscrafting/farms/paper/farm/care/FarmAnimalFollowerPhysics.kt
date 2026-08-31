package ru.ruscrafting.farms.paper.farm.care

import org.bukkit.entity.Mob
import org.bukkit.entity.Player

/** Applies a bounded catch-up impulse without teleporting a rescued farm animal. */
internal fun pullFarmAnimalTowardHolder(
    mob: Mob,
    holder: Player,
    followDistance: Double,
    followSpeed: Double,
    impulseBase: Double,
    impulsePerBlock: Double,
    impulseMax: Double,
    smoothing: Double,
) {
    if (!mob.isOnGround || mob.world != holder.world) return
    val delta = holder.location.toVector().subtract(mob.location.toVector()).setY(0.0)
    val distance = delta.length()
    if (distance <= followDistance) return
    val speed = ((impulseBase + distance * impulsePerBlock) * (followSpeed / 1.25))
        .coerceAtMost(impulseMax)
    val current = mob.velocity
    val pull = delta.normalize().multiply(speed)
    mob.velocity = current.multiply(smoothing).setX(pull.x).setZ(pull.z)
}
