package ru.ruscrafting.farms.paper.farm.harvest

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.data.Ageable
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmIncidentPlanner
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmOrder
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPerkType
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.domain.ShiftEvent
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmShiftStarter
import ru.ruscrafting.farms.paper.farm.FarmTaskHintSink
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.incident.drought.FarmDroughtIncident
import ru.ruscrafting.farms.paper.farm.placement.FarmPlacementService
import ru.ruscrafting.farms.paper.farm.recovery.FarmFixedCropRecoveryController
import ru.ruscrafting.farms.paper.farm.recovery.FarmIncidentRecoveryController
import java.util.UUID
import kotlin.math.ceil

private data class HarvestCommit(
    val crop: Material,
    val replantData: Ageable?,
    val fixedCrop: Boolean,
    val now: Long,
)

/** Owns crop acceptance, drop suppression, replant and harvest progression. */
internal class FarmHarvestController(
    private val settings: () -> ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
    private val port: WorksiteRuntimePort,
    private val ledger: FarmBlockLedger,
    private val fixedCrops: FarmFixedCropRecoveryController,
    private val incidentRecovery: FarmIncidentRecoveryController,
    private val drought: FarmDroughtIncident,
    private val placement: FarmPlacementService,
    private val transitions: FarmTransitionSink,
    private val shiftStarter: FarmShiftStarter,
    private val taskHints: FarmTaskHintSink,
    private val runtimes: () -> Collection<FarmRuntime>,
    private val clock: () -> Long,
    private val perkActive: (UUID, FarmPerkType, Long) -> Boolean = { _, _, _ -> false },
) {
    fun onBreak(event: BlockBreakEvent, runtime: FarmRuntime) {
        event.isCancelled = true
        validate(runtime, event.player, event.block) { commit ->
            event.isDropItems = false
            event.expToDrop = 0
            if (commit.fixedCrop) {
                fixedCrops.prepareHarvest(runtime, event.player, event.block, commit.now, ::progress)
                return@validate
            }
            event.isCancelled = false
            val areaHarvest = perkActive(event.player.uniqueId, FarmPerkType.HARVEST_AREA, commit.now)
            commitBrokenCrop(runtime, event.player, event.block, commit.crop, requireNotNull(commit.replantData)) {
                if (areaHarvest) commitHarvestArea(runtime, event.player, event.block)
            }
        }
    }

    fun onInteract(event: PlayerInteractEvent, runtime: FarmRuntime, clicked: Block): Boolean {
        if (clicked.type != Material.SWEET_BERRY_BUSH || clicked.type.name !in runtime.settings.crops) return false
        event.isCancelled = true
        validate(runtime, event.player, clicked) { commit ->
            check(!commit.fixedCrop) { "Sweet berry harvest cannot use fixed crop recovery" }
            clicked.setBlockData(requireNotNull(commit.replantData), false)
            clicked.getRelative(org.bukkit.block.BlockFace.DOWN).takeIf { it.type == Material.FARMLAND }?.let { soil ->
                ledger.captureActiveCrop(soil, runtime.settings.id)
            }
            debug.event(
                "farm_crop_committed",
                "player" to event.player.name,
                "zone" to runtime.settings.id,
                "crop" to commit.crop,
                "input" to "right_click",
                "drops" to "consumed_by_order",
            )
            if (settings().sounds) {
                event.player.playSound(clicked.location, Sound.BLOCK_SWEET_BERRY_BUSH_PICK_BERRIES, 0.7f, 1.05f)
            }
            progress(runtime, event.player, commit.crop.name)
            if (perkActive(event.player.uniqueId, FarmPerkType.HARVEST_AREA, commit.now)) {
                commitHarvestArea(runtime, event.player, clicked)
            }
        }
        return true
    }

    fun onBlockDrop(event: BlockDropItemEvent, runtime: FarmRuntime) {
        if (event.blockState.type.name !in runtime.settings.crops) return
        val removed = event.items.filter { it.itemStack.type in DROP_TYPES }
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

    fun nextRequiredCrop(state: FarmShiftState, order: FarmOrder): Map.Entry<String, Int>? = order.required.entries
        .firstOrNull { (crop, required) -> (state.progress[crop] ?: 0) < required }

    fun remainingCrops(runtime: FarmRuntime, order: FarmOrder): Component = Component.join(
        JoinConfiguration.commas(true),
        order.required.entries
            .filter { (crop, required) -> (runtime.state.progress[crop] ?: 0) < required }
            .map { MaterialRules.cropComponent(MaterialRules.material(it.key)) },
    )

    private fun validate(runtime: FarmRuntime, player: Player, block: Block, commit: (HarvestCommit) -> Unit) {
        if (!port.hasAccess(player, runtime.settings.permission)) {
            port.sendChat(player, MessageKey.ZONE_LOCKED)
            return
        }
        if (runtime.state.phase != FarmPhase.INCIDENT && incidentRecovery.pending(runtime)) {
            incidentRecovery.restore(runtime, runtime.settings.restoreBlocksPerTick, drought.hasActiveWater(runtime.settings.id))
            if (incidentRecovery.pending(runtime)) {
                port.sendActionBar(player, MessageKey.FARM_FIELD_RESTORING)
                reject(runtime, player, block, "incident_recovery_pending")
                return
            }
        }
        if (runtime.state.phase in BLOCKED_PHASES) {
            taskHints.show(player, runtime, "block_break_during_${runtime.state.phase.name.lowercase()}")
            return
        }
        val crop = block.type
        if (crop.name !in runtime.settings.crops) {
            taskHints.show(player, runtime, "wrong_block_break")
            return
        }
        val fixedCrop = MaterialRules.isFixedBlockCrop(crop)
        val ageable = block.blockData as? Ageable
        if (!fixedCrop && ageable == null) {
            taskHints.show(player, runtime, "unsupported_crop_break")
            return
        }
        if (ageable != null && ageable.age < ageable.maximumAge) {
            taskHints.show(player, runtime, "immature_crop_break")
            reject(runtime, player, block, "immature")
            return
        }
        val now = clock()
        if (runtime.state.phase == FarmPhase.COOLDOWN) {
            port.sendActionBar(
                player,
                MessageKey.COOLDOWN,
                mapOf("seconds" to locale.text(remainingSeconds(runtime.state.cooldownEndsAt, now))),
            )
            reject(runtime, player, block, "cooldown")
            return
        }
        if (runtime.state.phase == FarmPhase.IDLE) {
            shiftStarter.start(runtime, player, now)
            if (runtime.state.phase != FarmPhase.IDLE) taskHints.show(player, runtime, "shift_started_by_crop_break")
            return
        }
        val order = currentOrder(runtime) ?: return
        if (crop.name !in order.required) {
            port.sendActionBar(player, MessageKey.FARM_WRONG_TARGET, mapOf("crops" to remainingCrops(runtime, order)))
            reject(runtime, player, block, "not_requested")
            return
        }
        if ((runtime.state.progress[crop.name] ?: 0) >= order.required.getValue(crop.name)) {
            val next = nextRequiredCrop(runtime.state, order)
            port.sendActionBar(
                player,
                MessageKey.FARM_CROP_ALREADY_COMPLETE,
                mapOf(
                    "crop" to MaterialRules.cropComponent(crop),
                    "next" to (next?.let { MaterialRules.cropComponent(MaterialRules.material(it.key)) } ?: Component.empty()),
                    "amount" to locale.text(next?.let { it.value - (runtime.state.progress[it.key] ?: 0) } ?: 0),
                ),
            )
            if (settings().particles) {
                player.spawnParticle(
                    Particle.DUST,
                    block.location.toCenterLocation().add(0.0, 1.0, 0.0),
                    8,
                    0.32,
                    0.4,
                    0.32,
                    0.0,
                    Particle.DustOptions(DANGER_COLOR, 1.25f),
                )
            }
            reject(runtime, player, block, "quota_complete")
            return
        }
        val replantData = ageable?.let { (it.clone() as Ageable).also { data -> data.age = 0 } }
        commit(HarvestCommit(crop, replantData, fixedCrop, now))
    }

    private fun commitBrokenCrop(
        runtime: FarmRuntime,
        player: Player,
        block: Block,
        crop: Material,
        replantData: Ageable,
        after: () -> Unit = {},
    ) {
        val existingItems = nearbyDropIds(block.location, 2.0)
        val inventoryBefore = dropInventory(player)
        debug.event("farm_crop_committed", "player" to player.name, "zone" to runtime.settings.id, "crop" to crop, "drops" to "consumed_by_order")
        val zoneId = runtime.settings.id
        val sequence = runtime.state.sequence
        port.runLater(1L) {
            val currentRuntime = runtimes().firstOrNull { it.settings.id == zoneId && it.state.sequence == sequence }
                ?: return@runLater
            if (!port.isOperational() || !block.type.isAir) return@runLater
            block.setBlockData(replantData, false)
            block.getRelative(org.bukkit.block.BlockFace.DOWN).takeIf { it.type == Material.FARMLAND }?.let { soil ->
                ledger.captureActiveCrop(soil, currentRuntime.settings.id)
            }
            removeNewDrops(block.location, 2.0, existingItems)
            removeInventoryGains(player, inventoryBefore, currentRuntime.settings.id)
            port.runLater(2L) {
                removeNewDrops(block.location, 2.0, existingItems)
                removeInventoryGains(player, inventoryBefore, currentRuntime.settings.id)
            }
            if (currentRuntime.state.phase == FarmPhase.HARVESTING) {
                progress(currentRuntime, player, crop.name)
                if (currentRuntime.state.phase == FarmPhase.HARVESTING) after()
            }
        }
    }

    private fun commitHarvestArea(runtime: FarmRuntime, player: Player, origin: Block) {
        for (dx in -1..1) for (dz in -1..1) {
            if (dx == 0 && dz == 0) continue
            val crop = origin.getRelative(dx, 0, dz)
            val soil = crop.getRelative(org.bukkit.block.BlockFace.DOWN)
            if (ledger.record(soil)?.zoneId != runtime.settings.id) continue
            validate(runtime, player, crop) { commit ->
                if (commit.fixedCrop || commit.replantData == null) return@validate
                crop.setBlockData(commit.replantData, false)
                ledger.captureActiveCrop(soil, runtime.settings.id)
                debug.event(
                    "farm_crop_committed",
                    "player" to player.name,
                    "zone" to runtime.settings.id,
                    "crop" to commit.crop,
                    "input" to "harvest_area",
                    "drops" to "consumed_by_order",
                )
                progress(runtime, player, commit.crop.name)
            }
        }
    }

    private fun progress(runtime: FarmRuntime, player: Player, crop: String) {
        val now = clock()
        if (runtime.state.phase == FarmPhase.COOLDOWN) {
            port.sendActionBar(
                player,
                MessageKey.COOLDOWN,
                mapOf("seconds" to locale.text(remainingSeconds(runtime.state.cooldownEndsAt, now))),
            )
            return
        }
        val order = currentOrder(runtime) ?: return
        val incidentType = plannedIncident(runtime, order)
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
                state = result.state.copy(deliveryPosition = placement.selectDeliveryAnchor(runtime, player.location)),
            )
        }
        transitions.apply(runtime, result, player)
        val required = order.required[crop]
        if (result.accepted && required != null && before < required && (result.state.progress[crop] ?: 0) >= required) {
            nextRequiredCrop(result.state, order)?.let { next ->
                val zoneId = runtime.settings.id
                val sequence = result.state.sequence
                val announce = {
                    if (port.isOperational() && player.isOnline && runtimes().any {
                            it.settings.id == zoneId && it.state.sequence == sequence
                        }
                    ) {
                        port.showScreenTitle(
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
                if (ShiftEvent.INCIDENT_STARTED in result.events) port.runLater(settings().titleStaySeconds * 20L + 10L, announce)
                else announce()
            }
        }
        if (!result.accepted && ShiftEvent.COMPLETED !in result.events) {
            port.sendActionBar(player, MessageKey.FARM_WRONG_TARGET, mapOf("crops" to remainingCrops(runtime, order)))
        }
    }

    fun plannedIncident(runtime: FarmRuntime, order: FarmOrder): FarmIncidentType {
        val count = runtime.rules.incidentTargetCount(runtime.state.sequence)
        val plan = FarmIncidentPlanner.sequence(order.incidentTypes, count, runtime.state.sequence)
        return plan.getOrElse(runtime.state.incidentsResolved) { plan.last() }
    }

    private fun currentOrder(runtime: FarmRuntime): FarmOrder? = runtime.state.orderId?.let(runtime.orders::get)

    private fun reject(runtime: FarmRuntime, player: Player, block: Block, reason: String) {
        debug.event("farm_crop_rejected", "player" to player.name, "zone" to runtime.settings.id, "crop" to block.type, "reason" to reason)
    }

    private fun nearbyDropIds(location: Location, radius: Double): MutableSet<UUID> = location.world
        .getNearbyEntities(location.toCenterLocation(), radius, radius, radius)
        .filterIsInstance<Item>()
        .mapTo(mutableSetOf(), Entity::getUniqueId)

    private fun removeNewDrops(location: Location, radius: Double, existing: MutableSet<UUID>) {
        location.world.getNearbyEntities(location.toCenterLocation(), radius, radius, radius)
            .filterIsInstance<Item>()
            .filter { it.uniqueId !in existing && it.itemStack.type in DROP_TYPES }
            .forEach { item ->
                existing += item.uniqueId
                item.remove()
            }
    }

    private fun dropInventory(player: Player): Map<Material, Int> = DROP_TYPES.associateWith { material ->
        player.inventory.storageContents.filterNotNull().filter { it.type == material }.sumOf(ItemStack::getAmount)
    }

    private fun removeInventoryGains(player: Player, baseline: Map<Material, Int>, zoneId: String) {
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

    private fun remainingSeconds(deadline: Long, now: Long): Long =
        ceil((deadline - now).coerceAtLeast(0) / 1_000.0).toLong()

    private companion object {
        val DANGER_COLOR: Color = Color.fromRGB(255, 95, 109)
        val BLOCKED_PHASES = setOf(
            FarmPhase.PREPARATION,
            FarmPhase.PLANTING,
            FarmPhase.CARE,
            FarmPhase.INCIDENT,
            FarmPhase.DELIVERY,
        )
        val DROP_TYPES = setOf(
            Material.WHEAT,
            Material.WHEAT_SEEDS,
            Material.CARROT,
            Material.POTATO,
            Material.POISONOUS_POTATO,
            Material.BEETROOT,
            Material.BEETROOT_SEEDS,
            Material.SWEET_BERRIES,
        )
    }
}
