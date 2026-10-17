package ru.ruscrafting.farms.paper.worksite

import org.bukkit.World

/** Captures native chunks through the runtime's aggregate budget, then plans detached values. */
internal class WorksiteAsyncBlockScanner(private val tasks: WorksiteTaskPort) {
    fun <T> submit(
        world: World,
        chunkCoordinates: Collection<WorksiteChunkCoordinate>,
        stillValid: () -> Boolean,
        plan: (WorksiteBlockSnapshot) -> T,
        complete: (Result<T>) -> Unit,
    ): Boolean {
        return submit(world, { chunkCoordinates }, stillValid, plan, complete)
    }

    /** Coordinate geometry is detached too; callers must capture native names/bounds before supplying it. */
    fun <T> submit(
        world: World,
        chunkCoordinates: () -> Collection<WorksiteChunkCoordinate>,
        stillValid: () -> Boolean,
        plan: (WorksiteBlockSnapshot) -> T,
        complete: (Result<T>) -> Unit,
    ): Boolean {
        val token = tasks.lifecycleToken()
        val worldName = world.name
        val minimumHeight = world.minHeight
        val maximumHeight = world.maxHeight
        return tasks.runAsync(token) {
            val ordered = chunkCoordinates().distinct().sortedWith(compareBy(WorksiteChunkCoordinate::x, WorksiteChunkCoordinate::z))
            tasks.runSync(token) {
                if (!stillValid()) return@runSync
                val coordinates = ordered.iterator()
                val captured = linkedMapOf<WorksiteChunkCoordinate, org.bukkit.ChunkSnapshot>()
                tasks.capture(token, stillValid) {
                    if (!coordinates.hasNext()) true else {
                        val coordinate = coordinates.next()
                        if (world.isChunkLoaded(coordinate.x, coordinate.z)) {
                            captured[coordinate] = world.getChunkAt(coordinate.x, coordinate.z)
                                .getChunkSnapshot(false, false, false)
                        }
                        !coordinates.hasNext()
                    }
                }.whenComplete { _, failure ->
                    if (failure != null) {
                        tasks.runSync(token) { if (stillValid()) complete(Result.failure(failure)) }
                    } else if (!tasks.runAsync(token) {
                            val result = runCatching {
                                plan(WorksiteBlockSnapshot(worldName, minimumHeight, maximumHeight, captured.toMap()))
                            }
                            tasks.runSync(token) { if (stillValid()) complete(result) }
                        }) {
                        complete(Result.failure(IllegalStateException("Could not schedule worksite snapshot planning")))
                    }
                }
            }
        }
    }
}
