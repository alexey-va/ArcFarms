package ru.ruscrafting.farms.paper

import com.google.gson.Gson
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory
import ru.arc.config.ConfigManager
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.redis.RedisManager
import ru.arc.redis.ServerIdentity
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.ArcFarmsRedisBootstrap
import ru.ruscrafting.farms.network.ArcFarmsNetworkRepository
import ru.ruscrafting.farms.network.NoOpActivityNetworkGateway
import ru.ruscrafting.farms.persistence.ArcFarmsStateRepository
import ru.ruscrafting.farms.persistence.FarmLocationRepository
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
    private var farmLocationRepository: FarmLocationRepository? = null
    private var redis: RedisManager? = null
    private var network: ArcFarmsNetworkService? = null
    private var placeholderExpansion: ArcFarmsPlaceholderExpansion? = null

    override fun onEnable() {
        saveDefaultConfig()
        saveResourceIfMissing("lang/ru.yml")
        saveResourceIfMissing("lang/en.yml")
        saveResourceIfMissing("modules/redis.yml")
        PaperArcRuntime.installScheduling(this)
        try {
            val dataRoot = dataFolder.toPath()
            settings = ArcFarmsConfig.load(dataRoot)
            ArcFarmsLocale.validateFiles(dataRoot, settings)
            require(settings.enabled) { "ArcFarms is disabled in config.yml" }
            locale = ArcFarmsLocale(dataRoot) { settings }
            val debug = ArcFarmsDebug({ settings.debug.enabled }, logger::info)
            server.messenger.registerOutgoingPluginChannel(this, BungeeBackendTransfer.CHANNEL)
            val networkGateway = if (settings.network.enabled) {
                val redisConfig = ArcFarmsRedisBootstrap.load(dataRoot, settings)
                val manager = RedisManager(
                    redisConfig.connection(),
                    ServerIdentity { settings.serverId },
                    LoggerFactory.getLogger("ArcFarms.Redis"),
                )
                redis = manager
                ArcFarmsNetworkService(
                    plugin = this,
                    settings = { settings },
                    locale = locale,
                    repository = ArcFarmsNetworkRepository(manager, Gson()),
                    redis = manager,
                    debug = debug,
                ).also {
                    it.start()
                    network = it
                    manager.init()
                }
            } else {
                NoOpActivityNetworkGateway
            }
            stateRepository = ArcFarmsStateRepository(dataRoot)
            mineJournal = MineBlockJournal(dataRoot)
            farmLocationRepository = FarmLocationRepository(dataRoot)
            val regionGateway = if (settings.requiresWorldGuard) {
                require(server.pluginManager.isPluginEnabled("WorldGuard")) {
                    "WorldGuard is required because this node configures named regions"
                }
                WorldGuardRegionGateway()
            } else {
                CuboidRegionGateway()
            }
            val activeService = ArcFarmsService(
                plugin = this,
                initialSettings = settings,
                locale = locale,
                stateRepository = requireNotNull(stateRepository),
                mineJournal = requireNotNull(mineJournal),
                farmLocationRepository = requireNotNull(farmLocationRepository),
                network = networkGateway,
                transfer = BungeeBackendTransfer(this),
                debug = debug,
                regionGateway = regionGateway,
            )
            service = activeService
            activeService.start()
            if (server.pluginManager.isPluginEnabled("PlaceholderAPI")) {
                placeholderExpansion = ArcFarmsPlaceholderExpansion(pluginMeta.version, activeService).also {
                    require(it.register()) { "Could not register the PlaceholderAPI expansion" }
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
            logger.info(
                "ArcFarms enabled on ${settings.serverId}; network=${settings.network.enabled}; " +
                    "redisConnected=${redis?.isConnected() ?: false}",
            )
        } catch (failure: Throwable) {
            logger.log(Level.SEVERE, "ArcFarms failed closed during startup", failure)
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        runCatching { placeholderExpansion?.unregister() }
        placeholderExpansion = null
        runCatching { service?.close() }.onFailure { logger.log(Level.SEVERE, "Could not close ArcFarms service", it) }
        runCatching { network?.close() }.onFailure { logger.log(Level.SEVERE, "Could not close ArcFarms network", it) }
        runCatching { redis?.close() }.onFailure { logger.log(Level.SEVERE, "Could not close ArcFarms Redis", it) }
        runCatching { mineJournal?.close() }.onFailure { logger.log(Level.SEVERE, "Could not close mine journal", it) }
        runCatching { farmLocationRepository?.close() }.onFailure {
            logger.log(Level.SEVERE, "Could not close farm location repository", it)
        }
        runCatching { stateRepository?.close() }.onFailure { logger.log(Level.SEVERE, "Could not close state repository", it) }
        server.messenger.unregisterOutgoingPluginChannel(this, BungeeBackendTransfer.CHANNEL)
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

    private fun saveResourceIfMissing(path: String) {
        if (!Files.isRegularFile(dataFolder.toPath().resolve(path))) saveResource(path, false)
    }
}
