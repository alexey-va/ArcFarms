package ru.ruscrafting.farms.domain.mine.lift

import kotlin.math.pow

/** Short original lounge phrase, sampled by the existing cabin tick only while occupied. */
internal object MineLiftMusic {
    private val melody = intArrayOf(0, 4, 7, 9, 7, 4, 2, 0, 5, 9, 7, 4, 2, 5, 4, 0)
    private val bass = intArrayOf(-12, -3, -7, -5)
    data class Beat(val melodyPitch: Float, val bassPitch: Float?)

    fun at(tick: Int): Beat? {
        require(tick >= 0)
        if (tick % 5 != 0) return null
        val step = (tick / 5) % melody.size
        fun pitch(semitone: Int) = 2.0.pow(semitone / 12.0).toFloat()
        return Beat(pitch(melody[step]), if (step % 4 == 0) pitch(bass[step / 4]) else null)
    }
}
