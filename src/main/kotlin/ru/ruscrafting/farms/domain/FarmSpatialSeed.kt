package ru.ruscrafting.farms.domain

/** SplitMix64 finalizer: nearby shift numbers no longer select nearby/repeating map anchors. */
object FarmSpatialSeed {
    fun mix(sequence: Long, salt: Long): Long {
        var value = sequence + salt + -7046029254386353131L
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        return value xor (value ushr 31)
    }
}
