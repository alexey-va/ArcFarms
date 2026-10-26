package ru.ruscrafting.farms.paper.lumber

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.LumbermillSettings
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.FarmEventTypeRegistry
import ru.ruscrafting.farms.domain.lumber.*
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksiteCrankSampleStatus
import ru.ruscrafting.farms.domain.worksite.WorksiteCrankState
import ru.ruscrafting.farms.domain.worksite.WorksiteCrankTracker
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.lumber.scene.LumberPresentation
import ru.ruscrafting.farms.paper.lumber.scene.LumberSceneGeometry
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteCrankTethers
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteWaterJet
import java.util.UUID
import java.util.logging.Level
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot

/** Owns repair inputs, private tethers and transient samples; every durable change returns to the round owner. */
internal class LumberIncidentController(
    private val rules: LumbermillSettings,
    private val locale: ArcFarmsLocale,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val storage: WorksiteStatePort,
    private val items: WorksiteServiceItems,
    private val presentation: LumberPresentation,
    private val tethers: WorksiteCrankTethers,
) {
    private var generation = 0L
    private var scope: String? = null
    private var operator: UUID? = null
    private var turns = 0
    private var sample: WorksiteCrankState? = null
    private var partialRadians = 0.0
    private var tetherReady = false
    private var retryTetherAt = 0L
    private var physicalAngle = 0.0

    val crankRadians: Double get() = physicalAngle

    fun schedule(current: LumberState): LumberState = LumberIncidentEngine.schedule(current, positions())

    fun force(current: LumberState, type: LumberIncidentType): LumberState =
        LumberIncidentEngine.start(current, type, positions(), force = true)

    private fun positions(): List<WorksitePosition> = LumberSceneGeometry.smolderTargets().map {
        WorksitePosition(rules.world, floor(it.x).toInt(), floor(it.y).toInt(), floor(it.z).toInt())
    }

    fun hoseIdentity(current: LumberState): ServiceItemIdentity? = current.incident
        ?.takeIf { it.type == LumberIncidentType.SAWDUST_SMOLDER }?.let {
            ServiceItemIdentity(ActivityKind.LUMBER, LumberEngine.ZONE_ID, current.roundId, it.generation,
                ObjectiveTargetRole("hose"), "lumber_hose")
        }

    fun ensureHose(current: LumberState, player: Player) {
        if (!eligible(player)) return
        val identity = hoseIdentity(current) ?: return
        if (!items.has(player, identity)) items.issueTool(player, identity, Material.BLAZE_ROD,
            locale.render(MessageKey.LUMBER_HOSE_NAME, player))
    }

    fun ownsHose(player: Player): Boolean = eligible(player) && items.identity(player.inventory.itemInMainHand)?.let {
        it.activity == ActivityKind.LUMBER && it.zoneId == LumberEngine.ZONE_ID && it.itemId == "lumber_hose"
    } == true

    /** null means an unrelated input; the unchanged state means a consumed, non-progressing input. */
    fun input(current: LumberState, input: LumberInput?, player: Player, now: Long): LumberState? {
        if (!eligible(player)) return null
        val incident = current.incident ?: return null
        if (input == null && !ownsHose(player)) return null
        if (!access.allowInteraction("lumber-incident:${incident.generation}:${player.uniqueId}", rules.actionCooldownMillis)) return current
        return when (incident.type) {
            LumberIncidentType.FEED_JAM -> {
                if (input?.action != LumberAction.START_CRANK) return current.takeIf { input != null }
                if (incident.crankOperator != null && incident.crankOperator != player.uniqueId) {
                    audience.sendActionBar(player, MessageKey.LUMBER_CRANK_BUSY)
                    return current
                }
                if (!nearCrank(player)) {
                    audience.sendActionBar(player, MessageKey.LUMBER_TOO_FAR)
                    return current
                }
                LumberIncidentEngine.startCrank(current, player.uniqueId)
            }
            LumberIncidentType.SAWDUST_SMOLDER -> {
                if (!ownsHose(player)) {
                    if (input?.action != LumberAction.EXTINGUISH_SAWDUST) return current.takeIf { input != null }
                    ensureHose(current, player)
                    audience.sendActionBar(player, MessageKey.LUMBER_NEED_HOSE)
                    return current
                }
                if (items.identity(player.inventory.itemInMainHand) != hoseIdentity(current)) {
                    audience.sendActionBar(player, MessageKey.LUMBER_NEED_HOSE)
                    return current
                }
                presentation.spray(player)
                val eye = player.eyeLocation
                val targets = incident.hotspots?.targets.orEmpty().filter { it.status == ObjectiveTargetStatus.AVAILABLE }.mapNotNull { target ->
                    val index = target.id.removePrefix("pile_").toIntOrNull() ?: return@mapNotNull null
                    val point = LumberSceneGeometry.smolderTargets().getOrNull(index) ?: return@mapNotNull null
                    val at = Location(player.world, point.x, point.y, point.z)
                    (target.id to at).takeIf { eye.distanceSquared(at) <= LumberSceneGeometry.HOSE_RANGE * LumberSceneGeometry.HOSE_RANGE }
                }
                val hit = WorksiteWaterJet.hitTargets(targets, eye, eye.direction, LumberSceneGeometry.HOSE_RANGE, .45).firstOrNull()
                    ?: return current
                LumberIncidentEngine.extinguish(current, hit, player.uniqueId, incident.generation, now)
            }
        }
    }

    /** Reconciles only committed state. Missing operators cannot keep hidden native anchors. */
    fun reconcile(current: LumberState, viewers: Collection<Player>, now: Long) {
        val incident = current.incident
        if (incident?.generation != generation || incident.crankOperator != operator) {
            clearTransient()
            generation = incident?.generation ?: 0L
            operator = incident?.crankOperator
            turns = incident?.crankTurns ?: 0
            scope = incident?.let { "lumber-incident:${current.roundId}:${it.generation}" }
        } else if (incident.crankTurns != turns) {
            partialRadians = 0.0
            turns = incident.crankTurns
        }
        val eligibleViewers = viewers.filter(::eligible)
        val player = eligibleViewers.firstOrNull { it.uniqueId == operator }
        val ownedScope = scope
        if (incident?.type != LumberIncidentType.FEED_JAM || player == null || ownedScope == null || !nearCrank(player)) {
            ownedScope?.let(tethers::clear)
            tetherReady = false
            return
        }
        val point = LumberSceneGeometry.incidentCrank()
        physicalAngle = atan2(player.location.z - point.z, player.location.x - point.x)
        if (now < retryTetherAt) return
        val result = tethers.attach(ownedScope, player, Location(player.world, point.x, point.y + .35, point.z), eligibleViewers)
        tetherReady = result.isSuccess
        if (result.getOrNull() == true) audience.sendActionBar(player, MessageKey.LUMBER_FEED_JAM_WINDING_HINT)
        result.exceptionOrNull()?.let { failure ->
            retryTetherAt = now + 1_000L
            if (access.allowInteraction("lumber-crank-tether-failed", 30_000L))
                storage.log(Level.WARNING, "Lumber crank tether unavailable: round=${current.roundId} generation=${incident.generation}", failure)
        }
    }

    fun update(current: LumberState, participants: Collection<Player>, now: Long): LumberState {
        val incident = current.incident ?: return current
        if (incident.type != LumberIncidentType.FEED_JAM) return current
        val actor = incident.crankOperator ?: return current
        val player = participants.firstOrNull { it.uniqueId == actor && eligible(it) }
        if (player == null || !nearCrank(player)) return LumberIncidentEngine.release(current, actor)
        if (!inRing(player)) { sample = null; partialRadians = 0.0; return current }
        if (!tetherReady) return current
        val at = player.location
        val center = LumberSceneGeometry.incidentCrank()
        val next = WorksiteCrankTracker.sample(sample, at.x, at.z, center.x, center.z,
            LumberSceneGeometry.CRANK_INNER_RADIUS, LumberSceneGeometry.CRANK_OUTER_RADIUS, .2, 1.2)
        sample = next.state
        physicalAngle = next.state?.angle ?: physicalAngle
        if (next.status in setOf(WorksiteCrankSampleStatus.REVERSED, WorksiteCrankSampleStatus.TELEPORTED)) partialRadians = 0.0
        if (next.state == null) return LumberIncidentEngine.release(current, actor)
        partialRadians += next.acceptedRadians
        if (partialRadians + .0001 < WorksiteCrankTracker.FULL_LAP_RADIANS) return current
        return LumberIncidentEngine.completeTurn(current, actor, incident.generation, now)
    }

    private fun inRing(player: Player): Boolean {
        val center = LumberSceneGeometry.incidentCrank()
        val at = player.location
        return player.world.name == rules.world && abs(at.y - center.y) <= 1.0 &&
            hypot(at.x - center.x, at.z - center.z) in
            (LumberSceneGeometry.CRANK_INNER_RADIUS - .2)..(LumberSceneGeometry.CRANK_OUTER_RADIUS + .2)
    }

    private fun nearCrank(player: Player): Boolean {
        val center = LumberSceneGeometry.incidentCrank()
        val at = player.location
        return player.world.name == rules.world &&
            (at.x - center.x).let { it * it } + (at.y - center.y).let { it * it } + (at.z - center.z).let { it * it } <= 9.0
    }

    private fun eligible(player: Player): Boolean = player.hasPermission(LumbermillSettings.BETA_PERMISSION) && player.isOnline &&
        !player.isDead && player.world.name == rules.world && player.location.let { rules.worksite.contains(it.blockX, it.blockY, it.blockZ) }

    fun releasePlayer(playerId: UUID) {
        if (operator != playerId) return
        scope?.let { tethers.release(it, playerId) }
        sample = null
        partialRadians = 0.0
        tetherReady = false
        physicalAngle = 0.0
        retryTetherAt = 0L
    }

    fun hint(current: LumberState, playerId: UUID): MessageKey? = current.incident?.let {
        when (it.type) {
            LumberIncidentType.FEED_JAM -> when (it.crankOperator) {
                null -> MessageKey.LUMBER_FEED_JAM_HINT
                playerId -> MessageKey.LUMBER_FEED_JAM_WINDING_HINT
                else -> MessageKey.LUMBER_CRANK_BUSY
            }
            LumberIncidentType.SAWDUST_SMOLDER -> MessageKey.LUMBER_SAWDUST_SMOLDER_HINT
        }
    }

    fun definition(current: LumberState) = current.incident?.let { FarmEventTypeRegistry.definition(it.type) }

    fun progress(current: LumberState): Pair<Int, Int>? = current.incident?.let {
        when (it.type) {
            LumberIncidentType.FEED_JAM -> it.crankTurns to LumberIncidentEngine.CRANK_TURNS
            LumberIncidentType.SAWDUST_SMOLDER -> (it.hotspots?.completed ?: 0) to LumberIncidentEngine.SMOLDER_PILES
        }
    }

    fun targets(current: LumberState): List<LumberPose> = when (current.incident?.type) {
        LumberIncidentType.FEED_JAM -> listOf(LumberSceneGeometry.incidentCrank())
        LumberIncidentType.SAWDUST_SMOLDER -> current.incident.hotspots?.targets.orEmpty()
            .filter { it.status == ObjectiveTargetStatus.AVAILABLE }.mapNotNull { target ->
                target.id.removePrefix("pile_").toIntOrNull()?.let { LumberSceneGeometry.smolderTargets().getOrNull(it) }
            }
        null -> emptyList()
    }

    private fun clearTransient() {
        scope?.let(tethers::clear)
        scope = null
        operator = null
        sample = null
        partialRadians = 0.0
        tetherReady = false
        physicalAngle = 0.0
        retryTetherAt = 0L
    }

    fun cleanup() {
        clearTransient()
        tethers.cleanup(Bukkit.getWorld(rules.world)?.entities.orEmpty())
        generation = 0L
    }
}
