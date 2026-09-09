package ru.ruscrafting.farms.paper.fixtures

import org.bukkit.Chunk
import org.bukkit.World
import ru.ruscrafting.farms.paper.platform.FarmRouteChunkLoader
import java.util.concurrent.CompletableFuture

/** MockBukkit lacks Paper async chunk loading; model only its successful load boundary. */
internal object MockBukkitFarmRouteChunkLoader : FarmRouteChunkLoader {
    override fun load(world: World, x: Int, z: Int): CompletableFuture<Chunk?> {
        world.loadChunk(x, z, false)
        return CompletableFuture.completedFuture(world.getChunkAt(x, z))
    }
}
