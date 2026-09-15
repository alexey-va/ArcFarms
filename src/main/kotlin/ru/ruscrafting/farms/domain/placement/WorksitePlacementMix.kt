package ru.ruscrafting.farms.domain.placement

/** Shared deterministic mixer for replayable worksite placement decisions. */
internal object WorksitePlacementMix {
    fun mix(value: Long): Long {
        var mixed = value xor (value ushr 33)
        mixed *= -49064778989728563L
        mixed = mixed xor (mixed ushr 33)
        mixed *= -4265267296055464877L
        return mixed xor (mixed ushr 33)
    }
}
