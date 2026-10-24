package ru.ruscrafting.farms.paper.farm.care

import org.bukkit.entity.FishHook
import org.bukkit.entity.Mob
import org.bukkit.event.entity.ProjectileHitEvent

/** Narrowly-scoped platform mutation for a validated ditch-rescue hook hit. */
internal object FarmRescueHookAttachment {
    fun configureAnimal(animal: Mob, enabled: Boolean) {
        animal.isInvulnerable = !enabled
        animal.isCollidable = enabled
    }

    fun attach(event: ProjectileHitEvent): Boolean {
        val hook = event.entity as? FishHook ?: return false
        val animal = event.hitEntity ?: return false
        event.isCancelled = true
        hook.hookedEntity = animal
        return true
    }
}
