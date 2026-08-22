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
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.EntityType
import org.bukkit.entity.Interaction
import org.bukkit.entity.Item
import org.bukkit.entity.ItemDisplay
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.MoistureChangeEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.Event
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
import ru.ruscrafting.farms.domain.FarmDroughtPlanner
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
private data class DeliveryKey(val zoneId: String, val index: Int)
private data class SupplyKey(val zoneId: String, val kind: FarmSupplyKind)
private enum class FarmSupplyKind { TOOL, SEEDS, WATER }

private val FARM_SOIL_TYPES = setOf(Material.DIRT, Material.FARMLAND)
private val FARM_WATER_DROP_TYPES = setOf(
    Material.WHEAT,
    Material.WHEAT_SEEDS,
    Material.CARROT,
    Material.POTATO,
    Material.POISONOUS_POTATO,
    Material.BEETROOT,
    Material.BEETROOT_SEEDS,
)
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
    private val pestEatenCrops = mutableMapOf<String, MutableSet<FarmPlotPosition>>()
    private val managedFarmBeds = mutableMapOf<String, MutableSet<FarmPlotPosition>>()
    private val deliveryEntities = mutableMapOf<DeliveryKey, MutableSet<UUID>>()
    private val deliveryCarriers = mutableMapOf<DeliveryKey, UUID>()
    private val carriedDisplays = mutableMapOf<DeliveryKey, UUID>()
    private val supplyEntities = mutableMapOf<SupplyKey, MutableSet<UUID>>()
    private val supplyVisualMaterials = mutableMapOf<SupplyKey, Material>()
    private val activeWaterZones = mutableSetOf<String>()
    private val temporaryFarmWater = mutableMapOf<String, MutableSet<FarmPlotPosition>>()
    private val adminEditPlayers = mutableSetOf<UUID>()
    private val farmBlockLedger = FarmBlockLedger(plugin)
    private val pestZoneKey = NamespacedKey(plugin, "farm_pest_zone")
    private val pestSequenceKey = NamespacedKey(plugin, "farm_pest_sequence")
    private val deliveryZoneKey = NamespacedKey(plugin, "farm_delivery_zone")
    private val deliverySequenceKey = NamespacedKey(plugin, "farm_delivery_sequence")
    private val deliveryIndexKey = NamespacedKey(plugin, "farm_delivery_index")
    private val supplyZoneKey = NamespacedKey(plugin, "farm_supply_zone")
    private val supplyKindKey = NamespacedKey(plugin, "farm_supply_kind")
    private val serviceItemKey = NamespacedKey(plugin, "farm_service_item")
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
        farms.forEach(::ensureFarmSupplies)
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
        clearTemporaryFarmWater("reload")
        hideAllBars()
        cleanupOwnedFarmEntities()
        settings = candidate
        rebuild(snapshot)
        reconcileFarmPatches()
        farms.forEach(::ensureFarmSupplies)
        startTasks()
        plugin.logger.info("ArcFarms reloaded with ${farms.size + lumbermills.size + mines.size} zones")
    }

    fun onBreakHigh(event: BlockBreakEvent) {
        if (event.player.uniqueId in adminEditPlayers) {
            val soil = when {
                farmAt(event.block.location) != null && event.block.type in FARM_SOIL_TYPES -> event.block
                farmAt(event.block.location) != null && event.block.getRelative(org.bukkit.block.BlockFace.DOWN).type in FARM_SOIL_TYPES ->
                    event.block.getRelative(org.bukkit.block.BlockFace.DOWN)
                else -> null
            }
            val runtime = soil?.let { farmAt(it.location) }
            if (soil != null && runtime != null) {
                val removed = farmBlockLedger.remove(soil)
                managedFarmBeds[runtime.settings.id]?.remove(soil.toFarmPlotPosition())
                event.isCancelled = true
                if (event.block == soil) soil.getRelative(org.bukkit.block.BlockFace.UP).setType(Material.AIR, false)
                event.block.setType(Material.AIR, false)
                debug.event(
                    "farm_admin_edit_break",
                    "player" to event.player.name,
                    "zone" to runtime.settings.id,
                    "block" to event.block.type,
                    "managed_record_removed" to removed,
                    "x" to event.block.x,
                    "y" to event.block.y,
                    "z" to event.block.z,
                )
                sendActionBar(event.player, MessageKey.ADMIN_EDIT_BLOCK_REMOVED)
                return
            }
        }
        mineAt(event.block.location)?.let { runtime ->
            if (event.isCancelled) return
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
            if (event.isCancelled) return
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
            if (clicked.type == Material.FARMLAND && farmAt(clicked.location) != null) event.isCancelled = true
            return
        }
        if (event.hand != EquipmentSlot.HAND || event.action != Action.RIGHT_CLICK_BLOCK) return
        val clicked = event.clickedBlock ?: return
        val player = event.player
        if (handleFarmCareInteraction(event, clicked, player)) return
        if (farmAt(clicked.location) != null && isFarmServiceItem(player.inventory.itemInMainHand)) {
            event.isCancelled = true
            return
        }
        if (event.useInteractedBlock() == Event.Result.DENY || event.useItemInHand() == Event.Result.DENY) return
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
        val fromFarm = farmAt(event.from)
        val toFarm = farmAt(destination)
        if (fromFarm != null && fromFarm !== toFarm) {
            removeFarmServiceItems(player, fromFarm.settings.id, "left_zone")
        }
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
        val keys = activeBars.keys.filter { it.playerId == player.uniqueId }
        keys.forEach { key -> activeBars.remove(key)?.let(player::hideBossBar) }
        deliveryCarriers.filterValues { it == player.uniqueId }.keys.toList().forEach { key ->
            farms.firstOrNull { it.settings.id == key.zoneId }?.let { runtime ->
                returnDelivery(runtime, key, player, "player_quit", notify = false)
            }
        }
        removeFarmServiceItems(player, reason = "player_quit")
        adminEditPlayers.remove(player.uniqueId)
    }

    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
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
        if (
            event.entity.persistentDataContainer.has(deliveryZoneKey, PersistentDataType.STRING) ||
            event.entity.persistentDataContainer.has(supplyZoneKey, PersistentDataType.STRING)
        ) {
            event.isCancelled = true
            return
        }
        val zoneId = event.entity.persistentDataContainer.get(pestZoneKey, PersistentDataType.STRING) ?: return
        val attacker = when (event) {
            is EntityDamageByEntityEvent -> when (val damager = event.damager) {
                is Player -> damager
                is Projectile -> damager.shooter as? Player
                else -> null
            }
            else -> null
        } ?: return
        val runtime = farms.firstOrNull { it.settings.id == zoneId } ?: return
        if (hasAccess(attacker, runtime.settings.permission) && runtime.region.contains(event.entity.location)) {
            event.isCancelled = false
            debug.event("farm_pest_damage_allowed", "zone" to zoneId, "player" to attacker.name, "entity" to event.entity.type)
        }
    }

    fun onMoistureChange(event: MoistureChangeEvent) {
        val runtime = farmAt(event.block.location) ?: return
        val position = event.block.toFarmPlotPosition()
        if (runtime.settings.id in activeWaterZones && position in runtime.state.droughtPlots) return
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
        if (farms.any { it.state.phase !in setOf(FarmPhase.IDLE, FarmPhase.COOLDOWN) }) {
            sendActionBar(player, MessageKey.ADMIN_EDIT_ACTIVE_SHIFT)
            return null
        }
        adminEditPlayers += player.uniqueId
        debug.event("farm_admin_edit", "player" to player.name, "enabled" to true)
        sendActionBar(player, MessageKey.ADMIN_EDIT_ENABLED)
        return true
    }

    fun onDrop(event: PlayerDropItemEvent) {
        if (isFarmServiceItem(event.itemDrop.itemStack)) event.isCancelled = true
    }

    fun onDeath(event: PlayerDeathEvent) {
        event.drops.removeIf(::isFarmServiceItem)
        removeFarmServiceItems(event.entity, reason = "player_death")
    }

    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.view.topInventory.type == InventoryType.CRAFTING) return
        val player = event.whoClicked as? Player ?: return
        val hotbar = event.hotbarButton.takeIf { it >= 0 }?.let(player.inventory::getItem)
        if (listOf(event.currentItem, event.cursor, hotbar).any(::isFarmServiceItem)) event.isCancelled = true
    }

    fun onInventoryDrag(event: InventoryDragEvent) {
        if (
            event.view.topInventory.type != InventoryType.CRAFTING && isFarmServiceItem(event.oldCursor) &&
            event.rawSlots.any { it < event.view.topInventory.size }
        ) event.isCancelled = true
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
        if (event.block.type == Material.FARMLAND && farmAt(event.block.location) != null) event.isCancelled = true
    }

    fun onBlockFade(event: BlockFadeEvent) {
        if (event.block.type == Material.FARMLAND && farmAt(event.block.location) != null) event.isCancelled = true
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
        removeFarmServiceItems(player, reason = "player_join")
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
                    configured.droughtTargetBeds(configured.preparationPatchSize),
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
            listOf(zone.supplies.tool, zone.supplies.seeds, zone.supplies.water).forEach { point ->
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
        if (adminEditPlayers.isNotEmpty()) return false
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
            farmBlockLedger.capture(soil, runtime.settings.id)
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
                    9,
                    0.28,
                    0.35,
                    0.28,
                    0.0,
                    Particle.DustOptions(Color.fromRGB(116, 199, 236), 1.25f),
                )
                player.spawnParticle(Particle.END_ROD, above.location.toCenterLocation().add(0.0, 1.1, 0.0), 3, 0.18, 0.25, 0.18, 0.01)
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
        pourFarmWater(runtime, soil, player)
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
        event.isCancelled = true
        event.isDropItems = false
        event.expToDrop = 0
        debug.event("farm_crop_committed", "player" to player.name, "zone" to runtime.settings.id, "crop" to crop, "drops" to "consumed_by_order")
        block.setType(Material.AIR, false)
        Tasks.scheduler.runLater(1L) {
            if (!block.type.isAir) return@runLater
            block.setBlockData(replantData, false)
            block.getRelative(org.bukkit.block.BlockFace.DOWN).takeIf { it.type == Material.FARMLAND }?.let { soil ->
                farmBlockLedger.captureActiveCrop(soil, runtime.settings.id)
            }
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
        val activeRules = if (incidentType == FarmIncidentType.DROUGHT) {
            val gardenBeds = runtime.state.preparationPatch.size.takeIf { it > 0 } ?: runtime.settings.preparationPatchSize
            runtime.rules.copy(droughtQuota = runtime.settings.droughtTargetBeds(gardenBeds))
        } else {
            runtime.rules
        }
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
                    players(runtime.region).forEach { removeFarmServiceItems(it, runtime.settings.id, "phase_changed", FarmSupplyKind.TOOL) }
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
                    players(runtime.region).forEach { removeFarmServiceItems(it, runtime.settings.id, "phase_changed", FarmSupplyKind.SEEDS) }
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
                    players(runtime.region).forEach { removeFarmServiceItems(it, runtime.settings.id, "phase_changed", FarmSupplyKind.WATER) }
                    restoreIncidentCrops(runtime)
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
                    restoreIncidentCrops(runtime)
                    ensureFarmDelivery(runtime)
                    persistAsync()
                }
                ShiftEvent.DELIVERY_PROGRESS -> {
                    if (actor != null) sendActionBar(actor, MessageKey.FARM_DELIVERY_REQUIRED)
                    ensureFarmDelivery(runtime)
                    persistAsync()
                }
                ShiftEvent.COMPLETED -> {
                    clearDelivery(runtime, "completed")
                    val contributors = runtime.state.contributors
                    if (restoreFarmPatchOriginal(runtime)) clearFarmPatchState(runtime)
                    recordCompletion(ActivityKind.FARM, runtime.state.contributors)
                    if (runtime.settings.completionExperience > 0) {
                        contributors.keys.mapNotNull(Bukkit::getPlayer).filter(Player::isOnline).forEach { player ->
                            player.giveExp(runtime.settings.completionExperience)
                            debug.event(
                                "farm_completion_reward",
                                "zone" to runtime.settings.id,
                                "player" to player.name,
                                "experience" to runtime.settings.completionExperience,
                            )
                        }
                    }
                    broadcast(
                        runtime.region,
                        MessageKey.FARM_COMPLETED,
                        mapOf(
                            "players" to locale.text(runtime.state.contributors.size),
                            "experience" to locale.text(runtime.settings.completionExperience),
                        ),
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
            if (runtime.state.phase == FarmPhase.COOLDOWN && runtime.state.preparationPatch.isNotEmpty()) {
                if (restoreFarmPatchOriginal(runtime)) {
                    clearFarmPatchState(runtime)
                    persistAsync()
                } else {
                    return@forEach
                }
            }
            if (runtime.state.phase == FarmPhase.IDLE) {
                players(runtime.region).firstOrNull()?.let { player ->
                    tryStartFarmShift(runtime, player, now)
                }
            }
            val result = FarmShiftEngine.tick(runtime.state, currentOrder(runtime), runtime.rules, now)
            if (result.events.isNotEmpty()) applyFarmResult(runtime, result, null)
            ensureFarmDroughtTargets(runtime)
            ensureFarmPests(runtime)
            letPestsEatCrops(runtime)
            ensureFarmDelivery(runtime)
            ensureFarmSupplies(runtime)
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
                val carrying = deliveryCarriers.any { (delivery, carrierId) ->
                    delivery.zoneId == runtime.settings.id && carrierId == player.uniqueId
                }
                val key = when (runtime.state.phase) {
                    FarmPhase.PREPARATION -> MessageKey.FARM_PREPARATION_BOSSBAR
                    FarmPhase.PLANTING -> MessageKey.FARM_PLANTING_BOSSBAR
                    FarmPhase.INCIDENT -> if ((runtime.state.incidentType ?: FarmIncidentType.PESTS) == FarmIncidentType.DROUGHT) {
                        MessageKey.FARM_DROUGHT_BOSSBAR
                    } else {
                        MessageKey.FARM_INCIDENT_BOSSBAR
                    }
                    FarmPhase.GOLDEN_HARVEST -> MessageKey.FARM_GOLDEN_BOSSBAR
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
                                runtime.state.goldenCrop ?: order.required.keys.firstOrNull()
                            },
                        ))),
                        "requirements" to farmRequirements(runtime, order),
                        "done" to locale.text(
                            when (runtime.state.phase) {
                                FarmPhase.PREPARATION -> runtime.state.preparationProgress
                                FarmPhase.PLANTING -> runtime.state.plantingProgress
                                FarmPhase.INCIDENT -> runtime.state.incidentProgress
                                FarmPhase.DELIVERY -> runtime.state.deliveredCrates.size
                                else -> done
                            },
                        ),
                        "total" to locale.text(
                            when (runtime.state.phase) {
                                FarmPhase.PREPARATION -> runtime.state.preparationRequired
                                FarmPhase.PLANTING -> runtime.state.preparationRequired
                                FarmPhase.INCIDENT -> runtime.state.incidentRequired
                                FarmPhase.DELIVERY -> runtime.settings.delivery.crates
                                else -> order.totalRequired
                            },
                        ),
                    ),
                )
                val progress = when (runtime.state.phase) {
                    FarmPhase.PREPARATION -> runtime.state.preparationProgress.toFloat() / runtime.state.preparationRequired
                    FarmPhase.PLANTING -> runtime.state.plantingProgress.toFloat() / runtime.state.preparationRequired
                    FarmPhase.INCIDENT -> runtime.state.incidentProgress.toFloat() / runtime.state.incidentRequired
                    FarmPhase.DELIVERY -> runtime.state.deliveredCrates.size.toFloat() / runtime.settings.delivery.crates
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

    private fun restoreFarmPatchOriginal(runtime: FarmRuntime): Boolean {
        var complete = true
        runtime.state.preparationPatch.forEach { position ->
            val world = Bukkit.getWorld(position.world)
            if (world == null) {
                complete = false
                return@forEach
            }
            runCatching {
                val soil = world.getBlockAt(position.x, position.y, position.z)
                farmBlockLedger.restoreOriginal(soil)
            }.onFailure { failure ->
                complete = false
                plugin.logger.log(Level.SEVERE, "Could not restore managed farm plot $position", failure)
            }
        }
        if (!complete) return false
        managedFarmBeds.remove(runtime.settings.id)
        pestEatenCrops.remove(runtime.settings.id)
        debug.event(
            "farm_patch_restored",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "plots" to runtime.state.preparationPatch.size,
        )
        return true
    }

    private fun clearFarmPatchState(runtime: FarmRuntime) {
        runtime.state = runtime.state.copy(
            preparationPatch = emptyList(),
            preparationCrop = null,
            preparationReleased = false,
            tilledPlots = emptySet(),
            plantedPlots = emptySet(),
            preparationProgress = 0,
            plantingProgress = 0,
            preparationRequired = 0,
            droughtPlots = emptySet(),
        )
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
        val droughtActive = runtime.state.phase == FarmPhase.INCIDENT &&
            (runtime.state.incidentType ?: FarmIncidentType.PESTS) == FarmIncidentType.DROUGHT
        positions.forEach { position ->
            val soil = position.block() ?: return@forEach
            if (position in runtime.state.droughtPlots) {
                setDryFarmland(soil)
                val above = soil.getRelative(org.bukkit.block.BlockFace.UP)
                if (!above.type.isAir && (above.type != Material.WATER || runtime.settings.id !in activeWaterZones)) {
                    above.setType(Material.AIR, false)
                }
                return@forEach
            }
            if (position in runtime.state.tilledPlots && soil.type != Material.FARMLAND) setWetFarmland(soil)
            if (soil.type == Material.FARMLAND) setWetFarmland(soil)
            if (droughtActive) {
                val above = soil.getRelative(org.bukkit.block.BlockFace.UP)
                if (above.type == Material.WATER && runtime.settings.id !in activeWaterZones) above.setType(Material.AIR, false)
                return@forEach
            }
            if (crop != null && position in runtime.state.plantedPlots) {
                val above = soil.getRelative(org.bukkit.block.BlockFace.UP)
                if (above.type == Material.WATER && runtime.settings.id !in activeWaterZones) above.setType(Material.AIR, false)
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

    private fun setDryFarmland(block: Block) {
        if (block.type != Material.FARMLAND) block.setType(Material.FARMLAND, false)
        val farmland = (block.blockData as? Farmland) ?: (Material.FARMLAND.createBlockData() as Farmland)
        if (farmland.moisture != 0) {
            farmland.moisture = 0
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
            if (runtime.state.droughtPlots.isNotEmpty()) {
                restoreIncidentCrops(runtime)
                runtime.state = runtime.state.copy(droughtPlots = emptySet())
                persistAsync()
            }
            return
        }
        if (runtime.state.droughtPlots.isNotEmpty()) {
            runtime.state.droughtPlots.forEach { position ->
                position.block()?.let { soil ->
                    setDryFarmland(soil)
                    soil.getRelative(org.bukkit.block.BlockFace.UP).setType(Material.AIR, false)
                }
            }
            return
        }
        val candidates = runtime.state.preparationPatch.mapNotNull { position ->
            position.block()?.takeIf { it.type == Material.FARMLAND }?.let { position }
        }
        if (candidates.isEmpty()) return
        val desired = remaining.coerceAtMost(candidates.size)
        val targets = FarmDroughtPlanner.select(
            candidates,
            candidates[random.nextInt(candidates.size)],
            desired,
            runtime.settings.droughtPatches,
        ).flatten().toSet()
        targets.forEach { position ->
            val soil = position.block() ?: return@forEach
            farmBlockLedger.captureActiveCrop(soil, runtime.settings.id)
            setDryFarmland(soil)
            soil.getRelative(org.bukkit.block.BlockFace.UP).setType(Material.AIR, false)
        }
        runtime.state = runtime.state.copy(droughtPlots = targets)
        persistAsync()
        debug.event(
            "farm_drought_patch_created",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "plots" to targets.size,
            "patches" to runtime.settings.droughtPatches.coerceAtMost(targets.size),
        )
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

    private fun horizontalDistanceSquared(first: FarmPlotPosition, second: FarmPlotPosition): Int {
        val dx = first.x - second.x
        val dz = first.z - second.z
        return dx * dx + dz * dz
    }

    private fun pourFarmWater(runtime: FarmRuntime, soil: Block, player: Player) {
        if (!activeWaterZones.add(runtime.settings.id)) return
        val source = soil.getRelative(org.bukkit.block.BlockFace.UP)
        val radius = 7
        val affectedPlots = runtime.state.preparationPatch.filterTo(linkedSetOf()) { position ->
            val managedSoil = position.block() ?: return@filterTo false
            managedSoil.location.distanceSquared(soil.location) <= (radius * radius).toDouble()
        }
        affectedPlots.forEach { position ->
            if (position in runtime.state.droughtPlots) return@forEach
            val managedSoil = position.block() ?: return@forEach
            farmBlockLedger.captureActiveCrop(managedSoil, runtime.settings.id)
        }
        val beforeWater = mutableSetOf<FarmPlotPosition>()
        val trackedWater = linkedSetOf<FarmPlotPosition>()
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
                            val position = block.toFarmPlotPosition()
                            trackedWater += position
                            temporaryFarmWater.getOrPut(runtime.settings.id, ::linkedSetOf) += position
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
                    affectedPlots.any { plot ->
                        plot.world == item.world.name && plot.x == item.location.blockX && plot.z == item.location.blockZ &&
                            item.location.blockY in plot.y..plot.y + 2
                    }
            }.forEach { item ->
                existingItems += item.uniqueId
                item.remove()
                removedDrops++
            }
        }
        source.setType(Material.WATER, true)
        val sourcePosition = source.toFarmPlotPosition()
        trackedWater += sourcePosition
        temporaryFarmWater.getOrPut(runtime.settings.id, ::linkedSetOf) += sourcePosition
        for (delay in 1L..33L step 2L) {
            Tasks.scheduler.runLater(delay) {
                if (runtime.settings.id in activeWaterZones) observeWaterAndDrops()
            }
        }
        if (settings.sounds) player.playSound(source.location, Sound.ITEM_BUCKET_EMPTY, 0.8f, 1.05f)
        if (settings.particles) {
            player.spawnParticle(Particle.SPLASH, source.location.toCenterLocation(), 24, 0.45, 0.25, 0.45, 0.12)
        }
        debug.event(
            "farm_water_poured",
            "player" to player.name,
            "zone" to runtime.settings.id,
            "x" to source.x,
            "y" to source.y,
            "z" to source.z,
        )
        Tasks.scheduler.runLater(35L) {
            observeWaterAndDrops()
            val reached = runtime.state.droughtPlots.filterTo(linkedSetOf()) { position ->
                val targetSoil = position.block() ?: return@filterTo false
                position == soil.toFarmPlotPosition() ||
                    targetSoil.getRelative(org.bukkit.block.BlockFace.UP).type == Material.WATER ||
                    ((targetSoil.blockData as? Farmland)?.moisture ?: 0) > 0
            }
            trackedWater.forEach { position ->
                position.block()?.takeIf { it.type == Material.WATER }?.setType(Material.AIR, false)
            }
            temporaryFarmWater.remove(runtime.settings.id)
            activeWaterZones.remove(runtime.settings.id)
            reached.forEach { it.block()?.let(::setWetFarmland) }
            val order = currentOrder(runtime)
            if (order != null && reached.isNotEmpty()) {
                var state = runtime.state
                var contribution = 0
                val events = mutableListOf<ShiftEvent>()
                reached.forEach { position ->
                    state = state.copy(droughtPlots = state.droughtPlots - position)
                    val result = FarmShiftEngine.waterDrySoil(state, order, runtime.rules, player.uniqueId, clock())
                    state = result.state
                    contribution += result.contribution
                    result.events.forEach { event -> if (event !in events) events += event }
                }
                applyFarmResult(runtime, EngineResult(state, true, contribution, events), player)
            }
            ensureFarmDroughtTargets(runtime)
            debug.event(
                "farm_water_settled",
                "player" to player.name,
                "zone" to runtime.settings.id,
                "removed_water" to trackedWater.size,
                "removed_drops" to removedDrops,
                "watered_plots" to reached.size,
                "remaining" to runtime.state.droughtPlots.size,
            )
        }
    }

    private fun restoreIncidentCrops(runtime: FarmRuntime) {
        val candidates = linkedSetOf<FarmPlotPosition>().apply {
            addAll(runtime.state.preparationPatch)
            addAll(pestEatenCrops.remove(runtime.settings.id).orEmpty())
        }
        candidates.forEach { position ->
            val soil = position.block() ?: return@forEach
            val above = soil.getRelative(org.bukkit.block.BlockFace.UP)
            if (above.type.isAir || above.type == Material.WATER) farmBlockLedger.restoreActiveCrop(soil)
        }
    }

    private fun ensureFarmDelivery(runtime: FarmRuntime) {
        if (runtime.state.phase != FarmPhase.DELIVERY) {
            if (deliveryKeys(runtime).isNotEmpty()) {
                clearDelivery(runtime, "phase_inactive")
            }
            return
        }
        val zoneId = runtime.settings.id
        val position = runtime.state.deliveryPosition ?: players(runtime.region).firstOrNull()?.location?.let { location ->
            FarmDeliveryPosition(location.world.name, location.x, location.y, location.z)
        } ?: return
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
        val location = deliveryCrateLocation(runtime, position, key.index)
        if (!runtime.region.contains(location)) {
            plugin.logger.severe("Farm delivery position left ${runtime.region.label} for ${runtime.settings.id}; crate ${key.index} was not spawned")
            return
        }
        val display = world.spawn(location.clone().add(0.0, 0.45, 0.0), ItemDisplay::class.java) { entity ->
            entity.setItemStack(ItemStack(Material.BARREL))
            entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
            entity.isGlowing = true
            entity.isPersistent = true
            entity.customName(locale.render(MessageKey.FARM_CRATE_NAME))
            entity.isCustomNameVisible = true
            markDeliveryEntity(entity, runtime, key.index)
        }
        val interaction = world.spawn(location, Interaction::class.java) { entity ->
            entity.interactionWidth = 1.35f
            entity.interactionHeight = 1.45f
            entity.isResponsive = true
            entity.isPersistent = true
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

    private fun deliveryCrateLocation(runtime: FarmRuntime, position: FarmDeliveryPosition, index: Int): Location {
        val world = requireNotNull(Bukkit.getWorld(position.world))
        val offsets = listOf(
            0.0 to 0.0,
            1.4 to 0.0,
            -1.4 to 0.0,
            0.0 to 1.4,
            0.0 to -1.4,
            1.4 to 1.4,
            -1.4 to 1.4,
            1.4 to -1.4,
        )
        val (x, z) = offsets[index]
        val candidate = Location(world, position.x + x, position.y, position.z + z)
        return candidate.takeIf(runtime.region::contains) ?: Location(world, position.x, position.y, position.z)
    }

    private fun markDeliveryEntity(entity: Entity, runtime: FarmRuntime, index: Int) {
        entity.persistentDataContainer.set(deliveryZoneKey, PersistentDataType.STRING, runtime.settings.id)
        entity.persistentDataContainer.set(deliverySequenceKey, PersistentDataType.LONG, runtime.state.sequence)
        entity.persistentDataContainer.set(deliveryIndexKey, PersistentDataType.INTEGER, index)
    }

    private fun pickupDelivery(runtime: FarmRuntime, key: DeliveryKey, player: Player) {
        removeDeliveryEntities(key, "picked_up")
        deliveryCarriers[key] = player.uniqueId
        val display = player.world.spawn(carriedDisplayLocation(player), ItemDisplay::class.java) { entity ->
            entity.setItemStack(ItemStack(Material.BARREL))
            entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
            entity.teleportDuration = 3
            entity.isGlowing = true
            entity.isPersistent = true
            markDeliveryEntity(entity, runtime, key.index)
        }
        carriedDisplays[key] = display.uniqueId
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
        if (destination.world.name != delivery.world) return
        val target = Location(destination.world, delivery.x, delivery.y, delivery.z)
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

    private fun carriedDisplayLocation(player: Player): Location {
        val direction = player.location.direction.setY(0)
        if (direction.lengthSquared() > 0.001) direction.normalize().multiply(-0.65)
        return player.location.clone().add(direction).add(0.0, 1.0, 0.0)
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

    private fun letPestsEatCrops(runtime: FarmRuntime) {
        activePests(runtime).forEach { pest ->
            if (!allowInteraction("farm-pest-eat:${pest.uniqueId}", 1_500)) return@forEach
            val target = runtime.state.preparationPatch.asSequence()
                .mapNotNull(FarmPlotPosition::block)
                .map { soil -> soil.getRelative(org.bukkit.block.BlockFace.UP) }
                .filter { crop -> crop.type.name in runtime.settings.crops }
                .minByOrNull { crop -> crop.location.distanceSquared(pest.location) }
                ?.takeIf { crop -> crop.location.distanceSquared(pest.location) <= 9.0 }
                ?: return@forEach
            val soil = target.getRelative(org.bukkit.block.BlockFace.DOWN)
            farmBlockLedger.captureActiveCrop(soil, runtime.settings.id)
            pestEatenCrops.getOrPut(runtime.settings.id) { mutableSetOf() } += soil.toFarmPlotPosition()
            val cropData = target.blockData
            target.setType(Material.AIR, false)
            if (settings.particles) {
                runtime.region.world.spawnParticle(
                    Particle.BLOCK,
                    target.location.toCenterLocation().add(0.0, 0.35, 0.0),
                    14,
                    0.3,
                    0.3,
                    0.3,
                    0.05,
                    cropData,
                )
            }
            if (settings.sounds) {
                runtime.region.world.playSound(target.location, Sound.ENTITY_SILVERFISH_AMBIENT, 0.7f, 0.75f)
            }
            debug.event(
                "farm_pest_ate_crop",
                "zone" to runtime.settings.id,
                "sequence" to runtime.state.sequence,
                "pest" to pest.uniqueId,
                "crop" to cropData.material,
                "x" to target.x,
                "y" to target.y,
                "z" to target.z,
            )
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
        entity.isGlowing = true
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

    private fun ensureFarmSupplies(runtime: FarmRuntime) {
        val points = mapOf(
            FarmSupplyKind.TOOL to runtime.settings.supplies.tool,
            FarmSupplyKind.SEEDS to runtime.settings.supplies.seeds,
            FarmSupplyKind.WATER to runtime.settings.supplies.water,
        )
        points.forEach { (kind, point) ->
            val key = SupplyKey(runtime.settings.id, kind)
            val visual = supplyMaterial(runtime, kind)
            val active = supplyEntities[key].orEmpty().mapNotNull(Bukkit::getEntity).filter { entity ->
                entity.isValid &&
                    entity.persistentDataContainer.get(supplyZoneKey, PersistentDataType.STRING) == runtime.settings.id &&
                    entity.persistentDataContainer.get(supplyKindKey, PersistentDataType.STRING) == kind.name
            }
            if (active.size >= 3 && supplyVisualMaterials[key] == visual) {
                supplyEntities[key] = active.mapTo(mutableSetOf(), Entity::getUniqueId)
                return@forEach
            }
            removeSupplyEntities(key, "refresh")
            val world = Bukkit.getWorld(point.world) ?: return@forEach
            val location = Location(world, point.x, point.y, point.z)
            if (!runtime.region.contains(location)) return@forEach
            val barrel = world.spawn(location.clone().add(0.0, 0.45, 0.0), ItemDisplay::class.java) { entity ->
                entity.setItemStack(ItemStack(Material.BARREL))
                entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
                entity.isGlowing = true
                entity.isPersistent = true
                markSupplyEntity(entity, runtime, kind)
            }
            val item = world.spawn(location.clone().add(0.0, 1.25, 0.0), ItemDisplay::class.java) { entity ->
                entity.setItemStack(ItemStack(visual))
                entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
                entity.isGlowing = true
                entity.isPersistent = true
                entity.customName(MaterialRules.itemComponent(visual))
                entity.isCustomNameVisible = true
                markSupplyEntity(entity, runtime, kind)
            }
            val interaction = world.spawn(location, Interaction::class.java) { entity ->
                entity.interactionWidth = 1.35f
                entity.interactionHeight = 1.7f
                entity.isResponsive = true
                entity.isPersistent = true
                markSupplyEntity(entity, runtime, kind)
            }
            supplyEntities[key] = mutableSetOf(barrel.uniqueId, item.uniqueId, interaction.uniqueId)
            supplyVisualMaterials[key] = visual
            debug.event("farm_supply_spawned", "zone" to runtime.settings.id, "kind" to kind, "material" to visual)
        }
    }

    private fun supplyMaterial(runtime: FarmRuntime, kind: FarmSupplyKind): Material = when (kind) {
        FarmSupplyKind.TOOL -> MaterialRules.material(runtime.settings.supplies.toolMaterial)
        FarmSupplyKind.WATER -> Material.WATER_BUCKET
        FarmSupplyKind.SEEDS -> runtime.state.preparationCrop
            ?.let(MaterialRules::material)
            ?.let(MaterialRules::seedForCrop)
            ?: Material.WHEAT_SEEDS
    }

    private fun markSupplyEntity(entity: Entity, runtime: FarmRuntime, kind: FarmSupplyKind) {
        entity.persistentDataContainer.set(supplyZoneKey, PersistentDataType.STRING, runtime.settings.id)
        entity.persistentDataContainer.set(supplyKindKey, PersistentDataType.STRING, kind.name)
    }

    private fun giveFarmSupply(runtime: FarmRuntime, kind: FarmSupplyKind, player: Player) {
        val allowed = when (kind) {
            FarmSupplyKind.TOOL -> runtime.state.phase == FarmPhase.PREPARATION
            FarmSupplyKind.SEEDS -> runtime.state.phase == FarmPhase.PLANTING
            FarmSupplyKind.WATER -> runtime.state.phase == FarmPhase.INCIDENT &&
                runtime.state.incidentType == FarmIncidentType.DROUGHT
        }
        if (!allowed) return
        removeFarmServiceItems(player, runtime.settings.id, "replace_supply", kind)
        val material = supplyMaterial(runtime, kind)
        val amount = if (kind == FarmSupplyKind.SEEDS) runtime.settings.supplies.seedAmount else 1
        val item = ItemStack(material, amount)
        val meta = item.itemMeta
        meta.persistentDataContainer.set(serviceItemKey, PersistentDataType.STRING, "${runtime.settings.id}:${kind.name}")
        if (kind == FarmSupplyKind.TOOL) meta.isUnbreakable = true
        item.itemMeta = meta
        if (player.inventory.firstEmpty() < 0) return
        player.inventory.addItem(item)
        if (settings.sounds) player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 0.7f, 1.2f)
        debug.event("farm_supply_given", "zone" to runtime.settings.id, "kind" to kind, "player" to player.name, "material" to material)
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
        if (removed > 0) debug.event("farm_supply_removed", "player" to player.name, "zone" to zoneId, "kind" to kind, "count" to removed, "reason" to reason)
    }

    private fun removeSupplyEntities(key: SupplyKey, reason: String) {
        val ids = supplyEntities.remove(key).orEmpty()
        ids.forEach { id -> Bukkit.getEntity(id)?.remove() }
        supplyVisualMaterials.remove(key)
        if (ids.isNotEmpty()) debug.event("farm_supply_removed", "zone" to key.zoneId, "kind" to key.kind, "count" to ids.size, "reason" to reason)
    }

    private fun cleanupOwnedFarmEntities() {
        val worlds = farms.map { it.region.world }.distinct()
        var removed = 0
        worlds.flatMap { it.entities }.forEach { entity ->
            if (
                entity.persistentDataContainer.has(pestZoneKey, PersistentDataType.STRING) ||
                entity.persistentDataContainer.has(deliveryZoneKey, PersistentDataType.STRING) ||
                entity.persistentDataContainer.has(supplyZoneKey, PersistentDataType.STRING)
            ) {
                entity.remove()
                removed++
            }
        }
        pestEntities.clear()
        managedFarmBeds.clear()
        deliveryEntities.clear()
        deliveryCarriers.clear()
        carriedDisplays.clear()
        supplyEntities.clear()
        supplyVisualMaterials.clear()
        activeWaterZones.clear()
        Bukkit.getOnlinePlayers().forEach { removeFarmServiceItems(it, reason = "service_cleanup") }
        if (removed > 0) debug.event("farm_entities_cleanup", "count" to removed)
    }

    private fun clearTemporaryFarmWater(reason: String) {
        val positions = temporaryFarmWater.values.flatten().distinct()
        positions.forEach { position ->
            position.block()?.takeIf { it.type == Material.WATER }?.setType(Material.AIR, false)
        }
        if (positions.isNotEmpty()) debug.event("farm_water_removed", "count" to positions.size, "reason" to reason)
        temporaryFarmWater.clear()
        activeWaterZones.clear()
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
        farms.filter {
            it.state.phase in setOf(
                FarmPhase.PREPARATION,
                FarmPhase.PLANTING,
                FarmPhase.HARVESTING,
                FarmPhase.INCIDENT,
                FarmPhase.GOLDEN_HARVEST,
                FarmPhase.DELIVERY,
            )
        }.forEach(::emitFarmActionBeacons)
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
                        player.spawnParticle(
                            Particle.DUST,
                            location.toCenterLocation().add(0.0, 1.3, 0.0),
                            3,
                            0.22,
                            0.25,
                            0.22,
                            0.0,
                            Particle.DustOptions(Color.fromRGB(180, 142, 255), 1.1f),
                        )
                        player.spawnParticle(Particle.END_ROD, location.toCenterLocation().add(0.0, 1.55, 0.0), 1)
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
            val targets = runtime.state.droughtPlots.mapNotNull(FarmPlotPosition::location)
            players(runtime.region).forEach { player ->
                targets.forEach { location ->
                    player.spawnParticle(Particle.SMOKE, location.toCenterLocation().add(0.0, 0.55, 0.0), 4, 0.25, 0.15, 0.25, 0.015)
                    player.spawnParticle(Particle.DRIPPING_WATER, location.toCenterLocation().add(0.0, 1.0, 0.0), 2, 0.2, 0.1, 0.2, 0.0)
                }
            }
        }
        farms.filter { it.state.phase == FarmPhase.DELIVERY }.forEach { runtime ->
            val delivery = runtime.settings.delivery
            val world = Bukkit.getWorld(delivery.world) ?: return@forEach
            val target = Location(world, delivery.x, delivery.y, delivery.z)
            deliveryCarriers.filterKeys { it.zoneId == runtime.settings.id }.values.distinct().forEach carrier@{ carrierId ->
                val player = Bukkit.getPlayer(carrierId)?.takeIf(Player::isOnline) ?: return@carrier
                if (player.world != world) return@carrier
                player.spawnParticle(Particle.END_ROD, target.clone().add(0.0, 0.8, 0.0), 5, 0.45, 0.6, 0.45, 0.015)
                player.spawnParticle(Particle.HAPPY_VILLAGER, target.clone().add(0.0, 1.2, 0.0), 2, 0.3, 0.25, 0.3, 0.0)
            }
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

    private fun emitFarmActionBeacons(runtime: FarmRuntime) {
        val viewers = players(runtime.region)
        if (viewers.isEmpty()) return
        if (runtime.state.phase == FarmPhase.DELIVERY) {
            val source = runtime.state.deliveryPosition?.let { position ->
                Bukkit.getWorld(position.world)?.let { world -> Location(world, position.x, position.y, position.z) }
            }
            val delivery = runtime.settings.delivery
            val receiving = Bukkit.getWorld(delivery.world)?.let { world ->
                Location(world, delivery.x, delivery.y, delivery.z)
            }
            viewers.forEach { player ->
                if (deliveryCarriers.none { (key, carrier) -> key.zoneId == runtime.settings.id && carrier == player.uniqueId }) {
                    source?.let { emitParticleColumn(player, it, Color.fromRGB(255, 209, 102)) }
                }
                receiving?.let { emitParticleColumn(player, it, Color.fromRGB(180, 142, 255)) }
            }
            return
        }
        val plots = if (runtime.state.incidentType == FarmIncidentType.DROUGHT && runtime.state.droughtPlots.isNotEmpty()) {
            runtime.state.droughtPlots
        } else {
            runtime.state.preparationPatch.toSet()
        }
        if (plots.isEmpty()) return
        val areas = if (runtime.state.incidentType == FarmIncidentType.DROUGHT) {
            clusterFarmPlots(plots, runtime.settings.droughtPatches)
        } else {
            listOf(plots)
        }
        val markers = areas.flatMap(::farmAreaMarkers).distinct()
        val color = when (runtime.state.phase) {
            FarmPhase.PREPARATION -> Color.fromRGB(255, 184, 107)
            FarmPhase.PLANTING -> Color.fromRGB(180, 142, 255)
            FarmPhase.INCIDENT -> if (runtime.state.incidentType == FarmIncidentType.DROUGHT) {
                Color.fromRGB(116, 199, 236)
            } else {
                Color.fromRGB(255, 107, 107)
            }
            FarmPhase.GOLDEN_HARVEST -> Color.fromRGB(255, 209, 102)
            else -> Color.fromRGB(168, 230, 163)
        }
        viewers.forEach { player ->
            markers.mapNotNull(FarmPlotPosition::location).forEach { location ->
                emitParticleColumn(player, location.toCenterLocation().add(0.0, 0.8, 0.0), color)
            }
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

    private fun farmAreaMarkers(plots: Set<FarmPlotPosition>): List<FarmPlotPosition> {
        val minX = plots.minOf(FarmPlotPosition::x)
        val maxX = plots.maxOf(FarmPlotPosition::x)
        val minZ = plots.minOf(FarmPlotPosition::z)
        val maxZ = plots.maxOf(FarmPlotPosition::z)
        return listOf(
            minX to minZ,
            minX to maxZ,
            maxX to minZ,
            maxX to maxZ,
            (minX + maxX) / 2 to (minZ + maxZ) / 2,
        ).mapNotNull { (x, z) ->
            plots.minByOrNull { plot ->
                val dx = plot.x - x
                val dz = plot.z - z
                dx * dx + dz * dz
            }
        }.distinct()
    }

    private fun emitParticleColumn(player: Player, base: Location, color: Color) {
        val dust = Particle.DustOptions(color, 1.35f)
        var height = 0.0
        while (height <= 12.0) {
            val point = base.clone().add(0.0, height, 0.0)
            player.spawnParticle(Particle.DUST, point, 1, 0.04, 0.04, 0.04, 0.0, dust)
            if (height.toInt() % 3 == 0) player.spawnParticle(Particle.END_ROD, point, 1, 0.03, 0.12, 0.03, 0.0)
            height += 1.5
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
        clearTemporaryFarmWater("plugin_close")
        hideAllBars()
        cleanupOwnedFarmEntities()
        persistBlocking()
        started = false
    }

    companion object {
        private val TITLE_TIMES = Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(400))
    }
}
