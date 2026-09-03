package ru.ruscrafting.farms.paper.platform

import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Mob

internal interface FarmMobNavigation {
    fun moveTo(mob: Mob, target: Entity, speed: Double): Boolean
    fun moveTo(mob: Mob, target: Location, speed: Double): Boolean
}

internal object PaperFarmMobNavigation : FarmMobNavigation {
    override fun moveTo(mob: Mob, target: Entity, speed: Double): Boolean =
        mob.pathfinder.moveTo(target, speed)

    override fun moveTo(mob: Mob, target: Location, speed: Double): Boolean =
        mob.pathfinder.moveTo(target, speed)
}
