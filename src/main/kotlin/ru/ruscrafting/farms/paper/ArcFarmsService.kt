package ru.ruscrafting.farms.paper

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound as AdventureSound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.data.Ageable
import org.bukkit.block.data.type.Farmland
import org.bukkit.entity.Entity
import org.bukkit.entity.Display
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.EntityType
import org.bukkit.entity.Interaction
import org.bukkit.entity.Item
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Horse
import org.bukkit.entity.TextDisplay
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockGrowEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.block.MoistureChangeEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.config.LumberZoneSettings
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.config.MineZoneSettings
import ru.ruscrafting.farms.config.TeleportDestination
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.ActivityStatsIndex
import ru.ruscrafting.farms.domain.farmWeekStartEpochDay
import ru.ruscrafting.farms.domain.FarmAdminEdit
import ru.ruscrafting.farms.domain.ArcFarmsState
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.FarmOrder
import ru.ruscrafting.farms.domain.FarmOrderProgressReconciler
import ru.ruscrafting.farms.domain.FarmContractPlanner
import ru.ruscrafting.farms.domain.FarmCarePlanner
import ru.ruscrafting.farms.domain.FarmOrchardPlanner
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareTarget
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmCropDamage
import ru.ruscrafting.farms.domain.FarmDeliveryPlanner
import ru.ruscrafting.farms.domain.FarmPatchPlanner
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmDeliveryPosition
import ru.ruscrafting.farms.domain.FarmGuidancePlanner
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmIncidentPlanner
import ru.ruscrafting.farms.domain.FarmIncidentRecovery
import ru.ruscrafting.farms.domain.FarmLocationOverrides
import ru.ruscrafting.farms.domain.FarmMachinePlanner
import ru.ruscrafting.farms.domain.FarmMusicLoop
import ru.ruscrafting.farms.domain.FarmPestNest
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmRules
import ru.ruscrafting.farms.domain.FarmSeederStage
import ru.ruscrafting.farms.domain.FarmRewardPlanner
import ru.ruscrafting.farms.domain.FarmRewardRecipient
import ru.ruscrafting.farms.domain.FarmRewardItem
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.domain.FarmSpecialIncidentEngine
import ru.ruscrafting.farms.domain.FarmSpecialIncidentState
import ru.ruscrafting.farms.domain.FarmSpecialIncidentPlanner
import ru.ruscrafting.farms.domain.FarmMatureCrop
import ru.ruscrafting.farms.domain.seederStage
import ru.ruscrafting.farms.domain.FarmWaterPlanner
import ru.ruscrafting.farms.domain.FarmWaterFlowTracker
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.LumberRules
import ru.ruscrafting.farms.domain.LumberShiftEngine
import ru.ruscrafting.farms.domain.LumberShiftState
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineRules
import ru.ruscrafting.farms.domain.MineShiftEngine
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.PendingMineBlock
import ru.ruscrafting.farms.domain.PendingFixedFarmCrop
import ru.ruscrafting.farms.domain.PendingFarmReward
import ru.ruscrafting.farms.domain.PlayerActivityStats
import ru.ruscrafting.farms.domain.ShiftEvent
import ru.ruscrafting.farms.domain.winner
import ru.ruscrafting.farms.persistence.ArcFarmsStateRepository
import ru.ruscrafting.farms.persistence.FarmLocationRepository
import ru.ruscrafting.farms.persistence.FixedFarmCropJournal
import ru.ruscrafting.farms.persistence.MineBlockJournal
import ru.ruscrafting.farms.network.ActivityNetworkGateway
import ru.ruscrafting.farms.network.NetworkSignal
import ru.ruscrafting.farms.network.NoOpActivityNetworkGateway
import ru.ruscrafting.farms.network.WorkdayState
import java.time.Duration
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.random.RandomGenerator
import java.util.logging.Level
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import org.bukkit.util.Vector

data class ActivityStatus(
    val kind: ActivityKind,
    val id: String,
    val phasePath: String,
    val progress: String,
)

private data class FarmRuntime(
    val settings: FarmZoneSettings,
    val region: ActivityRegion,
    val orders: Map<String, FarmOrder>,
    val orderList: List<FarmOrder>,
    val rules: FarmRules,
    var state: FarmShiftState,
)

private data class LumberRuntime(
    val settings: LumberZoneSettings,
    val region: ActivityRegion,
    val station: ActivityRegion,
    val rules: LumberRules,
    val stationMaterials: Set<Material>,
    var state: LumberShiftState,
)

private data class MineRuntime(
    val settings: MineZoneSettings,
    val region: ActivityRegion,
    val rules: MineRules,
    val temporaryMaterial: Material,
    val baseMaterial: Material,
    val materialWeights: LinkedHashMap<Material, Int>,
    var state: MineShiftState,
)

private data class BarKey(val playerId: UUID, val runtimeKey: String)
private data class DeliveryKey(val zoneId: String, val index: Int)
private data class SupplyKey(val zoneId: String, val kind: FarmSupplyKind)
private data class PestNestKey(val zoneId: String, val position: FarmPlotPosition)
private data class CareEntityKey(val zoneId: String, val targetId: Int)
private data class DroughtGrowthRuntime(var startedAt: Long, var spawned: Int)
private data class FarmCropHarvestCommit(
    val crop: Material,
    val replantData: Ageable?,
    val fixedCrop: Boolean,
    val now: Long,
)
private enum class FarmSupplyKind { TOOL, SEEDS, WATER }

private val FARM_SOIL_TYPES = setOf(
    Material.DIRT,
    Material.FARMLAND,
    Material.GRASS_BLOCK,
    Material.DIRT_PATH,
    Material.COARSE_DIRT,
    Material.ROOTED_DIRT,
    Material.PODZOL,
    Material.MYCELIUM,
)
private val FARM_WATER_DROP_TYPES = setOf(
    Material.WHEAT,
    Material.WHEAT_SEEDS,
    Material.CARROT,
    Material.POTATO,
    Material.POISONOUS_POTATO,
    Material.BEETROOT,
    Material.BEETROOT_SEEDS,
    Material.SWEET_BERRIES,
)
private const val PATCH_PERSIST_INTERVAL = 10
private const val FARM_WATER_RADIUS = 5
private const val FARM_CARE_RECONCILE_INTERVAL_MILLIS = 10_000L
private val FARM_TILL_COLOR = Color.fromRGB(255, 173, 66)
private val FARM_PLANT_COLOR = Color.fromRGB(199, 120, 255)
private val FARM_DROUGHT_COLOR = Color.fromRGB(255, 122, 69)
private val FARM_AMBER_COLOR = Color.fromRGB(255, 200, 87)
private val FARM_DELIVERY_COLOR = Color.fromRGB(199, 120, 255)
private val FARM_DANGER_COLOR = Color.fromRGB(255, 95, 109)
private val FARM_SUCCESS_COLOR = Color.fromRGB(85, 217, 139)
private val FARM_CARE_COLOR = Color.fromRGB(84, 201, 185)

private fun Block.toFarmPlotPosition(): FarmPlotPosition = FarmPlotPosition(world.name, x, y, z)

private fun Location.toFarmPlotPosition(): FarmPlotPosition = FarmPlotPosition(world.name, blockX, blockY, blockZ)

private fun FarmPlotPosition.location(): Location? =
    Bukkit.getWorld(world)?.let { Location(it, x.toDouble(), y.toDouble(), z.toDouble()) }

private fun FarmPlotPosition.block(): Block? {
    val loadedWorld = Bukkit.getWorld(world) ?: return null
    if (!loadedWorld.isChunkLoaded(x shr 4, z shr 4)) return null
    return loadedWorld.getBlockAt(x, y, z)
}

class ArcFarmsService(
    private val plugin: Plugin,
    initialSettings: ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    private val stateRepository: ArcFarmsStateRepository,
    private val mineJournal: MineBlockJournal,
    private val fixedCropJournal: FixedFarmCropJournal,
    private val farmLocationRepository: FarmLocationRepository,
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
    private var farms: List<FarmRuntime> = emptyList()
    private var lumbermills: List<LumberRuntime> = emptyList()
    private var mines: List<MineRuntime> = emptyList()
    private val stats = ActivityStatsIndex(currentWeekStartEpochDay = { farmWeekStartEpochDay(clock()) })
    private val pendingFarmRewards = mutableListOf<PendingFarmReward>()
    private val claimedFarmRewardSequences = mutableMapOf<String, Long>()
    private val activeBars = mutableMapOf<BarKey, BossBar>()
    private val farmScoreboards = FarmScoreboardController(FarmScoreboardRenderer(locale), { settings.farmScoreboard }, debug)
    private val contractScene = FarmContractSceneManager(plugin, debug)
    private val specialIncidentScene = FarmSpecialIncidentSceneManager(plugin, debug)
    private val nightShift = FarmNightShiftController()
    private val marketMenu = FarmMarketMenu(locale) { settings }
    private val mineReservations = ConcurrentHashMap.newKeySet<String>()
    private val pendingPositions = ConcurrentHashMap<String, String>()
    private val interactionCooldowns = mutableMapOf<String, Long>()
    private val pestEntities = mutableMapOf<String, MutableSet<UUID>>()
    private val pestNestEntities = mutableMapOf<PestNestKey, MutableSet<UUID>>()
    private val deliveryEntities = mutableMapOf<DeliveryKey, MutableSet<UUID>>()
    private val deliveryCarriers = mutableMapOf<DeliveryKey, UUID>()
    private val carriedDisplays = mutableMapOf<DeliveryKey, UUID>()
    private val supplyEntities = mutableMapOf<SupplyKey, MutableSet<UUID>>()
    private val supplyVisualMaterials = mutableMapOf<SupplyKey, Material>()
    private val careEntities = mutableMapOf<CareEntityKey, MutableSet<UUID>>()
    private val animalFollowers = mutableMapOf<CareEntityKey, UUID>()
    private val diseaseNextSpreadAt = mutableMapOf<String, Long>()
    private val careNextReconcileAt = mutableMapOf<String, Long>()
    private val farmMusic = FarmMusicLoop()
    private val pollenCharges = mutableMapOf<UUID, Int>()
    private val waterFlows = mutableMapOf<String, FarmWaterFlowTracker>()
    private val droughtGrowth = mutableMapOf<String, DroughtGrowthRuntime>()
    private val pendingIncidentRestore = mutableSetOf<String>()
    private val patchRestoreProgress = mutableMapOf<String, MutableSet<FarmPlotPosition>>()
    private val fixedCropRestoreQueue = FarmBlockRestoreQueue()
    private val adminPausedFarmZones = mutableSetOf<String>()
    private var nextWaterFlowId = 1L
    private var farmLocations: FarmLocationOverrides = FarmLocationOverrides()
    private val adminEditPlayers = mutableSetOf<UUID>()
    private val adminInspectPlayers = mutableSetOf<UUID>()
    private val farmBlockLedger = FarmBlockLedger(plugin)
    private val farmMachineBlocks = FarmMachineBlockProcessor(farmBlockLedger)
    private val farmBlockRegistry = FarmBlockRegistry(plugin, farmBlockLedger, clock)
    private val farmBlockAdmin = FarmBlockAdminController(
        plugin,
        farmBlockRegistry,
        fixedCropJournal,
        locale,
        debug,
    ) { player, key, values -> sendChat(player, key, values) }
    private val farmBackupAdmin = FarmBackupAdminController(
        plugin,
        plugin.dataFolder.toPath(),
        farmBlockRegistry,
        fixedCropJournal,
        locale,
        debug,
        { player, key, values -> sendChat(player, key, values) },
        clock,
    )
    private val pestZoneKey = NamespacedKey(plugin, "farm_pest_zone")
    private val pestSequenceKey = NamespacedKey(plugin, "farm_pest_sequence")
    private val pestNestZoneKey = NamespacedKey(plugin, "farm_pest_nest_zone")
    private val pestNestXKey = NamespacedKey(plugin, "farm_pest_nest_x")
    private val pestNestYKey = NamespacedKey(plugin, "farm_pest_nest_y")
    private val pestNestZKey = NamespacedKey(plugin, "farm_pest_nest_z")
    private val deliveryZoneKey = NamespacedKey(plugin, "farm_delivery_zone")
    private val deliverySequenceKey = NamespacedKey(plugin, "farm_delivery_sequence")
    private val deliveryIndexKey = NamespacedKey(plugin, "farm_delivery_index")
    private val supplyZoneKey = NamespacedKey(plugin, "farm_supply_zone")
    private val supplyKindKey = NamespacedKey(plugin, "farm_supply_kind")
    private val serviceItemKey = NamespacedKey(plugin, "farm_service_item")
    private val careZoneKey = NamespacedKey(plugin, "farm_care_zone")
    private val careSequenceKey = NamespacedKey(plugin, "farm_care_sequence")
    private val careTargetKey = NamespacedKey(plugin, "farm_care_target")
    private val careRoleKey = NamespacedKey(plugin, "farm_care_role")
    private val tasks = mutableListOf<ScheduledTask>()
    @Volatile
    private var started = false
    @Volatile
    private var closed = false
    private var stateSafeToPersist = false
    private var persistenceSuspended = false
    private var persistenceRequestedWhileSuspended = false

    fun start() {
        check(!started && !closed) { "ArcFarms service cannot be started in its current lifecycle state" }
        farmLocations = farmLocationRepository.load()
        validateRuntime(settings)
        validateLocationOverrides(settings)
        val loaded = stateRepository.load()
        val persisted = reconcileFarmOrderProgress(settings, loaded)
        validatePersistedState(settings, persisted)
        validateMineJournalMaterials()
        stats.replace(persisted.stats)
        pendingFarmRewards.clear()
        pendingFarmRewards += persisted.pendingFarmRewards
        claimedFarmRewardSequences.clear()
        claimedFarmRewardSequences += persisted.claimedFarmRewardSequences
        adminPausedFarmZones.clear()
        adminPausedFarmZones += persisted.pausedFarmZones.orEmpty()
        rebuild(persisted)
        cleanupOwnedFarmEntities()
        reconcileFarmPatches()
        reconcileLoadedFarmBlockIndexes()
        reconcileLoadedFixedCrops()
        farms.forEach(::ensureFarmSupplies)
        farms.forEach(::ensureFarmContractScene)
        farms.forEach(::ensureFarmSpecialIncident)
        mineJournal.records().forEach { pendingPositions[it.positionKey] = it.id }
        startTasks()
        stateSafeToPersist = true
        started = true
        if (persisted != loaded) persistAsync()
        Tasks.scheduler.runLater(1L) {
            if (isOperational()) deliverPendingFarmRewards(Bukkit.getOnlinePlayers())
        }
        plugin.logger.info(
            "ArcFarms ready: ${farms.size} farm, ${lumbermills.size} lumbermill, ${mines.size} mine zones; " +
                "${pendingPositions.size} pending mine blocks",
        )
    }

    fun reload(candidate: ArcFarmsConfig, publishSettings: (ArcFarmsConfig) -> Unit) {
        check(started) { "ArcFarms service is not started" }
        require(!farmBackupAdmin.busy()) { "ArcFarms cannot reload while a farm backup operation is active" }
        val snapshot = snapshotState()
        val reconciledSnapshot = reconcileFarmOrderProgress(candidate, snapshot)
        validateReload(candidate, reconciledSnapshot)
        validateRuntime(candidate)
        validateLocationOverrides(candidate)
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
        plugin.logger.info("ArcFarms reloaded with ${farms.size + lumbermills.size + mines.size} zones")
    }

    fun isOperational(): Boolean = started && !closed

    private fun replaceRuntime(candidate: ArcFarmsConfig, snapshot: ArcFarmsState, reason: String) {
        stopAllFarmMusic(reason)
        clearTemporaryFarmWater(reason)
        droughtGrowth.clear()
        hideAllBars()
        farmScoreboards.restoreAll(reason)
        cleanupOwnedFarmEntities()
        settings = candidate
        rebuild(snapshot)
        reconcileFarmPatches()
        reconcileLoadedFarmBlockIndexes()
        reconcileLoadedFixedCrops()
        farms.forEach(::ensureFarmSupplies)
        farms.forEach(::ensureFarmContractScene)
        farms.forEach(::ensureFarmSpecialIncident)
        startTasks()
    }

    fun onBreakLowest(event: BlockBreakEvent) {
        if (
            farmAt(event.block.location) != null &&
            WorldEditToolGuard.ownsInteraction(event.player, event.player.inventory.itemInMainHand)
        ) {
            event.isCancelled = true
            return
        }
        if (event.player.uniqueId in adminInspectPlayers) {
            event.isCancelled = true
            inspectFarmBlock(event.player, event.block)
            return
        }
        if (event.player.uniqueId in adminEditPlayers) return
        if (farmAt(event.block.location) != null) event.isCancelled = true
    }

    fun onBreakHigh(event: BlockBreakEvent) {
        if (
            farmAt(event.block.location) != null &&
            WorldEditToolGuard.ownsInteraction(event.player, event.player.inventory.itemInMainHand)
        ) {
            event.isCancelled = true
            return
        }
        if (event.player.uniqueId in adminInspectPlayers) {
            event.isCancelled = true
            return
        }
        if (event.player.uniqueId in adminEditPlayers) {
            val fixedRecord = farmBlockLedger.fixedCropRecord(event.block)
            if (fixedRecord != null) {
                event.isCancelled = true
                val removedBlockType = event.block.type
                val removed = farmBlockLedger.removeFixedCrop(event.block)
                val fixedPositionKey = positionKey(event.block.location)
                if (fixedCropJournal.contains(fixedPositionKey)) {
                    retireFixedCropJournal(fixedPositionKey, "admin_edit_break")
                }
                event.block.setType(Material.AIR, false)
                debug.event(
                    "farm_admin_edit_fixed_crop_removed",
                    "player" to event.player.name,
                    "zone" to fixedRecord.zoneId,
                    "block" to removedBlockType,
                    "managed_record_removed" to removed,
                    "x" to event.block.x,
                    "y" to event.block.y,
                    "z" to event.block.z,
                )
                sendActionBar(event.player, MessageKey.ADMIN_EDIT_BLOCK_REMOVED)
                return
            }
            val soil = when {
                farmAt(event.block.location) != null && event.block.type in FARM_SOIL_TYPES -> event.block
                farmAt(event.block.location) != null && event.block.getRelative(org.bukkit.block.BlockFace.DOWN).type in FARM_SOIL_TYPES ->
                    event.block.getRelative(org.bukkit.block.BlockFace.DOWN)
                else -> null
            }
            val runtime = soil?.let { farmAt(it.location) }
            if (soil != null && runtime != null) {
                event.isCancelled = true
                val position = soil.toFarmPlotPosition()
                val previous = runtime.state
                val removal = FarmAdminEdit.removePlot(previous, position)
                runtime.state = removal.state
                try {
                    persistBlocking()
                } catch (failure: Exception) {
                    runtime.state = previous
                    plugin.logger.log(Level.SEVERE, "Could not persist admin removal of managed farm plot $position", failure)
                    sendChat(event.player, MessageKey.GENERIC_ERROR)
                    return
                }
                val removedBlockType = event.block.type
                val removed = farmBlockLedger.remove(soil)
                farmBlockRegistry.removeBeds(runtime.settings.id, listOf(position))
                removal.careTargetIds.forEach { targetId ->
                    removeFarmCareEntities(CareEntityKey(runtime.settings.id, targetId), "admin_plot_removed")
                }
                if (removal.pestNestRemoved) {
                    removePestNestEntities(PestNestKey(runtime.settings.id, position), "admin_plot_removed")
                }
                if (event.block == soil) soil.getRelative(org.bukkit.block.BlockFace.UP).setType(Material.AIR, false)
                event.block.setType(Material.AIR, false)
                if (removal.shiftRetired) {
                    clearFarmCare(runtime, "admin_plot_removed")
                    removePests(runtime, activePests(runtime), "admin_plot_removed")
                    removePestNestEntities(runtime, "admin_plot_removed")
                    clearDelivery(runtime, "admin_plot_removed")
                } else if (
                    runtime.state.phase == FarmPhase.INCIDENT &&
                    (runtime.state.incidentType ?: FarmIncidentType.PESTS) == FarmIncidentType.PESTS &&
                    runtime.state.pestNests.isEmpty() && runtime.state.pestAlive == 0
                ) {
                    if (currentOrder(runtime) != null) {
                        applyFarmResult(
                            runtime,
                            FarmShiftEngine.finishPestIncidentIfClear(runtime.state),
                            null,
                        )
                    }
                }
                debug.event(
                    "farm_admin_edit_break",
                    "player" to event.player.name,
                    "zone" to runtime.settings.id,
                    "block" to removedBlockType,
                    "managed_record_removed" to removed,
                    "x" to event.block.x,
                    "y" to event.block.y,
                    "z" to event.block.z,
                )
                sendActionBar(event.player, MessageKey.ADMIN_EDIT_BLOCK_REMOVED)
                return
            }
            if (farmAt(event.block.location) != null) event.isCancelled = false
            return
        }
        mineAt(event.block.location)?.let { runtime ->
            traceBlockBreak(event, ActivityKind.MINE, runtime.settings.id)
            handleMineBreak(event, runtime)
            return
        }
        farmAt(event.block.location)?.let { runtime ->
            traceBlockBreak(event, ActivityKind.FARM, runtime.settings.id)
            handleFarmBreakHigh(event, runtime)
            return
        }
        lumberAt(event.block.location)?.let { runtime ->
            traceBlockBreak(event, ActivityKind.LUMBER, runtime.settings.id)
            handleLumberBreakHigh(event, runtime)
        }
    }

    fun onBreakMonitor(event: BlockBreakEvent) {
        if (event.isCancelled) return
        lumberAt(event.block.location)?.let { runtime ->
            val species = MaterialRules.speciesOf(event.block.type) ?: return@let
            handleLumberFell(runtime, event.player, species)
        }
    }

    fun onBlockDrop(event: BlockDropItemEvent) {
        val runtime = farmAt(event.blockState.location) ?: return
        if (event.blockState.type.name !in runtime.settings.crops) return
        val removed = event.items.filter { it.itemStack.type in FARM_WATER_DROP_TYPES }
        removed.forEach(Entity::remove)
        if (removed.isNotEmpty()) {
            debug.event(
                "farm_crop_drops_suppressed",
                "zone" to runtime.settings.id,
                "crop" to event.blockState.type,
                "count" to removed.sumOf { it.itemStack.amount },
            )
        }
    }

    fun onInteractLowest(event: PlayerInteractEvent) {
        if (WorldEditToolGuard.ownsInteraction(event.player, event.item)) return
        if (event.player.uniqueId in adminInspectPlayers) {
            val clicked = event.clickedBlock ?: return
            if (event.action != Action.PHYSICAL && (event.hand == null || event.hand == EquipmentSlot.HAND)) {
                denyInteraction(event)
                inspectFarmBlock(event.player, clicked)
            }
            return
        }
        if (event.player.uniqueId in adminEditPlayers) return
        val clicked = event.clickedBlock ?: return
        val runtime = farmAt(clicked.location) ?: return
        if (event.action == Action.PHYSICAL && clicked.type == Material.FARMLAND) {
            denyInteraction(event)
            return
        }
        if (event.hand != EquipmentSlot.HAND || event.action != Action.RIGHT_CLICK_BLOCK) return
        val item = event.player.inventory.itemInMainHand
        val ownedInteraction = (clicked.type == Material.SWEET_BERRY_BUSH && clicked.type.name in runtime.settings.crops) ||
            isFarmServiceItem(item) ||
            (runtime.state.phase == FarmPhase.PREPARATION && MaterialRules.isHoe(item)) ||
            (runtime.state.phase == FarmPhase.PLANTING && MaterialRules.cropForSeed(item) != null) ||
            (runtime.state.phase == FarmPhase.INCIDENT &&
                runtime.state.incidentType == FarmIncidentType.DROUGHT && item.type == Material.WATER_BUCKET)
        if (ownedInteraction) denyInteraction(event)
    }

    fun onInteract(event: PlayerInteractEvent) {
        if (WorldEditToolGuard.ownsInteraction(event.player, event.item)) return
        if (event.player.uniqueId in adminInspectPlayers) {
            if (event.clickedBlock != null && event.action != Action.PHYSICAL) denyInteraction(event)
            return
        }
        if (event.player.uniqueId in adminEditPlayers) {
            if (event.clickedBlock?.location?.let(::farmAt) != null) {
                event.setUseInteractedBlock(org.bukkit.event.Event.Result.ALLOW)
                event.setUseItemInHand(org.bukkit.event.Event.Result.ALLOW)
                event.isCancelled = false
            }
            return
        }
        if (event.action == Action.PHYSICAL) {
            val clicked = event.clickedBlock ?: return
            if (clicked.type == Material.FARMLAND && farmAt(clicked.location) != null) event.isCancelled = true
            return
        }
        if (event.hand != EquipmentSlot.HAND || event.action != Action.RIGHT_CLICK_BLOCK) return
        val clicked = event.clickedBlock ?: return
        val player = event.player
        if (handleFarmCareInteraction(event, clicked, player)) return
        farmAt(clicked.location)?.takeIf { runtime ->
            clicked.type == Material.SWEET_BERRY_BUSH && clicked.type.name in runtime.settings.crops
        }?.let { runtime ->
            event.isCancelled = true
            if (handleFarmSpecialCropBreak(runtime, player, clicked)) return
            handleFarmCropHarvest(runtime, player, clicked) { commit ->
                check(!commit.fixedCrop) { "Sweet berry harvest cannot use fixed crop recovery" }
                val replantData = requireNotNull(commit.replantData)
                clicked.setBlockData(replantData, false)
                clicked.getRelative(org.bukkit.block.BlockFace.DOWN).takeIf { it.type == Material.FARMLAND }?.let { soil ->
                    farmBlockLedger.captureActiveCrop(soil, runtime.settings.id)
                }
                debug.event(
                    "farm_crop_committed",
                    "player" to player.name,
                    "zone" to runtime.settings.id,
                    "crop" to commit.crop,
                    "input" to "right_click",
                    "drops" to "consumed_by_order",
                )
                if (settings.sounds) {
                    player.playSound(clicked.location, Sound.BLOCK_SWEET_BERRY_BUSH_PICK_BERRIES, 0.7f, 1.05f)
                }
                handleFarmHarvest(runtime, player, commit.crop.name)
            }
            return
        }
        farmAt(clicked.location)?.takeIf { isFarmServiceItem(player.inventory.itemInMainHand) }?.let { runtime ->
            event.isCancelled = true
            sendFarmCurrentTaskHint(player, runtime, "service_item_wrong_phase")
            return
        }
        lumbermills.firstOrNull { it.station.contains(clicked.location) }?.let { runtime ->
            if (clicked.type !in runtime.stationMaterials) return@let
            event.isCancelled = true
            debug.event(
                "player_interact",
                "player" to player.name,
                "activity" to ActivityKind.LUMBER,
                "zone" to runtime.settings.id,
                "action" to "use_station",
                "block" to clicked.type,
            )
            if (!hasAccess(player, runtime.settings.permission)) {
                event.isCancelled = true
                sendChat(player, MessageKey.ZONE_LOCKED)
                return
            }
            if (!allowInteraction("lumber:${runtime.settings.id}:${player.uniqueId}", 900)) return
            handleLumberProcessing(runtime, player)
            return
        }

        val runtime = mineAt(clicked.location) ?: return
        if (runtime.state.phase != MinePhase.HAZARD || !player.isSneaking ||
            !MaterialRules.isPickaxe(player.inventory.itemInMainHand) || !clicked.type.isSolid
        ) return
        debug.event(
            "player_interact",
            "player" to player.name,
            "activity" to ActivityKind.MINE,
            "zone" to runtime.settings.id,
            "action" to "install_support",
            "block" to clicked.type,
        )
        event.isCancelled = true
        if (!hasAccess(player, runtime.settings.permission)) {
            sendChat(player, MessageKey.ZONE_LOCKED)
            return
        }
        if (!allowInteraction("mine:${runtime.settings.id}:${player.uniqueId}", 750)) return
        val result = MineShiftEngine.stabilize(runtime.state, runtime.rules, player.uniqueId, clock())
        applyMineResult(runtime, result, player)
    }

    fun onBlockFromTo(event: org.bukkit.event.block.BlockFromToEvent) {
        val runtime = farmAt(event.block.location) ?: return
        val tracker = waterFlows[runtime.settings.id] ?: return
        if (tracker.owners(event.block.toFarmPlotPosition()).isEmpty()) return
        if (!runtime.region.contains(event.toBlock.location)) {
            event.isCancelled = true
            return
        }
        event.isCancelled = false
        val target = event.toBlock
        if (target.type.name in runtime.settings.crops && !MaterialRules.isFixedBlockCrop(target.type)) {
            val soil = target.getRelative(org.bukkit.block.BlockFace.DOWN)
            if (soil.type in FARM_SOIL_TYPES) {
                farmBlockLedger.captureActiveCropIfPresent(soil, runtime.settings.id)
                val position = soil.toFarmPlotPosition()
                farmBlockRegistry.addBeds(runtime.settings.id, listOf(position))
                if (position !in runtime.state.droughtDamagedPlots) {
                    runtime.state = runtime.state.copy(droughtDamagedPlots = runtime.state.droughtDamagedPlots + position)
                    if (runtime.state.droughtDamagedPlots.size % 5 == 0) persistAsync()
                }
                // Remove the crop without drops before vanilla water replaces it.
                // The ledger above remains the sole recovery source for this drought.
                target.setType(Material.AIR, false)
            }
        }
        if (target.type != Material.WATER) {
            tracker.propagate(event.block.toFarmPlotPosition(), target.toFarmPlotPosition())
        }
    }

    fun onMove(event: PlayerMoveEvent) {
        val destination = event.to
        if (
            event.from.world == destination.world && event.from.blockX == destination.blockX &&
            event.from.blockY == destination.blockY && event.from.blockZ == destination.blockZ
        ) return
        val player = event.player
        val fromFarm = farmAt(event.from)
        val toFarm = farmAt(destination)
        if (fromFarm != null && fromFarm !== toFarm) {
            removeFarmServiceItems(player, fromFarm.settings.id, "left_zone")
            farmScoreboards.remove(player, "left_zone")
        }
        if (toFarm != null && fromFarm !== toFarm) {
            showFarmEntry(player, toFarm)
        }
        if (fromFarm !== toFarm) syncFarmMusic(player, toFarm, clock())
        deliveryCarriers.filterValues { it == player.uniqueId }.keys.toList().forEach { key ->
            farms.firstOrNull { it.settings.id == key.zoneId }?.let { runtime ->
                handleDeliveryMovement(runtime, key, player, destination)
            }
        }
        if (event is PlayerTeleportEvent) return
        mines.firstOrNull { runtime ->
            runtime.state.phase == MinePhase.EXTRACTION &&
                runtime.region.contains(event.from) && !runtime.region.contains(destination)
        }?.let { runtime ->
            debug.event(
                "player_move",
                "player" to player.name,
                "activity" to ActivityKind.MINE,
                "zone" to runtime.settings.id,
                "action" to "leave_for_extraction",
            )
            val result = MineShiftEngine.extract(runtime.state, runtime.rules, player.uniqueId, clock())
            applyMineResult(runtime, result, player)
        }
    }

    fun onQuit(player: Player) {
        stopFarmMusic(player, "player_quit")
        nightShift.clear(player)
        farmScoreboards.remove(player, "player_quit")
        val keys = activeBars.keys.filter { it.playerId == player.uniqueId }
        keys.forEach { key -> activeBars.remove(key)?.let(player::hideBossBar) }
        deliveryCarriers.filterValues { it == player.uniqueId }.keys.toList().forEach { key ->
            farms.firstOrNull { it.settings.id == key.zoneId }?.let { runtime ->
                returnDelivery(runtime, key, player, "player_quit", notify = false)
            }
        }
        removeFarmServiceItems(player, reason = "player_quit")
        pollenCharges.remove(player.uniqueId)
        animalFollowers.filterValues { it == player.uniqueId }.keys.toList().forEach { key ->
            val mob = careEntities[key].orEmpty().asSequence().mapNotNull(Bukkit::getEntity).filterIsInstance<Mob>().firstOrNull()
            releaseAnimalFollower(key, mob, "player_quit")
        }
        val playerToken = player.uniqueId.toString()
        interactionCooldowns.keys.removeIf { playerToken in it }
        adminEditPlayers.remove(player.uniqueId)
        adminInspectPlayers.remove(player.uniqueId)
    }

    fun onInteractEntityLowest(event: PlayerInteractEntityEvent) {
        if (event.player.uniqueId in adminInspectPlayers) {
            event.isCancelled = true
            return
        }
        if (event.player.uniqueId in adminEditPlayers) return
        val data = event.rightClicked.persistentDataContainer
        if (
            data.has(careZoneKey, PersistentDataType.STRING) ||
            data.has(supplyZoneKey, PersistentDataType.STRING) ||
            data.has(deliveryZoneKey, PersistentDataType.STRING) ||
            contractScene.owns(event.rightClicked) ||
            specialIncidentScene.owns(event.rightClicked)
        ) {
            event.isCancelled = true
        }
    }

    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (event.player.uniqueId in adminInspectPlayers) {
            event.isCancelled = true
            return
        }
        if (event.player.uniqueId in adminEditPlayers) return
        if (event.hand != EquipmentSlot.HAND) return
        if (specialIncidentScene.owns(event.rightClicked)) {
            event.isCancelled = true
            handleFarmSpecialSceneInteraction(event.player, event.rightClicked)
            return
        }
        if (contractScene.owns(event.rightClicked)) {
            event.isCancelled = true
            handleFarmContractSceneInteraction(event.player, event.rightClicked)
            return
        }
        val careZone = event.rightClicked.persistentDataContainer.get(careZoneKey, PersistentDataType.STRING)
        if (careZone != null) {
            event.isCancelled = true
            handleFarmCareEntityInteraction(event.player, event.rightClicked, careZone)
            return
        }
        val supplyZone = event.rightClicked.persistentDataContainer.get(supplyZoneKey, PersistentDataType.STRING)
        if (supplyZone != null) {
            event.isCancelled = true
            val kindName = event.rightClicked.persistentDataContainer.get(supplyKindKey, PersistentDataType.STRING) ?: return
            val kind = runCatching { FarmSupplyKind.valueOf(kindName) }.getOrNull() ?: return
            val runtime = farms.firstOrNull { it.settings.id == supplyZone } ?: return
            if (!hasAccess(event.player, runtime.settings.permission)) {
                sendChat(event.player, MessageKey.ZONE_LOCKED)
                return
            }
            if (!allowInteraction("farm-supply:$supplyZone:$kind:${event.player.uniqueId}", 500)) return
            giveFarmSupply(runtime, kind, event.player)
            return
        }
        val zoneId = event.rightClicked.persistentDataContainer.get(deliveryZoneKey, PersistentDataType.STRING) ?: return
        event.isCancelled = true
        val sequence = event.rightClicked.persistentDataContainer.get(deliverySequenceKey, PersistentDataType.LONG) ?: return
        val index = event.rightClicked.persistentDataContainer.get(deliveryIndexKey, PersistentDataType.INTEGER) ?: return
        val runtime = farms.firstOrNull { it.settings.id == zoneId } ?: return
        val player = event.player
        if (!hasAccess(player, runtime.settings.permission)) {
            sendChat(player, MessageKey.ZONE_LOCKED)
            return
        }
        val key = DeliveryKey(zoneId, index)
        if (runtime.state.phase != FarmPhase.DELIVERY || runtime.state.sequence != sequence || index in runtime.state.deliveredCrates) return
        if (deliveryCarriers.containsKey(key) || deliveryCarriers.containsValue(player.uniqueId)) return
        if (!allowInteraction("farm-delivery:$zoneId:$index:${player.uniqueId}", 500)) return
        pickupDelivery(runtime, key, player)
    }

    fun onEntityDamage(event: EntityDamageEvent) {
        val inspectingAttacker = (event as? EntityDamageByEntityEvent)?.let { damage ->
            when (val damager = damage.damager) {
                is Player -> damager
                is Projectile -> damager.shooter as? Player
                else -> null
            }
        }?.takeIf { it.uniqueId in adminInspectPlayers }
        if (inspectingAttacker != null) {
            event.isCancelled = true
            return
        }
        if (contractScene.owns(event.entity)) {
            event.isCancelled = true
            return
        }
        if (specialIncidentScene.owns(event.entity)) {
            event.isCancelled = true
            handleFarmSpecialSceneDamage(event)
            return
        }
        if (event.entity.persistentDataContainer.has(careZoneKey, PersistentDataType.STRING)) {
            event.isCancelled = true
            val player = (event as? EntityDamageByEntityEvent)?.damager as? Player ?: return
            val zoneId = event.entity.persistentDataContainer.get(careZoneKey, PersistentDataType.STRING) ?: return
            handleFarmCareEntityInteraction(player, event.entity, zoneId)
            return
        }
        val nestZoneId = event.entity.persistentDataContainer.get(pestNestZoneKey, PersistentDataType.STRING)
        if (nestZoneId != null) {
            event.isCancelled = true
            val attacker = when (event) {
                is EntityDamageByEntityEvent -> when (val damager = event.damager) {
                    is Player -> damager
                    is Projectile -> damager.shooter as? Player
                    else -> null
                }
                else -> null
            } ?: return
            val runtime = farms.firstOrNull { it.settings.id == nestZoneId } ?: return
            val position = pestNestPosition(event.entity) ?: return
            if (!hasAccess(attacker, runtime.settings.permission) || !runtime.region.contains(event.entity.location)) return
            currentOrder(runtime) ?: return
            val result = FarmShiftEngine.damagePestNest(runtime.state, position, attacker.uniqueId)
            if (!result.accepted) return
            val remainingHealth = result.state.pestNests.firstOrNull { it.position == position }?.health ?: 0
            if (remainingHealth == 0) {
                removePestNestEntities(PestNestKey(nestZoneId, position), "destroyed")
                if (settings.sounds) attacker.playSound(event.entity.location, Sound.BLOCK_WOOD_BREAK, 0.9f, 0.75f)
            } else if (settings.sounds) {
                attacker.playSound(event.entity.location, Sound.BLOCK_WOOD_HIT, 0.7f, 0.85f)
            }
            if (settings.particles) {
                attacker.spawnParticle(
                    Particle.BLOCK,
                    event.entity.location.clone().add(0.0, 0.5, 0.0),
                    8,
                    0.35,
                    0.3,
                    0.35,
                    0.04,
                    Material.MANGROVE_ROOTS.createBlockData(),
                )
            }
            sendActionBar(
                attacker,
                if (remainingHealth == 0) MessageKey.FARM_PEST_NEST_DESTROYED else MessageKey.FARM_PEST_NEST_DAMAGED,
                mapOf("health" to locale.text(remainingHealth)),
            )
            debug.event(
                "farm_pest_nest_damaged",
                "zone" to nestZoneId,
                "player" to attacker.name,
                "health" to remainingHealth,
                "x" to position.x,
                "y" to position.y,
                "z" to position.z,
            )
            applyFarmResult(runtime, result, attacker)
            return
        }
        if (
            event.entity.persistentDataContainer.has(deliveryZoneKey, PersistentDataType.STRING) ||
            event.entity.persistentDataContainer.has(supplyZoneKey, PersistentDataType.STRING) ||
            event.entity.persistentDataContainer.has(careZoneKey, PersistentDataType.STRING) ||
            contractScene.owns(event.entity)
        ) {
            event.isCancelled = true
            return
        }
        val zoneId = event.entity.persistentDataContainer.get(pestZoneKey, PersistentDataType.STRING) ?: return
        event.isCancelled = true
        val sequence = event.entity.persistentDataContainer.get(pestSequenceKey, PersistentDataType.LONG) ?: return
        val attacker = when (event) {
            is EntityDamageByEntityEvent -> when (val damager = event.damager) {
                is Player -> damager
                is Projectile -> damager.shooter as? Player
                else -> null
            }
            else -> null
        } ?: return
        val runtime = farms.firstOrNull { it.settings.id == zoneId } ?: return
        val allowed = FarmPestDamagePolicy.allows(
            hasAccess = hasAccess(attacker, runtime.settings.permission),
            insideRegion = runtime.region.contains(event.entity.location),
            pestIncidentActive = runtime.state.phase == FarmPhase.INCIDENT &&
                (runtime.state.incidentType ?: FarmIncidentType.PESTS) == FarmIncidentType.PESTS,
            sequenceMatches = runtime.state.sequence == sequence,
        )
        if (allowed) {
            event.isCancelled = false
            debug.event("farm_pest_damage_allowed", "zone" to zoneId, "player" to attacker.name, "entity" to event.entity.type)
        } else {
            debug.event("farm_pest_damage_rejected", "zone" to zoneId, "player" to attacker.name, "entity" to event.entity.type)
        }
    }

    fun onMoistureChange(event: MoistureChangeEvent) {
        val runtime = farmAt(event.block.location) ?: return
        val position = event.block.toFarmPlotPosition()
        if (hasActiveWater(runtime.settings.id) && position in runtime.state.droughtPlots) return
        event.isCancelled = true
        if (position !in runtime.state.droughtPlots) setWetFarmland(event.block)
    }

    fun toggleAdminEdit(player: Player): Boolean? {
        if (player.uniqueId in adminEditPlayers) {
            adminEditPlayers.remove(player.uniqueId)
            debug.event("farm_admin_edit", "player" to player.name, "enabled" to false)
            sendActionBar(player, MessageKey.ADMIN_EDIT_DISABLED)
            return false
        }
        if (adminInspectPlayers.remove(player.uniqueId)) {
            sendActionBar(player, MessageKey.ADMIN_INSPECT_DISABLED)
        }
        adminEditPlayers += player.uniqueId
        debug.event("farm_admin_edit", "player" to player.name, "enabled" to true)
        sendActionBar(player, MessageKey.ADMIN_EDIT_ENABLED)
        return true
    }

    fun toggleAdminInspect(player: Player): Boolean {
        if (adminInspectPlayers.remove(player.uniqueId)) {
            debug.event("farm_admin_inspect", "player" to player.name, "enabled" to false)
            sendActionBar(player, MessageKey.ADMIN_INSPECT_DISABLED)
            return false
        }
        if (adminEditPlayers.remove(player.uniqueId)) sendActionBar(player, MessageKey.ADMIN_EDIT_DISABLED)
        adminInspectPlayers += player.uniqueId
        debug.event("farm_admin_inspect", "player" to player.name, "enabled" to true)
        sendActionBar(player, MessageKey.ADMIN_INSPECT_ENABLED)
        return true
    }

    fun adminStartFarmBlockReset(player: Player, zoneId: String): Boolean {
        val runtime = farms.firstOrNull { it.settings.id == zoneId } ?: run {
            sendChat(player, MessageKey.ADMIN_ZONE_UNKNOWN, mapOf("zone" to locale.text(zoneId)))
            return false
        }
        return farmBlockAdmin.start(
            player = player,
            zoneId = zoneId,
            idle = runtime.state.phase == FarmPhase.IDLE,
            definition = farmBlockIndexDefinition(runtime),
        ) {
            adminEditPlayers.remove(player.uniqueId)
            adminInspectPlayers.remove(player.uniqueId)
        }
    }

    fun adminFarmBlockResetStatus(player: Player, zoneId: String): Boolean {
        if (farms.none { it.settings.id == zoneId }) {
            sendChat(player, MessageKey.ADMIN_ZONE_UNKNOWN, mapOf("zone" to locale.text(zoneId)))
            return false
        }
        return farmBlockAdmin.status(player, zoneId)
    }

    fun adminSaveFarmBackup(player: Player, zoneId: String): Boolean =
        farmBackupContext(player, zoneId)?.let { farmBackupAdmin.save(player, it) } ?: false

    fun adminRestoreFarmBackup(player: Player, zoneId: String, backupId: String): Boolean =
        farmBackupContext(player, zoneId)?.let { farmBackupAdmin.restore(player, it, backupId) } ?: false

    fun adminListFarmBackups(player: Player, zoneId: String): Boolean =
        farmBackupContext(player, zoneId)?.let { farmBackupAdmin.list(player, it) } ?: false

    fun adminFarmBackupStatus(player: Player, zoneId: String): Boolean =
        farmBackupContext(player, zoneId)?.let { farmBackupAdmin.status(player, it) } ?: false

    private fun farmBackupContext(player: Player, zoneId: String): FarmBackupAdminContext? {
        val runtime = farms.firstOrNull { it.settings.id == zoneId } ?: run {
            sendChat(player, MessageKey.ADMIN_ZONE_UNKNOWN, mapOf("zone" to locale.text(zoneId)))
            return null
        }
        return FarmBackupAdminContext(
            zoneId = zoneId,
            region = runtime.region,
            idle = runtime.state.phase == FarmPhase.IDLE,
            reindexDefinition = farmBlockIndexDefinition(runtime),
            backupBlocksPerTick = runtime.settings.backupBlocksPerTick,
            backupMaxBlocks = runtime.settings.backupMaxBlocks,
            paused = zoneId in adminPausedFarmZones,
            pause = {
                if (!setFarmOrderCyclePaused(zoneId, paused = true)) adminPausedFarmZones.add(zoneId)
            },
            resume = { setFarmOrderCyclePaused(zoneId, paused = false) },
            clearAdminModes = {
                adminEditPlayers.remove(player.uniqueId)
                adminInspectPlayers.remove(player.uniqueId)
            },
        )
    }

    private fun inspectFarmBlock(player: Player, block: Block) {
        val cooldown = "farm-admin-inspect:${player.uniqueId}:${block.world.name}:${block.x}:${block.y}:${block.z}"
        if (!allowInteraction(cooldown, 250L)) return
        val runtime = farmAt(block.location)
        val fixedBlock = when {
            farmBlockLedger.fixedCropRecord(block) != null -> block
            farmBlockLedger.fixedCropRecord(block.getRelative(org.bukkit.block.BlockFace.UP)) != null ->
                block.getRelative(org.bukkit.block.BlockFace.UP)
            else -> null
        }
        val fixed = fixedBlock?.let(farmBlockLedger::fixedCropRecord)
        val soil = when {
            farmBlockLedger.record(block) != null -> block
            farmBlockLedger.record(block.getRelative(org.bukkit.block.BlockFace.DOWN)) != null ->
                block.getRelative(org.bukkit.block.BlockFace.DOWN)
            else -> null
        }
        val plotRecord = soil?.let(farmBlockLedger::record)
        val orchardRecord = farmBlockLedger.orchardLeafRecord(block)
        val position = soil?.toFarmPlotPosition() ?: block.toFarmPlotPosition()
        val state = runtime?.state
        val tracking = buildList {
            if (runtime != null && position in farmBlockRegistry.beds(runtime.settings.id)) add("managed")
            if (state != null && position in state.preparationPatch) add("patch")
            if (state != null && position in state.droughtPlots) add("drought")
            if (state != null && position in state.droughtDamagedPlots) add("drought-damaged")
            if (state != null && state.pestNests.any { it.position == position }) add("pest-nest")
            if (state != null && state.pestDamagedCrops.any { it.position == position }) add("pest-damaged")
            if (state != null && position in state.specialIncident?.plots.orEmpty()) add("special-target")
            if (state != null && state.specialDamagedCrops.any { it.position == position }) add("special-damaged")
            if (orchardRecord != null) add("orchard")
        }
        sendChat(
            player,
            MessageKey.ADMIN_INSPECT_HEADER,
            mapOf(
                "world" to locale.text(block.world.name),
                "x" to locale.text(block.x),
                "y" to locale.text(block.y),
                "z" to locale.text(block.z),
            ),
        )
        sendChat(
            player,
            MessageKey.ADMIN_INSPECT_BLOCK,
            mapOf(
                "material" to locale.text(block.type.name),
                "data" to locale.text(block.blockData.asString.take(256)),
                "zone" to locale.text(runtime?.settings?.id ?: "—"),
            ),
        )
        fixed?.let { record ->
            val restore = record.restoreAt?.let { timestamp ->
                locale.renderPath(
                    "admin-inspect.restore.pending",
                    player,
                    mapOf("seconds" to locale.text(remainingSeconds(timestamp, clock()))),
                )
            } ?: locale.renderPath("admin-inspect.restore.ready", player)
            sendChat(
                player,
                MessageKey.ADMIN_INSPECT_FIXED,
                mapOf(
                    "zone" to locale.text(record.zoneId),
                    "original" to locale.text(record.originalBlockData.take(256)),
                    "restore" to restore,
                    "x" to locale.text(record.x),
                    "y" to locale.text(record.y),
                    "z" to locale.text(record.z),
                ),
            )
        }
        plotRecord?.let { record ->
            sendChat(
                player,
                MessageKey.ADMIN_INSPECT_PLOT,
                mapOf(
                    "zone" to locale.text(record.zoneId),
                    "soil" to locale.text(record.originalSoilData.take(256)),
                    "original" to locale.text(record.originalCropData?.take(256) ?: "—"),
                    "active" to locale.text(record.activeCropData?.take(256) ?: "—"),
                ),
            )
        }
        if (tracking.isNotEmpty()) {
            val rendered = tracking.map { kind ->
                Component.text("  • ").append(locale.renderPath("admin-inspect.tracking-kind.$kind", player))
            }
            sendChat(
                player,
                MessageKey.ADMIN_INSPECT_TRACKING,
                mapOf("tracking" to Component.join(JoinConfiguration.separator(Component.newline()), rendered)),
            )
        }
        if (fixed == null && plotRecord == null && orchardRecord == null && tracking.isEmpty()) {
            sendChat(player, MessageKey.ADMIN_INSPECT_NONE)
        }
        debug.event(
            "farm_admin_block_inspected",
            "player" to player.name,
            "zone" to runtime?.settings?.id,
            "block" to block.type,
            "fixed_record" to (fixed != null),
            "plot_record" to (plotRecord != null),
            "orchard_record" to (orchardRecord != null),
            "tracking" to tracking.joinToString(","),
            "x" to block.x,
            "y" to block.y,
            "z" to block.z,
        )
    }

    fun adminUnmanageSelection(player: Player, zoneId: String): Boolean {
        val runtime = farms.firstOrNull { it.settings.id == zoneId } ?: run {
            sendChat(player, MessageKey.ADMIN_ZONE_UNKNOWN, mapOf("zone" to locale.text(zoneId)))
            return false
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldEdit")) {
            sendChat(player, MessageKey.ADMIN_UNMANAGE_WORLD_EDIT_REQUIRED)
            return false
        }
        val selection = when (val result = WorldEditSelectionReader.current(player)) {
            WorldEditSelectionResult.PluginUnavailable -> {
                sendChat(player, MessageKey.ADMIN_UNMANAGE_WORLD_EDIT_REQUIRED)
                return false
            }
            WorldEditSelectionResult.Incomplete -> {
                sendChat(player, MessageKey.ADMIN_UNMANAGE_SELECTION_REQUIRED)
                return false
            }
            is WorldEditSelectionResult.Available -> result.selection
        }
        if (selection.world != runtime.region.world.name) {
            sendChat(
                player,
                MessageKey.ADMIN_UNMANAGE_WRONG_WORLD,
                mapOf("world" to locale.text(selection.world), "expected" to locale.text(runtime.region.world.name)),
            )
            return false
        }
        val tracked = linkedSetOf<FarmPlotPosition>().apply {
            addAll(farmBlockRegistry.beds(runtime.settings.id))
            addAll(runtime.state.preparationPatch)
            addAll(runtime.state.droughtPlots)
            addAll(runtime.state.droughtDamagedPlots)
            runtime.state.pestNests.mapTo(this) { it.position }
            runtime.state.pestDamagedCrops.mapTo(this) { it.position }
            runtime.state.specialIncident?.plots?.let(::addAll)
            runtime.state.specialDamagedCrops.mapTo(this) { it.position }
        }
        val selected = tracked.filterTo(linkedSetOf(), selection::contains)
        val selectedFixed = linkedSetOf<FarmFixedCropPosition>()
        val selectedLeaves = farmBlockRegistry.orchardLeaves(runtime.settings.id)
            .filterTo(linkedSetOf(), selection::contains)
        runtime.region.world.loadedChunks.asSequence()
            .flatMap { farmBlockLedger.fixedCropRecords(it).asSequence() }
            .filter { it.zoneId == runtime.settings.id }
            .filter { selection.contains(FarmPlotPosition(selection.world, it.x, it.y, it.z)) }
            .mapTo(selectedFixed) { FarmFixedCropPosition(selection.world, it.x, it.y, it.z) }
        fixedCropJournal.records().asSequence()
            .filter { it.zoneId == runtime.settings.id && it.world == selection.world }
            .filter { selection.contains(FarmPlotPosition(it.world, it.x, it.y, it.z)) }
            .mapTo(selectedFixed) { FarmFixedCropPosition(it.world, it.x, it.y, it.z) }
        if (selected.isEmpty() && selectedFixed.isEmpty() && selectedLeaves.isEmpty()) {
            sendChat(player, MessageKey.ADMIN_UNMANAGE_EMPTY)
            return false
        }

        val previous = runtime.state
        val removal = FarmAdminEdit.removePlots(previous, selected)
        runtime.state = removal.state
        try {
            persistBlocking()
        } catch (failure: Exception) {
            runtime.state = previous
            plugin.logger.log(Level.SEVERE, "Could not persist WorldEdit farm unmanage for ${runtime.settings.id}", failure)
            sendChat(player, MessageKey.GENERIC_ERROR)
            return false
        }

        var removedRecords = 0
        selected.forEach { position ->
            val world = Bukkit.getWorld(position.world) ?: return@forEach
            world.getChunkAt(position.x shr 4, position.z shr 4)
            if (farmBlockLedger.remove(world.getBlockAt(position.x, position.y, position.z))) removedRecords++
        }
        selectedFixed.forEach { position ->
            val block = runtime.region.world.getBlockAt(position.x, position.y, position.z)
            if (farmBlockLedger.removeFixedCrop(block)) removedRecords++
            if (fixedCropJournal.contains(positionKey(block.location))) {
                retireFixedCropJournal(positionKey(block.location), "admin_worldedit_unmanage")
                removedRecords++
            }
        }
        selectedLeaves.forEach { position ->
            val block = runtime.region.world.getBlockAt(position.x, position.y, position.z)
            if (farmBlockLedger.removeOrchardLeaf(block)) removedRecords++
        }
        farmBlockRegistry.removeBeds(runtime.settings.id, selected)
        farmBlockRegistry.removeOrchardLeaves(runtime.settings.id, selectedLeaves)
        removal.careTargetIds.forEach { targetId ->
            removeFarmCareEntities(CareEntityKey(runtime.settings.id, targetId), "admin_worldedit_unmanage")
        }
        selected.forEach { position ->
            removePestNestEntities(PestNestKey(runtime.settings.id, position), "admin_worldedit_unmanage")
        }
        if (removal.shiftRetired) {
            clearFarmCare(runtime, "admin_worldedit_unmanage")
            removePests(runtime, activePests(runtime), "admin_worldedit_unmanage")
            removePestNestEntities(runtime, "admin_worldedit_unmanage")
            clearDelivery(runtime, "admin_worldedit_unmanage")
        } else if (
            runtime.state.phase == FarmPhase.INCIDENT &&
            (runtime.state.incidentType ?: FarmIncidentType.PESTS) == FarmIncidentType.PESTS &&
            runtime.state.pestNests.isEmpty() && runtime.state.pestAlive == 0 &&
            currentOrder(runtime) != null
        ) {
            applyFarmResult(runtime, FarmShiftEngine.finishPestIncidentIfClear(runtime.state), null)
        }
        sendChat(
            player,
            MessageKey.ADMIN_UNMANAGE_DONE,
            mapOf(
                "plots" to locale.text(selected.size + selectedFixed.size + selectedLeaves.size),
                "records" to locale.text(removedRecords),
            ),
        )
        debug.event(
            "farm_admin_worldedit_unmanage",
            "player" to player.name,
            "zone" to runtime.settings.id,
            "selection_volume" to selection.volume,
            "plots" to selected.size,
            "fixed_crops" to selectedFixed.size,
            "orchard_leaves" to selectedLeaves.size,
            "records" to removedRecords,
            "shift_retired" to removal.shiftRetired,
        )
        return true
    }

    fun farmZoneIds(): List<String> = farms.map { it.settings.id }

    fun farmOrderIds(zoneId: String): List<String> = farms.firstOrNull { it.settings.id == zoneId }
        ?.orderList
        ?.map(FarmOrder::id)
        .orEmpty()

    fun adminFarmPoints(zoneId: String): Map<FarmPointKind, FarmPointPosition>? {
        val runtime = farms.firstOrNull { it.settings.id == zoneId } ?: return null
        return FarmPointKind.entries.mapNotNull { kind ->
            val configured = farmLocations.zones[runtime.settings.id]?.get(kind)
            val resolved = configured ?: when (kind) {
                FarmPointKind.HIVE,
                FarmPointKind.IRRIGATION,
                FarmPointKind.COVERS,
                FarmPointKind.SCARECROWS,
                FarmPointKind.PEN,
                -> careFixturePoint(runtime, kind)
                else -> point(runtime, kind)
            }
            resolved?.let { kind to it }
        }.toMap()
    }

    fun adminOverriddenFarmPoints(zoneId: String): Set<FarmPointKind>? {
        if (farms.none { it.settings.id == zoneId }) return null
        return farmLocations.zones[zoneId].orEmpty().keys
    }

    fun adminSetFarmPoint(player: Player, zoneId: String, kind: FarmPointKind): Boolean {
        val runtime = farms.firstOrNull { it.settings.id == zoneId } ?: run {
            sendChat(player, MessageKey.ADMIN_ZONE_UNKNOWN, mapOf("zone" to locale.text(zoneId)))
            return false
        }
        if (kind != FarmPointKind.TRAVEL && !runtime.region.contains(player.location)) {
            sendChat(player, MessageKey.ADMIN_POINT_OUTSIDE)
            return false
        }
        if (kind != FarmPointKind.TRAVEL) {
            val floor = player.location.block.getRelative(org.bukkit.block.BlockFace.DOWN)
            if (floor.type == Material.FARMLAND || player.location.block.type.name in runtime.settings.crops) {
                sendChat(player, MessageKey.ADMIN_POINT_ON_BED)
                return false
            }
        }
        val position = FarmPointPosition(
            player.world.name,
            player.location.x,
            player.location.y,
            player.location.z,
            player.location.yaw,
            player.location.pitch,
        )
        val previous = farmLocations
        val updatedPoints = farmLocations.zones[zoneId].orEmpty() + (kind to position)
        farmLocations = farmLocations.copy(zones = farmLocations.zones + (zoneId to updatedPoints))
        try {
            validateLocationOverrides()
            farmLocationRepository.saveBlocking(farmLocations)
        } catch (failure: Exception) {
            farmLocations = previous
            plugin.logger.log(Level.SEVERE, "Could not save farm point $zoneId/$kind", failure)
            sendChat(player, MessageKey.GENERIC_ERROR)
            return false
        }
        refreshFarmPoint(runtime, kind, player, "admin_point_changed")
        sendChat(
            player,
            MessageKey.ADMIN_POINT_SAVED,
            mapOf(
                "point" to locale.renderPath("admin.point.${kind.name.lowercase()}", player),
                "zone" to locale.text(zoneId),
            ),
        )
        debug.event(
            "farm_admin_point_saved",
            "player" to player.name,
            "zone" to zoneId,
            "point" to kind,
            "world" to position.world,
            "x" to position.x,
            "y" to position.y,
            "z" to position.z,
        )
        return true
    }

    fun adminClearFarmPoint(player: Player, zoneId: String, kind: FarmPointKind): Boolean {
        val runtime = farms.firstOrNull { it.settings.id == zoneId } ?: run {
            sendChat(player, MessageKey.ADMIN_ZONE_UNKNOWN, mapOf("zone" to locale.text(zoneId)))
            return false
        }
        val previous = farmLocations
        val updated = farmLocations.without(zoneId, kind)
        if (updated == previous) {
            sendChat(
                player,
                MessageKey.ADMIN_POINT_NOT_OVERRIDDEN,
                mapOf("point" to locale.renderPath("admin.point.${kind.name.lowercase()}", player)),
            )
            return false
        }
        farmLocations = updated
        try {
            validateLocationOverrides()
            farmLocationRepository.saveBlocking(farmLocations)
        } catch (failure: Exception) {
            farmLocations = previous
            plugin.logger.log(Level.SEVERE, "Could not clear farm point $zoneId/$kind", failure)
            sendChat(player, MessageKey.GENERIC_ERROR)
            return false
        }
        refreshFarmPoint(runtime, kind, player, "admin_point_cleared")
        sendChat(
            player,
            MessageKey.ADMIN_POINT_CLEARED,
            mapOf(
                "point" to locale.renderPath("admin.point.${kind.name.lowercase()}", player),
                "zone" to locale.text(zoneId),
            ),
        )
        debug.event(
            "farm_admin_point_cleared",
            "player" to player.name,
            "zone" to zoneId,
            "point" to kind,
        )
        return true
    }

    fun adminSetFarmStage(player: Player, zoneId: String, stage: String): Boolean {
        val runtime = farms.firstOrNull { it.settings.id == zoneId } ?: run {
            sendChat(player, MessageKey.ADMIN_ZONE_UNKNOWN, mapOf("zone" to locale.text(zoneId)))
            return false
        }
        val normalized = stage.lowercase()
        val careStages = mapOf(
            "seeder" to FarmCareType.SEEDER,
            "weeds" to FarmCareType.WEEDS,
            "irrigation" to FarmCareType.IRRIGATION,
            "pollination" to FarmCareType.POLLINATION,
            "covers" to FarmCareType.STORM_COVERS,
            "scarecrows" to FarmCareType.SCARECROWS,
            "animals" to FarmCareType.ANIMAL_RESCUE,
            "disease" to FarmCareType.DISEASE,
            "moles" to FarmCareType.MOLES,
            "apples" to FarmCareType.APPLE_HARVEST,
        )
        if (normalized !in setOf(
                "preparation", "planting", "harvesting", "pests", "drought", "giant-crop", "channels", "night-shift", "market",
                "delivery", "complete", "reset",
            ) && normalized !in careStages
        ) {
            sendChat(player, MessageKey.ADMIN_STAGE_UNKNOWN)
            return false
        }
        if (normalized == "reset") {
            if (!resetFarmForAdmin(runtime)) {
                sendChat(player, MessageKey.GENERIC_ERROR)
                return false
            }
            if (!setFarmOrderCyclePaused(zoneId, paused = true)) {
                sendChat(player, MessageKey.GENERIC_ERROR)
                return false
            }
            sendChat(player, MessageKey.ADMIN_STAGE_SET, mapOf("stage" to locale.renderPath("admin.stage.reset", player)))
            return true
        }
        adminPausedFarmZones -= zoneId
        if (normalized == "preparation") {
            if (!resetFarmForAdmin(runtime)) {
                sendChat(player, MessageKey.GENERIC_ERROR)
                return false
            }
            interactionCooldowns.remove("farm-patch-scan:${runtime.settings.id}")
            if (!tryStartFarmShift(runtime, player, clock())) return false
            sendChat(player, MessageKey.ADMIN_STAGE_SET, mapOf("stage" to locale.renderPath("admin.stage.preparation", player)))
            return true
        }
        if (!ensureAdminFarmShift(runtime, player)) return false
        clearTemporaryFarmWater("admin_stage")
        clearFarmCare(runtime, "admin_stage")
        runtime.state = runtime.state.copy(careType = null, seederStage = null, careTargets = emptyList())
        removePests(runtime, activePests(runtime), "admin_stage")
        removePestNestEntities(runtime, "admin_stage")
        specialIncidentScene.clearZone(runtime.settings.id, "admin_stage")
        nightShift.clearZone(runtime.settings.id)
        restoreIncidentCrops(runtime, runtime.settings.restoreBlocksPerTick)
        if (FarmIncidentRecovery.pending(runtime.state)) {
            sendChat(
                player,
                MessageKey.ADMIN_INCIDENT_RECOVERY_PENDING,
                mapOf(
                    "count" to locale.text(
                        runtime.state.droughtDamagedPlots.size + runtime.state.pestDamagedCrops.size +
                            runtime.state.specialDamagedCrops.size,
                    ),
                ),
            )
            return false
        }
        runtime.state = runtime.state.copy(specialIncident = null, specialDamagedCrops = emptyList())
        clearDelivery(runtime, "admin_stage")
        val order = currentOrder(runtime) ?: return false
        val nextCrop = nextRequiredCrop(runtime.state, order)?.key ?: order.required.keys.first()
        careStages[normalized]?.let { careType ->
            val seeder = careType == FarmCareType.SEEDER
            if (seeder) {
                releaseFarmPatch(runtime)
            } else {
                prepareAdminPatch(runtime, plant = true, mature = true)
            }
            runtime.state = runtime.state.copy(
                phase = if (seeder) FarmPhase.PREPARATION else FarmPhase.HARVESTING,
                preparationReleased = true,
                tilledPlots = if (seeder) emptySet() else runtime.state.preparationPatch.toSet(),
                plantedPlots = if (seeder) emptySet() else runtime.state.preparationPatch.toSet(),
                preparationProgress = if (seeder) 0 else runtime.state.preparationRequired,
                plantingProgress = if (seeder) 0 else runtime.state.preparationRequired,
                careType = null,
                careTargets = emptyList(),
                incidentType = null,
            )
            if (!initializeFarmCare(runtime, player, careType)) {
                sendChat(player, MessageKey.ADMIN_CARE_UNAVAILABLE)
                return false
            }
            persistBlocking()
            sendChat(player, MessageKey.ADMIN_STAGE_SET, mapOf("stage" to locale.renderPath("admin.stage.$normalized", player)))
            return true
        }
        val events: List<ShiftEvent>
        runtime.state = when (normalized) {
            "planting" -> {
                prepareAdminPatch(runtime, plant = false, mature = false)
                events = listOf(ShiftEvent.PLANTING_STARTED)
                runtime.state.copy(
                    phase = FarmPhase.PLANTING,
                    preparationReleased = true,
                    tilledPlots = runtime.state.preparationPatch.toSet(),
                    plantedPlots = emptySet(),
                    preparationProgress = runtime.state.preparationRequired,
                    plantingProgress = 0,
                    careType = null,
                    careTargets = emptyList(),
                    incidentType = null,
                    pestNestsInitialized = false,
                    pestNests = emptyList(),
                    pestAlive = 0,
                )
            }
            "harvesting" -> {
                prepareAdminPatch(runtime, plant = true, mature = true)
                events = emptyList()
                val resolvedIncidents = runtime.state.incidentsResolved +
                    if (runtime.state.phase == FarmPhase.INCIDENT) 1 else 0
                runtime.state.copy(
                    phase = FarmPhase.HARVESTING,
                    preparationReleased = true,
                    tilledPlots = runtime.state.preparationPatch.toSet(),
                    plantedPlots = runtime.state.preparationPatch.toSet(),
                    preparationProgress = runtime.state.preparationRequired,
                    plantingProgress = runtime.state.preparationRequired,
                    careType = null,
                    careTargets = emptyList(),
                    incidentCrop = null,
                    incidentType = null,
                    incidentProgress = 0,
                    incidentRequired = 0,
                    incidentResolved = resolvedIncidents > 0,
                    incidentsResolved = resolvedIncidents.coerceAtMost(runtime.rules.incidentTargetCount(runtime.state.sequence)),
                    pestNestsInitialized = false,
                    pestNests = emptyList(),
                    pestAlive = 0,
                )
            }
            "pests", "drought", "giant-crop", "channels", "night-shift", "market" -> {
                prepareAdminPatch(runtime, plant = true, mature = true)
                val incidentType = when (normalized) {
                    "pests" -> FarmIncidentType.PESTS
                    "drought" -> FarmIncidentType.DROUGHT
                    "giant-crop" -> FarmIncidentType.GIANT_CROP
                    "channels" -> FarmIncidentType.CHANNELS
                    "night-shift" -> FarmIncidentType.NIGHT_SHIFT
                    "market" -> FarmIncidentType.MARKET
                    else -> error("unreachable")
                }
                val quota = if (incidentType == FarmIncidentType.DROUGHT) {
                    runtime.settings.droughtTargetBeds(discoverIncidentBeds(runtime).size.coerceAtLeast(runtime.state.preparationPatch.size))
                } else {
                    runtime.rules.incidentQuota
                }
                events = listOf(ShiftEvent.INCIDENT_STARTED)
                runtime.state.copy(
                    phase = FarmPhase.INCIDENT,
                    incidentCrop = nextCrop,
                    incidentType = incidentType,
                    incidentProgress = 0,
                    incidentRequired = quota,
                    incidentResolved = false,
                    droughtPlots = emptySet(),
                    droughtDamagedPlots = emptySet(),
                    pestNestsInitialized = false,
                    pestNests = emptyList(),
                    pestAlive = 0,
                    pestDamagedCrops = emptyList(),
                    specialIncident = null,
                    specialDamagedCrops = emptyList(),
                )
            }
            "delivery", "complete" -> {
                events = listOf(ShiftEvent.DELIVERY_STARTED)
                runtime.state.copy(
                    phase = FarmPhase.DELIVERY,
                    progress = order.required,
                    harvestCheckpoint = 10,
                    harvestMilestone = 4,
                    incidentCrop = null,
                    incidentType = null,
                    incidentProgress = 0,
                    incidentRequired = 0,
                    pestNestsInitialized = false,
                    pestNests = emptyList(),
                    pestAlive = 0,
                    deliveryPosition = selectDeliveryAnchor(runtime, player.location),
                    deliveredCrates = emptySet(),
                )
            }
            else -> {
                sendChat(player, MessageKey.ADMIN_STAGE_UNKNOWN)
                return false
            }
        }
        applyFarmResult(runtime, EngineResult(runtime.state, true, events = events), player)
        if (normalized == "complete") {
            var state = runtime.state
            var final: EngineResult<FarmShiftState>? = null
            repeat(runtime.settings.delivery.crates) { index ->
                final = FarmShiftEngine.deliver(
                    state,
                    runtime.rules,
                    index,
                    runtime.settings.delivery.crates,
                    player.uniqueId,
                    clock(),
                )
                state = requireNotNull(final).state
            }
            applyFarmResult(runtime, requireNotNull(final), player)
        }
        persistBlocking()
        sendChat(player, MessageKey.ADMIN_STAGE_SET, mapOf("stage" to locale.renderPath("admin.stage.$normalized", player)))
        return true
    }

    fun adminStopFarmOrderCycle(player: Player, zoneId: String): Boolean {
        if (farms.none { it.settings.id == zoneId }) {
            sendChat(player, MessageKey.ADMIN_ZONE_UNKNOWN, mapOf("zone" to locale.text(zoneId)))
            return false
        }
        if (!setFarmOrderCyclePaused(zoneId, paused = true)) {
            sendChat(player, MessageKey.GENERIC_ERROR)
            return false
        }
        sendChat(player, MessageKey.ADMIN_ORDER_CYCLE_STOPPED)
        debug.event("farm_admin_order_cycle", "player" to player.name, "zone" to zoneId, "paused" to true)
        return true
    }

    fun adminStartFarmOrderCycle(player: Player, zoneId: String): Boolean {
        val runtime = farms.firstOrNull { it.settings.id == zoneId } ?: run {
            sendChat(player, MessageKey.ADMIN_ZONE_UNKNOWN, mapOf("zone" to locale.text(zoneId)))
            return false
        }
        if (!setFarmOrderCyclePaused(zoneId, paused = false)) {
            sendChat(player, MessageKey.GENERIC_ERROR)
            return false
        }
        if (runtime.state.phase == FarmPhase.IDLE && runtime.region.contains(player.location)) {
            interactionCooldowns.remove("farm-patch-scan:${runtime.settings.id}")
            tryStartFarmShift(runtime, player, clock())
        }
        sendChat(player, MessageKey.ADMIN_ORDER_CYCLE_STARTED)
        debug.event("farm_admin_order_cycle", "player" to player.name, "zone" to zoneId, "paused" to false)
        return true
    }

    private fun setFarmOrderCyclePaused(zoneId: String, paused: Boolean): Boolean {
        val changed = if (paused) adminPausedFarmZones.add(zoneId) else adminPausedFarmZones.remove(zoneId)
        if (!changed) return true
        return runCatching { persistBlocking() }.fold(
            onSuccess = { true },
            onFailure = { failure ->
                if (paused) adminPausedFarmZones.remove(zoneId) else adminPausedFarmZones.add(zoneId)
                plugin.logger.log(Level.SEVERE, "Could not persist farm order cycle state for $zoneId paused=$paused", failure)
                false
            },
        )
    }

    fun adminAdvanceFarm(player: Player, zoneId: String): Boolean {
        val runtime = farms.firstOrNull { it.settings.id == zoneId } ?: run {
            sendChat(player, MessageKey.ADMIN_ZONE_UNKNOWN, mapOf("zone" to locale.text(zoneId)))
            return false
        }
        val next = when (runtime.state.phase) {
            FarmPhase.IDLE, FarmPhase.COOLDOWN -> "preparation"
            FarmPhase.PREPARATION -> "planting"
            FarmPhase.PLANTING -> currentOrder(runtime)?.careTypes?.let { careTypes ->
                careTypes[java.lang.Math.floorMod(runtime.state.sequence.toInt(), careTypes.size)].adminStageName()
            } ?: "harvesting"
            FarmPhase.CARE -> "harvesting"
            FarmPhase.HARVESTING -> currentOrder(runtime)?.let { order ->
                nextFarmIncident(runtime, order).specialId()
            } ?: "pests"
            FarmPhase.INCIDENT -> "harvesting"
            FarmPhase.DELIVERY -> "complete"
        }
        return adminSetFarmStage(player, zoneId, next)
    }

    fun adminDebugFarmStatus(player: Player, zoneId: String): Boolean {
        val runtime = farms.firstOrNull { it.settings.id == zoneId } ?: run {
            sendChat(player, MessageKey.ADMIN_ZONE_UNKNOWN, mapOf("zone" to locale.text(zoneId)))
            return false
        }
        val state = runtime.state
        val order = currentOrder(runtime)
        val tracker = waterFlows[zoneId]
        val incident = when (state.incidentType) {
            FarmIncidentType.PESTS -> locale.renderPath("admin.stage.pests", player)
            FarmIncidentType.GIANT_CROP -> locale.renderPath("admin.stage.giant-crop", player)
            FarmIncidentType.CHANNELS -> locale.renderPath("admin.stage.channels", player)
            FarmIncidentType.NIGHT_SHIFT -> locale.renderPath("admin.stage.night-shift", player)
            FarmIncidentType.MARKET -> locale.renderPath("admin.stage.market", player)
            FarmIncidentType.DROUGHT -> locale.renderPath("admin.stage.drought", player)
            null -> locale.text("—")
        }
        sendChat(player, MessageKey.ADMIN_DEBUG_HEADER, mapOf("zone" to locale.text(zoneId)))
        sendChat(
            player,
            MessageKey.ADMIN_DEBUG_SHIFT,
            mapOf(
                "phase" to locale.renderPath("phase.farm.${state.phase.name.lowercase()}", player),
                "sequence" to locale.text(state.sequence),
                "order" to (state.orderId?.let { locale.renderPath("order.farm.$it", player) } ?: locale.text("—")),
                "done" to locale.text(order?.let(state::completed) ?: 0),
                "total" to locale.text(order?.totalRequired ?: 0),
                "rarity" to locale.text(order?.rarity?.name?.lowercase() ?: "—"),
                "customer" to (order?.let {
                    locale.renderPath("customer.${it.customerType.name.lowercase()}.name", player)
                } ?: locale.text("—")),
                "cart" to locale.text(
                    state.deliveredCrates.size * 100 / (runtime.settings.delivery.crates.coerceAtLeast(1)),
                ),
            ),
        )
        sendChat(
            player,
            MessageKey.ADMIN_DEBUG_PATCH,
            mapOf(
                "size" to locale.text(state.preparationRequired),
                "tilled" to locale.text(state.preparationProgress),
                "planted" to locale.text(state.plantingProgress),
                "damaged" to locale.text(state.droughtDamagedPlots.size + state.pestDamagedCrops.size),
            ),
        )
        sendChat(
            player,
            MessageKey.ADMIN_DEBUG_INCIDENT,
            mapOf(
                "incident" to incident,
                "dry" to locale.text(state.droughtPlots.size),
                "flows" to locale.text(tracker?.activeFlowCount() ?: 0),
                "water" to locale.text(tracker?.trackedBlockCount() ?: 0),
                "nests" to locale.text(state.pestNests.size),
                "spawned" to locale.text(state.pestNests.sumOf(FarmPestNest::spawned)),
                "alive" to locale.text(state.pestAlive),
                "entities" to locale.text(activePests(runtime).size),
            ),
        )
        sendChat(
            player,
            MessageKey.ADMIN_DEBUG_CARE,
            mapOf(
                "care" to (state.careType?.let { locale.renderPath("care.${it.name.lowercase()}.name", player) } ?: locale.text("—")),
                "done" to locale.text(if (state.careType == FarmCareType.SEEDER) {
                    if (state.seederStage() == FarmSeederStage.TILLING) state.preparationProgress else state.plantingProgress
                } else {
                    state.careProgress()
                }),
                "total" to locale.text(if (state.careType == FarmCareType.SEEDER) state.preparationRequired else state.careRequired()),
                "entities" to locale.text(careEntities.keys.count { it.zoneId == zoneId }),
            ),
        )
        sendChat(
            player,
            MessageKey.ADMIN_DEBUG_DELIVERY,
            mapOf(
                "done" to locale.text(state.deliveredCrates.size),
                "total" to locale.text(runtime.settings.delivery.crates),
                "carriers" to locale.text(deliveryCarriers.keys.count { it.zoneId == zoneId }),
            ),
        )
        debug.event(
            "farm_admin_debug_status",
            "player" to player.name,
            "zone" to zoneId,
            "phase" to state.phase,
            "sequence" to state.sequence,
            "order" to state.orderId,
            "progress" to order?.let { "${state.completed(it)}/${it.totalRequired}" },
            "patch" to state.preparationRequired,
            "drought" to state.droughtPlots.size,
            "nests" to state.pestNests.size,
            "pests" to state.pestAlive,
            "crates" to "${state.deliveredCrates.size}/${runtime.settings.delivery.crates}",
        )
        return true
    }

    fun adminSetFarmContract(player: Player, zoneId: String, orderId: String): Boolean {
        val runtime = farms.firstOrNull { it.settings.id == zoneId } ?: run {
            sendChat(player, MessageKey.ADMIN_ZONE_UNKNOWN, mapOf("zone" to locale.text(zoneId)))
            return false
        }
        val order = runtime.orders[orderId] ?: run {
            sendChat(player, MessageKey.ADMIN_DEBUG_CONTRACT_UNKNOWN, mapOf("order" to locale.text(orderId)))
            return false
        }
        if (adminEditPlayers.any { it != player.uniqueId }) {
            sendChat(player, MessageKey.GENERIC_ERROR)
            debug.event("farm_admin_contract_blocked", "zone" to zoneId, "reason" to "another_editor")
            return false
        }
        if (adminEditPlayers.remove(player.uniqueId)) sendActionBar(player, MessageKey.ADMIN_EDIT_DISABLED)
        if (!resetFarmForAdmin(runtime)) {
            sendChat(player, MessageKey.GENERIC_ERROR)
            return false
        }
        adminPausedFarmZones -= runtime.settings.id
        interactionCooldowns.remove("farm-patch-scan:${runtime.settings.id}")
        if (!tryStartFarmShift(runtime, player, clock(), order)) return false
        sendChat(
            player,
            MessageKey.ADMIN_DEBUG_CONTRACT_SET,
            mapOf("order" to locale.renderPath("order.farm.${order.id}", player)),
        )
        return true
    }

    fun adminGiveFarmSupply(player: Player, zoneId: String, rawKind: String): Boolean {
        val runtime = farms.firstOrNull { it.settings.id == zoneId } ?: run {
            sendChat(player, MessageKey.ADMIN_ZONE_UNKNOWN, mapOf("zone" to locale.text(zoneId)))
            return false
        }
        val kind = FarmSupplyKind.entries.firstOrNull { it.name.equals(rawKind, ignoreCase = true) } ?: return false
        if (!giveFarmSupply(runtime, kind, player)) {
            sendChat(player, MessageKey.ADMIN_DEBUG_INVENTORY_FULL)
            return true
        }
        sendChat(
            player,
            MessageKey.ADMIN_DEBUG_SUPPLY_GIVEN,
            mapOf("supply" to locale.renderPath("admin.point.${kind.name.lowercase()}", player)),
        )
        return true
    }

    fun adminShowFarmGuidance(player: Player, zoneId: String): Boolean {
        if (farms.none { it.settings.id == zoneId }) {
            sendChat(player, MessageKey.ADMIN_ZONE_UNKNOWN, mapOf("zone" to locale.text(zoneId)))
            return false
        }
        showDebugFarmGuidance(player.uniqueId, zoneId, 10)
        sendChat(player, MessageKey.ADMIN_DEBUG_HIGHLIGHTED)
        debug.event("farm_admin_debug_highlight", "player" to player.name, "zone" to zoneId)
        return true
    }

    private fun ensureAdminFarmShift(runtime: FarmRuntime, player: Player): Boolean {
        adminPausedFarmZones -= runtime.settings.id
        if (runtime.state.phase !in setOf(FarmPhase.IDLE, FarmPhase.COOLDOWN) && runtime.state.preparationPatch.isNotEmpty()) {
            return true
        }
        if (!resetFarmForAdmin(runtime)) {
            sendChat(player, MessageKey.GENERIC_ERROR)
            return false
        }
        interactionCooldowns.remove("farm-patch-scan:${runtime.settings.id}")
        return tryStartFarmShift(runtime, player, clock())
    }

    private fun resetFarmForAdmin(runtime: FarmRuntime): Boolean {
        clearTemporaryFarmWater("admin_reset")
        clearFarmCare(runtime, "admin_reset")
        removePests(runtime, activePests(runtime), "admin_reset")
        removePestNestEntities(runtime, "admin_reset")
        clearDelivery(runtime, "admin_reset")
        clearFarmContractScene(runtime, "admin_reset")
        restoreIncidentCrops(runtime)
        if (FarmIncidentRecovery.pending(runtime.state)) return false
        if (runtime.state.preparationPatch.isNotEmpty() && !restoreFarmPatchOriginal(runtime)) return false
        commitFarmStateAfterPatchRecovery(runtime, FarmShiftState(sequence = runtime.state.sequence))
        pendingIncidentRestore.remove(runtime.settings.id)
        droughtGrowth.remove(runtime.settings.id)
        players(runtime.region).forEach { removeFarmServiceItems(it, runtime.settings.id, "admin_reset") }
        return true
    }

    private fun prepareAdminPatch(runtime: FarmRuntime, plant: Boolean, mature: Boolean) {
        val crop = MaterialRules.material(requireNotNull(runtime.state.preparationCrop))
        runtime.state.preparationPatch.forEach { position ->
            val soil = position.block() ?: return@forEach
            farmBlockLedger.capture(soil, runtime.settings.id)
            setWetFarmland(soil)
            val above = soil.getRelative(org.bukkit.block.BlockFace.UP)
            if (!plant) {
                above.setType(Material.AIR, false)
                return@forEach
            }
            val data = crop.createBlockData()
            if (mature && data is Ageable) data.age = data.maximumAge
            above.setBlockData(data, false)
            farmBlockLedger.captureActiveCrop(soil, runtime.settings.id)
        }
    }

    fun onDrop(event: PlayerDropItemEvent) {
        if (isFarmServiceItem(event.itemDrop.itemStack)) event.isCancelled = true
    }

    fun onDeath(event: PlayerDeathEvent) {
        event.drops.removeIf(::isFarmServiceItem)
        removeFarmServiceItems(event.entity, reason = "player_death")
    }

    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        marketMenu.handleClick(event)?.let { click ->
            handleFarmMarketDecision(player, click)
            return
        }
        val hotbar = if (event.click == ClickType.SWAP_OFFHAND) {
            player.inventory.itemInOffHand
        } else {
            event.hotbarButton.takeIf { it >= 0 }?.let(player.inventory::getItem)
        }
        if (
            FarmServiceInventoryPolicy.cancelClick(
                playerCraftingView = event.view.topInventory.type == InventoryType.CRAFTING,
                rawSlot = event.rawSlot,
                topSize = event.view.topInventory.size,
                shiftClick = event.isShiftClick,
                currentTagged = isFarmServiceItem(event.currentItem),
                cursorTagged = isFarmServiceItem(event.cursor),
                hotbarTagged = isFarmServiceItem(hotbar),
            )
        ) event.isCancelled = true
    }

    fun onInventoryDrag(event: InventoryDragEvent) {
        if (marketMenu.handleDrag(event)) return
        if (FarmServiceInventoryPolicy.cancelDrag(isFarmServiceItem(event.oldCursor), event.rawSlots, event.view.topInventory.size)) {
            event.isCancelled = true
        }
    }

    fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity
        val careZone = entity.persistentDataContainer.get(careZoneKey, PersistentDataType.STRING)
        if (careZone != null) {
            event.drops.clear()
            event.droppedExp = 0
            val targetId = entity.persistentDataContainer.get(careTargetKey, PersistentDataType.INTEGER)
            if (targetId != null) careEntities[CareEntityKey(careZone, targetId)]?.remove(entity.uniqueId)
            return
        }
        val zoneId = entity.persistentDataContainer.get(pestZoneKey, PersistentDataType.STRING) ?: return
        val sequence = entity.persistentDataContainer.get(pestSequenceKey, PersistentDataType.LONG) ?: return
        pestEntities[zoneId]?.remove(entity.uniqueId)
        event.drops.clear()
        event.droppedExp = 0
        val runtime = farms.firstOrNull { it.settings.id == zoneId }
        val killer = entity.killer
        if (
            runtime == null || runtime.state.phase != FarmPhase.INCIDENT ||
            (runtime.state.incidentType ?: FarmIncidentType.PESTS) != FarmIncidentType.PESTS ||
            runtime.state.sequence != sequence || killer == null ||
            !hasAccess(killer, runtime.settings.permission) || !runtime.region.contains(entity.location)
        ) {
            debug.event(
                "farm_pest_death_ignored",
                "zone" to zoneId,
                "sequence" to sequence,
                "killer" to killer?.name,
                "reason" to "inactive_or_environment",
            )
            return
        }
        currentOrder(runtime) ?: return
        debug.event("farm_pest_killed", "zone" to zoneId, "sequence" to sequence, "player" to killer.name, "entity" to entity.type)
        applyFarmResult(runtime, FarmShiftEngine.defeatPest(runtime.state, killer.uniqueId), killer)
    }

    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        if (
            event.entity.persistentDataContainer.has(pestZoneKey, PersistentDataType.STRING) ||
            event.entity.persistentDataContainer.has(careZoneKey, PersistentDataType.STRING)
        ) {
            event.isCancelled = true
            return
        }
        if (event.block.type == Material.FARMLAND && farmAt(event.block.location) != null) event.isCancelled = true
    }

    fun onBlockFade(event: BlockFadeEvent) {
        if (event.block.type == Material.FARMLAND && farmAt(event.block.location) != null) event.isCancelled = true
    }

    fun onBlockSpread(event: BlockSpreadEvent) {
        if (
            FarmGroundSpreadPolicy.blocks(
                insideFarm = farmAt(event.block.location) != null,
                source = event.source.type,
                result = event.newState.type,
            )
        ) {
            event.isCancelled = true
            debug.event(
                "farm_soil_spread_cancelled",
                "source" to event.source.type,
                "result" to event.newState.type,
                "x" to event.block.x,
                "y" to event.block.y,
                "z" to event.block.z,
            )
        }
    }

    fun onBlockGrow(event: BlockGrowEvent) {
        if (!MaterialRules.isFixedBlockCrop(event.newState.type)) return
        val runtime = farmAt(event.block.location) ?: return
        event.isCancelled = true
        debug.event(
            "farm_fixed_crop_natural_growth_cancelled",
            "zone" to runtime.settings.id,
            "crop" to event.newState.type,
            "x" to event.block.x,
            "y" to event.block.y,
            "z" to event.block.z,
        )
    }

    fun onBlockPlace(event: BlockPlaceEvent) {
        if (event.player.uniqueId in adminEditPlayers) {
            if (farmAt(event.blockPlaced.location) != null) {
                farmBlockLedger.removeFixedCrop(event.blockPlaced)
                val fixedPositionKey = positionKey(event.blockPlaced.location)
                if (fixedCropJournal.contains(fixedPositionKey)) {
                    retireFixedCropJournal(fixedPositionKey, "admin_edit_place")
                }
                event.isCancelled = false
            }
            return
        }
        val soil = event.blockPlaced.getRelative(org.bukkit.block.BlockFace.DOWN)
        val runtime = farmAt(soil.location) ?: return
        if (soil.toFarmPlotPosition() !in runtime.state.preparationPatch) return
        event.isCancelled = true
        sendActionBar(event.player, MessageKey.FARM_PATCH_PROTECTED)
        debug.event(
            "farm_patch_place_rejected",
            "player" to event.player.name,
            "zone" to runtime.settings.id,
            "block" to event.blockPlaced.type,
            "x" to event.blockPlaced.x,
            "y" to event.blockPlaced.y,
            "z" to event.blockPlaced.z,
        )
    }

    fun statuses(): List<ActivityStatus> = buildList {
        farms.forEach { runtime ->
            val order = currentOrder(runtime)
            val done = order?.let(runtime.state::completed) ?: 0
            val total = order?.totalRequired ?: 0
            val progress = when (runtime.state.phase) {
                FarmPhase.PREPARATION -> "${runtime.state.preparationProgress}/${runtime.state.preparationRequired}"
                FarmPhase.PLANTING -> "${runtime.state.plantingProgress}/${runtime.state.preparationRequired}"
                FarmPhase.CARE -> if (runtime.state.careType == FarmCareType.SEEDER) {
                    "${runtime.state.plantingProgress}/${runtime.state.preparationRequired}"
                } else {
                    "${runtime.state.careProgress()}/${runtime.state.careRequired()}"
                }
                FarmPhase.INCIDENT -> "${runtime.state.incidentProgress}/${runtime.state.incidentRequired}"
                FarmPhase.DELIVERY -> "${runtime.state.deliveredCrates.size}/${runtime.settings.delivery.crates}"
                else -> "$done/$total"
            }
            add(ActivityStatus(ActivityKind.FARM, runtime.settings.id, "phase.farm.${runtime.state.phase.name.lowercase()}", progress))
        }
        lumbermills.forEach { runtime ->
            val progress = if (runtime.state.phase == LumberPhase.PROCESSING) {
                "${runtime.state.processed}/${runtime.rules.processingQuota}"
            } else {
                "${runtime.state.felled}/${runtime.rules.fellingQuota}"
            }
            add(ActivityStatus(ActivityKind.LUMBER, runtime.settings.id, "phase.lumber.${runtime.state.phase.name.lowercase()}", progress))
        }
        mines.forEach { runtime ->
            add(ActivityStatus(ActivityKind.MINE, runtime.settings.id, "phase.mine.${runtime.state.phase.name.lowercase()}", "${runtime.state.cart}/${runtime.rules.cartQuota}"))
        }
    }

    fun playerStats(playerId: UUID): PlayerActivityStats = stats.player(playerId)

    fun leaderboard(kind: ActivityKind, limit: Int = 10): List<Pair<UUID, Long>> = stats.leaderboard(kind, limit)

    fun leaderboardRank(playerId: UUID): Int? = stats.farmRank(playerId)

    fun weeklyLeaderboard(kind: ActivityKind, limit: Int = 10): List<Pair<UUID, Long>> =
        stats.weeklyLeaderboard(kind, limit)

    fun weeklyLeaderboardRank(playerId: UUID): Int? = stats.farmWeeklyRank(playerId)

    fun weeklyContribution(playerId: UUID, kind: ActivityKind): Long = stats.weeklyContribution(playerId, kind)

    fun farmScoreboardActive(playerId: UUID): Boolean = farmScoreboards.active(playerId)

    fun farmScoreboardTitle(playerId: UUID): String = farmScoreboards.tabTitle(playerId)

    fun farmScoreboardLine(playerId: UUID, line: Int): String = farmScoreboards.tabLine(playerId, line)

    fun onChunkLoad(chunk: org.bukkit.Chunk) {
        contractScene.onChunkLoad(chunk)
        specialIncidentScene.onChunkLoad(chunk)
        farms.asSequence().filter { it.region.world == chunk.world }.forEach { runtime ->
            farmBlockRegistry.reconcileChunk(farmBlockIndexDefinition(runtime), chunk)
        }
        reconcileFixedCrops(chunk)
        var removed = 0
        chunk.entities.filter { entity ->
            val data = entity.persistentDataContainer
            data.has(pestZoneKey, PersistentDataType.STRING) ||
                data.has(pestNestZoneKey, PersistentDataType.STRING) ||
                data.has(deliveryZoneKey, PersistentDataType.STRING) ||
                data.has(supplyZoneKey, PersistentDataType.STRING) ||
                data.has(careZoneKey, PersistentDataType.STRING)
        }.forEach { entity ->
            entity.remove()
            removed++
        }
        if (removed > 0) {
            debug.event(
                "farm_transient_entities_reconciled",
                "world" to chunk.world.name,
                "chunk" to "${chunk.x},${chunk.z}",
                "removed" to removed,
                "reason" to "chunk_load",
            )
        }
    }

    private fun reconcileLoadedFixedCrops() {
        Bukkit.getWorlds().asSequence()
            .flatMap { it.loadedChunks.asSequence() }
            .forEach(::reconcileFixedCrops)
    }

    private fun reconcileLoadedFarmBlockIndexes() {
        farms.forEach { runtime ->
            runtime.region.world.loadedChunks.forEach { chunk ->
                farmBlockRegistry.reconcileChunk(farmBlockIndexDefinition(runtime), chunk)
            }
        }
    }

    private fun farmBlockIndexDefinition(runtime: FarmRuntime): FarmBlockIndexDefinition = FarmBlockIndexDefinition(
        zoneId = runtime.settings.id,
        region = runtime.region,
        crops = runtime.settings.crops,
        blocksPerTick = runtime.settings.blockReindexBlocksPerTick,
        maxBlocks = runtime.settings.blockReindexMaxBlocks,
        maxOrchardLeaves = runtime.settings.appleLeafIndexLimit,
    )

    private fun reconcileFixedCrops(chunk: org.bukkit.Chunk) {
        val now = clock()
        farmBlockLedger.fixedCropRecords(chunk).forEach { record ->
            val runtime = farms.firstOrNull { it.settings.id == record.zoneId && it.region.world == chunk.world }
                ?: return@forEach
            val block = chunk.world.getBlockAt(record.x, record.y, record.z)
            if (!runtime.region.contains(block.location)) return@forEach
            val original = runCatching { Bukkit.createBlockData(record.originalBlockData) }.getOrNull()
                ?: return@forEach
            if (!MaterialRules.isFixedBlockCrop(original.material) || original.material.name !in runtime.settings.crops) {
                return@forEach
            }
            val restoreAt = when {
                record.restoreAt != null -> record.restoreAt
                block.type.isAir || block.isReplaceable -> now
                else -> null
            } ?: return@forEach
            if (record.restoreAt == null) farmBlockLedger.scheduleExistingFixedCropRestore(block, restoreAt)
            fixedCropRestoreQueue.schedule(FarmFixedCropRestore(record.position(chunk.world.name), restoreAt))
        }
        fixedCropJournal.records().asSequence().filter { pending ->
            pending.world == chunk.world.name && (pending.x shr 4) == chunk.x && (pending.z shr 4) == chunk.z
        }.forEach { pending ->
            val runtime = farms.firstOrNull { it.settings.id == pending.zoneId && it.region.world == chunk.world }
                ?: return@forEach
            val block = chunk.world.getBlockAt(pending.x, pending.y, pending.z)
            if (!runtime.region.contains(block.location)) return@forEach
            val original = runCatching { Bukkit.createBlockData(pending.originalBlockData) }.getOrNull()
                ?: return@forEach
            if (!MaterialRules.isFixedBlockCrop(original.material) || original.material.name !in runtime.settings.crops) {
                return@forEach
            }
            if (block.type == original.material) {
                farmBlockLedger.reconcileFixedCrop(block, pending.zoneId, pending.originalBlockData, null)
                retireFixedCropJournal(pending.positionKey, "block_already_present")
                return@forEach
            }
            farmBlockLedger.reconcileFixedCrop(
                block,
                pending.zoneId,
                pending.originalBlockData,
                pending.restoreAt,
            )
            fixedCropRestoreQueue.schedule(
                FarmFixedCropRestore(
                    FarmFixedCropPosition(pending.world, pending.x, pending.y, pending.z),
                    pending.restoreAt,
                ),
            )
        }
    }

    private fun processFarmBlockRestores() {
        val now = clock()
        val fixedLimit = farms.maxOfOrNull { it.settings.restoreBlocksPerTick } ?: 1
        fixedCropRestoreQueue.pollDue(now, fixedLimit).forEach { entry ->
            restoreFixedCrop(entry, now)
        }
        farms.forEach { runtime ->
            if (isAdminEditingFarm(runtime)) return@forEach
            var remaining = runtime.settings.restoreBlocksPerTick
            if (runtime.state.phase != FarmPhase.INCIDENT && FarmIncidentRecovery.pending(runtime.state)) {
                val before = runtime.state.droughtDamagedPlots.size + runtime.state.pestDamagedCrops.size +
                    runtime.state.specialDamagedCrops.size
                restoreIncidentCrops(runtime, remaining)
                val after = runtime.state.droughtDamagedPlots.size + runtime.state.pestDamagedCrops.size +
                    runtime.state.specialDamagedCrops.size
                remaining -= (before - after).coerceAtLeast(0)
            }
            if (
                remaining > 0 && runtime.state.phase == FarmPhase.COOLDOWN &&
                !FarmIncidentRecovery.pending(runtime.state) && runtime.state.preparationPatch.isNotEmpty() &&
                restoreFarmPatchOriginal(runtime, remaining)
            ) {
                clearFarmPatchState(runtime)
                persistAsync()
            }
        }
    }

    private fun restoreFixedCrop(entry: FarmFixedCropRestore, now: Long) {
        val world = Bukkit.getWorld(entry.position.world) ?: return
        if (!world.isChunkLoaded(entry.position.x shr 4, entry.position.z shr 4)) return
        val block = world.getBlockAt(entry.position.x, entry.position.y, entry.position.z)
        val positionKey = positionKey(block.location)
        val pending = fixedCropJournal.record(positionKey)
        val record = farmBlockLedger.fixedCropRecord(block)
        val restoreAt = pending?.restoreAt ?: record?.restoreAt ?: return
        if (restoreAt > now) {
            fixedCropRestoreQueue.schedule(entry.copy(restoreAt = restoreAt))
            return
        }
        val zoneId = pending?.zoneId ?: requireNotNull(record).zoneId
        val originalData = pending?.originalBlockData ?: requireNotNull(record).originalBlockData
        val runtime = farms.firstOrNull { it.settings.id == zoneId && it.region.contains(block.location) } ?: return
        val original = runCatching { Bukkit.createBlockData(originalData) }.getOrElse { failure ->
            plugin.logger.log(Level.SEVERE, "Could not decode fixed crop at ${entry.position}", failure)
            return
        }
        if (!MaterialRules.isFixedBlockCrop(original.material) || original.material.name !in runtime.settings.crops) return
        when {
            block.type == original.material -> {
                farmBlockLedger.reconcileFixedCrop(block, zoneId, originalData, null)
                retireFixedCropJournal(positionKey, "block_present")
            }
            block.type.isAir || block.isReplaceable -> {
                block.setBlockData(original, false)
                farmBlockLedger.reconcileFixedCrop(block, zoneId, originalData, null)
                retireFixedCropJournal(positionKey, "restored")
                debug.event(
                    "farm_fixed_crop_restored",
                    "zone" to runtime.settings.id,
                    "crop" to original.material,
                    "x" to block.x,
                    "y" to block.y,
                    "z" to block.z,
                )
            }
            else -> {
                fixedCropRestoreQueue.schedule(entry.copy(restoreAt = now + 1_000L))
                if (allowInteraction("farm-fixed-crop-obstructed:${entry.position}", TimeUnit.MINUTES.toMillis(5))) {
                    plugin.logger.warning("Fixed crop recovery at ${entry.position} is obstructed by ${block.type}; record retained")
                }
            }
        }
    }

    private fun retireFixedCropJournal(positionKey: String, reason: String) {
        fixedCropJournal.remove(positionKey).whenComplete { _, failure ->
            if (failure != null) {
                plugin.logger.log(Level.SEVERE, "Could not retire fixed crop journal at $positionKey", failure)
                Tasks.scheduler.runSync {
                    fixedCropJournal.record(positionKey)?.let { pending ->
                        fixedCropRestoreQueue.schedule(
                            FarmFixedCropRestore(
                                FarmFixedCropPosition(pending.world, pending.x, pending.y, pending.z),
                                clock() + 1_000L,
                            ),
                        )
                    }
                }
            } else {
                debug.event("farm_fixed_crop_journal_retired", "position" to positionKey, "reason" to reason)
            }
        }
    }

    fun canNavigate(kind: ActivityKind): Boolean = kind.configKey in settings.destinations

    fun travel(player: Player, kind: ActivityKind) {
        if (!canAccess(player, kind)) {
            sendChat(player, MessageKey.ZONE_LOCKED)
            return
        }
        val destination = destination(kind)
        debug.event(
            "travel_requested",
            "player" to player.name,
            "activity" to kind,
            "from_server" to settings.serverId,
            "to_server" to destination.server,
            "world" to destination.world,
        )
        if (destination.server == settings.serverId) {
            teleportLocal(player, kind)
            return
        }
        sendActionBar(player, MessageKey.TRAVEL_PREPARING)
        network.createTravelTicket(player.uniqueId, kind, destination.server).whenComplete { created, failure ->
            if (!isOperational()) return@whenComplete
            Tasks.scheduler.runSync {
                if (!isOperational()) return@runSync
                if (!player.isOnline) return@runSync
                if (failure != null || created != true || !transfer.connect(player, destination.server)) {
                    debug.event(
                        "travel_transfer_failed",
                        "player" to player.name,
                        "activity" to kind,
                        "destination" to destination.server,
                        "reason" to (failure?.javaClass?.simpleName ?: "transfer_rejected"),
                    )
                    sendChat(player, MessageKey.TRAVEL_FAILED)
                    return@runSync
                }
                debug.event("travel_transfer_sent", "player" to player.name, "activity" to kind, "destination" to destination.server)
            }
        }
    }

    fun onJoin(player: Player) {
        removeFarmServiceItems(player, reason = "player_join")
        Tasks.scheduler.runLater(1L) {
            if (isOperational() && player.isOnline) {
                deliverPendingFarmRewards(player)
                syncFarmMusic(player, farmAt(player.location), clock())
            }
        }
        if (!settings.network.enabled) return
        network.claimTravelTicket(player.uniqueId, settings.serverId).whenComplete { ticket, failure ->
            if (!isOperational()) return@whenComplete
            Tasks.scheduler.runLater(1L) {
                if (!isOperational()) return@runLater
                if (!player.isOnline) return@runLater
                if (failure != null) {
                    debug.event("travel_claim_failed", "player" to player.name, "reason" to failure.javaClass.simpleName)
                    return@runLater
                }
                ticket?.let {
                    debug.event("travel_claimed", "player" to player.name, "activity" to it.activity, "server" to settings.serverId)
                    teleportLocal(player, it.activity)
                }
            }
        }
    }

    fun workday(): WorkdayState? = network.workday()

    fun isAvailable(kind: ActivityKind): Boolean = when (kind) {
        ActivityKind.FARM -> farms.isNotEmpty()
        ActivityKind.LUMBER -> lumbermills.isNotEmpty()
        ActivityKind.MINE -> mines.isNotEmpty()
    }

    fun canAccess(player: Player, kind: ActivityKind): Boolean = if (!isAvailable(kind)) {
        settings.network.enabled
    } else when (kind) {
        ActivityKind.FARM -> farms.any { hasAccess(player, it.settings.permission) }
        ActivityKind.LUMBER -> lumbermills.any { hasAccess(player, it.settings.permission) }
        ActivityKind.MINE -> mines.any { hasAccess(player, it.settings.permission) }
    }

    private fun teleportLocal(player: Player, kind: ActivityKind) {
        val destination = destination(kind)
        val world = Bukkit.getWorld(destination.world)
        if (world == null) {
            debug.event("travel_local_failed", "player" to player.name, "activity" to kind, "reason" to "world_unloaded")
            sendChat(player, MessageKey.TRAVEL_FAILED)
            return
        }
        val location = Location(world, destination.x, destination.y, destination.z, destination.yaw, destination.pitch)
        player.teleportAsync(location).whenComplete { success, failure ->
            if (!isOperational()) return@whenComplete
            Tasks.scheduler.runSync {
                if (!isOperational()) return@runSync
                if (!player.isOnline) return@runSync
                if (failure != null || success != true) {
                    debug.event(
                        "travel_local_failed",
                        "player" to player.name,
                        "activity" to kind,
                        "reason" to (failure?.javaClass?.simpleName ?: "teleport_rejected"),
                    )
                    sendChat(player, MessageKey.TRAVEL_FAILED)
                } else {
                    debug.event(
                        "travel_arrived",
                        "player" to player.name,
                        "activity" to kind,
                        "server" to destination.server,
                        "world" to destination.world,
                        "x" to destination.x,
                        "y" to destination.y,
                        "z" to destination.z,
                    )
                    sendActionBar(player, MessageKey.TRAVEL_ARRIVED)
                }
            }
        }
    }

    private fun rebuild(persisted: ArcFarmsState) {
        farms = settings.farms.map { configured ->
            val region = requireNotNull(regionGateway.resolve(configured.reference)) {
                "Farm zone ${configured.id} cannot resolve ${configured.reference}"
            }
            val orders = configured.orders.map { order ->
                FarmOrder(
                    id = order.id,
                    required = order.required,
                    rarity = order.rarity,
                    careTypes = order.careTypes,
                    incidentTypes = order.incidentTypes,
                    customerType = order.customerType,
                    cartLoadMaterial = order.cartLoadMaterial,
                    cartLoadCustomModelData = order.cartLoadCustomModelData,
                )
            }
            val orderMap = orders.associateBy(FarmOrder::id)
            val restored = persisted.farms[configured.id]?.let { state ->
                if (state.phase == FarmPhase.PREPARATION && state.preparationPatch.isEmpty()) {
                    state.copy(
                        phase = FarmPhase.HARVESTING,
                        preparationCrop = null,
                        preparationProgress = state.preparationRequired,
                    )
                } else {
                    state
                }
            } ?: FarmShiftState()
            FarmRuntime(
                configured,
                region,
                orderMap,
                orders,
                FarmRules(
                    incidentTriggerPercents = configured.incidentTriggerPercents,
                    incidentQuota = configured.incidentQuota,
                    cooldownMillis = settings.completedCooldownSeconds * 1000L,
                    droughtQuota = configured.droughtTargetBeds(configured.preparationPatchSize),
                    incidentCountMin = configured.incidentCountMin,
                    incidentCountMax = configured.incidentCountMax,
                ),
                restored,
            )
        }
        adminPausedFarmZones.retainAll(farms.mapTo(mutableSetOf()) { it.settings.id })
        lumbermills = settings.lumbermills.map { configured ->
            val region = requireNotNull(regionGateway.resolve(configured.reference)) {
                "Lumber zone ${configured.id} cannot resolve ${configured.reference}"
            }
            val station = requireNotNull(regionGateway.resolve(configured.station)) {
                "Lumber station ${configured.id} cannot resolve ${configured.station}"
            }
            val restored = persisted.lumbermills[configured.id] ?: LumberShiftState()
            LumberRuntime(
                configured,
                region,
                station,
                LumberRules(
                    configured.fellingQuota,
                    configured.processingQuota,
                    configured.processingPerUse,
                    settings.completedCooldownSeconds * 1000L,
                ),
                configured.stationMaterials.mapTo(mutableSetOf(), MaterialRules::material),
                restored,
            )
        }
        mines = settings.mines.map { configured ->
            val region = requireNotNull(regionGateway.resolve(configured.reference)) {
                "Mine zone ${configured.id} cannot resolve ${configured.reference}"
            }
            val restored = persisted.mines[configured.id] ?: MineShiftState()
            MineRuntime(
                configured,
                region,
                MineRules(
                    configured.cartQuota,
                    configured.hazardTrigger,
                    configured.supportsRequired,
                    settings.completedCooldownSeconds * 1000L,
                ),
                MaterialRules.material(configured.temporaryMaterial),
                MaterialRules.material(configured.baseMaterial),
                LinkedHashMap(configured.materialWeights.mapKeys { MaterialRules.material(it.key) }),
                restored,
            )
        }
    }

    private fun validatePersistedState(candidate: ArcFarmsConfig, persisted: ArcFarmsState) {
        persisted.pendingFarmRewards.forEach { reward ->
            reward.items.forEach { item -> MaterialRules.material(item.material) }
            require(reward.moneyCents == 0L || economy.available) {
                "Pending farm money reward ${reward.id} requires Vault and an economy provider"
            }
        }
        val farmZones = candidate.farms.associateBy(FarmZoneSettings::id)
        persisted.farms.forEach { (id, state) ->
            if (state.phase == FarmPhase.IDLE) return@forEach
            val zone = farmZones[id]
            if (
                zone == null && state.phase == FarmPhase.COOLDOWN && state.preparationPatch.isEmpty() &&
                !FarmIncidentRecovery.pending(state)
            ) return@forEach
            requireNotNull(zone) { "Persisted active farm zone $id is missing from config" }
            if (state.phase != FarmPhase.COOLDOWN) {
                val order = zone.orders.firstOrNull { it.id == state.orderId }
                require(order != null) { "Persisted active farm order $id/${state.orderId} is missing from config" }
                require(state.progress.keys == order.required.keys) { "Persisted farm order $id changed its crop set" }
                require(state.progress.all { (crop, amount) -> amount <= order.required.getValue(crop) }) {
                    "Persisted farm progress exceeds the configured order in $id"
                }
                require(state.preparationCrop == null || state.preparationCrop in order.required) {
                    "Persisted farm preparation crop ${state.preparationCrop} is missing from $id"
                }
                require(state.incidentCrop == null || state.incidentCrop in order.required) {
                    "Persisted farm incident crop ${state.incidentCrop} is missing from $id"
                }
            }
            val region = requireNotNull(regionGateway.resolve(zone.reference)) { "Persisted farm region $id cannot be resolved" }
            val managedPlots = buildList {
                addAll(state.preparationPatch)
                addAll(state.droughtPlots)
                addAll(state.droughtDamagedPlots)
                state.pestNests.mapTo(this) { it.position }
                state.pestDamagedCrops.mapTo(this) { it.position }
            }
            require(managedPlots.all { position ->
                position.world == region.world.name && position.location()?.let(region::contains) == true
            }) { "Persisted farm-managed block escaped region $id" }
            require(state.careTargets.all { target ->
                val position = target.position
                val location = Location(region.world, position.x, position.y, position.z)
                position.world == region.world.name && region.contains(location)
            }) { "Persisted farm care target escaped region $id" }
            require(state.pestDamagedCrops.all { it.crop in zone.crops }) {
                "Persisted farm pest damage contains an unknown crop in $id"
            }
            state.deliveryPosition?.let { position ->
                val location = Location(region.world, position.x, position.y, position.z)
                require(position.world == region.world.name && region.contains(location)) {
                    "Persisted farm delivery escaped region $id"
                }
            }
        }

        val lumberZones = candidate.lumbermills.associateBy(LumberZoneSettings::id)
        persisted.lumbermills.forEach { (id, state) ->
            if (state.phase in setOf(LumberPhase.IDLE, LumberPhase.COOLDOWN)) return@forEach
            val zone = requireNotNull(lumberZones[id]) { "Persisted active lumber zone $id is missing from config" }
            require(state.species in zone.species) { "Persisted lumber species ${state.species} is missing from $id" }
        }

        val mineIds = candidate.mines.mapTo(mutableSetOf(), MineZoneSettings::id)
        persisted.mines.filterValues { it.phase !in setOf(MinePhase.IDLE, MinePhase.COOLDOWN) }.keys.forEach { id ->
            require(id in mineIds) { "Persisted active mine zone $id is missing from config" }
        }
    }

    private fun reconcileFarmOrderProgress(candidate: ArcFarmsConfig, persisted: ArcFarmsState): ArcFarmsState {
        val zones = candidate.farms.associateBy(FarmZoneSettings::id)
        var changed = false
        val farms = persisted.farms.mapValues { (zoneId, state) ->
            val order = zones[zoneId]?.orders?.firstOrNull { it.id == state.orderId } ?: return@mapValues state
            val result = FarmOrderProgressReconciler.reconcile(state, order.required)
            changed = changed || result.changed
            result.state
        }
        return if (changed) persisted.copy(farms = farms) else persisted
    }

    private fun validateMineJournalMaterials() {
        mineJournal.records().forEach { record ->
            MaterialRules.material(record.originalMaterial)
            MaterialRules.material(record.temporaryMaterial)
            MaterialRules.material(record.nextMaterial)
        }
    }

    private fun validateReload(candidate: ArcFarmsConfig, snapshot: ArcFarmsState) {
        validatePersistedState(candidate, snapshot)
        val farmZones = candidate.farms.associateBy(FarmZoneSettings::id)
        snapshot.farms.filterValues {
            it.phase !in setOf(FarmPhase.IDLE, FarmPhase.COOLDOWN) || it.preparationPatch.isNotEmpty() ||
                FarmIncidentRecovery.pending(it)
        }.forEach { (id, state) ->
            val zone = requireNotNull(farmZones[id]) { "Cannot remove farm zone $id with pending world recovery" }
            if (state.phase != FarmPhase.COOLDOWN) {
                val order = zone.orders.firstOrNull { it.id == state.orderId }
                require(order != null) { "Cannot remove active farm order $id/${state.orderId} during reload" }
                require(state.preparationCrop == null || state.preparationCrop in order.required) {
                    "Cannot remove active farm preparation crop ${state.preparationCrop} from $id"
                }
            }
            if (state.preparationPatch.isNotEmpty()) {
                val region = requireNotNull(regionGateway.resolve(zone.reference)) {
                    "Cannot resolve active farm patch region $id during reload"
                }
                require(state.preparationPatch.all { position ->
                    position.world == region.world.name && region.contains(requireNotNull(position.location()))
                }) { "Cannot move or shrink active farm patch region $id during reload" }
            }
        }
        snapshot.lumbermills.filterValues { it.phase !in setOf(LumberPhase.IDLE, LumberPhase.COOLDOWN) }.forEach { (id, state) ->
            require(candidate.lumbermills.any { it.id == id && state.species in it.species }) {
                "Cannot remove active lumber zone/species $id during reload"
            }
        }
        val candidateMineIds = candidate.mines.mapTo(mutableSetOf(), MineZoneSettings::id)
        snapshot.mines.filterValues { it.phase !in setOf(MinePhase.IDLE, MinePhase.COOLDOWN) }.keys.forEach { id ->
            require(id in candidateMineIds) { "Cannot remove active mine $id during reload" }
        }
        mineJournal.records().forEach { record ->
            require(record.zoneId in candidateMineIds) { "Cannot remove mine ${record.zoneId} with pending block recovery" }
        }
    }

    private fun validateRuntime(candidate: ArcFarmsConfig) {
        if (candidate.menuBackground.enabled) MaterialRules.material(candidate.menuBackground.material)
        candidate.destinations.values.filter { it.server == candidate.serverId }.forEach { destination ->
            requireNotNull(Bukkit.getWorld(destination.world)) {
                "Destination world ${destination.world} is not loaded on ${candidate.serverId}"
            }
        }
        candidate.farms.forEach { zone ->
            require(!zone.rewards.requiresEconomy || economy.available) {
                "Farm zone ${zone.id} money reward requires Vault and an economy provider"
            }
            zone.rewards.items.forEach { reward ->
                val material = MaterialRules.material(reward.material)
                require(material.isItem) { "Farm zone ${zone.id} reward ${reward.id} must use an item material" }
            }
            require(zone.rewards.items.sumOf { reward ->
                val stackSize = MaterialRules.material(reward.material).maxStackSize
                (reward.amount + stackSize - 1) / stackSize
            } <= 54) { "Farm zone ${zone.id} fixed item rewards exceed 54 inventory stacks" }
            zone.rewards.randomBundles.entries.forEach { bundle ->
                bundle.items.forEach { reward ->
                    val material = MaterialRules.material(reward.material)
                    require(material.isItem) { "Farm zone ${zone.id} reward bundle ${bundle.id} must use item materials" }
                }
                require(bundle.items.sumOf { reward ->
                    val stackSize = MaterialRules.material(reward.material).maxStackSize
                    (reward.amount + stackSize - 1) / stackSize
                } <= 27) { "Farm zone ${zone.id} reward bundle ${bundle.id} exceeds 27 inventory stacks" }
            }
            val region = requireNotNull(regionGateway.resolve(zone.reference)) {
                "Farm zone ${zone.id} cannot resolve ${zone.reference}"
            }
            val deliveryWorld = requireNotNull(Bukkit.getWorld(zone.delivery.world)) {
                "Farm zone ${zone.id} delivery world ${zone.delivery.world} is not loaded"
            }
            val delivery = Location(deliveryWorld, zone.delivery.x, zone.delivery.y, zone.delivery.z)
            require(region.contains(delivery)) { "Farm zone ${zone.id} delivery point is outside ${region.label}" }
            listOf(zone.supplies.tool, zone.supplies.seeds, zone.supplies.water, zone.delivery.pickup).forEach { point ->
                val supplyWorld = requireNotNull(Bukkit.getWorld(point.world)) {
                    "Farm zone ${zone.id} supply world ${point.world} is not loaded"
                }
                require(region.contains(Location(supplyWorld, point.x, point.y, point.z))) {
                    "Farm zone ${zone.id} supply point is outside ${region.label}"
                }
            }
            require(MaterialRules.isHoe(ItemStack(MaterialRules.material(zone.supplies.toolMaterial)))) {
                "Farm zone ${zone.id} supplies.tool-material must be a hoe"
            }
            zone.careVisuals.values.forEach { MaterialRules.material(it.material) }
            zone.orders.forEach { order ->
                val load = MaterialRules.material(order.cartLoadMaterial)
                require(load.isItem) { "Farm order ${zone.id}/${order.id} cart load must use an item material" }
            }
            zone.careAnimalEntities.forEach { entityName ->
                val entityType = EntityType.valueOf(entityName)
                require(entityType.entityClass?.let(Mob::class.java::isAssignableFrom) == true) {
                    "Farm zone ${zone.id} care-animal-entities must contain mobs"
                }
            }
            MaterialRules.material(zone.delivery.itemMaterial)
            zone.crops.forEach { cropName ->
                val crop = MaterialRules.material(cropName)
                require(MaterialRules.isPlantableCrop(crop) || MaterialRules.isFixedBlockCrop(crop)) {
                    "Farm zone ${zone.id} crop $cropName is neither plantable nor a managed block crop"
                }
            }
            zone.orders.forEach { order ->
                require(order.required.keys.any { MaterialRules.isPlantableCrop(MaterialRules.material(it)) }) {
                    "Farm order ${zone.id}/${order.id} needs at least one plantable crop for preparation"
                }
            }
            val pestType = EntityType.valueOf(zone.pestEntity)
            require(pestType.entityClass?.let(LivingEntity::class.java::isAssignableFrom) == true) {
                "Farm zone ${zone.id} pest-entity must be a living entity"
            }
        }
        fixedCropJournal.records().forEach { pending ->
            val zone = candidate.farms.firstOrNull { it.id == pending.zoneId }
                ?: error("Pending fixed crop references missing farm zone ${pending.zoneId}")
            val material = Bukkit.createBlockData(pending.originalBlockData).material
            require(MaterialRules.isFixedBlockCrop(material) && material.name in zone.crops) {
                "Pending fixed crop ${pending.positionKey} is no longer configured in ${pending.zoneId}"
            }
        }
        candidate.lumbermills.forEach { zone ->
            requireNotNull(regionGateway.resolve(zone.reference)) { "Lumber zone ${zone.id} cannot resolve ${zone.reference}" }
            requireNotNull(regionGateway.resolve(zone.station)) { "Lumber station ${zone.id} cannot resolve ${zone.station}" }
            zone.stationMaterials.forEach(MaterialRules::material)
            zone.species.forEach { species ->
                require(
                    listOf("${species}_LOG", "${species}_WOOD", "${species}_STEM", "${species}_HYPHAE")
                        .any { Material.matchMaterial(it) != null },
                ) { "Unknown lumber species in ${zone.id}: $species" }
            }
        }
        candidate.mines.forEach { zone ->
            requireNotNull(regionGateway.resolve(zone.reference)) { "Mine zone ${zone.id} cannot resolve ${zone.reference}" }
            MaterialRules.material(zone.temporaryMaterial)
            MaterialRules.material(zone.baseMaterial)
            zone.materialWeights.keys.forEach(MaterialRules::material)
        }
    }

    private fun validateLocationOverrides(candidate: ArcFarmsConfig = settings) {
        farmLocations.zones.forEach { (zoneId, points) ->
            val configured = candidate.farms.firstOrNull { it.id == zoneId }
                ?: error("Farm location override references unknown zone $zoneId")
            val region = requireNotNull(regionGateway.resolve(configured.reference)) {
                "Farm location override cannot resolve zone $zoneId"
            }
            points.forEach { (kind, point) ->
                val world = requireNotNull(Bukkit.getWorld(point.world)) {
                    "Farm point $zoneId/$kind world ${point.world} is not loaded"
                }
                if (kind == FarmPointKind.TRAVEL) {
                    require(candidate.destinations.getValue(ActivityKind.FARM.configKey).server == candidate.serverId) {
                        "Farm travel point can only be overridden on its destination server"
                    }
                } else {
                    require(region.contains(Location(world, point.x, point.y, point.z))) {
                        "Farm point $zoneId/$kind is outside ${region.label}"
                    }
                }
            }
        }
    }

    private fun tryStartFarmShift(
        runtime: FarmRuntime,
        player: Player,
        now: Long,
        forcedOrder: FarmOrder? = null,
    ): Boolean {
        if (runtime.state.phase != FarmPhase.IDLE) return false
        if (runtime.settings.id in adminPausedFarmZones || farmBlockRegistry.isReindexing(runtime.settings.id)) return false
        if (adminEditPlayers.isNotEmpty()) return false
        if (!allowInteraction("farm-patch-scan:${runtime.settings.id}", 5_000)) return false
        val order = forcedOrder ?: FarmContractPlanner.select(
            orders = runtime.orderList,
            rareChancePercent = runtime.settings.rareOrderChancePercent,
            rareRoll = random.nextInt(100),
            selectionIndex = runtime.state.sequence,
        )
        val candidates = discoverFarmBeds(runtime, player.location)
        val anchor = player.location.toFarmPlotPosition()
        val seederShift = isSeederSequence(runtime, runtime.state.sequence + 1L)
        val targetSize = if (seederShift) runtime.settings.seederPatchSize else runtime.settings.preparationPatchSize
        val maxSize = if (seederShift) runtime.settings.seederPatchMaxSize else runtime.settings.preparationPatchMaxSize
        val patch = if (seederShift) {
            FarmPatchPlanner.selectMechanized(
                candidates = candidates,
                anchor = anchor,
                targetSize = targetSize,
                maxSize = maxSize,
                componentGap = runtime.settings.seederComponentGap,
                maxComponents = runtime.settings.seederComponentLimit,
                selectionIndex = runtime.state.sequence,
            )
        } else {
            FarmPatchPlanner.select(
                candidates = candidates,
                anchor = anchor,
                targetSize = targetSize,
                maxSize = maxSize,
                selectionIndex = runtime.state.sequence,
            )
        }
        if (patch.isEmpty()) {
            if (allowInteraction("farm-patch-empty:${runtime.settings.id}:${player.uniqueId}", 10_000)) {
                sendActionBar(player, MessageKey.FARM_PATCH_UNAVAILABLE)
                debug.event(
                    "farm_patch_unavailable",
                    "zone" to runtime.settings.id,
                    "player" to player.name,
                    "search_radius" to runtime.settings.preparationSearchRadius,
                )
            }
            return false
        }
        val preparationCrop = order.required.filterKeys {
            MaterialRules.isPlantableCrop(MaterialRules.material(it))
        }.maxWith(
            compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key },
        ).key
        val started = FarmShiftEngine.start(runtime.state, order, patch, preparationCrop, now)
        val previous = runtime.state
        runtime.state = started.state
        try {
            persistBlocking()
        } catch (failure: Exception) {
            plugin.logger.log(Level.SEVERE, "Could not durably start farm patch ${runtime.settings.id}", failure)
            runtime.state = previous
            sendChat(player, MessageKey.GENERIC_ERROR)
            return false
        }
        if (releaseFarmPatch(runtime)) {
            runtime.state = runtime.state.copy(preparationReleased = true)
            runCatching(::persistBlocking).onFailure { failure ->
                plugin.logger.log(Level.SEVERE, "Could not confirm released farm patch ${runtime.settings.id}; recovery remains idempotent", failure)
            }
        }
        farmBlockRegistry.addBeds(runtime.settings.id, patch)
        if (patch.size < targetSize) {
            debug.event(
                "farm_patch_limited",
                "zone" to runtime.settings.id,
                "wanted" to targetSize,
                "available" to patch.size,
                "mechanized" to seederShift,
            )
        }
        debug.event(
            "farm_patch_selected",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "order" to order.id,
            "rarity" to order.rarity,
            "plots" to patch.size,
            "min_x" to patch.minOf(FarmPlotPosition::x),
            "max_x" to patch.maxOf(FarmPlotPosition::x),
            "y_levels" to patch.map(FarmPlotPosition::y).distinct().sorted(),
            "min_z" to patch.minOf(FarmPlotPosition::z),
            "max_z" to patch.maxOf(FarmPlotPosition::z),
            "mechanized" to seederShift,
        )
        applyFarmResult(runtime, started.copy(state = runtime.state), player)
        if (seederShift) initializeFarmCare(runtime, player, FarmCareType.SEEDER)
        return true
    }

    private fun discoverFarmBeds(runtime: FarmRuntime, anchor: Location): Set<FarmPlotPosition> {
        val radius = runtime.settings.preparationSearchRadius
        val candidates = linkedSetOf<FarmPlotPosition>()
        val world = runtime.region.world
        for (x in anchor.blockX - radius..anchor.blockX + radius) {
            for (z in anchor.blockZ - radius..anchor.blockZ + radius) {
                if (!world.isChunkLoaded(x shr 4, z shr 4)) continue
                for (y in anchor.blockY - 5..anchor.blockY + 3) {
                    val block = world.getBlockAt(x, y, z)
                    if (!runtime.region.contains(block.location)) continue
                    if (isNearFarmOperationPoint(runtime, block.location)) continue
                    val above = block.getRelative(org.bukkit.block.BlockFace.UP).type
                    if (!FarmBlockPolicy.isSelectableBed(block.type, above, runtime.settings.crops)) continue
                    candidates += block.toFarmPlotPosition()
                }
            }
        }
        farmBlockRegistry.addBeds(runtime.settings.id, candidates)
        debug.event(
            "farm_beds_discovered",
            "zone" to runtime.settings.id,
            "candidates" to candidates.size,
            "search_radius" to radius,
        )
        return candidates
    }

    private fun discoverIncidentBeds(runtime: FarmRuntime): Set<FarmPlotPosition> {
        val patch = runtime.state.preparationPatch
        if (patch.isEmpty()) return emptySet()
        val levels = patch.map(FarmPlotPosition::y).distinct()
        val centerX = patch.sumOf(FarmPlotPosition::x) / patch.size
        val centerZ = patch.sumOf(FarmPlotPosition::z) / patch.size
        val radius = runtime.settings.preparationSearchRadius
        val knownBeds = farmBlockRegistry.beds(runtime.settings.id) + patch
        val candidates = linkedSetOf<FarmPlotPosition>()
        for (x in centerX - radius..centerX + radius) {
            for (z in centerZ - radius..centerZ + radius) {
                if (!runtime.region.world.isChunkLoaded(x shr 4, z shr 4)) continue
                levels.forEach { y ->
                    val soil = runtime.region.world.getBlockAt(x, y, z)
                    val position = soil.toFarmPlotPosition()
                    if (position !in knownBeds) return@forEach
                    if (!runtime.region.contains(soil.location) || soil.type !in FARM_SOIL_TYPES) return@forEach
                    if (isNearFarmOperationPoint(runtime, soil.location)) return@forEach
                    val above = soil.getRelative(org.bukkit.block.BlockFace.UP).type
                    if (FarmBlockPolicy.isOpenBedContent(above, runtime.settings.crops)) candidates += position
                }
            }
        }
        farmBlockRegistry.addBeds(runtime.settings.id, candidates)
        debug.event(
            "farm_incident_beds_discovered",
            "zone" to runtime.settings.id,
            "candidates" to candidates.size,
            "levels" to levels.joinToString(","),
            "radius" to radius,
        )
        return candidates
    }

    private fun isNearFarmOperationPoint(runtime: FarmRuntime, location: Location): Boolean = listOf(
        FarmPointKind.TOOL,
        FarmPointKind.SEEDS,
        FarmPointKind.WATER,
        FarmPointKind.CRATES,
        FarmPointKind.RECEIVING,
        FarmPointKind.CART,
        FarmPointKind.CUSTOMER,
    ).any { kind ->
        val point = point(runtime, kind)
        point.world == location.world.name && kotlin.math.abs(point.y - location.y) <= 3.0 &&
            (point.x - location.x) * (point.x - location.x) + (point.z - location.z) * (point.z - location.z) <= 9.0
    }

    private fun releaseFarmPatch(runtime: FarmRuntime): Boolean {
        var complete = true
        runtime.state.preparationPatch.forEach { position ->
            val soil = position.block()
            if (soil == null) {
                complete = false
                return@forEach
            }
            farmBlockLedger.capture(soil, runtime.settings.id)
            val above = soil.getRelative(org.bukkit.block.BlockFace.UP)
            if (above.type.name in runtime.settings.crops && !MaterialRules.isFixedBlockCrop(above.type)) {
                above.setType(Material.AIR, false)
            }
            soil.setType(Material.DIRT, false)
        }
        debug.event(
            "farm_patch_released",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "plots" to runtime.state.preparationPatch.size,
            "crop" to runtime.state.preparationCrop,
            "complete" to complete,
        )
        return complete
    }

    private fun initializeFarmCare(
        runtime: FarmRuntime,
        actor: Player?,
        preferredType: FarmCareType? = null,
    ): Boolean {
        val sourceReady = if (preferredType == FarmCareType.SEEDER) {
            runtime.state.phase == FarmPhase.PREPARATION
        } else {
            runtime.state.phase == FarmPhase.HARVESTING
        }
        if (!sourceReady || runtime.state.careType != null) return false
        val configured = currentOrder(runtime)?.careTypes ?: return false
        val start = if (preferredType == null) {
            java.lang.Math.floorMod(runtime.state.sequence.toInt() * 17 + random.nextInt(configured.size), configured.size)
        } else {
            configured.indexOf(preferredType).takeIf { it >= 0 } ?: 0
        }
        val candidates = if (preferredType != null) {
            listOf(preferredType)
        } else {
            configured.indices.map { configured[(start + it) % configured.size] }
        }
        val selected = candidates.firstNotNullOfOrNull { type ->
            buildFarmCareTargets(runtime, type, actor)?.let { type to it }
        }
        if (selected == null) {
            debug.event(
                "farm_care_unavailable",
                "zone" to runtime.settings.id,
                "sequence" to runtime.state.sequence,
                "types" to configured.joinToString(","),
                "procedural" to runtime.settings.proceduralCareFixtures,
            )
            return false
        }
        val result = FarmShiftEngine.startCare(runtime.state, selected.first, selected.second)
        applyFarmResult(runtime, result, actor)
        if (selected.first == FarmCareType.DISEASE) {
            diseaseNextSpreadAt[runtime.settings.id] = clock() + runtime.settings.diseaseSpreadSeconds * 1_000L
        }
        ensureFarmCare(runtime)
        persistAsync()
        return true
    }

    private fun shouldUseSeeder(runtime: FarmRuntime): Boolean {
        return runtime.state.preparationProgress == 0 && runtime.state.plantingProgress == 0 &&
            isSeederSequence(runtime, runtime.state.sequence)
    }

    private fun isSeederSequence(runtime: FarmRuntime, sequence: Long): Boolean {
        val every = runtime.settings.seederEveryShifts
        return every > 0 && java.lang.Math.floorMod(sequence - 1L, every.toLong()) == 0L
    }

    private fun buildFarmCareTargets(runtime: FarmRuntime, type: FarmCareType, actor: Player?): List<FarmCareTarget>? {
        val patch = runtime.state.preparationPatch
        if (patch.isEmpty()) return null
        val count = runtime.settings.careTargetCount
        val salt = runtime.state.sequence * 101L + type.ordinal * 17L
        fun bedTargets(role: FarmCareRole, amount: Int, required: Int = 1): List<FarmCareTarget> =
            FarmCarePlanner.spread(patch, amount.coerceAtMost(patch.size), salt).mapIndexed { index, plot ->
                FarmCareTarget(
                    id = index,
                    role = role,
                    position = FarmPointPosition(plot.world, plot.x + 0.5, plot.y + 1.05, plot.z + 0.5),
                    required = required,
                )
            }
        fun explicit(kind: FarmPointKind): FarmPointPosition? = farmLocations.zones[runtime.settings.id]?.get(kind)

        return when (type) {
            FarmCareType.SEEDER -> {
                val origin = actor?.location?.takeIf(runtime.region::contains)
                    ?: farmAreaCenter(patch)?.location()
                    ?: return null
                val start = patch.minWithOrNull(
                    compareBy<FarmPlotPosition> { plot ->
                        val dx = plot.x + 0.5 - origin.x
                        val dz = plot.z + 0.5 - origin.z
                        dx * dx + dz * dz
                    }.thenBy(FarmPlotPosition::x).thenBy(FarmPlotPosition::z),
                ) ?: return null
                listOf(
                    FarmCareTarget(
                        0,
                        FarmCareRole.SEEDER_HORSE,
                        FarmPointPosition(start.world, start.x + 0.5, start.y + 1.05, start.z + 0.5),
                    ),
                )
            }
            FarmCareType.WEEDS -> bedTargets(FarmCareRole.WEED_ROOT, count + 1, required = 2)
            FarmCareType.IRRIGATION -> {
                val field = bedTargets(FarmCareRole.VALVE, count)
                FarmCarePlanner.orient(field, explicit(FarmPointKind.IRRIGATION))
            }
            FarmCareType.POLLINATION -> {
                val hive = careFixturePoint(runtime, FarmPointKind.HIVE) ?: return null
                listOf(FarmCareTarget(0, FarmCareRole.HIVE, hive)) +
                    bedTargets(FarmCareRole.FLOWER_PATCH, count).mapIndexed { index, target -> target.copy(id = index + 1) }
            }
            FarmCareType.STORM_COVERS -> {
                val corners = FarmCarePlanner.corners(patch).mapIndexed { index, plot ->
                    FarmCareTarget(
                        index,
                        FarmCareRole.COVER_ANCHOR,
                        FarmPointPosition(plot.world, plot.x + 0.5, plot.y + 1.05, plot.z + 0.5),
                    )
                }
                FarmCarePlanner.orient(corners, explicit(FarmPointKind.COVERS))
            }
            FarmCareType.SCARECROWS -> {
                val field = bedTargets(FarmCareRole.SCARECROW, minOf(3, count), required = 2)
                FarmCarePlanner.orient(field, explicit(FarmPointKind.SCARECROWS))
            }
            FarmCareType.ANIMAL_RESCUE -> {
                val pen = careFixturePoint(runtime, FarmPointKind.PEN) ?: return null
                val sources = farmPlacementSources(runtime, actor?.location)
                val safePoints = FarmDeliveryPlanner.selectTargets(
                    candidates = findDeliveryCandidates(runtime, sources, runtime.settings.placementSearchRadius),
                    objectiveX = pen.x,
                    objectiveZ = pen.z,
                    participants = sources.map { it.x to it.z },
                    minimumObjectiveDistance = runtime.settings.placementMinObjectiveDistance.toDouble(),
                    maximumParticipantDistance = runtime.settings.animalRescueMaxPlayerDistance.toDouble(),
                    targetCount = runtime.settings.animalRescueTargetCount,
                    selectionIndex = salt,
                    minimumTargetDistance = runtime.settings.animalRescueMinSpacing,
                )
                    .map { FarmPointPosition(it.world, it.x, it.y, it.z) }
                if (safePoints.isEmpty()) return null
                safePoints.mapIndexed { index, position ->
                    FarmCareTarget(index, FarmCareRole.ANIMAL, position)
                }
            }
            FarmCareType.DISEASE -> bedTargets(
                FarmCareRole.DISEASED_CROP,
                runtime.settings.diseaseInitialSpots.coerceAtMost(patch.size),
                required = 2,
            )
            FarmCareType.MOLES -> bedTargets(FarmCareRole.MOLE_MOUND, count, required = 3)
            FarmCareType.APPLE_HARVEST -> {
                val leaves = farmBlockRegistry.orchardLeaves(runtime.settings.id).filter { position ->
                    val leaf = position.block() ?: return@filter false
                    runtime.region.contains(leaf.location) && FarmBlockPolicy.isOrchardLeaf(
                        leaf.type,
                        leaf.getRelative(org.bukkit.block.BlockFace.DOWN).type,
                    )
                }
                FarmOrchardPlanner.select(
                    candidates = leaves,
                    targetCount = runtime.settings.appleTargetCount,
                    minimumSpacing = runtime.settings.appleMinSpacing,
                    selectionIndex = salt,
                ).mapIndexed { index, leaf ->
                    FarmCareTarget(
                        id = index,
                        role = FarmCareRole.APPLE,
                        position = FarmPointPosition(leaf.world, leaf.x + 0.5, leaf.y + 0.5, leaf.z + 0.5),
                    )
                }.takeIf { it.size >= minOf(3, runtime.settings.appleTargetCount) } ?: return null
            }
        }.takeIf { it.isNotEmpty() }
    }

    private fun handleFarmCareEntityInteraction(player: Player, entity: Entity, zoneId: String) {
        val runtime = farms.firstOrNull { it.settings.id == zoneId } ?: return
        val sequence = entity.persistentDataContainer.get(careSequenceKey, PersistentDataType.LONG) ?: return
        val targetId = entity.persistentDataContainer.get(careTargetKey, PersistentDataType.INTEGER) ?: return
        val roleName = entity.persistentDataContainer.get(careRoleKey, PersistentDataType.STRING) ?: return
        val role = runCatching { FarmCareRole.valueOf(roleName) }.getOrNull() ?: return
        if (runtime.state.phase != FarmPhase.CARE || runtime.state.sequence != sequence || !runtime.region.contains(entity.location)) return
        if (!hasAccess(player, runtime.settings.permission)) {
            sendChat(player, MessageKey.ZONE_LOCKED)
            return
        }
        if (!allowInteraction("farm-care-target:$zoneId:$targetId:${player.uniqueId}", 250)) return
        if (role == FarmCareRole.PEN) {
            sendActionBar(player, MessageKey.FARM_CARE_ANIMAL_PEN)
            return
        }
        val target = runtime.state.careTargets.firstOrNull { it.id == targetId && it.role == role } ?: return
        if (role == FarmCareRole.SEEDER_HORSE) {
            val key = CareEntityKey(zoneId, targetId)
            animalFollowers[key] = player.uniqueId
            ((entity as? Horse) ?: careEntities[key].orEmpty().asSequence()
                .mapNotNull(Bukkit::getEntity).filterIsInstance<Horse>().firstOrNull())?.let { horse ->
                horse.isGlowing = true
                horse.isAware = true
                horse.setLeashHolder(player)
                horse.pathfinder.moveTo(player, 1.15)
            }
            if (!target.complete) {
                farmCareFeedback(player, entity.location, role, true)
                applyFarmResult(
                    runtime,
                    FarmShiftEngine.startSeeder(runtime.state, target.id),
                    player,
                )
            }
            sendActionBar(player, MessageKey.FARM_CARE_SEEDER_FOLLOWING)
            debug.event("farm_seeder_following", "zone" to zoneId, "player" to player.name)
            return
        }
        when (role) {
            FarmCareRole.WEED_ROOT, FarmCareRole.DISEASED_CROP, FarmCareRole.MOLE_MOUND -> if (!MaterialRules.isHoe(player.inventory.itemInMainHand)) {
                sendActionBar(player, MessageKey.FARM_CARE_TOOL)
                return
            }
            FarmCareRole.VALVE -> {
                val next = runtime.state.careTargets.filterNot(FarmCareTarget::complete).minByOrNull(FarmCareTarget::id)
                if (next?.id != target.id) {
                    sendActionBar(player, MessageKey.FARM_CARE_ORDER)
                    return
                }
            }
            FarmCareRole.HIVE -> {
                pollenCharges[player.uniqueId] = 2
                sendActionBar(player, MessageKey.FARM_CARE_POLLEN_TAKEN)
                if (target.complete) return
            }
            FarmCareRole.FLOWER_PATCH -> {
                val charges = pollenCharges[player.uniqueId] ?: 0
                if (charges <= 0) {
                    sendActionBar(player, MessageKey.FARM_CARE_POLLEN_REQUIRED)
                    return
                }
                pollenCharges[player.uniqueId] = charges - 1
            }
            FarmCareRole.ANIMAL -> {
                val key = CareEntityKey(zoneId, targetId)
                animalFollowers[key] = player.uniqueId
                (entity as? Mob)?.let { mob ->
                    mob.isGlowing = true
                    mob.setLeashHolder(player)
                    mob.pathfinder.moveTo(player, 1.15)
                }
                sendActionBar(player, MessageKey.FARM_CARE_ANIMAL_FOLLOWING)
                debug.event("farm_care_animal_following", "zone" to zoneId, "target" to targetId, "player" to player.name)
                return
            }
            FarmCareRole.COVER_ANCHOR, FarmCareRole.SCARECROW, FarmCareRole.SEEDER_WAYPOINT, FarmCareRole.APPLE -> Unit
            FarmCareRole.SEEDER_HORSE -> return
            FarmCareRole.PEN -> return
        }
        var result = FarmShiftEngine.advanceCare(runtime.state, target.id, player.uniqueId)
        if (!result.accepted) return
        val completed = result.state.careTargets.firstOrNull { it.id == target.id }?.complete != false
        if (role == FarmCareRole.MOLE_MOUND && !completed) {
            val relocated = FarmCarePlanner.relocate(
                runtime.state.preparationPatch,
                result.state.careTargets.map(FarmCareTarget::position) + target.position,
                runtime.state.sequence * 131L + target.id * 17L + target.progress,
            )
            if (relocated != null) {
                val nextPosition = FarmPointPosition(
                    relocated.world,
                    relocated.x + 0.5,
                    relocated.y + 1.05,
                    relocated.z + 0.5,
                )
                result = result.copy(
                    state = result.state.copy(
                        careTargets = result.state.careTargets.map { candidate ->
                            if (candidate.id == target.id) candidate.copy(position = nextPosition) else candidate
                        },
                    ),
                )
                removeFarmCareEntities(CareEntityKey(zoneId, target.id), "mole_relocated")
                showMoleTrail(player, target.position, nextPosition)
                debug.event(
                    "farm_mole_relocated",
                    "zone" to zoneId,
                    "target" to target.id,
                    "progress" to (target.progress + 1),
                    "x" to nextPosition.x,
                    "y" to nextPosition.y,
                    "z" to nextPosition.z,
                )
            }
        }
        if (role == FarmCareRole.VALVE) {
            showIrrigationFlow(runtime, result.state, target, player)
            removeFarmCareEntities(CareEntityKey(zoneId, target.id), "irrigation_valve_opened")
        }
        farmCareFeedback(player, entity.location, role, completed)
        applyFarmResult(runtime, result, player)
    }

    private fun farmCareFeedback(player: Player, location: Location, role: FarmCareRole, completed: Boolean) {
        if (settings.particles) {
            val color = if (completed) FARM_SUCCESS_COLOR else FARM_CARE_COLOR
            player.spawnParticle(
                Particle.DUST,
                location.clone().add(0.0, 0.55, 0.0),
                if (completed) 7 else 4,
                0.35,
                0.25,
                0.35,
                0.0,
                Particle.DustOptions(color, if (completed) 1.35f else 1.05f),
            )
        }
        if (settings.sounds) {
            val sound = when (role) {
                FarmCareRole.SEEDER_HORSE, FarmCareRole.SEEDER_WAYPOINT -> Sound.ENTITY_HORSE_STEP_WOOD
                FarmCareRole.WEED_ROOT -> Sound.BLOCK_ROOTED_DIRT_BREAK
                FarmCareRole.VALVE -> Sound.BLOCK_CHAIN_PLACE
                FarmCareRole.HIVE, FarmCareRole.FLOWER_PATCH -> Sound.ENTITY_BEE_POLLINATE
                FarmCareRole.COVER_ANCHOR -> Sound.BLOCK_WOOL_PLACE
                FarmCareRole.SCARECROW -> Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE
                FarmCareRole.ANIMAL, FarmCareRole.PEN -> Sound.ENTITY_ITEM_PICKUP
                FarmCareRole.DISEASED_CROP -> Sound.BLOCK_BREWING_STAND_BREW
                FarmCareRole.MOLE_MOUND -> Sound.BLOCK_ROOTED_DIRT_BREAK
                FarmCareRole.APPLE -> Sound.ENTITY_ITEM_PICKUP
            }
            player.playSound(location, sound, 0.7f, if (completed) 1.2f else 0.95f)
        }
    }

    private fun showMoleTrail(player: Player, from: FarmPointPosition, to: FarmPointPosition) {
        if (!settings.particles || from.world != to.world || player.world.name != from.world) return
        val start = Location(player.world, from.x, from.y, from.z)
        val end = Location(player.world, to.x, to.y, to.z)
        val delta = end.toVector().subtract(start.toVector())
        repeat(10) { index ->
            val point = start.clone().add(delta.clone().multiply((index + 1) / 10.0))
            player.spawnParticle(
                Particle.BLOCK,
                point.clone().add(0.0, -0.55, 0.0),
                2,
                0.12,
                0.04,
                0.12,
                0.01,
                Material.DIRT.createBlockData(),
            )
        }
    }

    private fun showIrrigationFlow(
        runtime: FarmRuntime,
        state: FarmShiftState,
        target: FarmCareTarget,
        player: Player,
    ) {
        val watered = carePlotsForTarget(runtime, target, FarmCareRole.VALVE)
        watered.forEach { position ->
            position.block()?.let(::setWetFarmland)
        }
        debug.event(
            "farm_irrigation_valve_opened",
            "zone" to runtime.settings.id,
            "target" to target.id,
            "beds" to watered.size,
            "player" to player.name,
        )
        if (settings.particles) {
            val previous = state.careTargets
                .filter { it.role == FarmCareRole.VALVE && it.id < target.id }
                .maxByOrNull(FarmCareTarget::id)
                ?.position
                ?: target.position
            if (previous.world == target.position.world && player.world.name == previous.world) {
                val start = Location(player.world, previous.x, previous.y + 0.15, previous.z)
                val end = Location(player.world, target.position.x, target.position.y + 0.15, target.position.z)
                val delta = end.toVector().subtract(start.toVector())
                repeat(14) { index ->
                    player.spawnParticle(
                        Particle.DUST,
                        start.clone().add(delta.clone().multiply(index / 13.0)),
                        1,
                        0.0,
                        0.0,
                        0.0,
                        0.0,
                        Particle.DustOptions(Color.fromRGB(69, 200, 245), 1.15f),
                    )
                }
            }
        }
        if (settings.sounds) player.playSound(target.position.let { Location(player.world, it.x, it.y, it.z) }, Sound.BLOCK_WATER_AMBIENT, 0.65f, 1.2f)
    }

    private fun handleFarmCareInteraction(event: PlayerInteractEvent, clicked: Block, player: Player): Boolean {
        val clickedRuntime = farmAt(clicked.location)
        if (
            clickedRuntime != null &&
            clickedRuntime.state.phase == FarmPhase.INCIDENT &&
            (clickedRuntime.state.incidentType ?: FarmIncidentType.PESTS) == FarmIncidentType.DROUGHT &&
            player.inventory.itemInMainHand.type == Material.WATER_BUCKET
        ) {
            event.isCancelled = true
            ensureFarmDroughtTargets(clickedRuntime)
            if (!hasAccess(player, clickedRuntime.settings.permission)) {
                sendChat(player, MessageKey.ZONE_LOCKED)
                return true
            }
            val source = findFarmWaterSource(clickedRuntime, clicked, event.blockFace)
            if (source == null) {
                sendActionBar(player, MessageKey.FARM_DROUGHT_REQUIRED)
                debug.event(
                    "farm_water_rejected",
                    "player" to player.name,
                    "zone" to clickedRuntime.settings.id,
                    "reason" to "invalid_source",
                    "block" to clicked.type,
                    "x" to clicked.x,
                    "y" to clicked.y,
                    "z" to clicked.z,
                )
                return true
            }
            pourFarmWater(clickedRuntime, source, player)
            return true
        }
        val soil = when {
            clicked.type in FARM_SOIL_TYPES -> clicked
            clicked.getRelative(org.bukkit.block.BlockFace.DOWN).type in FARM_SOIL_TYPES ->
                clicked.getRelative(org.bukkit.block.BlockFace.DOWN)
            else -> return false
        }
        val runtime = farmAt(soil.location) ?: return false
        val incidentType = runtime.state.incidentType ?: FarmIncidentType.PESTS
        val drought = runtime.state.phase == FarmPhase.INCIDENT && incidentType == FarmIncidentType.DROUGHT
        val preparation = runtime.state.phase == FarmPhase.PREPARATION
        val planting = runtime.state.phase == FarmPhase.PLANTING
        if (!preparation && !planting && !drought) return false
        if (drought) ensureFarmDroughtTargets(runtime)
        val target = soil.toFarmPlotPosition()
        val activeTarget = when {
            preparation || planting -> runtime.state.preparationReleased && target in runtime.state.preparationPatch
            else -> target in runtime.state.droughtPlots
        }
        if (!activeTarget) {
            if (preparation && MaterialRules.isHoe(player.inventory.itemInMainHand)) {
                event.isCancelled = true
                if (allowInteraction("farm-patch-miss:${runtime.settings.id}:${player.uniqueId}", 500)) {
                    sendActionBar(player, MessageKey.FARM_PREPARATION_REQUIRED)
                    debug.event(
                        "farm_till_rejected",
                        "player" to player.name,
                        "zone" to runtime.settings.id,
                        "reason" to "outside_patch_or_not_released",
                    )
                }
                return true
            }
            if (planting && MaterialRules.cropForSeed(player.inventory.itemInMainHand) != null) {
                event.isCancelled = true
                if (allowInteraction("farm-patch-miss:${runtime.settings.id}:${player.uniqueId}", 500)) {
                    sendActionBar(
                        player,
                        MessageKey.FARM_PLANTING_REQUIRED,
                        mapOf(
                            "crop" to MaterialRules.cropComponent(
                                MaterialRules.material(requireNotNull(runtime.state.preparationCrop)),
                            ),
                        ),
                    )
                    debug.event(
                        "farm_plant_rejected",
                        "player" to player.name,
                        "zone" to runtime.settings.id,
                        "reason" to "outside_patch_or_not_released",
                    )
                }
                return true
            }
            return false
        }
        event.isCancelled = true
        if (!hasAccess(player, runtime.settings.permission)) {
            sendChat(player, MessageKey.ZONE_LOCKED)
            return true
        }
        if (!allowInteraction("farm-care:${runtime.settings.id}:${player.uniqueId}", 100)) return true

        if (preparation) {
            if (!MaterialRules.isHoe(player.inventory.itemInMainHand)) {
                sendActionBar(player, MessageKey.FARM_PREPARATION_TOOL)
                debug.event(
                    "farm_care_rejected",
                    "player" to player.name,
                    "zone" to runtime.settings.id,
                    "phase" to runtime.state.phase,
                    "reason" to "wrong_tool",
                )
                return true
            }
            val result = FarmShiftEngine.till(runtime.state, target, player.uniqueId)
            if (!result.accepted) return true
            setWetFarmland(soil)
            debug.event(
                "farm_till_committed",
                "player" to player.name,
                "zone" to runtime.settings.id,
                "x" to soil.x,
                "y" to soil.y,
                "z" to soil.z,
            )
            if (settings.particles) {
                player.spawnParticle(
                    Particle.DUST,
                    soil.location.toCenterLocation().add(0.0, 0.65, 0.0),
                    3,
                    0.18,
                    0.12,
                    0.18,
                    0.0,
                    Particle.DustOptions(FARM_TILL_COLOR, 1.0f),
                )
            }
            if (settings.sounds) player.playSound(soil.location, Sound.ITEM_HOE_TILL, 0.65f, 1.15f)
            applyFarmResult(runtime, result, player)
            return true
        }

        if (planting) {
            val expectedCrop = MaterialRules.material(requireNotNull(runtime.state.preparationCrop))
            val expectedSeed = requireNotNull(MaterialRules.seedForCrop(expectedCrop))
            val actualCrop = MaterialRules.cropForSeed(player.inventory.itemInMainHand)
            if (actualCrop != expectedCrop) {
                sendActionBar(
                    player,
                    MessageKey.FARM_PLANTING_TOOL,
                    mapOf(
                        "crop" to MaterialRules.cropComponent(expectedCrop),
                        "seed" to MaterialRules.itemComponent(expectedSeed),
                    ),
                )
                debug.event(
                    "farm_plant_rejected",
                    "player" to player.name,
                    "zone" to runtime.settings.id,
                    "reason" to "wrong_seed",
                    "expected" to expectedSeed,
                    "actual" to player.inventory.itemInMainHand.type,
                )
                return true
            }
            val above = soil.getRelative(org.bukkit.block.BlockFace.UP)
            if (!above.type.isAir && above.type != expectedCrop) {
                sendActionBar(player, MessageKey.FARM_PLANTING_BLOCKED)
                debug.event(
                    "farm_plant_rejected",
                    "player" to player.name,
                    "zone" to runtime.settings.id,
                    "reason" to "plot_blocked",
                    "block" to above.type,
                )
                return true
            }
            val result = FarmShiftEngine.plant(runtime.state, target, expectedCrop.name, player.uniqueId)
            if (!result.accepted) return true
            setWetFarmland(soil)
            above.setBlockData(expectedCrop.createBlockData(), false)
            farmBlockLedger.captureActiveCrop(soil, runtime.settings.id)
            debug.event(
                "farm_plant_committed",
                "player" to player.name,
                "zone" to runtime.settings.id,
                "crop" to expectedCrop,
                "x" to soil.x,
                "y" to soil.y,
                "z" to soil.z,
                "seeds" to "not_consumed",
            )
            if (settings.particles) {
                player.spawnParticle(
                    Particle.DUST,
                    above.location.toCenterLocation().add(0.0, 0.8, 0.0),
                    4,
                    0.2,
                    0.22,
                    0.2,
                    0.0,
                    Particle.DustOptions(FARM_PLANT_COLOR, 1.1f),
                )
            }
            if (settings.sounds) player.playSound(soil.location, Sound.ITEM_CROP_PLANT, 0.65f, 1.1f)
            applyFarmResult(runtime, result, player)
            return true
        }

        if (player.inventory.itemInMainHand.type != Material.WATER_BUCKET) {
            sendActionBar(player, MessageKey.FARM_DROUGHT_TOOL)
            debug.event(
                "farm_care_rejected",
                "player" to player.name,
                "zone" to runtime.settings.id,
                "phase" to runtime.state.phase,
                "reason" to "wrong_tool",
            )
            return true
        }
        val source = soil.getRelative(org.bukkit.block.BlockFace.UP)
        if (!source.isReplaceable || source.type == Material.WATER) {
            sendActionBar(player, MessageKey.FARM_DROUGHT_REQUIRED)
            return true
        }
        pourFarmWater(runtime, source, player)
        return true
    }

    private fun findFarmWaterSource(
        runtime: FarmRuntime,
        clicked: Block,
        face: org.bukkit.block.BlockFace,
    ): Block? = listOf(
        clicked,
        clicked.getRelative(face),
        clicked.getRelative(org.bukkit.block.BlockFace.UP),
        clicked.getRelative(face).getRelative(org.bukkit.block.BlockFace.UP),
    ).distinctBy { Triple(it.x, it.y, it.z) }.firstOrNull { source ->
        runtime.region.contains(source.location) &&
            source.isReplaceable &&
            source.type != Material.WATER &&
            FarmWaterPlanner.canPlace(source.toFarmPlotPosition(), runtime.state.droughtPlots, FARM_WATER_RADIUS)
    }

    private fun handleFarmBreakHigh(event: BlockBreakEvent, runtime: FarmRuntime) {
        event.isCancelled = true
        if (handleFarmSpecialCropBreak(runtime, event.player, event.block)) return
        handleFarmCropHarvest(runtime, event.player, event.block) { commit ->
            event.isDropItems = false
            event.expToDrop = 0
            if (commit.fixedCrop) {
                if (prepareFixedCropHarvest(runtime, event.player, event.block, commit.now)) {
                    event.isCancelled = false
                }
                return@handleFarmCropHarvest
            }
            event.isCancelled = false
            commitBrokenFarmCrop(
                runtime = runtime,
                player = event.player,
                block = event.block,
                crop = commit.crop,
                replantData = requireNotNull(commit.replantData),
            )
        }
    }

    private fun handleFarmSpecialCropBreak(runtime: FarmRuntime, player: Player, block: Block): Boolean {
        val type = runtime.state.incidentType ?: return false
        if (runtime.state.phase != FarmPhase.INCIDENT || type !in setOf(FarmIncidentType.NIGHT_SHIFT, FarmIncidentType.MARKET)) {
            return false
        }
        val special = runtime.state.specialIncident ?: return true
        if (!hasAccess(player, runtime.settings.permission)) {
            sendChat(player, MessageKey.ZONE_LOCKED)
            return true
        }
        if (type == FarmIncidentType.MARKET && !special.marketAccepted) {
            sendActionBar(player, MessageKey.FARM_MARKET_REQUIRED)
            return true
        }
        val soil = block.getRelative(org.bukkit.block.BlockFace.DOWN)
        val position = soil.toFarmPlotPosition()
        val data = block.blockData as? Ageable
        val expected = special.crop?.let(MaterialRules::material)
        val valid = position in special.plots && data != null && data.age == data.maximumAge &&
            (expected == null || block.type == expected)
        if (!valid) {
            sendActionBar(
                player,
                MessageKey.FARM_SPECIAL_PROGRESS,
                mapOf(
                    "event" to specialIncidentName(type, player),
                    "done" to locale.text(runtime.state.incidentProgress),
                    "total" to locale.text(runtime.state.incidentRequired),
                ),
            )
            return true
        }
        val crop = block.type
        farmBlockLedger.captureActiveCrop(soil, runtime.settings.id)
        val result = FarmSpecialIncidentEngine.harvestSpecialCrop(
            current = runtime.state,
            type = type,
            damage = FarmCropDamage(position, crop.name),
            playerId = player.uniqueId,
            marketBonusPercent = runtime.settings.specialIncidents.marketMoneyBonusPercent,
        )
        if (!result.accepted) return true
        block.setType(Material.AIR, false)
        if (settings.particles) {
            block.world.spawnParticle(
                if (type == FarmIncidentType.NIGHT_SHIFT) Particle.END_ROD else Particle.HAPPY_VILLAGER,
                block.location.toCenterLocation().add(0.0, 0.65, 0.0),
                4,
                0.2,
                0.25,
                0.2,
                0.01,
            )
        }
        if (settings.sounds) {
            player.playSound(
                block.location,
                if (type == FarmIncidentType.NIGHT_SHIFT) Sound.BLOCK_AMETHYST_BLOCK_CHIME else Sound.ENTITY_VILLAGER_TRADE,
                0.7f,
                1.2f + runtime.state.incidentProgress.coerceAtMost(12) * 0.025f,
            )
        }
        applyFarmResult(runtime, result, player)
        return true
    }

    private fun handleFarmCropHarvest(
        runtime: FarmRuntime,
        player: Player,
        block: Block,
        commit: (FarmCropHarvestCommit) -> Unit,
    ) {
        if (!hasAccess(player, runtime.settings.permission)) {
            sendChat(player, MessageKey.ZONE_LOCKED)
            return
        }
        if (runtime.state.phase != FarmPhase.INCIDENT && FarmIncidentRecovery.pending(runtime.state)) {
            restoreIncidentCrops(runtime, runtime.settings.restoreBlocksPerTick)
            if (FarmIncidentRecovery.pending(runtime.state)) {
                sendActionBar(player, MessageKey.FARM_FIELD_RESTORING)
                debug.event(
                    "farm_crop_rejected",
                    "player" to player.name,
                    "zone" to runtime.settings.id,
                    "crop" to block.type,
                    "reason" to "incident_recovery_pending",
                )
                return
            }
        }
        if (
            runtime.state.phase in setOf(
                FarmPhase.PREPARATION,
                FarmPhase.PLANTING,
                FarmPhase.CARE,
                FarmPhase.INCIDENT,
                FarmPhase.DELIVERY,
            )
        ) {
            sendFarmCurrentTaskHint(player, runtime, "block_break_during_${runtime.state.phase.name.lowercase()}")
            return
        }
        val crop = block.type
        if (crop.name !in runtime.settings.crops) {
            sendFarmCurrentTaskHint(player, runtime, "wrong_block_break")
            return
        }
        val fixedCrop = MaterialRules.isFixedBlockCrop(crop)
        val ageable = block.blockData as? Ageable
        if (!fixedCrop && ageable == null) {
            sendFarmCurrentTaskHint(player, runtime, "unsupported_crop_break")
            return
        }
        if (ageable != null && ageable.age < ageable.maximumAge) {
            sendFarmCurrentTaskHint(player, runtime, "immature_crop_break")
            debug.event("farm_crop_rejected", "player" to player.name, "zone" to runtime.settings.id, "crop" to crop, "reason" to "immature")
            return
        }
        val now = clock()
        if (runtime.state.phase == FarmPhase.COOLDOWN) {
            sendActionBar(
                player,
                MessageKey.COOLDOWN,
                mapOf("seconds" to locale.text(remainingSeconds(runtime.state.cooldownEndsAt, now))),
            )
            debug.event("farm_crop_rejected", "player" to player.name, "zone" to runtime.settings.id, "crop" to crop, "reason" to "cooldown")
            return
        }
        if (runtime.state.phase == FarmPhase.IDLE) {
            tryStartFarmShift(runtime, player, now)
            if (runtime.state.phase == FarmPhase.IDLE) {
                return
            }
            sendFarmCurrentTaskHint(player, runtime, "shift_started_by_crop_break")
            return
        }
        val order = currentOrder(runtime) ?: return
        if (crop.name !in order.required) {
            sendActionBar(player, MessageKey.FARM_WRONG_TARGET, mapOf("crops" to remainingCrops(runtime, order)))
            debug.event("farm_crop_rejected", "player" to player.name, "zone" to runtime.settings.id, "crop" to crop, "reason" to "not_requested")
            return
        }
        if ((runtime.state.progress[crop.name] ?: 0) >= order.required.getValue(crop.name)) {
            val next = nextRequiredCrop(runtime.state, order)
            sendActionBar(
                player,
                MessageKey.FARM_CROP_ALREADY_COMPLETE,
                mapOf(
                    "crop" to MaterialRules.cropComponent(crop),
                    "next" to (next?.let { MaterialRules.cropComponent(MaterialRules.material(it.key)) } ?: Component.empty()),
                    "amount" to locale.text(next?.let { it.value - (runtime.state.progress[it.key] ?: 0) } ?: 0),
                ),
            )
            if (settings.particles) {
                player.spawnParticle(
                    Particle.DUST,
                    block.location.toCenterLocation().add(0.0, 1.0, 0.0),
                    8,
                    0.32,
                    0.4,
                    0.32,
                    0.0,
                    Particle.DustOptions(FARM_DANGER_COLOR, 1.25f),
                )
            }
            debug.event("farm_crop_rejected", "player" to player.name, "zone" to runtime.settings.id, "crop" to crop, "reason" to "quota_complete")
            return
        }
        val replantData = ageable?.let { (it.clone() as Ageable).also { data -> data.age = 0 } }
        commit(FarmCropHarvestCommit(crop, replantData, fixedCrop, now))
    }

    private fun commitBrokenFarmCrop(
        runtime: FarmRuntime,
        player: Player,
        block: Block,
        crop: Material,
        replantData: Ageable,
    ) {
        val existingItems = nearbyFarmDropIds(block.location, 2.0)
        val inventoryBefore = farmDropInventory(player)
        debug.event("farm_crop_committed", "player" to player.name, "zone" to runtime.settings.id, "crop" to crop, "drops" to "consumed_by_order")
        val zoneId = runtime.settings.id
        val sequence = runtime.state.sequence
        Tasks.scheduler.runLater(1L) {
            val currentRuntime = farms.firstOrNull { it.settings.id == zoneId && it.state.sequence == sequence }
                ?.takeIf { it.state.phase == FarmPhase.HARVESTING }
                ?: return@runLater
            if (!isOperational()) return@runLater
            if (!block.type.isAir) return@runLater
            block.setBlockData(replantData, false)
            block.getRelative(org.bukkit.block.BlockFace.DOWN).takeIf { it.type == Material.FARMLAND }?.let { soil ->
                farmBlockLedger.captureActiveCrop(soil, currentRuntime.settings.id)
            }
            removeNewFarmDrops(block.location, 2.0, existingItems)
            removeFarmDropInventoryGains(player, inventoryBefore, currentRuntime.settings.id)
            Tasks.scheduler.runLater(2L) {
                removeNewFarmDrops(block.location, 2.0, existingItems)
                removeFarmDropInventoryGains(player, inventoryBefore, currentRuntime.settings.id)
            }
            handleFarmHarvest(currentRuntime, player, crop.name)
        }
    }

    private fun prepareFixedCropHarvest(runtime: FarmRuntime, player: Player, block: Block, now: Long): Boolean {
        val positionKey = positionKey(block.location)
        if (fixedCropJournal.contains(positionKey)) {
            sendActionBar(player, MessageKey.FARM_FIXED_CROP_PENDING)
            return false
        }
        val ledgerRecord = runCatching { farmBlockLedger.captureFixedCrop(block, runtime.settings.id) }.getOrElse { failure ->
            plugin.logger.log(Level.SEVERE, "Could not capture fixed crop metadata at $positionKey", failure)
            sendChat(player, MessageKey.GENERIC_ERROR)
            return false
        }
        val restoreAt = now + TimeUnit.SECONDS.toMillis(runtime.settings.fixedCropRespawnSeconds.toLong())
        val scheduledRecord = runCatching {
            farmBlockLedger.scheduleExistingFixedCropRestore(block, restoreAt)
                ?: error("Fixed crop PDC disappeared before harvest")
        }.getOrElse { failure ->
            plugin.logger.log(Level.SEVERE, "Could not schedule fixed crop restore at $positionKey", failure)
            sendChat(player, MessageKey.GENERIC_ERROR)
            return false
        }
        val pending = PendingFixedFarmCrop(
            zoneId = runtime.settings.id,
            world = block.world.name,
            x = block.x,
            y = block.y,
            z = block.z,
            originalBlockData = ledgerRecord.originalBlockData,
            restoreAt = restoreAt,
        )
        val crop = block.type
        val sequence = runtime.state.sequence
        runCatching { fixedCropJournal.prepare(pending) }.getOrElse { failure ->
            plugin.logger.log(Level.SEVERE, "Could not prepare fixed crop journal at $positionKey", failure)
            farmBlockLedger.reconcileFixedCrop(
                block,
                runtime.settings.id,
                scheduledRecord.originalBlockData,
                null,
            )
            sendChat(player, MessageKey.GENERIC_ERROR)
            return false
        }.whenComplete { _, failure ->
            if (failure != null) {
                plugin.logger.log(
                    Level.SEVERE,
                    "Could not persist fixed crop journal at $positionKey; chunk PDC remains authoritative",
                    failure,
                )
            }
        }
        Tasks.scheduler.runLater(1L) {
            if (!isOperational()) return@runLater
            if (!block.type.isAir) {
                farmBlockLedger.reconcileFixedCrop(
                    block,
                    pending.zoneId,
                    pending.originalBlockData,
                    null,
                )
                fixedCropJournal.remove(positionKey).whenComplete { _, failure ->
                    if (failure != null) {
                        plugin.logger.log(Level.SEVERE, "Could not roll back fixed crop journal at $positionKey", failure)
                    }
                }
                return@runLater
            }
            fixedCropRestoreQueue.schedule(FarmFixedCropRestore(scheduledRecord.position(block.world.name), restoreAt))
            val currentRuntime = farms.firstOrNull {
                it.settings.id == pending.zoneId && it.state.sequence == sequence && it.state.phase == FarmPhase.HARVESTING
            } ?: return@runLater
            debug.event(
                "farm_fixed_crop_committed",
                "player" to player.name,
                "zone" to currentRuntime.settings.id,
                "crop" to crop,
                "restore_at" to restoreAt,
                "x" to block.x,
                "y" to block.y,
                "z" to block.z,
            )
            handleFarmHarvest(currentRuntime, player, crop.name)
        }
        return true
    }

    private fun handleFarmHarvest(runtime: FarmRuntime, player: Player, crop: String) {
        val now = clock()
        if (runtime.state.phase == FarmPhase.COOLDOWN) {
            val seconds = remainingSeconds(runtime.state.cooldownEndsAt, now)
            sendActionBar(player, MessageKey.COOLDOWN, mapOf("seconds" to locale.text(seconds)))
            return
        }
        val order = currentOrder(runtime) ?: return
        val incidentType = nextFarmIncident(runtime, order)
        val activeRules = if (incidentType == FarmIncidentType.DROUGHT) {
            val gardenBeds = runtime.state.preparationPatch.size.takeIf { it > 0 } ?: runtime.settings.preparationPatchSize
            runtime.rules.copy(droughtQuota = runtime.settings.droughtTargetBeds(gardenBeds))
        } else {
            runtime.rules
        }
        val before = runtime.state.progress[crop] ?: 0
        var result = FarmShiftEngine.harvest(
            runtime.state,
            order,
            activeRules,
            crop,
            player.uniqueId,
            now,
            incidentType,
        )
        if (ShiftEvent.DELIVERY_STARTED in result.events) {
            result = result.copy(
                state = result.state.copy(
                    deliveryPosition = selectDeliveryAnchor(runtime, player.location),
                ),
            )
        }
        applyFarmResult(runtime, result, player)
        val required = order.required[crop]
        if (result.accepted && required != null && before < required && (result.state.progress[crop] ?: 0) >= required) {
            val next = nextRequiredCrop(result.state, order)
            if (next != null) {
                val zoneId = runtime.settings.id
                val sequence = result.state.sequence
                val announce = {
                    if (isOperational() && player.isOnline && farms.any { it.settings.id == zoneId && it.state.sequence == sequence }) {
                        showScreenTitle(
                            player,
                            MessageKey.FARM_CROP_COMPLETED,
                            mapOf(
                                "crop" to MaterialRules.cropComponent(MaterialRules.material(crop)),
                                "next" to MaterialRules.cropComponent(MaterialRules.material(next.key)),
                                "amount" to locale.text(next.value - (result.state.progress[next.key] ?: 0)),
                            ),
                        )
                    }
                }
                if (ShiftEvent.INCIDENT_STARTED in result.events) Tasks.scheduler.runLater(settings.titleStaySeconds * 20L + 10L, announce)
                else announce()
            }
        }
        if (!result.accepted && ShiftEvent.COMPLETED !in result.events) {
            sendActionBar(player, MessageKey.FARM_WRONG_TARGET, mapOf("crops" to remainingCrops(runtime, order)))
        }
    }

    private fun nextFarmIncident(runtime: FarmRuntime, order: FarmOrder): FarmIncidentType {
        val count = runtime.rules.incidentTargetCount(runtime.state.sequence)
        val plan = FarmIncidentPlanner.sequence(order.incidentTypes, count, runtime.state.sequence)
        return plan.getOrElse(runtime.state.incidentsResolved) { plan.last() }
    }

    private fun handleLumberBreakHigh(event: BlockBreakEvent, runtime: LumberRuntime) {
        if (!hasAccess(event.player, runtime.settings.permission)) {
            event.isCancelled = true
            sendChat(event.player, MessageKey.ZONE_LOCKED)
            return
        }
        event.isCancelled = !MaterialRules.isLumberBreakable(event.block.type)
    }

    private fun handleLumberFell(runtime: LumberRuntime, player: Player, species: String) {
        val now = clock()
        if (runtime.state.phase == LumberPhase.COOLDOWN) {
            sendActionBar(
                player,
                MessageKey.COOLDOWN,
                mapOf("seconds" to locale.text(remainingSeconds(runtime.state.cooldownEndsAt, now))),
            )
            return
        }
        if (runtime.state.phase == LumberPhase.IDLE) {
            val target = runtime.settings.species[(runtime.state.sequence % runtime.settings.species.size).toInt()]
            applyLumberResult(runtime, LumberShiftEngine.start(runtime.state, target, runtime.rules, now), player)
        }
        val result = LumberShiftEngine.fell(runtime.state, runtime.rules, species, player.uniqueId, now)
        applyLumberResult(runtime, result, player)
        if (!result.accepted && runtime.state.phase == LumberPhase.FELLING) {
            val target = MaterialRules.woodComponent(requireNotNull(runtime.state.species))
            sendActionBar(player, MessageKey.LUMBER_WRONG_SPECIES, mapOf("wood" to target))
        }
    }

    private fun handleLumberProcessing(runtime: LumberRuntime, player: Player): Boolean {
        if (runtime.state.phase != LumberPhase.PROCESSING) {
            sendActionBar(player, MessageKey.LUMBER_STATION_REQUIRED)
            return false
        }
        val result = LumberShiftEngine.process(runtime.state, runtime.rules, player.uniqueId, clock())
        applyLumberResult(runtime, result, player)
        return result.accepted
    }

    private fun handleMineBreak(event: BlockBreakEvent, runtime: MineRuntime) {
        val experience = event.expToDrop
        event.isCancelled = true
        val player = event.player
        if (!hasAccess(player, runtime.settings.permission)) {
            sendChat(player, MessageKey.ZONE_LOCKED)
            return
        }
        val toolSlot = player.inventory.heldItemSlot
        val toolSnapshot = player.inventory.getItem(toolSlot)?.clone()
        if (toolSnapshot == null || !MaterialRules.isPickaxe(toolSnapshot)) {
            sendActionBar(player, MessageKey.MINE_PICKAXE_REQUIRED)
            return
        }
        val block = event.block
        if (block.type !in runtime.materialWeights) return
        when (runtime.state.phase) {
            MinePhase.HAZARD -> {
                sendActionBar(player, MessageKey.MINE_HAZARD_HELP)
                return
            }
            MinePhase.EXTRACTION -> {
                sendActionBar(player, MessageKey.MINE_EXTRACTION_REQUIRED)
                return
            }
            MinePhase.COOLDOWN -> {
                sendActionBar(
                    player,
                    MessageKey.COOLDOWN,
                    mapOf("seconds" to locale.text(remainingSeconds(runtime.state.cooldownEndsAt, clock()))),
                )
                return
            }
            else -> Unit
        }
        val positionKey = positionKey(block.location)
        if (pendingPositions.containsKey(positionKey) || !mineReservations.add(positionKey)) {
            sendActionBar(player, MessageKey.MINE_REGENERATING)
            return
        }
        val now = clock()
        val nextMaterial = MaterialRules.weightedMaterial(runtime.materialWeights, random)
        val record = PendingMineBlock(
            id = "${runtime.settings.id}:${UUID.randomUUID()}",
            zoneId = runtime.settings.id,
            world = block.world.name,
            x = block.x,
            y = block.y,
            z = block.z,
            originalMaterial = block.type.name,
            temporaryMaterial = runtime.temporaryMaterial.name,
            nextMaterial = nextMaterial.name,
            restoreAt = now + runtime.settings.restoreSeconds * 1000L,
        )
        val originalMaterial = block.type
        val drops = block.getDrops(toolSnapshot, player).map { it.clone() }
        mineJournal.prepare(record).whenComplete { _, failure ->
            if (!isOperational()) {
                mineReservations.remove(positionKey)
                return@whenComplete
            }
            Tasks.scheduler.runSync {
                if (failure != null) {
                    mineReservations.remove(positionKey)
                    if (player.isOnline) sendChat(player, MessageKey.MINE_JOURNAL_FAILED)
                    plugin.logger.log(Level.SEVERE, "Could not journal mine block ${record.id}", failure)
                    return@runSync
                }
                if (!isOperational() || mines.none { it === runtime }) {
                    mineReservations.remove(positionKey)
                    mineJournal.remove(record.id).whenComplete { _, retireFailure ->
                        if (retireFailure != null) {
                            plugin.logger.log(Level.SEVERE, "Could not retire stale mine journal record ${record.id}", retireFailure)
                        }
                    }
                    return@runSync
                }
                if (block.type != originalMaterial) {
                    mineReservations.remove(positionKey)
                    mineJournal.remove(record.id)
                    return@runSync
                }
                block.setType(runtime.temporaryMaterial, false)
                pendingPositions[positionKey] = record.id
                mineReservations.remove(positionKey)
                drops.forEach { block.world.dropItemNaturally(block.location.toCenterLocation(), it) }
                if (experience > 0 && player.isOnline) player.giveExp(experience)
                if (player.isOnline && player.gameMode != GameMode.CREATIVE) {
                    val currentTool = player.inventory.getItem(toolSlot)
                    if (currentTool != null && currentTool.isSimilar(toolSnapshot)) {
                        player.inventory.setItem(toolSlot, player.damageItemStack(currentTool, 1))
                    }
                }
                val actionNow = clock()
                if (runtime.state.phase == MinePhase.IDLE) {
                    applyMineResult(runtime, MineShiftEngine.start(runtime.state, runtime.rules, actionNow), player)
                }
                val points = if (originalMaterial == runtime.baseMaterial) 1 else 2
                applyMineResult(
                    runtime,
                    MineShiftEngine.mine(runtime.state, runtime.rules, points, player.uniqueId, actionNow),
                    player,
                )
            }
        }
    }

    private fun applyFarmResult(runtime: FarmRuntime, result: EngineResult<FarmShiftState>, actor: Player?) {
        val incidentType = result.state.incidentType ?: runtime.state.incidentType ?: FarmIncidentType.PESTS
        val careType = result.state.careType ?: runtime.state.careType
        runtime.state = result.state
        traceResult(
            activity = ActivityKind.FARM,
            zone = runtime.settings.id,
            actor = actor,
            phase = runtime.state.phase,
            progress = currentOrder(runtime)?.let { "${runtime.state.completed(it)}/${it.totalRequired}" },
            result = result,
        )
        if (actor != null && result.contribution > 0) recordContribution(actor.uniqueId, ActivityKind.FARM, result.contribution)
        result.events.forEach { event ->
            when (event) {
                ShiftEvent.STARTED -> {
                    broadcast(
                        runtime.region,
                        MessageKey.FARM_STARTED,
                        sound = Sound.BLOCK_BELL_USE,
                        valuesForPlayer = { player ->
                            mapOf(
                                "order" to locale.renderPath("order.farm.${runtime.state.orderId}", player),
                                "total" to locale.text(runtime.state.preparationRequired),
                            )
                        },
                    )
                    broadcastStoryTitle(runtime, "start") { player ->
                        locale.renderPath("order.farm.${runtime.state.orderId}", player) to mapOf(
                            "total" to locale.text(runtime.state.preparationRequired),
                        )
                    }
                }
                ShiftEvent.PREPARATION_PROGRESS -> if (actor != null) {
                    sendActionBar(
                        actor,
                        MessageKey.FARM_PREPARATION_PROGRESS,
                        mapOf(
                            "done" to locale.text(runtime.state.preparationProgress),
                            "total" to locale.text(runtime.state.preparationRequired),
                        ),
                    )
                    if (
                        runtime.state.preparationProgress < runtime.state.preparationRequired &&
                        runtime.state.preparationProgress % PATCH_PERSIST_INTERVAL == 0
                    ) persistAsync()
                }
                ShiftEvent.PLANTING_STARTED -> {
                    if (shouldUseSeeder(runtime) && initializeFarmCare(runtime, actor, FarmCareType.SEEDER)) {
                        persistAsync()
                        return@forEach
                    }
                    val crop = MaterialRules.material(requireNotNull(runtime.state.preparationCrop))
                    broadcast(
                        runtime.region,
                        MessageKey.FARM_PLANTING_STARTED,
                        mapOf(
                            "crop" to MaterialRules.cropComponent(crop),
                            "total" to locale.text(runtime.state.preparationRequired),
                        ),
                        Sound.ENTITY_PLAYER_LEVELUP,
                        title = true,
                    )
                    playFarmStageFanfare(runtime, 0.95f)
                    persistAsync()
                }
                ShiftEvent.PLANTING_PROGRESS -> if (actor != null) {
                    sendActionBar(
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
                        runtime.state.plantingProgress % PATCH_PERSIST_INTERVAL == 0
                    ) persistAsync()
                }
                ShiftEvent.PREPARATION_COMPLETED -> {
                    successBurst(runtime.region)
                    playFarmStageFanfare(runtime, 1.05f)
                    if (!initializeFarmCare(runtime, actor)) {
                        broadcast(
                            runtime.region,
                            MessageKey.FARM_PREPARATION_COMPLETED,
                            sound = Sound.ENTITY_VILLAGER_YES,
                            title = true,
                        )
                    }
                    persistAsync()
                }
                ShiftEvent.CARE_STARTED -> {
                    val type = requireNotNull(careType)
                    players(runtime.region).forEach { player ->
                        val title = locale.renderPath("care.${type.name.lowercase()}.name", player)
                        val instructionPath = if (type == FarmCareType.SEEDER) {
                            seederInstructionPath(runtime.state)
                        } else {
                            "care.${type.name.lowercase()}.instruction"
                        }
                        val subtitle = locale.renderPath(instructionPath, player)
                        showScreenTitle(
                            player,
                            title,
                            subtitle,
                        )
                        debug.message("title", "local", "care.${type.name.lowercase()}.name", player, title)
                        debug.message("subtitle", "local", instructionPath, player, subtitle)
                        if (settings.sounds) player.playSound(player.location, farmCareStartSound(type), 0.75f, 1.0f)
                    }
                    warningBurst(runtime.region)
                    debug.event(
                        "farm_care_started",
                        "zone" to runtime.settings.id,
                        "sequence" to runtime.state.sequence,
                        "type" to type,
                        "targets" to runtime.state.careTargets.size,
                        "required" to runtime.state.careRequired(),
                    )
                    persistAsync()
                }
                ShiftEvent.CARE_PROGRESS -> {
                    if (actor != null && careType != FarmCareType.SEEDER) {
                        sendActionBar(
                            actor,
                            MessageKey.FARM_CARE_PROGRESS,
                            mapOf(
                                "done" to locale.text(runtime.state.careProgress()),
                                "total" to locale.text(runtime.state.careRequired()),
                            ),
                        )
                    }
                    ensureFarmCare(runtime)
                    persistAsync()
                }
                ShiftEvent.SEEDER_PROGRESS -> {
                    if (actor != null) {
                        val stage = requireNotNull(runtime.state.seederStage())
                        sendActionBar(
                            actor,
                            MessageKey.FARM_CARE_SEEDER_PROGRESS,
                            mapOf(
                                "stage" to locale.renderPath("care.seeder.stage.${stage.name.lowercase()}", actor),
                                "done" to locale.text(if (stage == FarmSeederStage.TILLING) {
                                    runtime.state.preparationProgress
                                } else {
                                    runtime.state.plantingProgress
                                }),
                                "total" to locale.text(runtime.state.preparationRequired),
                            ),
                        )
                    }
                    persistAsync()
                }
                ShiftEvent.SEEDER_PLANTING_STARTED -> {
                    runtime.state.careTargets.filter { it.role == FarmCareRole.SEEDER_WAYPOINT }.forEach { target ->
                        removeFarmCareEntities(
                            CareEntityKey(runtime.settings.id, target.id),
                            "seeder_planting_route",
                        )
                    }
                    ensureFarmCare(runtime)
                    players(runtime.region).forEach { player ->
                        val title = locale.render(MessageKey.FARM_CARE_SEEDER_PLANTING_STARTED, player)
                        val subtitle = locale.render(MessageKey.FARM_CARE_SEEDER_PLANTING_STARTED_SUBTITLE, player)
                        showScreenTitle(player, title, subtitle)
                        debug.message("title", "local", MessageKey.FARM_CARE_SEEDER_PLANTING_STARTED.path, player, title)
                        debug.message(
                            "subtitle",
                            "local",
                            MessageKey.FARM_CARE_SEEDER_PLANTING_STARTED_SUBTITLE.path,
                            player,
                            subtitle,
                        )
                        if (settings.sounds) {
                            player.playSound(player.location, Sound.BLOCK_GRINDSTONE_USE, 0.85f, 1.25f)
                        }
                    }
                    successBurst(runtime.region)
                    playFarmStageFanfare(runtime, 1.05f)
                    debug.event(
                        "farm_seeder_planting_started",
                        "zone" to runtime.settings.id,
                        "sequence" to runtime.state.sequence,
                        "plots" to runtime.state.preparationRequired,
                    )
                    persistAsync()
                }
                ShiftEvent.CARE_RESOLVED -> {
                    clearFarmCare(runtime, "care_resolved")
                    diseaseNextSpreadAt.remove(runtime.settings.id)
                    if (careType == FarmCareType.SEEDER) {
                        runtime.state = runtime.state.copy(careType = null, seederStage = null, careTargets = emptyList())
                        broadcast(
                            runtime.region,
                            MessageKey.FARM_CARE_SEEDER_RESOLVED,
                            sound = Sound.ENTITY_HORSE_ARMOR,
                        )
                    } else {
                        broadcast(
                            runtime.region,
                            MessageKey.FARM_CARE_RESOLVED,
                            sound = Sound.ENTITY_VILLAGER_YES,
                            title = true,
                        )
                    }
                    successBurst(runtime.region)
                    playFarmStageFanfare(runtime, 1.1f)
                    debug.event(
                        "farm_care_resolved",
                        "zone" to runtime.settings.id,
                        "sequence" to runtime.state.sequence,
                        "type" to careType,
                    )
                    if (careType == FarmCareType.SEEDER) initializeFarmCare(runtime, actor)
                    persistAsync()
                }
                ShiftEvent.HARVEST_CHECKPOINT -> {
                    val checkpoint = runtime.state.harvestCheckpoint
                    if (actor != null) {
                        sendActionBar(
                            actor,
                            MessageKey.FARM_HARVEST_MILESTONE,
                            mapOf("percent" to locale.text(checkpoint * 10)),
                        )
                        if (settings.particles) {
                            actor.spawnParticle(
                                Particle.COMPOSTER,
                                actor.location.clone().add(0.0, 1.0, 0.0),
                                5,
                                0.35,
                                0.3,
                                0.35,
                                0.02,
                            )
                        }
                    }
                    playFarmMilestone(runtime, Sound.BLOCK_NOTE_BLOCK_HAT, 0.85f + checkpoint * 0.035f)
                    debug.event(
                        "farm_harvest_checkpoint",
                        "zone" to runtime.settings.id,
                        "sequence" to runtime.state.sequence,
                        "checkpoint" to checkpoint,
                        "percent" to checkpoint * 10,
                    )
                    persistAsync()
                }
                ShiftEvent.HARVEST_MILESTONE -> {
                    val milestone = runtime.state.harvestMilestone
                    debug.event(
                        "farm_harvest_milestone",
                        "zone" to runtime.settings.id,
                        "sequence" to runtime.state.sequence,
                        "milestone" to milestone,
                        "percent" to milestone * 25,
                    )
                    persistAsync()
                }
                ShiftEvent.INCIDENT_STARTED -> {
                    when (incidentType) {
                        FarmIncidentType.DROUGHT -> {
                            ensureFarmDroughtTargets(runtime)
                            broadcastStoryTitle(runtime, "drought", Sound.WEATHER_RAIN_ABOVE) { player ->
                                locale.render(MessageKey.FARM_DROUGHT_STARTED, player) to mapOf(
                                    "total" to locale.text(runtime.state.incidentRequired),
                                )
                            }
                        }
                        FarmIncidentType.PESTS -> {
                            ensureFarmPestNests(runtime)
                            ensurePestNestEntities(runtime)
                            broadcastStoryTitle(runtime, "pests", Sound.ENTITY_BEE_LOOP_AGGRESSIVE) { player ->
                                locale.render(MessageKey.FARM_INCIDENT_STARTED, player) to mapOf(
                                    "crop" to MaterialRules.cropComponent(MaterialRules.material(requireNotNull(runtime.state.incidentCrop))),
                                    "nests" to locale.text(runtime.state.pestNests.size),
                                    "pests" to locale.text(runtime.state.pestAlive),
                                )
                            }
                        }
                        else -> {
                            initializeFarmSpecialIncident(runtime, incidentType)
                            announceFarmSpecialIncident(runtime, incidentType)
                            ensureFarmSpecialIncident(runtime)
                        }
                    }
                    warningBurst(runtime.region)
                    network.signal(
                        NetworkSignal.FARM_INCIDENT,
                        ActivityKind.FARM,
                        actor?.name,
                        players(runtime.region).mapTo(mutableSetOf(), Player::getUniqueId),
                    )
                    persistAsync()
                }
                ShiftEvent.INCIDENT_PROGRESS -> if (actor != null) {
                    val key = when (incidentType) {
                        FarmIncidentType.DROUGHT -> MessageKey.FARM_DROUGHT_PROGRESS
                        FarmIncidentType.PESTS -> MessageKey.FARM_INCIDENT_PROGRESS
                        else -> MessageKey.FARM_SPECIAL_PROGRESS
                    }
                    sendActionBar(actor, key, buildMap {
                        put("done", locale.text(runtime.state.incidentProgress))
                        put("total", locale.text(runtime.state.incidentRequired))
                        if (incidentType in SPECIAL_FARM_INCIDENT_TYPES) put("event", specialIncidentName(incidentType, actor))
                    })
                }
                ShiftEvent.INCIDENT_RESOLVED -> {
                    droughtGrowth.remove(runtime.settings.id)
                    removePestNestEntities(runtime, "incident_resolved")
                    specialIncidentScene.clearZone(runtime.settings.id, "incident_resolved")
                    nightShift.clearZone(runtime.settings.id)
                    broadcast(
                        runtime.region,
                        if (incidentType in SPECIAL_FARM_INCIDENT_TYPES) {
                            MessageKey.FARM_SPECIAL_RESOLVED
                        } else {
                            MessageKey.FARM_INCIDENT_RESOLVED
                        },
                        sound = Sound.ENTITY_VILLAGER_YES,
                        title = true,
                    )
                    successBurst(runtime.region)
                    playFarmStageFanfare(runtime, 1.15f)
                    network.signal(
                        NetworkSignal.FARM_RESCUED,
                        ActivityKind.FARM,
                        actor?.name,
                        players(runtime.region).mapTo(mutableSetOf(), Player::getUniqueId),
                    )
                    persistAsync()
                }
                ShiftEvent.DELIVERY_STARTED -> {
                    broadcastStoryTitle(runtime, "delivery", Sound.BLOCK_BARREL_CLOSE) { player ->
                        locale.render(MessageKey.FARM_DELIVERY_STARTED, player) to emptyMap()
                    }
                    removePests(runtime, activePests(runtime), "delivery_started")
                    removePestNestEntities(runtime, "delivery_started")
                    ensureFarmDelivery(runtime)
                    persistAsync()
                }
                ShiftEvent.DELIVERY_PROGRESS -> {
                    if (actor != null) sendActionBar(actor, MessageKey.FARM_DELIVERY_REQUIRED)
                    ensureFarmDelivery(runtime)
                    ensureFarmContractScene(runtime)
                    val cart = point(runtime, FarmPointKind.CART)
                    Bukkit.getWorld(cart.world)?.let { world ->
                        val location = Location(world, cart.x, cart.y + 0.9, cart.z)
                        if (settings.particles) {
                            world.spawnParticle(Particle.COMPOSTER, location, 10, 0.45, 0.3, 0.45, 0.03)
                        }
                        if (settings.sounds) {
                            players(runtime.region).forEach { player ->
                                player.playSound(location, Sound.BLOCK_BARREL_CLOSE, 0.75f, 1.1f)
                            }
                        }
                    }
                    persistAsync()
                }
                ShiftEvent.COMPLETED -> {
                    players(runtime.region).forEach { removeFarmServiceItems(it, runtime.settings.id, "shift_completed") }
                    clearDelivery(runtime, "completed")
                    val contributors = runtime.state.contributors
                    recordCompletion(ActivityKind.FARM, runtime.state.contributors)
                    queueFarmCompletionRewards(runtime, contributors)
                    broadcast(
                        runtime.region,
                        MessageKey.FARM_COMPLETED,
                        mapOf("players" to locale.text(runtime.state.contributors.size)),
                        Sound.UI_TOAST_CHALLENGE_COMPLETE,
                        title = true,
                    )
                    announceWinner(runtime.region, runtime.state.contributors)
                    celebration(runtime.region)
                    network.complete(
                        ActivityKind.FARM,
                        actor?.name,
                        players(runtime.region).mapTo(mutableSetOf(), Player::getUniqueId),
                    )
                    persistAsync()
                }
                else -> Unit
            }
        }
        ensureFarmContractScene(runtime)
    }

    private fun applyLumberResult(runtime: LumberRuntime, result: EngineResult<LumberShiftState>, actor: Player?) {
        runtime.state = result.state
        traceResult(
            activity = ActivityKind.LUMBER,
            zone = runtime.settings.id,
            actor = actor,
            phase = runtime.state.phase,
            progress = "${runtime.state.felled}/${runtime.rules.fellingQuota}:${runtime.state.processed}/${runtime.rules.processingQuota}",
            result = result,
        )
        if (actor != null && result.contribution > 0) recordContribution(actor.uniqueId, ActivityKind.LUMBER, result.contribution)
        result.events.forEach { event ->
            when (event) {
                ShiftEvent.STARTED -> broadcast(
                    runtime.region,
                    MessageKey.LUMBER_STARTED,
                    mapOf("wood" to MaterialRules.woodComponent(requireNotNull(runtime.state.species))),
                    Sound.BLOCK_WOOD_PLACE,
                )
                ShiftEvent.PHASE_CHANGED -> {
                    broadcast(
                        runtime.region,
                        MessageKey.LUMBER_PROCESSING,
                        sound = Sound.BLOCK_PISTON_EXTEND,
                        title = true,
                    )
                    successBurst(runtime.region)
                    network.signal(
                        NetworkSignal.LUMBER_PROCESSING,
                        ActivityKind.LUMBER,
                        actor?.name,
                        listOf(runtime.region, runtime.station).flatMap(::players).mapTo(mutableSetOf(), Player::getUniqueId),
                    )
                }
                ShiftEvent.COMPLETED -> {
                    recordCompletion(ActivityKind.LUMBER, runtime.state.contributors)
                    broadcast(
                        listOf(runtime.region, runtime.station),
                        MessageKey.LUMBER_COMPLETED,
                        mapOf("players" to locale.text(runtime.state.contributors.size)),
                        Sound.UI_TOAST_CHALLENGE_COMPLETE,
                        title = true,
                    )
                    announceWinner(listOf(runtime.region, runtime.station), runtime.state.contributors)
                    celebration(listOf(runtime.region, runtime.station))
                    network.complete(
                        ActivityKind.LUMBER,
                        actor?.name,
                        listOf(runtime.region, runtime.station).flatMap(::players).mapTo(mutableSetOf(), Player::getUniqueId),
                    )
                    persistAsync()
                }
                else -> Unit
            }
        }
    }

    private fun applyMineResult(runtime: MineRuntime, result: EngineResult<MineShiftState>, actor: Player?) {
        runtime.state = result.state
        traceResult(
            activity = ActivityKind.MINE,
            zone = runtime.settings.id,
            actor = actor,
            phase = runtime.state.phase,
            progress = "${runtime.state.cart}/${runtime.rules.cartQuota}",
            result = result,
        )
        if (actor != null && result.contribution > 0) recordContribution(actor.uniqueId, ActivityKind.MINE, result.contribution)
        result.events.forEach { event ->
            when (event) {
                ShiftEvent.STARTED -> broadcast(
                    runtime.region,
                    MessageKey.MINE_STARTED,
                    sound = Sound.BLOCK_IRON_DOOR_OPEN,
                    valuesForPlayer = { player ->
                        mapOf("route" to locale.renderPath("route.mine.${runtime.settings.id}", player))
                    },
                )
                ShiftEvent.HAZARD_STARTED -> {
                    broadcast(runtime.region, MessageKey.MINE_HAZARD_STARTED, sound = Sound.ENTITY_GENERIC_EXPLODE, title = true)
                    broadcast(runtime.region, MessageKey.MINE_HAZARD_HELP)
                    warningBurst(runtime.region)
                    network.signal(
                        NetworkSignal.MINE_HAZARD,
                        ActivityKind.MINE,
                        actor?.name,
                        players(runtime.region).mapTo(mutableSetOf(), Player::getUniqueId),
                    )
                    persistAsync()
                }
                ShiftEvent.HAZARD_RESOLVED -> {
                    broadcast(
                        runtime.region,
                        MessageKey.MINE_HAZARD_RESOLVED,
                        sound = Sound.BLOCK_ANVIL_USE,
                        title = true,
                    )
                    successBurst(runtime.region)
                    network.signal(
                        NetworkSignal.MINE_STABLE,
                        ActivityKind.MINE,
                        actor?.name,
                        players(runtime.region).mapTo(mutableSetOf(), Player::getUniqueId),
                    )
                }
                ShiftEvent.EXTRACTION_STARTED -> {
                    broadcast(
                        runtime.region,
                        MessageKey.MINE_EXTRACTION_STARTED,
                        sound = Sound.BLOCK_BELL_RESONATE,
                        title = true,
                    )
                    network.signal(
                        NetworkSignal.MINE_EXTRACTION,
                        ActivityKind.MINE,
                        actor?.name,
                        players(runtime.region).mapTo(mutableSetOf(), Player::getUniqueId),
                    )
                }
                ShiftEvent.COMPLETED -> {
                    recordCompletion(ActivityKind.MINE, runtime.state.contributors)
                    broadcast(runtime.region, MessageKey.MINE_COMPLETED, sound = Sound.UI_TOAST_CHALLENGE_COMPLETE, title = true)
                    announceWinner(runtime.region, runtime.state.contributors)
                    celebration(runtime.region)
                    network.complete(
                        ActivityKind.MINE,
                        actor?.name,
                        players(runtime.region).mapTo(mutableSetOf(), Player::getUniqueId),
                    )
                    persistAsync()
                }
                ShiftEvent.PROGRESS -> if (runtime.state.phase == MinePhase.HAZARD && actor != null) {
                    sendActionBar(
                        actor,
                        MessageKey.MINE_HAZARD_PROGRESS,
                        mapOf(
                            "done" to locale.text(runtime.state.supports),
                            "total" to locale.text(runtime.rules.supportsRequired),
                        ),
                    )
                }
                else -> Unit
            }
        }
    }

    private fun startTasks() {
        tasks += Tasks.scheduler.runTimer(1L, 1L) { runGuarded("farm_block_restores", ::processFarmBlockRestores) }
        tasks += Tasks.scheduler.runTimer(20L, 20L) { runGuarded("tick", ::tick) }
        tasks += Tasks.scheduler.runTimer(10L, 10L) { runGuarded("guidance_particles", ::emitGuidanceParticles) }
        tasks += Tasks.scheduler.runTimer(5L, 5L) {
            farms.forEach { runtime ->
                runGuarded("farm_animals:${runtime.settings.id}") {
                    if (!isAdminEditingFarm(runtime)) {
                        updateFarmCareAnimals(runtime)
                        updateFarmSeeder(runtime)
                    }
                }
            }
        }
        tasks += Tasks.scheduler.runTimer(1L, 1L) { runGuarded("carried_displays", ::updateCarriedDisplays) }
        tasks += Tasks.scheduler.runTimer(
            settings.saveSeconds * 20L,
            settings.saveSeconds * 20L,
        ) { runGuarded("periodic_save", ::persistAsync) }
    }

    private fun stopTasks() {
        tasks.forEach(ScheduledTask::cancel)
        tasks.clear()
    }

    private fun tick() {
        val now = clock()
        Bukkit.getOnlinePlayers().forEach { player -> syncFarmMusic(player, farmAt(player.location), now) }
        farms.forEach { runtime ->
            runGuarded("farm:${runtime.settings.id}") {
                if (isAdminEditingFarm(runtime)) return@runGuarded
                if (runtime.state.phase != FarmPhase.INCIDENT && FarmIncidentRecovery.pending(runtime.state)) return@runGuarded
                if (runtime.state.phase == FarmPhase.COOLDOWN && runtime.state.preparationPatch.isNotEmpty()) {
                    return@runGuarded
                }
                val result = FarmShiftEngine.tick(runtime.state, currentOrder(runtime), now)
                if (result.events.isNotEmpty()) applyFarmResult(runtime, result, null)
                if (runtime.state.phase == FarmPhase.IDLE) {
                    players(runtime.region).firstOrNull()?.let { player ->
                        tryStartFarmShift(runtime, player, now)
                    }
                }
                if (runtime.state.phase == FarmPhase.PREPARATION && shouldUseSeeder(runtime)) {
                    initializeFarmCare(runtime, players(runtime.region).firstOrNull(), FarmCareType.SEEDER)
                }
                ensureFarmDroughtTargets(runtime)
                ensureFarmPests(runtime)
                ensureFarmSpecialIncident(runtime)
                letPestsEatCrops(runtime)
                updateFarmDisease(runtime, now)
                reconcileLoadedFarmCareEntities(runtime)
                ensureFarmCare(runtime)
                ensureFarmDelivery(runtime)
                ensureFarmContractScene(runtime)
                ensureFarmSupplies(runtime)
                maintainWetFarmBeds(runtime)
            }
        }
        lumbermills.forEach { runtime ->
            runGuarded("lumber:${runtime.settings.id}") {
                val result = LumberShiftEngine.tick(runtime.state, runtime.rules, now)
                if (result.events.isNotEmpty()) applyLumberResult(runtime, result, null)
            }
        }
        mines.forEach { runtime ->
            runGuarded("mine:${runtime.settings.id}") {
                val result = MineShiftEngine.tick(runtime.state, runtime.rules, now)
                if (result.events.isNotEmpty()) applyMineResult(runtime, result, null)
            }
        }
        runGuarded("mine_recovery") { restoreMineBlocks(now) }
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

    private fun restoreMineBlocks(now: Long) {
        mineJournal.records().filter { it.restoreAt <= now }.forEach { record ->
            val world = Bukkit.getWorld(record.world) ?: return@forEach
            if (!world.isChunkLoaded(record.x shr 4, record.z shr 4)) return@forEach
            val block = world.getBlockAt(record.x, record.y, record.z)
            val temporary = runCatching { MaterialRules.material(record.temporaryMaterial) }.getOrNull()
            val next = runCatching { MaterialRules.material(record.nextMaterial) }.getOrNull()
            if (temporary == null || next == null) {
                if (allowInteraction("mine-journal-material:${record.id}", TimeUnit.MINUTES.toMillis(5))) {
                    plugin.logger.severe("Mine journal record ${record.id} contains an unknown material and was retained for recovery")
                }
                return@forEach
            }
            if (block.type == temporary) block.setType(next, false)
            mineJournal.remove(record.id).whenComplete { _, failure ->
                if (failure == null) pendingPositions.remove(record.positionKey, record.id)
                else plugin.logger.log(Level.SEVERE, "Could not retire mine journal record ${record.id}", failure)
            }
        }
    }

    private fun updatePlayerGuidance() {
        val expectedBars = mutableSetOf<BarKey>()
        val expectedScoreboards = mutableSetOf<UUID>()
        farms.forEach { runtime ->
            if (
                runtime.state.phase !in setOf(
                    FarmPhase.PREPARATION,
                    FarmPhase.PLANTING,
                    FarmPhase.CARE,
                    FarmPhase.HARVESTING,
                    FarmPhase.INCIDENT,
                    FarmPhase.DELIVERY,
                    FarmPhase.COOLDOWN,
                )
            ) return@forEach
            if (runtime.state.phase == FarmPhase.COOLDOWN) {
                val now = clock()
                val remainingMillis = (runtime.state.cooldownEndsAt - now).coerceAtLeast(0)
                val cooldownMillis = runtime.rules.cooldownMillis.coerceAtLeast(1)
                players(runtime.region).filterNot(::isAdminEditing).forEach { player ->
                    updateBar(
                        player,
                        "farm:${runtime.settings.id}",
                        locale.render(
                            MessageKey.FARM_COOLDOWN_BOSSBAR,
                            player,
                            mapOf("seconds" to locale.text(remainingSeconds(runtime.state.cooldownEndsAt, now))),
                        ),
                        (1.0 - remainingMillis.toDouble() / cooldownMillis).toFloat(),
                        BossBar.Color.YELLOW,
                        expectedBars,
                    )
                }
                return@forEach
            }
            val order = currentOrder(runtime) ?: return@forEach
            val done = runtime.state.completed(order)
            players(runtime.region).filterNot(::isAdminEditing).forEach { player ->
                val carrying = deliveryCarriers.any { (delivery, carrierId) ->
                    delivery.zoneId == runtime.settings.id && carrierId == player.uniqueId
                }
                val phaseDone = when (runtime.state.phase) {
                    FarmPhase.PREPARATION -> runtime.state.preparationProgress
                    FarmPhase.PLANTING -> runtime.state.plantingProgress
                    FarmPhase.CARE -> if (runtime.state.careType == FarmCareType.SEEDER) {
                        if (runtime.state.seederStage() == FarmSeederStage.TILLING) {
                            runtime.state.preparationProgress
                        } else {
                            runtime.state.plantingProgress
                        }
                    } else {
                        runtime.state.careProgress()
                    }
                    FarmPhase.INCIDENT -> runtime.state.incidentProgress
                    FarmPhase.DELIVERY -> runtime.state.deliveredCrates.size
                    else -> done
                }
                val phaseTotal = when (runtime.state.phase) {
                    FarmPhase.PREPARATION, FarmPhase.PLANTING -> runtime.state.preparationRequired
                    FarmPhase.CARE -> if (runtime.state.careType == FarmCareType.SEEDER) {
                        runtime.state.preparationRequired
                    } else {
                        runtime.state.careRequired()
                    }
                    FarmPhase.INCIDENT -> runtime.state.incidentRequired
                    FarmPhase.DELIVERY -> runtime.settings.delivery.crates
                    else -> order.totalRequired
                }.coerceAtLeast(1)
                val key = when (runtime.state.phase) {
                    FarmPhase.PREPARATION -> MessageKey.FARM_PREPARATION_BOSSBAR
                    FarmPhase.PLANTING -> MessageKey.FARM_PLANTING_BOSSBAR
                    FarmPhase.CARE -> MessageKey.FARM_CARE_BOSSBAR
                    FarmPhase.INCIDENT -> when (runtime.state.incidentType ?: FarmIncidentType.PESTS) {
                        FarmIncidentType.DROUGHT -> MessageKey.FARM_DROUGHT_BOSSBAR
                        FarmIncidentType.PESTS -> MessageKey.FARM_INCIDENT_BOSSBAR
                        else -> MessageKey.FARM_SPECIAL_BOSSBAR
                    }
                    FarmPhase.DELIVERY -> if (carrying) {
                        MessageKey.FARM_DELIVERY_CARRYING_BOSSBAR
                    } else {
                        MessageKey.FARM_DELIVERY_BOSSBAR
                    }
                    else -> MessageKey.FARM_BOSSBAR
                }
                val component = locale.render(
                    key,
                    player,
                    mapOf(
                        "order" to locale.renderPath("order.farm.${order.id}", player),
                        "crop" to MaterialRules.cropComponent(MaterialRules.material(requireNotNull(
                            if (runtime.state.phase == FarmPhase.PLANTING) {
                                runtime.state.preparationCrop
                            } else {
                                order.required.keys.firstOrNull()
                            },
                        ))),
                        "requirements" to farmRequirements(runtime, order),
                        "instruction" to (runtime.state.careType?.let { type ->
                            locale.renderPath(if (type == FarmCareType.SEEDER) {
                                seederInstructionPath(runtime.state)
                            } else {
                                "care.${type.name.lowercase()}.instruction"
                            }, player)
                        } ?: Component.empty()),
                        "nests" to locale.text(runtime.state.pestNests.size),
                        "pests" to locale.text(runtime.state.pestAlive),
                        "event" to (runtime.state.incidentType?.takeIf { it in SPECIAL_FARM_INCIDENT_TYPES }
                            ?.let { specialIncidentName(it, player) } ?: Component.empty()),
                        "done" to locale.text(phaseDone),
                        "total" to locale.text(phaseTotal),
                    ),
                )
                val progress = phaseDone.toFloat() / phaseTotal
                updateBar(
                    player,
                    "farm:${runtime.settings.id}",
                    component,
                    progress,
                    when (runtime.state.phase) {
                        FarmPhase.PREPARATION -> BossBar.Color.WHITE
                        FarmPhase.PLANTING -> BossBar.Color.GREEN
                        FarmPhase.CARE -> BossBar.Color.BLUE
                        FarmPhase.INCIDENT -> BossBar.Color.RED
                        FarmPhase.DELIVERY -> BossBar.Color.PURPLE
                        else -> BossBar.Color.GREEN
                    },
                    expectedBars,
                )
                updateFarmScoreboard(
                    runtime = runtime,
                    order = order,
                    player = player,
                    phaseDone = phaseDone,
                    phaseTotal = phaseTotal,
                    carrying = carrying,
                    expected = expectedScoreboards,
                )
            }
        }
        reconcileFarmScoreboards(expectedScoreboards)
        lumbermills.forEach { runtime ->
            if (runtime.state.phase !in setOf(LumberPhase.FELLING, LumberPhase.PROCESSING)) return@forEach
            val region = if (runtime.state.phase == LumberPhase.PROCESSING) runtime.station else runtime.region
            players(region).forEach { player ->
                val processing = runtime.state.phase == LumberPhase.PROCESSING
                val done = if (processing) runtime.state.processed else runtime.state.felled
                val total = if (processing) runtime.rules.processingQuota else runtime.rules.fellingQuota
                val key = if (processing) MessageKey.LUMBER_ACTIONBAR_PROCESSING else MessageKey.LUMBER_ACTIONBAR_FELLING
                val component = locale.render(
                    key,
                    player,
                    mapOf(
                        "wood" to MaterialRules.woodComponent(requireNotNull(runtime.state.species)),
                        "done" to locale.text(done),
                        "total" to locale.text(total),
                    ),
                )
                sendActionBar(player, key, mapOf(
                    "wood" to MaterialRules.woodComponent(requireNotNull(runtime.state.species)),
                    "done" to locale.text(done),
                    "total" to locale.text(total),
                ))
                updateBar(player, "lumber:${runtime.settings.id}", component, done.toFloat() / total, BossBar.Color.YELLOW, expectedBars)
            }
        }
        mines.forEach { runtime ->
            if (runtime.state.phase !in setOf(MinePhase.MINING, MinePhase.HAZARD, MinePhase.EXTRACTION)) return@forEach
            players(runtime.region).filter { mineAt(it.location) === runtime }.forEach { player ->
                val component = locale.render(
                    MessageKey.MINE_ACTIONBAR,
                    player,
                    mapOf(
                        "route" to locale.renderPath("route.mine.${runtime.settings.id}", player),
                        "done" to locale.text(runtime.state.cart),
                        "total" to locale.text(runtime.rules.cartQuota),
                        "phase" to locale.renderPath("phase.mine.${runtime.state.phase.name.lowercase()}", player),
                    ),
                )
                sendActionBar(
                    player,
                    MessageKey.MINE_ACTIONBAR,
                    mapOf(
                        "route" to locale.renderPath("route.mine.${runtime.settings.id}", player),
                        "done" to locale.text(runtime.state.cart),
                        "total" to locale.text(runtime.rules.cartQuota),
                        "phase" to locale.renderPath("phase.mine.${runtime.state.phase.name.lowercase()}", player),
                    ),
                )
                val color = when (runtime.state.phase) {
                    MinePhase.HAZARD -> BossBar.Color.RED
                    MinePhase.EXTRACTION -> BossBar.Color.YELLOW
                    else -> BossBar.Color.BLUE
                }
                updateBar(
                    player,
                    "mine:${runtime.settings.id}",
                    component,
                    runtime.state.cart.toFloat() / runtime.rules.cartQuota,
                    color,
                    expectedBars,
                )
            }
        }
        val stale = activeBars.keys - expectedBars
        stale.forEach { key ->
            val bar = activeBars.remove(key) ?: return@forEach
            Bukkit.getPlayer(key.playerId)?.hideBossBar(bar)
        }
    }

    private fun reconcileFarmPatches() {
        var changed = false
        farms.forEach { runtime ->
            val originalPatch = runtime.state.preparationPatch
            if (originalPatch.isEmpty()) return@forEach
            if (runtime.state.phase in setOf(FarmPhase.PREPARATION, FarmPhase.PLANTING)) {
                val anchor = originalPatch.firstNotNullOfOrNull(FarmPlotPosition::location)
                if (anchor != null) {
                    val expanded = FarmPatchPlanner.expand(
                        candidates = discoverFarmBeds(runtime, anchor) + originalPatch,
                        currentPatch = originalPatch,
                        maxSize = runtime.settings.preparationPatchMaxSize,
                    )
                    if (expanded.size > originalPatch.size) {
                        runtime.state = runtime.state.copy(
                            preparationPatch = expanded,
                            preparationRequired = expanded.size,
                            preparationReleased = false,
                        )
                        changed = true
                        debug.event(
                            "farm_patch_expanded_on_recovery",
                            "zone" to runtime.settings.id,
                            "sequence" to runtime.state.sequence,
                            "before" to originalPatch.size,
                            "after" to expanded.size,
                        )
                    }
                }
            }
            val patch = runtime.state.preparationPatch
            farmBlockRegistry.addBeds(runtime.settings.id, patch)
            patch.forEach { position -> position.block()?.let { farmBlockLedger.capture(it, runtime.settings.id) } }
            if (!runtime.state.preparationReleased) {
                if (releaseFarmPatch(runtime)) {
                    runtime.state = runtime.state.copy(preparationReleased = true)
                    changed = true
                } else {
                    return@forEach
                }
            }
            val crop = runtime.state.preparationCrop?.let(MaterialRules::material) ?: return@forEach
            val tilled = runtime.state.tilledPlots.toMutableSet()
            val planted = runtime.state.plantedPlots.toMutableSet()
            patch.forEach plot@{ position ->
                val soil = position.block() ?: return@plot
                val above = soil.getRelative(org.bukkit.block.BlockFace.UP)
                if (above.type == crop) {
                    tilled += position
                    planted += position
                } else if (soil.type == Material.FARMLAND) {
                    tilled += position
                }
            }
            val nextPhase = when {
                runtime.state.phase in setOf(FarmPhase.PREPARATION, FarmPhase.PLANTING) && planted.size >= patch.size ->
                    FarmPhase.HARVESTING
                runtime.state.phase == FarmPhase.PREPARATION && tilled.size >= patch.size -> FarmPhase.PLANTING
                else -> runtime.state.phase
            }
            val next = runtime.state.copy(
                phase = nextPhase,
                tilledPlots = tilled,
                plantedPlots = planted,
                preparationProgress = tilled.size.coerceAtMost(patch.size),
                plantingProgress = planted.size.coerceAtMost(patch.size),
            )
            if (next != runtime.state) {
                runtime.state = next
                changed = true
            }
            maintainWetFarmBeds(runtime)
        }
        if (changed) persistBlocking()
    }

    private fun restoreFarmPatchOriginal(runtime: FarmRuntime, limit: Int = Int.MAX_VALUE): Boolean {
        require(limit >= 1) { "Farm patch restore limit must be positive" }
        val restored = patchRestoreProgress.getOrPut(runtime.settings.id, ::linkedSetOf)
        runtime.state.preparationPatch.asSequence().filterNot(restored::contains).take(limit).forEach { position ->
            val soil = position.block()
            if (soil == null) {
                return@forEach
            }
            runCatching {
                if (!farmBlockLedger.restoreOriginal(soil, clear = false)) {
                    if (allowInteraction("farm-ledger-missing:${runtime.settings.id}:$position", TimeUnit.MINUTES.toMillis(5))) {
                        plugin.logger.severe("Managed farm plot $position has no recovery ledger and was retained for repair")
                    }
                } else {
                    restored += position
                }
            }.onFailure { failure ->
                plugin.logger.log(Level.SEVERE, "Could not restore managed farm plot $position", failure)
            }
        }
        val complete = restored.containsAll(runtime.state.preparationPatch)
        if (!complete) return false
        patchRestoreProgress.remove(runtime.settings.id)
        debug.event(
            "farm_patch_restored",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "plots" to runtime.state.preparationPatch.size,
        )
        return true
    }

    private fun ensureFarmCare(runtime: FarmRuntime) {
        if (runtime.state.phase != FarmPhase.CARE) {
            if (careEntities.keys.any { it.zoneId == runtime.settings.id }) clearFarmCare(runtime, "phase_inactive")
            diseaseNextSpreadAt.remove(runtime.settings.id)
            return
        }
        val activeIds = runtime.state.careTargets.mapTo(mutableSetOf(), FarmCareTarget::id)
        careEntities.keys.filter { it.zoneId == runtime.settings.id && it.targetId >= 0 && it.targetId !in activeIds }
            .forEach { removeFarmCareEntities(it, "stale_target") }
        runtime.state.careTargets.forEach { target ->
            if (target.role == FarmCareRole.SEEDER_HORSE) return@forEach
            if (target.role == FarmCareRole.SEEDER_WAYPOINT) {
                removeFarmCareEntities(CareEntityKey(runtime.settings.id, target.id), "seeder_route_removed")
                return@forEach
            }
            if (target.complete && target.role !in setOf(FarmCareRole.HIVE, FarmCareRole.VALVE)) {
                removeFarmCareEntities(CareEntityKey(runtime.settings.id, target.id), "target_complete")
                return@forEach
            }
            ensureFarmCareTarget(runtime, target)
        }
        if (runtime.state.careType == FarmCareType.ANIMAL_RESCUE) ensureFarmAnimalPen(runtime)
        if (runtime.state.careType == FarmCareType.SEEDER) ensureFarmSeeder(runtime)
    }

    private fun reconcileLoadedFarmCareEntities(runtime: FarmRuntime) {
        val now = clock()
        if (now < careNextReconcileAt.getOrDefault(runtime.settings.id, 0L)) return
        careNextReconcileAt[runtime.settings.id] = now + FARM_CARE_RECONCILE_INTERVAL_MILLIS
        val targets = runtime.state.careTargets.associateBy(FarmCareTarget::id)
        runtime.region.world.entities.asSequence().filter { entity ->
            entity.persistentDataContainer.get(careZoneKey, PersistentDataType.STRING) == runtime.settings.id
        }.forEach { entity ->
            val sequence = entity.persistentDataContainer.get(careSequenceKey, PersistentDataType.LONG)
            val targetId = entity.persistentDataContainer.get(careTargetKey, PersistentDataType.INTEGER)
            val role = entity.persistentDataContainer.get(careRoleKey, PersistentDataType.STRING)
            val ordinaryTarget = targetId?.let(targets::get)
            val currentTarget = ordinaryTarget != null && ordinaryTarget.role.name == role
            val currentPen = targetId == -1 && role == FarmCareRole.PEN.name &&
                runtime.state.careType == FarmCareType.ANIMAL_RESCUE
            val valid = runtime.state.phase == FarmPhase.CARE && runtime.state.sequence == sequence &&
                (currentTarget || currentPen)
            if (!valid) {
                entity.remove()
                debug.event(
                    "farm_care_stale_entity_removed",
                    "zone" to runtime.settings.id,
                    "target" to targetId,
                    "role" to role,
                )
                return@forEach
            }
            careEntities.getOrPut(CareEntityKey(runtime.settings.id, requireNotNull(targetId)), ::linkedSetOf) += entity.uniqueId
        }
    }

    private fun ensureFarmSeeder(runtime: FarmRuntime) {
        val target = runtime.state.careTargets.firstOrNull { it.role == FarmCareRole.SEEDER_HORSE } ?: return
        val key = CareEntityKey(runtime.settings.id, target.id)
        val active = careEntities[key].orEmpty().mapNotNull(Bukkit::getEntity).filter { entity ->
            entity.isValid &&
                entity.persistentDataContainer.get(careZoneKey, PersistentDataType.STRING) == runtime.settings.id &&
                entity.persistentDataContainer.get(careSequenceKey, PersistentDataType.LONG) == runtime.state.sequence &&
                entity.persistentDataContainer.get(careTargetKey, PersistentDataType.INTEGER) == target.id &&
                entity.persistentDataContainer.get(careRoleKey, PersistentDataType.STRING) == target.role.name
        }
        if (active.size == 2 && active.count { it is Horse } == 1 && active.count { it is TextDisplay } == 1) {
            careEntities[key] = active.mapTo(mutableSetOf(), Entity::getUniqueId)
            return
        }
        removeFarmCareEntities(key, "replace_seeder")
        val world = Bukkit.getWorld(target.position.world) ?: return
        val location = Location(world, target.position.x, target.position.y, target.position.z)
        if (!runtime.region.contains(location) || !world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) return
        val horse = world.spawn(location, Horse::class.java) { entity ->
            entity.setAdult()
            entity.isPersistent = false
            entity.removeWhenFarAway = false
            entity.isInvulnerable = true
            entity.isCollidable = false
            entity.isGlowing = true
            entity.isAware = false
            entity.inventory.saddle = ItemStack(Material.SADDLE)
            markFarmCareEntity(entity, runtime, target.id, target.role)
        }
        val label = world.spawn(location.clone().add(0.0, 2.25, 0.0), TextDisplay::class.java) { entity ->
            entity.text(locale.render(MessageKey.FARM_CARE_SEEDER_NAME))
            entity.billboard = Display.Billboard.VERTICAL
            entity.alignment = TextDisplay.TextAlignment.CENTER
            entity.backgroundColor = Color.fromARGB(128, 16, 16, 16)
            entity.isShadowed = true
            entity.viewRange = 0.7f
            entity.isPersistent = false
            markFarmCareEntity(entity, runtime, target.id, target.role)
        }
        careEntities[key] = mutableSetOf(horse.uniqueId, label.uniqueId)
        debug.event("farm_seeder_spawned", "zone" to runtime.settings.id, "x" to location.x, "y" to location.y, "z" to location.z)
    }

    private fun ensureFarmCareTarget(runtime: FarmRuntime, target: FarmCareTarget) {
        val key = CareEntityKey(runtime.settings.id, target.id)
        val expected = if (target.role == FarmCareRole.ANIMAL) 1 else 2
        val active = careEntities[key].orEmpty().mapNotNull(Bukkit::getEntity).filter { entity ->
                entity.isValid &&
                entity.persistentDataContainer.get(careZoneKey, PersistentDataType.STRING) == runtime.settings.id &&
                entity.persistentDataContainer.get(careSequenceKey, PersistentDataType.LONG) == runtime.state.sequence &&
                entity.persistentDataContainer.get(careTargetKey, PersistentDataType.INTEGER) == target.id &&
                entity.persistentDataContainer.get(careRoleKey, PersistentDataType.STRING) == target.role.name
        }
        if (active.size == expected) {
            if (target.role == FarmCareRole.ANIMAL) {
                (active.singleOrNull() as? Mob)?.let { mob ->
                    mob.isGlowing = true
                }
            }
            careEntities[key] = active.mapTo(mutableSetOf(), Entity::getUniqueId)
            return
        }
        removeFarmCareEntities(key, "replace_target")
        val world = Bukkit.getWorld(target.position.world) ?: return
        if (!world.isChunkLoaded(target.position.x.toInt() shr 4, target.position.z.toInt() shr 4)) return
        val location = Location(world, target.position.x, target.position.y, target.position.z)
        if (!runtime.region.contains(location)) {
            plugin.logger.warning("Farm care target ${runtime.settings.id}/${target.id} is outside ${runtime.region.label}")
            return
        }
        if (target.role == FarmCareRole.APPLE) {
            val leaf = location.block
            if (!FarmBlockPolicy.isOrchardLeaf(leaf.type, leaf.getRelative(org.bukkit.block.BlockFace.DOWN).type)) {
                plugin.logger.warning("Farm apple target ${runtime.settings.id}/${target.id} no longer has an open leaf anchor")
                return
            }
        }
        if (target.role == FarmCareRole.ANIMAL) {
            val typeName = runtime.settings.careAnimalEntities[
                java.lang.Math.floorMod(runtime.state.sequence.toInt() + target.id, runtime.settings.careAnimalEntities.size)
            ]
            val mob = world.spawnEntity(location, EntityType.valueOf(typeName)) as? Mob ?: return
            mob.isPersistent = false
            mob.removeWhenFarAway = false
            mob.isInvulnerable = true
            mob.isCollidable = false
            mob.isGlowing = true
            markFarmCareEntity(mob, runtime, target.id, target.role)
            careEntities[key] = mutableSetOf(mob.uniqueId)
            debug.event("farm_care_animal_spawned", "zone" to runtime.settings.id, "target" to target.id, "entity" to typeName)
            return
        }
        val visual = runtime.settings.careVisuals.getValue(target.role)
        val stack = ItemStack(MaterialRules.material(visual.material))
        if (visual.customModelData > 0) {
            val meta = stack.itemMeta
            meta.setCustomModelData(visual.customModelData)
            stack.itemMeta = meta
        }
        val displayOffset = if (target.role == FarmCareRole.APPLE) -0.62 else 0.45
        val interactionOffset = if (target.role == FarmCareRole.APPLE) -0.68 else 0.05
        val display = world.spawn(location.clone().add(0.0, displayOffset, 0.0), ItemDisplay::class.java) { entity ->
            entity.setItemStack(stack)
            entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
            if (target.role == FarmCareRole.APPLE) entity.uniformScale(runtime.settings.appleDisplayScale)
            entity.viewRange = runtime.settings.displayViewRange
            entity.isGlowing = true
            entity.glowColorOverride = if (target.complete) FARM_SUCCESS_COLOR else careRoleColor(target.role)
            entity.isPersistent = false
            markFarmCareEntity(entity, runtime, target.id, target.role)
        }
        val interaction = world.spawn(location.clone().add(0.0, interactionOffset, 0.0), Interaction::class.java) { entity ->
            entity.interactionWidth = if (target.role == FarmCareRole.COVER_ANCHOR) 1.45f else 1.15f
            entity.interactionHeight = if (target.role == FarmCareRole.APPLE) 1.15f else 1.45f
            entity.isResponsive = true
            entity.isPersistent = false
            markFarmCareEntity(entity, runtime, target.id, target.role)
        }
        careEntities[key] = mutableSetOf(display.uniqueId, interaction.uniqueId)
        debug.event(
            "farm_care_target_spawned",
            "zone" to runtime.settings.id,
            "target" to target.id,
            "role" to target.role,
            "x" to location.x,
            "y" to location.y,
            "z" to location.z,
        )
    }

    private fun ensureFarmAnimalPen(runtime: FarmRuntime) {
        val point = careFixturePoint(runtime, FarmPointKind.PEN) ?: return
        val key = CareEntityKey(runtime.settings.id, -1)
        val active = careEntities[key].orEmpty().mapNotNull(Bukkit::getEntity).filter { entity ->
            entity.isValid &&
                entity.persistentDataContainer.get(careZoneKey, PersistentDataType.STRING) == runtime.settings.id &&
                entity.persistentDataContainer.get(careSequenceKey, PersistentDataType.LONG) == runtime.state.sequence &&
                entity.persistentDataContainer.get(careTargetKey, PersistentDataType.INTEGER) == -1 &&
                entity.persistentDataContainer.get(careRoleKey, PersistentDataType.STRING) == FarmCareRole.PEN.name
        }
        if (active.size == 2) return
        removeFarmCareEntities(key, "replace_pen")
        val world = Bukkit.getWorld(point.world) ?: return
        if (!world.isChunkLoaded(point.x.toInt() shr 4, point.z.toInt() shr 4)) return
        val location = Location(world, point.x, point.y, point.z)
        if (!runtime.region.contains(location)) return
        val visual = runtime.settings.careVisuals.getValue(FarmCareRole.PEN)
        val stack = ItemStack(MaterialRules.material(visual.material)).also { item ->
            if (visual.customModelData > 0) item.itemMeta = item.itemMeta.also { it.setCustomModelData(visual.customModelData) }
        }
        val display = world.spawn(location.clone().add(0.0, 0.55, 0.0), ItemDisplay::class.java) { entity ->
            entity.setItemStack(stack)
            entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
            entity.viewRange = runtime.settings.displayViewRange
            entity.isGlowing = true
            entity.glowColorOverride = FARM_SUCCESS_COLOR
            entity.isPersistent = false
            markFarmCareEntity(entity, runtime, -1, FarmCareRole.PEN)
        }
        val interaction = world.spawn(location, Interaction::class.java) { entity ->
            entity.interactionWidth = 2.2f
            entity.interactionHeight = 1.8f
            entity.isResponsive = true
            entity.isPersistent = false
            markFarmCareEntity(entity, runtime, -1, FarmCareRole.PEN)
        }
        careEntities[key] = mutableSetOf(display.uniqueId, interaction.uniqueId)
        debug.event(
            "farm_care_pen_spawned",
            "zone" to runtime.settings.id,
            "x" to location.x,
            "y" to location.y,
            "z" to location.z,
        )
    }

    private fun updateFarmCareAnimals(runtime: FarmRuntime) {
        if (runtime.state.phase != FarmPhase.CARE || runtime.state.careType != FarmCareType.ANIMAL_RESCUE) return
        val pen = careFixturePoint(runtime, FarmPointKind.PEN) ?: return
        val penLocation = Bukkit.getWorld(pen.world)?.let { Location(it, pen.x, pen.y, pen.z) } ?: return
        runtime.state.careTargets.filter { it.role == FarmCareRole.ANIMAL && !it.complete }.forEach { target ->
            val key = CareEntityKey(runtime.settings.id, target.id)
            val mob = careEntities[key].orEmpty().asSequence()
                .mapNotNull(Bukkit::getEntity).filterIsInstance<Mob>().firstOrNull() ?: return@forEach
            val actor = animalFollowers[key]?.let(Bukkit::getPlayer)?.takeIf { player ->
                player.isOnline && runtime.region.contains(player.location)
            }
            if (actor != null && mob.world == penLocation.world &&
                mob.location.distanceSquared(penLocation) <= runtime.settings.animalDeliveryRadius * runtime.settings.animalDeliveryRadius
            ) {
                releaseAnimalFollower(key, mob, "delivered")
                farmCareFeedback(actor, mob.location, FarmCareRole.ANIMAL, true)
                applyFarmResult(runtime, FarmShiftEngine.advanceCare(runtime.state, target.id, actor.uniqueId), actor)
                return@forEach
            }
            if (!runtime.region.contains(mob.location)) {
                mob.teleport(Location(mob.world, target.position.x, target.position.y, target.position.z))
                releaseAnimalFollower(key, mob, "outside_zone")
                return@forEach
            }
            if (actor == null) {
                releaseAnimalFollower(key, mob, "actor_unavailable")
            } else if (mob.location.distanceSquared(actor.location) > 2.25) {
                if (!mob.isLeashed || runCatching { mob.leashHolder }.getOrNull() != actor) mob.setLeashHolder(actor)
                mob.pathfinder.moveTo(actor, 1.25)
                pullFarmAnimalTowardHolder(mob, actor)
            }
        }
    }

    private fun updateFarmSeeder(runtime: FarmRuntime) {
        if (runtime.state.phase != FarmPhase.CARE || runtime.state.careType != FarmCareType.SEEDER) return
        val horseTarget = runtime.state.careTargets.firstOrNull { it.role == FarmCareRole.SEEDER_HORSE } ?: return
        val key = CareEntityKey(runtime.settings.id, horseTarget.id)
        val horse = careEntities[key].orEmpty().asSequence()
            .mapNotNull(Bukkit::getEntity).filterIsInstance<Horse>().firstOrNull() ?: return
        careEntities[key].orEmpty().mapNotNull(Bukkit::getEntity).filterIsInstance<TextDisplay>().forEach { label ->
            label.teleport(horse.location.clone().add(0.0, 2.25, 0.0))
        }
        val actor = animalFollowers[key]?.let(Bukkit::getPlayer)?.takeIf { player ->
            player.isOnline && runtime.region.contains(player.location)
        }
        if (actor == null) {
            horse.isAware = false
            releaseAnimalFollower(key, horse, "seeder_actor_unavailable")
            return
        }
        horse.isAware = true
        if (!runtime.region.contains(horse.location)) {
            horse.teleport(Location(horse.world, horseTarget.position.x, horseTarget.position.y, horseTarget.position.z))
            releaseAnimalFollower(key, horse, "seeder_outside_zone")
            return
        }
        val stage = requireNotNull(runtime.state.seederStage())
        val completedPlots = if (stage == FarmSeederStage.TILLING) runtime.state.tilledPlots else runtime.state.plantedPlots
        val reachable = FarmMachinePlanner.plotsInWorkingRadius(
            candidates = runtime.state.preparationPatch.filterNot(completedPlots::contains),
            world = horse.world.name,
            machineX = horse.location.x,
            machineY = horse.location.y,
            machineZ = horse.location.z,
            radius = runtime.settings.seederWorkingRadius,
        )
        val mutation = when (stage) {
            FarmSeederStage.TILLING -> farmMachineBlocks.till(
                runtime.settings.id,
                reachable,
                runtime.settings.seederBlocksPerUpdate,
            )
            FarmSeederStage.PLANTING -> farmMachineBlocks.plant(
                runtime.settings.id,
                MaterialRules.material(requireNotNull(runtime.state.preparationCrop)),
                reachable,
                runtime.settings.seederBlocksPerUpdate,
            )
        }
        if (mutation.processed.isNotEmpty()) {
            machineSwathFeedback(runtime, mutation.processed, stage)
            applyFarmResult(runtime, FarmShiftEngine.workSeeder(runtime.state, mutation.processed, actor.uniqueId), actor)
        }
        if (runtime.state.phase != FarmPhase.CARE || runtime.state.careType != FarmCareType.SEEDER || !horse.isValid) return
        if (!horse.isLeashed || runCatching { horse.leashHolder }.getOrNull() != actor) horse.setLeashHolder(actor)
        horse.pathfinder.moveTo(actor, 1.2)
        pullFarmAnimalTowardHolder(horse, actor)
    }

    private fun machineSwathFeedback(
        runtime: FarmRuntime,
        plots: Collection<FarmPlotPosition>,
        stage: FarmSeederStage,
    ) {
        if (!settings.particles) return
        val color = if (stage == FarmSeederStage.TILLING) FARM_TILL_COLOR else FARM_PLANT_COLOR
        plots.asSequence().filterIndexed { index, _ -> index % 4 == 0 }.forEach { position ->
            val above = position.block()?.getRelative(org.bukkit.block.BlockFace.UP) ?: return@forEach
            runtime.region.world.spawnParticle(
                Particle.DUST,
                above.location.toCenterLocation().add(0.0, 0.75, 0.0),
                2,
                0.16,
                0.16,
                0.16,
                0.0,
                Particle.DustOptions(color, 0.95f),
            )
        }
    }

    private fun carePlotsForTarget(
        runtime: FarmRuntime,
        target: FarmCareTarget,
        role: FarmCareRole,
    ): Set<FarmPlotPosition> {
        val targets = runtime.state.careTargets.filter { it.role == role }
        if (targets.isEmpty()) return emptySet()
        return runtime.state.preparationPatch.filterTo(linkedSetOf()) { plot ->
            targets.minWith(
                compareBy<FarmCareTarget> { candidate ->
                    val dx = plot.x + 0.5 - candidate.position.x
                    val dz = plot.z + 0.5 - candidate.position.z
                    dx * dx + dz * dz
                }.thenBy(FarmCareTarget::id),
            ).id == target.id
        }
    }

    private fun updateFarmDisease(runtime: FarmRuntime, now: Long) {
        if (runtime.state.phase != FarmPhase.CARE || runtime.state.careType != FarmCareType.DISEASE) {
            diseaseNextSpreadAt.remove(runtime.settings.id)
            return
        }
        if (players(runtime.region).isEmpty()) {
            diseaseNextSpreadAt[runtime.settings.id] = now + runtime.settings.diseaseSpreadSeconds * 1_000L
            return
        }
        val nextAt = diseaseNextSpreadAt.getOrPut(runtime.settings.id) {
            now + runtime.settings.diseaseSpreadSeconds * 1_000L
        }
        if (now < nextAt) return
        diseaseNextSpreadAt[runtime.settings.id] = now + runtime.settings.diseaseSpreadSeconds * 1_000L
        val current = runtime.state.careTargets.filter { it.role == FarmCareRole.DISEASED_CROP }
        if (current.size >= runtime.settings.diseaseMaxSpots) return
        val occupied = current.map(FarmCareTarget::position)
        val candidate = FarmCarePlanner.relocate(
            runtime.state.preparationPatch.filter { plot ->
                occupied.all { point ->
                    val dx = plot.x + 0.5 - point.x
                    val dz = plot.z + 0.5 - point.z
                    dx * dx + dz * dz >= 9.0
                }
            },
            occupied,
            runtime.state.sequence * 173L + current.size * 19L,
        ) ?: return
        val target = FarmCareTarget(
            id = (runtime.state.careTargets.maxOfOrNull(FarmCareTarget::id) ?: -1) + 1,
            role = FarmCareRole.DISEASED_CROP,
            position = FarmPointPosition(candidate.world, candidate.x + 0.5, candidate.y + 1.05, candidate.z + 0.5),
            required = 2,
        )
        val spread = FarmShiftEngine.spreadDisease(runtime.state, target, runtime.settings.diseaseMaxSpots)
        if (!spread.accepted) return
        runtime.state = spread.state
        ensureFarmCareTarget(runtime, target)
        players(runtime.region).forEach { player ->
            sendActionBar(player, MessageKey.FARM_CARE_DISEASE_SPREAD)
            if (settings.sounds) player.playSound(player.location, Sound.BLOCK_SCULK_SPREAD, 0.55f, 1.45f)
        }
        debug.event("farm_disease_spread", "zone" to runtime.settings.id, "target" to target.id, "total" to current.size + 1)
        persistAsync()
    }

    private fun pullFarmAnimalTowardHolder(mob: Mob, holder: Player) {
        if (!mob.isOnGround || mob.world != holder.world) return
        val delta = holder.location.toVector().subtract(mob.location.toVector()).setY(0.0)
        val distance = delta.length()
        if (distance <= 2.0) return
        val speed = (0.16 + distance * 0.025).coerceAtMost(0.42)
        val current = mob.velocity
        val pull = delta.normalize().multiply(speed)
        mob.velocity = current.multiply(0.25).setX(pull.x).setZ(pull.z)
    }

    private fun refreshFarmPoint(runtime: FarmRuntime, kind: FarmPointKind, actor: Player, reason: String) {
        when (kind) {
            FarmPointKind.TOOL, FarmPointKind.SEEDS, FarmPointKind.WATER -> {
                val supplyKind = when (kind) {
                    FarmPointKind.TOOL -> FarmSupplyKind.TOOL
                    FarmPointKind.SEEDS -> FarmSupplyKind.SEEDS
                    else -> FarmSupplyKind.WATER
                }
                removeSupplyEntities(SupplyKey(runtime.settings.id, supplyKind), reason)
                ensureFarmSupplies(runtime)
            }
            FarmPointKind.CRATES -> if (runtime.state.phase == FarmPhase.DELIVERY) {
                val position = farmLocations.zones[runtime.settings.id]?.get(FarmPointKind.CRATES)
                runtime.state = runtime.state.copy(
                    deliveryPosition = position?.let {
                        FarmDeliveryPosition(it.world, it.x, it.y, it.z)
                    } ?: selectDeliveryAnchor(runtime, actor.location),
                )
                clearDelivery(runtime, reason)
                ensureFarmDelivery(runtime)
                persistAsync()
            }
            FarmPointKind.CART, FarmPointKind.CUSTOMER -> {
                clearFarmContractScene(runtime, reason)
                ensureFarmContractScene(runtime)
            }
            FarmPointKind.RECEIVING -> refreshActiveFarmCarePoint(runtime, FarmPointKind.PEN, reason)
            FarmPointKind.HIVE,
            FarmPointKind.IRRIGATION,
            FarmPointKind.COVERS,
            FarmPointKind.SCARECROWS,
            FarmPointKind.PEN,
            -> refreshActiveFarmCarePoint(runtime, kind, reason)
            FarmPointKind.TRAVEL -> Unit
        }
    }

    private fun refreshActiveFarmCarePoint(runtime: FarmRuntime, kind: FarmPointKind, reason: String) {
        if (runtime.state.phase != FarmPhase.CARE) return
        if (kind == FarmPointKind.PEN) {
            if (runtime.state.careType == FarmCareType.ANIMAL_RESCUE) {
                removeFarmCareEntities(CareEntityKey(runtime.settings.id, -1), reason)
                ensureFarmAnimalPen(runtime)
            }
            return
        }
        val type = when (kind) {
            FarmPointKind.HIVE -> FarmCareType.POLLINATION
            FarmPointKind.IRRIGATION -> FarmCareType.IRRIGATION
            FarmPointKind.COVERS -> FarmCareType.STORM_COVERS
            FarmPointKind.SCARECROWS -> FarmCareType.SCARECROWS
            else -> return
        }
        if (runtime.state.careType != type) return
        val previousTargets = runtime.state.careTargets
        val rebuilt = buildFarmCareTargets(runtime, type, null) ?: return
        val merged = FarmCarePlanner.preserveProgress(previousTargets, rebuilt)
        clearFarmCare(runtime, reason)
        runtime.state = runtime.state.copy(careTargets = merged)
        ensureFarmCare(runtime)
        persistAsync()
    }

    private fun markFarmCareEntity(entity: Entity, runtime: FarmRuntime, targetId: Int, role: FarmCareRole) {
        entity.persistentDataContainer.set(careZoneKey, PersistentDataType.STRING, runtime.settings.id)
        entity.persistentDataContainer.set(careSequenceKey, PersistentDataType.LONG, runtime.state.sequence)
        entity.persistentDataContainer.set(careTargetKey, PersistentDataType.INTEGER, targetId)
        entity.persistentDataContainer.set(careRoleKey, PersistentDataType.STRING, role.name)
    }

    private fun removeFarmCareEntities(key: CareEntityKey, reason: String) {
        val ids = careEntities.remove(key).orEmpty()
        ids.forEach { id ->
            val entity = Bukkit.getEntity(id)
            if (entity is Mob) releaseAnimalFollower(key, entity, reason)
            entity?.remove()
        }
        animalFollowers.remove(key)
        if (ids.isNotEmpty()) debug.event("farm_care_entities_removed", "zone" to key.zoneId, "target" to key.targetId, "reason" to reason)
    }

    private fun clearFarmCare(runtime: FarmRuntime, reason: String) {
        careEntities.keys.filter { it.zoneId == runtime.settings.id }.toList().forEach { removeFarmCareEntities(it, reason) }
        players(runtime.region).forEach { pollenCharges.remove(it.uniqueId) }
        diseaseNextSpreadAt.remove(runtime.settings.id)
        careNextReconcileAt.remove(runtime.settings.id)
    }

    private fun releaseAnimalFollower(key: CareEntityKey, mob: Mob?, reason: String) {
        val playerId = animalFollowers.remove(key)
        mob?.pathfinder?.stopPathfinding()
        if (mob?.isLeashed == true) runCatching { mob.setLeashHolder(null) }
        if (playerId != null) {
            debug.event("farm_care_animal_released", "zone" to key.zoneId, "target" to key.targetId, "reason" to reason)
        }
    }

    private fun careRoleColor(role: FarmCareRole): Color = when (role) {
        FarmCareRole.SEEDER_HORSE, FarmCareRole.SEEDER_WAYPOINT -> FARM_PLANT_COLOR
        FarmCareRole.WEED_ROOT -> Color.fromRGB(194, 137, 70)
        FarmCareRole.VALVE -> Color.fromRGB(79, 195, 247)
        FarmCareRole.HIVE, FarmCareRole.FLOWER_PATCH -> FARM_AMBER_COLOR
        FarmCareRole.COVER_ANCHOR -> Color.fromRGB(154, 140, 255)
        FarmCareRole.SCARECROW -> FARM_DANGER_COLOR
        FarmCareRole.ANIMAL, FarmCareRole.PEN -> FARM_SUCCESS_COLOR
        FarmCareRole.DISEASED_CROP -> Color.fromRGB(190, 82, 214)
        FarmCareRole.MOLE_MOUND -> Color.fromRGB(151, 105, 72)
        FarmCareRole.APPLE -> Color.fromRGB(235, 67, 53)
    }

    private fun farmCareStartSound(type: FarmCareType): Sound = when (type) {
        FarmCareType.SEEDER -> Sound.ENTITY_HORSE_SADDLE
        FarmCareType.WEEDS -> Sound.BLOCK_ROOTED_DIRT_BREAK
        FarmCareType.IRRIGATION -> Sound.BLOCK_CHAIN_PLACE
        FarmCareType.POLLINATION -> Sound.ENTITY_BEE_POLLINATE
        FarmCareType.STORM_COVERS -> Sound.WEATHER_RAIN_ABOVE
        FarmCareType.SCARECROWS -> Sound.ENTITY_PARROT_FLY
        FarmCareType.ANIMAL_RESCUE -> Sound.ENTITY_CHICKEN_AMBIENT
        FarmCareType.DISEASE -> Sound.BLOCK_SCULK_SPREAD
        FarmCareType.MOLES -> Sound.ENTITY_RABBIT_JUMP
        FarmCareType.APPLE_HARVEST -> Sound.BLOCK_CHERRY_LEAVES_BREAK
    }

    private fun FarmCareType.adminStageName(): String = when (this) {
        FarmCareType.SEEDER -> "seeder"
        FarmCareType.WEEDS -> "weeds"
        FarmCareType.IRRIGATION -> "irrigation"
        FarmCareType.POLLINATION -> "pollination"
        FarmCareType.STORM_COVERS -> "covers"
        FarmCareType.SCARECROWS -> "scarecrows"
        FarmCareType.ANIMAL_RESCUE -> "animals"
        FarmCareType.DISEASE -> "disease"
        FarmCareType.MOLES -> "moles"
        FarmCareType.APPLE_HARVEST -> "apples"
    }

    private fun clearFarmPatchState(runtime: FarmRuntime) {
        commitFarmStateAfterPatchRecovery(runtime, runtime.state.copy(
            preparationPatch = emptyList(),
            preparationCrop = null,
            preparationReleased = false,
            tilledPlots = emptySet(),
            plantedPlots = emptySet(),
            preparationProgress = 0,
            plantingProgress = 0,
            preparationRequired = 0,
            droughtPlots = emptySet(),
            pestNestsInitialized = false,
            pestNests = emptyList(),
            pestAlive = 0,
        ))
    }

    private fun commitFarmStateAfterPatchRecovery(runtime: FarmRuntime, next: FarmShiftState) {
        val previous = runtime.state
        val recoveredPatch = previous.preparationPatch
        runtime.state = next
        try {
            persistBlocking()
        } catch (failure: Exception) {
            runtime.state = previous
            throw failure
        }
        recoveredPatch.forEach { position ->
            val soil = position.block() ?: return@forEach
            runCatching { farmBlockLedger.removeTransient(soil) }
                .onFailure { failure ->
                    plugin.logger.log(Level.WARNING, "Could not clear recovered farm ledger at $position", failure)
                }
        }
        patchRestoreProgress.remove(runtime.settings.id)
    }

    private fun maintainWetFarmBeds(runtime: FarmRuntime) {
        if (runtime.state.preparationPatch.isNotEmpty() && !runtime.state.preparationReleased) {
            if (releaseFarmPatch(runtime)) {
                runtime.state = runtime.state.copy(preparationReleased = true)
                persistAsync()
            }
            return
        }
        val positions = linkedSetOf<FarmPlotPosition>().apply {
            addAll(farmBlockRegistry.beds(runtime.settings.id))
            addAll(runtime.state.preparationPatch)
        }
        val crop = runtime.state.preparationCrop?.let(MaterialRules::material)
        val incidentActive = runtime.state.phase == FarmPhase.INCIDENT
        val temporarilyControlledPositions = buildSet {
            addAll(runtime.state.preparationPatch)
            addAll(runtime.state.droughtPlots)
            addAll(runtime.state.droughtDamagedPlots)
            runtime.state.pestDamagedCrops.mapTo(this) { it.position }
        }
        positions.forEach { position ->
            val soil = position.block() ?: return@forEach
            if (
                position !in temporarilyControlledPositions && !FarmBlockPolicy.isSelectableBed(
                    soil.type,
                    soil.getRelative(org.bukkit.block.BlockFace.UP).type,
                    runtime.settings.crops,
                )
            ) {
                farmBlockRegistry.removeBeds(runtime.settings.id, listOf(position))
                return@forEach
            }
            if (position in runtime.state.droughtPlots) {
                setDrySoil(soil)
                val above = soil.getRelative(org.bukkit.block.BlockFace.UP)
                if (!above.type.isAir && (above.type != Material.WATER || !hasActiveWater(runtime.settings.id))) {
                    above.setType(Material.AIR, false)
                }
                return@forEach
            }
            val awaitingMachine = runtime.state.phase == FarmPhase.CARE && runtime.state.careType == FarmCareType.SEEDER
            if (
                (runtime.state.phase == FarmPhase.PREPARATION || awaitingMachine) &&
                position in runtime.state.preparationPatch &&
                position !in runtime.state.tilledPlots &&
                soil.type != Material.DIRT
            ) {
                soil.setType(Material.DIRT, false)
            }
            if (position in runtime.state.tilledPlots && soil.type != Material.FARMLAND) setWetFarmland(soil)
            if (soil.type == Material.FARMLAND) setWetFarmland(soil)
            if (incidentActive) {
                val above = soil.getRelative(org.bukkit.block.BlockFace.UP)
                if (above.type == Material.WATER && !hasActiveWater(runtime.settings.id)) above.setType(Material.AIR, false)
                return@forEach
            }
            if (crop != null && position in runtime.state.plantedPlots) {
                val above = soil.getRelative(org.bukkit.block.BlockFace.UP)
                if (above.type == Material.WATER && !hasActiveWater(runtime.settings.id)) above.setType(Material.AIR, false)
                if (above.type.isAir && !farmBlockLedger.restoreActiveCrop(soil)) {
                    above.setBlockData(crop.createBlockData(), false)
                }
            }
        }
    }

    private fun setWetFarmland(block: Block) {
        if (block.type != Material.FARMLAND) block.setType(Material.FARMLAND, false)
        val farmland = (block.blockData as? Farmland) ?: (Material.FARMLAND.createBlockData() as Farmland)
        if (farmland.moisture != farmland.maximumMoisture) {
            farmland.moisture = farmland.maximumMoisture
            block.setBlockData(farmland, false)
        }
    }

    private fun setDrySoil(block: Block) {
        if (block.type != Material.DIRT) block.setType(Material.DIRT, false)
    }

    private fun ensureFarmSpecialIncident(runtime: FarmRuntime) {
        val type = runtime.state.incidentType
        if (runtime.state.phase != FarmPhase.INCIDENT || type !in SPECIAL_FARM_INCIDENT_TYPES) {
            specialIncidentScene.clearZone(runtime.settings.id, "inactive")
            nightShift.clearZone(runtime.settings.id)
            return
        }
        val activeType = requireNotNull(type)
        if (runtime.state.specialIncident == null) initializeFarmSpecialIncident(runtime, activeType)
        val special = runtime.state.specialIncident ?: return
        when (activeType) {
            FarmIncidentType.GIANT_CROP -> ensureGiantCropScene(runtime, special)
            FarmIncidentType.CHANNELS -> ensureChannelScene(runtime, special)
            FarmIncidentType.NIGHT_SHIFT -> {
                specialIncidentScene.clearZone(runtime.settings.id, "night_shift")
                nightShift.sync(runtime.settings.id, players(runtime.region), runtime.settings.specialIncidents.nightPlayerTime)
            }
            FarmIncidentType.MARKET -> specialIncidentScene.clearZone(runtime.settings.id, "market")
            else -> Unit
        }
    }

    private fun announceFarmSpecialIncident(runtime: FarmRuntime, type: FarmIncidentType) {
        val title = when (type) {
            FarmIncidentType.GIANT_CROP -> MessageKey.FARM_GIANT_CROP_STARTED
            FarmIncidentType.CHANNELS -> MessageKey.FARM_CHANNELS_STARTED
            FarmIncidentType.NIGHT_SHIFT -> MessageKey.FARM_NIGHT_SHIFT_STARTED
            FarmIncidentType.MARKET -> MessageKey.FARM_MARKET_STARTED
            else -> return
        }
        val sound = when (type) {
            FarmIncidentType.GIANT_CROP -> Sound.BLOCK_ROOTED_DIRT_BREAK
            FarmIncidentType.CHANNELS -> Sound.BLOCK_CONDUIT_ACTIVATE
            FarmIncidentType.NIGHT_SHIFT -> Sound.BLOCK_AMETHYST_BLOCK_RESONATE
            FarmIncidentType.MARKET -> Sound.ENTITY_VILLAGER_TRADE
            else -> Sound.BLOCK_NOTE_BLOCK_CHIME
        }
        broadcast(
            runtime.region,
            title,
            mapOf("total" to locale.text(runtime.state.incidentRequired)),
            sound = sound,
            title = true,
        )
    }

    private fun initializeFarmSpecialIncident(runtime: FarmRuntime, type: FarmIncidentType) {
        if (type !in SPECIAL_FARM_INCIDENT_TYPES || runtime.state.specialIncident != null) return
        val mature = discoverIncidentBeds(runtime).mapNotNull { plot ->
            val soil = plot.block() ?: return@mapNotNull null
            val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
            val age = crop.blockData as? Ageable ?: return@mapNotNull null
            if (age.age != age.maximumAge || crop.type.name !in runtime.settings.crops) return@mapNotNull null
            FarmMatureCrop(plot, crop.type.name)
        }
        val settings = runtime.settings.specialIncidents
        val plan = FarmSpecialIncidentPlanner.plan(
            type = type,
            sequence = runtime.state.sequence,
            matureCrops = mature,
            fallbackPlot = farmAreaCenter(runtime.state.preparationPatch),
            irrigationSource = point(runtime, FarmPointKind.IRRIGATION),
            giantHits = settings.giantCropHits,
            channelGates = settings.channelGateCount,
            nightCrops = settings.nightCropCount,
            marketCrops = settings.marketCropCount,
        ) ?: return
        val initialized = FarmSpecialIncidentEngine.initialize(runtime.state, type, plan.state, plan.required)
        if (!initialized.accepted) return
        runtime.state = initialized.state
        debug.event(
            "farm_special_incident_initialized",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "type" to type,
            "required" to runtime.state.incidentRequired,
        )
        persistAsync()
    }

    private fun ensureGiantCropScene(runtime: FarmRuntime, special: FarmSpecialIncidentState) {
        val point = special.points.firstOrNull() ?: return
        val location = Location(runtime.region.world, point.x, point.y, point.z)
        val item = ItemStack(special.crop?.let(MaterialRules::material) ?: Material.PUMPKIN)
        val scale = runtime.settings.specialIncidents.giantCropScale
        specialIncidentScene.ensure(
            FarmSpecialSceneSpec(
                runtime.settings.id,
                runtime.state.sequence,
                runtime.settings.displayViewRange,
                listOf(
                    FarmSpecialSceneObject(FarmSpecialSceneRole.GIANT_CROP, 0, location, item, scale),
                    FarmSpecialSceneObject(FarmSpecialSceneRole.GIANT_HITBOX, 0, location, scale = scale),
                ),
            ),
        )
    }

    private fun ensureChannelScene(runtime: FarmRuntime, special: FarmSpecialIncidentState) {
        val visual = runtime.settings.careVisuals.getValue(FarmCareRole.VALVE)
        val item = ItemStack(MaterialRules.material(visual.material)).apply {
            if (visual.customModelData > 0) editMeta { it.setCustomModelData(visual.customModelData) }
        }
        val objects = special.points.flatMapIndexed { index, point ->
            val location = Location(runtime.region.world, point.x, point.y, point.z)
            val active = index in special.active
            listOf(
                FarmSpecialSceneObject(
                    FarmSpecialSceneRole.CHANNEL_GATE,
                    index,
                    location.clone().add(0.0, 0.35, 0.0),
                    item,
                    runtime.settings.specialIncidents.channelDisplayScale,
                    active,
                ),
                FarmSpecialSceneObject(FarmSpecialSceneRole.CHANNEL_HITBOX, index, location, active = active),
            )
        }
        specialIncidentScene.ensure(
            FarmSpecialSceneSpec(runtime.settings.id, runtime.state.sequence, runtime.settings.displayViewRange, objects),
        )
    }

    private fun handleFarmSpecialSceneInteraction(player: Player, entity: Entity) {
        val identity = specialIncidentScene.metadata(entity) ?: return
        val runtime = farms.firstOrNull { it.settings.id == identity.zoneId } ?: return
        if (!hasAccess(player, runtime.settings.permission) || !runtime.region.contains(player.location)) return
        if (runtime.state.sequence != identity.sequence || runtime.state.phase != FarmPhase.INCIDENT) return
        when (identity.role) {
            FarmSpecialSceneRole.CHANNEL_GATE, FarmSpecialSceneRole.CHANNEL_HITBOX -> {
                if (runtime.state.incidentType != FarmIncidentType.CHANNELS) return
                if (!allowInteraction("farm-channel:${identity.zoneId}:${identity.index}:${player.uniqueId}", 250)) return
                val result = FarmSpecialIncidentEngine.toggleChannelGate(runtime.state, identity.index, player.uniqueId)
                if (!result.accepted) return
                if (settings.sounds) {
                    player.playSound(entity.location, Sound.BLOCK_LEVER_CLICK, 0.8f, if (identity.index in result.state.specialIncident.orEmptyActive()) 1.45f else 0.8f)
                }
                applyFarmResult(runtime, result, player)
                ensureFarmSpecialIncident(runtime)
            }
            FarmSpecialSceneRole.GIANT_CROP, FarmSpecialSceneRole.GIANT_HITBOX ->
                sendActionBar(player, MessageKey.FARM_GIANT_CROP_TOOL)
        }
    }

    private fun FarmSpecialIncidentState?.orEmptyActive(): Set<Int> = this?.active.orEmpty()

    private fun handleFarmSpecialSceneDamage(event: EntityDamageEvent) {
        val identity = specialIncidentScene.metadata(event.entity) ?: return
        if (identity.role !in setOf(FarmSpecialSceneRole.GIANT_CROP, FarmSpecialSceneRole.GIANT_HITBOX)) return
        val player = (event as? EntityDamageByEntityEvent)?.let { damage ->
            when (val source = damage.damager) {
                is Player -> source
                is Projectile -> source.shooter as? Player
                else -> null
            }
        } ?: return
        val runtime = farms.firstOrNull { it.settings.id == identity.zoneId } ?: return
        if (!hasAccess(player, runtime.settings.permission) || !runtime.region.contains(player.location)) return
        if (
            runtime.state.sequence != identity.sequence || runtime.state.phase != FarmPhase.INCIDENT ||
            runtime.state.incidentType != FarmIncidentType.GIANT_CROP
        ) return
        if (!MaterialRules.isHoe(player.inventory.itemInMainHand)) {
            sendActionBar(player, MessageKey.FARM_GIANT_CROP_TOOL)
            return
        }
        if (!allowInteraction("farm-giant:${identity.zoneId}:${player.uniqueId}", 180)) return
        val result = FarmSpecialIncidentEngine.damageGiantCrop(runtime.state, player.uniqueId)
        if (!result.accepted) return
        if (settings.particles) {
            val crop = runtime.state.specialIncident?.crop?.let(MaterialRules::material) ?: Material.PUMPKIN
            event.entity.world.spawnParticle(Particle.BLOCK, event.entity.location, 16, 0.65, 0.8, 0.65, crop.createBlockData())
        }
        if (settings.sounds) {
            player.playSound(event.entity.location, Sound.BLOCK_WOOD_HIT, 0.9f, 0.75f + result.state.incidentProgress * 0.025f)
        }
        applyFarmResult(runtime, result, player)
    }

    private fun handleFarmMarketDecision(player: Player, click: FarmMarketClick) {
        val runtime = farms.firstOrNull { it.settings.id == click.zoneId } ?: return
        if (!hasAccess(player, runtime.settings.permission) || !runtime.region.contains(player.location)) return
        if (
            runtime.state.sequence != click.sequence || runtime.state.phase != FarmPhase.INCIDENT ||
            runtime.state.incidentType != FarmIncidentType.MARKET
        ) return
        val special = runtime.state.specialIncident ?: return
        val result = when (click.decision) {
            FarmMarketDecision.ACCEPT -> FarmSpecialIncidentEngine.acceptMarket(runtime.state)
            FarmMarketDecision.DECLINE -> FarmSpecialIncidentEngine.declineMarket(runtime.state)
        }
        if (!result.accepted) return
        player.closeInventory()
        if (click.decision == FarmMarketDecision.ACCEPT) {
            sendActionBar(
                player,
                MessageKey.FARM_MARKET_ACCEPTED,
                mapOf(
                    "crop" to MaterialRules.cropComponent(MaterialRules.material(requireNotNull(special.crop))),
                    "total" to locale.text(runtime.state.incidentRequired),
                ),
            )
            if (settings.sounds) player.playSound(player.location, Sound.ENTITY_VILLAGER_YES, 0.85f, 1.15f)
        } else {
            sendActionBar(player, MessageKey.FARM_MARKET_DECLINED)
        }
        applyFarmResult(runtime, result, player)
    }

    private fun specialIncidentName(type: FarmIncidentType, audience: Player): Component = locale.renderPath(
        "incident.${type.specialId()}.name",
        audience,
    )

    private fun FarmIncidentType.specialId(): String = when (this) {
        FarmIncidentType.GIANT_CROP -> "giant-crop"
        FarmIncidentType.CHANNELS -> "channels"
        FarmIncidentType.NIGHT_SHIFT -> "night-shift"
        FarmIncidentType.MARKET -> "market"
        FarmIncidentType.PESTS -> "pests"
        FarmIncidentType.DROUGHT -> "drought"
    }

    private fun ensureFarmDroughtTargets(runtime: FarmRuntime) {
        val active = runtime.state.phase == FarmPhase.INCIDENT &&
            (runtime.state.incidentType ?: FarmIncidentType.PESTS) == FarmIncidentType.DROUGHT
        if (!active) {
            droughtGrowth.remove(runtime.settings.id)
            return
        }
        val remaining = (runtime.state.incidentRequired - runtime.state.incidentProgress).coerceAtLeast(0)
        if (remaining == 0) return
        runtime.state.droughtPlots.forEach { position ->
            position.block()?.let { soil ->
                setDrySoil(soil)
                soil.getRelative(org.bukkit.block.BlockFace.UP).setType(Material.AIR, false)
            }
        }
        val now = clock()
        val growth = droughtGrowth.getOrPut(runtime.settings.id) {
            DroughtGrowthRuntime(
                startedAt = now,
                spawned = runtime.state.incidentProgress + runtime.state.droughtPlots.size,
            )
        }
        growth.spawned = maxOf(growth.spawned, runtime.state.incidentProgress + runtime.state.droughtPlots.size)
        val spawnLimit = FarmIncidentPlanner.droughtSpawnLimit(
            required = runtime.state.incidentRequired,
            initial = runtime.settings.droughtInitialBeds.coerceAtMost(runtime.state.incidentRequired),
            growthStep = runtime.settings.droughtGrowthBeds,
            growthIntervalMillis = runtime.settings.droughtGrowthSeconds * 1_000L,
            startedAt = growth.startedAt,
            now = now,
        )
        val requested = (spawnLimit - growth.spawned).coerceAtLeast(0).coerceAtMost(remaining)
        if (requested == 0) return
        val existing = runtime.state.droughtPlots
        val candidates = discoverIncidentBeds(runtime).filter { position ->
            val soil = position.block() ?: return@filter false
            soil.type in FARM_SOIL_TYPES &&
                (position !in runtime.state.droughtDamagedPlots || position in existing)
        }
        if (candidates.isEmpty()) return
        val desiredActive = (existing.size + requested).coerceAtMost(candidates.size).coerceAtMost(64)
        val selected = FarmIncidentPlanner.growDroughtPatches(
            candidates = candidates + existing,
            existing = existing,
            targetSize = desiredActive,
            patchCount = runtime.settings.droughtPatches,
            selectionIndex = runtime.state.sequence * 37L,
        )
        val targets = selected - existing
        targets.forEach { position ->
            val soil = position.block() ?: return@forEach
            farmBlockLedger.captureActiveCropIfPresent(soil, runtime.settings.id)
            setDrySoil(soil)
            soil.getRelative(org.bukkit.block.BlockFace.UP).setType(Material.AIR, false)
        }
        runtime.state = runtime.state.copy(
            droughtPlots = existing + targets,
            droughtDamagedPlots = runtime.state.droughtDamagedPlots + targets,
        )
        growth.spawned += targets.size
        persistAsync()
        debug.event(
            "farm_drought_patch_grown",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "added" to targets.size,
            "active" to runtime.state.droughtPlots.size,
            "spawned" to growth.spawned,
            "limit" to spawnLimit,
        )
        if (targets.size < requested && allowInteraction("farm-care-missing:${runtime.settings.id}", 10_000)) {
            debug.event(
                "farm_care_targets_limited",
                "zone" to runtime.settings.id,
                "phase" to runtime.state.phase,
                "wanted" to requested,
                "added" to targets.size,
            )
        }
    }

    private fun horizontalDistanceSquared(first: FarmPlotPosition, second: FarmPlotPosition): Int {
        val dx = first.x - second.x
        val dz = first.z - second.z
        return dx * dx + dz * dz
    }

    private fun pourFarmWater(runtime: FarmRuntime, source: Block, player: Player) {
        val radius = FARM_WATER_RADIUS
        val flowId = nextWaterFlowId++
        val tracker = waterFlows.getOrPut(runtime.settings.id, ::FarmWaterFlowTracker)
        val beforeWater = mutableSetOf<FarmPlotPosition>()
        val existingItems = source.world.getNearbyEntities(
            source.location.toCenterLocation(),
            radius + 2.0,
            4.0,
            radius + 2.0,
        ).filterIsInstance<Item>().mapTo(mutableSetOf(), Entity::getUniqueId)
        var removedDrops = 0
        for (x in source.x - radius..source.x + radius) {
            for (y in source.y - 1..source.y + 2) {
                for (z in source.z - radius..source.z + radius) {
                    val block = source.world.getBlockAt(x, y, z)
                    if (runtime.region.contains(block.location) && block.type == Material.WATER) {
                        beforeWater += block.toFarmPlotPosition()
                    }
                }
            }
        }
        fun observeWaterAndDrops() {
            for (x in source.x - radius..source.x + radius) {
                for (y in source.y - 1..source.y + 2) {
                    for (z in source.z - radius..source.z + radius) {
                        val block = source.world.getBlockAt(x, y, z)
                        if (
                            runtime.region.contains(block.location) && block.type == Material.WATER &&
                            block.toFarmPlotPosition() !in beforeWater
                        ) {
                            tracker.observe(flowId, block.toFarmPlotPosition())
                        }
                    }
                }
            }
            source.world.getNearbyEntities(
                source.location.toCenterLocation(),
                radius + 2.0,
                4.0,
                radius + 2.0,
            ).filterIsInstance<Item>().filter { item ->
                item.uniqueId !in existingItems && item.itemStack.type in FARM_WATER_DROP_TYPES &&
                    runtime.region.contains(item.location)
            }.forEach { item ->
                existingItems += item.uniqueId
                item.remove()
                removedDrops++
            }
        }
        val sourcePosition = source.toFarmPlotPosition()
        if (source.type.name in runtime.settings.crops && !MaterialRules.isFixedBlockCrop(source.type)) {
            val soil = source.getRelative(org.bukkit.block.BlockFace.DOWN)
            if (soil.type in FARM_SOIL_TYPES) {
                farmBlockLedger.captureActiveCropIfPresent(soil, runtime.settings.id)
                val position = soil.toFarmPlotPosition()
                farmBlockRegistry.addBeds(runtime.settings.id, listOf(position))
                runtime.state = runtime.state.copy(droughtDamagedPlots = runtime.state.droughtDamagedPlots + position)
                source.setType(Material.AIR, false)
            }
        }
        tracker.start(flowId, sourcePosition)
        source.setType(Material.WATER, true)
        val droughtBefore = runtime.state.droughtPlots
        val reachedImmediately = FarmWaterPlanner.reachedPlotsWithinRadius(
            sourcePosition,
            droughtBefore,
            radius,
        )
        reachedImmediately.forEach { position -> position.block()?.let(::setWetFarmland) }
        val completedPatches = FarmWaterPlanner.completedPatchCount(droughtBefore, reachedImmediately)
        if (currentOrder(runtime) != null && reachedImmediately.isNotEmpty()) {
            var state = runtime.state
            var contribution = 0
            val events = mutableListOf<ShiftEvent>()
            reachedImmediately.forEach { position ->
                state = state.copy(droughtPlots = state.droughtPlots - position)
                val result = FarmShiftEngine.waterDrySoil(state, player.uniqueId)
                state = result.state
                contribution += result.contribution
                result.events.forEach { event -> if (event !in events) events += event }
            }
            applyFarmResult(runtime, EngineResult(state, true, contribution, events), player)
        }
        if (completedPatches > 0) {
            playFarmMilestone(runtime, Sound.BLOCK_BEACON_POWER_SELECT, 1.15f)
            debug.event(
                "farm_drought_patch_watered",
                "zone" to runtime.settings.id,
                "player" to player.name,
                "patches" to completedPatches,
            )
        }
        for (delay in 1L..19L step 2L) {
            Tasks.scheduler.runLater(delay) {
                if (tracker.isActive(flowId)) observeWaterAndDrops()
            }
        }
        if (settings.sounds) player.playSound(source.location, Sound.ITEM_BUCKET_EMPTY, 0.8f, 1.05f)
        if (settings.particles) {
            player.spawnParticle(Particle.SPLASH, source.location.toCenterLocation(), 8, 0.35, 0.18, 0.35, 0.05)
        }
        debug.event(
            "farm_water_poured",
            "player" to player.name,
            "zone" to runtime.settings.id,
            "x" to source.x,
            "y" to source.y,
            "z" to source.z,
            "watered_plots" to reachedImmediately.size,
        )
        Tasks.scheduler.runLater(21L) {
            if (!tracker.isActive(flowId)) return@runLater
            observeWaterAndDrops()
            val trackedWater = tracker.positions(flowId)
            tracker.finish(flowId).forEach { position ->
                position.block()?.takeIf { it.type == Material.WATER }?.setType(Material.AIR, false)
            }
            if (tracker.isEmpty()) waterFlows.remove(runtime.settings.id)
            ensureFarmDroughtTargets(runtime)
            persistAsync()
            if (!hasActiveWater(runtime.settings.id) && runtime.settings.id in pendingIncidentRestore) {
                pendingIncidentRestore.remove(runtime.settings.id)
            }
            debug.event(
                "farm_water_settled",
                "player" to player.name,
                "zone" to runtime.settings.id,
                "removed_water" to trackedWater.size,
                "removed_drops" to removedDrops,
                "watered_plots" to reachedImmediately.size,
                "remaining" to runtime.state.droughtPlots.size,
            )
        }
    }

    private fun restoreIncidentCrops(runtime: FarmRuntime, limit: Int = Int.MAX_VALUE) {
        if (hasActiveWater(runtime.settings.id)) {
            pendingIncidentRestore += runtime.settings.id
            return
        }
        val before = runtime.state
        runtime.state = FarmIncidentRecovery.recover(
            before,
            restoreDrought = { position -> restoreDroughtCrop(runtime, position) },
            restorePest = { damage -> restorePestCrop(runtime, damage) },
            restoreSpecial = { damage -> restorePestCrop(runtime, damage) },
            limit = limit,
        )
        if (runtime.state != before) persistAsync()
        if (FarmIncidentRecovery.pending(runtime.state)) {
            if (allowInteraction("farm-incident-recovery:${runtime.settings.id}", TimeUnit.MINUTES.toMillis(1))) {
                plugin.logger.warning(
                    "Farm incident recovery in ${runtime.settings.id} is waiting for " +
                        "${runtime.state.droughtDamagedPlots.size + runtime.state.pestDamagedCrops.size + runtime.state.specialDamagedCrops.size} loaded plot(s)",
                )
            }
        } else {
            pendingIncidentRestore.remove(runtime.settings.id)
        }
    }

    private fun restoreDroughtCrop(runtime: FarmRuntime, position: FarmPlotPosition): Boolean {
        val soil = position.block() ?: return false
        return runCatching {
            setWetFarmland(soil)
            val restored = farmBlockLedger.restoreActiveCrop(soil)
            if (restored && position !in runtime.state.preparationPatch) farmBlockLedger.removeTransient(soil)
            restored
        }.getOrElse { failure ->
            plugin.logger.log(Level.SEVERE, "Could not restore drought-damaged farm plot $position", failure)
            false
        }
    }

    private fun restorePestCrop(runtime: FarmRuntime, damage: FarmCropDamage): Boolean {
        val soil = damage.position.block() ?: return false
        return runCatching {
            setWetFarmland(soil)
            val crop = MaterialRules.material(damage.crop)
            val above = soil.getRelative(org.bukkit.block.BlockFace.UP)
            val restored = when {
                above.type == crop -> true
                above.type.isAir || above.type == Material.WATER -> {
                    above.setBlockData(crop.createBlockData(), false)
                    farmBlockLedger.captureActiveCrop(soil, runtime.settings.id)
                    true
                }
                else -> false
            }
            if (restored && damage.position !in runtime.state.preparationPatch) farmBlockLedger.removeTransient(soil)
            restored
        }.getOrElse { failure ->
            plugin.logger.log(Level.SEVERE, "Could not restore pest-damaged farm plot ${damage.position}", failure)
            false
        }
    }

    private fun selectDeliveryAnchor(runtime: FarmRuntime, source: Location): FarmDeliveryPosition {
        val sources = farmPlacementSources(runtime, source)
        val candidates = findDeliveryBedCandidates(runtime, sources, runtime.settings.placementSearchRadius)
        val receiving = point(runtime, FarmPointKind.RECEIVING)
        val selected = FarmDeliveryPlanner.selectTargets(
            candidates = candidates,
            objectiveX = receiving.x,
            objectiveZ = receiving.z,
            participants = sources.map { it.x to it.z },
            minimumObjectiveDistance = runtime.settings.placementMinObjectiveDistance.toDouble(),
            maximumParticipantDistance = runtime.settings.placementMaxPlayerDistance.toDouble(),
            targetCount = 1,
            selectionIndex = runtime.state.sequence + if (candidates.isEmpty()) 0 else random.nextInt(minOf(candidates.size, 24)),
        ).firstOrNull()
        if (selected != null) {
            debug.event(
                "farm_delivery_anchor_selected",
                "zone" to runtime.settings.id,
                "sequence" to runtime.state.sequence,
                "candidates" to candidates.size,
                "x" to selected.x,
                "y" to selected.y,
                "z" to selected.z,
            )
            return selected
        }
        val fallback = point(runtime, FarmPointKind.CRATES)
        plugin.logger.warning(
            "No indexed farm bed is available for delivery crates in ${runtime.settings.id}; using the configured fallback",
        )
        return FarmDeliveryPosition(fallback.world, fallback.x, fallback.y, fallback.z)
    }

    private fun farmPlacementSources(runtime: FarmRuntime, preferred: Location?): List<Location> {
        val candidates = buildList {
            preferred?.takeIf { it.world == runtime.region.world && runtime.region.contains(it) }?.let(::add)
            players(runtime.region).map(Player::getLocation).forEach(::add)
        }.distinctBy { Triple(it.blockX, it.blockY, it.blockZ) }
        if (candidates.isNotEmpty()) return candidates.take(8)
        val fallback = farmAreaCenter(runtime.state.preparationPatch)?.location()
            ?: point(runtime, FarmPointKind.RECEIVING).let { Location(runtime.region.world, it.x, it.y, it.z) }
        return listOf(fallback)
    }

    private fun findDeliveryCandidates(
        runtime: FarmRuntime,
        sources: Collection<Location>,
        radius: Int,
    ): List<FarmDeliveryPosition> = sources.asSequence()
        .take(8)
        .flatMap { source -> findDeliveryCandidates(runtime, source, radius).asSequence() }
        .distinct()
        .toList()

    private fun findDeliveryCandidates(
        runtime: FarmRuntime,
        source: Location,
        radius: Int,
    ): List<FarmDeliveryPosition> {
        val world = runtime.region.world
        if (source.world != world) return emptyList()
        val receiving = point(runtime, FarmPointKind.RECEIVING)
        val receivingExclusion = runtime.settings.delivery.radius + 1.5
        val receivingExclusionSquared = receivingExclusion * receivingExclusion
        val patchColumns = runtime.state.preparationPatch.mapTo(hashSetOf()) { it.x to it.z }
        val candidates = mutableListOf<FarmDeliveryPosition>()
        val sourceX = source.blockX
        val sourceY = source.blockY
        val sourceZ = source.blockZ
        val verticalOffsets = listOf(0, -1, 1, -2, 2)
        for (x in sourceX - radius..sourceX + radius) {
            for (z in sourceZ - radius..sourceZ + radius) {
                val dx = x - sourceX
                val dz = z - sourceZ
                if (dx * dx + dz * dz > radius * radius || (x to z) in patchColumns) continue
                val receivingDx = x + 0.5 - receiving.x
                val receivingDz = z + 0.5 - receiving.z
                if (receivingDx * receivingDx + receivingDz * receivingDz < receivingExclusionSquared) continue
                verticalOffsets.firstNotNullOfOrNull { offset ->
                    val feetY = sourceY + offset
                    if (!world.isChunkLoaded(x shr 4, z shr 4)) return@firstNotNullOfOrNull null
                    val location = Location(world, x + 0.5, feetY.toDouble(), z + 0.5)
                    if (!runtime.region.contains(location)) return@firstNotNullOfOrNull null
                    val floor = world.getBlockAt(x, feetY - 1, z)
                    val feet = world.getBlockAt(x, feetY, z)
                    val head = world.getBlockAt(x, feetY + 1, z)
                    if (!floor.type.isSolid || !feet.type.isAir || !head.type.isAir) return@firstNotNullOfOrNull null
                    FarmDeliveryPosition(world.name, location.x, location.y, location.z)
                }?.let(candidates::add)
            }
        }
        return candidates
    }

    private fun findDeliveryBedCandidates(
        runtime: FarmRuntime,
        sources: Collection<Location>,
        radius: Int,
    ): List<FarmDeliveryPosition> {
        val world = runtime.region.world
        val sourcePoints = sources.filter { it.world == world }.take(8)
        if (sourcePoints.isEmpty()) return emptyList()
        val radiusSquared = radius.toDouble() * radius
        return farmBlockRegistry.beds(runtime.settings.id).asSequence()
            .filter { it.world == world.name && world.isChunkLoaded(it.x shr 4, it.z shr 4) }
            .filter { bed ->
                sourcePoints.any { source ->
                    val dx = bed.x + 0.5 - source.x
                    val dz = bed.z + 0.5 - source.z
                    dx * dx + dz * dz <= radiusSquared
                }
            }
            .mapNotNull { bed ->
                val soil = world.getBlockAt(bed.x, bed.y, bed.z)
                val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
                val overhead = crop.getRelative(org.bukkit.block.BlockFace.UP)
                if (!FarmBlockPolicy.isSelectableBed(soil.type, crop.type, runtime.settings.crops)) return@mapNotNull null
                if (crop.type.name !in runtime.settings.crops) return@mapNotNull null
                if (!overhead.type.isAir || !runtime.region.contains(crop.location)) return@mapNotNull null
                FarmDeliveryPosition(world.name, bed.x + 0.5, bed.y + 1.05, bed.z + 0.5)
            }
            .distinct()
            .toList()
    }

    private fun ensureFarmContractScene(runtime: FarmRuntime) {
        val order = currentOrder(runtime)
        if (order == null || runtime.state.phase == FarmPhase.IDLE) {
            clearFarmContractScene(runtime, "contract_inactive")
            return
        }
        val recoveredMilestone = FarmContractPlanner.harvestMilestone(runtime.state.completed(order), order.totalRequired)
        if (recoveredMilestone > runtime.state.harvestMilestone) {
            runtime.state = runtime.state.copy(harvestMilestone = recoveredMilestone)
            persistAsync()
            debug.event(
                "farm_cart_progress_recovered",
                "zone" to runtime.settings.id,
                "sequence" to runtime.state.sequence,
                "milestone" to recoveredMilestone,
            )
        }
        val customerPoint = point(runtime, FarmPointKind.CUSTOMER)
        val cartPoint = point(runtime, FarmPointKind.CART)
        val cartVisual = runtime.settings.contractCartVisual
        val customerWorld = Bukkit.getWorld(customerPoint.world) ?: return
        val cartWorld = Bukkit.getWorld(cartPoint.world) ?: return
        if (customerWorld !== cartWorld) return
        val customerLocation = Location(customerWorld, customerPoint.x, customerPoint.y, customerPoint.z, customerPoint.yaw, 0f)
        val cartLocation = Location(
            cartWorld,
            cartPoint.x,
            cartPoint.y + cartVisual.yOffset,
            cartPoint.z,
            cartPoint.yaw + cartVisual.yawOffset,
            0f,
        )
        if (!runtime.region.contains(customerLocation) || !runtime.region.contains(cartLocation)) return
        @Suppress("DEPRECATION")
        fun sceneItem(material: String, customModelData: Int): ItemStack =
            ItemStack(MaterialRules.material(material)).also { item ->
                if (customModelData <= 0) return@also
                val meta = item.itemMeta
                meta.setCustomModelData(customModelData)
                item.itemMeta = meta
            }
        contractScene.ensure(
            FarmContractSceneSpec(
                zoneId = runtime.settings.id,
                sequence = runtime.state.sequence,
                customerType = order.customerType,
                customerLocation = customerLocation,
                cartLocation = cartLocation,
                cartItem = sceneItem(cartVisual.material, cartVisual.customModelData),
                cartDisplayTransform = cartVisual.displayTransform,
                cartScale = cartVisual.scale,
                loadItem = sceneItem(order.cartLoadMaterial, order.cartLoadCustomModelData),
                loadCount = runtime.state.deliveredCrates.size.coerceIn(0, runtime.settings.delivery.crates),
                loadYOffset = cartVisual.loadYOffset,
                loadScale = cartVisual.loadScale,
                viewRange = cartVisual.viewRange,
            ),
        )
    }

    private fun handleFarmContractSceneInteraction(player: Player, entity: Entity) {
        val identity = contractScene.metadata(entity) ?: return
        val runtime = farms.firstOrNull { it.settings.id == identity.zoneId } ?: return
        if (runtime.state.sequence != identity.sequence || !runtime.region.contains(entity.location)) return
        if (!hasAccess(player, runtime.settings.permission)) {
            sendChat(player, MessageKey.ZONE_LOCKED)
            return
        }
        if (!allowInteraction("farm-contract-scene:${identity.zoneId}:${identity.role}:${player.uniqueId}", 700)) return
        val order = currentOrder(runtime) ?: return
        when (identity.role) {
            FarmContractSceneRole.CUSTOMER -> {
                val market = runtime.state.specialIncident?.takeIf {
                    runtime.state.phase == FarmPhase.INCIDENT && runtime.state.incidentType == FarmIncidentType.MARKET
                }
                if (market != null) {
                    if (!market.marketAccepted) {
                        marketMenu.open(
                            player,
                            runtime.settings.id,
                            runtime.state.sequence,
                            MaterialRules.material(requireNotNull(market.crop)),
                            runtime.state.incidentRequired,
                            runtime.settings.specialIncidents.marketMoneyBonusPercent,
                        )
                    } else {
                        sendActionBar(
                            player,
                            MessageKey.FARM_SPECIAL_PROGRESS,
                            mapOf(
                                "event" to specialIncidentName(FarmIncidentType.MARKET, player),
                                "done" to locale.text(runtime.state.incidentProgress),
                                "total" to locale.text(runtime.state.incidentRequired),
                            ),
                        )
                    }
                    return
                }
                sendActionBar(
                    player,
                    MessageKey.FARM_CUSTOMER_REMINDER,
                    mapOf(
                        "customer" to locale.renderPath("customer.${order.customerType.name.lowercase()}.name", player),
                        "order" to locale.renderPath("order.farm.${order.id}", player),
                    ),
                )
                if (settings.sounds) player.playSound(entity.location, Sound.ENTITY_VILLAGER_YES, 0.65f, 1.05f)
            }
            FarmContractSceneRole.CART, FarmContractSceneRole.CART_INTERACTION, FarmContractSceneRole.CART_LOAD -> sendActionBar(
                player,
                MessageKey.FARM_CART_PROGRESS,
                mapOf(
                    "order" to locale.renderPath("order.farm.${order.id}", player),
                    "done" to locale.text(runtime.state.deliveredCrates.size),
                    "total" to locale.text(runtime.settings.delivery.crates),
                ),
            )
        }
        debug.event(
            "farm_contract_scene_interaction",
            "zone" to identity.zoneId,
            "sequence" to identity.sequence,
            "order" to order.id,
            "role" to identity.role,
            "player" to player.name,
        )
    }

    private fun clearFarmContractScene(runtime: FarmRuntime, reason: String) {
        contractScene.clearZone(runtime.settings.id, reason)
    }

    private fun ensureFarmDelivery(runtime: FarmRuntime) {
        if (runtime.state.phase != FarmPhase.DELIVERY) {
            if (deliveryKeys(runtime).isNotEmpty()) {
                clearDelivery(runtime, "phase_inactive")
            }
            return
        }
        val zoneId = runtime.settings.id
        val position = runtime.state.deliveryPosition ?: run {
            val source = players(runtime.region).firstOrNull()?.location
                ?: farmAreaCenter(runtime.state.preparationPatch)?.location()
                ?: point(runtime, FarmPointKind.CRATES).let { Location(runtime.region.world, it.x, it.y, it.z) }
            selectDeliveryAnchor(runtime, source)
        }
        if (runtime.state.deliveryPosition == null) {
            runtime.state = runtime.state.copy(deliveryPosition = position)
            persistAsync()
        }
        repeat(runtime.settings.delivery.crates) { index ->
            val key = DeliveryKey(zoneId, index)
            if (index in runtime.state.deliveredCrates) {
                removeDeliveryEntities(key, "already_delivered")
                deliveryCarriers.remove(key)
                carriedDisplays.remove(key)?.let(Bukkit::getEntity)?.remove()
                return@repeat
            }
            val carrierId = deliveryCarriers[key]
            if (carrierId != null) {
                val carrier = Bukkit.getPlayer(carrierId)
                if (carrier == null || !carrier.isOnline) {
                    returnDelivery(runtime, key, carrier, "carrier_offline", notify = false)
                } else {
                    handleDeliveryMovement(runtime, key, carrier, carrier.location)
                    updateCarriedDisplay(key, carrier)
                }
                return@repeat
            }
            val activeGroundEntities = deliveryEntities[key].orEmpty().mapNotNull(Bukkit::getEntity).filter { entity ->
                entity.isValid &&
                    entity.persistentDataContainer.get(deliveryZoneKey, PersistentDataType.STRING) == zoneId &&
                    entity.persistentDataContainer.get(deliverySequenceKey, PersistentDataType.LONG) == runtime.state.sequence &&
                    entity.persistentDataContainer.get(deliveryIndexKey, PersistentDataType.INTEGER) == index
            }
            if (activeGroundEntities.size >= 2) {
                deliveryEntities[key] = activeGroundEntities.mapTo(mutableSetOf(), Entity::getUniqueId)
                return@repeat
            }
            removeDeliveryEntities(key, "replace_ground_entities")
            spawnDeliveryCrate(runtime, key, position)
        }
    }

    private fun spawnDeliveryCrate(runtime: FarmRuntime, key: DeliveryKey, position: FarmDeliveryPosition) {
        val world = Bukkit.getWorld(position.world) ?: return
        val location = deliveryCrateLocation(runtime, position, key.index) ?: run {
            plugin.logger.severe("Farm ${runtime.settings.id} has no indexed bed for delivery crate ${key.index}")
            return
        }
        if (!world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) return
        if (!runtime.region.contains(location)) {
            plugin.logger.severe("Farm delivery position left ${runtime.region.label} for ${runtime.settings.id}; crate ${key.index} was not spawned")
            return
        }
        val display = world.spawn(
            location.clone().add(0.0, runtime.settings.delivery.displayYOffset, 0.0),
            ItemDisplay::class.java,
        ) { entity ->
            entity.setItemStack(deliveryItemStack(runtime))
            entity.itemDisplayTransform = runtime.settings.delivery.displayTransform.bukkit
            entity.uniformScale(runtime.settings.delivery.displayScale)
            entity.viewRange = runtime.settings.delivery.displayViewRange
            entity.isGlowing = true
            entity.isPersistent = false
            markDeliveryEntity(entity, runtime, key.index)
        }
        val interaction = world.spawn(location, Interaction::class.java) { entity ->
            entity.interactionWidth = 1.35f
            entity.interactionHeight = 1.45f
            entity.isResponsive = true
            entity.isPersistent = false
            markDeliveryEntity(entity, runtime, key.index)
        }
        deliveryEntities.getOrPut(key) { mutableSetOf() }.addAll(listOf(display.uniqueId, interaction.uniqueId))
        debug.event(
            "farm_delivery_spawned",
            "zone" to key.zoneId,
            "sequence" to runtime.state.sequence,
            "crate" to key.index,
            "x" to location.x,
            "y" to location.y,
            "z" to location.z,
        )
    }

    private fun deliveryCrateLocation(runtime: FarmRuntime, position: FarmDeliveryPosition, index: Int): Location? {
        val world = requireNotNull(Bukkit.getWorld(position.world))
        val anchor = Location(world, position.x, position.y, position.z)
        val nearby = findDeliveryBedCandidates(runtime, listOf(anchor), runtime.settings.delivery.spawnRadius)
        val candidates = if (nearby.size >= runtime.settings.delivery.crates) {
            nearby
        } else {
            findDeliveryBedCandidates(
                runtime,
                farmPlacementSources(runtime, anchor),
                runtime.settings.placementSearchRadius,
            )
        }
        val selectedTargets = FarmDeliveryPlanner.selectTargets(
            candidates = candidates,
            objectiveX = position.x,
            objectiveZ = position.z,
            participants = listOf(position.x to position.z),
            minimumObjectiveDistance = 0.0,
            maximumParticipantDistance = runtime.settings.delivery.spawnRadius.toDouble(),
            targetCount = runtime.settings.delivery.crates,
            selectionIndex = runtime.state.sequence,
            minimumTargetDistance = runtime.settings.delivery.minCrateSpacing,
        )
        selectedTargets.getOrNull(index)?.let { selected ->
            return Location(world, selected.x, selected.y, selected.z)
        }
        return null
    }

    private fun markDeliveryEntity(entity: Entity, runtime: FarmRuntime, index: Int) {
        entity.persistentDataContainer.set(deliveryZoneKey, PersistentDataType.STRING, runtime.settings.id)
        entity.persistentDataContainer.set(deliverySequenceKey, PersistentDataType.LONG, runtime.state.sequence)
        entity.persistentDataContainer.set(deliveryIndexKey, PersistentDataType.INTEGER, index)
    }

    @Suppress("DEPRECATION")
    private fun deliveryItemStack(runtime: FarmRuntime): ItemStack {
        val item = ItemStack(MaterialRules.material(runtime.settings.delivery.itemMaterial))
        if (runtime.settings.delivery.itemCustomModelData > 0) {
            val meta = item.itemMeta
            meta.setCustomModelData(runtime.settings.delivery.itemCustomModelData)
            item.itemMeta = meta
        }
        return item
    }

    private fun pickupDelivery(runtime: FarmRuntime, key: DeliveryKey, player: Player) {
        removeDeliveryEntities(key, "picked_up")
        deliveryCarriers[key] = player.uniqueId
        val display = player.world.spawn(carriedDisplayLocation(player), ItemDisplay::class.java) { entity ->
            entity.setItemStack(deliveryItemStack(runtime))
            entity.itemDisplayTransform = runtime.settings.delivery.displayTransform.bukkit
            entity.uniformScale(runtime.settings.delivery.carriedScale)
            entity.viewRange = runtime.settings.delivery.displayViewRange
            entity.teleportDuration = 1
            entity.isGlowing = true
            entity.isPersistent = false
            markDeliveryEntity(entity, runtime, key.index)
        }
        carriedDisplays[key] = display.uniqueId
        showScreenTitle(player, MessageKey.FARM_DELIVERY_PICKED_UP)
        if (settings.sounds) player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 0.9f, 0.8f)
        if (settings.particles) {
            player.spawnParticle(
                Particle.DUST,
                player.location.clone().add(0.0, 1.0, 0.0),
                4,
                0.3,
                0.35,
                0.3,
                0.0,
                Particle.DustOptions(FARM_DELIVERY_COLOR, 1.1f),
            )
        }
        debug.event(
            "farm_delivery_picked_up",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "crate" to key.index,
            "player" to player.name,
        )
        persistAsync()
        handleDeliveryMovement(runtime, key, player, player.location)
    }

    private fun handleDeliveryMovement(runtime: FarmRuntime, key: DeliveryKey, player: Player, destination: Location) {
        if (deliveryCarriers[key] != player.uniqueId) return
        if (runtime.state.phase != FarmPhase.DELIVERY) {
            clearDelivery(runtime, "state_changed")
            return
        }
        if (!runtime.region.contains(destination)) {
            returnDelivery(runtime, key, player, "left_zone")
            return
        }
        updateCarriedDisplay(key, player)
        val delivery = runtime.settings.delivery
        val receiving = point(runtime, FarmPointKind.RECEIVING)
        if (destination.world.name != receiving.world) return
        val target = Location(destination.world, receiving.x, receiving.y, receiving.z)
        if (destination.distanceSquared(target) > delivery.radius * delivery.radius) return
        debug.event(
            "farm_delivery_completed",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "crate" to key.index,
            "player" to player.name,
        )
        deliveryCarriers.remove(key)
        carriedDisplays.remove(key)?.let(Bukkit::getEntity)?.remove()
        playFarmMilestone(runtime, Sound.BLOCK_BARREL_CLOSE, 0.9f)
        applyFarmResult(
            runtime,
            FarmShiftEngine.deliver(
                runtime.state,
                runtime.rules,
                key.index,
                runtime.settings.delivery.crates,
                player.uniqueId,
                clock(),
            ),
            player,
        )
    }

    private fun updateCarriedDisplay(key: DeliveryKey, player: Player) {
        val display = carriedDisplays[key]?.let(Bukkit::getEntity) as? ItemDisplay ?: return
        display.teleport(carriedDisplayLocation(player))
    }

    private fun updateCarriedDisplays() {
        deliveryCarriers.forEach { (key, playerId) ->
            Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline)?.let { player ->
                updateCarriedDisplay(key, player)
            }
        }
    }

    private fun carriedDisplayLocation(player: Player): Location {
        val direction = player.location.direction.setY(0)
        if (direction.lengthSquared() > 0.001) direction.normalize().multiply(-0.65)
        val runtime = farmAt(player.location)
        return player.location.clone().add(direction).add(0.0, runtime?.settings?.delivery?.carriedYOffset ?: 0.65, 0.0)
    }

    private fun returnDelivery(runtime: FarmRuntime, key: DeliveryKey, player: Player?, reason: String, notify: Boolean = true) {
        deliveryCarriers.remove(key)
        carriedDisplays.remove(key)?.let(Bukkit::getEntity)?.remove()
        if (notify && player?.isOnline == true) sendActionBar(player, MessageKey.FARM_DELIVERY_RETURNED)
        debug.event(
            "farm_delivery_returned",
            "zone" to key.zoneId,
            "sequence" to runtime.state.sequence,
            "crate" to key.index,
            "player" to player?.name,
            "reason" to reason,
        )
    }

    private fun removeDeliveryEntities(key: DeliveryKey, reason: String) {
        val ids = deliveryEntities.remove(key).orEmpty()
        ids.forEach { id -> Bukkit.getEntity(id)?.remove() }
        if (ids.isNotEmpty()) {
            debug.event("farm_delivery_entities_removed", "zone" to key.zoneId, "crate" to key.index, "count" to ids.size, "reason" to reason)
        }
    }

    private fun clearDelivery(runtime: FarmRuntime, reason: String) {
        deliveryKeys(runtime).forEach { key ->
            removeDeliveryEntities(key, reason)
            deliveryCarriers.remove(key)
            carriedDisplays.remove(key)?.let(Bukkit::getEntity)?.remove()
        }
    }

    private fun deliveryKeys(runtime: FarmRuntime): Set<DeliveryKey> = buildSet {
        deliveryEntities.keys.filterTo(this) { it.zoneId == runtime.settings.id }
        deliveryCarriers.keys.filterTo(this) { it.zoneId == runtime.settings.id }
        carriedDisplays.keys.filterTo(this) { it.zoneId == runtime.settings.id }
    }

    private fun ensureFarmPests(runtime: FarmRuntime) {
        if (
            runtime.state.phase != FarmPhase.INCIDENT ||
            (runtime.state.incidentType ?: FarmIncidentType.PESTS) != FarmIncidentType.PESTS
        ) {
            val active = activePests(runtime)
            if (active.isNotEmpty()) removePests(runtime, active, "incident_inactive")
            removePestNestEntities(runtime, "incident_inactive")
            return
        }
        val nearbyPlayers = players(runtime.region)
        ensureFarmPestNests(runtime)
        ensurePestNestEntities(runtime)
        var active = activePests(runtime).toMutableList()
        if (nearbyPlayers.isEmpty()) {
            active.filterIsInstance<Mob>().forEach { pest ->
                pest.target = null
                pest.isAware = false
            }
            return
        }
        val fallbackAnchor = farmAreaCenter(runtime.state.preparationPatch)?.location() ?: nearbyPlayers.first().location
        active.forEach { pest ->
            (pest as? Mob)?.let { mob ->
                mob.isAware = true
                mob.target = nearbyPlayers.minByOrNull { it.location.distanceSquared(pest.location) }
            }
            if (!runtime.region.contains(pest.location)) {
                val anchor = runtime.state.pestNests.minByOrNull { nest ->
                    nest.position.location()?.distanceSquared(pest.location) ?: Double.MAX_VALUE
                }?.position?.location() ?: fallbackAnchor
                val safe = findPestSpawn(runtime, anchor)
                if (safe == null || !pest.teleport(safe)) {
                    pestEntities[runtime.settings.id]?.remove(pest.uniqueId)
                    pest.remove()
                    debug.event("farm_pest_replaced", "zone" to runtime.settings.id, "uuid" to pest.uniqueId, "reason" to "outside_region")
                } else {
                    debug.event(
                        "farm_pest_returned",
                        "zone" to runtime.settings.id,
                        "uuid" to pest.uniqueId,
                        "x" to safe.blockX,
                        "y" to safe.blockY,
                        "z" to safe.blockZ,
                    )
                }
            }
        }
        active = activePests(runtime).toMutableList()
        if (active.size > runtime.state.pestAlive) {
            removePests(runtime, active.drop(runtime.state.pestAlive), "surplus")
            active = active.take(runtime.state.pestAlive).toMutableList()
        }
        repeat((runtime.state.pestAlive - active.size).coerceAtLeast(0)) { index ->
            val nest = runtime.state.pestNests.getOrNull(index % runtime.state.pestNests.size.coerceAtLeast(1))
            val preferred = nest?.position?.location()?.add(0.5, 1.0, 0.5) ?: nearbyPlayers[index % nearbyPlayers.size].location
            spawnPest(runtime, preferred, nearbyPlayers[index % nearbyPlayers.size])?.let(active::add)
        }
        if (!allowInteraction("farm-pest-nest-spawn:${runtime.settings.id}", runtime.settings.pestSpawnIntervalSeconds * 1_000L)) {
            return
        }
        if (active.size >= runtime.settings.pestMaxAlive) return
        val nest = runtime.state.pestNests
            .filter { it.spawned < runtime.settings.pestSpawnsPerNest }
            .shuffled(kotlin.random.Random(runtime.state.sequence + runtime.state.incidentProgress + active.size))
            .firstOrNull { random.nextInt(100) < runtime.settings.pestSpawnChancePercent }
            ?: return
        val target = nearbyPlayers.minByOrNull { player ->
            nest.position.location()?.distanceSquared(player.location) ?: Double.MAX_VALUE
        }
        val location = nest.position.location()?.add(0.5, 1.0, 0.5) ?: return
        if (spawnPest(runtime, location, target) != null) {
            runtime.state = runtime.state.copy(
                pestAlive = runtime.state.pestAlive + 1,
                pestNests = runtime.state.pestNests.map {
                    if (it.position == nest.position) it.copy(spawned = it.spawned + 1) else it
                },
            )
            persistAsync()
            debug.event(
                "farm_pest_spawned_from_nest",
                "zone" to runtime.settings.id,
                "x" to nest.position.x,
                "y" to nest.position.y,
                "z" to nest.position.z,
                "spawned" to nest.spawned + 1,
                "limit" to runtime.settings.pestSpawnsPerNest,
            )
        }
    }

    private fun letPestsEatCrops(runtime: FarmRuntime) {
        var changed = false
        activePests(runtime).forEach { pest ->
            if (!allowInteraction("farm-pest-eat:${pest.uniqueId}", 1_000)) return@forEach
            val radius = runtime.settings.pestEatRadius
            val targets = buildList {
                for (x in pest.location.blockX - radius..pest.location.blockX + radius) {
                    for (z in pest.location.blockZ - radius..pest.location.blockZ + radius) {
                        for (y in pest.location.blockY - 1..pest.location.blockY + 1) {
                            val crop = runtime.region.world.getBlockAt(x, y, z)
                            if (
                                runtime.region.contains(crop.location) && crop.type.name in runtime.settings.crops &&
                                !MaterialRules.isFixedBlockCrop(crop.type)
                            ) add(crop)
                        }
                    }
                }
            }.distinctBy { positionKey(it.location) }
                .sortedBy { it.location.distanceSquared(pest.location) }
                .take(runtime.settings.pestEatPerPulse)
            targets.forEach { target ->
                val soil = target.getRelative(org.bukkit.block.BlockFace.DOWN)
                val position = soil.toFarmPlotPosition()
                val crop = target.type.name
                farmBlockLedger.captureActiveCropIfPresent(soil, runtime.settings.id)
                if (
                    runtime.state.pestDamagedCrops.size < MAX_INCIDENT_DAMAGED_CROPS &&
                    runtime.state.pestDamagedCrops.none { it.position == position }
                ) {
                    runtime.state = runtime.state.copy(
                        pestDamagedCrops = runtime.state.pestDamagedCrops + FarmCropDamage(position, crop),
                    )
                    changed = true
                }
                farmBlockRegistry.addBeds(runtime.settings.id, listOf(position))
                val cropData = target.blockData
                target.setType(Material.AIR, false)
                if (settings.particles) {
                    runtime.region.world.spawnParticle(
                        Particle.BLOCK,
                        target.location.toCenterLocation().add(0.0, 0.65, 0.0),
                        4,
                        0.22,
                        0.3,
                        0.22,
                        0.03,
                        cropData,
                    )
                }
                debug.event(
                    "farm_pest_ate_crop",
                    "zone" to runtime.settings.id,
                    "sequence" to runtime.state.sequence,
                    "pest" to pest.uniqueId,
                    "crop" to crop,
                    "x" to target.x,
                    "y" to target.y,
                    "z" to target.z,
                )
            }
            if (targets.isNotEmpty() && settings.sounds) {
                runtime.region.world.playSound(pest.location, Sound.ENTITY_SILVERFISH_AMBIENT, 0.7f, 0.75f)
            }
        }
        if (changed) persistAsync()
    }

    private fun ensureFarmPestNests(runtime: FarmRuntime) {
        if (runtime.state.pestNestsInitialized) return
        val candidates = discoverIncidentBeds(runtime).filter { position ->
            position.block()?.getRelative(org.bukkit.block.BlockFace.UP)?.type?.name?.let(runtime.settings.crops::contains) == true
        }
        val centers = FarmIncidentPlanner.dispersedCenters(
            candidates,
            runtime.settings.pestNestCount,
            runtime.state.sequence * 53L + 11L,
        )
        val damages = runtime.state.pestDamagedCrops.toMutableList()
        val nests = centers.mapNotNull { position ->
            val soil = position.block() ?: return@mapNotNull null
            val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
            if (crop.type.name !in runtime.settings.crops) return@mapNotNull null
            farmBlockLedger.captureActiveCropIfPresent(soil, runtime.settings.id)
            if (damages.none { it.position == position }) damages += FarmCropDamage(position, crop.type.name)
            crop.setType(Material.AIR, false)
            FarmPestNest(position, runtime.settings.pestNestHealth)
        }
        runtime.state = runtime.state.copy(
            pestNestsInitialized = true,
            pestNests = nests,
            pestDamagedCrops = damages,
            incidentRequired = nests.size + runtime.settings.pestSpawnsPerNest * nests.size,
        )
        persistAsync()
        debug.event(
            "farm_pest_nests_created",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "nests" to nests.size,
        )
        if (nests.isEmpty()) {
            currentOrder(runtime) ?: return
            applyFarmResult(
                runtime,
                FarmShiftEngine.finishPestIncidentIfClear(runtime.state),
                null,
            )
            return
        }
        val players = players(runtime.region)
        var state = runtime.state
        nests.take(runtime.settings.pestMaxAlive).forEachIndexed { index, nest ->
            val location = nest.position.location()?.add(0.5, 1.0, 0.5) ?: return@forEachIndexed
            if (spawnPest(runtime, location, players.getOrNull(index % players.size.coerceAtLeast(1))) != null) {
                state = state.copy(
                    pestAlive = state.pestAlive + 1,
                    pestNests = state.pestNests.map {
                        if (it.position == nest.position) it.copy(spawned = 1) else it
                    },
                )
            }
        }
        runtime.state = state
        persistAsync()
    }

    private fun ensurePestNestEntities(runtime: FarmRuntime) {
        val activeKeys = runtime.state.pestNests.mapTo(mutableSetOf()) { PestNestKey(runtime.settings.id, it.position) }
        pestNestEntities.keys.filter { it.zoneId == runtime.settings.id && it !in activeKeys }.toList().forEach { key ->
            removePestNestEntities(key, "state_removed")
        }
        runtime.state.pestNests.forEach { nest ->
            val key = PestNestKey(runtime.settings.id, nest.position)
            val active = pestNestEntities[key].orEmpty().mapNotNull(Bukkit::getEntity).filter(Entity::isValid)
            if (active.size == 2) return@forEach
            removePestNestEntities(key, "refresh")
            val base = nest.position.location()?.add(0.5, 1.0, 0.5) ?: return@forEach
            if (!base.world.isChunkLoaded(base.blockX shr 4, base.blockZ shr 4)) return@forEach
            val display = runtime.region.world.spawn(base.clone().add(0.0, 0.25, 0.0), ItemDisplay::class.java) { entity ->
                entity.setItemStack(ItemStack(Material.MANGROVE_ROOTS))
                entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
                entity.viewRange = runtime.settings.displayViewRange
                entity.isGlowing = true
                entity.isPersistent = false
                markPestNestEntity(entity, runtime, nest.position)
            }
            val hitbox = runtime.region.world.spawn(base, ArmorStand::class.java) { entity ->
                entity.isInvisible = true
                entity.setGravity(false)
                entity.isSmall = false
                entity.isPersistent = false
                entity.isInvulnerable = false
                entity.customName(locale.render(MessageKey.FARM_PEST_NEST_NAME))
                entity.isCustomNameVisible = true
                markPestNestEntity(entity, runtime, nest.position)
            }
            pestNestEntities[key] = mutableSetOf(display.uniqueId, hitbox.uniqueId)
        }
    }

    private fun markPestNestEntity(entity: Entity, runtime: FarmRuntime, position: FarmPlotPosition) {
        entity.persistentDataContainer.set(pestNestZoneKey, PersistentDataType.STRING, runtime.settings.id)
        entity.persistentDataContainer.set(pestNestXKey, PersistentDataType.INTEGER, position.x)
        entity.persistentDataContainer.set(pestNestYKey, PersistentDataType.INTEGER, position.y)
        entity.persistentDataContainer.set(pestNestZKey, PersistentDataType.INTEGER, position.z)
    }

    private fun pestNestPosition(entity: Entity): FarmPlotPosition? {
        val x = entity.persistentDataContainer.get(pestNestXKey, PersistentDataType.INTEGER) ?: return null
        val y = entity.persistentDataContainer.get(pestNestYKey, PersistentDataType.INTEGER) ?: return null
        val z = entity.persistentDataContainer.get(pestNestZKey, PersistentDataType.INTEGER) ?: return null
        return FarmPlotPosition(entity.world.name, x, y, z)
    }

    private fun removePestNestEntities(runtime: FarmRuntime, reason: String) {
        pestNestEntities.keys.filter { it.zoneId == runtime.settings.id }.toList().forEach { key ->
            removePestNestEntities(key, reason)
        }
    }

    private fun removePestNestEntities(key: PestNestKey, reason: String) {
        val ids = pestNestEntities.remove(key).orEmpty()
        ids.forEach { Bukkit.getEntity(it)?.remove() }
        if (ids.isNotEmpty()) debug.event("farm_pest_nest_entities_removed", "zone" to key.zoneId, "count" to ids.size, "reason" to reason)
    }

    private fun activePests(runtime: FarmRuntime): List<LivingEntity> {
        val tracked = pestEntities.getOrPut(runtime.settings.id) { mutableSetOf() }
        val active = tracked.mapNotNull { id -> Bukkit.getEntity(id) as? LivingEntity }
            .filter { entity ->
                entity.isValid &&
                    entity.persistentDataContainer.get(pestZoneKey, PersistentDataType.STRING) == runtime.settings.id &&
                    entity.persistentDataContainer.get(pestSequenceKey, PersistentDataType.LONG) == runtime.state.sequence
            }
        tracked.retainAll(active.mapTo(mutableSetOf(), LivingEntity::getUniqueId))
        return active
    }

    private fun spawnPest(runtime: FarmRuntime, preferred: Location, target: Player?): LivingEntity? {
        val location = findPestSpawn(runtime, preferred) ?: run {
            debug.event("farm_pest_spawn_failed", "zone" to runtime.settings.id, "reason" to "no_safe_location")
            return null
        }
        val entity = runtime.region.world.spawnEntity(location, EntityType.valueOf(runtime.settings.pestEntity)) as? LivingEntity ?: return null
        entity.persistentDataContainer.set(pestZoneKey, PersistentDataType.STRING, runtime.settings.id)
        entity.persistentDataContainer.set(pestSequenceKey, PersistentDataType.LONG, runtime.state.sequence)
        entity.isPersistent = false
        entity.removeWhenFarAway = false
        entity.isGlowing = true
        (entity as? Mob)?.target = target
        entity.customName(locale.render(MessageKey.FARM_PEST_NAME, target))
        entity.isCustomNameVisible = true
        pestEntities.getOrPut(runtime.settings.id) { mutableSetOf() } += entity.uniqueId
        debug.event(
            "farm_pest_spawned",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "entity" to entity.type,
            "uuid" to entity.uniqueId,
            "x" to location.blockX,
            "y" to location.blockY,
            "z" to location.blockZ,
        )
        return entity
    }

    private fun findPestSpawn(runtime: FarmRuntime, anchor: Location): Location? {
        val radius = runtime.settings.pestSpawnRadius
        repeat(24) {
            val x = anchor.blockX + random.nextInt(-radius, radius + 1)
            val z = anchor.blockZ + random.nextInt(-radius, radius + 1)
            if (!runtime.region.world.isChunkLoaded(x shr 4, z shr 4)) return@repeat
            for (y in (anchor.blockY - 2)..(anchor.blockY + 2)) {
                val feet = runtime.region.world.getBlockAt(x, y, z)
                val head = runtime.region.world.getBlockAt(x, y + 1, z)
                val floor = runtime.region.world.getBlockAt(x, y - 1, z)
                val candidate = Location(runtime.region.world, x + 0.5, y.toDouble(), z + 0.5)
                if (runtime.region.contains(candidate) && feet.isPassable && head.isPassable && floor.type.isSolid) return candidate
            }
        }
        return null
    }

    private fun removePests(runtime: FarmRuntime, entities: Collection<LivingEntity>, reason: String) {
        entities.forEach { entity ->
            pestEntities[runtime.settings.id]?.remove(entity.uniqueId)
            entity.remove()
        }
        debug.event("farm_pests_removed", "zone" to runtime.settings.id, "count" to entities.size, "reason" to reason)
    }

    private fun ensureFarmSupplies(runtime: FarmRuntime) {
        val points = mapOf(
            FarmSupplyKind.TOOL to point(runtime, FarmPointKind.TOOL),
            FarmSupplyKind.SEEDS to point(runtime, FarmPointKind.SEEDS),
            FarmSupplyKind.WATER to point(runtime, FarmPointKind.WATER),
        )
        val loadedByKind = runtime.region.world.entities.asSequence().mapNotNull { entity ->
            if (entity.persistentDataContainer.get(supplyZoneKey, PersistentDataType.STRING) != runtime.settings.id) {
                return@mapNotNull null
            }
            val kind = entity.persistentDataContainer.get(supplyKindKey, PersistentDataType.STRING)
                ?.let { runCatching { FarmSupplyKind.valueOf(it) }.getOrNull() }
                ?: return@mapNotNull null
            kind to entity
        }.groupBy({ it.first }, { it.second })
        points.forEach { (kind, point) ->
            val key = SupplyKey(runtime.settings.id, kind)
            val visual = supplyMaterial(runtime, kind)
            val world = Bukkit.getWorld(point.world) ?: return@forEach
            val location = Location(world, point.x, point.y, point.z)
            if (!runtime.region.contains(location) || !world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) {
                return@forEach
            }
            val active = (supplyEntities[key].orEmpty().mapNotNull(Bukkit::getEntity) + loadedByKind[kind].orEmpty())
                .distinctBy(Entity::getUniqueId).filter { entity ->
                entity.isValid &&
                    entity.persistentDataContainer.get(supplyZoneKey, PersistentDataType.STRING) == runtime.settings.id &&
                    entity.persistentDataContainer.get(supplyKindKey, PersistentDataType.STRING) == kind.name
            }
            supplyEntities[key] = active.mapTo(mutableSetOf(), Entity::getUniqueId)
            if (active.size == 3 && supplyVisualMaterials[key] == visual && supplyEntitiesMatchLocation(active, location)) {
                return@forEach
            }
            removeSupplyEntities(key, "refresh")
            val item = world.spawn(location.clone().add(0.0, SUPPLY_ITEM_Y_OFFSET, 0.0), ItemDisplay::class.java) { entity ->
                entity.setItemStack(ItemStack(visual))
                entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
                entity.uniformScale(SUPPLY_ITEM_SCALE)
                entity.viewRange = runtime.settings.displayViewRange
                entity.isGlowing = true
                entity.isPersistent = false
                markSupplyEntity(entity, runtime, kind)
            }
            val label = world.spawn(location.clone().add(0.0, SUPPLY_LABEL_Y_OFFSET, 0.0), TextDisplay::class.java) { entity ->
                entity.text(supplyLabel(runtime, kind, visual))
                entity.billboard = Display.Billboard.VERTICAL
                entity.alignment = TextDisplay.TextAlignment.CENTER
                entity.lineWidth = 180
                entity.backgroundColor = Color.fromARGB(128, 16, 16, 16)
                entity.isShadowed = true
                entity.viewRange = 0.5f
                entity.isPersistent = false
                markSupplyEntity(entity, runtime, kind)
            }
            val interaction = world.spawn(location.clone().add(0.0, SUPPLY_INTERACTION_Y_OFFSET, 0.0), Interaction::class.java) { entity ->
                entity.interactionWidth = 1.35f
                entity.interactionHeight = 1.4f
                entity.isResponsive = true
                entity.isPersistent = false
                markSupplyEntity(entity, runtime, kind)
            }
            supplyEntities[key] = mutableSetOf(item.uniqueId, label.uniqueId, interaction.uniqueId)
            supplyVisualMaterials[key] = visual
            debug.event("farm_supply_spawned", "zone" to runtime.settings.id, "kind" to kind, "material" to visual)
        }
    }

    private fun supplyEntitiesMatchLocation(entities: Collection<Entity>, base: Location): Boolean {
        fun Entity.near(yOffset: Double): Boolean = world == base.world &&
            location.distanceSquared(base.clone().add(0.0, yOffset, 0.0)) <= 0.04
        return entities.count { it is ItemDisplay && it.near(SUPPLY_ITEM_Y_OFFSET) } == 1 &&
            entities.count { it is TextDisplay && it.near(SUPPLY_LABEL_Y_OFFSET) } == 1 &&
            entities.count { it is Interaction && it.near(SUPPLY_INTERACTION_Y_OFFSET) } == 1
    }

    private fun supplyMaterial(runtime: FarmRuntime, kind: FarmSupplyKind): Material = when (kind) {
        FarmSupplyKind.TOOL -> MaterialRules.material(runtime.settings.supplies.toolMaterial)
        FarmSupplyKind.WATER -> Material.WATER_BUCKET
        FarmSupplyKind.SEEDS -> runtime.state.preparationCrop
            ?.let(MaterialRules::material)
            ?.let(MaterialRules::seedForCrop)
            ?: Material.WHEAT_SEEDS
    }

    private fun supplyLabel(runtime: FarmRuntime, kind: FarmSupplyKind, material: Material): Component = when (kind) {
        FarmSupplyKind.TOOL -> locale.render(
            MessageKey.FARM_SUPPLY_TOOL,
            values = mapOf("tool" to MaterialRules.itemComponent(material)),
        )
        FarmSupplyKind.SEEDS -> locale.render(
            MessageKey.FARM_SUPPLY_SEEDS,
            values = mapOf("seed" to MaterialRules.itemComponent(material)),
        )
        FarmSupplyKind.WATER -> locale.render(MessageKey.FARM_SUPPLY_WATER)
    }

    private fun markSupplyEntity(entity: Entity, runtime: FarmRuntime, kind: FarmSupplyKind) {
        entity.persistentDataContainer.set(supplyZoneKey, PersistentDataType.STRING, runtime.settings.id)
        entity.persistentDataContainer.set(supplyKindKey, PersistentDataType.STRING, kind.name)
    }

    private fun giveFarmSupply(
        runtime: FarmRuntime,
        kind: FarmSupplyKind,
        player: Player,
    ): Boolean {
        removeFarmServiceItems(player, runtime.settings.id, "replace_supply", kind)
        val material = supplyMaterial(runtime, kind)
        val amount = if (kind == FarmSupplyKind.SEEDS) runtime.settings.supplies.seedAmount else 1
        val item = ItemStack(material, amount)
        val meta = item.itemMeta
        meta.persistentDataContainer.set(serviceItemKey, PersistentDataType.STRING, "${runtime.settings.id}:${kind.name}")
        if (kind == FarmSupplyKind.TOOL) meta.isUnbreakable = true
        item.itemMeta = meta
        if (player.inventory.firstEmpty() < 0) return false
        player.inventory.addItem(item)
        if (settings.sounds) player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 0.7f, 1.2f)
        debug.event("farm_supply_given", "zone" to runtime.settings.id, "kind" to kind, "player" to player.name, "material" to material)
        return true
    }

    private fun isFarmServiceItem(item: ItemStack?): Boolean = item?.itemMeta?.persistentDataContainer
        ?.has(serviceItemKey, PersistentDataType.STRING) == true

    private fun removeFarmServiceItems(
        player: Player,
        zoneId: String? = null,
        reason: String,
        kind: FarmSupplyKind? = null,
    ) {
        var removed = 0
        player.inventory.storageContents.forEachIndexed { index, item ->
            val value = item?.itemMeta?.persistentDataContainer?.get(serviceItemKey, PersistentDataType.STRING) ?: return@forEachIndexed
            val parts = value.split(':', limit = 2)
            if (zoneId != null && parts.firstOrNull() != zoneId) return@forEachIndexed
            if (kind != null && parts.getOrNull(1) != kind.name) return@forEachIndexed
            removed += item.amount
            player.inventory.setItem(index, null)
        }
        val offHand = player.inventory.itemInOffHand
        val offHandValue = offHand.itemMeta?.persistentDataContainer?.get(serviceItemKey, PersistentDataType.STRING)
        if (
            offHandValue != null && (zoneId == null || offHandValue.substringBefore(':') == zoneId) &&
            (kind == null || offHandValue.substringAfter(':') == kind.name)
        ) {
            removed += offHand.amount
            player.inventory.setItemInOffHand(null)
        }
        if (isFarmServiceItem(player.itemOnCursor)) {
            val cursorValue = player.itemOnCursor.itemMeta.persistentDataContainer.get(serviceItemKey, PersistentDataType.STRING)
            if (
                cursorValue != null && (zoneId == null || cursorValue.substringBefore(':') == zoneId) &&
                (kind == null || cursorValue.substringAfter(':') == kind.name)
            ) {
                removed += player.itemOnCursor.amount
                player.setItemOnCursor(null)
            }
        }
        val top = player.openInventory.topInventory
        top.contents.forEachIndexed { index, item ->
            val value = item?.itemMeta?.persistentDataContainer?.get(serviceItemKey, PersistentDataType.STRING)
                ?: return@forEachIndexed
            if (zoneId != null && value.substringBefore(':') != zoneId) return@forEachIndexed
            if (kind != null && value.substringAfter(':') != kind.name) return@forEachIndexed
            removed += item.amount
            top.setItem(index, null)
        }
        if (removed > 0) debug.event("farm_supply_removed", "player" to player.name, "zone" to zoneId, "kind" to kind, "count" to removed, "reason" to reason)
    }

    private fun removeSupplyEntities(key: SupplyKey, reason: String) {
        val ids = supplyEntities.remove(key).orEmpty()
        ids.forEach { id -> Bukkit.getEntity(id)?.remove() }
        supplyVisualMaterials.remove(key)
        if (ids.isNotEmpty()) debug.event("farm_supply_removed", "zone" to key.zoneId, "kind" to key.kind, "count" to ids.size, "reason" to reason)
    }

    private fun cleanupOwnedFarmEntities() {
        val worlds = Bukkit.getWorlds()
        contractScene.cleanupLoaded("service_cleanup")
        specialIncidentScene.cleanupLoaded("service_cleanup")
        nightShift.clearAll(Bukkit.getOnlinePlayers())
        var removed = 0
        worlds.flatMap { it.entities }.forEach { entity ->
            if (
                entity.persistentDataContainer.has(pestZoneKey, PersistentDataType.STRING) ||
                entity.persistentDataContainer.has(pestNestZoneKey, PersistentDataType.STRING) ||
                entity.persistentDataContainer.has(deliveryZoneKey, PersistentDataType.STRING) ||
                entity.persistentDataContainer.has(supplyZoneKey, PersistentDataType.STRING) ||
                entity.persistentDataContainer.has(careZoneKey, PersistentDataType.STRING)
            ) {
                entity.remove()
                removed++
            }
        }
        pestEntities.clear()
        pestNestEntities.clear()
        farmBlockRegistry.clear()
        deliveryEntities.clear()
        deliveryCarriers.clear()
        carriedDisplays.clear()
        supplyEntities.clear()
        supplyVisualMaterials.clear()
        careEntities.clear()
        animalFollowers.clear()
        diseaseNextSpreadAt.clear()
        careNextReconcileAt.clear()
        pollenCharges.clear()
        pendingIncidentRestore.clear()
        patchRestoreProgress.clear()
        fixedCropRestoreQueue.clear()
        Bukkit.getOnlinePlayers().forEach { removeFarmServiceItems(it, reason = "service_cleanup") }
        if (removed > 0) debug.event("farm_entities_cleanup", "count" to removed)
    }

    private fun clearTemporaryFarmWater(reason: String) {
        val positions = waterFlows.values.flatMap(FarmWaterFlowTracker::clear).distinct()
        positions.forEach { position ->
            position.block()?.takeIf { it.type == Material.WATER }?.setType(Material.AIR, false)
        }
        if (positions.isNotEmpty()) debug.event("farm_water_removed", "count" to positions.size, "reason" to reason)
        waterFlows.clear()
    }

    private fun hasActiveWater(zoneId: String): Boolean = waterFlows[zoneId]?.isEmpty() == false

    private fun updateFarmScoreboard(
        runtime: FarmRuntime,
        order: FarmOrder,
        player: Player,
        phaseDone: Int,
        phaseTotal: Int,
        carrying: Boolean,
        expected: MutableSet<UUID>,
    ) {
        if (!settings.farmScoreboard.enabled) return
        val playerId = player.uniqueId
        expected += playerId
        val view = FarmScoreboardView(
            orderId = order.id,
            phase = runtime.state.phase,
            done = phaseDone,
            total = phaseTotal,
            required = order.required,
            cropProgress = runtime.state.progress,
            careType = runtime.state.careType,
            seederStage = runtime.state.seederStage(),
            incidentType = runtime.state.incidentType,
            carrying = carrying,
        )
        farmScoreboards.update(player, runtime.settings.id, view)
    }

    private fun reconcileFarmScoreboards(expected: Set<UUID>) {
        farmScoreboards.reconcile(expected)
    }

    private fun updateBar(
        player: Player,
        runtimeKey: String,
        name: Component,
        progress: Float,
        color: BossBar.Color,
        expected: MutableSet<BarKey>,
    ) {
        if (!settings.bossbars) return
        val key = BarKey(player.uniqueId, runtimeKey)
        expected += key
        val existing = activeBars[key]
        val bar = existing ?: BossBar.bossBar(
            name,
            progress.coerceIn(0f, 1f),
            color,
            BossBar.Overlay.PROGRESS,
        ).also {
            activeBars[key] = it
            player.showBossBar(it)
            debug.message("bossbar", "local", runtimeKey, player, name)
        }
        if (existing != null && existing.name() != name) debug.message("bossbar", "local", runtimeKey, player, name)
        bar.name(name)
        bar.progress(progress.coerceIn(0f, 1f))
        bar.color(color)
    }

    private fun emitGuidanceParticles() {
        if (!settings.particles) return
        farms.filter { it.state.phase == FarmPhase.PREPARATION }.forEach { runtime ->
            val marker = farmAreaCenter(runtime.state.preparationPatch.filterNot(runtime.state.tilledPlots::contains))?.location()
                ?: return@forEach
            players(runtime.region).filterNot(::isAdminEditing).forEach { player ->
                spawnGuidanceColumn(player, marker, FARM_TILL_COLOR)
            }
        }
        farms.filter { it.state.phase == FarmPhase.PLANTING }.forEach { runtime ->
            val remaining = runtime.state.preparationPatch.filterNot(runtime.state.plantedPlots::contains)
            val individual = FarmGuidancePlanner.individualMissingPlots(
                remaining,
                settings.missingBedHighlightThreshold,
            ).mapNotNull(FarmPlotPosition::location)
            val marker = if (individual.isEmpty()) farmAreaCenter(remaining)?.location() else null
            players(runtime.region).filterNot(::isAdminEditing).forEach { player ->
                if (individual.isNotEmpty()) {
                    individual.forEach { location -> spawnMissingPlotMarker(player, location, FARM_PLANT_COLOR) }
                } else if (marker != null) {
                    spawnGuidanceColumn(player, marker, FARM_PLANT_COLOR)
                }
            }
        }
        farms.filter {
            it.state.phase == FarmPhase.INCIDENT && it.state.incidentType == FarmIncidentType.DROUGHT
        }.forEach { runtime ->
            val markers = clusterFarmPlots(runtime.state.droughtPlots, runtime.settings.droughtPatches)
                .mapNotNull(::farmAreaCenter)
                .mapNotNull(FarmPlotPosition::location)
            players(runtime.region).filterNot(::isAdminEditing).forEach { player ->
                markers.forEach { location ->
                    spawnGuidanceColumn(player, location, FARM_DROUGHT_COLOR)
                }
            }
        }
        farms.filter { it.state.phase == FarmPhase.INCIDENT && it.state.incidentType in SPECIAL_FARM_INCIDENT_TYPES }
            .forEach { runtime -> emitFarmSpecialGuidance(runtime) }
        farms.filter { it.state.phase == FarmPhase.CARE }.forEach { runtime ->
            val incomplete = runtime.state.careTargets.filterNot(FarmCareTarget::complete)
            players(runtime.region).filterNot(::isAdminEditing).forEach { player ->
                val hive = runtime.state.careTargets.firstOrNull { it.role == FarmCareRole.HIVE }
                val visible = when (runtime.state.careType) {
                    FarmCareType.SEEDER -> incomplete.firstOrNull { it.role == FarmCareRole.SEEDER_HORSE }
                        ?.let(::listOf).orEmpty()
                    FarmCareType.IRRIGATION -> incomplete.minByOrNull(FarmCareTarget::id)?.let(::listOf).orEmpty()
                    FarmCareType.POLLINATION -> if ((pollenCharges[player.uniqueId] ?: 0) > 0) {
                        incomplete.filter { it.role == FarmCareRole.FLOWER_PATCH }
                    } else {
                        listOfNotNull(hive)
                    }
                    else -> incomplete
                }
                visible.filter { it.role != FarmCareRole.ANIMAL }.forEach { target ->
                    val world = Bukkit.getWorld(target.position.world) ?: return@forEach
                    if (player.world == world) {
                        spawnMissingPlotMarker(
                            player,
                            Location(world, target.position.x, target.position.y - 1.0, target.position.z),
                            careRoleColor(target.role),
                        )
                    }
                }
                if (runtime.state.careType == FarmCareType.ANIMAL_RESCUE) {
                    careFixturePoint(runtime, FarmPointKind.PEN)?.let { pen ->
                        val world = Bukkit.getWorld(pen.world) ?: return@let
                        if (player.world == world) spawnGuidanceColumn(player, Location(world, pen.x, pen.y, pen.z), FARM_SUCCESS_COLOR)
                    }
                } else {
                    visible.filter { target -> Bukkit.getWorld(target.position.world) == player.world }
                        .minByOrNull { target ->
                            val dx = target.position.x - player.location.x
                            val dz = target.position.z - player.location.z
                            dx * dx + dz * dz
                        }?.let { target ->
                            spawnGuidanceColumn(
                                player,
                                Location(player.world, target.position.x, target.position.y, target.position.z),
                                careRoleColor(target.role),
                            )
                        }
                }
            }
        }
        farms.filter { it.state.phase == FarmPhase.DELIVERY }.forEach { runtime ->
            val delivery = point(runtime, FarmPointKind.RECEIVING)
            val world = Bukkit.getWorld(delivery.world) ?: return@forEach
            val target = Location(world, delivery.x, delivery.y, delivery.z)
            val crateTargets = runtime.state.deliveryPosition?.let { position ->
                (0 until runtime.settings.delivery.crates)
                    .filterNot(runtime.state.deliveredCrates::contains)
                    .filter { index -> DeliveryKey(runtime.settings.id, index) !in deliveryCarriers }
                    .mapNotNull { index -> deliveryCrateLocation(runtime, position, index) }
            }.orEmpty()
            players(runtime.region).filter { it.world == world && !isAdminEditing(it) }.forEach { player ->
                spawnGuidanceColumn(player, target, FARM_DELIVERY_COLOR)
                spawnGuidanceRing(player, target, runtime.settings.delivery.radius, FARM_DELIVERY_COLOR)
                crateTargets.forEach { location -> spawnMissingPlotMarker(player, location, FARM_AMBER_COLOR) }
            }
        }
        lumbermills.filter { it.state.phase == LumberPhase.FELLING }.forEach { runtime ->
            val species = runtime.state.species ?: return@forEach
            players(runtime.region).filterNot(::isAdminEditing).forEach { player ->
                nearbyBlocks(player.location, runtime.region, 6, 5, 8) { MaterialRules.speciesOf(it.type) == species }.forEach { block ->
                    spawnGuidanceDust(player, block.location.toCenterLocation().add(0.0, 0.8, 0.0), FARM_AMBER_COLOR)
                }
            }
        }
        mines.filter { it.state.phase == MinePhase.HAZARD }.forEach { runtime ->
            players(runtime.region).filter { mineAt(it.location) === runtime }.forEach { player ->
                spawnGuidanceDust(player, player.location.clone().add(0.0, 1.2, 0.0), FARM_DANGER_COLOR)
            }
        }
    }

    private fun spawnGuidanceDust(player: Player, location: Location, color: Color, size: Float = 1.1f) {
        player.spawnParticle(Particle.DUST, location, 1, 0.0, 0.0, 0.0, 0.0, Particle.DustOptions(color, size))
    }

    private fun emitFarmSpecialGuidance(runtime: FarmRuntime) {
        val special = runtime.state.specialIncident ?: return
        val remaining = special.plots.filter { plot -> runtime.state.specialDamagedCrops.none { it.position == plot } }
        players(runtime.region).filterNot(::isAdminEditing).forEach { player ->
            when (runtime.state.incidentType) {
                FarmIncidentType.GIANT_CROP -> special.points.firstOrNull()?.let { point ->
                    spawnGuidanceRing(player, Location(player.world, point.x, point.y - 1.0, point.z), 2.3, FARM_AMBER_COLOR)
                }
                FarmIncidentType.CHANNELS -> special.points.forEachIndexed { index, point ->
                    spawnGuidanceDust(
                        player,
                        Location(player.world, point.x, point.y + 1.1, point.z),
                        if (index in special.active) FARM_SUCCESS_COLOR else Color.fromRGB(79, 195, 247),
                        1.35f,
                    )
                }.also {
                    val source = point(runtime, FarmPointKind.IRRIGATION)
                    val reached = listOf(source) + special.points.take(runtime.state.incidentProgress)
                    reached.zipWithNext().forEach { (from, to) -> spawnWaterTrail(player, from, to) }
                }
                FarmIncidentType.NIGHT_SHIFT -> remaining.forEachIndexed { index, plot ->
                    if (index % 3 == 0) plot.location()?.let { location ->
                        player.spawnParticle(Particle.END_ROD, location.add(0.5, 1.65, 0.5), 1, 0.08, 0.12, 0.08, 0.0)
                    }
                }
                FarmIncidentType.MARKET -> {
                    val customer = point(runtime, FarmPointKind.CUSTOMER)
                    player.spawnParticle(
                        Particle.HAPPY_VILLAGER,
                        Location(player.world, customer.x, customer.y + 1.25, customer.z),
                        3,
                        0.35,
                        0.5,
                        0.35,
                        0.0,
                    )
                }
                else -> Unit
            }
        }
    }

    private fun spawnWaterTrail(player: Player, from: FarmPointPosition, to: FarmPointPosition) {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val distance = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        val steps = (distance * 2.0).toInt().coerceIn(1, 48)
        repeat(steps) { step ->
            val ratio = (step + 1).toDouble() / steps
            player.spawnParticle(
                Particle.SPLASH,
                Location(player.world, from.x + dx * ratio, from.y + dy * ratio + 0.2, from.z + dz * ratio),
                1,
                0.04,
                0.02,
                0.04,
                0.0,
            )
        }
    }

    private fun spawnGuidanceColumn(player: Player, base: Location, color: Color) {
        val center = base.clone().toCenterLocation().add(0.0, 1.0, 0.0)
        val steps = settings.markerHeight * 2
        for (step in 0..steps) {
            player.spawnParticle(
                Particle.DUST,
                center.clone().add(0.0, step * 0.5, 0.0),
                if (step == 0 || step == steps) 4 else 2,
                0.22,
                0.12,
                0.22,
                0.0,
                Particle.DustOptions(color, if (step % 4 == 0) 2.2f else 1.7f),
                true,
            )
        }
    }

    private fun spawnMissingPlotMarker(player: Player, soil: Location, color: Color) {
        val center = soil.clone().toCenterLocation().add(0.0, 1.05, 0.0)
        repeat(4) { layer ->
            player.spawnParticle(
                Particle.DUST,
                center.clone().add(0.0, layer * 0.38, 0.0),
                3,
                0.18,
                0.08,
                0.18,
                0.0,
                Particle.DustOptions(color, if (layer == 0) 2.0f else 1.65f),
                true,
            )
        }
    }

    private fun spawnGuidanceRing(player: Player, center: Location, radius: Double, color: Color) {
        repeat(24) { index ->
            val angle = 2.0 * PI * index / 24.0
            player.spawnParticle(
                Particle.DUST,
                center.clone().add(cos(angle) * radius, 0.35, sin(angle) * radius),
                2,
                0.08,
                0.04,
                0.08,
                0.0,
                Particle.DustOptions(color, 1.75f),
                true,
            )
        }
    }

    private fun showDebugFarmGuidance(playerId: UUID, zoneId: String, remainingBursts: Int) {
        if (remainingBursts <= 0) return
        val player = Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline) ?: return
        val runtime = farms.firstOrNull { it.settings.id == zoneId } ?: return
        emitDebugActiveTarget(player, runtime)
        val pointColors = mapOf(
            FarmPointKind.TOOL to FARM_TILL_COLOR,
            FarmPointKind.SEEDS to FARM_PLANT_COLOR,
            FarmPointKind.WATER to Color.fromRGB(79, 195, 247),
            FarmPointKind.CRATES to FARM_AMBER_COLOR,
            FarmPointKind.RECEIVING to FARM_DELIVERY_COLOR,
            FarmPointKind.CART to FARM_AMBER_COLOR,
            FarmPointKind.CUSTOMER to FARM_SUCCESS_COLOR,
            FarmPointKind.TRAVEL to FARM_SUCCESS_COLOR,
            FarmPointKind.HIVE to FARM_AMBER_COLOR,
            FarmPointKind.IRRIGATION to Color.fromRGB(79, 195, 247),
            FarmPointKind.COVERS to Color.fromRGB(154, 140, 255),
            FarmPointKind.SCARECROWS to FARM_DANGER_COLOR,
            FarmPointKind.PEN to FARM_SUCCESS_COLOR,
        )
        pointColors.forEach { (kind, color) ->
            val point = point(runtime, kind)
            val world = Bukkit.getWorld(point.world) ?: return@forEach
            if (player.world == world) spawnGuidanceColumn(player, Location(world, point.x, point.y, point.z), color)
        }
        if (remainingBursts > 1) {
            Tasks.scheduler.runLater(10L) { showDebugFarmGuidance(playerId, zoneId, remainingBursts - 1) }
        }
    }

    private fun emitDebugActiveTarget(player: Player, runtime: FarmRuntime) {
        val markers = when (runtime.state.phase) {
            FarmPhase.PREPARATION -> listOfNotNull(
                farmAreaCenter(runtime.state.preparationPatch.filterNot(runtime.state.tilledPlots::contains))?.location()
                    ?.let { it to FARM_TILL_COLOR },
            )
            FarmPhase.PLANTING -> listOfNotNull(
                farmAreaCenter(runtime.state.preparationPatch.filterNot(runtime.state.plantedPlots::contains))?.location()
                    ?.let { it to FARM_PLANT_COLOR },
            )
            FarmPhase.CARE -> runtime.state.careTargets.filterNot(FarmCareTarget::complete).mapNotNull { target ->
                Bukkit.getWorld(target.position.world)?.let { world ->
                    Location(world, target.position.x, target.position.y, target.position.z) to careRoleColor(target.role)
                }
            }
            FarmPhase.HARVESTING -> listOfNotNull(
                farmAreaCenter(runtime.state.preparationPatch)?.location()?.let { it to FARM_AMBER_COLOR },
            )
            FarmPhase.INCIDENT -> when (runtime.state.incidentType) {
                FarmIncidentType.DROUGHT -> clusterFarmPlots(runtime.state.droughtPlots, runtime.settings.droughtPatches)
                    .mapNotNull(::farmAreaCenter)
                    .mapNotNull { it.location() }
                    .map { it to FARM_DROUGHT_COLOR }
                FarmIncidentType.PESTS -> runtime.state.pestNests.mapNotNull { it.position.location() }
                    .map { it to FARM_DANGER_COLOR }
                FarmIncidentType.GIANT_CROP -> runtime.state.specialIncident?.points.orEmpty().mapNotNull { point ->
                    Bukkit.getWorld(point.world)?.let { Location(it, point.x, point.y, point.z) to FARM_AMBER_COLOR }
                }
                FarmIncidentType.CHANNELS -> runtime.state.specialIncident?.points.orEmpty().mapNotNull { point ->
                    Bukkit.getWorld(point.world)?.let { Location(it, point.x, point.y, point.z) to Color.fromRGB(79, 195, 247) }
                }
                FarmIncidentType.NIGHT_SHIFT -> runtime.state.specialIncident?.plots.orEmpty().mapNotNull { it.location() }
                    .map { it to Color.fromRGB(139, 211, 255) }
                FarmIncidentType.MARKET -> point(runtime, FarmPointKind.CUSTOMER).let { point ->
                    listOf(Location(runtime.region.world, point.x, point.y, point.z) to FARM_AMBER_COLOR)
                }
                null -> emptyList()
            }
            FarmPhase.DELIVERY -> point(runtime, FarmPointKind.RECEIVING).let { point ->
                val world = Bukkit.getWorld(point.world)
                if (world == null) emptyList() else listOf(Location(world, point.x, point.y, point.z) to FARM_DELIVERY_COLOR)
            }
            FarmPhase.IDLE, FarmPhase.COOLDOWN -> emptyList()
        }
        markers.filter { it.first.world == player.world }.forEach { (location, color) ->
            spawnGuidanceColumn(player, location, color)
        }
    }

    private fun clusterFarmPlots(
        plots: Set<FarmPlotPosition>,
        requestedClusters: Int,
    ): List<Set<FarmPlotPosition>> {
        if (plots.isEmpty()) return emptyList()
        val centers = mutableListOf(plots.first())
        while (centers.size < minOf(requestedClusters, plots.size)) {
            val next = plots.asSequence().filterNot(centers::contains).maxByOrNull { plot ->
                centers.minOf { center -> horizontalDistanceSquared(plot, center) }
            } ?: break
            centers += next
        }
        val clusters = centers.associateWith { linkedSetOf<FarmPlotPosition>() }
        plots.forEach { plot ->
            val center = centers.minBy { horizontalDistanceSquared(plot, it) }
            clusters.getValue(center) += plot
        }
        return clusters.values.filter { it.isNotEmpty() }
    }

    private fun farmAreaCenter(plots: Collection<FarmPlotPosition>): FarmPlotPosition? {
        if (plots.isEmpty()) return null
        val centerX = plots.sumOf(FarmPlotPosition::x).toDouble() / plots.size
        val centerZ = plots.sumOf(FarmPlotPosition::z).toDouble() / plots.size
        return plots.minByOrNull { plot ->
            val dx = plot.x - centerX
            val dz = plot.z - centerZ
            dx * dx + dz * dz
        }
    }

    private fun nearbyBlocks(
        center: Location,
        region: ActivityRegion,
        horizontal: Int,
        vertical: Int,
        limit: Int,
        predicate: (Block) -> Boolean,
    ): List<Block> {
        val result = ArrayList<Block>(limit)
        loop@ for (y in -vertical..vertical) {
            for (x in -horizontal..horizontal) {
                for (z in -horizontal..horizontal) {
                    val block = center.world.getBlockAt(center.blockX + x, center.blockY + y, center.blockZ + z)
                    if (region.contains(block.location) && predicate(block)) result += block
                    if (result.size >= limit) break@loop
                }
            }
        }
        return result
    }

    private fun currentOrder(runtime: FarmRuntime): FarmOrder? = runtime.state.orderId?.let(runtime.orders::get)

    private fun destination(kind: ActivityKind): TeleportDestination {
        val configured = settings.destinations.getValue(kind.configKey)
        if (kind != ActivityKind.FARM) return configured
        val runtime = farms.firstOrNull() ?: return configured
        val override = farmLocations.zones[runtime.settings.id]?.get(FarmPointKind.TRAVEL) ?: return configured
        return configured.copy(
            world = override.world,
            x = override.x,
            y = override.y,
            z = override.z,
            yaw = override.yaw,
            pitch = override.pitch,
        )
    }

    private fun point(runtime: FarmRuntime, kind: FarmPointKind): FarmPointPosition {
        farmLocations.zones[runtime.settings.id]?.get(kind)?.let { return it }
        val zoneSettings = runtime.settings
        return when (kind) {
            FarmPointKind.TOOL -> zoneSettings.supplies.tool.toPoint()
            FarmPointKind.SEEDS -> zoneSettings.supplies.seeds.toPoint()
            FarmPointKind.WATER -> zoneSettings.supplies.water.toPoint()
            FarmPointKind.CRATES -> zoneSettings.delivery.pickup.toPoint()
            FarmPointKind.RECEIVING -> FarmPointPosition(
                zoneSettings.delivery.world,
                zoneSettings.delivery.x,
                zoneSettings.delivery.y,
                zoneSettings.delivery.z,
            )
            FarmPointKind.CART -> point(runtime, FarmPointKind.CRATES)
            FarmPointKind.CUSTOMER -> point(runtime, FarmPointKind.RECEIVING).let { receiving ->
                val radians = Math.toRadians(receiving.yaw.toDouble())
                val candidate = FarmPointPosition(
                    receiving.world,
                    receiving.x - kotlin.math.sin(radians) * 1.8,
                    receiving.y,
                    receiving.z + kotlin.math.cos(radians) * 1.8,
                    receiving.yaw + 180f,
                    0f,
                )
                val opposite = FarmPointPosition(
                    receiving.world,
                    receiving.x + kotlin.math.sin(radians) * 1.8,
                    receiving.y,
                    receiving.z - kotlin.math.cos(radians) * 1.8,
                    receiving.yaw,
                    0f,
                )
                listOf(candidate, opposite).firstOrNull { point ->
                    runtime.region.contains(Location(runtime.region.world, point.x, point.y, point.z))
                } ?: receiving.copy(yaw = receiving.yaw + 180f, pitch = 0f)
            }
            FarmPointKind.TRAVEL -> settings.destinations.getValue(ActivityKind.FARM.configKey).let { destination ->
                FarmPointPosition(
                    destination.world,
                    destination.x,
                    destination.y,
                    destination.z,
                    destination.yaw,
                    destination.pitch,
                )
            }
            FarmPointKind.HIVE,
            FarmPointKind.IRRIGATION,
            FarmPointKind.COVERS,
            FarmPointKind.SCARECROWS,
            FarmPointKind.PEN,
            -> careFixturePoint(runtime, kind)
                ?: farmAreaCenter(runtime.state.preparationPatch)?.let { plot ->
                    FarmPointPosition(plot.world, plot.x + 0.5, plot.y + 1.0, plot.z + 0.5)
                }
                ?: zoneSettings.supplies.tool.toPoint()
        }
    }

    private fun careFixturePoint(runtime: FarmRuntime, kind: FarmPointKind): FarmPointPosition? {
        farmLocations.zones[runtime.settings.id]?.get(kind)?.let { return it }
        if (kind == FarmPointKind.PEN) return point(runtime, FarmPointKind.RECEIVING)
        val patch = runtime.state.preparationPatch
        if (patch.isEmpty()) return null
        if (kind == FarmPointKind.HIVE) {
            val center = farmAreaCenter(patch)?.location() ?: return null
            nearbyBlocks(center, runtime.region, runtime.settings.careRadius, 4, 1) {
                it.type == Material.BEE_NEST || it.type == Material.BEEHIVE
            }.firstOrNull()?.let { hive ->
                return FarmPointPosition(hive.world.name, hive.x + 0.5, hive.y + 0.5, hive.z + 0.5)
            }
        }
        if (!runtime.settings.proceduralCareFixtures) return null
        val center = farmAreaCenter(patch)?.location() ?: return null
        val candidates = findDeliveryCandidates(runtime, center, runtime.settings.careRadius)
        if (candidates.isNotEmpty()) {
            val salt = kind.ordinal * 31 + runtime.state.sequence.toInt()
            val chosen = candidates[java.lang.Math.floorMod(salt, candidates.size)]
            return FarmPointPosition(chosen.world, chosen.x, chosen.y, chosen.z)
        }
        val fallback = FarmCarePlanner.spread(patch, 1, runtime.state.sequence + kind.ordinal).firstOrNull() ?: return null
        return FarmPointPosition(fallback.world, fallback.x + 0.5, fallback.y + 1.0, fallback.z + 0.5)
    }

    private fun ru.ruscrafting.farms.config.FarmSupplyPointSettings.toPoint(): FarmPointPosition =
        FarmPointPosition(world, x, y, z)

    private fun nextRequiredCrop(state: FarmShiftState, order: FarmOrder): Map.Entry<String, Int>? = order.required.entries
        .firstOrNull { (crop, required) -> (state.progress[crop] ?: 0) < required }

    private fun nearbyFarmDropIds(location: Location, radius: Double): MutableSet<UUID> = location.world
        .getNearbyEntities(location.toCenterLocation(), radius, radius, radius)
        .filterIsInstance<Item>()
        .mapTo(mutableSetOf(), Entity::getUniqueId)

    private fun removeNewFarmDrops(location: Location, radius: Double, existing: MutableSet<UUID>) {
        location.world.getNearbyEntities(location.toCenterLocation(), radius, radius, radius)
            .filterIsInstance<Item>()
            .filter { it.uniqueId !in existing && it.itemStack.type in FARM_WATER_DROP_TYPES }
            .forEach { item ->
                existing += item.uniqueId
                item.remove()
            }
    }

    private fun farmDropInventory(player: Player): Map<Material, Int> = FARM_WATER_DROP_TYPES.associateWith { material ->
        player.inventory.storageContents.filterNotNull().filter { it.type == material }.sumOf(ItemStack::getAmount)
    }

    private fun removeFarmDropInventoryGains(player: Player, baseline: Map<Material, Int>, zoneId: String) {
        var removed = 0
        baseline.forEach { (material, before) ->
            val current = player.inventory.storageContents.filterNotNull().filter { it.type == material }.sumOf(ItemStack::getAmount)
            var excess = (current - before).coerceAtLeast(0)
            if (excess == 0) return@forEach
            player.inventory.storageContents.forEachIndexed { index, item ->
                if (excess == 0 || item?.type != material) return@forEachIndexed
                val amount = minOf(excess, item.amount)
                excess -= amount
                removed += amount
                if (amount == item.amount) player.inventory.setItem(index, null) else item.amount -= amount
            }
        }
        if (removed > 0) {
            debug.event("farm_crop_inventory_suppressed", "zone" to zoneId, "player" to player.name, "count" to removed)
        }
    }

    private fun traceBlockBreak(event: BlockBreakEvent, activity: ActivityKind, zone: String) {
        debug.event(
            "player_block_break",
            "player" to event.player.name,
            "activity" to activity,
            "zone" to zone,
            "block" to event.block.type,
            "world" to event.block.world.name,
            "x" to event.block.x,
            "y" to event.block.y,
            "z" to event.block.z,
        )
    }

    private fun traceResult(
        activity: ActivityKind,
        zone: String,
        actor: Player?,
        phase: Any,
        progress: String?,
        result: EngineResult<*>,
    ) {
        if (!result.accepted && result.events.isEmpty()) return
        debug.event(
            "activity_result",
            "activity" to activity,
            "zone" to zone,
            "player" to actor?.name,
            "phase" to phase,
            "progress" to progress,
            "contribution" to result.contribution,
            "events" to result.events.joinToString(","),
        )
    }

    private fun remainingCrops(runtime: FarmRuntime, order: FarmOrder): Component = Component.join(
        JoinConfiguration.commas(true),
        order.required.entries
            .filter { (crop, required) -> (runtime.state.progress[crop] ?: 0) < required }
            .map { MaterialRules.cropComponent(MaterialRules.material(it.key)) },
    )

    private fun farmRequirements(runtime: FarmRuntime, order: FarmOrder): Component = Component.join(
        JoinConfiguration.commas(true),
        order.required.entries
            .filter { (crop, required) -> (runtime.state.progress[crop] ?: 0) < required }
            .map { (crop, required) ->
                MaterialRules.cropComponent(MaterialRules.material(crop))
                    .append(Component.space())
                    .append(Component.text("${runtime.state.progress[crop] ?: 0}/$required"))
            },
    )

    private fun farmAt(location: Location): FarmRuntime? = farms.firstOrNull { it.region.contains(location) }

    private fun lumberAt(location: Location): LumberRuntime? = lumbermills.firstOrNull { it.region.contains(location) }

    private fun mineAt(location: Location): MineRuntime? = mines.firstOrNull { it.region.contains(location) }

    private fun hasAccess(player: Player, permission: String): Boolean =
        player.hasPermission(permission) || player.hasPermission("arcfarms.admin")

    private val ActivityKind.configKey: String
        get() = when (this) {
            ActivityKind.FARM -> "farm"
            ActivityKind.LUMBER -> "lumber"
            ActivityKind.MINE -> "mine"
        }

    private fun players(region: ActivityRegion): List<Player> = region.world.players.filter { region.contains(it.location) }

    private fun isAdminEditing(player: Player): Boolean = player.uniqueId in adminEditPlayers

    private fun isAdminEditingFarm(runtime: FarmRuntime): Boolean =
        players(runtime.region).any(::isAdminEditing)

    private fun ItemDisplay.uniformScale(scale: Float) {
        transformation = Transformation(
            Vector3f(),
            AxisAngle4f(),
            Vector3f(scale, scale, scale),
            AxisAngle4f(),
        )
    }

    private val ru.ruscrafting.farms.config.FarmItemDisplayTransform.bukkit: ItemDisplay.ItemDisplayTransform
        get() = when (this) {
            ru.ruscrafting.farms.config.FarmItemDisplayTransform.GROUND -> ItemDisplay.ItemDisplayTransform.GROUND
            ru.ruscrafting.farms.config.FarmItemDisplayTransform.FIXED -> ItemDisplay.ItemDisplayTransform.FIXED
            ru.ruscrafting.farms.config.FarmItemDisplayTransform.HEAD -> ItemDisplay.ItemDisplayTransform.HEAD
        }

    private fun broadcast(
        region: ActivityRegion,
        key: MessageKey,
        values: Map<String, Component> = emptyMap(),
        sound: Sound? = null,
        title: Boolean = false,
        valuesForPlayer: ((Player) -> Map<String, Component>)? = null,
    ) = broadcast(listOf(region), key, values, sound, title, valuesForPlayer)

    private fun broadcast(
        regions: Collection<ActivityRegion>,
        key: MessageKey,
        values: Map<String, Component> = emptyMap(),
        sound: Sound? = null,
        title: Boolean = false,
        valuesForPlayer: ((Player) -> Map<String, Component>)? = null,
    ) {
        regions.flatMap(::players).distinctBy(Player::getUniqueId).forEach { player ->
            val playerValues = valuesForPlayer?.invoke(player) ?: values
            if (title) {
                showScreenTitle(player, key, playerValues, "local")
            } else {
                val message = locale.render(key, player, playerValues)
                player.sendActionBar(message)
                debug.message("actionbar", "local", key.path, player, message)
            }
            if (sound != null && settings.sounds) player.playSound(player.location, sound, 0.8f, 1.0f)
        }
    }

    private fun sendChat(player: Player, key: MessageKey, values: Map<String, Component> = emptyMap()) {
        val message = locale.render(key, player, values)
        player.sendMessage(message)
        debug.message("chat", "player", key.path, player, message)
    }

    private fun sendActionBar(player: Player, key: MessageKey, values: Map<String, Component> = emptyMap()) {
        val message = locale.render(key, player, values)
        player.sendActionBar(message)
        debug.message("actionbar", "player", key.path, player, message)
    }

    private fun showScreenTitle(
        player: Player,
        key: MessageKey,
        values: Map<String, Component> = emptyMap(),
        scope: String = "player",
    ) {
        val title = locale.render(key, player, values)
        val subtitleKey = TITLE_SUBTITLES[key]
        val subtitle = subtitleKey?.let { locale.render(it, player, values) } ?: Component.empty()
        showScreenTitle(player, title, subtitle)
        debug.message("title", scope, key.path, player, title)
        if (subtitleKey != null) debug.message("subtitle", scope, subtitleKey.path, player, subtitle)
    }

    private fun showScreenTitle(player: Player, title: Component, subtitle: Component) {
        player.showTitle(
            Title.title(
                title,
                subtitle,
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(settings.titleStaySeconds.toLong()), Duration.ofMillis(700)),
            ),
        )
    }

    private fun broadcastStoryTitle(
        runtime: FarmRuntime,
        beat: String,
        sound: Sound? = null,
        titleAndValues: (Player) -> Pair<Component, Map<String, Component>>,
    ) {
        val orderId = runtime.state.orderId ?: return
        players(runtime.region).forEach { player ->
            val (title, values) = titleAndValues(player)
            val subtitle = locale.renderPath("story.farm.$orderId.$beat", player, values)
            showScreenTitle(player, title, subtitle)
            debug.message("title", "local", "story.farm.$orderId.$beat", player, title)
            debug.message("subtitle", "local", "story.farm.$orderId.$beat", player, subtitle)
            if (sound != null && settings.sounds) player.playSound(player.location, sound, 0.8f, 1.0f)
        }
    }

    private fun showFarmEntry(player: Player, runtime: FarmRuntime) {
        if (isAdminEditing(player)) return
        val actionKey = when (runtime.state.phase) {
            FarmPhase.IDLE, FarmPhase.COOLDOWN -> MessageKey.FARM_ENTRY_IDLE
            FarmPhase.PREPARATION -> MessageKey.FARM_ENTRY_PREPARATION
            FarmPhase.PLANTING -> MessageKey.FARM_ENTRY_PLANTING
            FarmPhase.CARE -> null
            FarmPhase.HARVESTING -> MessageKey.FARM_ENTRY_HARVESTING
            FarmPhase.INCIDENT -> when (runtime.state.incidentType ?: FarmIncidentType.PESTS) {
                FarmIncidentType.DROUGHT -> MessageKey.FARM_ENTRY_DROUGHT
                FarmIncidentType.PESTS -> MessageKey.FARM_ENTRY_PESTS
                else -> null
            }
            FarmPhase.DELIVERY -> MessageKey.FARM_ENTRY_DELIVERY
        }
        val action = if (actionKey == null) {
            when (runtime.state.phase) {
                FarmPhase.CARE -> runtime.state.careType?.let {
                    locale.renderPath("care.${it.name.lowercase()}.entry", player)
                } ?: Component.empty()
                FarmPhase.INCIDENT -> runtime.state.incidentType?.takeIf { it in SPECIAL_FARM_INCIDENT_TYPES }?.let {
                    locale.renderPath("farm.entry-${it.specialId()}", player)
                } ?: Component.empty()
                else -> Component.empty()
            }
        } else {
            locale.render(
                actionKey,
                player,
                mapOf(
                    "crop" to (runtime.state.preparationCrop
                        ?.let(MaterialRules::material)
                        ?.let(MaterialRules::cropComponent)
                        ?: Component.empty()),
                ),
            )
        }
        showScreenTitle(player, MessageKey.FARM_ENTRY_TITLE, mapOf("action" to action), "zone_entry")
    }

    private fun syncFarmMusic(player: Player, runtime: FarmRuntime?, now: Long) {
        val music = runtime?.settings?.music?.takeIf { settings.sounds && it.enabled }
        val transition = if (music == null) {
            farmMusic.remove(player.uniqueId)
        } else {
            farmMusic.sync(
                playerId = player.uniqueId,
                desiredSound = music.sound,
                now = now,
                durationMillis = TimeUnit.SECONDS.toMillis(music.durationSeconds.toLong()),
            )
        }
        transition.stopSound?.let { sound ->
            player.stopSound(farmMusicSound(sound, 1.0f))
            debug.event("farm_music_stopped", "player" to player.name, "sound" to sound)
        }
        transition.playSound?.let { sound ->
            val volume = requireNotNull(music).volume
            player.playSound(farmMusicSound(sound, volume), AdventureSound.Emitter.self())
            debug.event(
                "farm_music_started",
                "player" to player.name,
                "zone" to runtime.settings.id,
                "sound" to sound,
                "duration_seconds" to music.durationSeconds,
            )
        }
    }

    private fun stopFarmMusic(player: Player, reason: String) {
        farmMusic.remove(player.uniqueId).stopSound?.let { sound ->
            player.stopSound(farmMusicSound(sound, 1.0f))
            debug.event("farm_music_stopped", "player" to player.name, "sound" to sound, "reason" to reason)
        }
    }

    private fun stopAllFarmMusic(reason: String) {
        farmMusic.clear().forEach { (playerId, sound) ->
            Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline)?.let { player ->
                player.stopSound(farmMusicSound(sound, 1.0f))
                debug.event("farm_music_stopped", "player" to player.name, "sound" to sound, "reason" to reason)
            }
        }
    }

    private fun farmMusicSound(sound: String, volume: Float): AdventureSound = AdventureSound.sound(
        Key.key(sound),
        AdventureSound.Source.MUSIC,
        volume,
        1.0f,
    )

    private fun warningBurst(region: ActivityRegion) {
        if (!settings.particles) return
        players(region).forEach { player ->
            player.spawnParticle(
                Particle.DUST,
                player.location.clone().add(0.0, 1.15, 0.0),
                5,
                0.55,
                0.4,
                0.55,
                0.0,
                Particle.DustOptions(FARM_DANGER_COLOR, 1.1f),
            )
        }
    }

    private fun playFarmMilestone(runtime: FarmRuntime, sound: Sound, pitch: Float = 1.0f) {
        if (!settings.sounds) return
        players(runtime.region).forEach { player ->
            player.playSound(player.location, sound, 0.85f, pitch)
        }
    }

    private fun playFarmStageFanfare(runtime: FarmRuntime, basePitch: Float) {
        if (!settings.sounds) return
        val recipients = players(runtime.region).map(Player::getUniqueId)
        recipients.mapNotNull(Bukkit::getPlayer).forEach { player ->
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.65f, basePitch)
        }
        Tasks.scheduler.runLater(4L) {
            recipients.mapNotNull(Bukkit::getPlayer).filter { runtime.region.contains(it.location) }.forEach { player ->
                player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, basePitch + 0.18f)
            }
        }
        Tasks.scheduler.runLater(8L) {
            recipients.mapNotNull(Bukkit::getPlayer).filter { runtime.region.contains(it.location) }.forEach { player ->
                player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.55f, basePitch + 0.32f)
            }
        }
    }

    private fun successBurst(region: ActivityRegion) {
        if (!settings.particles) return
        players(region).forEach { player ->
            player.spawnParticle(
                Particle.DUST,
                player.location.clone().add(0.0, 1.0, 0.0),
                6,
                0.55,
                0.45,
                0.55,
                0.0,
                Particle.DustOptions(FARM_SUCCESS_COLOR, 1.15f),
            )
        }
    }

    private fun celebration(region: ActivityRegion) = celebration(listOf(region))

    private fun celebration(regions: Collection<ActivityRegion>) {
        val recipients = regions.flatMap(::players).distinctBy(Player::getUniqueId)
        recipients.forEach { player ->
            if (settings.particles) {
                player.spawnParticle(Particle.FIREWORK, player.location.add(0.0, 1.2, 0.0), 8, 0.7, 0.55, 0.7, 0.025)
            }
            if (settings.sounds) player.playSound(player.location, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.7f, 1.15f)
        }
        if (recipients.isEmpty()) return
        Tasks.scheduler.runLater(8L) {
            recipients.filter(Player::isOnline).forEach { player ->
                if (settings.particles) {
                    player.spawnParticle(Particle.FIREWORK, player.location.add(0.0, 1.8, 0.0), 10, 0.9, 0.7, 0.9, 0.04)
                }
                if (settings.sounds) {
                    player.playSound(player.location, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.9f, 1.0f)
                    player.playSound(player.location, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.65f, 1.2f)
                }
            }
        }
    }

    private fun announceWinner(region: ActivityRegion, contributors: Map<UUID, Int>) {
        announceWinner(listOf(region), contributors)
    }

    private fun announceWinner(regions: Collection<ActivityRegion>, contributors: Map<UUID, Int>) {
        val winnerId = winner(contributors) ?: return
        val winnerName = Bukkit.getPlayer(winnerId)?.name ?: winnerId.toString().take(8)
        broadcast(
            regions,
            MessageKey.SHIFT_WINNER,
            mapOf(
                "player" to Component.text(winnerName),
                "amount" to locale.text(contributors.getValue(winnerId)),
            ),
        )
    }

    private fun recordContribution(playerId: UUID, kind: ActivityKind, amount: Int) {
        stats.contribute(playerId, kind, amount)
    }

    private fun recordCompletion(kind: ActivityKind, contributors: Map<UUID, Int>) {
        contributors.keys.forEach { playerId -> stats.complete(playerId, kind) }
    }

    private fun queueFarmCompletionRewards(runtime: FarmRuntime, contributors: Map<UUID, Int>) {
        val ranked = contributors.entries.sortedWith(
            compareByDescending<Map.Entry<UUID, Int>> { it.value }.thenBy { it.key.toString() },
        )
        val planned = ranked.mapIndexedNotNull { index, (playerId, contribution) ->
            val claimKey = "${runtime.settings.id}:$playerId"
            if ((claimedFarmRewardSequences[claimKey] ?: -1L) >= runtime.state.sequence) return@mapIndexedNotNull null
            FarmRewardPlanner.plan(
                settings = runtime.settings.rewards,
                zoneId = runtime.settings.id,
                sequence = runtime.state.sequence,
                recipient = FarmRewardRecipient(
                    playerId = playerId,
                    playerName = Bukkit.getOfflinePlayer(playerId).name ?: playerId.toString(),
                    contribution = contribution,
                    rank = index + 1,
                ),
                moneyMultiplierPercent = 100 + runtime.state.rewardMoneyBonusPercent,
            ).takeUnless { grant -> pendingFarmRewards.any { it.id == grant.id } }
        }
        if (planned.isEmpty()) return
        pendingFarmRewards += planned
        val saved = runCatching(::persistBlocking).onFailure { failure ->
            plugin.logger.log(Level.SEVERE, "Could not persist resolved farm completion rewards", failure)
        }.isSuccess
        planned.forEach { reward ->
            debug.event(
                "farm_reward_planned",
                "zone" to reward.zoneId,
                "sequence" to reward.sequence,
                "player" to reward.playerId,
                "experience" to reward.experience,
                "money_cents" to reward.moneyCents,
                "items" to reward.items.size,
                "commands" to reward.commands.size,
                "bundles" to reward.bundleIds.joinToString(","),
                "persisted" to saved,
            )
        }
        if (saved) {
            deliverPendingFarmRewards(
                planned.mapNotNull { Bukkit.getPlayer(it.playerId) }.filter(Player::isOnline).distinctBy(Player::getUniqueId),
            )
        } else {
            Tasks.scheduler.runLater(20L) {
                if (isOperational()) {
                    deliverPendingFarmRewards(
                        planned.mapNotNull { Bukkit.getPlayer(it.playerId) }.filter(Player::isOnline)
                            .distinctBy(Player::getUniqueId),
                    )
                }
            }
        }
    }

    private fun deliverPendingFarmRewards(player: Player) = deliverPendingFarmRewards(listOf(player))

    private fun deliverPendingFarmRewards(players: Collection<Player>) {
        val online = players.filter(Player::isOnline).distinctBy(Player::getUniqueId).associateBy(Player::getUniqueId)
        if (online.isEmpty()) return
        val candidates = pendingFarmRewards.filter { it.playerId in online }
        if (candidates.isEmpty()) return
        val deliverable = candidates.filter { reward ->
            (claimedFarmRewardSequences[reward.claimKey] ?: -1L) < reward.sequence
        }.sortedWith(compareBy(PendingFarmReward::sequence, PendingFarmReward::id))
        val beforePending = pendingFarmRewards.toList()
        val beforeClaims = claimedFarmRewardSequences.toMap()
        pendingFarmRewards.removeAll(candidates.toSet())
        deliverable.groupBy(PendingFarmReward::claimKey).forEach { (claimKey, rewards) ->
            claimedFarmRewardSequences[claimKey] = rewards.maxOf(PendingFarmReward::sequence)
        }
        try {
            persistBlocking()
        } catch (failure: Exception) {
            pendingFarmRewards.clear()
            pendingFarmRewards += beforePending
            claimedFarmRewardSequences.clear()
            claimedFarmRewardSequences += beforeClaims
            plugin.logger.log(Level.SEVERE, "Could not claim ${deliverable.size} farm reward(s); delivery was not attempted", failure)
            deliverable.forEach { reward ->
                debug.event("farm_reward_claim_failed", "grant" to reward.id, "player" to reward.playerId)
            }
            return
        }
        deliverable.forEach { reward -> online[reward.playerId]?.let { player -> deliverClaimedFarmReward(player, reward) } }
    }

    private fun deliverClaimedFarmReward(player: Player, reward: PendingFarmReward) {
        if (reward.experience > 0) player.giveExp(reward.experience)
        val moneySuccess = reward.moneyCents == 0L || runCatching {
            economy.deposit(player, reward.moneyCents / 100.0)
        }.onFailure { failure ->
            plugin.logger.log(Level.SEVERE, "Farm reward ${reward.id} economy provider failed", failure)
        }.getOrDefault(false)
        if (!moneySuccess) {
            plugin.logger.severe("Farm reward ${reward.id} could not deposit ${reward.moneyCents} cents")
        }

        var overflow = false
        reward.items.forEach { item ->
            val material = MaterialRules.material(item.material)
            var remaining = item.amount
            while (remaining > 0) {
                val stack = ItemStack(material, minOf(remaining, material.maxStackSize))
                remaining -= stack.amount
                player.inventory.addItem(stack).values.forEach { leftover ->
                    overflow = true
                    player.world.dropItemNaturally(player.location, leftover)
                }
            }
        }

        var successfulCommands = 0
        reward.commands.forEachIndexed { index, command ->
            val commandRoot = command.substringBefore(' ').take(64)
            val success = runCatching { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command) }
                .onFailure { failure ->
                    plugin.logger.log(Level.SEVERE, "Farm reward ${reward.id} command #$index failed", failure)
                }.getOrDefault(false)
            debug.event(
                "farm_reward_command",
                "grant" to reward.id,
                "player" to player.name,
                "index" to index,
                "root" to commandRoot,
                "success" to success,
            )
            if (success) successfulCommands++
        }

        val parts = mutableListOf<Component>()
        if (reward.experience > 0) {
            parts += locale.render(MessageKey.FARM_REWARD_EXPERIENCE, player, mapOf("amount" to locale.text(reward.experience)))
        }
        if (reward.moneyCents > 0 && moneySuccess) {
            parts += locale.render(
                MessageKey.FARM_REWARD_MONEY,
                player,
                mapOf("amount" to locale.text(BigDecimal.valueOf(reward.moneyCents, 2).stripTrailingZeros().toPlainString())),
            )
        }
        reward.bundleIds.forEach { bundleId ->
            parts += locale.render(
                MessageKey.FARM_REWARD_BUNDLE,
                player,
                mapOf("bundle" to locale.renderPath("reward.bundle.$bundleId", player)),
            )
        }
        fixedRewardItems(reward).forEach { item ->
            parts += locale.render(
                MessageKey.FARM_REWARD_ITEMS,
                player,
                mapOf(
                    "amount" to locale.text(item.amount),
                    "item" to MaterialRules.itemComponent(MaterialRules.material(item.material)),
                ),
            )
        }
        if (successfulCommands > 0) parts += locale.render(MessageKey.FARM_REWARD_SPECIAL, player)
        val summary = if (parts.isEmpty()) {
            locale.render(MessageKey.FARM_REWARD_MISSED, player)
        } else {
            Component.join(JoinConfiguration.commas(true), parts)
        }
        if (parts.isEmpty()) {
            sendActionBar(player, MessageKey.FARM_REWARD_MISSED)
        } else {
            sendActionBar(
                player,
                MessageKey.FARM_REWARD_RECEIVED,
                mapOf("reward" to summary),
            )
        }
        sendChat(player, MessageKey.FARM_REWARD_CHAT, mapOf("reward" to summary))
        if (overflow) sendChat(player, MessageKey.FARM_REWARD_OVERFLOW)
        debug.event(
            "farm_reward_delivered",
            "grant" to reward.id,
            "zone" to reward.zoneId,
            "player" to player.name,
            "experience" to reward.experience,
            "money_cents" to reward.moneyCents,
            "money_success" to moneySuccess,
            "item_units" to reward.items.sumOf { it.amount },
            "commands" to reward.commands.size,
            "overflow" to overflow,
        )
    }

    private fun fixedRewardItems(reward: PendingFarmReward): List<FarmRewardItem> {
        var remaining = reward.fixedItemUnits
        return buildList {
            reward.items.forEach { item ->
                if (remaining <= 0) return@forEach
                val amount = minOf(item.amount, remaining)
                add(item.copy(amount = amount))
                remaining -= amount
            }
        }
    }

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

    private fun denyInteraction(event: PlayerInteractEvent) {
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY)
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY)
        event.isCancelled = true
    }

    private fun seederInstructionPath(state: FarmShiftState): String = when (state.seederStage()) {
        FarmSeederStage.TILLING -> "care.seeder.tilling-instruction"
        FarmSeederStage.PLANTING -> "care.seeder.planting-instruction"
        null -> "care.seeder.instruction"
    }

    private fun sendFarmCurrentTaskHint(player: Player, runtime: FarmRuntime, reason: String) {
        if (!allowInteraction("farm-task-hint:${runtime.settings.id}:${player.uniqueId}", 900)) return
        when (runtime.state.phase) {
            FarmPhase.PREPARATION -> sendActionBar(player, MessageKey.FARM_PREPARATION_REQUIRED)
            FarmPhase.PLANTING -> sendActionBar(
                player,
                MessageKey.FARM_PLANTING_REQUIRED,
                mapOf(
                    "crop" to MaterialRules.cropComponent(
                        MaterialRules.material(requireNotNull(runtime.state.preparationCrop)),
                    ),
                ),
            )
            FarmPhase.CARE -> sendActionBar(
                player,
                MessageKey.FARM_CARE_REQUIRED,
                mapOf(
                    "instruction" to (runtime.state.careType?.let { type ->
                        locale.renderPath(if (type == FarmCareType.SEEDER) {
                            seederInstructionPath(runtime.state)
                        } else {
                            "care.${type.name.lowercase()}.instruction"
                        }, player)
                    } ?: Component.empty()),
                ),
            )
            FarmPhase.INCIDENT -> sendActionBar(
                player,
                if (runtime.state.incidentType == FarmIncidentType.DROUGHT) {
                    MessageKey.FARM_DROUGHT_REQUIRED
                } else {
                    MessageKey.FARM_PESTS_REQUIRED
                },
            )
            FarmPhase.DELIVERY -> sendActionBar(player, MessageKey.FARM_DELIVERY_REQUIRED)
            FarmPhase.HARVESTING -> currentOrder(runtime)?.let { order ->
                sendActionBar(player, MessageKey.FARM_WRONG_TARGET, mapOf("crops" to remainingCrops(runtime, order)))
            }
            FarmPhase.COOLDOWN -> sendActionBar(
                player,
                MessageKey.COOLDOWN,
                mapOf("seconds" to locale.text(remainingSeconds(runtime.state.cooldownEndsAt, clock()))),
            )
            FarmPhase.IDLE -> Unit
        }
        debug.event(
            "farm_wrong_action_hint",
            "player" to player.name,
            "zone" to runtime.settings.id,
            "phase" to runtime.state.phase,
            "reason" to reason,
        )
    }

    private fun remainingSeconds(deadline: Long, now: Long): Long = ceil((deadline - now).coerceAtLeast(0) / 1000.0).toLong()

    private fun positionKey(location: Location): String =
        "${location.world.name}:${location.blockX}:${location.blockY}:${location.blockZ}"

    private fun snapshotState(): ArcFarmsState = ArcFarmsState(
        farms = farms.associate { it.settings.id to it.state },
        pausedFarmZones = adminPausedFarmZones.toSet(),
        lumbermills = lumbermills.associate { it.settings.id to it.state },
        mines = mines.associate { it.settings.id to it.state },
        stats = stats.snapshot(),
        pendingFarmRewards = pendingFarmRewards.toList(),
        claimedFarmRewardSequences = claimedFarmRewardSequences.toMap(),
    )

    private fun persistAsync() {
        if (persistenceSuspended) {
            persistenceRequestedWhileSuspended = true
            return
        }
        runCatching { stateRepository.saveAsync(snapshotState()) }
            .onSuccess { operation ->
                operation.whenComplete { _, failure ->
                    if (failure != null) plugin.logger.log(Level.SEVERE, "Could not persist ArcFarms state", failure)
                }
            }
            .onFailure { failure ->
                plugin.logger.log(Level.SEVERE, "Could not schedule ArcFarms state persistence", failure)
            }
    }

    private fun persistBlocking() = stateRepository.saveBlocking(snapshotState())

    private fun hideAllBars() {
        activeBars.forEach { (key, bar) -> Bukkit.getPlayer(key.playerId)?.hideBossBar(bar) }
        activeBars.clear()
    }

    override fun close() {
        if (closed) return
        closed = true
        started = false
        val failures = mutableListOf<Throwable>()
        runCatching(::stopTasks).exceptionOrNull()?.let(failures::add)
        runCatching { stopAllFarmMusic("plugin_close") }.exceptionOrNull()?.let(failures::add)
        runCatching { clearTemporaryFarmWater("plugin_close") }.exceptionOrNull()?.let(failures::add)
        runCatching(::hideAllBars).exceptionOrNull()?.let(failures::add)
        runCatching { farmScoreboards.restoreAll("plugin_close") }.exceptionOrNull()?.let(failures::add)
        runCatching(::cleanupOwnedFarmEntities).exceptionOrNull()?.let(failures::add)
        runCatching(farmBackupAdmin::close).exceptionOrNull()?.let(failures::add)
        runCatching(farmBlockRegistry::close).exceptionOrNull()?.let(failures::add)
        if (stateSafeToPersist) runCatching(::persistBlocking).exceptionOrNull()?.let(failures::add)
        if (failures.isNotEmpty()) {
            throw IllegalStateException("ArcFarms service shutdown completed with ${failures.size} failure(s)", failures.first()).also {
                failures.drop(1).forEach(it::addSuppressed)
            }
        }
    }

    companion object {
        private val SPECIAL_FARM_INCIDENT_TYPES = setOf(
            FarmIncidentType.GIANT_CROP,
            FarmIncidentType.CHANNELS,
            FarmIncidentType.NIGHT_SHIFT,
            FarmIncidentType.MARKET,
        )
        private const val MAX_INCIDENT_DAMAGED_CROPS = 4_096
        private const val MAX_INTERACTION_COOLDOWNS = 10_000
        private const val SUPPLY_ITEM_Y_OFFSET = 0.25
        private const val SUPPLY_LABEL_Y_OFFSET = 1.15
        private const val SUPPLY_INTERACTION_Y_OFFSET = 0.25
        private const val SUPPLY_ITEM_SCALE = 1.35f
        private val TITLE_SUBTITLES = mapOf(
            MessageKey.FARM_ENTRY_TITLE to MessageKey.FARM_ENTRY_SUBTITLE,
            MessageKey.FARM_PLANTING_STARTED to MessageKey.FARM_PLANTING_STARTED_SUBTITLE,
            MessageKey.FARM_PREPARATION_COMPLETED to MessageKey.FARM_PREPARATION_COMPLETED_SUBTITLE,
            MessageKey.FARM_CARE_RESOLVED to MessageKey.FARM_CARE_RESOLVED_SUBTITLE,
            MessageKey.FARM_CARE_SEEDER_RESOLVED to MessageKey.FARM_CARE_SEEDER_RESOLVED_SUBTITLE,
            MessageKey.FARM_INCIDENT_STARTED to MessageKey.FARM_INCIDENT_STARTED_SUBTITLE,
            MessageKey.FARM_INCIDENT_RESOLVED to MessageKey.FARM_INCIDENT_RESOLVED_SUBTITLE,
            MessageKey.FARM_DROUGHT_STARTED to MessageKey.FARM_DROUGHT_STARTED_SUBTITLE,
            MessageKey.FARM_SPECIAL_RESOLVED to MessageKey.FARM_SPECIAL_RESOLVED_SUBTITLE,
            MessageKey.FARM_GIANT_CROP_STARTED to MessageKey.FARM_GIANT_CROP_STARTED_SUBTITLE,
            MessageKey.FARM_CHANNELS_STARTED to MessageKey.FARM_CHANNELS_STARTED_SUBTITLE,
            MessageKey.FARM_NIGHT_SHIFT_STARTED to MessageKey.FARM_NIGHT_SHIFT_STARTED_SUBTITLE,
            MessageKey.FARM_MARKET_STARTED to MessageKey.FARM_MARKET_STARTED_SUBTITLE,
            MessageKey.FARM_DELIVERY_STARTED to MessageKey.FARM_DELIVERY_STARTED_SUBTITLE,
            MessageKey.FARM_DELIVERY_PICKED_UP to MessageKey.FARM_DELIVERY_PICKED_UP_SUBTITLE,
            MessageKey.FARM_CROP_COMPLETED to MessageKey.FARM_CROP_COMPLETED_SUBTITLE,
            MessageKey.FARM_COMPLETED to MessageKey.FARM_COMPLETED_SUBTITLE,
            MessageKey.LUMBER_PROCESSING to MessageKey.LUMBER_PROCESSING_SUBTITLE,
            MessageKey.LUMBER_COMPLETED to MessageKey.LUMBER_COMPLETED_SUBTITLE,
            MessageKey.MINE_HAZARD_STARTED to MessageKey.MINE_HAZARD_STARTED_SUBTITLE,
            MessageKey.MINE_HAZARD_RESOLVED to MessageKey.MINE_HAZARD_RESOLVED_SUBTITLE,
            MessageKey.MINE_EXTRACTION_STARTED to MessageKey.MINE_EXTRACTION_STARTED_SUBTITLE,
            MessageKey.MINE_COMPLETED to MessageKey.MINE_COMPLETED_SUBTITLE,
        )
    }
}
