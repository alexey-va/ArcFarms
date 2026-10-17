package ru.ruscrafting.farms.paper

import com.google.gson.Gson
import net.milkbowl.vault.economy.Economy
import org.bukkit.generator.ChunkGenerator
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.api.ArcRedisService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import ru.ruscrafting.farms.persistence.MineLocationRepository
import ru.ruscrafting.farms.paper.mine.lift.MineLiftRuntimeManager
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.paper.api.ArcSidebarPriorities
import ru.arc.paper.api.ArcSidebarService
import ru.arc.paper.redis.PaperRedisClient
import ru.arc.redis.RedisModuleConfig
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.ArcFarmsRedisBootstrap
import ru.ruscrafting.farms.config.FarmScoreboardProvider
import ru.ruscrafting.farms.domain.ArcFarmsState
import ru.ruscrafting.farms.domain.FarmLocationOverrides
import ru.ruscrafting.farms.domain.FarmRouteState
import ru.ruscrafting.farms.domain.FixedFarmCropJournalState
import ru.ruscrafting.farms.domain.MineBlockJournalState
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseSnapshot
import ru.ruscrafting.farms.network.ArcFarmsNetworkRepository
import ru.ruscrafting.farms.network.NoOpActivityNetworkGateway
import ru.ruscrafting.farms.persistence.ArcFarmsStateRepository
import ru.ruscrafting.farms.persistence.FarmLocationRepository
import ru.ruscrafting.farms.persistence.FarmRouteRepository
import ru.ruscrafting.farms.persistence.FixedFarmCropJournal
import ru.ruscrafting.farms.persistence.MineBlockJournal
import ru.ruscrafting.farms.paper.mine.expedition.MineExpeditionWorldGenerator
import java.nio.file.Files
import java.util.logging.Level

/** Open so the pinned MockBukkit runtime can create its isolated lifecycle-test subclass. */
open class ArcFarmsPlugin : JavaPlugin() {
    @Volatile
    private lateinit var settings: ArcFarmsConfig
    private lateinit var locale: ArcFarmsLocale
    private var service: ArcFarmsService? = null
    private var stateRepository: ArcFarmsStateRepository? = null
    private var mineJournal: MineBlockJournal? = null
    private var fixedCropJournal: FixedFarmCropJournal? = null
    private var farmLocationRepository: FarmLocationRepository? = null
    private var farmRouteRepository: FarmRouteRepository? = null
    private var redis: PaperRedisClient? = null
    private var redisRuntimeSettings: RedisRuntimeSettings? = null
    private var network: ArcFarmsNetworkService? = null
    private var transfer: BungeeBackendTransfer? = null
    private var menu: ArcFarmsMenu? = null
    private var placeholderExpansion: ArcFarmsPlaceholderExpansion? = null
    @Volatile private var pluginRuntime: PaperPluginRuntime? = null
    private lateinit var io: BoundedAsyncExecutor
    private lateinit var lifecycleTasks: LifecycleTaskScope
    private var reloading = false
    private var shutdownDrain = CompletableFuture.completedFuture(Unit)
    private var predecessorDrain = CompletableFuture.completedFuture(Unit)
    private var enableScope: ru.arc.core.LifecycleTaskScope? = null
    private var generationOpen = false
    private var generationStarted = false

    private val preparedResources = ConcurrentHashMap.newKeySet<Prepared>()
    private val nestedStorage = ConcurrentHashMap.newKeySet<() -> CompletableFuture<Unit>>()

    override fun getDefaultWorldGenerator(worldName: String, id: String?): ChunkGenerator? =
        if (worldName == MineExpeditionWorldGenerator.WORLD_NAME) MineExpeditionWorldGenerator() else null

    override fun onEnable() {
        check(!generationOpen) { "Plugin generation is already open" }
        generationOpen = true
        generationStarted = false
        predecessorDrain = shutdownDrain
        shutdownDrain = CompletableFuture()
        val scope = ru.arc.core.LifecycleTaskScope(ru.arc.core.BukkitTaskScheduler(this))
        enableScope = scope
        val token = scope.token()
        predecessorDrain.whenComplete { _, failure ->
            scope.callSync(token) {
                if (enableScope !== scope || !generationOpen) return@callSync
                if (failure != null) {
                    logger.log(Level.SEVERE, "Previous plugin generation failed to drain; startup remains closed", failure)
                    server.pluginManager.disablePlugin(this)
                } else {
                    generationStarted = true
                    startGeneration()
                }
            }.whenComplete { _, startFailure ->
                if (startFailure != null) scope.callSync(token) {
                    if (enableScope === scope && generationOpen) {
                        logger.log(Level.SEVERE, "Plugin generation startup failed closed", startFailure)
                        server.pluginManager.disablePlugin(this)
                    }
                }
            }
        }
    }

    /** Cold preparation starts only after the exact predecessor's terminal writer/lease drain. */
    protected open fun startGeneration() {
        reloading = false
        ArcProductTelemetryBridge.install()
        PaperArcRuntime.installScheduling(this)
        val lifecycle = PaperPluginRuntime(this, "arc-farms").also {
            pluginRuntime = it
            it.start("version" to pluginMeta.version)
        }
        io = BoundedAsyncExecutor("arc-farms-prepare")
        lifecycleTasks = lifecycle.own(LifecycleTaskScope(Tasks.scheduler))
        val token = lifecycleTasks.token()
        val root = dataFolder.toPath()
        val redisService = server.servicesManager.load(ArcRedisService::class.java)
        val ownerName = name
        io.submit { prepare(root, redisService, ownerName) }.whenComplete { prepared, failure ->
            if (pluginRuntime !== lifecycle) {
                prepared?.let(::retirePrepared)
            } else lifecycleTasks.callSync(token) {
                if (failure != null) failStartup(lifecycle, failure)
                else activate(lifecycle, prepared)
            }.whenComplete { _, activationFailure ->
                if (activationFailure != null) prepared?.let(::retirePrepared)
            }
        }
    }

    private fun prepare(root: java.nio.file.Path, redisService: ArcRedisService?, ownerName: String): Prepared {
        val resources = mutableListOf<AutoCloseable>()
        try {
            saveResourceIfMissing("config.yml")
            listOf("lang/ru.yml", "lang/en.yml", "modules/redis.yml", "modules/mine-lift.yml").forEach(::saveResourceIfMissing)
            val config = ArcFarmsConfig.load(root)
            ArcFarmsLocale.synchronizeFiles(root)
            ArcFarmsLocale.validateFiles(root, config)
            require(config.enabled) { "ArcFarms is disabled in config.yml" }
            val translated = ArcFarmsLocale(root) { settings }
            val redisConfig = if (config.network.enabled) ArcFarmsRedisBootstrap.load(root, config) else null
            val manager = redisConfig?.let {
                PaperRedisClient.open(requireNotNull(redisService) { "ARC shared Redis service is unavailable" },
                    ownerName, it.connection(), config.serverId).also { redis -> resources += redis; redis.retainDuringShutdown(); redis.init() }
            }
            val state = ArcFarmsStateRepository(root).also { resources += it; it.load() }
            val mine = MineBlockJournal(root).also { resources += it }
            val crops = FixedFarmCropJournal(root).also { resources += it }
            val locations = FarmLocationRepository(root).also { resources += it; it.load() }
            val mineLocations = MineLocationRepository(root).also { resources += it; it.load() }
            val routes = FarmRouteRepository(root).also { resources += it; it.load() }
            val lifts = MineLiftRuntimeManager.prepare(root, io) { id, problem ->
                logger.log(Level.SEVERE, "Mine lift $id preparation rejected; lift remains closed", problem)
            }
            return Prepared(config, translated, ArcFarmsMenuPlatform.prepare(root), redisConfig, manager,
                state, mine, crops, locations, mineLocations, routes, lifts, resources).also { preparedResources += it }
        } catch (failure: Throwable) {
            resources.asReversed().forEach { resource -> runCatching { closeStorageResource(resource) }.exceptionOrNull()?.let(failure::addSuppressed) }
            throw failure
        }
    }

    private data class Prepared(
        val settings: ArcFarmsConfig, val locale: ArcFarmsLocale, val menus: ArcFarmsMenuPlatform.Prepared,
        val redisConfig: RedisModuleConfig?, val redis: PaperRedisClient?,
        val state: ArcFarmsStateRepository, val mine: MineBlockJournal, val crops: FixedFarmCropJournal,
        val locations: FarmLocationRepository, val mineLocations: MineLocationRepository, val routes: FarmRouteRepository,
        val lifts: MineLiftRuntimeManager.Prepared, val resources: List<AutoCloseable>,
    ) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            var failure: Throwable? = null
            resources.asReversed().forEach { resource ->
                runCatching {
                    closeStorageResource(resource)
                }.exceptionOrNull()?.let { problem ->
                    if (failure == null) failure = problem else failure.addSuppressed(problem)
                }
            }
            failure?.let { throw it }
        }
    }

    private fun retirePrepared(prepared: Prepared) {
        io.submit {
            try { prepared.close() } finally { preparedResources.remove(prepared) }
        }
        // The terminal finalizer owns any prepare finishing after admission closes.
    }

    private fun activate(lifecycle: PaperPluginRuntime, prepared: Prepared) {
        try {
            settings = prepared.settings
            locale = prepared.locale
            val debug = ArcFarmsDebug({ settings.debug.enabled }, logger::info)
            val networkGateway = if (settings.network.enabled) {
                val redisConfig = requireNotNull(prepared.redisConfig)
                redisRuntimeSettings = RedisRuntimeSettings.from(redisConfig)
                val manager = requireNotNull(prepared.redis)
                lifecycle.own(AutoCloseable { io.submit { manager.close() } })
                redis = manager
                val networkService = ArcFarmsNetworkService(
                    plugin = this,
                    settings = { settings },
                    locale = locale,
                    repository = ArcFarmsNetworkRepository(manager, Gson()),
                    redis = manager,
                    debug = debug,
                )
                lifecycle.own(networkService)
                networkService.start()
                network = networkService
                networkService
            } else {
                NoOpActivityNetworkGateway
            }
            val stateStore = lifecycle.own(prepared.state).also { stateRepository = it }
            val mineStore = lifecycle.own(prepared.mine).also { mineJournal = it }
            val fixedCropStore = lifecycle.own(prepared.crops).also { fixedCropJournal = it }
            val locationStore = lifecycle.own(prepared.locations).also { farmLocationRepository = it }
            val mineLocationStore = lifecycle.own(prepared.mineLocations)
            val routeStore = lifecycle.own(prepared.routes).also { farmRouteRepository = it }
            val regionGateway = if (settings.requiresWorldGuard) {
                require(server.pluginManager.isPluginEnabled("WorldGuard")) {
                    "WorldGuard is required because this node configures named regions"
                }
                WorldGuardRegionGateway()
            } else {
                CuboidRegionGateway()
            }
            require(
                !settings.farmScoreboard.enabled || settings.farmScoreboard.provider != FarmScoreboardProvider.TAB ||
                    server.pluginManager.isPluginEnabled("PlaceholderAPI"),
            ) { "PlaceholderAPI is required when ui.farm-scoreboard.provider is TAB" }
            val backendTransfer = lifecycle.own(BungeeBackendTransfer(this) { failure ->
                logger.log(Level.WARNING, "ArcFarms backend transfer send failed", failure)
            }).also { transfer = it }
            val menuPlatform = ArcFarmsMenuPlatform(this, prepared = prepared.menus)
            val sidebar = requireNotNull(server.servicesManager.load(ArcSidebarService::class.java)) {
                "ARC shared sidebar service is unavailable"
            }.register(
                this,
                "worksite",
                ArcSidebarPriorities.ACTIVITY,
            ).also(lifecycle::own)
            val mineLift = runCatching {
                lifecycle.own(ru.ruscrafting.farms.paper.mine.lift.MineLiftRuntimeManager(this, locale, prepared.lifts, capture = { current, step ->
                    service?.captureNative(current, step) ?: CompletableFuture.failedFuture(IllegalStateException("ArcFarms is unavailable"))
                }))
            }.getOrElse { failure ->
                logger.log(Level.SEVERE, "Mine lift unavailable; inspect its recovery journal", failure)
                null
            }
            val activeService = lifecycle.own(ArcFarmsService(
                plugin = this,
                initialSettings = settings,
                locale = locale,
                stateRepository = stateStore,
                mineJournal = mineStore,
                fixedCropJournal = fixedCropStore,
                farmLocationRepository = locationStore,
                mineLocationRepository = mineLocationStore,
                farmRouteRepository = routeStore,
                network = networkGateway,
                transfer = backendTransfer,
                debug = debug,
                regionGateway = regionGateway,
                economy = resolveEconomy(settings),
                menus = menuPlatform,
                mineLift = mineLift,
                sidebar = sidebar,
                calculate = { action -> io.submit(action) },
                retainStorage = { close -> nestedStorage += close },
            ))
            service = activeService
            activeService.start()
            if (server.pluginManager.isPluginEnabled("PlaceholderAPI")) {
                placeholderExpansion = ArcFarmsPlaceholderExpansion(pluginMeta.version, activeService).also {
                    require(it.register()) { "Could not register the PlaceholderAPI expansion" }
                    lifecycle.own(AutoCloseable { it.unregister() })
                }
            } else {
                logger.warning("PlaceholderAPI is unavailable; ArcFarms leaderboard placeholders are disabled")
            }
            val activeMenu = ArcFarmsMenu(menuPlatform, activeService, locale) { settings }
            menu = activeMenu
            lifecycle.own(activeMenu)
            val command = ArcFarmsCommand(activeService, locale, activeMenu, ::reloadPlugin)
            requireNotNull(getCommand("arcfarms")).apply {
                setExecutor(command)
                tabCompleter = command
            }
            server.pluginManager.registerEvents(ArcFarmsListener(activeService, activeMenu) {
                mineLift?.ownsTeleport(it) == true
            }, this)
            mineLift?.start()
            lifecycle.registerHealth("runtime") {
                val serviceReady = activeService.isOperational()
                val redisReady = !settings.network.enabled || redis?.isConnected() == true
                RuntimeHealthContribution(
                    state = when {
                        !serviceReady -> RuntimeHealthState.DOWN
                        !redisReady -> RuntimeHealthState.DEGRADED
                        else -> RuntimeHealthState.UP
                    },
                    recoveryBacklog = mineStore.pendingRecordCount() + fixedCropStore.pendingRecordCount(),
                    schemas = mapOf(
                        "state" to ArcFarmsState.SCHEMA_VERSION,
                        "mine_journal" to MineBlockJournalState.SCHEMA_VERSION,
                        "fixed_crop_journal" to FixedFarmCropJournalState.SCHEMA_VERSION,
                        "farm_locations" to FarmLocationOverrides.SCHEMA_VERSION,
                        "farm_routes" to FarmRouteState.SCHEMA_VERSION,
                        "worksite_enterprise" to WorksiteEnterpriseSnapshot.SCHEMA_VERSION,
                    ),
                    dependencies = mapOf("redis" to redisReady, "service" to serviceReady),
                )
            }
            lifecycle.ready(
                "server" to settings.serverId,
                "network" to settings.network.enabled,
                "redis" to (redis?.isConnected() ?: false),
            )
            lifecycle.reportHealthEvery(HEALTH_REPORT_TICKS)
            logger.info(
                "ArcFarms enabled on ${settings.serverId}; network=${settings.network.enabled}; " +
                    "redisConnected=${redis?.isConnected() ?: false}",
            )
        } catch (failure: Throwable) {
            failStartup(lifecycle, failure)
        }
    }

    private fun failStartup(lifecycle: PaperPluginRuntime, failure: Throwable) {
        runCatching { lifecycle.health.markDown(); lifecycle.emitHealth() }
        logger.log(Level.SEVERE, "ArcFarms failed closed during startup", failure)
        server.pluginManager.disablePlugin(this)
    }

    override fun onDisable() {
        if (!generationOpen) return
        generationOpen = false
        enableScope?.close()
        enableScope = null
        val completion = shutdownDrain
        val previous = predecessorDrain
        val retiring = if (generationStarted) runCatching { closeGeneration() }.getOrElse { CompletableFuture.failedFuture(it) }
            else CompletableFuture.completedFuture(Unit)
        generationStarted = false
        CompletableFuture.allOf(previous, retiring).whenComplete { _, failure ->
            if (failure == null) completion.complete(Unit) else completion.completeExceptionally(failure)
        }
    }

    /** Native detach is synchronous; the returned completion owns every accepted terminal IO action. */
    protected open fun closeGeneration(): CompletableFuture<Unit> {
        val retiring = pluginRuntime
        pluginRuntime = null
        val nativeFailure = runCatching { retiring?.close() }.exceptionOrNull()
        nativeFailure?.let { logger.log(Level.SEVERE, "Could not close ArcFarms runtime", it) }
        pluginRuntime = null
        menu = null
        placeholderExpansion = null
        service = null
        transfer = null
        network = null
        redis = null
        redisRuntimeSettings = null
        mineJournal = null
        fixedCropJournal = null
        farmLocationRepository = null
        farmRouteRepository = null
        stateRepository = null
        val drain = if (::io.isInitialized) io.closeAsync {
            var failure: Throwable? = null
            nestedStorage.forEach { close ->
                try { close().join() } catch (problem: Throwable) {
                    if (failure == null) failure = problem else failure.addSuppressed(problem)
                } finally { nestedStorage.remove(close) }
            }
            preparedResources.forEach { prepared ->
                try { prepared.close() } catch (problem: Throwable) {
                    if (failure == null) failure = problem else failure.addSuppressed(problem)
                } finally { preparedResources.remove(prepared) }
            }
            failure?.let { throw it }
        } else CompletableFuture.completedFuture(Unit)
        Tasks.reset()
        return drain.thenApply { nativeFailure?.let { throw it }; Unit }
    }

    private fun reloadPlugin(): CompletableFuture<Unit> {
        if (reloading) return CompletableFuture.failedFuture(IllegalStateException("ArcFarms reload is already in progress"))
        val lifecycle = pluginRuntime ?: return CompletableFuture.failedFuture(IllegalStateException("ArcFarms is unavailable"))
        val activeService = service ?: return CompletableFuture.failedFuture(IllegalStateException("ArcFarms is starting"))
        reloading = true
        val root = dataFolder.toPath()
        val previous = settings
        val previousLocale = locale.snapshot()
        val token = lifecycleTasks.token()
        val operation = io.submit {
            val candidate = ArcFarmsConfig.synchronize(root)
            ArcFarmsHotReloadPolicy.validate(previous, candidate)
            ArcFarmsLocale.synchronizeFiles(root)
            val translated = locale.prepareReload(candidate)
            val menus = ArcFarmsMenuPlatform.prepare(root)
            if (candidate.network.enabled) {
                require(RedisRuntimeSettings.from(ArcFarmsRedisBootstrap.loadFresh(root, candidate)) == redisRuntimeSettings) {
                    "modules/redis.yml connection settings require a full plugin restart"
                }
            }
            Triple(candidate, translated, menus)
        }.thenCompose { (candidate, translated, menus) ->
            lifecycleTasks.callSync(token) {
                check(pluginRuntime === lifecycle && service === activeService)
                require(!candidate.farmScoreboard.enabled || candidate.farmScoreboard.provider != FarmScoreboardProvider.TAB ||
                    server.pluginManager.isPluginEnabled("PlaceholderAPI")) {
                    "PlaceholderAPI is required when ui.farm-scoreboard.provider is TAB"
                }
                // Material/template registry checks remain native and precede runtime publication.
                ArcFarmsMenuPlatform.parse(menus.config)
                activeService.reload(candidate) { active -> settings = active }
                    .thenCompose {
                        lifecycleTasks.callSync(token) {
                            locale.publish(translated)
                            activeService.refreshPresentation()
                            menu?.publishReload(menus)
                            server.onlinePlayers.forEach { player ->
                                val holder = player.openInventory.topInventory.holder as? ArcFarmsReloadableInventory ?: return@forEach
                                runCatching { holder.refresh(player) }.onFailure { failure ->
                                    logger.log(Level.WARNING, "Could not redraw ArcFarms menu for ${player.name} after reload", failure)
                                }
                            }
                            Unit
                        }
                    }
            }.thenCompose { it }
        }
        return operation.handle { _, failure -> failure }.thenCompose { failure ->
            lifecycleTasks.callSync(token) {
                reloading = false
                if (failure != null) {
                    val cause = (failure as? java.util.concurrent.CompletionException)?.cause ?: failure
                    if (cause !is ArcFarmsAmbiguousLiveReloadException) {
                        settings = previous
                        locale.publish(previousLocale)
                        runCatching(activeService::refreshPresentation).exceptionOrNull()?.let(cause::addSuppressed)
                    }
                    if (cause is ArcFarmsAmbiguousLiveReloadException || !activeService.isOperational()) {
                        logger.log(Level.SEVERE, "ArcFarms became inoperable during reload and will be disabled", cause)
                        server.pluginManager.disablePlugin(this)
                    }
                    throw cause
                }
                Unit
            }
        }
    }

    private fun resolveEconomy(settings: ArcFarmsConfig): FarmEconomyGateway {
        val provider = if (server.pluginManager.isPluginEnabled("Vault")) {
            server.servicesManager.getRegistration(Economy::class.java)?.provider
        } else null
        if (provider != null) return VaultFarmEconomyGateway(provider)
        require(
            settings.farms.none { it.rewards.requiresEconomy } &&
                settings.enterprises.values.none { it.mode == ru.ruscrafting.farms.config.WorksiteEnterpriseMode.LIVE },
        ) {
            "Vault and an economy provider are required because money rewards or LIVE enterprise funding is configured"
        }
        return NoOpFarmEconomyGateway
    }

    private fun saveResourceIfMissing(path: String) {
        if (!Files.isRegularFile(dataFolder.toPath().resolve(path))) saveResource(path, false)
    }

    private companion object {
        const val HEALTH_REPORT_TICKS = 1_200L

        /** Worker-only: the predecessor gate includes each independent store's accepted writer drain. */
        fun closeStorageResource(resource: AutoCloseable) {
            when (resource) {
                is ArcFarmsStateRepository -> resource.closeAsync().join()
                is MineBlockJournal -> resource.closeAsync().join()
                is FixedFarmCropJournal -> resource.closeAsync().join()
                is FarmLocationRepository -> resource.closeAsync().join()
                is MineLocationRepository -> resource.closeAsync().join()
                is FarmRouteRepository -> resource.closeAsync().join()
                else -> resource.close()
            }
        }
    }
}

private data class RedisRuntimeSettings(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val serverName: String,
    val mainServer: Boolean,
) {
    companion object {
        fun from(config: RedisModuleConfig) = RedisRuntimeSettings(
            enabled = config.enabled,
            host = config.host,
            port = config.port,
            username = config.username,
            password = config.password,
            serverName = config.serverName,
            mainServer = config.mainServer,
        )
    }
}
