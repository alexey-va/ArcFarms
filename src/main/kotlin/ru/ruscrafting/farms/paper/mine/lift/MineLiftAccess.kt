package ru.ruscrafting.farms.paper.mine.lift

import org.bukkit.Location

/** Narrow maintenance surface for mine scenario owners. */
public interface MineLiftAccess {
    public data class FloorSnapshot(val id: String, val y: Double, val exit: Location)

    public fun floors(): List<FloorSnapshot>
    public fun beginMaintenance(ownerKey: String): Boolean
    public fun endMaintenance(ownerKey: String)
    public fun maintenanceReady(ownerKey: String): Boolean
}
