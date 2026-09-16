package ru.ruscrafting.farms.paper.worksite

import org.bukkit.World

/**
 * Default worksite path for expensive world-aware planning.
 *
 * Bukkit chunks are captured in bounded server-thread slices, the immutable snapshot is planned
 * on an async worker, and the result is returned to the server thread for live revalidation/apply.
 * Farm, mine, and future locations should use this instead of reading Bukkit world state async or
 * running an unbounded block search in one tick.
 */
internal class WorksiteAsyncBlockScanner(
    private val tasks: WorksiteTaskPort,
    private val maxChunksPerTick: Int = DEFAULT_MAX_CHUNKS_PER_TICK,
    private val captureNanosPerTick: Long = DEFAULT_CAPTURE_NANOS_PER_TICK,
) {
    init {
        require(maxChunksPerTick > 0)
        require(captureNanosPerTick > 0L)
    }

    fun <T> submit(
        world: World,
        chunkCoordinates: Collection<WorksiteChunkCoordinate>,
        stillValid: () -> Boolean,
        plan: (WorksiteBlockSnapshot) -> T,
        complete: (Result<T>) -> Unit,
    ): Boolean {
        val token = tasks.lifecycleToken()
        val capture = Capture(
            coordinates = chunkCoordinates.distinct().sortedWith(
                compareBy(WorksiteChunkCoordinate::x, WorksiteChunkCoordinate::z),
            ),
        )

        fun captureStep() {
            if (!stillValid()) return
            val deadline = System.nanoTime() + captureNanosPerTick
            var captured = 0
            while (capture.cursor < capture.coordinates.size &&
                captured < maxChunksPerTick && (captured == 0 || System.nanoTime() < deadline)) {
                val coordinate = capture.coordinates[capture.cursor++]
                if (world.isChunkLoaded(coordinate.x, coordinate.z)) {
                    capture.chunks[coordinate] = world.getChunkAt(coordinate.x, coordinate.z)
                        .getChunkSnapshot(false, false, false)
                }
                captured++
            }
            if (capture.cursor < capture.coordinates.size) {
                if (!tasks.runLater(token, 1L, ::captureStep)) {
                    complete(Result.failure(IllegalStateException("Could not schedule the next worksite snapshot slice")))
                }
                return
            }

            val snapshot = WorksiteBlockSnapshot(world.name, world.minHeight, world.maxHeight, capture.chunks.toMap())
            if (!tasks.runAsync(token) {
                    val result = runCatching { plan(snapshot) }
                    tasks.runSync(token) { if (stillValid()) complete(result) }
                }) {
                complete(Result.failure(IllegalStateException("Could not schedule worksite snapshot planning")))
            }
        }

        return tasks.runLater(token, 1L, ::captureStep)
    }

    private data class Capture(
        val coordinates: List<WorksiteChunkCoordinate>,
        val chunks: MutableMap<WorksiteChunkCoordinate, org.bukkit.ChunkSnapshot> = linkedMapOf(),
        var cursor: Int = 0,
    )

    private companion object {
        const val DEFAULT_MAX_CHUNKS_PER_TICK = 2
        const val DEFAULT_CAPTURE_NANOS_PER_TICK = 2_000_000L
    }
}
