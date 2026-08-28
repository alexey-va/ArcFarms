package ru.ruscrafting.farms.paper.platform

import org.bukkit.entity.Entity

/** Owns the exact passenger unlink operation used by farm vehicles. */
internal fun interface FarmVehiclePassengerControl {
    fun ejectAll(entity: Entity): Boolean
}

internal object PaperFarmVehiclePassengerControl : FarmVehiclePassengerControl {
    override fun ejectAll(entity: Entity): Boolean = entity.eject()
}
