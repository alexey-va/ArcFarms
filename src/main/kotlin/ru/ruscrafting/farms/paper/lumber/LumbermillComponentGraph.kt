package ru.ruscrafting.farms.paper.lumber

import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.paper.RegionGateway
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.lumber.index.LumberBlockIndex
import ru.ruscrafting.farms.paper.lumber.index.LumberChunkTicket
import ru.ruscrafting.farms.paper.lumber.recovery.LumberBlockRecoveryController
import ru.ruscrafting.farms.persistence.LumberRecoveryJournal

/** Composition-only graph; the registry is the sole mutable runtime collection owner. */
internal class LumbermillComponentGraph(
    plugin: Plugin,
    regions: RegionGateway,
    port: WorksiteRuntimePort,
    clock: () -> Long,
    journal: LumberRecoveryJournal,
) {
    private val registry = LumberRuntimeRegistry()
    internal val clock = clock
    val index = LumberBlockIndex(plugin)
    val recovery = LumberBlockRecoveryController(journal, port, clock)
    private val tickets = object : LumberChunkTicket {
        override fun retain(chunk: org.bukkit.Chunk): Boolean = chunk.addPluginChunkTicket(plugin)
        override fun release(chunk: org.bukkit.Chunk) {
            chunk.removePluginChunkTicket(plugin)
        }
    }
    val module = LumbermillModule(regions, port, registry, index, recovery, tickets)

    internal val mutableRuntimeCollectionCount: Int = 1
}
