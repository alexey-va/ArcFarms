package ru.ruscrafting.farms.paper.farm.incident.special

import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block

internal object FarmGiantCropHitEffects {
    fun emit(block: Block, crop: Material, progress: Int, particles: Boolean, sounds: Boolean) {
        val center = block.location.toCenterLocation()
        if (particles) {
            block.world.spawnParticle(
                Particle.BLOCK,
                center,
                24,
                0.42,
                0.42,
                0.42,
                0.08,
                crop.createBlockData(),
            )
            block.world.spawnParticle(Particle.EXPLOSION, center, 1, 0.0, 0.0, 0.0, 0.0)
        }
        if (sounds) {
            block.world.playSound(center, Sound.BLOCK_WOOD_BREAK, 0.9f, 0.85f + progress * 0.006f)
            block.world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.45f, 1.45f)
        }
    }
}
