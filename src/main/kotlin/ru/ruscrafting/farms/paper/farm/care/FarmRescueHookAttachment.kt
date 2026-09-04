package ru.ruscrafting.farms.paper.farm.care

import org.bukkit.entity.FishHook
import org.bukkit.entity.Mob
import org.bukkit.event.entity.EntityDamageByEntityEvent

/** Narrowly-scoped platform mutation for a validated ditch-rescue hook hit. */
internal object FarmRescueHookAttachment {
    fun configureAnimal(animal: Mob, enabled: Boolean) {
        animal.isInvulnerable = !enabled
        animal.isCollidable = enabled
    }

    fun attach(event: EntityDamageByEntityEvent): Boolean {
        val hook = event.damager as? FishHook ?: return false
        event.isCancelled = true
        hook.hookedEntity = event.entity
        return true
    }
}
