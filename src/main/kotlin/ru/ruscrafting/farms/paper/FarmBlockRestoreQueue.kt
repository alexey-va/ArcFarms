package ru.ruscrafting.farms.paper

import ru.ruscrafting.farms.paper.worksite.WorksiteRestoreQueue

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
    private val queue = WorksiteRestoreQueue<FarmFixedCropPosition, FarmFixedCropRestore>(
        keyOf = FarmFixedCropRestore::position,
        restoreAtOf = FarmFixedCropRestore::restoreAt,
    )

    fun schedule(entry: FarmFixedCropRestore) = queue.schedule(entry)

    fun pollDue(now: Long, limit: Int): List<FarmFixedCropRestore> = queue.pollDue(now, limit)

    fun clear() = queue.clear()
}
