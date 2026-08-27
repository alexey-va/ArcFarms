package ru.ruscrafting.farms.paper.farm

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockGrowEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.block.MoistureChangeEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.inventory.EquipmentSlot
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.FarmAdminEdit
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmGroundSpreadPolicy
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.FarmServiceInventoryPolicy
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.WorksiteModuleRegistry
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.WorldEditToolGuard
import ru.ruscrafting.farms.paper.farm.admin.FarmWorldAdminService
import ru.ruscrafting.farms.paper.farm.admin.FarmRouteAdminService
import ru.ruscrafting.farms.paper.farm.care.FarmCareController
import ru.ruscrafting.farms.paper.farm.delivery.FarmDeliveryController
import ru.ruscrafting.farms.paper.farm.field.FarmFieldController
import ru.ruscrafting.farms.paper.farm.harvest.FarmHarvestController
import ru.ruscrafting.farms.paper.farm.incident.drought.FarmDroughtIncident
import ru.ruscrafting.farms.paper.farm.incident.bird.FarmBirdIncident
import ru.ruscrafting.farms.paper.farm.incident.pest.FarmPestIncident
import ru.ruscrafting.farms.paper.farm.incident.special.FarmSpecialIncidentController
import ru.ruscrafting.farms.paper.farm.incident.route.FarmFoodDeliveryIncident
import ru.ruscrafting.farms.paper.farm.presentation.FarmHudController
import ru.ruscrafting.farms.paper.farm.perk.FarmPerkController
import ru.ruscrafting.farms.paper.farm.recovery.FarmFixedCropRecoveryController
import ru.ruscrafting.farms.paper.farm.scene.FarmContractSceneController
import ru.ruscrafting.farms.paper.farm.supply.FarmSupplyController
import ru.ruscrafting.farms.paper.toFarmPlotPosition
import java.util.logging.Level

/** Thin, capability-oriented Paper listener router for farm-owned interactions. */
internal class FarmEventRouter(
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
    private val port: WorksiteRuntimePort,
    private val runtimes: () -> Collection<FarmRuntime>,
    private val worldAdmin: FarmWorldAdminService,
    private val ledger: FarmBlockLedger,
    private val registry: FarmBlockRegistry,
    private val fixedCrops: FarmFixedCropRecoveryController,
    private val field: FarmFieldController,
    private val care: FarmCareController,
    private val drought: FarmDroughtIncident,
    private val pests: FarmPestIncident,
    private val birds: FarmBirdIncident,
    private val foodDelivery: FarmFoodDeliveryIncident,
    private val routeAdmin: FarmRouteAdminService,
    private val perks: FarmPerkController,
    private val special: FarmSpecialIncidentController,
    private val delivery: FarmDeliveryController,
    private val supplies: FarmSupplyController,
    private val scene: FarmContractSceneController,
    private val harvest: FarmHarvestController,
    private val hud: FarmHudController,
    private val auxiliary: WorksiteModuleRegistry,
    private val transitions: FarmTransitionSink,
    private val persistBlocking: () -> Unit,
    private val clock: () -> Long,
) {
    fun onBreakLowest(event: BlockBreakEvent) {
        if (farmAt(event.block.location) != null && WorldEditToolGuard.ownsInteraction(event.player, event.player.inventory.itemInMainHand)) {
            event.isCancelled = true
            return
        }
        if (worldAdmin.isInspecting(event.player)) {
            event.isCancelled = true
            worldAdmin.inspect(event.player, event.block)
            return
        }
        if (worldAdmin.isEditing(event.player)) return
        if (farmAt(event.block.location) != null) event.isCancelled = true
    }

    fun onBreakHigh(event: BlockBreakEvent) {
        if (farmAt(event.block.location) != null && WorldEditToolGuard.ownsInteraction(event.player, event.player.inventory.itemInMainHand)) {
            event.isCancelled = true
            return
        }
        if (worldAdmin.isInspecting(event.player)) {
            event.isCancelled = true
            return
        }
        if (worldAdmin.isEditing(event.player)) {
            handleAdminBreak(event)
            return
        }
        if (auxiliary.onBreakHigh(ActivityKind.MINE, event)) return
        farmAt(event.block.location)?.let { runtime ->
            port.traceBlockBreak(event, ActivityKind.FARM, runtime.settings.id)
            event.isCancelled = true
            if (!special.handleCropBreak(runtime, event.player, event.block)) harvest.onBreak(event, runtime)
            return
        }
        auxiliary.onBreakHigh(ActivityKind.LUMBER, event)
    }

    fun onBreakMonitor(event: BlockBreakEvent) = auxiliary.onBreakMonitor(event)

    fun onBlockDrop(event: BlockDropItemEvent) {
        farmAt(event.blockState.location)?.let { harvest.onBlockDrop(event, it) }
    }

    fun onInteractLowest(event: PlayerInteractEvent) {
        if (WorldEditToolGuard.ownsInteraction(event.player, event.item)) return
        if (worldAdmin.isInspecting(event.player)) {
            val clicked = event.clickedBlock ?: return
            if (event.action != Action.PHYSICAL && (event.hand == null || event.hand == EquipmentSlot.HAND)) {
                deny(event)
                worldAdmin.inspect(event.player, clicked)
            }
            return
        }
        if (worldAdmin.isEditing(event.player)) return
        val clicked = event.clickedBlock ?: return
        val runtime = farmAt(clicked.location) ?: return
        if (event.action == Action.PHYSICAL && clicked.type == Material.FARMLAND) {
            deny(event)
            return
        }
        if (event.hand != EquipmentSlot.HAND || event.action != Action.RIGHT_CLICK_BLOCK) return
        val item = event.player.inventory.itemInMainHand
        val owned = (clicked.type == Material.SWEET_BERRY_BUSH && clicked.type.name in runtime.settings.crops) ||
            supplies.isServiceItem(item) ||
            (runtime.state.phase == FarmPhase.PREPARATION && MaterialRules.isHoe(item)) ||
            (runtime.state.phase == FarmPhase.PLANTING && MaterialRules.cropForSeed(item) != null) ||
            drought.ownsInteraction(runtime, item.type)
        if (owned) deny(event)
    }

    fun onInteract(event: PlayerInteractEvent) {
        if (WorldEditToolGuard.ownsInteraction(event.player, event.item)) return
        if (worldAdmin.isInspecting(event.player)) {
            if (event.clickedBlock != null && event.action != Action.PHYSICAL) deny(event)
            return
        }
        if (worldAdmin.isEditing(event.player)) {
            if (event.clickedBlock?.location?.let(::farmAt) != null) {
                event.setUseInteractedBlock(Event.Result.ALLOW)
                event.setUseItemInHand(Event.Result.ALLOW)
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
        farmAt(clicked.location)?.let { runtime ->
            if (drought.handleInteraction(event, runtime) || field.handleInteraction(event, runtime, clicked, player)) return
        }
        farmAt(clicked.location)?.takeIf {
            clicked.type == Material.SWEET_BERRY_BUSH && clicked.type.name in it.settings.crops
        }?.let { runtime ->
            event.isCancelled = true
            if (!special.handleCropBreak(runtime, player, clicked)) harvest.onInteract(event, runtime, clicked)
            return
        }
        farmAt(clicked.location)?.takeIf { supplies.isServiceItem(player.inventory.itemInMainHand) }?.let { runtime ->
            event.isCancelled = true
            hud.taskHint(player, runtime, "service_item_wrong_phase")
            return
        }
        auxiliary.onInteract(event, clicked, player)
    }

    fun onBlockFromTo(event: BlockFromToEvent) {
        farmAt(event.block.location)?.let { drought.onFlow(event, it) }
    }

    fun onMove(event: PlayerMoveEvent) {
        routeAdmin.onMove(event)
        val destination = event.to
        if (event.from.world == destination.world && event.from.blockX == destination.blockX &&
            event.from.blockY == destination.blockY && event.from.blockZ == destination.blockZ
        ) return
        val player = event.player
        val from = farmAt(event.from)
        val to = farmAt(destination)
        if (from != null && from !== to) {
            supplies.removeServiceItems(player, from.settings.id, "left_zone")
            care.releasePlayer(player, "left_zone")
            hud.removePlayer(player, "left_zone")
        }
        if (to != null && from !== to) hud.enter(player, to)
        if (from !== to) hud.syncMusic(player, to, clock())
        delivery.moveCarried(runtimes(), player, destination)
        if (event !is PlayerTeleportEvent) auxiliary.onMove(event.from, destination, player)
    }

    fun onQuit(player: Player) {
        routeAdmin.release(player)
        foodDelivery.onQuit(player)
        hud.stopMusic(player, "player_quit")
        special.onQuit(player)
        hud.removePlayer(player, "player_quit")
        delivery.releasePlayer(runtimes(), player, "player_quit")
        supplies.removeServiceItems(player, reason = "player_quit")
        care.releasePlayer(player, "player_quit")
        port.resetInteractionsContaining(player.uniqueId.toString())
        worldAdmin.release(player)
    }

    fun onInteractEntityLowest(event: PlayerInteractEntityEvent) {
        if (worldAdmin.isInspecting(event.player)) {
            event.isCancelled = true
            return
        }
        if (worldAdmin.isEditing(event.player)) return
        if (care.owns(event.rightClicked) || supplies.owns(event.rightClicked) || delivery.owns(event.rightClicked) ||
            foodDelivery.owns(event.rightClicked) || perks.owns(event.rightClicked) ||
            scene.owns(event.rightClicked) || special.ownsScene(event.rightClicked)
        ) event.isCancelled = true
    }

    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (worldAdmin.isInspecting(event.player)) {
            event.isCancelled = true
            return
        }
        if (worldAdmin.isEditing(event.player) || event.hand != EquipmentSlot.HAND) return
        if (perks.interact(event)) return
        if (foodDelivery.interact(event, runtimes())) return
        if (special.ownsScene(event.rightClicked)) {
            event.isCancelled = true
            special.interactScene(event.player, event.rightClicked)
            return
        }
        if (scene.owns(event.rightClicked)) {
            event.isCancelled = true
            scene.interact(event.player, event.rightClicked)
            return
        }
        if (care.owns(event.rightClicked)) {
            event.isCancelled = true
            care.interact(event.player, event.rightClicked)
            return
        }
        supplies.interaction(event.rightClicked)?.let { identity ->
            event.isCancelled = true
            val runtime = runtimes().firstOrNull { it.settings.id == identity.zoneId } ?: return
            if (!port.hasAccess(event.player, runtime.settings.permission)) {
                port.sendChat(event.player, MessageKey.ZONE_LOCKED)
                return
            }
            if (port.allowInteraction("farm-supply:${identity.zoneId}:${identity.kind}:${event.player.uniqueId}", 500)) {
                supplies.give(runtime, identity.kind, event.player)
            }
            return
        }
        val identity = delivery.identity(event.rightClicked) ?: return
        if (!delivery.isGroundInteraction(event.rightClicked)) return
        event.isCancelled = true
        val runtime = runtimes().firstOrNull { it.settings.id == identity.zoneId } ?: return
        if (!port.hasAccess(event.player, runtime.settings.permission)) {
            port.sendChat(event.player, MessageKey.ZONE_LOCKED)
            return
        }
        if (port.allowInteraction("farm-delivery:${identity.zoneId}:${identity.index}:${event.player.uniqueId}", 500)) {
            delivery.pickup(runtime, identity, event.player)
        }
    }

    fun onVehicleEnter(event: VehicleEnterEvent) = care.onVehicleEnter(event)

    fun onEntityDamage(event: EntityDamageEvent) {
        val inspecting = (event as? EntityDamageByEntityEvent)?.playerDamager()?.takeIf(worldAdmin::isInspecting)
        if (inspecting != null) {
            event.isCancelled = true
            return
        }
        if (special.handleNightDamage(event)) return
        if (perks.owns(event.entity)) {
            event.isCancelled = true
            return
        }
        if (foodDelivery.onDamage(event)) return
        if (birds.onDamage(event, runtimes())) return
        if (scene.owns(event.entity) || special.ownsScene(event.entity)) {
            event.isCancelled = true
            return
        }
        if (care.onDamage(event)) return
        pests.nestZoneId(event.entity)?.let { zoneId ->
            event.isCancelled = true
            val attacker = (event as? EntityDamageByEntityEvent)?.playerDamager() ?: return
            val runtime = runtimes().firstOrNull { it.settings.id == zoneId } ?: return
            pests.damageNest(runtime, event.entity, attacker)
            return
        }
        if (delivery.owns(event.entity) || supplies.owns(event.entity) || scene.owns(event.entity)) {
            event.isCancelled = true
            return
        }
        pests.handlePestDamage(event, runtimes())
    }

    fun onMoistureChange(event: MoistureChangeEvent) {
        farmAt(event.block.location)?.let { drought.onMoistureChange(event, it) }
    }

    fun onDrop(event: PlayerDropItemEvent) {
        if (supplies.isServiceItem(event.itemDrop.itemStack)) event.isCancelled = true
    }

    fun onDeath(event: PlayerDeathEvent) {
        event.drops.removeIf(supplies::isServiceItem)
        supplies.removeServiceItems(event.entity, reason = "player_death")
    }

    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (perks.handleClick(event)) return
        if (special.handleInventoryClick(event)) return
        val hotbar = if (event.click == ClickType.SWAP_OFFHAND) player.inventory.itemInOffHand
        else event.hotbarButton.takeIf { it >= 0 }?.let(player.inventory::getItem)
        if (FarmServiceInventoryPolicy.cancelClick(
                playerCraftingView = event.view.topInventory.type == InventoryType.CRAFTING,
                rawSlot = event.rawSlot,
                topSize = event.view.topInventory.size,
                shiftClick = event.isShiftClick,
                currentTagged = supplies.isServiceItem(event.currentItem),
                cursorTagged = supplies.isServiceItem(event.cursor),
                hotbarTagged = supplies.isServiceItem(hotbar),
            )
        ) event.isCancelled = true
    }

    fun onInventoryDrag(event: InventoryDragEvent) {
        if (perks.handleDrag(event)) return
        if (special.handleInventoryDrag(event)) return
        if (FarmServiceInventoryPolicy.cancelDrag(supplies.isServiceItem(event.oldCursor), event.rawSlots, event.view.topInventory.size)) {
            event.isCancelled = true
        }
    }

    fun onEntityDeath(event: EntityDeathEvent) {
        if (!foodDelivery.onDeath(event) && !care.onDeath(event) && !birds.onDeath(event, runtimes())) {
            pests.onDeath(event, runtimes())
        }
    }

    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        if (pests.ownsPest(event.entity) || birds.owns(event.entity) || care.owns(event.entity) || special.ownsNightEntity(event.entity)) {
            event.isCancelled = true
            return
        }
        if (event.block.type == Material.FARMLAND && farmAt(event.block.location) != null) event.isCancelled = true
    }

    fun onBlockFade(event: BlockFadeEvent) {
        if (event.block.type == Material.FARMLAND && farmAt(event.block.location) != null) event.isCancelled = true
    }

    fun onBlockSpread(event: BlockSpreadEvent) {
        if (!FarmGroundSpreadPolicy.blocks(farmAt(event.block.location) != null, event.source.type, event.newState.type)) return
        event.isCancelled = true
        debug.event(
            "farm_soil_spread_cancelled", "source" to event.source.type, "result" to event.newState.type,
            "x" to event.block.x, "y" to event.block.y, "z" to event.block.z,
        )
    }

    fun onBlockGrow(event: BlockGrowEvent) {
        if (!MaterialRules.isFixedBlockCrop(event.newState.type)) return
        val runtime = farmAt(event.block.location) ?: return
        event.isCancelled = true
        debug.event(
            "farm_fixed_crop_natural_growth_cancelled", "zone" to runtime.settings.id, "crop" to event.newState.type,
            "x" to event.block.x, "y" to event.block.y, "z" to event.block.z,
        )
    }

    fun onBlockPlace(event: BlockPlaceEvent) {
        if (worldAdmin.isEditing(event.player)) {
            if (farmAt(event.blockPlaced.location) != null) {
                ledger.removeFixedCrop(event.blockPlaced)
                if (fixedCrops.contains(event.blockPlaced.location)) fixedCrops.retire(event.blockPlaced.location, "admin_edit_place")
                event.isCancelled = false
            }
            return
        }
        val soil = event.blockPlaced.getRelative(org.bukkit.block.BlockFace.DOWN)
        val runtime = farmAt(soil.location) ?: return
        if (soil.toFarmPlotPosition() !in runtime.state.preparationPatch) return
        event.isCancelled = true
        port.sendActionBar(event.player, MessageKey.FARM_PATCH_PROTECTED)
        debug.event(
            "farm_patch_place_rejected", "player" to event.player.name, "zone" to runtime.settings.id,
            "block" to event.blockPlaced.type, "x" to event.blockPlaced.x, "y" to event.blockPlaced.y, "z" to event.blockPlaced.z,
        )
    }

    private fun handleAdminBreak(event: BlockBreakEvent) {
        ledger.fixedCropRecord(event.block)?.let { record ->
            event.isCancelled = true
            val type = event.block.type
            val removed = ledger.removeFixedCrop(event.block)
            if (fixedCrops.contains(event.block.location)) fixedCrops.retire(event.block.location, "admin_edit_break")
            event.block.setType(Material.AIR, false)
            debug.event(
                "farm_admin_edit_fixed_crop_removed", "player" to event.player.name, "zone" to record.zoneId,
                "block" to type, "managed_record_removed" to removed,
                "x" to event.block.x, "y" to event.block.y, "z" to event.block.z,
            )
            port.sendActionBar(event.player, MessageKey.ADMIN_EDIT_BLOCK_REMOVED)
            return
        }
        val soil = when {
            farmAt(event.block.location) != null && event.block.type in FARM_SOIL_TYPES -> event.block
            farmAt(event.block.location) != null && event.block.getRelative(org.bukkit.block.BlockFace.DOWN).type in FARM_SOIL_TYPES ->
                event.block.getRelative(org.bukkit.block.BlockFace.DOWN)
            else -> null
        }
        val runtime = soil?.let { farmAt(it.location) }
        if (soil == null || runtime == null) {
            if (farmAt(event.block.location) != null) event.isCancelled = false
            return
        }
        event.isCancelled = true
        val position = soil.toFarmPlotPosition()
        val previous = runtime.state
        val removal = FarmAdminEdit.removePlot(previous, position, runtime.settings.fieldCompletionPercent)
        runtime.state = removal.state
        try {
            persistBlocking()
        } catch (failure: Exception) {
            runtime.state = previous
            port.log(Level.SEVERE, "Could not persist admin removal of managed farm plot $position", failure)
            port.sendChat(event.player, MessageKey.GENERIC_ERROR)
            return
        }
        val type = event.block.type
        val removed = ledger.remove(soil)
        registry.removeBeds(runtime.settings.id, listOf(position))
        removal.careTargetIds.forEach { care.removeTarget(runtime.settings.id, it, "admin_plot_removed") }
        if (removal.pestNestRemoved) pests.removeNestAt(runtime, position, "admin_plot_removed")
        if (event.block == soil) soil.getRelative(org.bukkit.block.BlockFace.UP).setType(Material.AIR, false)
        event.block.setType(Material.AIR, false)
        if (removal.shiftRetired) {
            care.clear(runtime, "admin_plot_removed")
            pests.clear(runtime, "admin_plot_removed")
            delivery.clear(runtime, "admin_plot_removed")
        } else if (runtime.state.phase == FarmPhase.INCIDENT &&
            (runtime.state.incidentType ?: FarmIncidentType.PESTS) == FarmIncidentType.PESTS &&
            runtime.state.pestNests.isEmpty() && runtime.state.pestAlive == 0 && currentOrder(runtime) != null
        ) {
            transitions.apply(runtime, FarmShiftEngine.finishPestIncidentIfClear(runtime.state), null)
        }
        debug.event(
            "farm_admin_edit_break", "player" to event.player.name, "zone" to runtime.settings.id,
            "block" to type, "managed_record_removed" to removed,
            "x" to event.block.x, "y" to event.block.y, "z" to event.block.z,
        )
        port.sendActionBar(event.player, MessageKey.ADMIN_EDIT_BLOCK_REMOVED)
    }

    private fun EntityDamageByEntityEvent.playerDamager(): Player? = when (val source = damager) {
        is Player -> source
        is Projectile -> source.shooter as? Player
        else -> null
    }

    private fun deny(event: PlayerInteractEvent) {
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
        event.isCancelled = true
    }

    private fun farmAt(location: org.bukkit.Location): FarmRuntime? = runtimes().firstOrNull { it.region.contains(location) }
    private fun currentOrder(runtime: FarmRuntime) = runtime.state.orderId?.let(runtime.orders::get)

    private companion object {
        val FARM_SOIL_TYPES = setOf(
            Material.DIRT, Material.FARMLAND, Material.GRASS_BLOCK, Material.DIRT_PATH,
            Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.PODZOL, Material.MYCELIUM,
        )
    }
}
