package ru.ruscrafting.farms.paper

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockGrowEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.block.MoistureChangeEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.ActivityStatsIndex
import ru.ruscrafting.farms.domain.farmWeekStartEpochDay
import ru.ruscrafting.farms.domain.ArcFarmsState
import ru.ruscrafting.farms.domain.FarmOrder
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.PlayerActivityStats
import ru.ruscrafting.farms.persistence.ArcFarmsStateRepository
import ru.ruscrafting.farms.persistence.FarmLocationRepository
import ru.ruscrafting.farms.persistence.FarmRouteRepository
import ru.ruscrafting.farms.persistence.FixedFarmCropJournal
import ru.ruscrafting.farms.persistence.MineBlockJournal
import ru.ruscrafting.farms.network.ActivityNetworkGateway
import ru.ruscrafting.farms.network.NoOpActivityNetworkGateway
import ru.ruscrafting.farms.network.WorkdayState
import ru.ruscrafting.farms.paper.farm.FarmComponentGraph
import ru.ruscrafting.farms.paper.navigation.ActivityTravelService
import ru.ruscrafting.farms.paper.lumber.LumbermillVersionedModule
import ru.ruscrafting.farms.paper.worksite.WorksiteEventRouter
import ru.ruscrafting.farms.paper.worksite.WorksiteParticipantSafety
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItemController
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.random.RandomGenerator
import java.util.logging.Level

data class ActivityStatus(
    val kind: ActivityKind,
    val id: String,
    val phasePath: String,
    val progress: String,
)

class ArcFarmsService(
    private val plugin: Plugin,
    initialSettings: ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    private val stateRepository: ArcFarmsStateRepository,
    private val mineJournal: MineBlockJournal,
    private val fixedCropJournal: FixedFarmCropJournal,
    private val farmLocationRepository: FarmLocationRepository,
    private val farmRouteRepository: FarmRouteRepository,
    private val network: ActivityNetworkGateway = NoOpActivityNetworkGateway,
    private val transfer: BackendTransfer = BackendTransfer { _, _ -> false },
    private val debug: ArcFarmsDebug = ArcFarmsDebug({ false }) {},
    private val regionGateway: RegionGateway = CuboidRegionGateway(),
    private val economy: FarmEconomyGateway = NoOpFarmEconomyGateway,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: RandomGenerator = RandomGenerator.getDefault(),
) : AutoCloseable {
    @Volatile
    private var settings: ArcFarmsConfig = initialSettings
    private val stats = ActivityStatsIndex(currentWeekStartEpochDay = { farmWeekStartEpochDay(clock()) })

    fun reportCommandFailure(sender: org.bukkit.command.CommandSender, label: String, failure: Exception) {
        plugin.logger.log(Level.SEVERE, "ArcFarms command failed: /$label", failure)
        sender.sendMessage(locale.render(MessageKey.GENERIC_ERROR, sender))
    }
    private val interactionCooldowns = mutableMapOf<String, Long>()
    private val taskSupervisor = RuntimeTaskSupervisor()
    private val worksitePort = PaperWorksiteRuntimePort(
        plugin = plugin,
        locale = locale,
        settings = { settings },
        debug = debug,
        network = network,
        stats = stats,
        supervisor = taskSupervisor,
        operational = ::isOperational,
        access = ::hasAccess,
        interaction = ::allowInteraction,
        interactionReset = interactionCooldowns::remove,
        interactionResetMatching = { fragment -> interactionCooldowns.keys.removeIf { fragment in it } },
        adminEditing = ::isAdminEditing,
        persist = ::persistAsync,
        guard = ::runGuarded,
    )
    private val lumbermillModule = LumbermillVersionedModule(
        initialSettings.lumbermills, regionGateway, locale, worksitePort, clock,
    )
    private val mineController = MineController(regionGateway, locale, mineJournal, worksitePort, clock, random)
    private val runtimeValidator = ArcFarmsRuntimeValidator(regionGateway, { economy.available }, fixedCropJournal, mineJournal)
    private val farm = FarmComponentGraph(
        plugin = plugin,
        settings = { settings },
        locale = locale,
        fixedCropJournal = fixedCropJournal,
        farmLocationRepository = farmLocationRepository,
        farmRouteRepository = farmRouteRepository,
        debug = debug,
        economy = economy,
        runtimeValidator = runtimeValidator,
        regionGateway = regionGateway,
        port = worksitePort,
        taskSupervisor = taskSupervisor,
        clock = clock,
        random = random,
        weeklyContribution = { playerId -> stats.weeklyContribution(playerId, ActivityKind.FARM) },
        currentWeekStart = { farmWeekStartEpochDay(clock()) },
        persistAsync = ::persistAsync,
    )
    private val worksites = WorksiteModuleRegistry(listOf(farm.module, lumbermillModule, mineController))
    private val serviceItems = WorksiteServiceItemController(plugin, worksites)
    private val participantSafety = WorksiteParticipantSafety(serviceItems, listOf(worksites))
    private val worksiteEvents = WorksiteEventRouter(worksites, serviceItems, participantSafety)
    private val travelService = ActivityTravelService(
        settings = { settings },
        debug = debug,
        port = worksitePort,
        network = network,
        transfer = transfer,
        points = farm.pointService,
        farms = farm.runtimes,
        auxiliary = worksites,
    )
    @Volatile
    private var started = false
    @Volatile
    private var closed = false
    private var stateSafeToPersist = false
    private var persistenceSuspended = false
    private var persistenceRequestedWhileSuspended = false
    private var seederVisualTick = 0L

    fun start() {
        check(!started && !closed) { "ArcFarms service cannot be started in its current lifecycle state" }
        taskSupervisor.activate()
        try {
            startActivatedRuntime()
        } catch (failure: Throwable) {
            runCatching(taskSupervisor::cancelAll).exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun startActivatedRuntime() {
        runtimeValidator.validateRuntime(settings)
        runtimeValidator.validateLocations(settings, farm.pointService.load())
        val loaded = stateRepository.load()
        val persisted = runtimeValidator.reconcileOrderProgress(settings, loaded)
        runtimeValidator.validatePersisted(settings, persisted)
        MineController.validateJournalMaterials(mineJournal)
        stats.replace(persisted.stats)
        farm.rewards.replace(persisted.pendingFarmRewards, persisted.claimedFarmRewardSequences)
        farm.perks.replace(persisted.farmPerks.orEmpty())
        farm.orderCycle.replace(persisted.pausedFarmZones.orEmpty())
        rebuild(persisted)
        worksites.cleanup("service_start")
        worksites.activateLoadedState()
        startTasks()
        stateSafeToPersist = true
        started = true
        if (persisted != loaded) persistAsync()
        taskSupervisor.runLater(1L) {
            if (isOperational()) farm.rewards.deliverPending(Bukkit.getOnlinePlayers())
        }
        plugin.logger.info(
            "ArcFarms ready: ${farm.runtimes.size} farm, ${lumbermillModule.zoneCount} lumbermill, ${mineController.zoneCount} mine zones; " +
                "${mineController.pendingBlockCount} pending mine blocks",
        )
    }

    fun reload(candidate: ArcFarmsConfig, publishSettings: (ArcFarmsConfig) -> Unit) {
        check(started) { "ArcFarms service is not started" }
        require(candidate.lumbermills.firstOrNull()?.engineVersion == settings.lumbermills.firstOrNull()?.engineVersion) {
            "Changing lumber engine-version requires a full plugin restart"
        }
        require(!farm.worldAdmin.backupBusy()) { "ArcFarms cannot reload while a farm backup operation is active" }
        val validationSnapshot = runtimeValidator.reconcileOrderProgress(candidate, snapshotState())
        runtimeValidator.validateReload(candidate, validationSnapshot)
        runtimeValidator.validateRuntime(candidate)
        runtimeValidator.validateLocations(candidate, farm.pointService.snapshot())
        farm.rewards.prepareForLifecycleBoundary()
        val snapshot = snapshotState()
        val reconciledSnapshot = runtimeValidator.reconcileOrderProgress(candidate, snapshot)
        persistBlocking()
        val previous = settings
        persistenceSuspended = true
        persistenceRequestedWhileSuspended = false
        publishSettings(candidate)
        try {
            stopTasks()
            replaceRuntime(candidate, reconciledSnapshot, "reload")
        } catch (failure: Exception) {
            plugin.logger.log(Level.SEVERE, "ArcFarms reload failed after runtime mutation; restoring the previous runtime", failure)
            publishSettings(previous)
            val rollback = runCatching {
                stopTasks()
                replaceRuntime(previous, snapshot, "reload_rollback")
            }
            if (rollback.isFailure) {
                stateSafeToPersist = false
                started = false
                failure.addSuppressed(requireNotNull(rollback.exceptionOrNull()))
                plugin.logger.log(Level.SEVERE, "ArcFarms reload rollback failed; the plugin must be disabled", rollback.exceptionOrNull())
            }
            persistenceRequestedWhileSuspended = false
            persistenceSuspended = false
            throw failure
        }
        persistenceSuspended = false
        if (persistenceRequestedWhileSuspended || reconciledSnapshot != snapshot) {
            persistenceRequestedWhileSuspended = false
            persistAsync()
        }
        plugin.logger.info(
            "ArcFarms reloaded with ${farm.runtimes.size + lumbermillModule.zoneCount + mineController.zoneCount} zones",
        )
    }

    fun isOperational(): Boolean = started && !closed

    private fun replaceRuntime(candidate: ArcFarmsConfig, snapshot: ArcFarmsState, reason: String) {
        taskSupervisor.activate()
        farm.hud.stopAllMusic(reason)
        farm.drought.clear(reason)
        farm.hud.hideBars()
        farm.hud.restoreAll(reason)
        worksites.cleanup(reason)
        settings = candidate
        rebuild(snapshot)
        worksites.activateLoadedState()
        startTasks()
    }

    fun onBreakLowest(event: BlockBreakEvent) = farm.events.onBreakLowest(event)
    fun onBreakHigh(event: BlockBreakEvent) = worksiteEvents.onBreakHigh(event)
    fun onBreakMonitor(event: BlockBreakEvent) = worksiteEvents.onBreakMonitor(event)
    fun onBlockDrop(event: BlockDropItemEvent) = farm.events.onBlockDrop(event)
    fun onInteractLowest(event: PlayerInteractEvent) = farm.events.onInteractLowest(event)
    fun onInteract(event: PlayerInteractEvent) = worksiteEvents.onInteract(event)
    fun onBlockFromTo(event: org.bukkit.event.block.BlockFromToEvent) = farm.events.onBlockFromTo(event)
    fun onMove(event: PlayerMoveEvent) = worksiteEvents.onMove(event)
    fun onTeleport(event: PlayerTeleportEvent) {
        worksiteEvents.onMove(event)
        worksiteEvents.release(event.player, WorksitePlayerReleaseReason.TELEPORT_OUT)
    }
    fun onPortal(event: PlayerPortalEvent) {
        worksiteEvents.onMove(event)
        worksiteEvents.release(event.player, WorksitePlayerReleaseReason.PORTAL_OUT)
    }
    fun onQuit(player: Player) {
        worksiteEvents.release(player, WorksitePlayerReleaseReason.QUIT)
    }
    fun onInteractEntityLowest(event: PlayerInteractEntityEvent) = farm.events.onInteractEntityLowest(event)
    fun onInteractEntity(event: PlayerInteractEntityEvent) = farm.events.onInteractEntity(event)
    fun onVehicleEnter(event: VehicleEnterEvent) = farm.events.onVehicleEnter(event)
    fun onEntityDamage(event: EntityDamageEvent) = farm.events.onEntityDamage(event)
    fun onProjectileHit(event: ProjectileHitEvent) = farm.events.onProjectileHit(event)
    fun onMoistureChange(event: MoistureChangeEvent) = farm.events.onMoistureChange(event)

    fun toggleAdminEdit(player: Player): Boolean? = farm.worldAdmin.toggleEdit(player)

    fun toggleAdminInspect(player: Player): Boolean = farm.worldAdmin.toggleInspect(player)

    fun adminStartFarmBlockReset(player: Player, zoneId: String): Boolean =
        farm.worldAdmin.startBlockReset(player, zoneId)

    fun adminStartFarmRoute(player: Player, zoneId: String, routeName: String = "main"): Boolean =
        farm.routeAdmin.start(player, zoneId, routeName)

    fun adminFinishFarmRoute(player: Player): Boolean = farm.routeAdmin.finish(player)

    fun adminCancelFarmRoute(player: Player): Boolean = farm.routeAdmin.cancel(player)

    fun adminClearFarmRoute(player: Player, zoneId: String, routeName: String = "main"): Boolean =
        farm.routeAdmin.clear(player, zoneId, routeName)

    fun adminFarmRouteStatus(player: Player, zoneId: String, routeName: String = "main"): Boolean =
        farm.routeAdmin.status(player, zoneId, routeName)

    fun adminFarmRouteNames(zoneId: String): List<String> = farm.routeAdmin.names(zoneId)

    fun adminFarmBlockResetStatus(player: Player, zoneId: String): Boolean =
        farm.worldAdmin.blockResetStatus(player, zoneId)

    fun adminSaveFarmBackup(player: Player, zoneId: String): Boolean =
        farm.worldAdmin.saveBackup(player, zoneId)

    fun adminRestoreFarmBackup(player: Player, zoneId: String, backupId: String): Boolean =
        farm.worldAdmin.restoreBackup(player, zoneId, backupId)

    fun adminListFarmBackups(player: Player, zoneId: String): Boolean =
        farm.worldAdmin.listBackups(player, zoneId)

    fun adminFarmBackupStatus(player: Player, zoneId: String): Boolean =
        farm.worldAdmin.backupStatus(player, zoneId)

    fun adminUnmanageSelection(player: Player, zoneId: String): Boolean =
        farm.worldAdmin.unmanageSelection(player, zoneId)

    fun farmZoneIds(): List<String> = farm.runtimes.snapshot().map { it.settings.id }

    fun farmOrderIds(zoneId: String): List<String> = farm.runtimes.byId(zoneId)
        ?.orderList
        ?.map(FarmOrder::id)
        .orEmpty()

    fun adminFarmPoints(zoneId: String): Map<FarmPointKind, FarmPointPosition>? = farm.pointAdmin.points(zoneId)

    fun adminOverriddenFarmPoints(zoneId: String): Set<FarmPointKind>? = farm.pointAdmin.overridden(zoneId)

    fun adminSetFarmPoint(player: Player, zoneId: String, kind: FarmPointKind): Boolean =
        farm.pointAdmin.set(player, zoneId, kind)

    fun adminClearFarmPoint(player: Player, zoneId: String, kind: FarmPointKind): Boolean =
        farm.pointAdmin.clear(player, zoneId, kind)

    fun adminSetFarmStage(player: Player, zoneId: String, stage: String): Boolean =
        farm.gameplayAdmin.setStage(player, zoneId, stage)

    fun adminStopFarmOrderCycle(player: Player, zoneId: String): Boolean =
        farm.gameplayAdmin.stopCycle(player, zoneId)

    fun adminStartFarmOrderCycle(player: Player, zoneId: String): Boolean =
        farm.gameplayAdmin.startCycle(player, zoneId)

    fun adminAdvanceFarm(player: Player, zoneId: String): Boolean =
        farm.gameplayAdmin.advance(player, zoneId)

    fun adminDebugFarmStatus(player: Player, zoneId: String): Boolean =
        farm.gameplayAdmin.status(player, zoneId)

    fun adminSetFarmContract(player: Player, zoneId: String, orderId: String): Boolean =
        farm.gameplayAdmin.setContract(player, zoneId, orderId)

    fun adminGiveFarmSupply(player: Player, zoneId: String, rawKind: String): Boolean =
        farm.gameplayAdmin.giveSupply(player, zoneId, rawKind)

    fun adminShowFarmGuidance(player: Player, zoneId: String): Boolean =
        farm.gameplayAdmin.showGuidance(player, zoneId)

    fun onDrop(event: PlayerDropItemEvent) {
        worksiteEvents.onDrop(event)
        farm.events.onDrop(event)
    }
    fun onDeath(event: PlayerDeathEvent) {
        farm.events.onDeath(event)
        worksiteEvents.onDeath(event.entity, event.drops)
    }
    fun onInventoryClick(event: InventoryClickEvent) {
        worksiteEvents.onInventoryClick(event)
        farm.events.onInventoryClick(event)
    }
    fun onInventoryDrag(event: InventoryDragEvent) {
        worksiteEvents.onInventoryDrag(event)
        farm.events.onInventoryDrag(event)
    }
    fun onEntityDeath(event: EntityDeathEvent) = farm.events.onEntityDeath(event)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) = farm.events.onEntityChangeBlock(event)
    fun onBlockFade(event: BlockFadeEvent) = farm.events.onBlockFade(event)
    fun onBlockBurn(event: BlockBurnEvent) = farm.events.onBlockBurn(event)
    fun onBlockIgnite(event: BlockIgniteEvent) = farm.events.onBlockIgnite(event)
    fun onBlockSpread(event: BlockSpreadEvent) = farm.events.onBlockSpread(event)
    fun onBlockGrow(event: BlockGrowEvent) = farm.events.onBlockGrow(event)
    fun onBlockPlace(event: BlockPlaceEvent) = farm.events.onBlockPlace(event)

    fun statuses(): List<ActivityStatus> = worksites.statuses()

    fun playerStats(playerId: UUID): PlayerActivityStats = stats.player(playerId)

    fun leaderboard(kind: ActivityKind, limit: Int = 10): List<Pair<UUID, Long>> = stats.leaderboard(kind, limit)

    fun leaderboardRank(playerId: UUID): Int? = stats.farmRank(playerId)

    fun weeklyLeaderboard(kind: ActivityKind, limit: Int = 10): List<Pair<UUID, Long>> =
        stats.weeklyLeaderboard(kind, limit)

    fun weeklyLeaderboardRank(playerId: UUID): Int? = stats.farmWeeklyRank(playerId)

    fun weeklyContribution(playerId: UUID, kind: ActivityKind): Long = stats.weeklyContribution(playerId, kind)

    fun farmScoreboardActive(playerId: UUID): Boolean = farm.hud.active(playerId)

    fun farmScoreboardTitle(playerId: UUID): String = farm.hud.title(playerId)

    fun farmScoreboardLine(playerId: UUID, line: Int): String = farm.hud.line(playerId, line)

    fun onChunkLoad(chunk: org.bukkit.Chunk) = worksites.reconcileChunk(chunk)


    fun canNavigate(kind: ActivityKind): Boolean = travelService.canNavigate(kind)

    fun travel(player: Player, kind: ActivityKind) = travelService.travel(player, kind)

    fun onJoin(player: Player) {
        farm.moles.recoverPlayer(player)
        worksiteEvents.onJoin(player)
        farm.supplies.removeServiceItems(player, reason = "player_join")
        taskSupervisor.runLater(1L) {
            if (isOperational() && player.isOnline) {
                farm.rewards.deliverPending(player)
                farm.hud.syncMusic(player, farm.module.hudRuntime(player), clock())
            }
        }
        travelService.claimJoin(player)
    }

    fun workday(): WorkdayState? = network.workday()

    fun isAvailable(kind: ActivityKind): Boolean = travelService.isAvailable(kind)

    fun canAccess(player: Player, kind: ActivityKind): Boolean = travelService.canAccess(player, kind)

    private fun rebuild(persisted: ArcFarmsState) {
        farm.module.rebuild(persisted)
        lumbermillModule.rebuild(
            settings.lumbermills, persisted.lumbermills, settings.completedCooldownSeconds * 1000L,
        )
        mineController.rebuild(
            settings.mines,
            persisted.mines,
            settings.completedCooldownSeconds * 1000L,
        )
    }

    private fun startTasks() {
        taskSupervisor.runTimer(1L, 1L) { runGuarded("farm_block_restores", farm.module::processRestores) }
        taskSupervisor.runTimer(1L, 1L) {
            val tick = ++seederVisualTick
            farm.module.updateSeeder(tick)
        }
        taskSupervisor.runTimer(20L, 20L) { runGuarded("tick", ::tick) }
        taskSupervisor.runTimer(10L, 10L) { runGuarded("guidance_particles", ::emitGuidanceParticles) }
        taskSupervisor.runTimer(5L, 5L) {
            farm.module.updateAmbient()
        }
        taskSupervisor.runTimer(1L, 1L) { farm.module.updatePlayerTimes() }
        taskSupervisor.runTimer(1L, 1L) { runGuarded("carried_displays", farm.module::updateCarriedDisplays) }
        taskSupervisor.runTimer(
            settings.saveSeconds * 20L,
            settings.saveSeconds * 20L,
        ) { runGuarded("periodic_save") { persistAsync() } }
    }

    private fun stopTasks() {
        taskSupervisor.cancelAll()
    }

    private fun tick() {
        val now = clock()
        Bukkit.getOnlinePlayers().forEach { player -> farm.hud.syncMusic(player, farm.module.hudRuntime(player), now) }
        worksites.tick(now)
        runGuarded("player_guidance", ::updatePlayerGuidance)
    }

    private inline fun runGuarded(scope: String, action: () -> Unit) {
        try {
            action()
        } catch (failure: Exception) {
            if (allowInteraction("runtime-error:$scope", TimeUnit.MINUTES.toMillis(1))) {
                plugin.logger.log(Level.SEVERE, "ArcFarms runtime task failed in $scope; other zones remain active", failure)
            }
        }
    }

    private fun updatePlayerGuidance() {
        val expectedBars = worksites.updateGuidance()
        worksitePort.reconcileBars(expectedBars)
    }


    private fun emitGuidanceParticles() {
        worksites.emitGuidance()
    }

    private fun farmAt(location: Location): FarmRuntime? = farm.runtimes.at(location)

    private fun hasAccess(player: Player, permission: String): Boolean =
        player.hasPermission(permission) || player.hasPermission("arcfarms.admin")

    private fun isAdminEditing(player: Player): Boolean = farm.worldAdmin.isEditing(player)

    private fun allowInteraction(key: String, cooldownMillis: Long): Boolean {
        val now = clock()
        val previous = interactionCooldowns[key] ?: 0
        if (now >= previous && now - previous < cooldownMillis) return false
        interactionCooldowns[key] = now
        if (interactionCooldowns.size > MAX_INTERACTION_COOLDOWNS) {
            interactionCooldowns.entries.removeIf { timestamp ->
                now >= timestamp.value && now - timestamp.value > TimeUnit.HOURS.toMillis(1)
            }
            val iterator = interactionCooldowns.entries.iterator()
            while (interactionCooldowns.size > MAX_INTERACTION_COOLDOWNS && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        return true
    }

    private fun snapshotState(): ArcFarmsState {
        val rewards = farm.rewards.snapshot()
        return ArcFarmsState(
            farms = farm.runtimes.snapshot().associate { it.settings.id to it.state },
            pausedFarmZones = farm.orderCycle.snapshot(),
            lumbermills = lumbermillModule.states(),
            mines = mineController.states(),
            stats = stats.snapshot(),
            pendingFarmRewards = rewards.pending,
            claimedFarmRewardSequences = rewards.claimed,
            farmPerks = farm.perks.snapshot(),
        )
    }

    private fun persistAsync(): CompletableFuture<Unit> {
        if (persistenceSuspended) {
            persistenceRequestedWhileSuspended = true
            return CompletableFuture.failedFuture(IllegalStateException("ArcFarms persistence is suspended during reload"))
        }
        val startedAt = System.nanoTime()
        return runCatching { stateRepository.saveAsync(snapshotState()) }
            .onSuccess { operation ->
                operation.whenComplete { _, failure ->
                    if (failure != null) plugin.logger.log(Level.SEVERE, "Could not persist ArcFarms state", failure)
                    val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                    if (failure != null || elapsedMillis >= SLOW_PERSISTENCE_MILLIS) {
                        val health = stateRepository.health()
                        debug.event(
                            "state_persist",
                            "outcome" to if (failure == null) "slow" else "failed",
                            "elapsed_ms" to elapsedMillis,
                            "pending" to health.pendingRequests,
                            "max_ms" to health.maxDurationMillis,
                            "failures" to health.failedRequests,
                        )
                    }
                }
            }
            .getOrElse { failure ->
                plugin.logger.log(Level.SEVERE, "Could not schedule ArcFarms state persistence", failure)
                CompletableFuture.failedFuture(failure)
            }
    }

    private fun persistBlocking() = stateRepository.saveBlocking(snapshotState())

    override fun close() {
        if (closed) return
        closed = true
        started = false
        val failures = mutableListOf<Throwable>()
        runCatching(farm.rewards::prepareForLifecycleBoundary).exceptionOrNull()?.let(failures::add)
        runCatching(::stopTasks).exceptionOrNull()?.let(failures::add)
        runCatching { farm.hud.stopAllMusic("plugin_close") }.exceptionOrNull()?.let(failures::add)
        runCatching { farm.drought.clear("plugin_close") }.exceptionOrNull()?.let(failures::add)
        runCatching(farm.hud::hideBars).exceptionOrNull()?.let(failures::add)
        runCatching { farm.hud.restoreAll("plugin_close") }.exceptionOrNull()?.let(failures::add)
        runCatching { worksites.cleanup("plugin_close") }.exceptionOrNull()?.let(failures::add)
        runCatching(farm.worldAdmin::close).exceptionOrNull()?.let(failures::add)
        runCatching(farm.blockRegistry::close).exceptionOrNull()?.let(failures::add)
        if (stateSafeToPersist) runCatching(::persistBlocking).exceptionOrNull()?.let(failures::add)
        if (failures.isNotEmpty()) {
            throw IllegalStateException("ArcFarms service shutdown completed with ${failures.size} failure(s)", failures.first()).also {
                failures.drop(1).forEach(it::addSuppressed)
            }
        }
    }

    companion object {
        private const val MAX_INTERACTION_COOLDOWNS = 10_000
        private const val SLOW_PERSISTENCE_MILLIS = 50L
    }
}
