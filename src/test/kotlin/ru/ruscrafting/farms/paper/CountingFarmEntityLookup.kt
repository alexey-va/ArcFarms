package ru.ruscrafting.farms.paper

import org.bukkit.World
import org.bukkit.entity.Entity

internal class CountingFarmEntityLookup : FarmEntityLookup {
    var worldScans: Int = 0
        private set
    var globalScans: Int = 0
        private set

    override fun inWorld(world: World): List<Entity> {
        worldScans++
        return world.entities
    }

    override fun inAllWorlds(): List<Entity> {
        globalScans++
        return org.bukkit.Bukkit.getWorlds().flatMap(World::getEntities)
    }
}
