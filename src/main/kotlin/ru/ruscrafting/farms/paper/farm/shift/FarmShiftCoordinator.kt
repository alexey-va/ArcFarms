package ru.ruscrafting.farms.paper.farm.shift

import ru.ruscrafting.farms.paper.farm.incident.greenhouse.FarmHellGreenhouseIncident

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmOrder
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmSeederStage
import ru.ruscrafting.farms.domain.FarmProcessingStage
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.domain.FarmSpecialIncidentEngine
import ru.ruscrafting.farms.domain.FarmShiftEvent
import ru.ruscrafting.farms.domain.seederStage
import ru.ruscrafting.farms.network.NetworkSignal
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteNetworkPort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatsPort
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.care.FarmCareController
import ru.ruscrafting.farms.paper.farm.care.FarmCarePlanService
import ru.ruscrafting.farms.paper.farm.delivery.FarmDeliveryController
import ru.ruscrafting.farms.paper.farm.enterprise.FarmEnterprisePort
import ru.ruscrafting.farms.paper.farm.incident.drought.FarmDroughtIncident
import ru.ruscrafting.farms.paper.farm.incident.bird.FarmBirdIncident
import ru.ruscrafting.farms.paper.farm.incident.pest.FarmPestIncident
import ru.ruscrafting.farms.paper.farm.incident.special.FarmSpecialIncidentController
import ru.ruscrafting.farms.paper.farm.incident.route.FarmFoodDeliveryIncident
import ru.ruscrafting.farms.paper.farm.incident.processing.FarmProcessingIncident
import ru.ruscrafting.farms.paper.farm.incident.fire.FarmBarnFireIncident
import ru.ruscrafting.farms.paper.farm.incident.tornado.FarmTornadoIncident
import ru.ruscrafting.farms.paper.farm.incident.frost.FarmFrostIncident
import ru.ruscrafting.farms.paper.farm.incident.special.SPECIAL_FARM_INCIDENT_TYPES
import ru.ruscrafting.farms.paper.farm.incident.action.ACTION_FARM_INCIDENT_TYPES
import ru.ruscrafting.farms.paper.farm.incident.action.FarmActionIncidentController
import ru.ruscrafting.farms.paper.farm.presentation.FarmHudController
import ru.ruscrafting.farms.paper.farm.reward.FarmRewardService
import ru.ruscrafting.farms.paper.farm.scene.FarmContractSceneController
import ru.ruscrafting.farms.paper.farm.supply.FarmSupplyController
import ru.ruscrafting.farms.api.WorkShiftCompletedEvent

/** The only application owner allowed to apply a farm domain EngineResult. */
internal class FarmShiftCoordinator(
    private val settings: () -> ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
    private val port: WorksiteAudiencePort,
    private val state: WorksiteStatePort,
    private val tasks: ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort,
    private val stats: WorksiteStatsPort,
    private val network: WorksiteNetworkPort,
    private val carePlans: FarmCarePlanService,
    private val care: FarmCareController,
    private val drought: FarmDroughtIncident,
    private val pests: FarmPestIncident,
    private val birds: FarmBirdIncident,
    private val foodDelivery: FarmFoodDeliveryIncident,
    private val actionIncidents: FarmActionIncidentController,
    private val special: FarmSpecialIncidentController,
    private val processing: FarmProcessingIncident,
    private val barnFire: FarmBarnFireIncident,
    private val frost: FarmFrostIncident,
    private val tornado: FarmTornadoIncident,
    private val greenhouse: FarmHellGreenhouseIncident,
    private val delivery: FarmDeliveryController,
    private val scene: FarmContractSceneController,
    private val supplies: FarmSupplyController,
    private val rewards: FarmRewardService,
    private val enterprise: FarmEnterprisePort,
    private val hud: FarmHudController,
    private val points: FarmPointProvider,
) {
    fun apply(runtime: FarmRuntime, result: EngineResult<FarmShiftState, FarmShiftEvent>, actor: Player?) {
        val incidentType = result.state.incidentType ?: runtime.state.incidentType ?: FarmIncidentType.PESTS
        val careType = result.state.careType ?: runtime.state.careType
        runtime.state = result.state
        val order = currentOrder(runtime)
        state.traceResult(
            ActivityKind.FARM,
            runtime.settings.id,
            actor,
            runtime.state.phase,
            order?.let { "${runtime.state.completed(it)}/${it.totalRequired}" },
            result,
        )
        if (result.contributionCredits.isNotEmpty()) {
            result.contributionCredits.forEach { (playerId, contribution) ->
                if (contribution > 0) stats.recordContribution(playerId, ActivityKind.FARM, contribution)
            }
        } else if (actor != null && result.contribution > 0) {
            stats.recordContribution(actor.uniqueId, ActivityKind.FARM, result.contribution)
        }
        result.events.forEach { event ->
            when (event) {
                FarmShiftEvent.STARTED -> started(runtime)
                FarmShiftEvent.PREPARATION_PROGRESS -> preparationProgress(runtime, actor)
                FarmShiftEvent.PLANTING_STARTED -> plantingStarted(runtime, actor)
                FarmShiftEvent.PLANTING_PROGRESS -> plantingProgress(runtime, actor)
                FarmShiftEvent.PREPARATION_COMPLETED -> preparationCompleted(runtime, actor)
                FarmShiftEvent.CARE_STARTED -> careStarted(runtime, requireNotNull(careType))
                FarmShiftEvent.CARE_PROGRESS -> careProgress(runtime, careType, actor)
                FarmShiftEvent.SEEDER_PROGRESS -> seederProgress(runtime, actor)
                FarmShiftEvent.SEEDER_PLANTING_STARTED -> seederPlantingStarted(runtime)
                FarmShiftEvent.CARE_RESOLVED -> careResolved(runtime, careType, actor)
                FarmShiftEvent.HARVEST_CHECKPOINT -> harvestCheckpoint(runtime, actor)
                FarmShiftEvent.HARVEST_MILESTONE -> harvestMilestone(runtime)
                FarmShiftEvent.TORNADO_STARTED -> tornadoStarted(runtime)
                FarmShiftEvent.TORNADO_RESOLVED -> {
                    tornado.clear(runtime)
                    port.broadcast(listOf(runtime.region), MessageKey.FARM_TORNADO_RESOLVED)
                    state.persistAsync()
                }
                FarmShiftEvent.INCIDENT_STARTED -> incidentStarted(runtime, incidentType, actor)
                FarmShiftEvent.INCIDENT_PROGRESS -> incidentProgress(runtime, incidentType, actor)
                FarmShiftEvent.PROCESSING_STAGE_CHANGED -> processingStageChanged(runtime)
                FarmShiftEvent.INCIDENT_RESOLVED -> incidentResolved(runtime, incidentType, actor)
                FarmShiftEvent.MARKET_EXPIRED -> marketExpired(runtime)
                FarmShiftEvent.DELIVERY_STARTED -> deliveryStarted(runtime)
                FarmShiftEvent.DELIVERY_PROGRESS -> deliveryProgress(runtime, actor)
                FarmShiftEvent.COMPLETED -> completed(
                    runtime,
                    actor,
                    result.contribution > 0 || result.contributionCredits.values.any { it > 0 },
                )
                else -> Unit
            }
        }
        scene.ensure(runtime)
    }

    private fun started(runtime: FarmRuntime) {
        runtime.state.orderId?.let { orderId ->
            if (
                enterprise.orderStarted(
                    runtime.settings.id,
                    orderId,
                    runtime.state.sequence,
                    runtime.state.startedAt,
                )
            ) state.persistAsync()
        }
        port.broadcast(
            listOf(runtime.region),
            MessageKey.FARM_STARTED,
            sound = Sound.BLOCK_BELL_USE,
            valuesForPlayer = { player ->
                mapOf(
                    "order" to locale.renderPath("order.farm.${runtime.state.orderId}", player),
                    "total" to locale.text(runtime.state.preparationRequired),
                )
            },
        )
        hud.storyTitle(runtime, "start") { player ->
            locale.renderPath("order.farm.${runtime.state.orderId}", player) to
                mapOf("total" to locale.text(runtime.state.preparationRequired))
        }
        issueRequiredSupply(runtime)
    }

    private fun preparationProgress(runtime: FarmRuntime, actor: Player?) {
        actor ?: return
        port.sendActionBar(
            actor,
            MessageKey.FARM_PREPARATION_PROGRESS,
            mapOf(
                "done" to locale.text(runtime.state.preparationProgress),
                "total" to locale.text(runtime.state.preparationRequired),
            ),
        )
        if (
            runtime.state.preparationProgress < runtime.state.preparationRequired &&
            runtime.state.preparationProgress % PERSIST_INTERVAL == 0
        ) state.persistAsync()
    }

    private fun plantingStarted(runtime: FarmRuntime, actor: Player?) {
        if (carePlans.shouldUseSeeder(runtime) && care.initialize(runtime, actor, FarmCareType.SEEDER)) {
            state.persistAsync()
            return
        }
        val crop = MaterialRules.material(requireNotNull(runtime.state.preparationCrop))
        port.broadcast(
            listOf(runtime.region),
            MessageKey.FARM_PLANTING_STARTED,
            mapOf("crop" to MaterialRules.cropComponent(crop), "total" to locale.text(runtime.state.preparationRequired)),
            Sound.ENTITY_PLAYER_LEVELUP,
            title = true,
        )
        hud.playStageFanfare(runtime, 0.95f)
        issueRequiredSupply(runtime)
        state.persistAsync()
    }

    private fun plantingProgress(runtime: FarmRuntime, actor: Player?) {
        actor ?: return
        port.sendActionBar(
            actor,
            MessageKey.FARM_PLANTING_PROGRESS,
            mapOf(
                "crop" to MaterialRules.cropComponent(MaterialRules.material(requireNotNull(runtime.state.preparationCrop))),
                "done" to locale.text(runtime.state.plantingProgress),
                "total" to locale.text(runtime.state.preparationRequired),
            ),
        )
        if (
            runtime.state.plantingProgress < runtime.state.preparationRequired &&
            runtime.state.plantingProgress % PERSIST_INTERVAL == 0
        ) state.persistAsync()
    }

    private fun preparationCompleted(runtime: FarmRuntime, actor: Player?) {
        port.successBurst(runtime.region)
        hud.playStageFanfare(runtime, 1.05f)
        if (!care.initialize(runtime, actor)) {
            port.broadcast(
                listOf(runtime.region),
                MessageKey.FARM_PREPARATION_COMPLETED,
                sound = Sound.ENTITY_VILLAGER_YES,
                title = true,
            )
        }
        state.persistAsync()
    }

    private fun careStarted(runtime: FarmRuntime, type: FarmCareType) {
        players(runtime).forEach { player ->
            val title = locale.renderPath("care.${type.name.lowercase()}.name", player)
            val instructionPath = if (type == FarmCareType.SEEDER) {
                seederInstructionPath(runtime.state)
            } else "care.${type.name.lowercase()}.instruction"
            val subtitle = locale.renderPath(
                instructionPath,
                player,
                mapOf("total" to locale.text(runtime.state.careRequired())),
            )
            port.showScreenTitle(player, title, subtitle)
            debug.message("title", "local", "care.${type.name.lowercase()}.name", player, title)
            debug.message("subtitle", "local", instructionPath, player, subtitle)
            if (settings().sounds) player.playSound(player.location, care.startSound(type), 0.75f, 1.0f)
        }
        port.warningBurst(runtime.region)
        issueRequiredSupply(runtime)
        debug.event(
            "farm_care_started",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "type" to type,
            "targets" to runtime.state.careTargets.size,
            "required" to runtime.state.careRequired(),
        )
        state.persistAsync()
    }

    private fun careProgress(runtime: FarmRuntime, type: FarmCareType?, actor: Player?) {
        if (actor != null && type != FarmCareType.SEEDER) {
            port.sendActionBar(
                actor,
                MessageKey.FARM_CARE_PROGRESS,
                mapOf("done" to locale.text(runtime.state.careProgress()), "total" to locale.text(runtime.state.careRequired())),
            )
        }
        care.ensure(runtime)
        state.persistAsync()
    }

    private fun seederProgress(runtime: FarmRuntime, actor: Player?) {
        actor?.let { player ->
            val stage = requireNotNull(runtime.state.seederStage())
            port.sendActionBar(
                player,
                MessageKey.FARM_CARE_SEEDER_PROGRESS,
                mapOf(
                    "stage" to locale.renderPath("care.seeder.stage.${stage.name.lowercase()}", player),
                    "done" to locale.text(
                        if (stage == FarmSeederStage.TILLING) runtime.state.preparationProgress else runtime.state.plantingProgress,
                    ),
                    "total" to locale.text(runtime.state.preparationRequired),
                ),
            )
        }
        state.persistAsync()
    }

    private fun seederPlantingStarted(runtime: FarmRuntime) {
        runtime.state.careTargets.filter { it.role == FarmCareRole.SEEDER_WAYPOINT }.forEach { target ->
            care.removeTarget(runtime.settings.id, target.id, "seeder_planting_route")
        }
        care.ensure(runtime)
        players(runtime).forEach { player ->
            val title = locale.render(MessageKey.FARM_CARE_SEEDER_PLANTING_STARTED, player)
            val subtitle = locale.render(MessageKey.FARM_CARE_SEEDER_PLANTING_STARTED_SUBTITLE, player)
            port.showScreenTitle(player, title, subtitle)
            debug.message("title", "local", MessageKey.FARM_CARE_SEEDER_PLANTING_STARTED.path, player, title)
            debug.message("subtitle", "local", MessageKey.FARM_CARE_SEEDER_PLANTING_STARTED_SUBTITLE.path, player, subtitle)
            if (settings().sounds) player.playSound(player.location, Sound.BLOCK_GRINDSTONE_USE, 0.85f, 1.25f)
        }
        port.successBurst(runtime.region)
        hud.playStageFanfare(runtime, 1.05f)
        debug.event(
            "farm_seeder_planting_started",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "plots" to runtime.state.preparationRequired,
        )
        state.persistAsync()
    }

    private fun careResolved(runtime: FarmRuntime, type: FarmCareType?, actor: Player?) {
        care.clear(runtime, "care_resolved")
        runtime.state = runtime.state.copy(careType = null, seederStage = null, careTargets = emptyList(), careGoal = null)
        port.broadcast(
            listOf(runtime.region),
            if (type == FarmCareType.SEEDER) MessageKey.FARM_CARE_SEEDER_RESOLVED else MessageKey.FARM_CARE_RESOLVED,
            sound = if (type == FarmCareType.SEEDER) Sound.ENTITY_HORSE_ARMOR else Sound.ENTITY_VILLAGER_YES,
            title = type != FarmCareType.SEEDER,
        )
        port.successBurst(runtime.region)
        hud.playStageFanfare(runtime, 1.1f)
        debug.event("farm_care_resolved", "zone" to runtime.settings.id, "sequence" to runtime.state.sequence, "type" to type)
        if (type == FarmCareType.SEEDER) care.initialize(runtime, actor)
        state.persistAsync()
    }

    private fun harvestCheckpoint(runtime: FarmRuntime, actor: Player?) {
        val checkpoint = runtime.state.harvestCheckpoint
        actor?.let { player ->
            port.sendActionBar(player, MessageKey.FARM_HARVEST_MILESTONE, mapOf("percent" to locale.text(checkpoint * 10)))
            if (settings().particles) {
                player.spawnParticle(Particle.COMPOSTER, player.location.clone().add(0.0, 1.0, 0.0), 5, 0.35, 0.3, 0.35, 0.02)
            }
        }
        hud.playMilestone(runtime, Sound.BLOCK_NOTE_BLOCK_HAT, 0.85f + checkpoint * 0.035f)
        debug.event(
            "farm_harvest_checkpoint",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "checkpoint" to checkpoint,
            "percent" to checkpoint * 10,
        )
        state.persistAsync()
    }

    private fun harvestMilestone(runtime: FarmRuntime) {
        val milestone = runtime.state.harvestMilestone
        debug.event(
            "farm_harvest_milestone",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "milestone" to milestone,
            "percent" to milestone * 25,
        )
        state.persistAsync()
    }

    private fun incidentStarted(runtime: FarmRuntime, type: FarmIncidentType, actor: Player?) {
        clearSupplies(runtime, "incident_started")
        when (type) {
            FarmIncidentType.DROUGHT -> {
                drought.ensure(runtime)
                hud.storyTitle(runtime, "drought", Sound.WEATHER_RAIN_ABOVE) { player ->
                    locale.render(MessageKey.FARM_DROUGHT_STARTED, player) to
                        mapOf("total" to locale.text(runtime.state.incidentRequired))
                }
            }
            FarmIncidentType.PESTS -> {
                pests.ensure(runtime)
                hud.storyTitle(runtime, "pests", Sound.ENTITY_BEE_LOOP_AGGRESSIVE) { player ->
                    locale.render(MessageKey.FARM_INCIDENT_STARTED, player) to mapOf(
                        "crop" to MaterialRules.cropComponent(MaterialRules.material(requireNotNull(runtime.state.incidentCrop))),
                        "nests" to locale.text(runtime.state.pestNests.size),
                        "pests" to locale.text(runtime.state.pestAlive),
                    )
                }
            }
            FarmIncidentType.BIRDS -> {
                if (!birds.initialize(runtime)) {
                    apply(runtime, FarmShiftEngine.skipUnavailableIncident(runtime.state, FarmIncidentType.BIRDS), null)
                    return
                }
                birds.ensure(runtime)
                port.broadcast(
                    listOf(runtime.region),
                    MessageKey.FARM_BIRDS_STARTED,
                    mapOf("total" to locale.text(runtime.state.incidentRequired)),
                    Sound.ENTITY_PARROT_IMITATE_PHANTOM,
                    title = true,
                )
            }
            FarmIncidentType.FOOD_DELIVERY -> {
                if (!foodDelivery.initialize(runtime)) {
                    apply(runtime, foodDelivery.skipUnavailable(runtime, System.currentTimeMillis()), null)
                    return
                }
                foodDelivery.ensure(runtime, System.currentTimeMillis())
                port.broadcast(
                    listOf(runtime.region),
                    MessageKey.FARM_ROUTE_STARTED,
                    sound = Sound.ENTITY_HORSE_AMBIENT,
                    title = true,
                )
            }
            FarmIncidentType.PROCESSING -> {
                if (!processing.initialize(runtime)) {
                    actor?.takeIf {
                        it.hasPermission("arcfarms.admin") && !processing.hasConfiguredPoint(runtime.settings.id)
                    }?.let { player ->
                        port.sendChat(
                            player,
                            MessageKey.ADMIN_PROCESSING_POINT_REQUIRED,
                            mapOf("zone" to locale.text(runtime.settings.id)),
                        )
                    }
                    apply(runtime, FarmShiftEngine.skipUnavailableIncident(runtime.state, FarmIncidentType.PROCESSING), null)
                    return
                }
                processing.ensure(runtime)
                port.broadcast(
                    listOf(runtime.region),
                    MessageKey.FARM_PROCESSING_STARTED,
                    sound = Sound.BLOCK_GRINDSTONE_USE,
                    title = true,
                )
            }
            FarmIncidentType.BARN_FIRE -> {
                if (!barnFire.initialize(runtime)) {
                    apply(runtime, FarmShiftEngine.skipUnavailableIncident(runtime.state, FarmIncidentType.BARN_FIRE), null)
                    return
                }
                barnFire.ensure(runtime)
                port.broadcast(
                    listOf(runtime.region),
                    MessageKey.FARM_BARN_FIRE_STARTED,
                    values = mapOf("total" to locale.text(runtime.state.incidentRequired)),
                    sound = Sound.ITEM_FIRECHARGE_USE,
                    title = true,
                )
            }
            FarmIncidentType.HELL_GREENHOUSE -> {
                if (!greenhouse.initialize(runtime, actor)) {
                    apply(runtime, FarmShiftEngine.skipUnavailableIncident(runtime.state, type), null)
                    return
                }
                port.broadcast(listOf(runtime.region), MessageKey.FARM_HELL_GREENHOUSE_STARTED,
                    values = mapOf("total" to locale.text(runtime.state.incidentRequired)),
                    sound = Sound.BLOCK_PORTAL_TRIGGER, title = true)
            }
            FarmIncidentType.TORNADO -> tornadoStarted(runtime)
            FarmIncidentType.FROST -> {
                if (!frost.initialize(runtime)) {
                    actor?.takeIf {
                        it.hasPermission("arcfarms.admin") && !frost.hasConfiguredPoint(runtime)
                    }?.let { player ->
                        port.sendChat(
                            player,
                            MessageKey.ADMIN_POINT_NOT_OVERRIDDEN,
                            mapOf("point" to locale.renderPath("admin.point.firewood", player)),
                        )
                    }
                    apply(runtime, FarmShiftEngine.skipUnavailableIncident(runtime.state, FarmIncidentType.FROST), null)
                    return
                }
                frost.ensure(runtime)
                port.broadcast(
                    listOf(runtime.region),
                    MessageKey.FARM_FROST_STARTED,
                    sound = Sound.BLOCK_GLASS_BREAK,
                    title = true,
                )
            }
            FarmIncidentType.BOAR_BREAKOUT, FarmIncidentType.RIVAL_RAID -> {
                if (actionIncidents.initialize(runtime, type) == null) {
                    apply(runtime, FarmSpecialIncidentEngine.skipUnavailable(runtime.state), null)
                    return
                }
                actionIncidents.ensure(runtime)
                actionIncidents.announce(runtime)
            }
            else -> {
                val activeType = special.initialize(runtime, type) ?: return
                special.announce(runtime, activeType)
                special.ensure(runtime)
            }
        }
        issueRequiredSupply(runtime)
        port.warningBurst(runtime.region)
        network.signal(
            NetworkSignal.FARM_INCIDENT,
            ActivityKind.FARM,
            actor?.name,
            players(runtime).mapTo(mutableSetOf(), Player::getUniqueId),
        )
        state.persistAsync()
    }

    private fun incidentProgress(runtime: FarmRuntime, type: FarmIncidentType, actor: Player?) {
        actor ?: return
        val key = when (type) {
            FarmIncidentType.DROUGHT -> MessageKey.FARM_DROUGHT_PROGRESS
            FarmIncidentType.PESTS -> MessageKey.FARM_INCIDENT_PROGRESS
            FarmIncidentType.BIRDS -> MessageKey.FARM_BIRDS_PROGRESS
            FarmIncidentType.FOOD_DELIVERY -> MessageKey.FARM_ROUTE_PROGRESS
            FarmIncidentType.PROCESSING -> MessageKey.FARM_PROCESSING_BOSSBAR
            FarmIncidentType.BARN_FIRE -> MessageKey.FARM_BARN_FIRE_PROGRESS
            FarmIncidentType.FROST -> MessageKey.FARM_FROST_PROGRESS
            FarmIncidentType.BOAR_BREAKOUT -> MessageKey.FARM_BOAR_BREAKOUT_PROGRESS
            FarmIncidentType.RIVAL_RAID -> MessageKey.FARM_RIVAL_RAID_PROGRESS
            FarmIncidentType.MARKET -> MessageKey.FARM_MARKET_PROGRESS
            FarmIncidentType.CHANNELS -> MessageKey.FARM_CHANNELS_PROGRESS
            else -> MessageKey.FARM_SPECIAL_PROGRESS
        }
        port.sendActionBar(actor, key, buildMap {
            put("done", locale.text(runtime.state.incidentProgress))
            put("total", locale.text(runtime.state.incidentRequired))
            if (type in SPECIAL_FARM_INCIDENT_TYPES || type in ACTION_FARM_INCIDENT_TYPES) {
                put("event", special.name(type, actor))
            }
            if (type == FarmIncidentType.PROCESSING) put("instruction", locale.render(processingHint(runtime), actor))
            if (type == FarmIncidentType.MARKET) runtime.state.specialIncident?.let { incident ->
                incident.crop?.let { put("crop", MaterialRules.cropComponent(MaterialRules.material(it))) }
                put("time", locale.text(special.marketTime(runtime, incident)))
            }
        })
    }

    private fun tornadoStarted(runtime: FarmRuntime) {
        if (!tornado.initialize(runtime)) {
            runtime.state = runtime.state.copy(tornado = null)
            state.persistAsync()
            return
        }
        port.broadcast(listOf(runtime.region), MessageKey.FARM_TORNADO_STARTED,
            sound = Sound.ENTITY_BREEZE_CHARGE, title = true)
    }

    private fun incidentResolved(runtime: FarmRuntime, type: FarmIncidentType, actor: Player?) {
        clearSupplies(runtime, "incident_resolved")
        drought.resetGrowth(runtime.settings.id)
        pests.clear(runtime, "incident_resolved")
        birds.clear(runtime.settings.id, "incident_resolved")
        foodDelivery.clear(runtime.settings.id, "incident_resolved")
        processing.clear(runtime.settings.id, "incident_resolved")
        barnFire.clear(runtime.settings.id, "incident_resolved")
        frost.clear(runtime, "incident_resolved")
        tornado.clear(runtime)
        greenhouse.clear(runtime)
        actionIncidents.clear(runtime, "incident_resolved")
        if (type == FarmIncidentType.GIANT_CROP) special.beginRestore(runtime)
        special.clearZone(runtime, "incident_resolved")
        if (type == FarmIncidentType.HELL_GREENHOUSE) { state.persistAsync(); return }
        port.broadcast(
            listOf(runtime.region),
            if (type in SPECIAL_FARM_INCIDENT_TYPES || type in ACTION_FARM_INCIDENT_TYPES ||
                type in setOf(FarmIncidentType.PROCESSING, FarmIncidentType.BARN_FIRE, FarmIncidentType.FROST, FarmIncidentType.TORNADO)
            ) {
                MessageKey.FARM_SPECIAL_RESOLVED
            } else MessageKey.FARM_INCIDENT_RESOLVED,
            sound = Sound.ENTITY_VILLAGER_YES,
            title = true,
        )
        port.successBurst(runtime.region)
        hud.playStageFanfare(runtime, 1.15f)
        network.signal(
            NetworkSignal.FARM_RESCUED,
            ActivityKind.FARM,
            actor?.name,
            players(runtime).mapTo(mutableSetOf(), Player::getUniqueId),
        )
        state.persistAsync()
    }

    private fun processingStageChanged(runtime: FarmRuntime) {
        val (title, subtitle, pitch) = when (runtime.state.processing?.stage) {
            FarmProcessingStage.OPERATING -> Triple(
                MessageKey.FARM_PROCESSING_OPERATING_TITLE,
                MessageKey.FARM_PROCESSING_OPERATING_SUBTITLE,
                1.0f,
            )
            FarmProcessingStage.PACKING -> Triple(
                MessageKey.FARM_PROCESSING_PACKING_TITLE,
                MessageKey.FARM_PROCESSING_PACKING_SUBTITLE,
                1.2f,
            )
            FarmProcessingStage.LOADING, null -> Triple(
                MessageKey.FARM_PROCESSING_LOADING_TITLE,
                MessageKey.FARM_PROCESSING_LOADING_SUBTITLE,
                0.9f,
            )
        }
        players(runtime).forEach { player ->
            port.showScreenTitle(player, locale.render(title, player), locale.render(subtitle, player))
            if (settings().sounds) {
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, pitch)
                player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.65f, pitch + 0.15f)
            }
        }
        port.successBurst(runtime.region)
        processing.ensure(runtime)
        state.persistAsync()
    }

    private fun processingHint(runtime: FarmRuntime): MessageKey = when (runtime.state.processing?.stage) {
        FarmProcessingStage.LOADING -> MessageKey.FARM_PROCESSING_LOADING_HINT
        FarmProcessingStage.OPERATING -> MessageKey.FARM_PROCESSING_OPERATING_HINT
        FarmProcessingStage.PACKING -> MessageKey.FARM_PROCESSING_PACKING_HINT
        null -> MessageKey.FARM_PROCESSING_LOADING_HINT
    }

    private fun marketExpired(runtime: FarmRuntime) {
        drought.resetGrowth(runtime.settings.id)
        special.clearZone(runtime, "market_expired")
        port.broadcast(
            listOf(runtime.region),
            MessageKey.FARM_MARKET_EXPIRED,
            sound = Sound.ENTITY_VILLAGER_NO,
            title = true,
        )
        port.warningBurst(runtime.region)
        debug.event(
            "farm_market_expired",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "restores" to runtime.state.specialDamagedCrops.size,
        )
        state.persistAsync()
    }

    private fun deliveryStarted(runtime: FarmRuntime) {
        tornado.clear(runtime)
        hud.storyTitle(runtime, "delivery", Sound.BLOCK_BARREL_CLOSE) { player ->
            locale.render(MessageKey.FARM_DELIVERY_STARTED, player) to emptyMap()
        }
        pests.clear(runtime, "delivery_started")
        delivery.ensure(runtime)
        state.persistAsync()
    }

    private fun deliveryProgress(runtime: FarmRuntime, actor: Player?) {
        actor?.let { port.sendActionBar(it, MessageKey.FARM_DELIVERY_REQUIRED) }
        delivery.ensure(runtime)
        scene.ensure(runtime)
        val cart = points.resolve(runtime, FarmPointKind.CART)
        Bukkit.getWorld(cart.world)?.let { world ->
            val location = Location(world, cart.x, cart.y + 0.9, cart.z)
            if (settings().particles) world.spawnParticle(Particle.COMPOSTER, location, 10, 0.45, 0.3, 0.45, 0.03)
            if (settings().sounds) players(runtime).forEach { it.playSound(location, Sound.BLOCK_BARREL_CLOSE, 0.75f, 1.1f) }
        }
        state.persistAsync()
    }

    private fun completed(runtime: FarmRuntime, actor: Player?, commercialEligible: Boolean) {
        players(runtime).forEach { supplies.removeServiceItems(it, runtime.settings.id, "shift_completed") }
        delivery.clear(runtime, "completed")
        foodDelivery.clear(runtime.settings.id, "shift_completed")
        actionIncidents.clear(runtime, "shift_completed")
        tornado.clear(runtime)
        greenhouse.clear(runtime)
        val contributors = runtime.state.contributors
        if (commercialEligible && contributors.isNotEmpty()) {
            Bukkit.getPluginManager().callEvent(
                WorkShiftCompletedEvent(
                    eventId = "${settings().serverId}:farm:${runtime.settings.id}:${runtime.state.sequence}",
                    kind = "farm",
                    contributors = contributors.keys,
                    zoneId = runtime.settings.id,
                ),
            )
        }
        val enterpriseChanged = enterprise.orderCompleted(runtime.settings.id, runtime.state.sequence, contributors, commercialEligible)
        stats.recordCompletion(ActivityKind.FARM, contributors)
        rewards.queueCompletion(runtime, contributors)
        port.broadcast(
            listOf(runtime.region),
            MessageKey.FARM_COMPLETED,
            mapOf("players" to locale.text(contributors.size)),
            Sound.UI_TOAST_CHALLENGE_COMPLETE,
            title = true,
        )
        port.announceWinner(listOf(runtime.region), contributors)
        port.celebration(listOf(runtime.region))
        network.complete(
            ActivityKind.FARM,
            actor?.name,
            players(runtime).mapTo(mutableSetOf(), Player::getUniqueId),
        )
        val personal = if (enterpriseChanged && commercialEligible) contributors.keys.mapNotNull { id ->
            enterprise.playerView(id)?.let { id to it }
        }.toMap() else emptyMap()
        val token = tasks.lifecycleToken()
        state.persistAsync().whenComplete { _, failure ->
            if (failure == null && personal.isNotEmpty()) tasks.runSync(token) {
                personal.forEach { (id, view) ->
                    Bukkit.getPlayer(id)?.let { player ->
                        val weekSettings = settings().enterprises.getValue(ActivityKind.FARM).businessWeek
                        val date = java.time.Instant.ofEpochMilli(view.nextSettlementMillis).atZone(weekSettings.zoneId)
                            .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm z"))
                        port.sendChat(player, if (view.simulated) MessageKey.COMPANY_PERSONAL_SHADOW else MessageKey.COMPANY_PERSONAL_RESULT,
                            mapOf("amount" to locale.text(java.math.BigDecimal.valueOf(view.workerAccruedCents, 2).toPlainString()), "date" to locale.text(date)))
                    }
                }
            }
        }
    }

    private fun seederInstructionPath(state: FarmShiftState): String = when (state.seederStage()) {
        FarmSeederStage.TILLING -> "care.seeder.tilling-instruction"
        FarmSeederStage.PLANTING -> "care.seeder.planting-instruction"
        null -> "care.seeder.instruction"
    }

    private fun currentOrder(runtime: FarmRuntime): FarmOrder? = runtime.state.orderId?.let(runtime.orders::get)
    private fun players(runtime: FarmRuntime): List<Player> = port.players(runtime.region)

    private fun issueRequiredSupply(runtime: FarmRuntime) {
        players(runtime).forEach { player ->
            if (!supplies.ensureRequired(runtime, player)) {
                port.sendActionBar(player, MessageKey.FARM_ACTION_INVENTORY_FULL)
            }
        }
    }

    private fun clearSupplies(runtime: FarmRuntime, reason: String) {
        players(runtime).forEach { supplies.removeServiceItems(it, runtime.settings.id, reason) }
    }

    private companion object {
        const val PERSIST_INTERVAL = 10
    }
}
