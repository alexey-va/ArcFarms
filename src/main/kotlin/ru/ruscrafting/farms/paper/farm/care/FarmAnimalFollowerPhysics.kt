package ru.ruscrafting.farms.paper.farm.care

import org.bukkit.entity.Mob
import org.bukkit.entity.Player

/** Applies a bounded catch-up impulse without teleporting a rescued farm animal. */
internal fun pullFarmAnimalTowardHolder(mob: Mob, holder: Player) {
    if (!mob.isOnGround || mob.world != holder.world) return
    val delta = holder.location.toVector().subtract(mob.location.toVector()).setY(0.0)
    val distance = delta.length()
    if (distance <= 2.0) return
    val speed = (0.16 + distance * 0.025).coerceAtMost(0.42)
    val current = mob.velocity
    val pull = delta.normalize().multiply(speed)
    mob.velocity = current.multiply(0.25).setX(pull.x).setZ(pull.z)
}
