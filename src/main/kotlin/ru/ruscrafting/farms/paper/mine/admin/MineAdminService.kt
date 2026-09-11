package ru.ruscrafting.farms.paper.mine.admin

import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.extraction.MineExtractionController
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentScheduler
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import ru.ruscrafting.farms.paper.mine.index.MineChunkTicket
import ru.ruscrafting.farms.paper.mine.index.MineIndexDefinition
import ru.ruscrafting.farms.paper.mine.index.MineReindexJob
import ru.ruscrafting.farms.paper.mine.prospecting.MineProspectingController
import ru.ruscrafting.farms.paper.worksite.WorksiteAdminHandler
import ru.ruscrafting.farms.paper.worksite.WorksiteAdminReindexTick
import ru.ruscrafting.farms.paper.worksite.WorksiteAdminStatus
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import java.util.logging.Level

internal typealias MineAdminStatus = WorksiteAdminStatus

/** Typed admin boundary; callers never access the runtime collection directly. */
internal class MineAdminService(
    private val registry: MineRuntimeRegistry,
    private val index: MineBlockIndex,
    private val tickets: MineChunkTicket,
    private val prospecting: MineProspectingController,
    private val extraction: MineExtractionController,
    private val incidents: MineIncidentScheduler,
    private val state: WorksiteStatePort,
) : WorksiteAdminHandler {
    private val reindexes = mutableMapOf<String, MineReindexJob>()
    override val kind: ActivityKind = ActivityKind.MINE

    override fun zoneIds(): List<String> = registry.snapshot().map { it.settings.id }.sorted()
    override fun incidentIds(): List<String> = MineIncidentType.entries.map(Enum<*>::name)

    override fun status(zoneId: String): MineAdminStatus? = registry.byId(zoneId)?.let { runtime ->
        MineAdminStatus(
            kind, zoneId, runtime.state.phase.name, runtime.state.sequence, runtime.state.incident?.type?.name,
            runtime.state.objective,
            extraction.routeFor(runtime)?.let { route -> (0..route.finalIndex).map(route::sample) }.orEmpty(),
        )
    }

    override fun start(zoneId: String, player: Player): Boolean =
        registry.byId(zoneId)?.takeIf { it.state.phase == ru.ruscrafting.farms.domain.MinePhase.IDLE }
            ?.let { prospecting.adminStart(it, player) } == true

    override fun forceIncident(zoneId: String, incidentId: String, now: Long): Boolean =
        MineIncidentType.entries.firstOrNull { it.name.equals(incidentId, ignoreCase = true) }
            ?.let { forceIncident(zoneId, it, now) } == true

    fun forceIncident(zoneId: String, type: MineIncidentType, now: Long): Boolean {
        val runtime = registry.byId(zoneId) ?: return false
        return incidents.force(runtime, type, now)
    }

    override fun startReindex(zoneId: String): Boolean {
        if (zoneId in reindexes) return false
        val runtime = registry.byId(zoneId) ?: return false
        val phase = runtime.state.phase
        if (phase != ru.ruscrafting.farms.domain.MinePhase.IDLE &&
            !(runtime.settings.miningOnly && phase == ru.ruscrafting.farms.domain.MinePhase.MINING)) return false
        reindexes[zoneId] = MineReindexJob(
            MineIndexDefinition(
                zoneId,
                runtime.region,
                runtime.mineableMaterials,
                runtime.railMaterials,
            ),
            index,
            tickets,
        )
        state.log(Level.INFO, "Mine reindex started zone=$zoneId phase=$phase bounds=${runtime.region.bounds} volume=${runtime.region.bounds.volume}")
        return true
    }

    override fun tickReindex(zoneId: String, budget: Int): WorksiteAdminReindexTick? {
        val job = reindexes[zoneId] ?: return null
        val tick = try {
            job.tick(budget)
        } catch (failure: Throwable) {
            reindexes.remove(zoneId)
            state.log(Level.SEVERE, "Mine reindex failed zone=$zoneId budget=$budget", failure)
            throw failure
        }.also {
            if (it.finished) {
                reindexes.remove(zoneId)
                state.log(Level.INFO, "Mine reindex completed zone=$zoneId scanned=${it.scannedBlocks} indexed=${it.indexedTargets}")
            }
        }
        return WorksiteAdminReindexTick(tick.finished, tick.scannedBlocks, tick.indexedTargets)
    }

    override fun cancelReindex(zoneId: String): Boolean = reindexes.remove(zoneId)?.let { it.cancel(); true } == true

    fun tickReindexes(budgetPerZone: Int) {
        reindexes.keys.toList().forEach { tickReindex(it, budgetPerZone) }
    }

    fun cleanup() {
        reindexes.values.forEach(MineReindexJob::cancel)
        reindexes.clear()
    }
}
