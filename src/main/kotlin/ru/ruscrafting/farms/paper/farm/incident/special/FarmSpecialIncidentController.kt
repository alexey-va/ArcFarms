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
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmCropDamage
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
import ru.ruscrafting.farms.paper.FarmSpecialSceneObject
import ru.ruscrafting.farms.paper.FarmSpecialSceneRole
import ru.ruscrafting.farms.paper.FarmSpecialSceneSpec
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.block
import ru.ruscrafting.farms.paper.farm.FarmIncidentBedProvider
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.placement.FarmSurfacePolicy
import ru.ruscrafting.farms.paper.toFarmPlotPosition
import java.util.Locale
import java.util.logging.Level
import kotlin.math.floor

internal val SPECIAL_FARM_INCIDENT_TYPES = setOf(
    FarmIncidentType.GIANT_CROP,
    FarmIncidentType.CHANNELS,
    FarmIncidentType.NIGHT_SHIFT,
    FarmIncidentType.MARKET,
)

private const val MAX_GIANT_CROP_PLACEMENT_CHECKS = 128

/** Complete owner for giant crop, channels, night shift and urgent market incidents. */
internal class FarmSpecialIncidentController(
    plugin: Plugin,
    private val settings: () -> ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
    private val port: WorksiteRuntimePort,
    private val ledger: FarmBlockLedger,
    private val registry: FarmBlockRegistry,
    private val beds: FarmIncidentBedProvider,
    private val points: FarmPointProvider,
    private val transitions: FarmTransitionSink,
    private val runtimes: () -> Collection<FarmRuntime>,
    private val clock: () -> Long,
) {
    private val scene = FarmSpecialIncidentSceneManager(plugin, debug)
    private val giantCrop = FarmGiantCropController(plugin)
    private val nightShift = FarmNightShiftController(plugin)
    private val marketMenu = FarmMarketMenu(locale, settings)
    private val giantSelectionAttempts = mutableMapOf<String, Long>()

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
        val configuredTypes = order?.incidentTypes.orEmpty().filter(SPECIAL_FARM_INCIDENT_TYPES::contains)
        val scheduledTypes = order?.let { configured ->
            FarmIncidentPlanner.sequence(
                configured.incidentTypes,
                runtime.rules.incidentTargetCount(runtime.state.sequence),
                runtime.state.sequence,
            )
        }.orEmpty()
        val candidateTypes = (listOf(type) + configuredTypes.filterNot(scheduledTypes::contains) + configuredTypes).distinct()
        val selected = candidateTypes.firstNotNullOfOrNull { candidateType ->
            val giantCandidates = if (candidateType == FarmIncidentType.GIANT_CROP) {
                val selection = giantSelection ?: selectGiantCandidate(runtime, mature).also { giantSelection = it }
                listOfNotNull(selection.candidate)
            } else emptyList()
            FarmSpecialIncidentPlanner.plan(
                type = candidateType,
                sequence = runtime.state.sequence,
                matureCrops = mature,
                giantCandidates = giantCandidates,
                nightPatrolPlots = incidentBeds,
                fallbackPlot = areaCenter(runtime.state.preparationPatch),
                irrigationSource = points.resolve(runtime, FarmPointKind.IRRIGATION),
                channelBlockages = specialSettings.channelBlockageCount,
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
            port.log(Level.WARNING, "Skipped unavailable farm incident $type in ${runtime.settings.id}")
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
            port.persistAsync()
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
        port.persistAsync()
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
        port.broadcast(
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
        if (handleGiantCropBreak(runtime, player, block)) return true
        val type = runtime.state.incidentType ?: return false
        if (runtime.state.phase != FarmPhase.INCIDENT || type !in setOf(FarmIncidentType.NIGHT_SHIFT, FarmIncidentType.MARKET)) {
            return false
        }
        if (type == FarmIncidentType.MARKET && expireMarket(runtime, clock())) return true
        val special = runtime.state.specialIncident ?: return true
        if (!port.hasAccess(player, runtime.settings.permission)) {
            port.sendChat(player, MessageKey.ZONE_LOCKED)
            return true
        }
        if (type == FarmIncidentType.MARKET && !special.marketAccepted) {
            port.sendActionBar(player, MessageKey.FARM_MARKET_REQUIRED)
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
            port.sendActionBar(
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
        val identity = scene.metadata(entity) ?: return
        val runtime = runtimes().firstOrNull { it.settings.id == identity.zoneId } ?: return
        if (!port.hasAccess(player, runtime.settings.permission) || !runtime.region.contains(player.location)) return
        if (runtime.state.sequence != identity.sequence || runtime.state.phase != FarmPhase.INCIDENT) return
        if (identity.role !in setOf(
                FarmSpecialSceneRole.CHANNEL_BLOCKAGE,
                FarmSpecialSceneRole.CHANNEL_BLOCKAGE_HITBOX,
            )
        ) return
        if (runtime.state.incidentType != FarmIncidentType.CHANNELS) return
        if (!port.allowInteraction("farm-channel:${identity.zoneId}:${identity.index}:${player.uniqueId}", 250L)) return
        val beforeFlow = runtime.state.specialIncident?.let { incident ->
            FarmSpecialIncidentEngine.channelFlowProgress(incident.active, incident.points.size)
        } ?: 0
        val result = FarmSpecialIncidentEngine.clearChannelBlockage(runtime.state, identity.index, player.uniqueId)
        if (!result.accepted) return
        if (settings().particles) entity.world.spawnParticle(
            Particle.BLOCK,
            entity.location.clone().add(0.0, 0.45, 0.0),
            12,
            0.35,
            0.25,
            0.35,
            runtime.settings.specialIncidents.channelBlockageMaterial.let(MaterialRules::material).createBlockData(),
        )
        if (settings().sounds) {
            player.playSound(entity.location, Sound.BLOCK_ROOTED_DIRT_BREAK, 0.9f, 0.9f)
            val incident = result.state.specialIncident
            if (incident != null && FarmSpecialIncidentEngine.channelFlowProgress(incident.active, incident.points.size) > beforeFlow) {
                player.playSound(entity.location, Sound.ITEM_BUCKET_EMPTY, 0.65f, 1.3f)
            }
        }
        transitions.apply(runtime, result, player)
        ensure(runtime)
    }

    fun handleInventoryClick(event: InventoryClickEvent): Boolean {
        val player = event.whoClicked as? Player ?: return false
        val click = marketMenu.handleClick(event) ?: return false
        handleMarketDecision(player, click)
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
            port.persistAsync()
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

    fun ownsNightEntity(entity: Entity): Boolean = nightShift.owns(entity)

    fun handleNightDamage(event: EntityDamageEvent): Boolean {
        if (!nightShift.owns(event.entity)) return false
        event.isCancelled = true
        val attacker = (event as? EntityDamageByEntityEvent)?.let { damage ->
            when (val damager = damage.damager) {
                is Player -> damager
                is Projectile -> damager.shooter as? Player
                else -> null
            }
        }
        if (attacker != null && port.allowInteraction("farm-night-patrol:${attacker.uniqueId}", 1_500L)) {
            port.sendActionBar(attacker, MessageKey.FARM_NIGHT_PATROL_AVOID)
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
        scene.clearZone(runtime.settings.id, reason)
        nightShift.clearZone(runtime.settings.id)
    }

    fun cleanup(reason: String) {
        giantSelectionAttempts.clear()
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
            port.players(runtime.region),
            runtime.settings.specialIncidents.nightPlayerTime,
            special.points,
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
        return runtime.state.sequence * 1_000_003L + runtime.state.incidentsResolved * 101L + attempt - 1L
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
        val surfacePlots = (registry.beds(runtime.settings.id) + runtime.state.preparationPatch).filter { position ->
            if (position.world != runtime.region.world.name ||
                !runtime.region.world.isChunkLoaded(position.x shr 4, position.z shr 4)
            ) return@filter false
            val soil = position.block() ?: return@filter false
            runtime.region.contains(soil.location) && FarmBlockPolicy.isSelectableBed(
                soil.type,
                soil.getRelative(org.bukkit.block.BlockFace.UP).type,
                runtime.settings.crops,
            ) && FarmSurfacePolicy.isOutdoorBed(soil)
        }
        val surfacePoints = FarmSpecialIncidentPlanner.projectChannelGates(special.points, surfacePlots)
        if (surfacePoints.size != special.points.size) {
            scene.clearZone(runtime.settings.id, "channel_surface_unavailable")
            return
        }
        val projectedSpecial = if (surfacePoints != special.points) {
            special.copy(points = surfacePoints).also { projected ->
                runtime.state = runtime.state.copy(specialIncident = projected)
                debug.event(
                    "farm_channels_projected_to_surface",
                    "zone" to runtime.settings.id,
                    "sequence" to runtime.state.sequence,
                    "gates" to projected.points.size,
                )
                port.persistAsync()
            }
        } else special
        val normalized = normalizeChannels(runtime, projectedSpecial)
        val channel = runtime.settings.specialIncidents
        val item = ItemStack(MaterialRules.material(channel.channelBlockageMaterial)).apply {
            if (channel.channelBlockageCustomModelData > 0) editMeta {
                it.setCustomModelData(channel.channelBlockageCustomModelData)
            }
        }
        val nextBlockage = normalized.points.indices.firstOrNull { it !in normalized.active }
        val objects = normalized.points.flatMapIndexed { index, point ->
            if (index in normalized.active) return@flatMapIndexed emptyList()
            val location = Location(runtime.region.world, point.x, point.y, point.z)
            listOf(
                FarmSpecialSceneObject(
                    FarmSpecialSceneRole.CHANNEL_BLOCKAGE,
                    index,
                    location.clone().add(0.0, channel.channelBlockageDisplayYOffset, 0.0),
                    item,
                    channel.channelBlockageDisplayScale,
                    index == nextBlockage,
                ),
                FarmSpecialSceneObject(
                    FarmSpecialSceneRole.CHANNEL_BLOCKAGE_HITBOX,
                    index,
                    location.clone().add(0.0, channel.channelBlockageDisplayYOffset - 0.25, 0.0),
                ),
            )
        }
        scene.ensure(FarmSpecialSceneSpec(runtime.settings.id, runtime.state.sequence, runtime.settings.displayViewRange, objects))
    }

    private fun normalizeChannels(runtime: FarmRuntime, special: FarmSpecialIncidentState): FarmSpecialIncidentState {
        val expected = special.points.indices.toSet()
        if (
            special.solution == expected && runtime.state.incidentRequired == special.points.size &&
            runtime.state.incidentProgress == special.active.size
        ) return special
        val normalized = special.copy(solution = expected, active = emptySet())
        runtime.state = runtime.state.copy(
            specialIncident = normalized,
            incidentProgress = 0,
            incidentRequired = special.points.size,
        )
        debug.event(
            "farm_channel_state_migrated",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "blockages" to special.points.size,
        )
        port.persistAsync()
        return normalized
    }

    private fun handleGiantCropBreak(runtime: FarmRuntime, player: Player, block: Block): Boolean {
        if (runtime.state.phase != FarmPhase.INCIDENT || runtime.state.incidentType != FarmIncidentType.GIANT_CROP) return false
        if (!giantCrop.owns(block, runtime.settings.id, runtime.state.sequence)) return false
        if (!port.hasAccess(player, runtime.settings.permission)) {
            port.sendChat(player, MessageKey.ZONE_LOCKED)
            return true
        }
        if (!port.allowInteraction("farm-giant:${runtime.settings.id}:${player.uniqueId}", 90L)) return true
        val result = FarmSpecialIncidentEngine.damageGiantCrop(runtime.state, player.uniqueId)
        if (!result.accepted || !giantCrop.breakBlock(block, runtime.settings.id, runtime.state.sequence)) return true
        if (settings().particles) block.world.spawnParticle(
            Particle.BLOCK,
            block.location.toCenterLocation(),
            10,
            0.35,
            0.35,
            0.35,
            MaterialRules.material(runtime.state.specialIncident?.crop ?: "PUMPKIN").createBlockData(),
        )
        if (settings().sounds) player.playSound(
            block.location,
            Sound.BLOCK_WOOD_BREAK,
            0.8f,
            0.85f + result.state.incidentProgress * 0.006f,
        )
        transitions.apply(runtime, result, player)
        return true
    }

    private fun handleMarketDecision(player: Player, click: FarmMarketClick) {
        val runtime = runtimes().firstOrNull { it.settings.id == click.zoneId } ?: return
        if (!port.hasAccess(player, runtime.settings.permission) || !runtime.region.contains(player.location)) {
            player.closeInventory()
            return
        }
        if (click.decision == FarmMarketDecision.CLOSE) {
            player.closeInventory()
            debug.event("farm_market_menu_closed", "zone" to click.zoneId, "sequence" to click.sequence, "player" to player.name)
            return
        }
        if (
            runtime.state.sequence != click.sequence || runtime.state.phase != FarmPhase.INCIDENT ||
            runtime.state.incidentType != FarmIncidentType.MARKET
        ) {
            player.closeInventory()
            port.sendActionBar(player, MessageKey.FARM_MARKET_CHANGED)
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
            openMarket(player, runtime, special)
            port.sendActionBar(player, MessageKey.FARM_MARKET_ACTIVE, marketValues(runtime, special, player))
            return
        }
        val result = when (click.decision) {
            FarmMarketDecision.ACCEPT -> FarmSpecialIncidentEngine.acceptMarket(runtime.state, clock(), marketDurationMillis(runtime))
            FarmMarketDecision.DECLINE -> FarmSpecialIncidentEngine.declineMarket(runtime.state)
            FarmMarketDecision.CLOSE -> return
        }
        if (!result.accepted) {
            openMarket(player, runtime, requireNotNull(runtime.state.specialIncident))
            return
        }
        player.closeInventory()
        transitions.apply(runtime, result, player)
        if (click.decision == FarmMarketDecision.ACCEPT) {
            port.persistAsync()
            val accepted = requireNotNull(runtime.state.specialIncident)
            port.broadcast(
                listOf(runtime.region),
                MessageKey.FARM_MARKET_ACCEPTED,
                sound = Sound.ENTITY_VILLAGER_YES,
                title = true,
                valuesForPlayer = { audience -> marketValues(runtime, accepted, audience) },
            )
        } else port.broadcast(listOf(runtime.region), MessageKey.FARM_MARKET_DECLINED, sound = Sound.ENTITY_VILLAGER_NO)
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
    }
}
