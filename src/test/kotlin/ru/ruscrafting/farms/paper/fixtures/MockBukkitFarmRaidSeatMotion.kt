package ru.ruscrafting.farms.paper.fixtures

import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import ru.ruscrafting.farms.paper.platform.FarmRaidSeatMotion

/** MockBukkit does not advance entity velocity; emulate the resulting position while preserving mount bookkeeping. */
internal object MockBukkitFarmRaidSeatMotion : FarmRaidSeatMotion {
    override fun move(seat: ArmorStand, target: Location) {
        val passengers = seat.passengers.toList()
        passengers.forEach { it.leaveVehicle() }
        seat.teleport(target)
        passengers.forEach(seat::addPassenger)
    }
}
