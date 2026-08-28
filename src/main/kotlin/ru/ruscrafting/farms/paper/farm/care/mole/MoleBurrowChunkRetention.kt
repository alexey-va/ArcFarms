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
    override fun retain(chunk: Chunk): MoleBurrowChunkLease {
        val owned = chunk.addPluginChunkTicket(plugin)
        return object : MoleBurrowChunkLease {
            private var closed = false

            @Synchronized
            override fun close() {
                if (closed) return
                if (owned) chunk.removePluginChunkTicket(plugin)
                // Mark the lease closed only after Paper accepted the release. If
                // the API throws, the caller can retain the lease and retry.
                closed = true
            }
        }
    }
}
