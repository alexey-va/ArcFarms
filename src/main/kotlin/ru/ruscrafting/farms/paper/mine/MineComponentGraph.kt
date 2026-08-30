package ru.ruscrafting.farms.paper.mine

import ru.ruscrafting.farms.paper.RegionGateway
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.mine.recovery.MineBlockRecoveryController
import ru.ruscrafting.farms.persistence.MineRecoveryJournal

/** Composition-only graph; the registry remains the sole V2 runtime collection owner. */
internal class MineComponentGraph(
    regions: RegionGateway,
    port: WorksiteRuntimePort,
    clock: () -> Long,
    journal: MineRecoveryJournal,
) {
    internal val registry = MineRuntimeRegistry()
    val recovery = MineBlockRecoveryController(journal, port, clock)
    val module = MineModule(regions, port, registry, recovery)

    internal val mutableRuntimeCollectionCount: Int = 1
}
