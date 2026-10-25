package ru.ruscrafting.farms.paper.lumber

import org.bukkit.Chunk
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.paper.display.PaperPacketDisplays
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.paper.ActivityStatus
import ru.ruscrafting.farms.paper.WorksiteModule
import ru.ruscrafting.farms.paper.WorksiteFastVisualHandler
import ru.ruscrafting.farms.paper.lumber.sawmill.CedarSawmillController
import ru.ruscrafting.farms.paper.worksite.WorksiteAdminHandler
import ru.ruscrafting.farms.paper.worksite.WorksiteAdminReindexTick
import ru.ruscrafting.farms.paper.worksite.WorksiteAdminStatus

/**
 * The lumber worksite remains unavailable. Its authored sawmill is a public demonstration,
 * with no shift, player inventory, rewards, terrain mutation or durable worksite state.
 */
internal class LumbermillVersionedModule(
    plugin: Plugin,
    serverId: String,
    displays: PaperPacketDisplays,
) : WorksiteModule<Unit>, WorksiteAdminHandler, WorksiteFastVisualHandler {
    private val sawmill = CedarSawmillController(plugin, serverId, displays)

    override val kind: ActivityKind = ActivityKind.LUMBER
    override val zoneCount: Int = 0

    override fun states(): Map<String, Unit> = emptyMap()
    override fun statuses(): List<ActivityStatus> = emptyList()
    override fun tick(now: Long) = Unit
    override fun isAvailable(): Boolean = false
    override fun canAccess(player: Player): Boolean = false

    override fun activateLoadedState() = sawmill.activateLoadedState()
    override fun updateVisuals() = sawmill.updateVisuals()
    override fun reconcileChunk(chunk: Chunk) = sawmill.reconcileChunk(chunk)
    override fun beforeChunkUnload(chunk: Chunk) = sawmill.beforeChunkUnload(chunk)
    override fun beforeReload(reason: String) = sawmill.cleanup(reason)
    override fun cleanup(reason: String) = sawmill.cleanup(reason)

    fun sawmillSnapshot(): Map<String, Any> = sawmill.snapshot()

    override fun zoneIds(): List<String> = emptyList()
    override fun incidentIds(): List<String> = emptyList()
    override fun status(zoneId: String): WorksiteAdminStatus? = null
    override fun start(zoneId: String, player: Player): Boolean = false
    override fun forceIncident(zoneId: String, incidentId: String, now: Long): Boolean = false
    override fun startReindex(zoneId: String): Boolean = false
    override fun tickReindex(zoneId: String, budget: Int): WorksiteAdminReindexTick? = null
    override fun cancelReindex(zoneId: String): Boolean = false
}
