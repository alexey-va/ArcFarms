package ru.ruscrafting.farms.paper.mine

import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.paper.RegionGateway
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.mine.recovery.MineBlockRecoveryController
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import ru.ruscrafting.farms.paper.mine.index.MineChunkTicket
import ru.ruscrafting.farms.persistence.MineRecoveryJournal

/** Composition-only graph; the registry remains the sole V2 runtime collection owner. */
internal class MineComponentGraph(
    plugin: Plugin,
    regions: RegionGateway,
    port: WorksiteRuntimePort,
    clock: () -> Long,
    journal: MineRecoveryJournal,
) {
    internal val registry = MineRuntimeRegistry()
    val recovery = MineBlockRecoveryController(journal, port, clock)
    val index = MineBlockIndex(plugin)
    private val tickets = object : MineChunkTicket {
        override fun retain(chunk: org.bukkit.Chunk): Boolean = chunk.addPluginChunkTicket(plugin)
        override fun release(chunk: org.bukkit.Chunk) {
            chunk.removePluginChunkTicket(plugin)
        }
    }
    val module = MineModule(regions, port, registry, recovery, index, tickets)

    internal val mutableRuntimeCollectionCount: Int = 1
}
