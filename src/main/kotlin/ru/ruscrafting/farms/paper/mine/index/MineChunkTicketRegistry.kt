package ru.ruscrafting.farms.paper.mine.index

import org.bukkit.Chunk
import org.bukkit.plugin.Plugin
import java.util.UUID

/** Reference-counts the plugin-level Bukkit ticket shared by warmup and reindex owners. */
internal class MineChunkTicketRegistry(
    plugin: Plugin,
    private val addTicket: (Chunk) -> Boolean = { it.addPluginChunkTicket(plugin) },
    private val removeTicket: (Chunk) -> Unit = { it.removePluginChunkTicket(plugin) },
) : MineChunkTicket {
    private val entries = mutableMapOf<ChunkKey, Entry>()

    override fun retain(chunk: Chunk): Boolean {
        val key = ChunkKey(chunk.world.uid, chunk.x, chunk.z)
        val current = entries[key]
        if (current != null) {
            current.references++
            return true
        }
        entries[key] = Entry(chunk, references = 1, ownsBukkitTicket = addTicket(chunk))
        return true
    }

    override fun release(chunk: Chunk) {
        val key = ChunkKey(chunk.world.uid, chunk.x, chunk.z)
        val current = entries[key] ?: return
        current.references--
        if (current.references > 0) return
        entries.remove(key)
        if (current.ownsBukkitTicket) removeTicket(current.chunk)
    }

    private data class ChunkKey(val worldId: UUID, val x: Int, val z: Int)
    private data class Entry(val chunk: Chunk, var references: Int, val ownsBukkitTicket: Boolean)
}
