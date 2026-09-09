package ru.ruscrafting.farms.paper.platform

import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture

/** Loads an existing delivery checkpoint chunk without generating new terrain. */
internal fun interface FarmRouteChunkLoader {
    fun load(world: World, x: Int, z: Int): CompletableFuture<Chunk?>
    fun retain(chunk: Chunk, plugin: Plugin): Boolean = chunk.addPluginChunkTicket(plugin)
    fun release(chunk: Chunk, plugin: Plugin) { chunk.removePluginChunkTicket(plugin) }
}

internal object PaperFarmRouteChunkLoader : FarmRouteChunkLoader {
    override fun load(world: World, x: Int, z: Int): CompletableFuture<Chunk?> =
        world.getChunkAtAsync(x, z, false)
}
