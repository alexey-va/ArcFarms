package ru.ruscrafting.farms.paper.worksite.scene

/** Immutable coordinate lookup without a key and hash-table entry for every journalled block. */
internal class WorksitePreparedSceneIndex(private val records: List<WorksitePreparedSceneRecord>) {
    private data class ChunkKey(val world: String, val x: Int, val z: Int)
    private data class Positions(val coordinates: LongArray, val recordIndices: IntArray)

    private val chunks = records.indices.groupBy { index ->
        val record = records[index]
        ChunkKey(record.world, record.x shr 4, record.z shr 4)
    }.mapValues { (_, indices) ->
        val sorted = indices.sortedBy { index -> records[index].let { coordinate(it.x, it.y, it.z) } }
        val coordinates = LongArray(sorted.size)
        val recordIndices = IntArray(sorted.size)
        var count = 0
        sorted.forEach { index ->
            val record = records[index]
            val position = coordinate(record.x, record.y, record.z)
            if (count == 0 || coordinates[count - 1] != position) coordinates[count++] = position
            // Match associateBy: the last record wins; scene admission still rejects duplicate positions.
            recordIndices[count - 1] = index
        }
        Positions(if (count == coordinates.size) coordinates else coordinates.copyOf(count),
            if (count == recordIndices.size) recordIndices else recordIndices.copyOf(count))
    }

    val size: Int = chunks.values.sumOf { it.coordinates.size }

    operator fun get(world: String, x: Int, y: Int, z: Int): WorksitePreparedSceneRecord? {
        val chunk = chunks[ChunkKey(world, x shr 4, z shr 4)] ?: return null
        val index = chunk.coordinates.binarySearch(coordinate(x, y, z))
        return if (index < 0) null else records[chunk.recordIndices[index]]
    }

    fun contains(world: String, x: Int, y: Int, z: Int): Boolean = get(world, x, y, z) != null

    private fun coordinate(x: Int, y: Int, z: Int): Long =
        (y.toLong() shl 8) or ((x and 15).toLong() shl 4) or (z and 15).toLong()
}
