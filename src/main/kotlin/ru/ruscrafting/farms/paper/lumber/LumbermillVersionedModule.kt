package ru.ruscrafting.farms.paper.lumber

import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.paper.ActivityStatus
import ru.ruscrafting.farms.paper.WorksiteModule
import ru.ruscrafting.farms.paper.worksite.WorksiteAdminHandler
import ru.ruscrafting.farms.paper.worksite.WorksiteAdminReindexTick
import ru.ruscrafting.farms.paper.worksite.WorksiteAdminStatus

/**
 * Deliberately empty lumbermill seam.
 *
 * The previous implementation was retired wholesale. A future lumbermill must be composed behind
 * this boundary from the farm-first player-experience contract instead of reviving legacy behavior.
 */
internal class LumbermillVersionedModule : WorksiteModule<Unit>, WorksiteAdminHandler {
    override val kind: ActivityKind = ActivityKind.LUMBER
    override val zoneCount: Int = 0

    override fun states(): Map<String, Unit> = emptyMap()
    override fun statuses(): List<ActivityStatus> = emptyList()
    override fun tick(now: Long) = Unit
    override fun isAvailable(): Boolean = false
    override fun canAccess(player: Player): Boolean = false

    override fun zoneIds(): List<String> = emptyList()
    override fun incidentIds(): List<String> = emptyList()
    override fun status(zoneId: String): WorksiteAdminStatus? = null
    override fun start(zoneId: String, player: Player): Boolean = false
    override fun forceIncident(zoneId: String, incidentId: String, now: Long): Boolean = false
    override fun startReindex(zoneId: String): Boolean = false
    override fun tickReindex(zoneId: String, budget: Int): WorksiteAdminReindexTick? = null
    override fun cancelReindex(zoneId: String): Boolean = false
}
