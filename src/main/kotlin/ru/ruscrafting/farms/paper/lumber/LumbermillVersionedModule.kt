package ru.ruscrafting.farms.paper.lumber

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.plugin.Plugin
import ru.arc.paper.display.PaperPacketDisplays
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.LumbermillSettings
import ru.ruscrafting.farms.domain.lumber.LumberEngine
import ru.ruscrafting.farms.domain.lumber.LumberState
import ru.ruscrafting.farms.domain.lumber.LumberIncidentType
import ru.ruscrafting.farms.domain.lumber.LumberIncidentEngine
import ru.ruscrafting.farms.paper.*
import ru.ruscrafting.farms.paper.lumber.scene.PacketLumberPresentation
import ru.ruscrafting.farms.paper.worksite.*
import java.util.UUID

/** Registration and composition only. The controller owns the complete closed-beta lifecycle. */
internal class LumbermillVersionedModule(
    private val plugin: Plugin,
    private val serverId: String,
    private val displays: PaperPacketDisplays,
    initialSettings: LumbermillSettings,
    private val locale: ArcFarmsLocale,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val storage: WorksiteStatePort,
    private val tasks: WorksiteTaskPort,
    private val serviceItems: WorksiteServiceItems,
    private val clock: () -> Long,
) : WorksiteModule<LumberState>, WorksiteFastVisualHandler, WorksiteGuidanceHandler,
    WorksiteBlockBreakGuard, WorksiteBlockBreakHandler, WorksiteBlockDamageHandler,
    WorksitePlayerInteractHandler, WorksiteArmSwingHandler, WorksiteMoveHandler,
    WorksiteParticipantOwner, WorksiteServiceItemOwner, WorksiteAdminHandler {
    private var rules = initialSettings
    private var controller: LumberController = create(LumberState())
    private var retired = false

    override val kind: ActivityKind = ActivityKind.LUMBER
    override val zoneCount: Int get() = if (isAvailable()) 1 else 0

    override fun states(): Map<String, LumberState> = if (serverId == "spawn") mapOf(LumberEngine.ZONE_ID to controller.state) else emptyMap()
    override fun statuses() = if (!isAvailable()) emptyList() else listOf(ActivityStatus(kind, LumberEngine.ZONE_ID,
        "lumber.${controller.state.phase.name.lowercase()}", "${controller.state.completedRounds}"))
    override fun tick(now: Long) = controller.tick(now)
    override fun isAvailable() = serverId == "spawn" && Bukkit.getWorld(rules.world) != null
    override fun canAccess(player: Player) = player.hasPermission(LumbermillSettings.BETA_PERMISSION) && isAvailable()

    fun rebuild(settings: LumbermillSettings, persisted: Map<String, LumberState>) {
        cleanup("rebuild")
        rules = settings
        controller = create(persisted[LumberEngine.ZONE_ID] ?: LumberState())
        retired = false
    }
    fun reconfigure(settings: LumbermillSettings, persisted: Map<String, LumberState>) = rebuild(settings, persisted)
    private fun create(state: LumberState) = LumberController(rules, serverId, locale, access, audience, storage, tasks,
        serviceItems, PacketLumberPresentation(plugin, displays, rules.world, LumbermillSettings.BETA_PERMISSION, rules.warehouse) { player ->
            controller.onDisplayInteract(player)
        }, state, clock, incidentTethers = WorksiteCrankTethers(NamespacedKey(plugin, "lumber_incident_crank_tether"), plugin))
    override fun activateLoadedState() {
        if (retired) { controller = create(controller.state); retired = false }
        controller.activate()
    }
    override fun updateVisuals() = controller.updateVisuals()
    override fun reconcileChunk(chunk: Chunk) { if (chunk.world.name == rules.world) controller.onChunkChanged() }
    override fun beforeChunkUnload(chunk: Chunk) { if (chunk.world.name == rules.world) controller.onChunkChanged() }
    override fun beforeReload(reason: String) = cleanup(reason)
    override fun cleanup(reason: String) {
        if (!retired) { controller.cleanup(reason); retired = true }
    }
    override fun onBreakLowest(event: BlockBreakEvent) = controller.onBreakLowest(event)
    override fun onBreakHigh(event: BlockBreakEvent) = controller.onBreakHigh(event)
    override fun onBlockDamage(event: BlockDamageEvent) = controller.onDamage(event)
    override fun onInteract(event: PlayerInteractEvent, player: Player) = controller.onInteract(event)
    override fun onSwing(event: PlayerAnimationEvent) = controller.onSwing(event)
    override fun onMove(from: Location, to: Location, player: Player) = controller.onMove(player)
    override fun updateGuidance(expectedBars: MutableSet<ActivityBarKey>) = controller.updateGuidance(expectedBars)
    override fun emitGuidance() = controller.emitGuidance()
    override fun releasePlayer(player: Player, reason: WorksitePlayerReleaseReason) = controller.releasePlayer(player)
    override fun isActive(identity: ServiceItemIdentity) = controller.isActive(identity)
    override fun release(playerId: UUID, identity: ServiceItemIdentity, reason: WorksitePlayerReleaseReason) = controller.releaseIdentity(playerId)

    fun sawmillSnapshot(): Map<String, Any> = with(controller.state) { linkedMapOf(
        "active" to isAvailable(), "world" to rules.world, "phase" to phase.name, "round_id" to roundId,
        "pass" to sawPasses, "running" to sawRunning, "completed_rounds" to completedRounds,
        "index" to controller.indexStatus.name,
        "index_report" to controller.indexReport(),
        "pending" to controller.transitionPending, "incident" to (incident?.type?.name ?: "none"),
        "incident_generation" to incidentGeneration, "incident_handled_round_id" to incidentHandledRoundId,
        "incident_turns" to (incident?.crankTurns ?: 0), "incident_operator" to (incident?.crankOperator?.toString() ?: ""),
        "incident_completed" to (incident?.hotspots?.completed ?: incident?.crankTurns ?: 0),
        "incident_total" to when (incident?.type) { LumberIncidentType.FEED_JAM -> LumberIncidentEngine.CRANK_TURNS
            LumberIncidentType.SAWDUST_SMOLDER -> LumberIncidentEngine.SMOLDER_PILES; null -> 0 },
    ) }

    override fun zoneIds() = if (isAvailable()) listOf(LumberEngine.ZONE_ID) else emptyList()
    override fun incidentIds(): List<String> = LumberIncidentType.entries.map { it.name.lowercase() }
    override fun status(zoneId: String): WorksiteAdminStatus? = controller.state.takeIf {
        isAvailable() && zoneId == LumberEngine.ZONE_ID
    }?.let { WorksiteAdminStatus(kind, zoneId, it.phase.name.lowercase(), it.roundId,
        it.incident?.type?.name?.lowercase(), it.incident?.hotspots) }
    override fun start(zoneId: String, player: Player): Boolean {
        if (!canAccess(player) || zoneId != LumberEngine.ZONE_ID) return false
        controller.onMove(player)
        controller.tick(clock())
        return true
    }
    override fun forceIncident(zoneId: String, incidentId: String, now: Long): Boolean =
        incidentType(zoneId, incidentId)?.let(controller::forceIncident) == true
    override fun forceIncidentAsync(zoneId: String, incidentId: String, now: Long): java.util.concurrent.CompletableFuture<Boolean> =
        incidentType(zoneId, incidentId)?.let(controller::forceIncidentAsync) ?: java.util.concurrent.CompletableFuture.completedFuture(false)
    private fun incidentType(zoneId: String, id: String): LumberIncidentType? =
        LumberIncidentType.entries.firstOrNull { isAvailable() && zoneId == LumberEngine.ZONE_ID && it.name.equals(id, ignoreCase = true) }
    override fun startReindex(zoneId: String): Boolean = false
    override fun tickReindex(zoneId: String, budget: Int): WorksiteAdminReindexTick? = null
    override fun cancelReindex(zoneId: String): Boolean = false
}
