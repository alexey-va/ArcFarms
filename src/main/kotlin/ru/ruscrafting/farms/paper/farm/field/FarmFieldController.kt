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
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmChannelOwnership
import ru.ruscrafting.farms.domain.FarmFieldQuota
import ru.ruscrafting.farms.domain.FarmPatchPlanner
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmSeederStage
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.FarmShiftEvent
import ru.ruscrafting.farms.domain.seederStage
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmBlockPolicy
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import ru.ruscrafting.farms.paper.block
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.location
import ru.ruscrafting.farms.paper.toFarmPlotPosition
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Level

internal val FARM_SOIL_TYPES = setOf(
    Material.DIRT, Material.FARMLAND, Material.GRASS_BLOCK, Material.DIRT_PATH, Material.COARSE_DIRT,
    Material.ROOTED_DIRT, Material.PODZOL, Material.MYCELIUM,
)

internal data class FarmPatchReleaseResult(
    val processed: Int,
    val complete: Boolean,
)

private data class FarmPatchReleaseKey(val zoneId: String, val sequence: Long)

/** Owns managed bed discovery, patch lifecycle, manual tilling/planting and bounded recovery. */
internal class FarmFieldController(
    private val settings: () -> ArcFarmsConfig,
    private val debug: ArcFarmsDebug,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val state: WorksiteStatePort,
    private val tasks: WorksiteTaskPort,
    private val ledger: FarmBlockLedger,
    private val registry: FarmBlockRegistry,
    points: FarmPointProvider,
    private val transitions: FarmTransitionSink,
    private val persistAsync: () -> CompletableFuture<Unit>,
) {
    private val patchReleaseProgress = mutableMapOf<FarmPatchReleaseKey, MutableSet<FarmPlotPosition>>()
    private val patchRestoreProgress = mutableMapOf<String, MutableSet<FarmPlotPosition>>()
    private val pendingRecoveryCommits = mutableSetOf<String>()
    private val beds = FarmBedDiscovery(debug, registry, points, tasks)
    private val recovery = FarmFieldRecovery(ledger, tasks)
    private val autoFinisher = FarmPatchAutoFinisher(ledger, debug, tasks)
    private val maintenance = FarmFieldMaintenance(ledger, registry, tasks, access, state)

    fun clearCaches() {
        recovery.clear()
        patchReleaseProgress.clear()
        patchRestoreProgress.clear()
        pendingRecoveryCommits.clear()
        autoFinisher.clear()
        maintenance.clear()
    }

    fun beforeReload() {
        recovery.clear()
        maintenance.clear()
        autoFinisher.clear()
        pendingRecoveryCommits.clear()
    }

    fun isPending(runtime: FarmRuntime): Boolean =
        recovery.isPending(runtime.settings.id) || autoFinisher.isPending(runtime.settings.id) || runtime.settings.id in pendingRecoveryCommits

    fun releaseAsync(runtime: FarmRuntime, limit: Int): CompletableFuture<FarmPatchReleaseResult> =
        recovery.process(runtime, limit, restoring = false)

    fun restoreOriginalAsync(runtime: FarmRuntime, limit: Int): CompletableFuture<Boolean> =
        recovery.process(runtime, limit, restoring = true).thenApply { it.complete }

    fun selectPatch(
        runtime: FarmRuntime,
        anchor: Location,
        mechanized: Boolean,
        selectionIndex: Long,
        additionalCandidates: Collection<FarmPlotPosition> = emptyList(),
    ): List<FarmPlotPosition> = beds.selectPatch(runtime, anchor, mechanized, selectionIndex, additionalCandidates)

    fun selectPatchAsync(
        runtime: FarmRuntime, anchor: Location, mechanized: Boolean, selectionIndex: Long,
        additionalCandidates: Collection<FarmPlotPosition> = emptyList(), isCurrent: () -> Boolean = { true },
    ): CompletableFuture<List<FarmPlotPosition>> =
        beds.selectPatchAsync(runtime, anchor, mechanized, selectionIndex, additionalCandidates, isCurrent)

    fun incidentBeds(runtime: FarmRuntime): Set<FarmPlotPosition> = beds.incident(runtime)
    fun incidentBedsAsync(runtime: FarmRuntime, isCurrent: () -> Boolean) = beds.incidentAsync(runtime, isCurrent)

    fun finishAutomaticQuota(runtime: FarmRuntime, limit: Int): Int = autoFinisher.process(runtime, limit)

    fun finishAutomaticQuotaAsync(runtime: FarmRuntime, limit: Int): CompletableFuture<Int> = autoFinisher.processAsync(runtime, limit)

    fun hasPendingAutomaticQuota(runtime: FarmRuntime, source: FarmShiftState = runtime.state): Boolean =
        autoFinisher.hasPending(runtime, source)

    /** A persisted completed seeder stays in CARE until the physical remainder is restored. */
    fun resumeSeederAfterRecovery(runtime: FarmRuntime): Boolean {
        val source = runtime.state
        if (isPending(runtime) || source.phase != FarmPhase.CARE || source.careType != FarmCareType.SEEDER ||
            source.preparationPatch.isEmpty() || !source.preparationReleased ||
            !source.plantedPlots.containsAll(source.preparationPatch) || hasPendingAutomaticQuota(runtime)
        ) return false
        transitions.apply(runtime, EngineResult(source.copy(
            phase = FarmPhase.HARVESTING, seederStage = null, careTargets = emptyList(), careGoal = null,
        ), true, events = listOf(FarmShiftEvent.CARE_RESOLVED)), null)
        return true
    }

    fun release(runtime: FarmRuntime, limit: Int): FarmPatchReleaseResult {
        require(limit >= 1) { "Farm patch release limit must be positive" }
        val key = FarmPatchReleaseKey(runtime.settings.id, runtime.state.sequence)
        patchReleaseProgress.keys.removeIf { it.zoneId == runtime.settings.id && it != key }
        val released = patchReleaseProgress.getOrPut(key, ::linkedSetOf)
        val batch = mutableListOf<Pair<FarmPlotPosition, Block>>()
        for (position in runtime.state.preparationPatch) {
            if (batch.size >= limit) break
            if (position in released) continue
            val soil = position.block() ?: continue
            batch += position to soil
        }
        val soils = batch.map { it.second }
        if (runtime.state.mechanizedPreparation) {
            // The seeder temporarily clears a mixed field. Refresh every bed snapshot from
            // the live farm immediately before that mutation so it can restore the exact
            // crop (and growth state) which occupied this coordinate.
            ledger.captureActiveCrops(soils, runtime.settings.id)
        } else {
            ledger.captureAll(soils, runtime.settings.id)
        }
        batch.forEach { (position, soil) ->
            val above = soil.getRelative(org.bukkit.block.BlockFace.UP)
            if (above.type.name in runtime.settings.crops && !MaterialRules.isFixedBlockCrop(above.type)) {
                above.setType(Material.AIR, false)
            }
            soil.setType(Material.DIRT, false)
            released += position
        }
        val processed = batch.size
        val complete = released.containsAll(runtime.state.preparationPatch)
        if (complete) patchReleaseProgress.remove(key)
        debug.event(
            if (complete) "farm_patch_released" else "farm_patch_release_progress",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "plots" to runtime.state.preparationPatch.size,
            "processed" to processed,
            "released" to released.size,
            "crop" to runtime.state.preparationCrop,
            "complete" to complete,
        )
        return FarmPatchReleaseResult(processed, complete)
    }

    fun handleInteraction(event: PlayerInteractEvent, runtime: FarmRuntime, clicked: Block, player: Player): Boolean {
        val soil = when {
            clicked.type in FARM_SOIL_TYPES -> clicked
            clicked.getRelative(org.bukkit.block.BlockFace.DOWN).type in FARM_SOIL_TYPES ->
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
                if (access.allowInteraction(
                        "farm-patch-miss:${runtime.settings.id}:${player.uniqueId}",
                        runtime.settings.inputCooldowns.patchMissMillis,
                    )) {
                    audience.sendActionBar(player, MessageKey.FARM_PREPARATION_REQUIRED)
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
                if (access.allowInteraction(
                        "farm-patch-miss:${runtime.settings.id}:${player.uniqueId}",
                        runtime.settings.inputCooldowns.patchMissMillis,
                    )) {
                    audience.sendActionBar(
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
        if (!access.hasAccess(player, runtime.settings.permission)) {
            audience.sendChat(player, MessageKey.ZONE_LOCKED)
            return true
        }
        if (!access.allowInteraction(
                "farm-care:${runtime.settings.id}:${player.uniqueId}", runtime.settings.inputCooldowns.careMillis,
            )) return true

        if (preparation) {
            if (!MaterialRules.isHoe(player.inventory.itemInMainHand)) {
                audience.sendActionBar(player, MessageKey.FARM_PREPARATION_TOOL)
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
                audience.sendActionBar(
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
                audience.sendActionBar(player, MessageKey.FARM_PLANTING_BLOCKED)
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
                    } else FarmPatchPlanner.retainCurrent(
                        currentPatch = originalPatch,
                        selectedPatch = selectPatch(
                            runtime = runtime,
                            anchor = anchor,
                            mechanized = false,
                            selectionIndex = runtime.state.sequence,
                            additionalCandidates = originalPatch,
                        ),
                        maxSize = runtime.settings.preparationPatchMaxSize,
                    )
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
            if (!runtime.state.preparationReleased) {
                return@forEach
            }
            val crop = runtime.state.preparationCrop?.let(MaterialRules::material) ?: return@forEach
            val tilled = runtime.state.tilledPlots.toMutableSet()
            val planted = runtime.state.plantedPlots.toMutableSet()
            val loadedSoils = patch.mapNotNull(FarmPlotPosition::block)
            val records = ledger.records(loadedSoils)
            patch.forEach plot@{ position ->
                val soil = position.block() ?: return@plot
                val above = soil.getRelative(org.bukkit.block.BlockFace.UP)
                val expectedCrop = if (runtime.state.mechanizedPreparation) {
                    records[position]?.activeCropData?.let { data ->
                        runCatching { org.bukkit.Bukkit.createBlockData(data).material }.getOrNull()
                    }
                } else crop
                if (expectedCrop != null && above.type == expectedCrop) {
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
        if (changed) persistAsync()
    }

    fun restoreOriginal(runtime: FarmRuntime, limit: Int = Int.MAX_VALUE): Boolean {
        require(limit >= 1) { "Farm patch restore limit must be positive" }
        val restored = patchRestoreProgress.getOrPut(runtime.settings.id, ::linkedSetOf)
        val pending = runtime.state.preparationPatch.asSequence().filterNot(restored::contains).take(limit).toList()
        val loaded = pending.mapNotNull { position -> position.block()?.let { position to it } }
        runCatching { ledger.restoreOriginals(loaded.map { it.second }, clear = false) }
            .onSuccess { restoredBlocks ->
                loaded.forEach { (position, soil) ->
                    if (soil in restoredBlocks) {
                        restored += position
                    } else if (access.allowInteraction(
                            "farm-ledger-missing:${runtime.settings.id}:$position",
                            TimeUnit.MINUTES.toMillis(5),
                        )
                    ) {
                        state.log(Level.SEVERE, "Managed farm plot $position has no recovery ledger and was retained for repair")
                    }
                }
            }
            .onFailure { failure ->
                state.log(
                    Level.SEVERE,
                    "Could not restore ${loaded.size} managed farm plots for ${runtime.settings.id}",
                    failure,
                )
            }
        val complete = restored.containsAll(runtime.state.preparationPatch)
        if (!complete) return false
        patchRestoreProgress.remove(runtime.settings.id)
        patchReleaseProgress.keys.removeIf { it.zoneId == runtime.settings.id }
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
            mechanizedPreparation = false,
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

    fun commitAfterRecovery(
        runtime: FarmRuntime,
        next: FarmShiftState,
        afterCommit: () -> Unit = {},
    ) {
        if (!pendingRecoveryCommits.add(runtime.settings.id)) return
        val previous = runtime.state
        val recoveredPatch = previous.preparationPatch
        runtime.state = next
        val token = tasks.lifecycleToken()
        runCatching(persistAsync).getOrElse { CompletableFuture.failedFuture(it) }.whenComplete { _, failure ->
            tasks.runSync(token) {
                pendingRecoveryCommits.remove(runtime.settings.id)
                if (failure != null) {
                    if (runtime.state == next) runtime.state = previous else state.persistAsync()
                    state.log(Level.SEVERE, "Could not persist recovered farm patch ${runtime.settings.id}", failure)
                    return@runSync
                }
                patchRestoreProgress.remove(runtime.settings.id)
                recovery.clearTransient(runtime, recoveredPatch, previous.sequence).whenComplete { _, ledgerFailure ->
                    tasks.runSync(token) {
                        if (ledgerFailure != null) state.log(Level.WARNING,
                            "Could not clear recovered farm ledger for ${runtime.settings.id}; records retained", ledgerFailure)
                        afterCommit()
                    }
                }
            }
        }
    }

    fun maintain(runtime: FarmRuntime, activeWater: Boolean, irrigationDryPlots: Set<FarmPlotPosition> = emptySet()) {
        if (!isPending(runtime)) maintenance.maintain(runtime, activeWater, irrigationDryPlots)
    }

    fun maintainAsync(runtime: FarmRuntime, activeWater: Boolean, irrigationDryPlots: Set<FarmPlotPosition> = emptySet()):
        CompletableFuture<Unit> = maintenance.maintainAsync(runtime, activeWater, irrigationDryPlots) { !isPending(runtime) }

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
    }
}
