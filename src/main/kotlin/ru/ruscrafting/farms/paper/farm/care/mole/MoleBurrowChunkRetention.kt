package ru.ruscrafting.farms.paper.farm.care.mole

import org.bukkit.Chunk
import org.bukkit.plugin.Plugin

internal fun interface MoleBurrowChunkLease : AutoCloseable {
    override fun close()
}

/** Retains one tunnel chunk until its durable journal has been restored. */
internal fun interface MoleBurrowChunkRetention {
    fun retain(chunk: Chunk): MoleBurrowChunkLease
}

internal class PaperMoleBurrowChunkRetention(
    private val plugin: Plugin,
) : MoleBurrowChunkRetention {
    private val registry = ru.arc.paper.chunk.PaperChunkTicketRegistry(plugin)
    override fun retain(chunk: Chunk): MoleBurrowChunkLease {
        val acquired = registry.acquire(chunk)
        val lease = (acquired as? ru.arc.paper.chunk.PaperChunkTicketAcquireResult.Acquired)?.lease
            ?: error("Could not retain farm tunnel chunk: $acquired")
        return MoleBurrowChunkLease { lease.close() }
    }
}
