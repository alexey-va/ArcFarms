package ru.ruscrafting.farms.paper.lumber.incident.jam

import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEvent
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.LumberIncidentType
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.lumber.LumberRuntime
import ru.ruscrafting.farms.paper.lumber.LumberRuntimeRegistry
import ru.ruscrafting.farms.paper.lumber.incident.LumberIncidentCoordinator

internal class LumberSawJamIncident(
    private val registry: LumberRuntimeRegistry,
    private val incidents: LumberIncidentCoordinator,
    private val port: WorksiteRuntimePort,
) {
    fun start(runtime: LumberRuntime, required: Int, now: Long): Boolean =
        incidents.start(runtime, LumberIncidentType.SAW_JAM, required, now)

    fun useSwitch(runtime: LumberRuntime, switchIndex: Int, player: Player): Boolean {
        val incident = runtime.state.incident ?: return false
        if (runtime.state.phase != LumberPhase.INCIDENT || incident.type != LumberIncidentType.SAW_JAM) return false
        if (switchIndex != incident.progress) {
            port.sendActionBar(player, MessageKey.LUMBER_JAM_SEQUENCE)
            return false
        }
        return incidents.work(runtime, player).accepted
    }

    fun onInteract(event: PlayerInteractEvent, clicked: Block, player: Player): Boolean {
        val runtime = registry.snapshot().firstOrNull { it.station.contains(clicked.location) } ?: return false
        if (runtime.state.incident?.type != LumberIncidentType.SAW_JAM) return false
        val index = runtime.settings.stationMaterials.indexOf(clicked.type.name)
        if (index < 0) return false
        event.isCancelled = true
        useSwitch(runtime, index, player)
        return true
    }
}
