package ru.ruscrafting.farms.paper.fixtures

import org.bukkit.Chunk
import org.bukkit.World
import ru.ruscrafting.farms.paper.platform.FarmRouteChunkLoader
import java.util.concurrent.CompletableFuture

/** MockBukkit lacks Paper async chunk loading; model only its successful load boundary. */
internal object MockBukkitFarmRouteChunkLoader : FarmRouteChunkLoader {
    // MockBukkit does not implement plugin tickets; lifecycle ownership is checked separately.
    override fun retain(chunk: Chunk, plugin: org.bukkit.plugin.Plugin) = true
    override fun release(chunk: Chunk, plugin: org.bukkit.plugin.Plugin) = Unit

    override fun load(world: World, x: Int, z: Int): CompletableFuture<Chunk?> {
        world.loadChunk(x, z, false)
        return CompletableFuture.completedFuture(world.getChunkAt(x, z))
    }
}
