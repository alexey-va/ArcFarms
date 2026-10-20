package ru.ruscrafting.farms.domain.mine.lift

import kotlin.math.abs
import kotlin.math.ceil

/** One shared cabin. Floor calls are deduplicated and served after the occupied trip. */
internal class MineLiftMotion(
    private val floors: List<Double>,
    private val speed: Double,
    private val boardingTicks: Int = BOARDING_TICKS,
) {
    enum class Phase { DOCKED, BOARDING, CLOSING, MOVING }
    var floor: Int = 0
        private set
    var target: Int = 0
        private set
    var y: Double = floors.first()
        private set
    var phase = Phase.DOCKED
        private set
    private var ticks = 0
    private var duration = 0
    private var originY = y
    private val calls = linkedSetOf<Int>()
    val queued: List<Int> get() = calls.toList()
    val doorsOpen: Boolean get() = phase == Phase.DOCKED || phase == Phase.BOARDING

    init {
        require(floors.size in 2..8 && floors.distinct().size == floors.size)
        require(floors.all(Double::isFinite) && speed.isFinite() && speed in 1.0..MAX_SPEED)
        require(boardingTicks > 0)
    }

    fun call(destination: Int) {
        require(destination in floors.indices)
        if (phase == Phase.DOCKED && destination == floor) return
        if (phase != Phase.DOCKED && destination == target) return
        calls += destination
    }

    fun canBoard(destination: Int): Boolean = destination in floors.indices && destination != floor &&
        (phase == Phase.DOCKED || phase == Phase.BOARDING && destination == target)

    fun board(destination: Int): Boolean {
        require(destination in floors.indices)
        if (!canBoard(destination)) return false
        if (phase == Phase.BOARDING) return true
        target = destination
        ticks = boardingTicks
        phase = Phase.BOARDING
        return true
    }

    /** Returns true exactly on arrival. Motion uses smoothstep, capped at [speed] blocks/s. */
    fun tick(): Boolean {
        when (phase) {
            Phase.DOCKED -> {
                if (ticks > 0) { ticks--; return false }
                val next = calls.firstOrNull() ?: return false
                calls.remove(next)
                if (next != floor) { target = next; closeDoors() }
            }
            Phase.BOARDING -> if (--ticks <= 0) closeDoors()
            Phase.CLOSING -> if (--ticks <= 0) depart()
            Phase.MOVING -> {
                ticks++
                val progress = (ticks.toDouble() / duration).coerceAtMost(1.0)
                y = originY + (floors[target] - originY) * progress * progress * (3.0 - 2.0 * progress)
                if (ticks >= duration) {
                    y = floors[target]
                    floor = target
                    calls.remove(floor)
                    phase = Phase.DOCKED
                    ticks = DOCK_TICKS
                    return true
                }
            }
        }
        return false
    }

    fun nearestFloor(): Int = floors.indices.minBy { abs(floors[it] - y) }

    private fun closeDoors() {
        ticks = CLOSING_TICKS
        phase = Phase.CLOSING
    }

    private fun depart() {
        originY = y
        duration = ceil(abs(floors[target] - y) * 30.0 / speed).toInt().coerceAtLeast(20)
        ticks = 0
        phase = Phase.MOVING
    }

    companion object {
        const val BOARDING_TICKS = 30
        const val CLOSING_TICKS = 10
        const val DOCK_TICKS = 40
        const val MAX_SPEED = 20.0
    }
}
