package ru.ruscrafting.farms.paper.lumber.admin

import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.LumberIncidentType
import ru.ruscrafting.farms.paper.lumber.LumberRuntimeRegistry
import ru.ruscrafting.farms.paper.lumber.felling.LumberFellingController
import ru.ruscrafting.farms.paper.lumber.incident.LumberIncidentScheduler
import ru.ruscrafting.farms.paper.lumber.index.LumberBlockIndex
import ru.ruscrafting.farms.paper.lumber.index.LumberChunkTicket
import ru.ruscrafting.farms.paper.lumber.index.LumberIndexDefinition
import ru.ruscrafting.farms.paper.lumber.index.LumberReindexJob
import ru.ruscrafting.farms.paper.worksite.WorksiteAdminHandler
import ru.ruscrafting.farms.paper.worksite.WorksiteAdminReindexTick
import ru.ruscrafting.farms.paper.worksite.WorksiteAdminStatus

/** Lumber-specific implementation of the shared V2 worksite admin contract. */
internal class LumberAdminService(
    private val registry: LumberRuntimeRegistry,
    private val index: LumberBlockIndex,
    private val tickets: LumberChunkTicket,
    private val felling: LumberFellingController,
    private val incidents: LumberIncidentScheduler,
) : WorksiteAdminHandler {
    private val reindexes = mutableMapOf<String, LumberReindexJob>()
    override val kind: ActivityKind = ActivityKind.LUMBER

    override fun zoneIds(): List<String> = registry.snapshot().map { it.settings.id }.sorted()
    override fun incidentIds(): List<String> = LumberIncidentType.entries.map(Enum<*>::name)

    override fun status(zoneId: String): WorksiteAdminStatus? = registry.byId(zoneId)?.let { runtime ->
        WorksiteAdminStatus(
            kind, zoneId, runtime.state.phase.name, runtime.state.sequence, runtime.state.incident?.type?.name,
            runtime.state.objective,
        )
    }

    override fun start(zoneId: String, player: Player): Boolean =
        registry.byId(zoneId)?.let { felling.adminStart(it, player) } == true

    override fun forceIncident(zoneId: String, incidentId: String, now: Long): Boolean {
        val runtime = registry.byId(zoneId) ?: return false
        val type = LumberIncidentType.entries.firstOrNull { it.name.equals(incidentId, ignoreCase = true) } ?: return false
        return incidents.force(runtime, type, now)
    }

    override fun startReindex(zoneId: String): Boolean {
        if (zoneId in reindexes) return false
        val runtime = registry.byId(zoneId) ?: return false
        if (runtime.state.phase != ru.ruscrafting.farms.domain.LumberPhase.IDLE) return false
        reindexes[zoneId] = LumberReindexJob(
            LumberIndexDefinition(runtime.settings.id, runtime.region, runtime.settings.species.toSet()),
            index,
            tickets,
        )
        return true
    }

    override fun tickReindex(zoneId: String, budget: Int): WorksiteAdminReindexTick? {
        val job = reindexes[zoneId] ?: return null
        val tick = job.tick(budget).also { if (it.finished) reindexes.remove(zoneId) }
        return WorksiteAdminReindexTick(tick.finished, tick.scannedBlocks, tick.indexedLogs)
    }

    override fun cancelReindex(zoneId: String): Boolean = reindexes.remove(zoneId)?.let { it.cancel(); true } == true

    fun tickReindexes(budgetPerZone: Int) {
        reindexes.keys.toList().forEach { tickReindex(it, budgetPerZone) }
    }

    fun cleanup() {
        reindexes.values.forEach(LumberReindexJob::cancel)
        reindexes.clear()
    }
}
