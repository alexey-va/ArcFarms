package ru.ruscrafting.farms.paper.farm.care

import org.bukkit.entity.FishHook
import org.bukkit.event.entity.EntityDamageByEntityEvent

/** Final, narrowly-scoped platform mutation for a validated ditch-rescue hook hit. */
internal object FarmRescueHookAttachment {
    fun attach(event: EntityDamageByEntityEvent): Boolean {
        val hook = event.damager as? FishHook ?: return false
        event.isCancelled = false
        hook.hookedEntity = event.entity
        return true
    }
}
