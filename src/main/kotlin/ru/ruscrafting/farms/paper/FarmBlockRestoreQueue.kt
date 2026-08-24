package ru.ruscrafting.farms.paper

import java.util.PriorityQueue

internal data class FarmFixedCropPosition(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
)

internal data class FarmFixedCropRestore(
    val position: FarmFixedCropPosition,
    val restoreAt: Long,
)

/**
 * Keeps delayed block repairs cheap at runtime. The chunk PDC ledger remains the
 * durable source of truth; this queue is rebuilt from loaded chunks after start.
 */
internal class FarmBlockRestoreQueue {
    private val due = PriorityQueue(compareBy<FarmFixedCropRestore> { it.restoreAt }.thenBy { it.position.world })
    private val scheduled = mutableMapOf<FarmFixedCropPosition, Long>()

    fun schedule(entry: FarmFixedCropRestore) {
        val current = scheduled[entry.position]
        if (current != null && current <= entry.restoreAt) return
        scheduled[entry.position] = entry.restoreAt
        due += entry
    }

    fun pollDue(now: Long, limit: Int): List<FarmFixedCropRestore> = buildList {
        require(limit >= 1) { "Restore batch size must be positive" }
        while (size < limit) {
            val next = due.peek() ?: break
            if (next.restoreAt > now) break
            due.remove()
            if (scheduled[next.position] != next.restoreAt) continue
            scheduled.remove(next.position)
            add(next)
        }
    }

    fun clear() {
        due.clear()
        scheduled.clear()
    }
}
