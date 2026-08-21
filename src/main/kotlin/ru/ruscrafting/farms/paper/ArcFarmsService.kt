package ru.ruscrafting.farms.paper

import net.kyori.adventure.bossbar.BossBar
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
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.EntityType
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.config.LumberZoneSettings
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.config.MineZoneSettings
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.ArcFarmsState
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.FarmOrder
import ru.ruscrafting.farms.domain.FarmPatchPlanner
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmDeliveryPosition
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmRules
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.LumberRules
import ru.ruscrafting.farms.domain.LumberShiftEngine
import ru.ruscrafting.farms.domain.LumberShiftState
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineRules
import ru.ruscrafting.farms.domain.MineShiftEngine
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.PendingMineBlock
import ru.ruscrafting.farms.domain.PlayerActivityStats
import ru.ruscrafting.farms.domain.ShiftEvent
import ru.ruscrafting.farms.domain.winner
import ru.ruscrafting.farms.persistence.ArcFarmsStateRepository
import ru.ruscrafting.farms.persistence.MineBlockJournal
import ru.ruscrafting.farms.network.ActivityNetworkGateway
import ru.ruscrafting.farms.network.NetworkSignal
import ru.ruscrafting.farms.network.NoOpActivityNetworkGateway
import ru.ruscrafting.farms.network.WorkdayState
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.random.RandomGenerator
import java.util.logging.Level
import kotlin.math.ceil

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

private val FARM_SOIL_TYPES = setOf(Material.DIRT, Material.FARMLAND)
private const val PATCH_PARTICLE_LIMIT = 24
private const val PATCH_PERSIST_INTERVAL = 10

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
    private val network: ActivityNetworkGateway = NoOpActivityNetworkGateway,
    private val transfer: BackendTransfer = BackendTransfer { _, _ -> false },
    private val debug: ArcFarmsDebug = ArcFarmsDebug({ false }) {},
    private val regionGateway: RegionGateway = CuboidRegionGateway(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: RandomGenerator = RandomGenerator.getDefault(),
) : AutoCloseable {
    @Volatile
    private var settings: ArcFarmsConfig = initialSettings
    private var farms: List<FarmRuntime> = emptyList()
    private var lumbermills: List<LumberRuntime> = emptyList()
    private var mines: List<MineRuntime> = emptyList()
    private var stats: MutableMap<UUID, PlayerActivityStats> = mutableMapOf()
    private val activeBars = mutableMapOf<BarKey, BossBar>()
    private val mineReservations = ConcurrentHashMap.newKeySet<String>()
    private val pendingPositions = ConcurrentHashMap<String, String>()
    private val interactionCooldowns = mutableMapOf<String, Long>()
    private val pestEntities = mutableMapOf<String, MutableSet<UUID>>()
    private val farmDroughtTargets = mutableMapOf<String, MutableSet<FarmPlotPosition>>()
    private val managedFarmBeds = mutableMapOf<String, MutableSet<FarmPlotPosition>>()
    private val deliveryEntities = mutableMapOf<String, MutableSet<UUID>>()
    private val deliveryCarriers = mutableMapOf<String, UUID>()
    private val carriedDisplays = mutableMapOf<String, UUID>()
    private val pestZoneKey = NamespacedKey(plugin, "farm_pest_zone")
    private val pestSequenceKey = NamespacedKey(plugin, "farm_pest_sequence")
    private val deliveryZoneKey = NamespacedKey(plugin, "farm_delivery_zone")
    private val deliverySequenceKey = NamespacedKey(plugin, "farm_delivery_sequence")
    private val tasks = mutableListOf<ScheduledTask>()
    private var started = false

    fun start() {
        check(!started) { "ArcFarms service is already started" }
        validateRuntime(settings)
        val persisted = stateRepository.load()
        stats = persisted.stats.toMutableMap()
        rebuild(persisted)
        cleanupOwnedFarmEntities()
        reconcileFarmPatches()
        mineJournal.records().forEach { pendingPositions[it.positionKey] = it.id }
        startTasks()
        started = true
        plugin.logger.info(
            "ArcFarms ready: ${farms.size} farm, ${lumbermills.size} lumbermill, ${mines.size} mine zones; " +
                "${pendingPositions.size} pending mine blocks",
        )
    }

    fun reload(candidate: ArcFarmsConfig) {
        check(started) { "ArcFarms service is not started" }
        val snapshot = snapshotState()
        validateReload(candidate, snapshot)
        validateRuntime(candidate)
        persistBlocking()
        stopTasks()
        hideAllBars()
        cleanupOwnedFarmEntities()
        settings = candidate
        rebuild(snapshot)
        reconcileFarmPatches()
        startTasks()
        plugin.logger.info("ArcFarms reloaded with ${farms.size + lumbermills.size + mines.size} zones")
    }

    fun onBreakHigh(event: BlockBreakEvent) {
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

    fun onInteract(event: PlayerInteractEvent) {
        if (event.action == Action.PHYSICAL) {
            val clicked = event.clickedBlock ?: return
            if (clicked.type == Material.FARMLAND && isManagedFarmSoil(clicked)) event.isCancelled = true
            return
        }
        if (event.hand != EquipmentSlot.HAND || event.action != Action.RIGHT_CLICK_BLOCK) return
        val clicked = event.clickedBlock ?: return
        val player = event.player
        if (handleFarmCareInteraction(event, clicked, player)) return
        lumbermills.firstOrNull { it.station.contains(clicked.location) }?.let { runtime ->
            if (clicked.type !in runtime.stationMaterials) return@let
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
            val accepted = handleLumberProcessing(runtime, player)
            if (accepted) event.isCancelled = true
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

    fun onMove(event: PlayerMoveEvent) {
        val destination = event.to
        if (
            event.from.world == destination.world && event.from.blockX == destination.blockX &&
            event.from.blockY == destination.blockY && event.from.blockZ == destination.blockZ
        ) return
        val player = event.player
        farms.firstOrNull { deliveryCarriers[it.settings.id] == player.uniqueId }?.let { runtime ->
            handleDeliveryMovement(runtime, player, destination)
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
        val keys = activeBars.keys.filter { it.playerId == player.uniqueId }
        keys.forEach { key -> activeBars.remove(key)?.let(player::hideBossBar) }
        farms.filter { deliveryCarriers[it.settings.id] == player.uniqueId }.forEach { runtime ->
            returnDelivery(runtime, player, "player_quit", notify = false)
        }
    }

    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val zoneId = event.rightClicked.persistentDataContainer.get(deliveryZoneKey, PersistentDataType.STRING) ?: return
        event.isCancelled = true
        val sequence = event.rightClicked.persistentDataContainer.get(deliverySequenceKey, PersistentDataType.LONG) ?: return
        val runtime = farms.firstOrNull { it.settings.id == zoneId } ?: return
        val player = event.player
        if (!hasAccess(player, runtime.settings.permission)) {
            sendChat(player, MessageKey.ZONE_LOCKED)
            return
        }
        if (runtime.state.phase != FarmPhase.DELIVERY || runtime.state.sequence != sequence) return
        if (deliveryCarriers.containsKey(zoneId)) return
        if (!allowInteraction("farm-delivery:$zoneId:${player.uniqueId}", 500)) return
        pickupDelivery(runtime, player)
    }

    fun onEntityDamage(event: EntityDamageEvent) {
        if (event.entity.persistentDataContainer.has(deliveryZoneKey, PersistentDataType.STRING)) {
            event.isCancelled = true
        }
    }

    fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity
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
            runtime.state.sequence != sequence || killer == null
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
        val order = currentOrder(runtime) ?: return
        debug.event("farm_pest_killed", "zone" to zoneId, "sequence" to sequence, "player" to killer.name, "entity" to entity.type)
        applyFarmResult(runtime, FarmShiftEngine.defeatPest(runtime.state, order, runtime.rules, killer.uniqueId, clock()), killer)
    }

    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        if (event.entity.persistentDataContainer.has(pestZoneKey, PersistentDataType.STRING)) {
            event.isCancelled = true
            return
        }
        if (isManagedFarmSoil(event.block)) event.isCancelled = true
    }

    fun onBlockFade(event: BlockFadeEvent) {
        if (event.block.type == Material.FARMLAND && isManagedFarmSoil(event.block)) event.isCancelled = true
    }

    fun onBlockPlace(event: BlockPlaceEvent) {
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
                FarmPhase.INCIDENT -> "${runtime.state.incidentProgress}/${runtime.state.incidentRequired}"
                FarmPhase.DELIVERY -> if (deliveryCarriers.containsKey(runtime.settings.id)) "1/2" else "0/2"
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

    fun playerStats(playerId: UUID): PlayerActivityStats = stats[playerId] ?: PlayerActivityStats()

    fun leaderboard(kind: ActivityKind, limit: Int = 10): List<Pair<UUID, Long>> =
        stats.entries
            .map { it.key to (it.value.contributions[kind] ?: 0L) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<UUID, Long>> { it.second }.thenBy { it.first.toString() })
            .take(limit.coerceIn(1, 50))

    fun canNavigate(kind: ActivityKind): Boolean = kind.configKey in settings.destinations

    fun travel(player: Player, kind: ActivityKind) {
        if (!canAccess(player, kind)) {
            sendChat(player, MessageKey.ZONE_LOCKED)
            return
        }
        val destination = settings.destinations.getValue(kind.configKey)
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
            Tasks.scheduler.runSync {
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
        if (!settings.network.enabled) return
        network.claimTravelTicket(player.uniqueId, settings.serverId).whenComplete { ticket, failure ->
            Tasks.scheduler.runLater(1L) {
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
        val destination = settings.destinations.getValue(kind.configKey)
        val world = Bukkit.getWorld(destination.world)
        if (world == null) {
            debug.event("travel_local_failed", "player" to player.name, "activity" to kind, "reason" to "world_unloaded")
            sendChat(player, MessageKey.TRAVEL_FAILED)
            return
        }
        val location = Location(world, destination.x, destination.y, destination.z, destination.yaw, destination.pitch)
        player.teleportAsync(location).whenComplete { success, failure ->
            Tasks.scheduler.runSync {
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
            val orders = configured.orders.map { FarmOrder(it.id, it.required) }
            val orderMap = orders.associateBy(FarmOrder::id)
            val restored = persisted.farms[configured.id]?.takeIf { state ->
                state.orderId == null || (
                    state.orderId in orderMap &&
                        (state.preparationCrop == null || state.preparationCrop in orderMap.getValue(state.orderId).required)
                    )
            }?.let { state ->
                if (state.phase == FarmPhase.PREPARATION && state.preparationPatch.isEmpty()) {
                    state.copy(
                        phase = FarmPhase.HARVESTING,
                        preparationProgress = state.preparationRequired,
                    )
                } else {
                    state
                }
            } ?: FarmShiftState(sequence = persisted.farms[configured.id]?.sequence ?: 0)
            FarmRuntime(
                configured,
                region,
                orderMap,
                orders,
                FarmRules(
                    configured.incidentTriggerPercent,
                    configured.incidentQuota,
                    configured.goldenWindowSeconds * 1000L,
                    settings.completedCooldownSeconds * 1000L,
                ),
                restored,
            )
        }
        lumbermills = settings.lumbermills.map { configured ->
            val region = requireNotNull(regionGateway.resolve(configured.reference)) {
                "Lumber zone ${configured.id} cannot resolve ${configured.reference}"
            }
            val station = requireNotNull(regionGateway.resolve(configured.station)) {
                "Lumber station ${configured.id} cannot resolve ${configured.station}"
            }
            val restored = persisted.lumbermills[configured.id]?.takeIf { state ->
                state.species == null || state.species in configured.species
            } ?: LumberShiftState(sequence = persisted.lumbermills[configured.id]?.sequence ?: 0)
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

    private fun validateReload(candidate: ArcFarmsConfig, snapshot: ArcFarmsState) {
        val farmZones = candidate.farms.associateBy(FarmZoneSettings::id)
        snapshot.farms.filterValues { it.phase !in setOf(FarmPhase.IDLE, FarmPhase.COOLDOWN) }.forEach { (id, state) ->
            val zone = farmZones[id]
            val order = zone?.orders?.firstOrNull { it.id == state.orderId }
            require(order != null) {
                "Cannot remove active farm zone/order $id during reload"
            }
            require(state.preparationCrop == null || state.preparationCrop in order.required) {
                "Cannot remove active farm preparation crop ${state.preparationCrop} from $id"
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
        candidate.destinations.values.filter { it.server == candidate.serverId }.forEach { destination ->
            requireNotNull(Bukkit.getWorld(destination.world)) {
                "Destination world ${destination.world} is not loaded on ${candidate.serverId}"
            }
        }
        candidate.farms.forEach { zone ->
            val region = requireNotNull(regionGateway.resolve(zone.reference)) {
                "Farm zone ${zone.id} cannot resolve ${zone.reference}"
            }
            val deliveryWorld = requireNotNull(Bukkit.getWorld(zone.delivery.world)) {
                "Farm zone ${zone.id} delivery world ${zone.delivery.world} is not loaded"
            }
            val delivery = Location(deliveryWorld, zone.delivery.x, zone.delivery.y, zone.delivery.z)
            require(region.contains(delivery)) { "Farm zone ${zone.id} delivery point is outside ${region.label}" }
            zone.crops.forEach { cropName ->
                val crop = MaterialRules.material(cropName)
                requireNotNull(MaterialRules.seedForCrop(crop)) {
                    "Farm zone ${zone.id} crop $cropName cannot be planted by the preparation flow"
                }
            }
            val pestType = EntityType.valueOf(zone.pestEntity)
            require(pestType.entityClass?.let(LivingEntity::class.java::isAssignableFrom) == true) {
                "Farm zone ${zone.id} pest-entity must be a living entity"
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

    private fun tryStartFarmShift(runtime: FarmRuntime, player: Player, now: Long): Boolean {
        if (runtime.state.phase != FarmPhase.IDLE) return false
        if (!allowInteraction("farm-patch-scan:${runtime.settings.id}", 5_000)) return false
        val order = runtime.orderList[(runtime.state.sequence % runtime.orderList.size).toInt()]
        val candidates = discoverFarmBeds(runtime, player.location)
        val anchor = player.location.toFarmPlotPosition()
        val patch = FarmPatchPlanner.select(candidates, anchor, runtime.settings.preparationPatchSize)
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
        val preparationCrop = order.required.maxWith(
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
        managedFarmBeds.getOrPut(runtime.settings.id) { mutableSetOf() }.addAll(patch)
        if (patch.size < runtime.settings.preparationPatchSize) {
            debug.event(
                "farm_patch_limited",
                "zone" to runtime.settings.id,
                "wanted" to runtime.settings.preparationPatchSize,
                "available" to patch.size,
            )
        }
        applyFarmResult(runtime, started.copy(state = runtime.state), player)
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
                    if (!runtime.region.contains(block.location) || block.type != Material.FARMLAND) continue
                    val above = block.getRelative(org.bukkit.block.BlockFace.UP).type
                    if (!above.isAir && above.name !in runtime.settings.crops) continue
                    candidates += block.toFarmPlotPosition()
                }
            }
        }
        managedFarmBeds.getOrPut(runtime.settings.id) { mutableSetOf() }.addAll(candidates)
        debug.event(
            "farm_beds_discovered",
            "zone" to runtime.settings.id,
            "candidates" to candidates.size,
            "search_radius" to radius,
        )
        return candidates
    }

    private fun releaseFarmPatch(runtime: FarmRuntime): Boolean {
        var complete = true
        runtime.state.preparationPatch.forEach { position ->
            val soil = position.block()
            if (soil == null) {
                complete = false
                return@forEach
            }
            val above = soil.getRelative(org.bukkit.block.BlockFace.UP)
            if (above.type.name in runtime.settings.crops) above.setType(Material.AIR, false)
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

    private fun handleFarmCareInteraction(event: PlayerInteractEvent, clicked: Block, player: Player): Boolean {
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
            else -> target in farmDroughtTargets[runtime.settings.id].orEmpty()
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
            if (drought && player.inventory.itemInMainHand.type == Material.WATER_BUCKET) {
                event.isCancelled = true
                sendActionBar(player, MessageKey.FARM_DROUGHT_REQUIRED)
                return true
            }
            return false
        }
        event.isCancelled = true
        if (!hasAccess(player, runtime.settings.permission)) {
            sendChat(player, MessageKey.ZONE_LOCKED)
            return true
        }
        if (!allowInteraction("farm-care:${runtime.settings.id}:${player.uniqueId}", 120)) return true

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
                player.spawnParticle(Particle.COMPOSTER, soil.location.toCenterLocation().add(0.0, 0.45, 0.0), 8, 0.25, 0.2, 0.25, 0.02)
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
                player.spawnParticle(Particle.COMPOSTER, above.location.toCenterLocation(), 7, 0.25, 0.25, 0.25, 0.02)
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
        farmDroughtTargets[runtime.settings.id]?.remove(target)
        val order = currentOrder(runtime) ?: return true
        val result = FarmShiftEngine.waterDrySoil(runtime.state, order, runtime.rules, player.uniqueId, clock())
        debug.event(
            "farm_drought_committed",
            "player" to player.name,
            "zone" to runtime.settings.id,
            "phase" to runtime.state.phase,
            "x" to soil.x,
            "y" to soil.y,
            "z" to soil.z,
        )
        if (settings.particles) {
            player.spawnParticle(Particle.COMPOSTER, soil.location.toCenterLocation().add(0.0, 0.45, 0.0), 10, 0.25, 0.2, 0.25, 0.02)
            player.spawnParticle(Particle.SPLASH, soil.location.toCenterLocation().add(0.0, 0.5, 0.0), 18, 0.35, 0.2, 0.35, 0.08)
        }
        if (settings.sounds) {
            player.playSound(soil.location, Sound.ITEM_BUCKET_EMPTY, 0.8f, 1.05f)
        }
        applyFarmResult(runtime, result, player)
        ensureFarmDroughtTargets(runtime)
        return true
    }

    private fun handleFarmBreakHigh(event: BlockBreakEvent, runtime: FarmRuntime) {
        val player = event.player
        if (!hasAccess(player, runtime.settings.permission)) {
            event.isCancelled = true
            sendChat(player, MessageKey.ZONE_LOCKED)
            return
        }
        val crop = event.block.type
        if (crop.name !in runtime.settings.crops) {
            event.isCancelled = true
            return
        }
        val ageable = event.block.blockData as? Ageable ?: return
        if (ageable.age < ageable.maximumAge) {
            event.isCancelled = true
            debug.event("farm_crop_rejected", "player" to player.name, "zone" to runtime.settings.id, "crop" to crop, "reason" to "immature")
            return
        }
        val now = clock()
        if (runtime.state.phase == FarmPhase.COOLDOWN) {
            event.isCancelled = true
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
                event.isCancelled = true
                return
            }
        }
        if (runtime.state.phase in setOf(FarmPhase.PREPARATION, FarmPhase.PLANTING)) {
            event.isCancelled = true
            if (runtime.state.phase == FarmPhase.PREPARATION) {
                sendActionBar(player, MessageKey.FARM_PREPARATION_REQUIRED)
            } else {
                val preparationCrop = MaterialRules.material(requireNotNull(runtime.state.preparationCrop))
                sendActionBar(
                    player,
                    MessageKey.FARM_PLANTING_REQUIRED,
                    mapOf("crop" to MaterialRules.cropComponent(preparationCrop)),
                )
            }
            debug.event(
                "farm_crop_rejected",
                "player" to player.name,
                "zone" to runtime.settings.id,
                "crop" to crop,
                "reason" to if (runtime.state.phase == FarmPhase.PREPARATION) "tilling_required" else "planting_required",
            )
            return
        }
        if (runtime.state.phase == FarmPhase.INCIDENT) {
            event.isCancelled = true
            val drought = (runtime.state.incidentType ?: FarmIncidentType.PESTS) == FarmIncidentType.DROUGHT
            sendActionBar(player, if (drought) MessageKey.FARM_DROUGHT_REQUIRED else MessageKey.FARM_PESTS_REQUIRED)
            debug.event(
                "farm_crop_rejected",
                "player" to player.name,
                "zone" to runtime.settings.id,
                "crop" to crop,
                "reason" to if (drought) "drought_active" else "pests_active",
            )
            return
        }
        if (runtime.state.phase == FarmPhase.DELIVERY) {
            event.isCancelled = true
            sendActionBar(player, MessageKey.FARM_DELIVERY_REQUIRED)
            debug.event("farm_crop_rejected", "player" to player.name, "zone" to runtime.settings.id, "crop" to crop, "reason" to "delivery_required")
            return
        }
        val order = currentOrder(runtime) ?: return
        if (crop.name !in order.required || (runtime.state.progress[crop.name] ?: 0) >= order.required.getValue(crop.name)) {
            event.isCancelled = true
            sendActionBar(player, MessageKey.FARM_WRONG_TARGET, mapOf("crops" to remainingCrops(runtime, order)))
            debug.event("farm_crop_rejected", "player" to player.name, "zone" to runtime.settings.id, "crop" to crop, "reason" to "not_requested")
            return
        }
        val block = event.block
        val replantData = block.blockData.clone() as Ageable
        replantData.age = 0
        event.isDropItems = false
        event.expToDrop = 0
        debug.event("farm_crop_committed", "player" to player.name, "zone" to runtime.settings.id, "crop" to crop, "drops" to "consumed_by_order")
        Tasks.scheduler.runLater(1L) {
            if (!block.type.isAir) return@runLater
            block.setBlockData(replantData, false)
            handleFarmHarvest(runtime, player, crop.name, block.location.toCenterLocation())
        }
    }

    private fun handleFarmHarvest(runtime: FarmRuntime, player: Player, crop: String, harvestedAt: Location) {
        val now = clock()
        if (runtime.state.phase == FarmPhase.COOLDOWN) {
            val seconds = remainingSeconds(runtime.state.cooldownEndsAt, now)
            sendActionBar(player, MessageKey.COOLDOWN, mapOf("seconds" to locale.text(seconds)))
            return
        }
        val order = currentOrder(runtime) ?: return
        val incidentType = runtime.settings.incidentTypes[random.nextInt(runtime.settings.incidentTypes.size)]
        var result = FarmShiftEngine.harvest(
            runtime.state,
            order,
            runtime.rules,
            crop,
            player.uniqueId,
            now,
            incidentType,
        )
        if (ShiftEvent.DELIVERY_STARTED in result.events) {
            result = result.copy(
                state = result.state.copy(
                    deliveryPosition = FarmDeliveryPosition(
                        harvestedAt.world.name,
                        harvestedAt.x,
                        harvestedAt.y,
                        harvestedAt.z,
                    ),
                ),
            )
        }
        applyFarmResult(runtime, result, player)
        if (!result.accepted && ShiftEvent.COMPLETED !in result.events) {
            sendActionBar(player, MessageKey.FARM_WRONG_TARGET, mapOf("crops" to remainingCrops(runtime, order)))
        }
    }

    private fun handleLumberBreakHigh(event: BlockBreakEvent, runtime: LumberRuntime) {
        if (!hasAccess(event.player, runtime.settings.permission)) {
            event.isCancelled = true
            sendChat(event.player, MessageKey.ZONE_LOCKED)
            return
        }
        if (!MaterialRules.isLumberBreakable(event.block.type)) event.isCancelled = true
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
            Tasks.scheduler.runSync {
                if (failure != null) {
                    mineReservations.remove(positionKey)
                    if (player.isOnline) sendChat(player, MessageKey.MINE_JOURNAL_FAILED)
                    plugin.logger.log(Level.SEVERE, "Could not journal mine block ${record.id}", failure)
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
                if (experience > 0) player.giveExp(experience)
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
                ShiftEvent.STARTED -> broadcast(
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
                    val crop = MaterialRules.material(requireNotNull(runtime.state.preparationCrop))
                    broadcast(
                        runtime.region,
                        MessageKey.FARM_PLANTING_STARTED,
                        mapOf(
                            "crop" to MaterialRules.cropComponent(crop),
                            "total" to locale.text(runtime.state.preparationRequired),
                        ),
                        Sound.ENTITY_VILLAGER_WORK_FARMER,
                        title = true,
                    )
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
                    broadcast(
                        runtime.region,
                        MessageKey.FARM_PREPARATION_COMPLETED,
                        sound = Sound.ENTITY_VILLAGER_YES,
                        title = true,
                    )
                    successBurst(runtime.region)
                    persistAsync()
                }
                ShiftEvent.INCIDENT_STARTED -> {
                    if (incidentType == FarmIncidentType.DROUGHT) {
                        broadcast(
                            runtime.region,
                            MessageKey.FARM_DROUGHT_STARTED,
                            mapOf("total" to locale.text(runtime.state.incidentRequired)),
                            Sound.WEATHER_RAIN_ABOVE,
                            title = true,
                        )
                    } else {
                        broadcast(
                            runtime.region,
                            MessageKey.FARM_INCIDENT_STARTED,
                            mapOf(
                                "crop" to MaterialRules.cropComponent(MaterialRules.material(requireNotNull(runtime.state.incidentCrop))),
                                "total" to locale.text(runtime.state.incidentRequired),
                            ),
                            Sound.ENTITY_BEE_LOOP_AGGRESSIVE,
                            title = true,
                        )
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
                    sendActionBar(
                        actor,
                        if (incidentType == FarmIncidentType.DROUGHT) MessageKey.FARM_DROUGHT_PROGRESS else MessageKey.FARM_INCIDENT_PROGRESS,
                        mapOf(
                            "done" to locale.text(runtime.state.incidentProgress),
                            "total" to locale.text(runtime.state.incidentRequired),
                        ),
                    )
                    if (settings.particles) {
                        actor.spawnParticle(Particle.COMPOSTER, actor.location.add(0.0, 1.0, 0.0), 6, 0.35, 0.4, 0.35, 0.02)
                    }
                }
                ShiftEvent.INCIDENT_RESOLVED -> {
                    broadcast(
                        runtime.region,
                        MessageKey.FARM_INCIDENT_RESOLVED,
                        sound = Sound.ENTITY_VILLAGER_YES,
                        title = true,
                    )
                    successBurst(runtime.region)
                    network.signal(
                        NetworkSignal.FARM_RESCUED,
                        ActivityKind.FARM,
                        actor?.name,
                        players(runtime.region).mapTo(mutableSetOf(), Player::getUniqueId),
                    )
                    persistAsync()
                }
                ShiftEvent.GOLDEN_STARTED -> broadcast(
                    runtime.region,
                    MessageKey.FARM_GOLDEN_STARTED,
                    mapOf(
                        "crop" to MaterialRules.cropComponent(MaterialRules.material(requireNotNull(runtime.state.goldenCrop))),
                        "seconds" to locale.text(runtime.rules.goldenWindowMillis / 1000),
                    ),
                    Sound.ENTITY_PLAYER_LEVELUP,
                    title = true,
                )
                ShiftEvent.GOLDEN_ENDED -> broadcast(runtime.region, MessageKey.FARM_GOLDEN_ENDED)
                ShiftEvent.DELIVERY_STARTED -> {
                    broadcast(
                        runtime.region,
                        MessageKey.FARM_DELIVERY_STARTED,
                        sound = Sound.BLOCK_BARREL_CLOSE,
                        title = true,
                    )
                    removePests(runtime, activePests(runtime), "delivery_started")
                    farmDroughtTargets.remove(runtime.settings.id)
                    ensureFarmDelivery(runtime)
                    persistAsync()
                }
                ShiftEvent.COMPLETED -> {
                    clearDelivery(runtime, "completed")
                    recordCompletion(ActivityKind.FARM, runtime.state.contributors)
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
        tasks += Tasks.scheduler.runTimer(20L, 20L) { tick() }
        tasks += Tasks.scheduler.runTimer(10L, 10L) { emitGuidanceParticles() }
        tasks += Tasks.scheduler.runTimer(
            settings.saveSeconds * 20L,
            settings.saveSeconds * 20L,
        ) { persistAsync() }
    }

    private fun stopTasks() {
        tasks.forEach(ScheduledTask::cancel)
        tasks.clear()
    }

    private fun tick() {
        val now = clock()
        farms.forEach { runtime ->
            if (runtime.state.phase == FarmPhase.IDLE) {
                players(runtime.region).firstOrNull()?.let { player ->
                    tryStartFarmShift(runtime, player, now)
                }
            }
            val result = FarmShiftEngine.tick(runtime.state, currentOrder(runtime), runtime.rules, now)
            if (result.events.isNotEmpty()) applyFarmResult(runtime, result, null)
            ensureFarmDroughtTargets(runtime)
            ensureFarmPests(runtime)
            ensureFarmDelivery(runtime)
            maintainWetFarmBeds(runtime)
        }
        lumbermills.forEach { runtime ->
            val result = LumberShiftEngine.tick(runtime.state, runtime.rules, now)
            if (result.events.isNotEmpty()) applyLumberResult(runtime, result, null)
        }
        mines.forEach { runtime ->
            val result = MineShiftEngine.tick(runtime.state, runtime.rules, now)
            if (result.events.isNotEmpty()) applyMineResult(runtime, result, null)
        }
        restoreMineBlocks(now)
        updatePlayerGuidance()
    }

    private fun restoreMineBlocks(now: Long) {
        mineJournal.records().filter { it.restoreAt <= now }.forEach { record ->
            val world = Bukkit.getWorld(record.world) ?: return@forEach
            if (!world.isChunkLoaded(record.x shr 4, record.z shr 4)) return@forEach
            val block = world.getBlockAt(record.x, record.y, record.z)
            val temporary = runCatching { MaterialRules.material(record.temporaryMaterial) }.getOrNull()
            val next = runCatching { MaterialRules.material(record.nextMaterial) }.getOrNull()
            if (temporary != null && next != null && block.type == temporary) block.setType(next, false)
            mineJournal.remove(record.id).whenComplete { _, failure ->
                if (failure == null) pendingPositions.remove(record.positionKey, record.id)
                else plugin.logger.log(Level.SEVERE, "Could not retire mine journal record ${record.id}", failure)
            }
        }
    }

    private fun updatePlayerGuidance() {
        val expectedBars = mutableSetOf<BarKey>()
        farms.forEach { runtime ->
            if (
                runtime.state.phase !in setOf(
                    FarmPhase.PREPARATION,
                    FarmPhase.PLANTING,
                    FarmPhase.HARVESTING,
                    FarmPhase.INCIDENT,
                    FarmPhase.GOLDEN_HARVEST,
                    FarmPhase.DELIVERY,
                )
            ) return@forEach
            val order = currentOrder(runtime) ?: return@forEach
            val done = runtime.state.completed(order)
            players(runtime.region).forEach { player ->
                val carrier = deliveryCarriers[runtime.settings.id]
                val key = when (runtime.state.phase) {
                    FarmPhase.PREPARATION -> MessageKey.FARM_PREPARATION_BOSSBAR
                    FarmPhase.PLANTING -> MessageKey.FARM_PLANTING_BOSSBAR
                    FarmPhase.INCIDENT -> if ((runtime.state.incidentType ?: FarmIncidentType.PESTS) == FarmIncidentType.DROUGHT) {
                        MessageKey.FARM_DROUGHT_BOSSBAR
                    } else {
                        MessageKey.FARM_INCIDENT_BOSSBAR
                    }
                    FarmPhase.GOLDEN_HARVEST -> MessageKey.FARM_GOLDEN_BOSSBAR
                    FarmPhase.DELIVERY -> if (carrier == player.uniqueId) {
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
                                runtime.state.goldenCrop ?: order.required.keys.firstOrNull()
                            },
                        ))),
                        "requirements" to farmRequirements(runtime, order),
                        "done" to locale.text(
                            when (runtime.state.phase) {
                                FarmPhase.PREPARATION -> runtime.state.preparationProgress
                                FarmPhase.PLANTING -> runtime.state.plantingProgress
                                FarmPhase.INCIDENT -> runtime.state.incidentProgress
                                FarmPhase.DELIVERY -> if (carrier == null) 0 else 1
                                else -> done
                            },
                        ),
                        "total" to locale.text(
                            when (runtime.state.phase) {
                                FarmPhase.PREPARATION -> runtime.state.preparationRequired
                                FarmPhase.PLANTING -> runtime.state.preparationRequired
                                FarmPhase.INCIDENT -> runtime.state.incidentRequired
                                FarmPhase.DELIVERY -> 2
                                else -> order.totalRequired
                            },
                        ),
                    ),
                )
                val progress = when (runtime.state.phase) {
                    FarmPhase.PREPARATION -> runtime.state.preparationProgress.toFloat() / runtime.state.preparationRequired
                    FarmPhase.PLANTING -> runtime.state.plantingProgress.toFloat() / runtime.state.preparationRequired
                    FarmPhase.INCIDENT -> runtime.state.incidentProgress.toFloat() / runtime.state.incidentRequired
                    FarmPhase.DELIVERY -> if (carrier == null) 0f else 0.5f
                    else -> runtime.state.progressRatio(order).toFloat()
                }
                updateBar(
                    player,
                    "farm:${runtime.settings.id}",
                    component,
                    progress,
                    when (runtime.state.phase) {
                        FarmPhase.PREPARATION -> BossBar.Color.WHITE
                        FarmPhase.PLANTING -> BossBar.Color.GREEN
                        FarmPhase.INCIDENT -> BossBar.Color.RED
                        FarmPhase.GOLDEN_HARVEST -> BossBar.Color.YELLOW
                        FarmPhase.DELIVERY -> BossBar.Color.PURPLE
                        else -> BossBar.Color.GREEN
                    },
                    expectedBars,
                )
            }
        }
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
            val patch = runtime.state.preparationPatch
            if (patch.isEmpty()) return@forEach
            managedFarmBeds.getOrPut(runtime.settings.id) { mutableSetOf() }.addAll(patch)
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

    private fun maintainWetFarmBeds(runtime: FarmRuntime) {
        if (runtime.state.preparationPatch.isNotEmpty() && !runtime.state.preparationReleased) {
            if (releaseFarmPatch(runtime)) {
                runtime.state = runtime.state.copy(preparationReleased = true)
                persistAsync()
            }
            return
        }
        val positions = linkedSetOf<FarmPlotPosition>().apply {
            addAll(managedFarmBeds[runtime.settings.id].orEmpty())
            addAll(runtime.state.preparationPatch)
        }
        val crop = runtime.state.preparationCrop?.let(MaterialRules::material)
        positions.forEach { position ->
            val soil = position.block() ?: return@forEach
            if (position in runtime.state.tilledPlots && soil.type != Material.FARMLAND) setWetFarmland(soil)
            if (soil.type == Material.FARMLAND) setWetFarmland(soil)
            if (crop != null && position in runtime.state.plantedPlots) {
                val above = soil.getRelative(org.bukkit.block.BlockFace.UP)
                if (above.type.isAir) above.setBlockData(crop.createBlockData(), false)
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

    private fun isManagedFarmSoil(block: Block): Boolean {
        val position = block.toFarmPlotPosition()
        return managedFarmBeds.values.any { position in it } || farms.any { position in it.state.preparationPatch }
    }

    private fun ensureFarmDroughtTargets(runtime: FarmRuntime) {
        val remaining = if (
            runtime.state.phase == FarmPhase.INCIDENT &&
            (runtime.state.incidentType ?: FarmIncidentType.PESTS) == FarmIncidentType.DROUGHT
        ) {
            runtime.state.incidentRequired - runtime.state.incidentProgress
        } else {
            0
        }.coerceAtLeast(0)
        if (remaining == 0) {
            farmDroughtTargets.remove(runtime.settings.id)
            return
        }
        val nearbyPlayers = players(runtime.region)
        if (nearbyPlayers.isEmpty()) {
            farmDroughtTargets.remove(runtime.settings.id)
            return
        }
        val targets = farmDroughtTargets.getOrPut(runtime.settings.id) { mutableSetOf() }
        targets.removeIf { position ->
            val location = position.location()
            location == null || !runtime.region.contains(location) || location.block.type != Material.FARMLAND
        }
        val desired = remaining.coerceAtMost(3)
        if (targets.size >= desired) return
        val anchor = nearbyPlayers.minByOrNull { player ->
            targets.minOfOrNull { target -> target.location()?.distanceSquared(player.location) ?: Double.MAX_VALUE }
                ?: 0.0
        } ?: return
        val candidates = nearbyBlocks(
            anchor.location,
            runtime.region,
            runtime.settings.careRadius,
            3,
            256,
        ) { block -> block.type == Material.FARMLAND && block.toFarmPlotPosition() !in targets }
            .sortedBy { random.nextDouble() }
        candidates.take(desired - targets.size).forEach { targets += it.toFarmPlotPosition() }
        if (targets.size < desired && allowInteraction("farm-care-missing:${runtime.settings.id}", 10_000)) {
            debug.event(
                "farm_care_targets_limited",
                "zone" to runtime.settings.id,
                "phase" to runtime.state.phase,
                "wanted" to desired,
                "available" to targets.size,
            )
        }
    }

    private fun ensureFarmDelivery(runtime: FarmRuntime) {
        if (runtime.state.phase != FarmPhase.DELIVERY) {
            if (deliveryCarriers.containsKey(runtime.settings.id) || deliveryEntities[runtime.settings.id].orEmpty().isNotEmpty()) {
                clearDelivery(runtime, "phase_inactive")
            }
            return
        }
        val zoneId = runtime.settings.id
        val carrierId = deliveryCarriers[zoneId]
        if (carrierId != null) {
            val carrier = Bukkit.getPlayer(carrierId)
            if (carrier == null || !carrier.isOnline) {
                returnDelivery(runtime, carrier, "carrier_offline", notify = false)
            } else {
                handleDeliveryMovement(runtime, carrier, carrier.location)
                updateCarriedDisplay(runtime, carrier)
            }
            return
        }
        val activeGroundEntities = deliveryEntities[zoneId].orEmpty().mapNotNull(Bukkit::getEntity).filter { entity ->
            entity.isValid &&
                entity.persistentDataContainer.get(deliveryZoneKey, PersistentDataType.STRING) == zoneId &&
                entity.persistentDataContainer.get(deliverySequenceKey, PersistentDataType.LONG) == runtime.state.sequence
        }
        if (activeGroundEntities.size >= 2) {
            deliveryEntities[zoneId] = activeGroundEntities.mapTo(mutableSetOf(), org.bukkit.entity.Entity::getUniqueId)
            return
        }
        removeDeliveryEntities(runtime, "replace_ground_entities")
        val position = runtime.state.deliveryPosition ?: players(runtime.region).firstOrNull()?.location?.let { location ->
            FarmDeliveryPosition(location.world.name, location.x, location.y, location.z)
        } ?: return
        if (runtime.state.deliveryPosition == null) {
            runtime.state = runtime.state.copy(deliveryPosition = position)
            persistAsync()
        }
        val world = Bukkit.getWorld(position.world) ?: return
        val location = Location(world, position.x, position.y, position.z)
        if (!runtime.region.contains(location)) {
            plugin.logger.severe("Farm delivery position left ${runtime.region.label} for ${runtime.settings.id}; crate was not spawned")
            return
        }
        val display = world.spawn(location.clone().add(0.0, 0.45, 0.0), ItemDisplay::class.java) { entity ->
            entity.setItemStack(ItemStack(Material.BARREL))
            entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
            entity.isGlowing = true
            entity.isPersistent = true
            entity.customName(locale.render(MessageKey.FARM_CRATE_NAME))
            entity.isCustomNameVisible = true
            markDeliveryEntity(entity, runtime)
        }
        val interaction = world.spawn(location, Interaction::class.java) { entity ->
            entity.interactionWidth = 1.35f
            entity.interactionHeight = 1.45f
            entity.isResponsive = true
            entity.isPersistent = true
            markDeliveryEntity(entity, runtime)
        }
        deliveryEntities.getOrPut(zoneId) { mutableSetOf() }.addAll(listOf(display.uniqueId, interaction.uniqueId))
        debug.event(
            "farm_delivery_spawned",
            "zone" to zoneId,
            "sequence" to runtime.state.sequence,
            "x" to position.x,
            "y" to position.y,
            "z" to position.z,
        )
    }

    private fun markDeliveryEntity(entity: org.bukkit.entity.Entity, runtime: FarmRuntime) {
        entity.persistentDataContainer.set(deliveryZoneKey, PersistentDataType.STRING, runtime.settings.id)
        entity.persistentDataContainer.set(deliverySequenceKey, PersistentDataType.LONG, runtime.state.sequence)
    }

    private fun pickupDelivery(runtime: FarmRuntime, player: Player) {
        removeDeliveryEntities(runtime, "picked_up")
        deliveryCarriers[runtime.settings.id] = player.uniqueId
        val display = player.world.spawn(carriedDisplayLocation(player), ItemDisplay::class.java) { entity ->
            entity.setItemStack(ItemStack(Material.BARREL))
            entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
            entity.teleportDuration = 3
            entity.isGlowing = true
            entity.isPersistent = true
            markDeliveryEntity(entity, runtime)
        }
        carriedDisplays[runtime.settings.id] = display.uniqueId
        val message = locale.render(MessageKey.FARM_DELIVERY_PICKED_UP, player)
        player.showTitle(Title.title(message, Component.empty(), TITLE_TIMES))
        debug.message("title", "player", MessageKey.FARM_DELIVERY_PICKED_UP.path, player, message)
        if (settings.sounds) player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 0.9f, 0.8f)
        if (settings.particles) {
            player.spawnParticle(Particle.END_ROD, player.location.add(0.0, 1.0, 0.0), 12, 0.45, 0.5, 0.45, 0.03)
        }
        debug.event(
            "farm_delivery_picked_up",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "player" to player.name,
        )
        persistAsync()
        handleDeliveryMovement(runtime, player, player.location)
    }

    private fun handleDeliveryMovement(runtime: FarmRuntime, player: Player, destination: Location) {
        if (deliveryCarriers[runtime.settings.id] != player.uniqueId) return
        if (runtime.state.phase != FarmPhase.DELIVERY) {
            clearDelivery(runtime, "state_changed")
            return
        }
        if (!runtime.region.contains(destination)) {
            returnDelivery(runtime, player, "left_zone")
            return
        }
        updateCarriedDisplay(runtime, player)
        val delivery = runtime.settings.delivery
        if (destination.world.name != delivery.world) return
        val target = Location(destination.world, delivery.x, delivery.y, delivery.z)
        if (destination.distanceSquared(target) > delivery.radius * delivery.radius) return
        debug.event(
            "farm_delivery_completed",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "player" to player.name,
        )
        applyFarmResult(
            runtime,
            FarmShiftEngine.deliver(runtime.state, runtime.rules, player.uniqueId, clock()),
            player,
        )
    }

    private fun updateCarriedDisplay(runtime: FarmRuntime, player: Player) {
        val display = carriedDisplays[runtime.settings.id]?.let(Bukkit::getEntity) as? ItemDisplay ?: return
        display.teleport(carriedDisplayLocation(player))
    }

    private fun carriedDisplayLocation(player: Player): Location {
        val direction = player.location.direction.setY(0)
        if (direction.lengthSquared() > 0.001) direction.normalize().multiply(-0.65)
        return player.location.clone().add(direction).add(0.0, 1.0, 0.0)
    }

    private fun returnDelivery(runtime: FarmRuntime, player: Player?, reason: String, notify: Boolean = true) {
        val zoneId = runtime.settings.id
        deliveryCarriers.remove(zoneId)
        carriedDisplays.remove(zoneId)?.let(Bukkit::getEntity)?.remove()
        if (notify && player?.isOnline == true) sendActionBar(player, MessageKey.FARM_DELIVERY_RETURNED)
        debug.event(
            "farm_delivery_returned",
            "zone" to zoneId,
            "sequence" to runtime.state.sequence,
            "player" to player?.name,
            "reason" to reason,
        )
    }

    private fun removeDeliveryEntities(runtime: FarmRuntime, reason: String) {
        val ids = deliveryEntities.remove(runtime.settings.id).orEmpty()
        ids.forEach { id -> Bukkit.getEntity(id)?.remove() }
        if (ids.isNotEmpty()) debug.event("farm_delivery_entities_removed", "zone" to runtime.settings.id, "count" to ids.size, "reason" to reason)
    }

    private fun clearDelivery(runtime: FarmRuntime, reason: String) {
        removeDeliveryEntities(runtime, reason)
        deliveryCarriers.remove(runtime.settings.id)
        carriedDisplays.remove(runtime.settings.id)?.let(Bukkit::getEntity)?.remove()
    }

    private fun ensureFarmPests(runtime: FarmRuntime) {
        val active = activePests(runtime).toMutableList()
        if (
            runtime.state.phase != FarmPhase.INCIDENT ||
            (runtime.state.incidentType ?: FarmIncidentType.PESTS) != FarmIncidentType.PESTS
        ) {
            if (active.isNotEmpty()) removePests(runtime, active, "incident_inactive")
            return
        }
        val nearbyPlayers = players(runtime.region)
        if (nearbyPlayers.isEmpty()) {
            if (active.isNotEmpty()) removePests(runtime, active, "zone_empty")
            return
        }
        val required = (runtime.state.incidentRequired - runtime.state.incidentProgress).coerceAtLeast(0)
        if (active.size > required) {
            removePests(runtime, active.drop(required), "surplus")
            active.subList(required, active.size).clear()
        }
        repeat(required - active.size) {
            spawnPest(runtime, nearbyPlayers[it % nearbyPlayers.size])
        }
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

    private fun spawnPest(runtime: FarmRuntime, anchor: Player) {
        val location = findPestSpawn(runtime, anchor.location) ?: run {
            debug.event("farm_pest_spawn_failed", "zone" to runtime.settings.id, "reason" to "no_safe_location")
            return
        }
        val entity = runtime.region.world.spawnEntity(location, EntityType.valueOf(runtime.settings.pestEntity)) as? LivingEntity ?: return
        entity.persistentDataContainer.set(pestZoneKey, PersistentDataType.STRING, runtime.settings.id)
        entity.persistentDataContainer.set(pestSequenceKey, PersistentDataType.LONG, runtime.state.sequence)
        entity.isPersistent = true
        entity.removeWhenFarAway = false
        (entity as? Mob)?.target = anchor
        entity.customName(locale.render(MessageKey.FARM_PEST_NAME, anchor))
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
    }

    private fun findPestSpawn(runtime: FarmRuntime, anchor: Location): Location? {
        val radius = runtime.settings.pestSpawnRadius
        repeat(24) {
            val x = anchor.blockX + random.nextInt(-radius, radius + 1)
            val z = anchor.blockZ + random.nextInt(-radius, radius + 1)
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

    private fun cleanupOwnedFarmEntities() {
        val worlds = farms.map { it.region.world }.distinct()
        var removed = 0
        worlds.flatMap { it.entities }.forEach { entity ->
            if (
                entity.persistentDataContainer.has(pestZoneKey, PersistentDataType.STRING) ||
                entity.persistentDataContainer.has(deliveryZoneKey, PersistentDataType.STRING)
            ) {
                entity.remove()
                removed++
            }
        }
        pestEntities.clear()
        farmDroughtTargets.clear()
        managedFarmBeds.clear()
        deliveryEntities.clear()
        deliveryCarriers.clear()
        carriedDisplays.clear()
        if (removed > 0) debug.event("farm_entities_cleanup", "count" to removed)
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
            players(runtime.region).forEach { player ->
                runtime.state.preparationPatch.asSequence()
                    .filterNot(runtime.state.tilledPlots::contains)
                    .mapNotNull(FarmPlotPosition::location)
                    .sortedBy(player.location::distanceSquared)
                    .take(PATCH_PARTICLE_LIMIT)
                    .forEach { location ->
                    player.spawnParticle(Particle.COMPOSTER, location.toCenterLocation().add(0.0, 0.55, 0.0), 3, 0.22, 0.12, 0.22, 0.01)
                    player.spawnParticle(Particle.HAPPY_VILLAGER, location.toCenterLocation().add(0.0, 0.8, 0.0), 1)
                }
            }
        }
        farms.filter { it.state.phase == FarmPhase.PLANTING }.forEach { runtime ->
            players(runtime.region).forEach { player ->
                runtime.state.preparationPatch.asSequence()
                    .filterNot(runtime.state.plantedPlots::contains)
                    .mapNotNull(FarmPlotPosition::location)
                    .sortedBy(player.location::distanceSquared)
                    .take(PATCH_PARTICLE_LIMIT)
                    .forEach { location ->
                        player.spawnParticle(Particle.HAPPY_VILLAGER, location.toCenterLocation().add(0.0, 0.75, 0.0), 2, 0.18, 0.12, 0.18, 0.0)
                    }
            }
        }
        farms.filter { it.state.phase == FarmPhase.GOLDEN_HARVEST }.forEach { runtime ->
            val target = MaterialRules.material(requireNotNull(runtime.state.goldenCrop))
            players(runtime.region).forEach { player ->
                nearbyBlocks(player.location, runtime.region, 5, 3, 12) { it.type == target }.forEach { block ->
                    player.spawnParticle(Particle.HAPPY_VILLAGER, block.location.toCenterLocation(), 2, 0.15, 0.15, 0.15, 0.0)
                }
            }
        }
        farms.filter {
            it.state.phase == FarmPhase.INCIDENT &&
                (it.state.incidentType ?: FarmIncidentType.PESTS) == FarmIncidentType.PESTS
        }.forEach { runtime ->
            val target = MaterialRules.material(requireNotNull(runtime.state.incidentCrop))
            players(runtime.region).forEach { player ->
                nearbyBlocks(player.location, runtime.region, 5, 3, 12) { it.type == target }.forEach { block ->
                    player.spawnParticle(Particle.ANGRY_VILLAGER, block.location.toCenterLocation().add(0.0, 0.45, 0.0), 1)
                    player.spawnParticle(Particle.SMOKE, block.location.toCenterLocation(), 2, 0.18, 0.2, 0.18, 0.01)
                }
            }
        }
        farms.filter {
            it.state.phase == FarmPhase.INCIDENT && it.state.incidentType == FarmIncidentType.DROUGHT
        }.forEach { runtime ->
            val targets = farmDroughtTargets[runtime.settings.id].orEmpty().mapNotNull(FarmPlotPosition::location)
            players(runtime.region).forEach { player ->
                targets.forEach { location ->
                    player.spawnParticle(Particle.SMOKE, location.toCenterLocation().add(0.0, 0.55, 0.0), 4, 0.25, 0.15, 0.25, 0.015)
                    player.spawnParticle(Particle.DRIPPING_WATER, location.toCenterLocation().add(0.0, 1.0, 0.0), 2, 0.2, 0.1, 0.2, 0.0)
                }
            }
        }
        farms.filter { it.state.phase == FarmPhase.DELIVERY }.forEach { runtime ->
            val carrierId = deliveryCarriers[runtime.settings.id] ?: return@forEach
            val player = Bukkit.getPlayer(carrierId)?.takeIf(Player::isOnline) ?: return@forEach
            val delivery = runtime.settings.delivery
            val world = Bukkit.getWorld(delivery.world) ?: return@forEach
            if (player.world != world) return@forEach
            val target = Location(world, delivery.x, delivery.y, delivery.z)
            player.spawnParticle(Particle.END_ROD, target.clone().add(0.0, 0.8, 0.0), 5, 0.45, 0.6, 0.45, 0.015)
            player.spawnParticle(Particle.HAPPY_VILLAGER, target.clone().add(0.0, 1.2, 0.0), 2, 0.3, 0.25, 0.3, 0.0)
        }
        lumbermills.filter { it.state.phase == LumberPhase.FELLING }.forEach { runtime ->
            val species = runtime.state.species ?: return@forEach
            players(runtime.region).forEach { player ->
                nearbyBlocks(player.location, runtime.region, 6, 5, 16) { MaterialRules.speciesOf(it.type) == species }.forEach { block ->
                    player.spawnParticle(Particle.COMPOSTER, block.location.toCenterLocation(), 2, 0.2, 0.35, 0.2, 0.0)
                }
            }
        }
        mines.filter { it.state.phase == MinePhase.HAZARD }.forEach { runtime ->
            players(runtime.region).filter { mineAt(it.location) === runtime }.forEach { player ->
                player.spawnParticle(Particle.SMOKE, player.location.add(0.0, 1.0, 0.0), 3, 0.8, 0.3, 0.8, 0.01)
            }
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
            val message = locale.render(key, player, valuesForPlayer?.invoke(player) ?: values)
            if (title) {
                player.showTitle(Title.title(message, Component.empty(), TITLE_TIMES))
                debug.message("title", "local", key.path, player, message)
            } else {
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

    private fun warningBurst(region: ActivityRegion) {
        if (!settings.particles) return
        players(region).forEach { player ->
            player.spawnParticle(Particle.LARGE_SMOKE, player.location.add(0.0, 1.0, 0.0), 12, 1.0, 0.45, 1.0, 0.02)
            player.spawnParticle(Particle.ANGRY_VILLAGER, player.location.add(0.0, 1.8, 0.0), 4, 0.8, 0.3, 0.8, 0.0)
        }
    }

    private fun successBurst(region: ActivityRegion) {
        if (!settings.particles) return
        players(region).forEach { player ->
            player.spawnParticle(Particle.HAPPY_VILLAGER, player.location.add(0.0, 1.0, 0.0), 18, 1.0, 0.7, 1.0, 0.03)
            player.spawnParticle(Particle.END_ROD, player.location.add(0.0, 1.0, 0.0), 10, 0.8, 0.6, 0.8, 0.02)
        }
    }

    private fun celebration(region: ActivityRegion) = celebration(listOf(region))

    private fun celebration(regions: Collection<ActivityRegion>) {
        val recipients = regions.flatMap(::players).distinctBy(Player::getUniqueId)
        recipients.forEach { player ->
            if (settings.particles) {
                player.spawnParticle(Particle.FIREWORK, player.location.add(0.0, 1.2, 0.0), 28, 1.4, 1.0, 1.4, 0.08)
                player.spawnParticle(Particle.FLASH, player.location.add(0.0, 1.6, 0.0), 2, 0.5, 0.4, 0.5, 0.0, Color.LIME)
            }
            if (settings.sounds) player.playSound(player.location, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.7f, 1.15f)
        }
        if (recipients.isEmpty()) return
        Tasks.scheduler.runLater(8L) {
            recipients.filter(Player::isOnline).forEach { player ->
                if (settings.particles) {
                    player.spawnParticle(Particle.FIREWORK, player.location.add(0.0, 2.0, 0.0), 36, 1.8, 1.2, 1.8, 0.12)
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
        stats[playerId] = (stats[playerId] ?: PlayerActivityStats()).contribute(kind, amount)
    }

    private fun recordCompletion(kind: ActivityKind, contributors: Map<UUID, Int>) {
        contributors.keys.forEach { playerId ->
            stats[playerId] = (stats[playerId] ?: PlayerActivityStats()).complete(kind)
        }
    }

    private fun allowInteraction(key: String, cooldownMillis: Long): Boolean {
        val now = clock()
        val previous = interactionCooldowns[key] ?: 0
        if (now - previous < cooldownMillis) return false
        interactionCooldowns[key] = now
        if (interactionCooldowns.size > 10_000) interactionCooldowns.entries.removeIf { now - it.value > TimeUnit.HOURS.toMillis(1) }
        return true
    }

    private fun remainingSeconds(deadline: Long, now: Long): Long = ceil((deadline - now).coerceAtLeast(0) / 1000.0).toLong()

    private fun positionKey(location: Location): String =
        "${location.world.name}:${location.blockX}:${location.blockY}:${location.blockZ}"

    private fun snapshotState(): ArcFarmsState = ArcFarmsState(
        farms = farms.associate { it.settings.id to it.state },
        lumbermills = lumbermills.associate { it.settings.id to it.state },
        mines = mines.associate { it.settings.id to it.state },
        stats = stats.toMap(),
    )

    private fun persistAsync() {
        stateRepository.saveAsync(snapshotState()).whenComplete { _, failure ->
            if (failure != null) plugin.logger.log(Level.SEVERE, "Could not persist ArcFarms state", failure)
        }
    }

    private fun persistBlocking() = stateRepository.saveBlocking(snapshotState())

    private fun hideAllBars() {
        activeBars.forEach { (key, bar) -> Bukkit.getPlayer(key.playerId)?.hideBossBar(bar) }
        activeBars.clear()
    }

    override fun close() {
        if (!started) return
        stopTasks()
        hideAllBars()
        cleanupOwnedFarmEntities()
        persistBlocking()
        started = false
    }

    companion object {
        private val TITLE_TIMES = Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(400))
    }
}
