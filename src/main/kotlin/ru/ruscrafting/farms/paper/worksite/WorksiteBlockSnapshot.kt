package ru.ruscrafting.farms.paper.worksite

import org.bukkit.ChunkSnapshot
import org.bukkit.Material
import ru.ruscrafting.farms.domain.worksite.WorksitePosition

/**
 * Immutable, thread-safe view of captured Bukkit chunks for background placement planning.
 * Chunk capture stays on the server thread; consumers may only read this detached view.
 */
internal class WorksiteBlockSnapshot(
    private val world: String,
    private val minimumHeight: Int,
    private val maximumHeight: Int,
    private val chunks: Map<WorksiteChunkCoordinate, ChunkSnapshot>,
) {
    fun type(position: WorksitePosition): Material? {
        if (position.world != world || position.y !in minimumHeight until maximumHeight) return null
        val chunk = chunks[WorksiteChunkCoordinate(position.x shr 4, position.z shr 4)] ?: return null
        return chunk.getBlockType(position.x and 15, position.y, position.z and 15)
    }
}

internal data class WorksiteChunkCoordinate(val x: Int, val z: Int)
