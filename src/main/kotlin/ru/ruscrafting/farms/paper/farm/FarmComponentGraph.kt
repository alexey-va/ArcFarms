package ru.ruscrafting.farms.paper.farm

import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.ArcFarmsRuntimeValidator
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmEconomyGateway
import ru.ruscrafting.farms.paper.FarmNightShiftController
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.RegionGateway
import ru.ruscrafting.farms.paper.worksite.WorksitePorts
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import ru.ruscrafting.farms.paper.farm.admin.FarmGameplayAdminService
import ru.ruscrafting.farms.paper.farm.admin.FarmPointAdminService
import ru.ruscrafting.farms.paper.farm.admin.FarmRouteAdminService
import ru.ruscrafting.farms.paper.farm.admin.FarmWorldAdminService
import ru.ruscrafting.farms.paper.farm.care.FarmCareController
import ru.ruscrafting.farms.paper.farm.care.FarmCarePlanService
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowController
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowWorld
import ru.ruscrafting.farms.paper.farm.care.mole.PaperMoleBurrowChunkRetention
import ru.ruscrafting.farms.paper.farm.delivery.FarmDeliveryController
import ru.ruscrafting.farms.paper.farm.enterprise.FarmEnterprisePort
import ru.ruscrafting.farms.paper.farm.field.FarmFieldController
import ru.ruscrafting.farms.paper.farm.harvest.FarmHarvestController
import ru.ruscrafting.farms.paper.farm.incident.drought.FarmDroughtIncident
import ru.ruscrafting.farms.paper.farm.incident.bird.FarmBirdIncident
import ru.ruscrafting.farms.paper.farm.incident.pest.FarmPestIncident
import ru.ruscrafting.farms.paper.farm.incident.special.FarmSpecialIncidentController
import ru.ruscrafting.farms.paper.farm.incident.route.FarmFoodDeliveryIncident
import ru.ruscrafting.farms.paper.farm.incident.processing.FarmProcessingIncident
import ru.ruscrafting.farms.paper.farm.incident.fire.FarmBarnFireIncident
import ru.ruscrafting.farms.paper.farm.incident.frost.FarmFrostIncident
import ru.ruscrafting.farms.paper.farm.incident.action.FarmActionIncidentController
import ru.ruscrafting.farms.paper.farm.placement.FarmPlacementService
import ru.ruscrafting.farms.paper.farm.point.FarmPointService
import ru.ruscrafting.farms.paper.farm.perk.FarmPerkController
import ru.ruscrafting.farms.paper.farm.presentation.FarmGuidanceController
import ru.ruscrafting.farms.paper.farm.presentation.FarmHarvestGuidance
import ru.ruscrafting.farms.paper.farm.presentation.FarmHudController
import ru.ruscrafting.farms.paper.farm.recovery.FarmFixedCropRecoveryController
import ru.ruscrafting.farms.paper.farm.recovery.FarmIncidentRecoveryController
import ru.ruscrafting.farms.paper.farm.reward.FarmRewardService
import ru.ruscrafting.farms.paper.farm.scene.FarmContractSceneController
import ru.ruscrafting.farms.paper.farm.shift.FarmOrderCycleController
import ru.ruscrafting.farms.paper.farm.shift.FarmShiftCoordinator
import ru.ruscrafting.farms.paper.farm.shift.FarmShiftStartService
import ru.ruscrafting.farms.paper.farm.supply.FarmSupplyController
import ru.ruscrafting.farms.paper.platform.PaperFarmBlockDataDecoder
import ru.ruscrafting.farms.paper.platform.PaperFarmBlockPassability
import ru.ruscrafting.farms.paper.platform.PaperFarmEntityRayTrace
import ru.ruscrafting.farms.paper.platform.PaperFarmMobDespawnPolicy
import ru.ruscrafting.farms.paper.platform.PaperFarmTextDisplayRenderer
import ru.ruscrafting.farms.paper.platform.PaperFarmVehiclePassengerControl
import ru.ruscrafting.farms.persistence.FarmLocationRepository
import ru.ruscrafting.farms.persistence.FarmRouteRepository
import ru.ruscrafting.farms.persistence.FixedFarmCropJournal
import java.util.random.RandomGenerator
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Composition-only graph for the farm vertical slices. It contains no listener,
 * tick, state-machine or world-mutation logic of its own.
 */
internal class FarmComponentGraph(
    plugin: Plugin,
    settings: () -> ArcFarmsConfig,
    locale: ArcFarmsLocale,
    fixedCropJournal: FixedFarmCropJournal,
    farmLocationRepository: FarmLocationRepository,
    farmRouteRepository: FarmRouteRepository,
    debug: ArcFarmsDebug,
    economy: FarmEconomyGateway,
    runtimeValidator: ArcFarmsRuntimeValidator,
    regionGateway: RegionGateway,
    ports: WorksitePorts,
    serviceItems: WorksiteServiceItems,
    taskSupervisor: ru.ruscrafting.farms.paper.RuntimeTaskSupervisor,
    clock: () -> Long,
    random: RandomGenerator,
    weeklyContribution: (UUID) -> Long,
    currentWeekStart: () -> Long,
    persistAsync: () -> CompletableFuture<Unit>,
    enterprise: FarmEnterprisePort,
) {
    val runtimes = FarmRuntimeRegistry()
    private val blockPassability = PaperFarmBlockPassability
    private val blockDataDecoder = PaperFarmBlockDataDecoder
    private val textDisplays = PaperFarmTextDisplayRenderer
    private val entityRayTrace = PaperFarmEntityRayTrace
    private val mobDespawns = PaperFarmMobDespawnPolicy
    private val vehiclePassengers = PaperFarmVehiclePassengerControl
    private val moleChunkRetention = PaperMoleBurrowChunkRetention(plugin)
    private val ledger = FarmBlockLedger(plugin)
    val blockRegistry = FarmBlockRegistry(plugin, ledger, clock)
    val pointService = FarmPointService(settings, farmLocationRepository)
    val routeAdmin = FarmRouteAdminService(farmRouteRepository, debug, ports.audience, runtimes::snapshot)
    private val basePoints = FarmPointProvider(pointService::resolveBase)
    private val placement = FarmPlacementService(plugin, blockRegistry, basePoints, debug, random)
    private val moleBurrowWorld = FarmMoleBurrowWorld(plugin, debug, moleChunkRetention, blockDataDecoder)
    private val carePlans = FarmCarePlanService(
        debug = debug,
        registry = blockRegistry,
        placement = placement,
        points = basePoints,
        overrides = pointService::snapshot,
        random = random,
        moleBurrow = moleBurrowWorld,
        participantCount = { region -> ports.audience.players(region).size },
        log = ports.state::log,
    )
    private val points = object : FarmPointProvider {
        override fun resolve(runtime: FarmRuntime, kind: FarmPointKind) =
            carePlans.fixturePoint(runtime, kind) ?: pointService.resolveBase(runtime, kind)

        override fun configured(runtime: FarmRuntime, kind: FarmPointKind) =
            pointService.configured(runtime.settings.id, kind)
    }
    private val transitions = FarmTransitionRouter()
    private val launches = FarmShiftLaunchRouter()
    private val taskHints = FarmTaskHintRouter()
    private val fixedCrops = FarmFixedCropRecoveryController(
        journal = fixedCropJournal,
        ledger = ledger,
        locale = locale,
        debug = debug,
        access = ports.access,
        port = ports.audience,
        state = ports.state,
        tasks = ports.tasks,
        runtimes = runtimes::snapshot,
        clock = clock,
    )
    private val field = FarmFieldController(
        settings = settings,
        debug = debug,
        access = ports.access,
        audience = ports.audience,
        state = ports.state,
        tasks = ports.tasks,
        ledger = ledger,
        registry = blockRegistry,
        points = points,
        transitions = transitions,
        persistAsync = persistAsync,
    )
    val moles = FarmMoleBurrowController(
        plugin = plugin,
        settings = settings,
        locale = locale,
        debug = debug,
        access = ports.access,
        audience = ports.audience,
        state = ports.state,
        tasks = ports.tasks,
        world = moleBurrowWorld,
        transitions = transitions,
        runtimes = runtimes::snapshot,
        clock = clock,
        textDisplays = textDisplays,
        mobDespawns = mobDespawns,
    )
    private val care = FarmCareController(
        plugin = plugin,
        settings = settings,
        locale = locale,
        debug = debug,
        access = ports.access,
        audience = ports.audience,
        state = ports.state,
        serviceItems = serviceItems,
        ledger = ledger,
        registry = blockRegistry,
        plans = carePlans,
        points = points,
        moles = moles,
        transitions = transitions,
        runtimes = runtimes::snapshot,
        clock = clock,
    )
    private val incidentBeds = FarmIncidentBedProvider(field::incidentBeds)
    private val nightShift = FarmNightShiftController(plugin)
    private val frost = FarmFrostIncident(
        plugin = plugin,
        settings = settings,
        locale = locale,
        debug = debug,
        access = ports.access,
        audience = ports.audience,
        state = ports.state,
        ledger = ledger,
        beds = incidentBeds,
        points = points,
        transitions = transitions,
        runtimes = runtimes::snapshot,
        clock = clock,
        night = nightShift,
    )
    private val birds = FarmBirdIncident(
        plugin = plugin,
        settings = settings,
        locale = locale,
        debug = debug,
        access = ports.access,
        audience = ports.audience,
        state = ports.state,
        tasks = ports.tasks,
        ledger = ledger,
        beds = incidentBeds,
        transitions = transitions,
    )
    private val foodDelivery = FarmFoodDeliveryIncident(
        plugin = plugin,
        settings = settings,
        locale = locale,
        debug = debug,
        access = ports.access,
        audience = ports.audience,
        state = ports.state,
        tasks = ports.tasks,
        routes = routeAdmin,
        points = points,
        transitions = transitions,
        random = random,
        night = nightShift,
        entityRayTrace = entityRayTrace,
        blockPassability = blockPassability,
        mobDespawns = mobDespawns,
        vehiclePassengers = vehiclePassengers,
        textDisplays = textDisplays,
    )
    val perks = FarmPerkController(
        plugin = plugin,
        settings = settings,
        locale = locale,
        debug = debug,
        access = ports.access,
        audience = ports.audience,
        state = ports.state,
        tasks = ports.tasks,
        points = points,
        runtimes = runtimes::snapshot,
        weeklyContribution = weeklyContribution,
        currentWeekStart = currentWeekStart,
        clock = clock,
        persistAsync = persistAsync,
    )
    val rewards = FarmRewardService(
        plugin = plugin,
        locale = locale,
        economy = economy,
        debug = debug,
        port = ports.audience,
        supervisor = taskSupervisor,
        persistAsync = persistAsync,
        operational = ports.access::isOperational,
        playerMultiplier = { playerId, runtime ->
            perks.rewardMultiplier(playerId, runtime.settings.perks.rewardBonusPercent)
        },
    )
    val supplies = FarmSupplyController(plugin, locale, debug, settings)
    private val delivery = FarmDeliveryController(
        plugin = plugin,
        settings = settings,
        debug = debug,
        access = ports.access,
        port = ports.audience,
        points = points,
        placement = placement,
        transitions = transitions,
    )
    val drought = FarmDroughtIncident(
        settings = settings,
        debug = debug,
        access = ports.access,
        audience = ports.audience,
        state = ports.state,
        tasks = ports.tasks,
        blockLedger = ledger,
        blockRegistry = blockRegistry,
        beds = incidentBeds,
        transitions = transitions,
        clock = clock,
    )
    private val recovery = FarmIncidentRecoveryController(ledger, ports.access, ports.state)
    private val harvest = FarmHarvestController(
        settings = settings,
        locale = locale,
        debug = debug,
        access = ports.access,
        audience = ports.audience,
        tasks = ports.tasks,
        ledger = ledger,
        fixedCrops = fixedCrops,
        incidentRecovery = recovery,
        drought = drought,
        placement = placement,
        transitions = transitions,
        shiftStarter = launches,
        taskHints = taskHints,
        runtimes = runtimes::snapshot,
        clock = clock,
        perkActive = perks::active,
        harvestAreaRadius = { runtime -> runtime.settings.perks.harvestAreaRadius },
    )
    private val special = FarmSpecialIncidentController(
        plugin = plugin,
        settings = settings,
        locale = locale,
        debug = debug,
        access = ports.access,
        audience = ports.audience,
        state = ports.state,
        tasks = ports.tasks,
        serviceItems = serviceItems,
        ledger = ledger,
        registry = blockRegistry,
        beds = incidentBeds,
        points = points,
        transitions = transitions,
        runtimes = runtimes::snapshot,
        clock = clock,
        nightShift = nightShift,
    )
    private val actionIncidents = FarmActionIncidentController(
        plugin = plugin,
        settings = settings,
        locale = locale,
        debug = debug,
        access = ports.access,
        audience = ports.audience,
        state = ports.state,
        serviceItems = serviceItems,
        ledger = ledger,
        beds = incidentBeds,
        points = points,
        transitions = transitions,
        runtimes = runtimes::snapshot,
        entityRayTrace = entityRayTrace,
    )
    private val processing = FarmProcessingIncident(
        plugin = plugin,
        settings = settings,
        locale = locale,
        debug = debug,
        access = ports.access,
        audience = ports.audience,
        state = ports.state,
        configuredPoint = pointService::configured,
        transitions = transitions,
        blockPassability = blockPassability,
        textDisplays = textDisplays,
    )
    private val barnFire = FarmBarnFireIncident(
        settings = settings,
        debug = debug,
        access = ports.access,
        audience = ports.audience,
        state = ports.state,
        points = points,
        transitions = transitions,
        blockPassability = blockPassability,
    )
    private val scene = FarmContractSceneController(
        plugin = plugin,
        settings = settings,
        locale = locale,
        debug = debug,
        access = ports.access,
        audience = ports.audience,
        state = ports.state,
        points = points,
        special = special,
        runtimes = runtimes::snapshot,
    )
    private val pests = FarmPestIncident(
        plugin = plugin,
        settings = settings,
        locale = locale,
        debug = debug,
        access = ports.access,
        audience = ports.audience,
        state = ports.state,
        tasks = ports.tasks,
        blockLedger = ledger,
        blockRegistry = blockRegistry,
        beds = incidentBeds,
        transitions = transitions,
        random = random,
    )
    val hud = FarmHudController(
        settings = settings,
        locale = locale,
        debug = debug,
        access = ports.access,
        audience = ports.audience,
        tasks = ports.tasks,
        delivery = delivery,
        foodDelivery = foodDelivery,
        actionIncidents = actionIncidents,
        special = special,
        harvest = harvest,
        clock = clock,
    )
    private val guidance = FarmGuidanceController(
        settings = settings,
        access = ports.access,
        audience = ports.audience,
        tasks = ports.tasks,
        care = care,
        carePlans = carePlans,
        delivery = delivery,
        harvest = FarmHarvestGuidance(blockRegistry),
        points = points,
        runtimes = runtimes::snapshot,
    )
    private val shifts = FarmShiftCoordinator(
        settings = settings,
        locale = locale,
        debug = debug,
        port = ports.audience,
        state = ports.state,
        stats = ports.stats,
        network = ports.network,
        carePlans = carePlans,
        care = care,
        drought = drought,
        pests = pests,
        birds = birds,
        foodDelivery = foodDelivery,
        actionIncidents = actionIncidents,
        special = special,
        processing = processing,
        barnFire = barnFire,
        frost = frost,
        delivery = delivery,
        scene = scene,
        supplies = supplies,
        rewards = rewards,
        enterprise = enterprise,
        hud = hud,
        points = points,
    )
    val orderCycle = FarmOrderCycleController(ports.state, ports.tasks, persistAsync)
    val worldAdmin = FarmWorldAdminService(
        plugin = plugin,
        dataFolder = plugin.dataFolder.toPath(),
        locale = locale,
        debug = debug,
        access = ports.access,
        audience = ports.audience,
        ledger = ledger,
        registry = blockRegistry,
        fixedCrops = fixedCrops,
        fixedCropJournal = fixedCropJournal,
        care = care,
        pests = pests,
        delivery = delivery,
        enterprise = enterprise,
        special = special,
        transitions = transitions,
        runtimes = runtimes::snapshot,
        paused = orderCycle::isPaused,
        setPaused = orderCycle::set,
        persistAsync = persistAsync,
        clock = clock,
    )
    private val shiftStart = FarmShiftStartService(
        debug = debug,
        access = ports.access,
        audience = ports.audience,
        state = ports.state,
        tasks = ports.tasks,
        orderCycle = orderCycle,
        worldAdmin = worldAdmin,
        registry = blockRegistry,
        field = field,
        carePlans = carePlans,
        enterprise = enterprise,
        transitions = transitions,
        persistAsync = persistAsync,
        retrySettings = { settings().shiftStartPersistence },
        random = random,
    )
    val events = FarmEventRouter(
        locale = locale,
        debug = debug,
        access = ports.access,
        audience = ports.audience,
        state = ports.state,
        runtimes = runtimes::snapshot,
        worldAdmin = worldAdmin,
        ledger = ledger,
        registry = blockRegistry,
        fixedCrops = fixedCrops,
        field = field,
        care = care,
        drought = drought,
        pests = pests,
        birds = birds,
        foodDelivery = foodDelivery,
        actionIncidents = actionIncidents,
        routeAdmin = routeAdmin,
        perks = perks,
        special = special,
        processing = processing,
        barnFire = barnFire,
        frost = frost,
        delivery = delivery,
        enterprise = enterprise,
        supplies = supplies,
        scene = scene,
        harvest = harvest,
        hud = hud,
        transitions = transitions,
        shiftStartPending = shiftStart::isPending,
        persistAsync = persistAsync,
        clock = clock,
    )
    val module = FarmModule(
        settings = settings,
        debug = debug,
        access = ports.access,
        audience = ports.audience,
        state = ports.state,
        tasks = ports.tasks,
        registry = runtimes,
        regionGateway = regionGateway,
        blockRegistry = blockRegistry,
        orderCycle = orderCycle,
        worldAdmin = worldAdmin,
        shiftStart = shiftStart,
        transitions = transitions,
        field = field,
        fixedCrops = fixedCrops,
        incidentRecovery = recovery,
        carePlans = carePlans,
        care = care,
        moles = moles,
        drought = drought,
        pests = pests,
        birds = birds,
        foodDelivery = foodDelivery,
        actionIncidents = actionIncidents,
        perks = perks,
        special = special,
        processing = processing,
        barnFire = barnFire,
        frost = frost,
        delivery = delivery,
        scene = scene,
        supplies = supplies,
        points = points,
        pointService = pointService,
        placement = placement,
        hud = hud,
        guidance = guidance,
        events = events,
    )
    val pointAdmin = FarmPointAdminService(
        settings = settings,
        locale = locale,
        debug = debug,
        port = ports.audience,
        state = ports.state,
        pointService = pointService,
        points = points,
        carePlans = carePlans,
        validator = runtimeValidator,
        processing = processing,
        runtimes = runtimes::snapshot,
        refresh = module::refreshPoint,
    )
    val gameplayAdmin = FarmGameplayAdminService(
        locale = locale,
        debug = debug,
        access = ports.access,
        port = ports.audience,
        state = ports.state,
        runtimes = runtimes::snapshot,
        orderCycle = orderCycle,
        worldAdmin = worldAdmin,
        field = field,
        care = care,
        drought = drought,
        pests = pests,
        birds = birds,
        foodDelivery = foodDelivery,
        actionIncidents = actionIncidents,
        special = special,
        processing = processing,
        barnFire = barnFire,
        frost = frost,
        incidentRecovery = recovery,
        delivery = delivery,
        enterprise = enterprise,
        scene = scene,
        supplies = supplies,
        harvest = harvest,
        placement = placement,
        guidance = guidance,
        ledger = ledger,
        registry = blockRegistry,
        transitions = transitions,
        shiftLauncher = launches,
        persistAsync = persistAsync,
        clock = clock,
    )
    init {
        transitions.bind(FarmTransitionSink(shifts::apply))
        launches.bind(FarmShiftLauncher(shiftStart::start))
        taskHints.bind(FarmTaskHintSink(hud::taskHint))
    }
}
