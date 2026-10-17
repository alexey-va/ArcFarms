package ru.ruscrafting.farms.paper.worksite

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.entity.Entity
import java.util.concurrent.CompletableFuture

/** Native cursors only; scheduling and the aggregate budget belong to the runtime task port. */
internal fun WorksiteTaskPort.captureLoadedEntities(action: (Entity) -> Unit): CompletableFuture<Unit> {
    val token = lifecycleToken()
    val worlds = Bukkit.getWorlds().iterator()
    var chunks = emptyList<Chunk>().iterator()
    var entities = emptyList<Entity>().iterator()
    return capture(token, { true }) {
        when {
            entities.hasNext() -> entities.next().takeIf { it.isValid }?.let(action)
            chunks.hasNext() -> chunks.next().takeIf { it.isLoaded && Bukkit.getWorld(it.world.uid) === it.world }?.let { entities = it.entities.iterator() }
            worlds.hasNext() -> {
                val world = worlds.next()
                if (Bukkit.getWorld(world.uid) === world) chunks = world.loadedChunks.iterator()
            }
        }
        !worlds.hasNext() && !chunks.hasNext() && !entities.hasNext()
    }
}

internal fun WorksiteTaskPort.captureLoadedChunks(action: (Chunk) -> Unit): CompletableFuture<Unit> {
    val token = lifecycleToken()
    val worlds = Bukkit.getWorlds().iterator()
    var chunks = emptyList<Chunk>().iterator()
    return capture(token, { true }) {
        when {
            chunks.hasNext() -> chunks.next().takeIf { it.isLoaded && Bukkit.getWorld(it.world.uid) === it.world }?.let(action)
            worlds.hasNext() -> {
                val world = worlds.next()
                if (Bukkit.getWorld(world.uid) === world) chunks = world.loadedChunks.iterator()
            }
        }
        !worlds.hasNext() && !chunks.hasNext()
    }
}
