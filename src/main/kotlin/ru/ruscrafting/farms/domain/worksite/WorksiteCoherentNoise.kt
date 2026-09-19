package ru.ruscrafting.farms.domain.worksite

import kotlin.math.floor

/** Smooth volumetric value noise for replayable cave geometry, in [-1, 1]. */
object WorksiteCoherentNoise {
    fun sample(seed: Long, x: Double, y: Double, z: Double): Double {
        val left = floor(x).toInt()
        val bottom = floor(y).toInt()
        val back = floor(z).toInt()
        val dx = fade(x - left)
        val dy = fade(y - bottom)
        val dz = fade(z - back)
        fun plane(up: Int): Double {
            val near = lerp(value(seed, left, up, back), value(seed, left + 1, up, back), dx)
            val far = lerp(value(seed, left, up, back + 1), value(seed, left + 1, up, back + 1), dx)
            return lerp(near, far, dz)
        }
        return lerp(plane(bottom), plane(bottom + 1), dy)
    }

    private fun value(seed: Long, x: Int, y: Int, z: Int): Double =
        (WorksiteDeterministicSeed.positionScore(seed, "worksite_noise", x, y, z) ushr 11).toDouble() /
            (1L shl 53).toDouble() * 2.0 - 1.0

    private fun fade(value: Double): Double = value * value * value * (value * (value * 6.0 - 15.0) + 10.0)
    private fun lerp(from: Double, to: Double, weight: Double): Double = from + (to - from) * weight
}
