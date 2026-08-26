package ru.ruscrafting.farms.paper

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Entity

/**
 * Main-thread-only lookup seam for lifecycle reconciliation of transient farm entities.
 * Gameplay ticks must resolve tracked UUIDs directly; broad scans are reserved for the
 * first reconciliation of a lifecycle and explicit reload/shutdown cleanup.
 */
internal interface FarmEntityLookup {
    fun inWorld(world: World): List<Entity>

    fun inAllWorlds(): List<Entity>
}

internal object BukkitFarmEntityLookup : FarmEntityLookup {
    override fun inWorld(world: World): List<Entity> = world.entities

    override fun inAllWorlds(): List<Entity> = Bukkit.getWorlds().flatMap(World::getEntities)
}
