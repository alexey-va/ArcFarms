package ru.ruscrafting.farms.paper.farm.expedition

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Player

/** Shared short lived surface pulse for every underground entry. */
internal object FarmUndergroundSurfacePillar {
    fun render(player: Player, base: Location, color: Color, markerHeight: Int) {
        val center = base.clone().toCenterLocation().add(0.0, 1.0, 0.0)
        val steps = markerHeight * 2
        for (step in 0..steps) {
            player.spawnParticle(
                Particle.DUST,
                center.clone().add(0.0, step * 0.5, 0.0),
                if (step == 0 || step == steps) 4 else 2,
                0.22,
                0.12,
                0.22,
                0.0,
                Particle.DustOptions(color, if (step % 4 == 0) 2.2f else 1.7f),
                true,
            )
        }
    }
}
