package ru.ruscrafting.farms.paper.farm.incident.drought

import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.data.type.Farmland
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.MoistureChangeEvent
import org.bukkit.event.player.PlayerInteractEvent
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.FarmIncidentPlanner
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.domain.FarmWaterFlowTracker
import ru.ruscrafting.farms.domain.FarmWaterObservationPlan
import ru.ruscrafting.farms.domain.FarmWaterPlanner
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import ru.ruscrafting.farms.paper.farm.FarmIncidentBedProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.placement.FarmSurfacePolicy
import ru.ruscrafting.farms.paper.toFarmPlotPosition
import java.util.UUID

internal data class FarmWaterFlowStats(val activeFlows: Int, val trackedBlocks: Int)

private data class DroughtGrowthRuntime(var startedAt: Long, var spawned: Int)

/** Owns the complete drought lifecycle: growth, temporary water, interactions and flow cleanup. */
internal class FarmDroughtIncident(
    private val settings: () -> ArcFarmsConfig,
    private val debug: ArcFarmsDebug,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val state: WorksiteStatePort,
    private val tasks: WorksiteTaskPort,
    private val blockLedger: FarmBlockLedger,
    private val blockRegistry: FarmBlockRegistry,
    private val beds: FarmIncidentBedProvider,
    private val transitions: FarmTransitionSink,
    private val clock: () -> Long,
) {
    private val flows = mutableMapOf<String, FarmWaterFlowTracker>()
    private val growth = mutableMapOf<String, DroughtGrowthRuntime>()
    private var nextFlowId = 1L

    fun isActive(runtime: FarmRuntime): Boolean = runtime.state.phase == FarmPhase.INCIDENT &&
        (runtime.state.incidentType ?: FarmIncidentType.PESTS) == FarmIncidentType.DROUGHT

    fun ownsInteraction(runtime: FarmRuntime, material: Material): Boolean = isActive(runtime) && material == Material.WATER_BUCKET

    fun flowStats(zoneId: String): FarmWaterFlowStats = flows[zoneId]?.let {
        FarmWaterFlowStats(it.activeFlowCount(), it.trackedBlockCount())
    } ?: FarmWaterFlowStats(0, 0)

    fun hasActiveWater(zoneId: String): Boolean = flows[zoneId]?.isEmpty() == false

    fun resetGrowth(zoneId: String) {
        growth.remove(zoneId)
    }

    fun ensure(runtime: FarmRuntime) {
        if (!isActive(runtime)) {
            growth.remove(runtime.settings.id)
            return
        }
        val remaining = (runtime.state.incidentRequired - runtime.state.incidentProgress).coerceAtLeast(0)
        if (remaining == 0) return
        val previousPlots = runtime.state.droughtPlots
        val validPlots = previousPlots.filterTo(linkedSetOf()) { position ->
            positionBlock(position)?.let(FarmSurfacePolicy::isOutdoorBed) == true
        }
        if (validPlots != previousPlots) {
            runtime.state = runtime.state.copy(droughtPlots = validPlots)
            state.persistAsync()
            debug.event(
                "farm_drought_covered_plots_removed",
                "zone" to runtime.settings.id,
                "removed" to previousPlots.size - validPlots.size,
            )
        }
        validPlots.forEach { position ->
            positionBlock(position)?.let { soil ->
                dry(soil)
                soil.getRelative(org.bukkit.block.BlockFace.UP).setType(Material.AIR, false)
            }
        }
        val now = clock()
        val runtimeGrowth = growth.getOrPut(runtime.settings.id) {
            DroughtGrowthRuntime(now, runtime.state.incidentProgress + runtime.state.droughtPlots.size)
        }
        runtimeGrowth.spawned = maxOf(runtimeGrowth.spawned, runtime.state.incidentProgress + runtime.state.droughtPlots.size)
        val spawnLimit = FarmIncidentPlanner.droughtSpawnLimit(
            required = runtime.state.incidentRequired,
            initial = runtime.settings.droughtInitialBeds.coerceAtMost(runtime.state.incidentRequired),
            growthStep = runtime.settings.droughtGrowthBeds,
            growthIntervalMillis = runtime.settings.droughtGrowthSeconds * 1_000L,
            startedAt = runtimeGrowth.startedAt,
            now = now,
        )
        val requested = (spawnLimit - runtimeGrowth.spawned).coerceAtLeast(0).coerceAtMost(remaining)
        if (requested == 0) return
        val existing = validPlots
        val candidates = beds.discover(runtime).filter { position ->
            val soil = positionBlock(position) ?: return@filter false
            soil.type in FARM_SOIL_TYPES && (position !in runtime.state.droughtDamagedPlots || position in existing)
        }
        if (candidates.isEmpty()) return
        val desiredActive = (existing.size + requested).coerceAtMost(candidates.size).coerceAtMost(MAX_ACTIVE_DROUGHT_BEDS)
        val selected = FarmIncidentPlanner.growDroughtPatches(
            candidates = candidates + existing,
            existing = existing,
            targetSize = desiredActive,
            patchCount = runtime.settings.droughtPatches,
            selectionIndex = runtime.state.placementSequence * 37L,
        )
        val targets = selected - existing
        val targetSoils = targets.mapNotNull(::positionBlock)
        blockLedger.captureActiveCrops(targetSoils, runtime.settings.id)
        targetSoils.forEach { soil ->
            dry(soil)
            soil.getRelative(org.bukkit.block.BlockFace.UP).setType(Material.AIR, false)
        }
        runtime.state = runtime.state.copy(
            droughtPlots = existing + targets,
            droughtDamagedPlots = runtime.state.droughtDamagedPlots + targets,
        )
        runtimeGrowth.spawned += targets.size
        state.persistAsync()
        debug.event(
            "farm_drought_patch_grown",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "added" to targets.size,
            "active" to runtime.state.droughtPlots.size,
            "spawned" to runtimeGrowth.spawned,
            "limit" to spawnLimit,
        )
        if (targets.size < requested && access.allowInteraction("farm-care-missing:${runtime.settings.id}", 10_000L)) {
            debug.event(
                "farm_care_targets_limited",
                "zone" to runtime.settings.id,
                "phase" to runtime.state.phase,
                "wanted" to requested,
                "added" to targets.size,
            )
        }
    }

    fun handleInteraction(event: PlayerInteractEvent, runtime: FarmRuntime): Boolean {
        if (!isActive(runtime)) return false
        val clicked = event.clickedBlock ?: return false
        ensure(runtime)
        val soil = when {
            clicked.type in FARM_SOIL_TYPES -> clicked
            clicked.getRelative(org.bukkit.block.BlockFace.DOWN).type in FARM_SOIL_TYPES ->
                clicked.getRelative(org.bukkit.block.BlockFace.DOWN)
            else -> null
        }
        val player = event.player
        val source = if (player.inventory.itemInMainHand.type == Material.WATER_BUCKET) {
            findSource(runtime, clicked, event.blockFace)
        } else null
        if (source != null) {
            event.isCancelled = true
            if (!access.hasAccess(player, runtime.settings.permission)) {
                audience.sendChat(player, MessageKey.ZONE_LOCKED)
                return true
            }
            pour(runtime, source, player)
            return true
        }
        if (player.inventory.itemInMainHand.type == Material.WATER_BUCKET) {
            event.isCancelled = true
            if (!access.hasAccess(player, runtime.settings.permission)) {
                audience.sendChat(player, MessageKey.ZONE_LOCKED)
                return true
            }
            audience.sendActionBar(player, MessageKey.FARM_DROUGHT_REQUIRED)
            debug.event(
                "farm_water_rejected",
                "player" to player.name,
                "zone" to runtime.settings.id,
                "reason" to "invalid_source",
                "block" to clicked.type,
                "x" to clicked.x,
                "y" to clicked.y,
                "z" to clicked.z,
            )
            return true
        }
        if (soil?.toFarmPlotPosition() !in runtime.state.droughtPlots) return false
        event.isCancelled = true
        if (!access.hasAccess(player, runtime.settings.permission)) {
            audience.sendChat(player, MessageKey.ZONE_LOCKED)
            return true
        }
        if (player.inventory.itemInMainHand.type != Material.WATER_BUCKET) {
            audience.sendActionBar(player, MessageKey.FARM_DROUGHT_TOOL)
            debug.event(
                "farm_care_rejected",
                "player" to player.name,
                "zone" to runtime.settings.id,
                "phase" to runtime.state.phase,
                "reason" to "wrong_tool",
            )
            return true
        }
        return true
    }

    fun onFlow(event: BlockFromToEvent, runtime: FarmRuntime) {
        val tracker = flows[runtime.settings.id] ?: return
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
                blockLedger.captureActiveCropIfPresent(soil, runtime.settings.id)
                val position = soil.toFarmPlotPosition()
                blockRegistry.addBeds(runtime.settings.id, listOf(position))
                if (position !in runtime.state.droughtDamagedPlots) {
                    runtime.state = runtime.state.copy(droughtDamagedPlots = runtime.state.droughtDamagedPlots + position)
                    if (runtime.state.droughtDamagedPlots.size % PERSIST_EVERY_DAMAGED_CROPS == 0) state.persistAsync()
                }
                target.setType(Material.AIR, false)
            }
        }
        if (target.type != Material.WATER) tracker.propagate(event.block.toFarmPlotPosition(), target.toFarmPlotPosition())
    }

    fun onMoistureChange(event: MoistureChangeEvent, runtime: FarmRuntime) {
        val position = event.block.toFarmPlotPosition()
        if (hasActiveWater(runtime.settings.id) && position in runtime.state.droughtPlots) return
        event.isCancelled = true
        if (position !in runtime.state.droughtPlots) wet(event.block)
    }

    fun clear(reason: String) {
        val positions = flows.values.flatMap(FarmWaterFlowTracker::clear).distinct()
        positions.forEach { position -> positionBlock(position)?.takeIf { it.type == Material.WATER }?.setType(Material.AIR, false) }
        if (positions.isNotEmpty()) debug.event("farm_water_removed", "count" to positions.size, "reason" to reason)
        flows.clear()
        growth.clear()
    }

    fun clearZone(zoneId: String, reason: String) {
        val positions = flows.remove(zoneId)?.clear().orEmpty()
        positions.forEach { position -> positionBlock(position)?.takeIf { it.type == Material.WATER }?.setType(Material.AIR, false) }
        growth.remove(zoneId)
        if (positions.isNotEmpty()) {
            debug.event(
                "farm_water_removed",
                "zone" to zoneId,
                "count" to positions.size,
                "reason" to reason,
            )
        }
    }

    private fun findSource(runtime: FarmRuntime, clicked: Block, face: org.bukkit.block.BlockFace): Block? = listOf(
        clicked,
        clicked.getRelative(face),
        clicked.getRelative(org.bukkit.block.BlockFace.UP),
        clicked.getRelative(face).getRelative(org.bukkit.block.BlockFace.UP),
    ).distinctBy { Triple(it.x, it.y, it.z) }.firstOrNull { source ->
        runtime.region.contains(source.location) && source.isReplaceable && source.type != Material.WATER &&
            FarmWaterPlanner.canPlace(source.toFarmPlotPosition(), runtime.state.droughtPlots, WATER_RADIUS)
    }

    private fun pour(runtime: FarmRuntime, source: Block, player: Player) {
        val flowId = nextFlowId++
        val tracker = flows.getOrPut(runtime.settings.id, ::FarmWaterFlowTracker)
        val sourcePosition = source.toFarmPlotPosition()
        if (!tracker.tryStart(flowId, sourcePosition, MAX_ACTIVE_WATER_FLOWS)) {
            audience.sendActionBar(player, MessageKey.FARM_DROUGHT_REQUIRED)
            debug.event(
                "farm_water_rejected",
                "player" to player.name,
                "zone" to runtime.settings.id,
                "reason" to "active_flow_limit",
                "active_flows" to tracker.activeFlowCount(),
            )
            return
        }
        val beforeWater = mutableSetOf<FarmPlotPosition>()
        val existingItems = source.world.getNearbyEntities(
            source.location.toCenterLocation(), WATER_RADIUS + 2.0, 4.0, WATER_RADIUS + 2.0,
        ).filterIsInstance<Item>().mapTo(mutableSetOf(), Entity::getUniqueId)
        var removedDrops = 0
        forBlocksAround(source) { block ->
            if (runtime.region.contains(block.location) && block.type == Material.WATER) beforeWater += block.toFarmPlotPosition()
        }
        fun observeWaterAndDrops() {
            forBlocksAround(source) { block ->
                if (
                    runtime.region.contains(block.location) && block.type == Material.WATER &&
                    block.toFarmPlotPosition() !in beforeWater
                ) tracker.observe(flowId, block.toFarmPlotPosition())
            }
            source.world.getNearbyEntities(
                source.location.toCenterLocation(), WATER_RADIUS + 2.0, 4.0, WATER_RADIUS + 2.0,
            ).filterIsInstance<Item>().filter { item ->
                item.uniqueId !in existingItems && item.itemStack.type in WATER_DROP_TYPES && runtime.region.contains(item.location)
            }.forEach { item ->
                existingItems += item.uniqueId
                item.remove()
                removedDrops++
            }
        }
        if (source.type.name in runtime.settings.crops && !MaterialRules.isFixedBlockCrop(source.type)) {
            val soil = source.getRelative(org.bukkit.block.BlockFace.DOWN)
            if (soil.type in FARM_SOIL_TYPES) {
                blockLedger.captureActiveCropIfPresent(soil, runtime.settings.id)
                val position = soil.toFarmPlotPosition()
                blockRegistry.addBeds(runtime.settings.id, listOf(position))
                runtime.state = runtime.state.copy(droughtDamagedPlots = runtime.state.droughtDamagedPlots + position)
                source.setType(Material.AIR, false)
            }
        }
        source.setType(Material.WATER, true)
        val droughtBefore = runtime.state.droughtPlots
        val reached = FarmWaterPlanner.reachedPlotsWithinRadius(sourcePosition, droughtBefore, WATER_RADIUS)
        reached.forEach { position -> positionBlock(position)?.let(::wet) }
        val completedPatches = FarmWaterPlanner.completedPatchCount(droughtBefore, reached)
        if (runtime.state.orderId in runtime.orders && reached.isNotEmpty()) {
            var state = runtime.state
            var contribution = 0
            val events = mutableListOf<ru.ruscrafting.farms.domain.FarmShiftEvent>()
            reached.forEach { position ->
                state = state.copy(droughtPlots = state.droughtPlots - position)
                val result = FarmShiftEngine.waterDrySoil(state, player.uniqueId)
                state = result.state
                contribution += result.contribution
                result.events.forEach { marker -> if (marker !in events) events += marker }
            }
            transitions.apply(runtime, EngineResult(state, true, contribution, events), player)
        }
        if (completedPatches > 0) {
            if (settings().sounds) audience.players(runtime.region).forEach {
                it.playSound(source.location, Sound.BLOCK_BEACON_POWER_SELECT, 0.9f, 1.15f)
            }
            debug.event(
                "farm_drought_patch_watered",
                "zone" to runtime.settings.id,
                "player" to player.name,
                "patches" to completedPatches,
            )
        }
        FarmWaterObservationPlan.delays(WATER_SETTLE_TICKS).forEach { delay ->
            tasks.runLater(delay) { if (tracker.isActive(flowId)) observeWaterAndDrops() }
        }
        if (settings().sounds) player.playSound(source.location, Sound.ITEM_BUCKET_EMPTY, 0.8f, 1.05f)
        if (settings().particles) player.spawnParticle(
            Particle.SPLASH, source.location.toCenterLocation(), 8, 0.35, 0.18, 0.35, 0.05,
        )
        debug.event(
            "farm_water_poured",
            "player" to player.name,
            "zone" to runtime.settings.id,
            "x" to source.x,
            "y" to source.y,
            "z" to source.z,
            "watered_plots" to reached.size,
        )
        tasks.runLater(WATER_SETTLE_TICKS) {
            if (!tracker.isActive(flowId)) return@runLater
            observeWaterAndDrops()
            val trackedWater = tracker.positions(flowId)
            tracker.finish(flowId).forEach { position ->
                positionBlock(position)?.takeIf { it.type == Material.WATER }?.setType(Material.AIR, false)
            }
            if (tracker.isEmpty()) flows.remove(runtime.settings.id)
            ensure(runtime)
            state.persistAsync()
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

    private inline fun forBlocksAround(source: Block, action: (Block) -> Unit) {
        for (x in source.x - WATER_RADIUS..source.x + WATER_RADIUS) {
            for (y in source.y - 1..source.y + 2) {
                for (z in source.z - WATER_RADIUS..source.z + WATER_RADIUS) action(source.world.getBlockAt(x, y, z))
            }
        }
    }

    private fun positionBlock(position: FarmPlotPosition): Block? = org.bukkit.Bukkit.getWorld(position.world)?.let { world ->
        if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) null else world.getBlockAt(position.x, position.y, position.z)
    }

    private fun wet(block: Block) {
        if (block.type != Material.FARMLAND) block.setType(Material.FARMLAND, false)
        val farmland = (block.blockData as? Farmland) ?: (Material.FARMLAND.createBlockData() as Farmland)
        if (farmland.moisture != farmland.maximumMoisture) {
            farmland.moisture = farmland.maximumMoisture
            block.setBlockData(farmland, false)
        }
    }

    private fun dry(block: Block) {
        if (block.type != Material.DIRT) block.setType(Material.DIRT, false)
    }

    private companion object {
        const val WATER_RADIUS = 5
        const val WATER_SETTLE_TICKS = 21L
        const val MAX_ACTIVE_WATER_FLOWS = 8
        const val MAX_ACTIVE_DROUGHT_BEDS = 64
        const val PERSIST_EVERY_DAMAGED_CROPS = 5
        val FARM_SOIL_TYPES = setOf(
            Material.DIRT, Material.FARMLAND, Material.GRASS_BLOCK, Material.DIRT_PATH, Material.COARSE_DIRT,
            Material.ROOTED_DIRT, Material.PODZOL, Material.MYCELIUM,
        )
        val WATER_DROP_TYPES = setOf(
            Material.WHEAT, Material.WHEAT_SEEDS, Material.CARROT, Material.POTATO, Material.POISONOUS_POTATO,
            Material.BEETROOT, Material.BEETROOT_SEEDS, Material.SWEET_BERRIES,
        )
    }
}
