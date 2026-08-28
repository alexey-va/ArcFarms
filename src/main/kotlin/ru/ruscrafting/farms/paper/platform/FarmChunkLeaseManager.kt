package ru.ruscrafting.farms.paper.platform

import org.bukkit.Chunk
import org.bukkit.plugin.Plugin

/** Exact plugin-ticket owner used by bounded world-mutation lifecycles. */
internal interface FarmChunkLeaseManager {
    fun retain(chunk: Chunk): Boolean

    fun release(chunk: Chunk): Boolean
}

internal class PaperFarmChunkLeaseManager(
    private val plugin: Plugin,
) : FarmChunkLeaseManager {
    override fun retain(chunk: Chunk): Boolean = chunk.addPluginChunkTicket(plugin)

    override fun release(chunk: Chunk): Boolean = chunk.removePluginChunkTicket(plugin)
}
