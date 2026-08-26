package ru.ruscrafting.farms.paper.farm.field

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.data.Ageable
import org.bukkit.block.data.type.Farmland
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEvent
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmBedCandidatePool
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmFieldQuota
import ru.ruscrafting.farms.domain.FarmPatchPlanner
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmSeederStage
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.domain.seederStage
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmBlockPolicy
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.block
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.location
import ru.ruscrafting.farms.paper.toFarmPlotPosition
import java.util.concurrent.TimeUnit
import java.util.logging.Level

/** Owns managed bed discovery, patch lifecycle, manual tilling/planting and bounded recovery. */
internal class FarmFieldController(
    private val settings: () -> ArcFarmsConfig,
    private val debug: ArcFarmsDebug,
    private val port: WorksiteRuntimePort,
    private val ledger: FarmBlockLedger,
    private val registry: FarmBlockRegistry,
    private val points: FarmPointProvider,
    private val transitions: FarmTransitionSink,
    private val persistBlocking: () -> Unit,
) {
    private val patchRestoreProgress = mutableMapOf<String, MutableSet<FarmPlotPosition>>()

    fun clearCaches() = patchRestoreProgress.clear()

    fun selectPatch(
        runtime: FarmRuntime,
        anchor: Location,
        mechanized: Boolean,
        selectionIndex: Long,
        additionalCandidates: Collection<FarmPlotPosition> = emptyList(),
    ): List<FarmPlotPosition> {
        val candidates = discoverBeds(runtime, anchor) + additionalCandidates
        val anchorPlot = anchor.toFarmPlotPosition()
        return if (mechanized) {
            FarmPatchPlanner.selectMechanized(
                candidates = candidates,
                anchor = anchorPlot,
                targetSize = runtime.settings.seederPatchSize,
                maxSize = runtime.settings.seederPatchMaxSize,
                componentGap = runtime.settings.seederComponentGap,
                maxComponents = runtime.settings.seederComponentLimit,
                selectionIndex = selectionIndex,
            )
        } else {
            FarmPatchPlanner.select(
                candidates = candidates,
                anchor = anchorPlot,
                targetSize = runtime.settings.preparationPatchSize,
                maxSize = runtime.settings.preparationPatchMaxSize,
                selectionIndex = selectionIndex,
            )
        }
    }

    fun discoverBeds(runtime: FarmRuntime, anchor: Location): Set<FarmPlotPosition> {
        val radius = runtime.settings.preparationSearchRadius
        val discovered = linkedSetOf<FarmPlotPosition>()
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
                    discovered += block.toFarmPlotPosition()
                }
            }
        }
        registry.addBeds(runtime.settings.id, discovered)
        val indexed = registry.beds(runtime.settings.id)
        val candidates = FarmBedCandidatePool.merge(indexed, discovered) { position ->
            val soil = position.block() ?: return@merge false
            if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) return@merge false
            if (!runtime.region.contains(soil.location) || isNearFarmOperationPoint(runtime, soil.location)) return@merge false
            FarmBlockPolicy.isSelectableBed(
                soil.type,
                soil.getRelative(org.bukkit.block.BlockFace.UP).type,
                runtime.settings.crops,
            )
        }
        debug.event(
            "farm_beds_discovered",
            "zone" to runtime.settings.id,
            "indexed" to indexed.size,
            "locally_discovered" to discovered.size,
            "candidates" to candidates.size,
            "search_radius" to radius,
        )
        return candidates
    }

    fun incidentBeds(runtime: FarmRuntime): Set<FarmPlotPosition> {
        val patch = runtime.state.preparationPatch
        if (patch.isEmpty()) return emptySet()
        val indexed = registry.beds(runtime.settings.id)
        val candidates = FarmBedCandidatePool.merge(indexed, patch) { position ->
            val world = runtime.region.world
            if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) return@merge false
            val soil = position.block() ?: return@merge false
            if (!runtime.region.contains(soil.location) || soil.type !in SOIL_TYPES) return@merge false
            if (isNearFarmOperationPoint(runtime, soil.location)) return@merge false
            FarmBlockPolicy.isOpenBedContent(
                soil.getRelative(org.bukkit.block.BlockFace.UP).type,
                runtime.settings.crops,
            )
        }
        debug.event(
            "farm_incident_beds_discovered",
            "zone" to runtime.settings.id,
            "indexed" to indexed.size,
            "candidates" to candidates.size,
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
        val point = points.resolve(runtime, kind)
        point.world == location.world.name && kotlin.math.abs(point.y - location.y) <= 3.0 &&
            (point.x - location.x) * (point.x - location.x) + (point.z - location.z) * (point.z - location.z) <= 9.0
    }

    fun release(runtime: FarmRuntime): Boolean {
        var complete = true
        runtime.state.preparationPatch.forEach { position ->
            val soil = position.block()
            if (soil == null) {
                complete = false
                return@forEach
            }
            ledger.capture(soil, runtime.settings.id)
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

    fun handleInteraction(event: PlayerInteractEvent, runtime: FarmRuntime, clicked: Block, player: Player): Boolean {
        val soil = when {
            clicked.type in SOIL_TYPES -> clicked
            clicked.getRelative(org.bukkit.block.BlockFace.DOWN).type in SOIL_TYPES ->
                clicked.getRelative(org.bukkit.block.BlockFace.DOWN)
            else -> return false
        }
        val preparation = runtime.state.phase == FarmPhase.PREPARATION
        val planting = runtime.state.phase == FarmPhase.PLANTING
        if (!preparation && !planting) return false
        val target = soil.toFarmPlotPosition()
        val activeTarget = runtime.state.preparationReleased && target in runtime.state.preparationPatch
        if (!activeTarget) {
            if (preparation && MaterialRules.isHoe(player.inventory.itemInMainHand)) {
                event.isCancelled = true
                if (port.allowInteraction("farm-patch-miss:${runtime.settings.id}:${player.uniqueId}", 500)) {
                    port.sendActionBar(player, MessageKey.FARM_PREPARATION_REQUIRED)
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
                if (port.allowInteraction("farm-patch-miss:${runtime.settings.id}:${player.uniqueId}", 500)) {
                    port.sendActionBar(
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
        if (!port.hasAccess(player, runtime.settings.permission)) {
            port.sendChat(player, MessageKey.ZONE_LOCKED)
            return true
        }
        if (!port.allowInteraction("farm-care:${runtime.settings.id}:${player.uniqueId}", 100)) return true

        if (preparation) {
            if (!MaterialRules.isHoe(player.inventory.itemInMainHand)) {
                port.sendActionBar(player, MessageKey.FARM_PREPARATION_TOOL)
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
            wet(soil)
            debug.event(
                "farm_till_committed",
                "player" to player.name,
                "zone" to runtime.settings.id,
                "x" to soil.x,
                "y" to soil.y,
                "z" to soil.z,
            )
            if (settings().particles) {
                player.spawnParticle(
                    Particle.DUST,
                    soil.location.toCenterLocation().add(0.0, 0.65, 0.0),
                    3,
                    0.18,
                    0.12,
                    0.18,
                    0.0,
                    Particle.DustOptions(TILL_COLOR, 1.0f),
                )
            }
            if (settings().sounds) player.playSound(soil.location, Sound.ITEM_HOE_TILL, 0.65f, 1.15f)
            transitions.apply(runtime, result, player)
            return true
        }

        if (planting) {
            val expectedCrop = MaterialRules.material(requireNotNull(runtime.state.preparationCrop))
            val expectedSeed = requireNotNull(MaterialRules.seedForCrop(expectedCrop))
            val actualCrop = MaterialRules.cropForSeed(player.inventory.itemInMainHand)
            if (actualCrop != expectedCrop) {
                port.sendActionBar(
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
                port.sendActionBar(player, MessageKey.FARM_PLANTING_BLOCKED)
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
            wet(soil)
            above.setBlockData(expectedCrop.createBlockData(), false)
            ledger.captureActiveCrop(soil, runtime.settings.id)
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
            if (settings().particles) {
                player.spawnParticle(
                    Particle.DUST,
                    above.location.toCenterLocation().add(0.0, 0.8, 0.0),
                    4,
                    0.2,
                    0.22,
                    0.2,
                    0.0,
                    Particle.DustOptions(PLANT_COLOR, 1.1f),
                )
            }
            if (settings().sounds) player.playSound(soil.location, Sound.ITEM_CROP_PLANT, 0.65f, 1.1f)
            transitions.apply(runtime, result, player)
            return true
        }

        return false
    }

    fun reconcile(runtimes: Collection<FarmRuntime>) {
        var changed = false
        runtimes.forEach { runtime ->
            val originalPatch = runtime.state.preparationPatch
            if (originalPatch.isEmpty()) return@forEach
            val recoverableSeeder = runtime.state.phase == FarmPhase.CARE &&
                runtime.state.careType == FarmCareType.SEEDER &&
                runtime.state.seederStage == FarmSeederStage.TILLING &&
                runtime.state.tilledPlots.isEmpty() && runtime.state.plantedPlots.isEmpty()
            if (runtime.state.phase in setOf(FarmPhase.PREPARATION, FarmPhase.PLANTING) || recoverableSeeder) {
                val anchor = originalPatch.firstNotNullOfOrNull(FarmPlotPosition::location)
                if (anchor != null) {
                    val expanded = if (recoverableSeeder) {
                        FarmPatchPlanner.retainCurrent(
                            currentPatch = originalPatch,
                            selectedPatch = selectPatch(
                                runtime = runtime,
                                anchor = anchor,
                                mechanized = true,
                                selectionIndex = runtime.state.sequence,
                                additionalCandidates = originalPatch,
                            ),
                            maxSize = runtime.settings.seederPatchMaxSize,
                        )
                    } else {
                        FarmPatchPlanner.expand(
                            candidates = discoverBeds(runtime, anchor) + originalPatch,
                            currentPatch = originalPatch,
                            maxSize = runtime.settings.preparationPatchMaxSize,
                        )
                    }
                    if (expanded.size > originalPatch.size) {
                        runtime.state = runtime.state.copy(
                            preparationPatch = expanded,
                            preparationRequired = FarmFieldQuota.required(
                                expanded.size,
                                runtime.settings.fieldCompletionPercent,
                            ),
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
            registry.addBeds(runtime.settings.id, patch)
            patch.forEach { position -> position.block()?.let { ledger.capture(it, runtime.settings.id) } }
            if (!runtime.state.preparationReleased) {
                if (release(runtime)) {
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
            val required = FarmFieldQuota.required(patch.size, runtime.settings.fieldCompletionPercent)
            val nextPhase = when {
                runtime.state.phase in setOf(FarmPhase.PREPARATION, FarmPhase.PLANTING) && planted.size >= required ->
                    FarmPhase.HARVESTING
                runtime.state.phase == FarmPhase.PREPARATION && tilled.size >= required -> FarmPhase.PLANTING
                runtime.state.phase == FarmPhase.CARE && runtime.state.careType == FarmCareType.SEEDER &&
                    runtime.state.seederStage() == FarmSeederStage.PLANTING && planted.size >= required -> FarmPhase.HARVESTING
                else -> runtime.state.phase
            }
            val nextSeederStage = when {
                nextPhase == FarmPhase.HARVESTING -> null
                runtime.state.phase == FarmPhase.CARE && runtime.state.careType == FarmCareType.SEEDER &&
                    runtime.state.seederStage() == FarmSeederStage.TILLING && tilled.size >= required ->
                    FarmSeederStage.PLANTING
                else -> runtime.state.seederStage
            }
            val next = runtime.state.copy(
                phase = nextPhase,
                tilledPlots = tilled,
                plantedPlots = planted,
                preparationProgress = tilled.size,
                plantingProgress = planted.size,
                preparationRequired = required,
                careType = if (nextPhase == FarmPhase.HARVESTING && runtime.state.careType == FarmCareType.SEEDER) {
                    null
                } else runtime.state.careType,
                seederStage = nextSeederStage,
                careTargets = when {
                    nextPhase == FarmPhase.HARVESTING && runtime.state.careType == FarmCareType.SEEDER -> emptyList()
                    nextSeederStage == FarmSeederStage.PLANTING && runtime.state.seederStage() == FarmSeederStage.TILLING ->
                        runtime.state.careTargets.filter { it.role == FarmCareRole.SEEDER_HORSE }
                    else -> runtime.state.careTargets
                },
            )
            if (next != runtime.state) {
                runtime.state = next
                changed = true
            }
            maintain(runtime, false)
        }
        if (changed) persistBlocking()
    }

    fun restoreOriginal(runtime: FarmRuntime, limit: Int = Int.MAX_VALUE): Boolean {
        require(limit >= 1) { "Farm patch restore limit must be positive" }
        val restored = patchRestoreProgress.getOrPut(runtime.settings.id, ::linkedSetOf)
        runtime.state.preparationPatch.asSequence().filterNot(restored::contains).take(limit).forEach { position ->
            val soil = position.block()
            if (soil == null) {
                return@forEach
            }
            runCatching {
                if (!ledger.restoreOriginal(soil, clear = false)) {
                    if (port.allowInteraction("farm-ledger-missing:${runtime.settings.id}:$position", TimeUnit.MINUTES.toMillis(5))) {
                        port.log(Level.SEVERE, "Managed farm plot $position has no recovery ledger and was retained for repair")
                    }
                } else {
                    restored += position
                }
            }.onFailure { failure ->
                port.log(Level.SEVERE, "Could not restore managed farm plot $position", failure)
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

    fun clearState(runtime: FarmRuntime) {
        commitAfterRecovery(runtime, runtime.state.copy(
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

    fun commitAfterRecovery(runtime: FarmRuntime, next: FarmShiftState) {
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
            runCatching { ledger.removeTransient(soil) }
                .onFailure { failure ->
                    port.log(Level.WARNING, "Could not clear recovered farm ledger at $position", failure)
                }
        }
        patchRestoreProgress.remove(runtime.settings.id)
    }

    fun maintain(runtime: FarmRuntime, activeWater: Boolean) {
        if (runtime.state.preparationPatch.isNotEmpty() && !runtime.state.preparationReleased) {
            if (release(runtime)) {
                runtime.state = runtime.state.copy(preparationReleased = true)
                port.persistAsync()
            }
            return
        }
        val positions = linkedSetOf<FarmPlotPosition>().apply {
            addAll(registry.beds(runtime.settings.id))
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
                registry.removeBeds(runtime.settings.id, listOf(position))
                return@forEach
            }
            if (position in runtime.state.droughtPlots) {
                dry(soil)
                val above = soil.getRelative(org.bukkit.block.BlockFace.UP)
                if (!above.type.isAir && (above.type != Material.WATER || !activeWater)) {
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
            if (position in runtime.state.tilledPlots && soil.type != Material.FARMLAND) wet(soil)
            if (soil.type == Material.FARMLAND) wet(soil)
            if (incidentActive) {
                val above = soil.getRelative(org.bukkit.block.BlockFace.UP)
                if (above.type == Material.WATER && !activeWater) above.setType(Material.AIR, false)
                return@forEach
            }
            if (crop != null && position in runtime.state.plantedPlots) {
                val above = soil.getRelative(org.bukkit.block.BlockFace.UP)
                if (above.type == Material.WATER && !activeWater) above.setType(Material.AIR, false)
                if (above.type.isAir && !ledger.restoreActiveCrop(soil)) {
                    above.setBlockData(crop.createBlockData(), false)
                }
            }
        }
    }

    fun wet(block: Block) {
        if (block.type != Material.FARMLAND) block.setType(Material.FARMLAND, false)
        val farmland = (block.blockData as? Farmland) ?: (Material.FARMLAND.createBlockData() as Farmland)
        if (farmland.moisture != farmland.maximumMoisture) {
            farmland.moisture = farmland.maximumMoisture
            block.setBlockData(farmland, false)
        }
    }

    fun dry(block: Block) {
        if (block.type != Material.DIRT) block.setType(Material.DIRT, false)
    }


    private companion object {
        const val PATCH_PERSIST_INTERVAL = 10
        val TILL_COLOR: Color = Color.fromRGB(255, 173, 66)
        val PLANT_COLOR: Color = Color.fromRGB(199, 120, 255)
        val SOIL_TYPES = setOf(
            Material.DIRT, Material.FARMLAND, Material.GRASS_BLOCK, Material.DIRT_PATH, Material.COARSE_DIRT,
            Material.ROOTED_DIRT, Material.PODZOL, Material.MYCELIUM,
        )
    }
}
