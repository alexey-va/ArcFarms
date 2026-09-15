package ru.ruscrafting.farms.domain.worksite

import ru.ruscrafting.farms.domain.placement.WorksitePlacementPoint

/**
 * Canonical deterministic randomness for replayable worksite decisions.
 *
 * Callers choose an intent (derive a seed, rank a world position, or rank a
 * two-dimensional grid cell) instead of carrying mixer constants of their own.
 */
object WorksiteDeterministicSeed {
    /** Derives an independent replayable seed from a lifecycle sequence and a semantic salt. */
    fun derive(sequence: Long, salt: Long): Long = splitMixFinalizer(sequence + salt + GOLDEN_GAMMA)

    /** Stable ordering score for a lifecycle sequence and semantic ordering salt. */
    fun orderScore(sequence: Long, salt: Long = 0L): Long = avalanche(sequence xor salt)

    /** Stable ranking score for a canonical worksite placement point. */
    fun positionScore(seed: Long, point: WorksitePlacementPoint): Long = positionScore(
        seed = seed,
        world = point.world,
        x = java.lang.Double.doubleToLongBits(point.x),
        y = java.lang.Double.doubleToLongBits(point.y),
        z = java.lang.Double.doubleToLongBits(point.z),
    )

    /** Stable ranking score for an integral block position. */
    fun positionScore(seed: Long, world: String, x: Int, y: Int, z: Int): Long = positionScore(
        seed = seed,
        world = world,
        x = x.toLong(),
        y = y.toLong(),
        z = z.toLong(),
    )

    /** Stable ranking score for a two-dimensional procedural grid cell. */
    fun gridScore(seed: Long, x: Int, z: Int): Long = avalanche(
        seed xor (x.toLong() * X_SALT) xor (z.toLong() * Z_SALT),
    )

    /** Finalizes a pre-combined deterministic value. Prefer the intent methods above in feature code. */
    private fun avalanche(value: Long): Long {
        var mixed = value xor (value ushr 33)
        mixed *= MURMUR_MULTIPLIER_ONE
        mixed = mixed xor (mixed ushr 33)
        mixed *= MURMUR_MULTIPLIER_TWO
        return mixed xor (mixed ushr 33)
    }

    private fun positionScore(seed: Long, world: String, x: Long, y: Long, z: Long): Long {
        val coordinateSeed = x xor java.lang.Long.rotateLeft(y, 17) xor
            java.lang.Long.rotateLeft(z, 33) xor world.hashCode().toLong()
        return avalanche(seed xor coordinateSeed)
    }

    private fun splitMixFinalizer(input: Long): Long {
        var value = input
        value = (value xor (value ushr 30)) * X_SALT
        value = (value xor (value ushr 27)) * Z_SALT
        return value xor (value ushr 31)
    }

    private const val GOLDEN_GAMMA = -7046029254386353131L
    private const val X_SALT = -4658895280553007687L
    private const val Z_SALT = -7723592293110705685L
    private const val MURMUR_MULTIPLIER_ONE = -49064778989728563L
    private const val MURMUR_MULTIPLIER_TWO = -4265267296055464877L
}
