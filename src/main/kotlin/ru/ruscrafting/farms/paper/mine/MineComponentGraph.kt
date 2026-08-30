package ru.ruscrafting.farms.paper.mine

import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.paper.RegionGateway
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.mine.recovery.MineBlockRecoveryController
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import ru.ruscrafting.farms.paper.mine.index.MineChunkTicket
import ru.ruscrafting.farms.persistence.MineRecoveryJournal
import ru.ruscrafting.farms.paper.MineBlockEffects
import ru.ruscrafting.farms.paper.PaperMineBlockEffects
import ru.ruscrafting.farms.paper.mine.prospecting.MineProspectingController
import ru.ruscrafting.farms.paper.mine.mining.MineMiningController
import ru.ruscrafting.farms.paper.mine.loading.MineLoadingController
import ru.ruscrafting.farms.paper.mine.extraction.MineCartEffects
import ru.ruscrafting.farms.paper.mine.extraction.MineCartScene
import ru.ruscrafting.farms.paper.mine.extraction.MineExtractionController
import ru.ruscrafting.farms.paper.mine.extraction.PaperMineCartEffects
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator
import ru.ruscrafting.farms.paper.mine.incident.cavein.MineCaveInIncident
import ru.ruscrafting.farms.paper.mine.incident.track.MineTrackDamageIncident
import ru.ruscrafting.farms.paper.mine.incident.gas.MineGasLeakIncident
import ru.ruscrafting.farms.paper.mine.incident.crystal.MineCrystalResonanceIncident
import ru.ruscrafting.farms.paper.mine.incident.flood.MineFloodingIncident
import ru.ruscrafting.farms.paper.mine.incident.power.MinePowerFailureIncident
import ru.ruscrafting.farms.paper.mine.recovery.MineIncidentBlockJournal
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import ru.ruscrafting.farms.config.ArcFarmsLocale
import java.util.random.RandomGenerator

/** Composition-only graph; the registry remains the sole V2 runtime collection owner. */
internal class MineComponentGraph(
    plugin: Plugin,
    regions: RegionGateway,
    port: WorksiteRuntimePort,
    clock: () -> Long,
    journal: MineRecoveryJournal,
    random: RandomGenerator = RandomGenerator.getDefault(),
    blockEffects: MineBlockEffects = PaperMineBlockEffects,
    serviceItems: WorksiteServiceItems? = null,
    locale: ArcFarmsLocale? = null,
    cartEffects: MineCartEffects = PaperMineCartEffects(plugin),
) {
    internal val registry = MineRuntimeRegistry()
    val recovery = MineBlockRecoveryController(journal, port, clock)
    val index = MineBlockIndex(plugin)
    private val transitions = MineTransitionCoordinator(port)
    private val incidents = MineIncidentCoordinator(transitions, port)
    private val incidentJournal = MineIncidentBlockJournal(recovery)
    val caveIn = MineCaveInIncident(registry, index, incidents, serviceItems, port)
    val trackDamage = MineTrackDamageIncident(registry, index, incidents, serviceItems, port)
    val gasLeak = MineGasLeakIncident(registry, index, incidents)
    val crystalResonance = MineCrystalResonanceIncident(registry, index, incidents)
    val flooding = MineFloodingIncident(registry, index, incidents, incidentJournal, serviceItems, port)
    val powerFailure = MinePowerFailureIncident(registry, index, incidents, incidentJournal)
    val cartScene = MineCartScene(cartEffects)
    val extraction = MineExtractionController(registry, index, cartScene, transitions, port, clock)
    val loading = MineLoadingController(registry, index, extraction, transitions, serviceItems, locale, port, clock)
    val prospecting = MineProspectingController(registry, index, recovery, transitions, port, clock, loading::canStage)
    val mining = MineMiningController(
        registry, index, recovery, transitions, port, clock, random, blockEffects, loading::begin,
    )
    private val tickets = object : MineChunkTicket {
        override fun retain(chunk: org.bukkit.Chunk): Boolean = chunk.addPluginChunkTicket(plugin)
        override fun release(chunk: org.bukkit.Chunk) {
            chunk.removePluginChunkTicket(plugin)
        }
    }
    val module = MineModule(
        regions, port, registry, recovery, index, tickets, prospecting, mining, loading, extraction, cartScene,
        caveIn, trackDamage, gasLeak, crystalResonance, flooding, powerFailure,
    )

    internal val mutableRuntimeCollectionCount: Int = 1
}
