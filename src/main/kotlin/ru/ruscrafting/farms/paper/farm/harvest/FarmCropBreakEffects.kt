package ru.ruscrafting.farms.paper.farm.harvest

import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block

internal data class FarmCropBreakEffectPalette(val primary: Color, val accent: Color)

/** Shared crop burst used by both ordinary fixed fruit and the giant-harvest sculpture. */
internal object FarmCropBreakEffects {
    fun emitHarvest(block: Block, crop: Material, particles: Boolean, sounds: Boolean) {
        check(supportsHarvestBurst(crop)) { "Harvest burst is unsupported for $crop" }
        emit(block, crop, BREAK_PITCH, particles, sounds)
    }

    fun emitGiantHit(block: Block, crop: Material, progress: Int, particles: Boolean, sounds: Boolean) {
        emit(block, crop, (GIANT_PITCH_BASE + progress * GIANT_PITCH_STEP).coerceAtMost(GIANT_PITCH_MAX), particles, sounds)
    }

    fun supportsHarvestBurst(crop: Material): Boolean = crop == Material.MELON || crop == Material.PUMPKIN

    fun palette(crop: Material): FarmCropBreakEffectPalette = when (crop) {
        Material.PUMPKIN -> FarmCropBreakEffectPalette(Color.fromRGB(230, 128, 37), Color.fromRGB(92, 126, 49))
        Material.MELON -> FarmCropBreakEffectPalette(Color.fromRGB(121, 168, 75), Color.fromRGB(217, 79, 79))
        Material.CARROTS -> FarmCropBreakEffectPalette(Color.fromRGB(237, 138, 45), Color.fromRGB(111, 155, 66))
        Material.POTATOES -> FarmCropBreakEffectPalette(Color.fromRGB(196, 154, 98), Color.fromRGB(131, 99, 63))
        Material.BEETROOTS -> FarmCropBreakEffectPalette(Color.fromRGB(158, 48, 78), Color.fromRGB(78, 125, 55))
        else -> FarmCropBreakEffectPalette(Color.fromRGB(232, 201, 105), Color.fromRGB(165, 123, 61))
    }

    private fun emit(block: Block, crop: Material, breakPitch: Float, particles: Boolean, sounds: Boolean) {
        val center = block.location.toCenterLocation()
        if (particles) {
            val palette = palette(crop)
            block.world.spawnParticle(
                Particle.BLOCK,
                center,
                14,
                0.4,
                0.4,
                0.4,
                0.065,
                crop.createBlockData(),
            )
            block.world.spawnParticle(
                Particle.DUST_COLOR_TRANSITION,
                center,
                7,
                0.44,
                0.34,
                0.44,
                0.035,
                Particle.DustTransition(palette.primary, palette.accent, 1.15f),
            )
            block.world.spawnParticle(Particle.COMPOSTER, center, 3, 0.32, 0.28, 0.32, 0.025)
            block.world.spawnParticle(Particle.EXPLOSION, center, 1, 0.0, 0.0, 0.0, 0.0)
        }
        if (sounds) {
            block.world.playSound(center, Sound.BLOCK_WOOD_BREAK, 0.9f, breakPitch)
            block.world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.45f, 1.45f)
        }
    }

    private const val BREAK_PITCH = 1.02f
    private const val GIANT_PITCH_BASE = 0.85f
    private const val GIANT_PITCH_STEP = 0.006f
    private const val GIANT_PITCH_MAX = 1.65f
}
