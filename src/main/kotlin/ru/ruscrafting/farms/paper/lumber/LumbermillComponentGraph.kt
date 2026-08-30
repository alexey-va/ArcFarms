package ru.ruscrafting.farms.paper.lumber

import ru.ruscrafting.farms.paper.RegionGateway
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.worksite.RuntimeComponent

/** Composition-only graph; the registry is the sole mutable runtime collection owner. */
internal class LumbermillComponentGraph(
    regions: RegionGateway,
    port: WorksiteRuntimePort,
    clock: () -> Long,
    components: List<RuntimeComponent> = emptyList(),
) {
    private val registry = LumberRuntimeRegistry()
    internal val clock = clock
    val module = LumbermillModule(regions, port, registry, components)

    internal val mutableRuntimeCollectionCount: Int = 1
}
