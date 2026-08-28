package ru.ruscrafting.farms.paper.platform

import org.bukkit.entity.LivingEntity

/** Applies Paper's distance-despawn policy without owning mob gameplay. */
internal fun interface FarmMobDespawnPolicy {
    fun setRemoveWhenFarAway(entity: LivingEntity, value: Boolean)
}

internal object PaperFarmMobDespawnPolicy : FarmMobDespawnPolicy {
    override fun setRemoveWhenFarAway(entity: LivingEntity, value: Boolean) {
        entity.removeWhenFarAway = value
    }
}
