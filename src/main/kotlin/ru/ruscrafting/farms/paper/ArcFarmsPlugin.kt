package ru.ruscrafting.farms.paper

import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.ConfigManager
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.persistence.ArcFarmsStateRepository
import ru.ruscrafting.farms.persistence.MineBlockJournal
import java.nio.file.Files
import java.util.logging.Level

class ArcFarmsPlugin : JavaPlugin() {
    private lateinit var settings: ArcFarmsConfig
    private lateinit var locale: ArcFarmsLocale
    private var service: ArcFarmsService? = null
    private var stateRepository: ArcFarmsStateRepository? = null
    private var mineJournal: MineBlockJournal? = null

    override fun onEnable() {
        saveDefaultConfig()
        saveResourceIfMissing("lang/ru.yml")
        saveResourceIfMissing("lang/en.yml")
        PaperArcRuntime.installScheduling(this)
        try {
            val dataRoot = dataFolder.toPath()
            settings = ArcFarmsConfig.load(dataRoot)
            ArcFarmsLocale.validateFiles(dataRoot, settings)
            require(settings.enabled) { "ArcFarms is disabled in config.yml" }
            locale = ArcFarmsLocale(dataRoot) { settings }
            stateRepository = ArcFarmsStateRepository(dataRoot)
            mineJournal = MineBlockJournal(dataRoot)
            val activeService = ArcFarmsService(
                plugin = this,
                initialSettings = settings,
                locale = locale,
                stateRepository = requireNotNull(stateRepository),
                mineJournal = requireNotNull(mineJournal),
            ).also { it.start() }
            service = activeService
            val menu = ArcFarmsMenu(activeService, locale, ::reloadPlugin)
            val command = ArcFarmsCommand(activeService, locale, menu, ::reloadPlugin)
            requireNotNull(getCommand("arcfarms")).apply {
                setExecutor(command)
                tabCompleter = command
            }
            server.pluginManager.registerEvents(ArcFarmsListener(activeService, menu), this)
            logger.info("ArcFarms enabled with arc-core scheduling")
        } catch (failure: Throwable) {
            logger.log(Level.SEVERE, "ArcFarms failed closed during startup", failure)
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        runCatching { service?.close() }.onFailure { logger.log(Level.SEVERE, "Could not close ArcFarms service", it) }
        runCatching { mineJournal?.close() }.onFailure { logger.log(Level.SEVERE, "Could not close mine journal", it) }
        runCatching { stateRepository?.close() }.onFailure { logger.log(Level.SEVERE, "Could not close state repository", it) }
        Tasks.reset()
    }

    private fun reloadPlugin(): Result<Unit> = runCatching {
        val dataRoot = dataFolder.toPath()
        val candidate = ArcFarmsConfig.inspect(dataRoot)
        require(candidate.enabled) { "ArcFarms cannot be disabled with reload" }
        ArcFarmsLocale.validateFiles(dataRoot, candidate)
        ConfigManager.reloadAll()
        requireNotNull(service).reload(candidate)
        settings = candidate
    }

    private fun saveResourceIfMissing(path: String) {
        if (!Files.isRegularFile(dataFolder.toPath().resolve(path))) saveResource(path, false)
    }
}
