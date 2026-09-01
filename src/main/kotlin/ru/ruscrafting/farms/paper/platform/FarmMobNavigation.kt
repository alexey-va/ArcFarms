package ru.ruscrafting.farms.paper.platform

import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Mob

internal interface FarmMobNavigation {
    fun moveTo(mob: Mob, target: Entity, speed: Double)
    fun moveTo(mob: Mob, target: Location, speed: Double)
}

internal object PaperFarmMobNavigation : FarmMobNavigation {
    override fun moveTo(mob: Mob, target: Entity, speed: Double) {
        mob.pathfinder.moveTo(target, speed)
    }

    override fun moveTo(mob: Mob, target: Location, speed: Double) {
        mob.pathfinder.moveTo(target, speed)
    }
}
