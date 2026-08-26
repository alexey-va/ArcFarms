package ru.ruscrafting.farms.paper

import com.google.gson.Gson
import net.milkbowl.vault.economy.Economy
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory
import ru.arc.config.ConfigManager
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.redis.RedisManager
import ru.arc.redis.ServerIdentity
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.ArcFarmsRedisBootstrap
import ru.ruscrafting.farms.config.FarmScoreboardProvider
import ru.ruscrafting.farms.domain.ArcFarmsState
import ru.ruscrafting.farms.domain.FarmLocationOverrides
import ru.ruscrafting.farms.domain.FixedFarmCropJournalState
import ru.ruscrafting.farms.domain.MineBlockJournalState
import ru.ruscrafting.farms.network.ArcFarmsNetworkRepository
import ru.ruscrafting.farms.network.NoOpActivityNetworkGateway
import ru.ruscrafting.farms.persistence.ArcFarmsStateRepository
import ru.ruscrafting.farms.persistence.FarmLocationRepository
import ru.ruscrafting.farms.persistence.FixedFarmCropJournal
import ru.ruscrafting.farms.persistence.MineBlockJournal
import java.nio.file.Files
import java.util.logging.Level

class ArcFarmsPlugin : JavaPlugin() {
    @Volatile
    private lateinit var settings: ArcFarmsConfig
    private lateinit var locale: ArcFarmsLocale
    private var service: ArcFarmsService? = null
    private var stateRepository: ArcFarmsStateRepository? = null
    private var mineJournal: MineBlockJournal? = null
    private var fixedCropJournal: FixedFarmCropJournal? = null
    private var farmLocationRepository: FarmLocationRepository? = null
    private var redis: RedisManager? = null
    private var network: ArcFarmsNetworkService? = null
    private var transfer: BungeeBackendTransfer? = null
    private var placeholderExpansion: ArcFarmsPlaceholderExpansion? = null
    private var pluginRuntime: PaperPluginRuntime? = null

    override fun onEnable() {
        saveDefaultConfig()
        saveResourceIfMissing("lang/ru.yml")
        saveResourceIfMissing("lang/en.yml")
        saveResourceIfMissing("modules/redis.yml")
        PaperArcRuntime.installScheduling(this)
        val lifecycle = PaperPluginRuntime(this, "arc-farms").also {
            pluginRuntime = it
            it.start("version" to pluginMeta.version)
        }
        try {
            val dataRoot = dataFolder.toPath()
            settings = ArcFarmsConfig.load(dataRoot)
            ArcFarmsLocale.validateFiles(dataRoot, settings)
            require(settings.enabled) { "ArcFarms is disabled in config.yml" }
            locale = ArcFarmsLocale(dataRoot) { settings }
            val debug = ArcFarmsDebug({ settings.debug.enabled }, logger::info)
            val networkGateway = if (settings.network.enabled) {
                val redisConfig = ArcFarmsRedisBootstrap.load(dataRoot, settings)
                val manager = RedisManager(
                    redisConfig.connection(),
                    ServerIdentity { settings.serverId },
                    LoggerFactory.getLogger("ArcFarms.Redis"),
                )
                lifecycle.own(manager)
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
                manager.init()
                networkService
            } else {
                NoOpActivityNetworkGateway
            }
            val stateStore = lifecycle.own(ArcFarmsStateRepository(dataRoot)).also { stateRepository = it }
            val mineStore = lifecycle.own(MineBlockJournal(dataRoot)).also { mineJournal = it }
            val fixedCropStore = lifecycle.own(FixedFarmCropJournal(dataRoot)).also { fixedCropJournal = it }
            val locationStore = lifecycle.own(FarmLocationRepository(dataRoot)).also { farmLocationRepository = it }
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
            val activeService = lifecycle.own(ArcFarmsService(
                plugin = this,
                initialSettings = settings,
                locale = locale,
                stateRepository = stateStore,
                mineJournal = mineStore,
                fixedCropJournal = fixedCropStore,
                farmLocationRepository = locationStore,
                network = networkGateway,
                transfer = backendTransfer,
                debug = debug,
                regionGateway = regionGateway,
                economy = resolveEconomy(settings),
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
            val menu = ArcFarmsMenu(activeService, locale) { settings }
            val command = ArcFarmsCommand(activeService, locale, menu, ::reloadPlugin)
            requireNotNull(getCommand("arcfarms")).apply {
                setExecutor(command)
                tabCompleter = command
            }
            server.pluginManager.registerEvents(ArcFarmsListener(activeService, menu), this)
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
            runCatching { lifecycle.health.markDown(); lifecycle.emitHealth() }
            logger.log(Level.SEVERE, "ArcFarms failed closed during startup", failure)
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        runCatching { pluginRuntime?.close() }.onFailure { logger.log(Level.SEVERE, "Could not close ArcFarms runtime", it) }
        pluginRuntime = null
        placeholderExpansion = null
        service = null
        transfer = null
        network = null
        redis = null
        mineJournal = null
        fixedCropJournal = null
        farmLocationRepository = null
        stateRepository = null
        Tasks.reset()
    }

    private fun reloadPlugin(): Result<Unit> = runCatching {
        val dataRoot = dataFolder.toPath()
        val candidate = ArcFarmsConfig.inspect(dataRoot)
        require(candidate.enabled) { "ArcFarms cannot be disabled with reload" }
        require(candidate.serverId == settings.serverId) { "server-id requires a restart" }
        require(candidate.network.enabled == settings.network.enabled) { "network.enabled requires a restart" }
        ArcFarmsLocale.validateFiles(dataRoot, candidate)
        ConfigManager.reloadAll()
        val previous = settings
        try {
            requireNotNull(service).reload(candidate) { active -> settings = active }
        } catch (failure: Exception) {
            settings = previous
            if (service?.isOperational() != true) {
                logger.log(Level.SEVERE, "ArcFarms became inoperable during reload and will be disabled", failure)
                server.pluginManager.disablePlugin(this)
            }
            throw failure
        }
    }

    private fun resolveEconomy(settings: ArcFarmsConfig): FarmEconomyGateway {
        val provider = if (server.pluginManager.isPluginEnabled("Vault")) {
            server.servicesManager.getRegistration(Economy::class.java)?.provider
        } else null
        if (provider != null) return VaultFarmEconomyGateway(provider)
        require(settings.farms.none { it.rewards.requiresEconomy }) {
            "Vault and an economy provider are required because a farm money reward is configured"
        }
        return NoOpFarmEconomyGateway
    }

    private fun saveResourceIfMissing(path: String) {
        if (!Files.isRegularFile(dataFolder.toPath().resolve(path))) saveResource(path, false)
    }

    private companion object {
        const val HEALTH_REPORT_TICKS = 1_200L
    }
}
