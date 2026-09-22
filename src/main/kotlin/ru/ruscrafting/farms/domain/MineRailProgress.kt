package ru.ruscrafting.farms.domain

import ru.ruscrafting.farms.domain.worksite.WorksiteDeterministicSeed
import kotlin.math.abs

/** One continuous centreline and resumable maintenance, inside the existing working journal. */
data class MineRailProgress(
    val route: List<Int> = emptyList(),
    val serviced: Set<Int> = emptySet(),
    val service: MineRailService? = null,
    val finished: Boolean = false,
) {
    fun validate(cells: Int) {
        require(route.size <= cells && route.distinct().size == route.size && route.all { it in 0 until cells })
        require(route.zipWithNext().all { (a,b) -> abs(a % 33-b % 33)+abs(a / 33-b / 33)==1 })
        require(serviced.all { it in 0..1 })
        service?.let { require(it.index in 0..1 && it.index !in serviced && it.step in 0..2) }
    }
}

enum class MineRailServiceKind { JAM, CASSETTE }
data class MineRailService(val index: Int, val kind: MineRailServiceKind, val step: Int = 0)

object MineRailProgression {
    /** Seeded optional pauses; no random state is consumed on ticks or reconstruction. */
    fun due(progress: MineRailProgress, seed: Long, forward: Double): MineRailService? {
        progress.service?.let { return it }
        for (index in 0..1) {
            if (index in progress.serviced || forward < 14 + index * 14) continue
            val choice = Math.floorMod(WorksiteDeterministicSeed.derive(seed, 720L + index), 3L).toInt()
            if (choice != 0) return MineRailService(index, if (choice == 1) MineRailServiceKind.JAM else MineRailServiceKind.CASSETTE)
        }
        return null
    }

    /** Backtracking rewinds the branch; consecutive grid steps always share a real rail edge. */
    fun follow(progress: MineRailProgress, cell: Int): MineRailProgress {
        val route = progress.route.ifEmpty { listOf(16,49,82) }
        val index = route.indexOf(cell)
        if (index >= 0) return progress.copy(route = route.take(index + 1))
        var at = route.last()
        if (abs(at%33-cell%33)+abs(at/33-cell/33)>2) return progress.copy(route=route)
        val next = route.toMutableList()
        while (at != cell) {
            val dx=cell%33-at%33; val dz=cell/33-at/33
            at += if (abs(dz)>=abs(dx) && dz!=0) if(dz>0) 33 else -33 else if(dx>0) 1 else -1
            val old=next.indexOf(at)
            if(old>=0) { while(next.size>old+1) next.removeAt(next.lastIndex) } else next+=at
        }
        return progress.copy(route=next)
    }

    /** Nearby old track is also withdrawn on a tight turn, keeping native rail physics off the chassis. */
    fun laid(progress: MineRailProgress, checkpoint: Int): List<Int> =
        if (progress.finished) progress.route else progress.route.dropLast(3).filter { cell ->
            val x = cell % 33 - checkpoint % 33
            val z = cell / 33 - checkpoint / 33
            x * x + z * z > 9
        }

    fun finishService(progress: MineRailProgress): MineRailProgress {
        val service=progress.service ?: return progress
        return progress.copy(serviced=progress.serviced+service.index,service=null)
    }
}
