package ru.ruscrafting.farms.paper.farm

import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.ArcFarmsRuntimeValidator
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmEconomyGateway
import ru.ruscrafting.farms.paper.FarmNightShiftController
import ru.ruscrafting.farms.paper.RegionGateway
import ru.ruscrafting.farms.paper.WorksiteModuleRegistry
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.admin.FarmGameplayAdminService
import ru.ruscrafting.farms.paper.farm.admin.FarmPointAdminService
import ru.ruscrafting.farms.paper.farm.admin.FarmRouteAdminService
import ru.ruscrafting.farms.paper.farm.admin.FarmWorldAdminService
import ru.ruscrafting.farms.paper.farm.care.FarmCareController
import ru.ruscrafting.farms.paper.farm.care.FarmCarePlanService
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowController
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowWorld
import ru.ruscrafting.farms.paper.farm.delivery.FarmDeliveryController
import ru.ruscrafting.farms.paper.farm.field.FarmFieldController
import ru.ruscrafting.farms.paper.farm.harvest.FarmHarvestController
import ru.ruscrafting.farms.paper.farm.incident.drought.FarmDroughtIncident
import ru.ruscrafting.farms.paper.farm.incident.bird.FarmBirdIncident
import ru.ruscrafting.farms.paper.farm.incident.pest.FarmPestIncident
import ru.ruscrafting.farms.paper.farm.incident.special.FarmSpecialIncidentController
import ru.ruscrafting.farms.paper.farm.incident.route.FarmFoodDeliveryIncident
import ru.ruscrafting.farms.paper.farm.incident.processing.FarmProcessingIncident
import ru.ruscrafting.farms.paper.farm.placement.FarmPlacementService
import ru.ruscrafting.farms.paper.farm.point.FarmPointService
import ru.ruscrafting.farms.paper.farm.perk.FarmPerkController
import ru.ruscrafting.farms.paper.farm.presentation.FarmGuidanceController
import ru.ruscrafting.farms.paper.farm.presentation.FarmHudController
import ru.ruscrafting.farms.paper.farm.recovery.FarmFixedCropRecoveryController
import ru.ruscrafting.farms.paper.farm.recovery.FarmIncidentRecoveryController
import ru.ruscrafting.farms.paper.farm.reward.FarmRewardService
import ru.ruscrafting.farms.paper.farm.scene.FarmContractSceneController
import ru.ruscrafting.farms.paper.farm.shift.FarmOrderCycleController
import ru.ruscrafting.farms.paper.farm.shift.FarmShiftCoordinator
import ru.ruscrafting.farms.paper.farm.shift.FarmShiftStartService
import ru.ruscrafting.farms.paper.farm.supply.FarmSupplyController
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
    port: WorksiteRuntimePort,
    taskSupervisor: ru.ruscrafting.farms.paper.RuntimeTaskSupervisor,
    auxiliary: WorksiteModuleRegistry,
    clock: () -> Long,
    random: RandomGenerator,
    weeklyContribution: (UUID) -> Long,
    currentWeekStart: () -> Long,
    persistAsync: () -> CompletableFuture<Unit>,
) {
    val runtimes = FarmRuntimeRegistry()
    private val ledger = FarmBlockLedger(plugin)
    val blockRegistry = FarmBlockRegistry(plugin, ledger, clock)
    val pointService = FarmPointService(settings, farmLocationRepository)
    val routeAdmin = FarmRouteAdminService(farmRouteRepository, debug, port, runtimes::snapshot)
    private val basePoints = FarmPointProvider(pointService::resolveBase)
    private val placement = FarmPlacementService(plugin, blockRegistry, basePoints, debug, random)
    private val moleBurrowWorld = FarmMoleBurrowWorld(plugin, debug)
    private val carePlans = FarmCarePlanService(
        debug = debug,
        registry = blockRegistry,
        placement = placement,
        points = basePoints,
        overrides = pointService::snapshot,
        random = random,
        moleBurrow = moleBurrowWorld,
        participantCount = { region -> port.players(region).size },
        log = port::log,
    )
    private val points = FarmPointProvider { runtime, kind ->
        carePlans.fixturePoint(runtime, kind) ?: pointService.resolveBase(runtime, kind)
    }
    private val transitions = FarmTransitionRouter()
    private val launches = FarmShiftLaunchRouter()
    private val taskHints = FarmTaskHintRouter()
    private val fixedCrops = FarmFixedCropRecoveryController(
        journal = fixedCropJournal,
        ledger = ledger,
        locale = locale,
        debug = debug,
        port = port,
        runtimes = runtimes::snapshot,
        clock = clock,
    )
    private val field = FarmFieldController(
        settings = settings,
        debug = debug,
        port = port,
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
        port = port,
        world = moleBurrowWorld,
        transitions = transitions,
        runtimes = runtimes::snapshot,
        clock = clock,
    )
    private val care = FarmCareController(
        plugin = plugin,
        settings = settings,
        locale = locale,
        debug = debug,
        port = port,
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
    private val birds = FarmBirdIncident(
        plugin = plugin,
        settings = settings,
        locale = locale,
        debug = debug,
        port = port,
        ledger = ledger,
        beds = incidentBeds,
        transitions = transitions,
    )
    private val foodDelivery = FarmFoodDeliveryIncident(
        plugin = plugin,
        settings = settings,
        locale = locale,
        debug = debug,
        port = port,
        routes = routeAdmin,
        transitions = transitions,
        random = random,
        night = nightShift,
    )
    val perks = FarmPerkController(
        plugin = plugin,
        settings = settings,
        locale = locale,
        debug = debug,
        port = port,
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
        port = port,
        supervisor = taskSupervisor,
        persistAsync = persistAsync,
        operational = port::isOperational,
        playerMultiplier = { playerId, runtime ->
            perks.rewardMultiplier(playerId, runtime.settings.perks.rewardBonusPercent)
        },
    )
    val supplies = FarmSupplyController(plugin, locale, debug, settings)
    private val delivery = FarmDeliveryController(
        plugin = plugin,
        settings = settings,
        debug = debug,
        port = port,
        points = points,
        placement = placement,
        transitions = transitions,
        clock = clock,
    )
    val drought = FarmDroughtIncident(
        settings = settings,
        debug = debug,
        port = port,
        blockLedger = ledger,
        blockRegistry = blockRegistry,
        beds = incidentBeds,
        transitions = transitions,
        clock = clock,
    )
    private val recovery = FarmIncidentRecoveryController(ledger, port)
    private val harvest = FarmHarvestController(
        settings = settings,
        locale = locale,
        debug = debug,
        port = port,
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
    )
    private val special = FarmSpecialIncidentController(
        plugin = plugin,
        settings = settings,
        locale = locale,
        debug = debug,
        port = port,
        ledger = ledger,
        registry = blockRegistry,
        beds = incidentBeds,
        points = points,
        transitions = transitions,
        runtimes = runtimes::snapshot,
        clock = clock,
        nightShift = nightShift,
    )
    private val processing = FarmProcessingIncident(
        plugin = plugin,
        settings = settings,
        locale = locale,
        debug = debug,
        port = port,
        configuredPoint = pointService::configured,
        transitions = transitions,
    )
    private val scene = FarmContractSceneController(
        plugin = plugin,
        settings = settings,
        locale = locale,
        debug = debug,
        port = port,
        points = points,
        special = special,
        runtimes = runtimes::snapshot,
    )
    private val pests = FarmPestIncident(
        plugin = plugin,
        settings = settings,
        locale = locale,
        debug = debug,
        port = port,
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
        port = port,
        delivery = delivery,
        special = special,
        harvest = harvest,
        clock = clock,
    )
    private val guidance = FarmGuidanceController(
        settings = settings,
        port = port,
        care = care,
        carePlans = carePlans,
        delivery = delivery,
        points = points,
        runtimes = runtimes::snapshot,
    )
    private val shifts = FarmShiftCoordinator(
        settings = settings,
        locale = locale,
        debug = debug,
        port = port,
        carePlans = carePlans,
        care = care,
        drought = drought,
        pests = pests,
        birds = birds,
        foodDelivery = foodDelivery,
        special = special,
        processing = processing,
        delivery = delivery,
        scene = scene,
        supplies = supplies,
        rewards = rewards,
        hud = hud,
        points = points,
    )
    val orderCycle = FarmOrderCycleController(port, persistAsync)
    val worldAdmin = FarmWorldAdminService(
        plugin = plugin,
        dataFolder = plugin.dataFolder.toPath(),
        locale = locale,
        debug = debug,
        port = port,
        ledger = ledger,
        registry = blockRegistry,
        fixedCrops = fixedCrops,
        fixedCropJournal = fixedCropJournal,
        care = care,
        pests = pests,
        delivery = delivery,
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
        port = port,
        orderCycle = orderCycle,
        worldAdmin = worldAdmin,
        registry = blockRegistry,
        field = field,
        carePlans = carePlans,
        transitions = transitions,
        persistAsync = persistAsync,
        random = random,
    )
    val module = FarmModule(
        settings = settings,
        debug = debug,
        port = port,
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
        perks = perks,
        special = special,
        processing = processing,
        delivery = delivery,
        scene = scene,
        supplies = supplies,
        points = points,
        pointService = pointService,
        placement = placement,
        hud = hud,
        guidance = guidance,
    )
    val pointAdmin = FarmPointAdminService(
        settings = settings,
        locale = locale,
        debug = debug,
        port = port,
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
        port = port,
        runtimes = runtimes::snapshot,
        orderCycle = orderCycle,
        worldAdmin = worldAdmin,
        field = field,
        care = care,
        drought = drought,
        pests = pests,
        birds = birds,
        foodDelivery = foodDelivery,
        special = special,
        processing = processing,
        incidentRecovery = recovery,
        delivery = delivery,
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
    val events = FarmEventRouter(
        locale = locale,
        debug = debug,
        port = port,
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
        routeAdmin = routeAdmin,
        perks = perks,
        special = special,
        processing = processing,
        delivery = delivery,
        supplies = supplies,
        scene = scene,
        harvest = harvest,
        hud = hud,
        auxiliary = auxiliary,
        transitions = transitions,
        shiftStartPending = shiftStart::isPending,
        persistAsync = persistAsync,
        clock = clock,
    )

    init {
        transitions.bind(FarmTransitionSink(shifts::apply))
        launches.bind(FarmShiftLauncher(shiftStart::start))
        taskHints.bind(FarmTaskHintSink(hud::taskHint))
    }
}
