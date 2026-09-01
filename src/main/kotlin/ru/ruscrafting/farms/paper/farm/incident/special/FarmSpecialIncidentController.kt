package ru.ruscrafting.farms.paper.farm.incident.special

import net.kyori.adventure.text.Component
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.data.Ageable
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmCropDamage
import ru.ruscrafting.farms.domain.FARM_CHANNEL_ROUTE_NAME
import ru.ruscrafting.farms.domain.FarmGiantCropBlueprint
import ru.ruscrafting.farms.domain.FarmGiantCropCandidate
import ru.ruscrafting.farms.domain.FarmGiantCropCandidateSelection
import ru.ruscrafting.farms.domain.FarmGiantCropCandidateSelector
import ru.ruscrafting.farms.domain.FarmIncidentPlanner
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmMarketTimer
import ru.ruscrafting.farms.domain.FarmMatureCrop
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmSpecialCropPolicy
import ru.ruscrafting.farms.domain.FarmSpecialIncidentEngine
import ru.ruscrafting.farms.domain.FarmSpecialIncidentPlanner
import ru.ruscrafting.farms.domain.FarmSpecialIncidentState
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmBlockPolicy
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmGiantCropController
import ru.ruscrafting.farms.paper.FarmMarketClick
import ru.ruscrafting.farms.paper.FarmMarketDecision
import ru.ruscrafting.farms.paper.FarmMarketMenu
import ru.ruscrafting.farms.paper.FarmNightShiftController
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.FarmSpecialIncidentSceneManager
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.deferInventoryTransition
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import ru.ruscrafting.farms.paper.block
import ru.ruscrafting.farms.paper.farm.FarmIncidentBedProvider
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.field.FARM_SOIL_TYPES
import ru.ruscrafting.farms.paper.farm.harvest.FarmCropBreakEffects
import ru.ruscrafting.farms.paper.farm.placement.FarmSurfacePolicy
import ru.ruscrafting.farms.paper.toFarmPlotPosition
import java.util.Locale
import java.util.UUID
import java.util.logging.Level
import kotlin.math.floor

internal val SPECIAL_FARM_INCIDENT_TYPES =
    setOf(FarmIncidentType.GIANT_CROP, FarmIncidentType.CHANNELS, FarmIncidentType.NIGHT_SHIFT, FarmIncidentType.MARKET)

private const val MAX_GIANT_CROP_PLACEMENT_CHECKS = 128
private data class ChannelTaskKey(val zoneId: String, val sequence: Long)

/** Complete owner for giant crop, channels, night shift and urgent market incidents. */
internal class FarmSpecialIncidentController(
    plugin: Plugin,
    private val settings: () -> ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val state: WorksiteStatePort,
    private val tasks: WorksiteTaskPort,
    private val serviceItems: WorksiteServiceItems,
    private val ledger: FarmBlockLedger,
    private val registry: FarmBlockRegistry,
    private val beds: FarmIncidentBedProvider,
    private val points: FarmPointProvider,
    private val transitions: FarmTransitionSink,
    private val runtimes: () -> Collection<FarmRuntime>,
    private val clock: () -> Long,
    private val nightShift: FarmNightShiftController,
) {
    private val scene = FarmSpecialIncidentSceneManager(plugin, debug)
    private val giantCrop = FarmGiantCropController(plugin)
    private val marketMenu = FarmMarketMenu(locale, settings, ::refreshMarketView)
    private val giantSelectionAttempts = mutableMapOf<String, Long>()
    private val channelFlowTasks = mutableSetOf<ChannelTaskKey>()
    private val channelCompletionTasks = mutableSetOf<ChannelTaskKey>()

    fun ensure(runtime: FarmRuntime) {
        val type = runtime.state.incidentType
        if (runtime.state.phase != FarmPhase.INCIDENT || type !in SPECIAL_FARM_INCIDENT_TYPES) {
            scene.clearZone(runtime.settings.id, "inactive")
            nightShift.clearZone(runtime.settings.id)
            return
        }
        val activeType = if (runtime.state.specialIncident == null) initialize(runtime, requireNotNull(type)) else type
            ?: return
        val special = runtime.state.specialIncident ?: return
        when (activeType) {
            FarmIncidentType.GIANT_CROP -> ensureGiantCrop(runtime, special)
            FarmIncidentType.CHANNELS -> ensureChannels(runtime, special)
            FarmIncidentType.NIGHT_SHIFT -> ensureNightShift(runtime, special)
            FarmIncidentType.MARKET -> scene.clearZone(runtime.settings.id, "market")
            else -> Unit
        }
    }

    fun initialize(runtime: FarmRuntime, type: FarmIncidentType): FarmIncidentType? {
        if (type !in SPECIAL_FARM_INCIDENT_TYPES) return null
        if (runtime.state.specialIncident != null) return runtime.state.incidentType
        val incidentBeds = beds.discover(runtime)
        val mature = incidentBeds.mapNotNull { plot ->
            val soil = plot.block() ?: return@mapNotNull null
            val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
            val age = crop.blockData as? Ageable ?: return@mapNotNull null
            if (age.age != age.maximumAge || crop.type.name !in runtime.settings.crops) return@mapNotNull null
            FarmMatureCrop(plot, crop.type.name)
        }
        val specialSettings = runtime.settings.specialIncidents
        val indexedBedCount = registry.beds(runtime.settings.id).size
        val requestedPatrols = specialSettings.nightPatrolCount(indexedBedCount)
        var giantSelection: FarmGiantCropCandidateSelection? = null
        val order = runtime.state.orderId?.let(runtime.orders::get)
        val enabledOrderTypes = order?.incidentTypes.orEmpty().filter { candidate ->
            candidate != FarmIncidentType.CHANNELS || specialSettings.channelAutomaticEnabled
        }
        val configuredTypes = enabledOrderTypes.filter(SPECIAL_FARM_INCIDENT_TYPES::contains)
        val scheduledTypes = order?.let {
            FarmIncidentPlanner.sequence(
                enabledOrderTypes,
                runtime.rules.incidentTargetCount(runtime.state.sequence),
                runtime.state.sequence,
            )
        }.orEmpty()
        val candidateTypes = if (type == FarmIncidentType.CHANNELS && !specialSettings.channelAutomaticEnabled) {
            listOf(type)
        } else {
            (listOf(type) + configuredTypes.filterNot(scheduledTypes::contains) + configuredTypes).distinct()
        }
        val selected = candidateTypes.firstNotNullOfOrNull { candidateType ->
            val giantCandidates = if (candidateType == FarmIncidentType.GIANT_CROP) {
                val selection = giantSelection ?: selectGiantCandidate(runtime, mature).also { giantSelection = it }
                listOfNotNull(selection.candidate)
            } else emptyList()
            FarmSpecialIncidentPlanner.plan(
                type = candidateType,
                sequence = runtime.state.placementSequence,
                matureCrops = mature,
                giantCandidates = giantCandidates,
                nightPatrolPlots = incidentBeds,
                fallbackPlot = areaCenter(runtime.state.preparationPatch),
                irrigationSource = points.resolve(runtime, FarmPointKind.IRRIGATION),
                channelBlockages = specialSettings.channelSegmentCount,
                nightCropPlacements = specialSettings.nightCropPlacementCount,
                nightCropTarget = specialSettings.nightCropTargetCount,
                nightCropMinSpacing = specialSettings.nightCropMinSpacing,
                nightPatrols = requestedPatrols,
                nightPatrolMinSpacing = specialSettings.nightPatrolMinSpacing,
                marketCrops = specialSettings.marketCropCount,
            )?.let { candidateType to it }
        }
        if (selected == null) {
            val skipped = FarmSpecialIncidentEngine.skipUnavailable(runtime.state)
            if (skipped.accepted) runtime.state = skipped.state
            state.log(Level.WARNING, "Skipped unavailable farm incident $type in ${runtime.settings.id}")
            debug.event(
                "farm_special_incident_unavailable",
                "zone" to runtime.settings.id,
                "sequence" to runtime.state.sequence,
                "type" to type,
                "mature_crops" to mature.size,
                "giant_candidates" to giantSelection?.considered,
                "giant_candidates_checked" to giantSelection?.checked,
                "giant_candidates_rejected" to giantSelection?.rejected,
            )
            state.persistAsync()
            return null
        }
        val (activeType, plan) = selected
        if (activeType != type) {
            val retargeted = FarmSpecialIncidentEngine.retargetUninitialized(runtime.state, activeType)
            if (!retargeted.accepted) return null
            runtime.state = retargeted.state
            debug.event(
                "farm_special_incident_retargeted",
                "zone" to runtime.settings.id,
                "sequence" to runtime.state.sequence,
                "requested_type" to type,
                "active_type" to activeType,
            )
        }
        val initialized = FarmSpecialIncidentEngine.initialize(runtime.state, activeType, plan.state, plan.required)
        if (!initialized.accepted) return null
        runtime.state = initialized.state
        debug.event(
            "farm_special_incident_initialized",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "type" to activeType,
            "required" to runtime.state.incidentRequired,
            "indexed_beds" to indexedBedCount,
            "incident_beds" to incidentBeds.size,
            "patrols_requested" to requestedPatrols,
            "patrols_planned" to plan.state.points.size,
            "giant_candidates" to giantSelection?.considered,
            "giant_candidates_checked" to giantSelection?.checked,
        )
        state.persistAsync()
        return activeType
    }

    fun announce(runtime: FarmRuntime, type: FarmIncidentType) {
        val (title, sound) = when (type) {
            FarmIncidentType.GIANT_CROP -> MessageKey.FARM_GIANT_CROP_STARTED to Sound.BLOCK_ROOTED_DIRT_BREAK
            FarmIncidentType.CHANNELS -> MessageKey.FARM_CHANNELS_STARTED to Sound.BLOCK_CONDUIT_ACTIVATE
            FarmIncidentType.NIGHT_SHIFT -> MessageKey.FARM_NIGHT_SHIFT_STARTED to Sound.BLOCK_AMETHYST_BLOCK_RESONATE
            FarmIncidentType.MARKET -> MessageKey.FARM_MARKET_STARTED to Sound.ENTITY_VILLAGER_TRADE
            else -> return
        }
        audience.broadcast(
            listOf(runtime.region),
            title,
            mapOf("total" to locale.text(runtime.state.incidentRequired)),
            sound,
            title = true,
            valuesForPlayer = { player ->
                buildMap {
                    put("total", locale.text(runtime.state.incidentRequired))
                    runtime.state.specialIncident?.crop?.let { crop ->
                        put("crop", locale.renderPath("crop.${crop.lowercase()}", player))
                    }
                }
            },
        )
    }

    fun handleCropBreak(runtime: FarmRuntime, player: Player, block: Block): Boolean {
        if (handleGiantCropHit(runtime, player, block)) return true
        val type = runtime.state.incidentType ?: return false
        if (runtime.state.phase != FarmPhase.INCIDENT || type !in setOf(FarmIncidentType.NIGHT_SHIFT, FarmIncidentType.MARKET)) {
            return false
        }
        if (type == FarmIncidentType.MARKET && expireMarket(runtime, clock())) return true
        val special = runtime.state.specialIncident ?: return true
        if (!access.hasAccess(player, runtime.settings.permission)) {
            audience.sendChat(player, MessageKey.ZONE_LOCKED)
            return true
        }
        if (type == FarmIncidentType.MARKET && !special.marketAccepted) {
            audience.sendActionBar(player, MessageKey.FARM_MARKET_REQUIRED)
            return true
        }
        val soil = block.getRelative(org.bukkit.block.BlockFace.DOWN)
        val position = soil.toFarmPlotPosition()
        val data = block.blockData as? Ageable
        val expected = special.crop?.let(MaterialRules::material)
        val valid = FarmSpecialCropPolicy.isEligible(
            type = type,
            position = position,
            plannedPlots = special.plots,
            managedPlots = if (type == FarmIncidentType.MARKET) {
                registry.beds(runtime.settings.id) + runtime.state.preparationPatch
            } else beds.discover(runtime),
        ) && data != null && data.age == data.maximumAge && (expected == null || block.type == expected)
        if (!valid) {
            audience.sendActionBar(
                player,
                if (type == FarmIncidentType.MARKET) MessageKey.FARM_MARKET_ACTIVE else MessageKey.FARM_SPECIAL_PROGRESS,
                buildMap {
                    put("event", name(type, player))
                    put("crop", expected?.let(MaterialRules::cropComponent) ?: Component.empty())
                    put("done", locale.text(runtime.state.incidentProgress))
                    put("total", locale.text(runtime.state.incidentRequired))
                    if (type == FarmIncidentType.MARKET) put("time", locale.text(marketTime(runtime, special)))
                },
            )
            return true
        }
        val crop = block.type
        ledger.captureActiveCrop(soil, runtime.settings.id)
        val result = FarmSpecialIncidentEngine.harvestSpecialCrop(
            runtime.state,
            type,
            FarmCropDamage(position, crop.name),
            player.uniqueId,
            runtime.settings.specialIncidents.marketMoneyBonusPercent,
        )
        if (!result.accepted) return true
        if (type == FarmIncidentType.MARKET) {
            val replanted = data.clone() as Ageable
            replanted.age = 0
            block.setBlockData(replanted, false)
            ledger.captureActiveCrop(soil, runtime.settings.id)
        } else block.setType(Material.AIR, false)
        if (settings().particles) block.world.spawnParticle(
            if (type == FarmIncidentType.NIGHT_SHIFT) Particle.END_ROD else Particle.HAPPY_VILLAGER,
            block.location.toCenterLocation().add(0.0, 0.65, 0.0), 4, 0.2, 0.25, 0.2, 0.01,
        )
        if (settings().sounds) player.playSound(
            block.location,
            if (type == FarmIncidentType.NIGHT_SHIFT) Sound.BLOCK_AMETHYST_BLOCK_CHIME else Sound.ENTITY_VILLAGER_TRADE,
            0.7f,
            1.2f + runtime.state.incidentProgress.coerceAtMost(12) * 0.025f,
        )
        transitions.apply(runtime, result, player)
        return true
    }

    fun ownsScene(entity: Entity): Boolean = scene.owns(entity)

    fun ownsGiantBlock(runtime: FarmRuntime, block: Block): Boolean =
        giantCrop.owns(block, runtime.settings.id, runtime.state.sequence)

    fun interactScene(player: Player, entity: Entity) {
        // Legacy blockage displays are inert. The drainage-v2 objective is completed only by digging its soil blocks.
        if (scene.metadata(entity) != null) audience.sendActionBar(player, MessageKey.FARM_CHANNELS_TOOL)
    }

    fun handleChannelBreak(runtime: FarmRuntime, player: Player, block: Block): Boolean {
        if (runtime.state.phase != FarmPhase.INCIDENT || runtime.state.incidentType != FarmIncidentType.CHANNELS) return false
        val special = runtime.state.specialIncident ?: return false
        val index = special.points.indexOfFirst { point ->
            point.world == block.world.name && floor(point.x).toInt() == block.x &&
                floor(point.y).toInt() - 1 == block.y && floor(point.z).toInt() == block.z
        }
        if (index < 0 || index in special.solution) return false
        if (!access.hasAccess(player, runtime.settings.permission)) {
            audience.sendChat(player, MessageKey.ZONE_LOCKED)
            return true
        }
        if (serviceItems.identity(player.inventory.itemInMainHand) != channelToolIdentity(runtime)) {
            audience.sendActionBar(player, MessageKey.FARM_CHANNELS_TOOL)
            return true
        }
        if (block.type !in FARM_SOIL_TYPES) return true
        if (settings().particles) {
            block.world.spawnParticle(Particle.BLOCK_CRUMBLE, block.location.toCenterLocation(), 12, 0.35, 0.2, 0.35, block.blockData)
            block.world.spawnParticle(Particle.DUST_PLUME, block.location.toCenterLocation(), 4, 0.3, 0.12, 0.3, 0.01)
        }
        block.setType(Material.AIR, false)
        if (settings().sounds) player.playSound(block.location, Sound.BLOCK_GRAVEL_BREAK, 0.9f, 0.85f)
        val result = FarmSpecialIncidentEngine.digChannelSegment(runtime.state, index, player.uniqueId)
        if (result.accepted) {
            transitions.apply(runtime, result, player)
            scheduleChannelFlow(runtime)
        }
        return true
    }

    fun handleInventoryClick(event: InventoryClickEvent): Boolean {
        val player = event.whoClicked as? Player ?: return false
        val click = marketMenu.handleClick(event) ?: return false
        handleMarketDecision(player, event.view.topInventory, click)
        return true
    }

    fun handleInventoryDrag(event: InventoryDragEvent): Boolean = marketMenu.handleDrag(event)

    fun openMarket(player: Player, runtime: FarmRuntime, special: FarmSpecialIncidentState) {
        val crop = MaterialRules.material(requireNotNull(special.crop))
        if (special.marketAccepted) marketMenu.openActive(
            player,
            runtime.settings.id,
            runtime.state.sequence,
            crop,
            runtime.state.incidentProgress,
            runtime.state.incidentRequired,
            runtime.settings.specialIncidents.marketMoneyBonusPercent,
            marketTime(runtime, special),
        ) else marketMenu.openPending(
            player,
            runtime.settings.id,
            runtime.state.sequence,
            crop,
            runtime.state.incidentRequired,
            runtime.settings.specialIncidents.marketMoneyBonusPercent,
            marketTime(runtime, special),
        )
        debug.event(
            "farm_market_menu_opened",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "player" to player.name,
            "accepted" to special.marketAccepted,
            "crop" to special.crop,
            "progress" to runtime.state.incidentProgress,
            "required" to runtime.state.incidentRequired,
        )
    }

    private fun refreshMarketView(player: Player, zoneId: String, sequence: Long) {
        val runtime = runtimes().firstOrNull { it.settings.id == zoneId }
        val special = runtime?.state?.specialIncident
        if (
            runtime == null || runtime.state.sequence != sequence || runtime.state.phase != FarmPhase.INCIDENT ||
            runtime.state.incidentType != FarmIncidentType.MARKET || special == null
        ) {
            player.closeInventory()
            return
        }
        openMarket(player, runtime, special)
    }

    fun marketValues(runtime: FarmRuntime, special: FarmSpecialIncidentState, audience: Player?): Map<String, Component> {
        val crop = MaterialRules.material(requireNotNull(special.crop))
        return mapOf(
            "crop" to locale.renderPath("crop.${crop.name.lowercase()}", audience),
            "done" to locale.text(runtime.state.incidentProgress),
            "total" to locale.text(runtime.state.incidentRequired),
            "time" to locale.text(marketTime(runtime, special)),
            "event" to locale.renderPath("incident.market.name", audience),
        )
    }

    fun marketTime(runtime: FarmRuntime, special: FarmSpecialIncidentState): String {
        val seconds = if (special.marketAccepted && special.marketDeadlineAt > 0) {
            FarmMarketTimer.remainingSeconds(special.marketDeadlineAt, clock())
        } else marketDurationMillis(runtime) / 1_000L
        return "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
    }

    fun expireMarket(runtime: FarmRuntime, now: Long): Boolean {
        if (runtime.state.phase != FarmPhase.INCIDENT || runtime.state.incidentType != FarmIncidentType.MARKET) return false
        val special = runtime.state.specialIncident?.takeIf { it.marketAccepted } ?: return false
        if (special.marketDeadlineAt == 0L) {
            val duration = marketDurationMillis(runtime)
            val deadline = if (Long.MAX_VALUE - now < duration) Long.MAX_VALUE else now + duration
            runtime.state = runtime.state.copy(specialIncident = special.copy(marketDeadlineAt = deadline))
            debug.event(
                "farm_market_deadline_migrated",
                "zone" to runtime.settings.id,
                "sequence" to runtime.state.sequence,
                "deadline" to deadline,
            )
            state.persistAsync()
            return false
        }
        val result = FarmSpecialIncidentEngine.expireMarket(runtime.state, now)
        if (!result.accepted) return false
        transitions.apply(runtime, result, null)
        return true
    }

    fun name(type: FarmIncidentType, audience: Player): Component = locale.renderPath("incident.${type.configId()}.name", audience)

    fun id(type: FarmIncidentType): String = type.configId()

    fun onQuit(player: Player) = nightShift.clear(player)

    fun leaveZone(player: Player, runtime: FarmRuntime) {
        val identity = channelToolIdentity(runtime)
        while (serviceItems.consume(player, identity)) Unit
    }

    fun ownsNightEntity(entity: Entity): Boolean = nightShift.owns(entity)

    fun handleNightDamage(event: EntityDamageEvent): Boolean {
        if (event is EntityDamageByEntityEvent && nightShift.protectReceiving(event)) return true
        if (!nightShift.owns(event.entity)) return false
        event.isCancelled = true
        val attacker = (event as? EntityDamageByEntityEvent)?.let { damage ->
            when (val damager = damage.damager) {
                is Player -> damager
                is Projectile -> damager.shooter as? Player
                else -> null
            }
        }
        if (attacker != null && access.allowInteraction("farm-night-patrol:${attacker.uniqueId}", 1_500L)) {
            audience.sendActionBar(attacker, MessageKey.FARM_NIGHT_PATROL_AVOID)
        }
        return true
    }

    fun updateLights(runtime: FarmRuntime) = nightShift.updateLights(
        runtime.settings.id,
        runtime.region,
        runtime.settings.specialIncidents.nightPatrolLightLevel,
    )

    fun updatePlayerTimes() = nightShift.updatePlayerTimes()

    fun onChunkLoad(chunk: Chunk) {
        scene.onChunkLoad(chunk)
        nightShift.onChunkLoad(chunk)
        giantCrop.onChunkLoad(chunk) { zoneId, sequence ->
            runtimes().any { runtime ->
                runtime.settings.id == zoneId && runtime.state.sequence == sequence &&
                    runtime.state.phase == FarmPhase.INCIDENT && runtime.state.incidentType == FarmIncidentType.GIANT_CROP &&
                    runtime.state.specialIncident != null
            }
        }
    }

    fun beginRestore(runtime: FarmRuntime) = giantCrop.beginRestore(runtime.region.world, runtime.settings.id, runtime.state.sequence)

    fun processRestores(limit: Int): Set<Chunk> = giantCrop.processRestores(limit)

    fun restoreZoneNow(runtime: FarmRuntime): Set<Chunk> = giantCrop.restoreZoneNow(runtime.region.world, runtime.settings.id)

    fun clearZone(runtime: FarmRuntime, reason: String) {
        val channelKey = ChannelTaskKey(runtime.settings.id, runtime.state.sequence)
        channelFlowTasks.remove(channelKey)
        channelCompletionTasks.remove(channelKey)
        scene.clearZone(runtime.settings.id, reason)
        nightShift.clearZone(runtime.settings.id)
        if (runtime.state.incidentType == FarmIncidentType.CHANNELS) {
            runtime.state.specialIncident?.points.orEmpty().forEach { point ->
                val block = runtime.region.world.getBlockAt(
                    floor(point.x).toInt(),
                    floor(point.y).toInt(),
                    floor(point.z).toInt(),
                )
                val position = block.getRelative(org.bukkit.block.BlockFace.DOWN).toFarmPlotPosition()
                val damage = runtime.state.specialDamagedCrops.firstOrNull { it.position == position } ?: return@forEach
                if (!block.type.isAir && block.type.name != damage.crop) block.setType(Material.AIR, false)
            }
        }
        val identity = channelToolIdentity(runtime)
        org.bukkit.Bukkit.getOnlinePlayers().forEach { player -> while (serviceItems.consume(player, identity)) Unit }
    }

    fun isServiceItemActive(identity: ServiceItemIdentity): Boolean {
        if (identity.activity != ActivityKind.FARM || identity.itemId != CHANNEL_TOOL_ID) return false
        val runtime = runtimes().firstOrNull { it.settings.id == identity.zoneId } ?: return false
        return runtime.state.phase == FarmPhase.INCIDENT && runtime.state.incidentType == FarmIncidentType.CHANNELS &&
            runtime.state.sequence == identity.sequence && runtime.state.placementSequence == identity.objectiveNonce
    }

    fun releaseServiceItem(playerId: UUID, identity: ServiceItemIdentity, reason: WorksitePlayerReleaseReason) {
        if (identity.activity != ActivityKind.FARM || identity.itemId != CHANNEL_TOOL_ID) return
        debug.event(
            "farm_channel_tool_released",
            "zone" to identity.zoneId,
            "player" to playerId,
            "reason" to reason,
        )
    }

    fun cleanup(reason: String) {
        giantSelectionAttempts.clear()
        channelFlowTasks.clear()
        channelCompletionTasks.clear()
        giantCrop.restoreLoadedAll(reason)
        scene.cleanupLoaded(reason)
        nightShift.clearAll(org.bukkit.Bukkit.getOnlinePlayers())
    }

    private fun ensureNightShift(runtime: FarmRuntime, special: FarmSpecialIncidentState) {
        scene.clearZone(runtime.settings.id, "night_shift")
        val sync = nightShift.sync(
            runtime.settings.id,
            runtime.state.sequence,
            runtime.region,
            audience.players(runtime.region),
            runtime.settings.specialIncidents.nightPlayerTime,
            special.points,
            points.resolve(runtime, FarmPointKind.RECEIVING),
            runtime.settings.specialIncidents,
            settings().particles,
        )
        if (sync.spawnedPatrols > 0 || sync.removedPatrols > 0) debug.event(
            "farm_night_patrols_reconciled",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "active" to sync.activePatrols,
            "spawned" to sync.spawnedPatrols,
            "removed" to sync.removedPatrols,
        )
    }

    private fun selectGiantCandidate(
        runtime: FarmRuntime,
        mature: Collection<FarmMatureCrop>,
    ): FarmGiantCropCandidateSelection {
        val candidates = buildList {
            mature.filter { FarmGiantCropBlueprint.supports(it.crop) }.forEach {
                add(FarmGiantCropCandidate(it.plot.copy(y = it.plot.y + 1), it.crop))
            }
            registry.fixedCrops(runtime.settings.id).forEach { position ->
                val block = position.block() ?: return@forEach
                if (FarmGiantCropBlueprint.supports(block.type.name)) {
                    add(FarmGiantCropCandidate(position, block.type.name))
                }
            }
        }
        return FarmGiantCropCandidateSelector.select(
            candidates = candidates,
            sequence = nextGiantSelectionKey(runtime),
            maxChecks = MAX_GIANT_CROP_PLACEMENT_CHECKS,
        ) { candidate ->
            val anchor = candidate.block.block() ?: return@select "missing_block"
            if (!FarmSurfacePolicy.isOpenAbove(anchor)) return@select "covered_anchor"
            giantCrop.placementIssue(runtime.region, anchor, candidate.crop, runtime.settings.crops)
        }.also { selection ->
            debug.event(
                "farm_giant_crop_candidates_discovered",
                "zone" to runtime.settings.id,
                "mature" to mature.size,
                "considered" to selection.considered,
                "checked" to selection.checked,
                "accepted" to (selection.candidate != null),
                "rejected" to selection.rejected,
            )
        }
    }

    private fun nextGiantSelectionKey(runtime: FarmRuntime): Long {
        val attempt = giantSelectionAttempts.merge(runtime.settings.id, 1L, Long::plus) ?: 1L
        return runtime.state.placementSequence * 1_000_003L + runtime.state.incidentsResolved * 101L + attempt - 1L
    }

    private fun ensureGiantCrop(runtime: FarmRuntime, special: FarmSpecialIncidentState) {
        val point = special.points.firstOrNull() ?: return
        val crop = special.crop ?: return
        val anchor = runtime.region.world.getBlockAt(floor(point.x).toInt(), floor(point.y).toInt(), floor(point.z).toInt())
        val sync = giantCrop.ensure(
            runtime.settings.id,
            runtime.state.sequence,
            runtime.region,
            anchor,
            crop,
            runtime.settings.crops,
            runtime.state.incidentProgress,
        ) ?: return
        val reconciled = FarmSpecialIncidentEngine.reconcileGiantCrop(runtime.state, sync.totalBlocks, sync.brokenBlocks)
        if (reconciled.accepted) transitions.apply(runtime, reconciled, null)
    }

    private fun ensureChannels(runtime: FarmRuntime, special: FarmSpecialIncidentState) {
        val persistedPlots = special.points.mapTo(hashSetOf()) { point ->
            FarmPlotPosition(
                point.world,
                floor(point.x).toInt(),
                floor(point.y).toInt() - 1,
                floor(point.z).toInt(),
            )
        }
        val damagedPlots = runtime.state.specialDamagedCrops.mapTo(hashSetOf(), FarmCropDamage::position)
        val surfacePlots = (registry.beds(runtime.settings.id) + runtime.state.preparationPatch).filter { position ->
            if (position.world != runtime.region.world.name ||
                !runtime.region.world.isChunkLoaded(position.x shr 4, position.z shr 4)
            ) return@filter false
            val soil = position.block() ?: return@filter false
            val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
            val currentIncidentPosition = position in persistedPlots && position in damagedPlots
            runtime.region.contains(soil.location) &&
                (currentIncidentPosition || FarmBlockPolicy.isSelectableBed(soil.type, crop.type, runtime.settings.crops)) &&
                FarmSurfacePolicy.isOutdoorBed(soil)
        }
        val surfacePoints = FarmSpecialIncidentPlanner.projectChannelGates(special.points, surfacePlots)
        if (surfacePoints.size != special.points.size) {
            scene.clearZone(runtime.settings.id, "channel_surface_unavailable")
            return
        }
        val projectedSpecial = if (surfacePoints != special.points && special.routeName != FARM_CHANNEL_ROUTE_NAME) {
            special.copy(points = surfacePoints).also { projected ->
                runtime.state = runtime.state.copy(specialIncident = projected)
                debug.event(
                    "farm_channels_projected_to_surface",
                    "zone" to runtime.settings.id,
                    "sequence" to runtime.state.sequence,
                    "gates" to projected.points.size,
                )
                state.persistAsync()
            }
        } else special
        val normalized = normalizeChannels(runtime, projectedSpecial)
        scene.clearZone(runtime.settings.id, "physical_drainage_v2")
        val additions = mutableListOf<FarmCropDamage>()
        normalized.points.forEachIndexed { index, point ->
            val soil = runtime.region.world.getBlockAt(
                floor(point.x).toInt(),
                floor(point.y).toInt() - 1,
                floor(point.z).toInt(),
            )
            val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
            val position = soil.toFarmPlotPosition()
            val recordedDamage = runtime.state.specialDamagedCrops.firstOrNull { it.position == position }
            if (recordedDamage == null && crop.type.name in runtime.settings.crops) {
                ledger.captureActiveCrop(soil, runtime.settings.id)
                additions += FarmCropDamage(position, crop.type.name)
            }
            if (recordedDamage == null && crop.type.name !in runtime.settings.crops) return@forEachIndexed
            if (!crop.type.isAir) crop.setType(Material.AIR, false)
            when {
                index in normalized.active -> if (soil.type != Material.WATER) soil.setType(Material.WATER, false)
                index in normalized.solution -> if (!soil.type.isAir) soil.setType(Material.AIR, false)
                soil.type !in FARM_SOIL_TYPES -> soil.setType(Material.FARMLAND, false)
            }
        }
        if (additions.isNotEmpty()) {
            runtime.state = runtime.state.copy(
                specialDamagedCrops = (runtime.state.specialDamagedCrops + additions).distinctBy(FarmCropDamage::position),
            )
            state.persistAsync()
        }
        val identity = channelToolIdentity(runtime)
        audience.players(runtime.region).filterNot(access::isAdminEditing).forEach { player ->
            val hasTool = player.inventory.storageContents.any { serviceItems.identity(it) == identity } ||
                serviceItems.identity(player.inventory.itemInOffHand) == identity
            if (!hasTool && serviceItems.issue(
                    player,
                    identity,
                    Material.IRON_SHOVEL,
                    locale.render(MessageKey.FARM_CHANNELS_SHOVEL, player),
                ) == null
            ) audience.sendActionBar(player, MessageKey.FARM_ACTION_INVENTORY_FULL)
        }
        scheduleChannelFlow(runtime)
    }

    private fun channelToolIdentity(runtime: FarmRuntime) = ServiceItemIdentity(
        ActivityKind.FARM,
        runtime.settings.id,
        runtime.state.sequence,
        runtime.state.placementSequence,
        ObjectiveTargetRole("farm_special"),
        CHANNEL_TOOL_ID,
    )

    private fun normalizeChannels(runtime: FarmRuntime, special: FarmSpecialIncidentState): FarmSpecialIncidentState {
        if (
            special.routeName == FARM_CHANNEL_ROUTE_NAME && runtime.state.incidentRequired == special.points.size &&
            runtime.state.incidentProgress == special.solution.size
        ) return special
        val normalized = special.copy(
            routeName = FARM_CHANNEL_ROUTE_NAME,
            solution = emptySet(),
            active = emptySet(),
        )
        runtime.state = runtime.state.copy(
            specialIncident = normalized,
            incidentProgress = 0,
            incidentRequired = special.points.size,
        )
        debug.event(
            "farm_channel_state_migrated",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "segments" to special.points.size,
        )
        state.persistAsync()
        return normalized
    }

    private fun scheduleChannelFlow(runtime: FarmRuntime) {
        if (runtime.state.phase != FarmPhase.INCIDENT || runtime.state.incidentType != FarmIncidentType.CHANNELS) return
        val special = runtime.state.specialIncident ?: return
        val key = ChannelTaskKey(runtime.settings.id, runtime.state.sequence)
        val next = FarmSpecialIncidentEngine.channelFlowProgress(special.active, special.points.size)
        if (next >= special.points.size) {
            scheduleChannelCompletion(runtime, key)
            return
        }
        if (next !in special.solution || !channelFlowTasks.add(key)) return
        val scheduled = tasks.runLater(runtime.settings.specialIncidents.channelFlowIntervalTicks.toLong()) {
            channelFlowTasks.remove(key)
            val current = runtimes().firstOrNull { candidate ->
                candidate.settings.id == key.zoneId && candidate.state.sequence == key.sequence &&
                    candidate.state.phase == FarmPhase.INCIDENT && candidate.state.incidentType == FarmIncidentType.CHANNELS
            } ?: return@runLater
            val result = FarmSpecialIncidentEngine.advanceChannelFlow(current.state)
            if (!result.accepted) return@runLater
            current.state = result.state
            val flowed = requireNotNull(current.state.specialIncident).active.maxOrNull() ?: return@runLater
            val point = requireNotNull(current.state.specialIncident).points[flowed]
            val soil = current.region.world.getBlockAt(
                floor(point.x).toInt(),
                floor(point.y).toInt() - 1,
                floor(point.z).toInt(),
            )
            soil.setType(Material.WATER, false)
            if (settings().particles) {
                soil.world.spawnParticle(Particle.SPLASH, soil.location.toCenterLocation().add(0.0, 0.35, 0.0), 14, 0.35, 0.08, 0.35, 0.08)
            }
            if (settings().sounds) {
                audience.players(current.region).forEach { player ->
                    player.playSound(soil.location, Sound.ITEM_BUCKET_EMPTY, 0.55f, 1.2f)
                }
            }
            state.persistAsync()
            scheduleChannelFlow(current)
        }
        if (!scheduled) channelFlowTasks.remove(key)
    }

    private fun scheduleChannelCompletion(runtime: FarmRuntime, key: ChannelTaskKey) {
        val special = runtime.state.specialIncident ?: return
        val expected = special.points.indices.toSet()
        if (special.solution != expected || special.active != expected || !channelCompletionTasks.add(key)) return
        val scheduled = tasks.runLater(runtime.settings.specialIncidents.channelCompletionDelayTicks.toLong()) {
            channelCompletionTasks.remove(key)
            val current = runtimes().firstOrNull { candidate ->
                candidate.settings.id == key.zoneId && candidate.state.sequence == key.sequence &&
                    candidate.state.phase == FarmPhase.INCIDENT && candidate.state.incidentType == FarmIncidentType.CHANNELS
            } ?: return@runLater
            val result = FarmSpecialIncidentEngine.completeChannels(current.state)
            if (result.accepted) transitions.apply(current, result, null)
        }
        if (!scheduled) channelCompletionTasks.remove(key)
    }

    fun handleGiantCropHit(runtime: FarmRuntime, player: Player, block: Block): Boolean {
        if (runtime.state.phase != FarmPhase.INCIDENT || runtime.state.incidentType != FarmIncidentType.GIANT_CROP) return false
        if (!giantCrop.owns(block, runtime.settings.id, runtime.state.sequence)) return false
        if (!access.hasAccess(player, runtime.settings.permission)) {
            audience.sendChat(player, MessageKey.ZONE_LOCKED)
            return true
        }
        if (!access.allowInteraction(
                "farm-giant:${runtime.settings.id}:${player.uniqueId}",
                runtime.settings.specialIncidents.giantCropHitCooldownMillis,
            )
        ) return true
        val result = FarmSpecialIncidentEngine.damageGiantCrop(runtime.state, player.uniqueId)
        if (!result.accepted || !giantCrop.breakBlock(block, runtime.settings.id, runtime.state.sequence)) return true
        FarmCropBreakEffects.emitGiantHit(
            block,
            MaterialRules.material(runtime.state.specialIncident?.crop ?: "PUMPKIN"),
            result.state.incidentProgress,
            settings().particles,
            settings().sounds,
            runtime.settings.cropEffects,
        )
        debug.event(
            "farm_giant_crop_hit",
            "zone" to runtime.settings.id,
            "player" to player.name,
            "progress" to result.state.incidentProgress,
            "required" to result.state.incidentRequired,
            "x" to block.x,
            "y" to block.y,
            "z" to block.z,
        )
        transitions.apply(runtime, result, player)
        return true
    }

    private fun handleMarketDecision(player: Player, expectedTop: org.bukkit.inventory.Inventory, click: FarmMarketClick) {
        val runtime = runtimes().firstOrNull { it.settings.id == click.zoneId } ?: run {
            tasks.deferInventoryTransition(player, expectedTop, player::closeInventory)
            return
        }
        if (!access.hasAccess(player, runtime.settings.permission) || !runtime.region.contains(player.location)) {
            tasks.deferInventoryTransition(player, expectedTop, player::closeInventory)
            return
        }
        if (
            runtime.state.sequence != click.sequence || runtime.state.phase != FarmPhase.INCIDENT ||
            runtime.state.incidentType != FarmIncidentType.MARKET
        ) {
            tasks.deferInventoryTransition(player, expectedTop, player::closeInventory)
            audience.sendActionBar(player, MessageKey.FARM_MARKET_CHANGED)
            debug.event(
                "farm_market_stale_decision",
                "zone" to click.zoneId,
                "sequence" to click.sequence,
                "current_sequence" to runtime.state.sequence,
                "player" to player.name,
                "decision" to click.decision,
            )
            return
        }
        val special = runtime.state.specialIncident ?: return
        if (special.marketAccepted) {
            tasks.deferInventoryTransition(player, expectedTop) { refreshMarketView(player, click.zoneId, click.sequence) }
            audience.sendActionBar(player, MessageKey.FARM_MARKET_ACTIVE, marketValues(runtime, special, player))
            return
        }
        val result = when (click.decision) {
            FarmMarketDecision.ACCEPT -> FarmSpecialIncidentEngine.acceptMarket(runtime.state, clock(), marketDurationMillis(runtime))
            FarmMarketDecision.DECLINE -> FarmSpecialIncidentEngine.declineMarket(runtime.state)
        }
        if (!result.accepted) {
            tasks.deferInventoryTransition(player, expectedTop) { refreshMarketView(player, click.zoneId, click.sequence) }
            return
        }
        tasks.deferInventoryTransition(player, expectedTop, player::closeInventory)
        transitions.apply(runtime, result, player)
        if (click.decision == FarmMarketDecision.ACCEPT) {
            state.persistAsync()
            val accepted = requireNotNull(runtime.state.specialIncident)
            audience.broadcast(
                listOf(runtime.region),
                MessageKey.FARM_MARKET_ACCEPTED,
                sound = Sound.ENTITY_VILLAGER_YES,
                title = true,
                valuesForPlayer = { audience -> marketValues(runtime, accepted, audience) },
            )
        } else audience.broadcast(listOf(runtime.region), MessageKey.FARM_MARKET_DECLINED, sound = Sound.ENTITY_VILLAGER_NO)
        debug.event(
            "farm_market_decision",
            "zone" to click.zoneId,
            "sequence" to click.sequence,
            "player" to player.name,
            "decision" to click.decision,
        )
    }

    private fun marketDurationMillis(runtime: FarmRuntime): Long = runtime.settings.specialIncidents.let { timer ->
        FarmMarketTimer.durationMillis(
            runtime.state.incidentRequired,
            timer.marketBaseSeconds,
            timer.marketSecondsPerCrop,
            timer.marketMinimumSeconds,
            timer.marketMaximumSeconds,
        )
    }

    private fun areaCenter(plots: Collection<FarmPlotPosition>): FarmPlotPosition? {
        if (plots.isEmpty()) return null
        val world = plots.first().world
        val sameWorld = plots.filter { it.world == world }
        return FarmPlotPosition(
            world,
            sameWorld.sumOf(FarmPlotPosition::x) / sameWorld.size,
            sameWorld.sumOf(FarmPlotPosition::y) / sameWorld.size,
            sameWorld.sumOf(FarmPlotPosition::z) / sameWorld.size,
        )
    }

    private fun FarmIncidentType.configId(): String = when (this) {
        FarmIncidentType.GIANT_CROP -> "giant-crop"
        FarmIncidentType.CHANNELS -> "channels"
        FarmIncidentType.NIGHT_SHIFT -> "night-shift"
        FarmIncidentType.MARKET -> "market"
        FarmIncidentType.PESTS -> "pests"
        FarmIncidentType.DROUGHT -> "drought"
        FarmIncidentType.BIRDS -> "birds"
        FarmIncidentType.FOOD_DELIVERY -> "food-delivery"
        FarmIncidentType.PROCESSING -> "processing"
        FarmIncidentType.BARN_FIRE -> "barn-fire"
        FarmIncidentType.FROST -> "frost"
        FarmIncidentType.BOAR_BREAKOUT -> "boar-breakout"
        FarmIncidentType.RIVAL_RAID -> "rival-raid"
    }

    private companion object {
        const val CHANNEL_TOOL_ID = "drainage_shovel"
    }
}
