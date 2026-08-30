package ru.ruscrafting.farms.paper.worksite

import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveState
import ru.ruscrafting.farms.domain.worksite.WorksitePosition

internal data class WorksiteAdminStatus(
    val kind: ActivityKind,
    val zoneId: String,
    val phase: String,
    val sequence: Long,
    val incident: String?,
    val objective: WorksiteObjectiveState?,
    val route: List<WorksitePosition> = emptyList(),
)

internal data class WorksiteAdminReindexTick(
    val finished: Boolean,
    val scannedBlocks: Long,
    val indexedTargets: Int,
)

/** Common typed admin surface for every V2 physical worksite. */
internal interface WorksiteAdminHandler {
    val kind: ActivityKind
    fun zoneIds(): List<String>
    fun incidentIds(): List<String>
    fun status(zoneId: String): WorksiteAdminStatus?
    fun start(zoneId: String, player: Player): Boolean
    fun forceIncident(zoneId: String, incidentId: String, now: Long): Boolean
    fun startReindex(zoneId: String): Boolean
    fun tickReindex(zoneId: String, budget: Int): WorksiteAdminReindexTick?
    fun cancelReindex(zoneId: String): Boolean
}

internal class WorksiteAdminRegistry(handlers: Collection<WorksiteAdminHandler>) {
    private val byKind = handlers.associateBy(WorksiteAdminHandler::kind)

    init {
        require(byKind.size == handlers.size) { "Duplicate worksite admin activity" }
    }

    fun handler(kind: ActivityKind): WorksiteAdminHandler? = byKind[kind]
    fun zoneIds(kind: ActivityKind): List<String> = byKind[kind]?.zoneIds().orEmpty()
}
