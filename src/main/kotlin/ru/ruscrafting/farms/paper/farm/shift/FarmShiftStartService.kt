package ru.ruscrafting.farms.paper.farm.shift

import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmContractPlanner
import ru.ruscrafting.farms.domain.FarmOrder
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.admin.FarmWorldAdminService
import ru.ruscrafting.farms.paper.farm.care.FarmCareController
import ru.ruscrafting.farms.paper.farm.care.FarmCarePlanService
import ru.ruscrafting.farms.paper.farm.field.FarmFieldController
import java.util.random.RandomGenerator
import java.util.logging.Level

/** Selects a contract and field, then durably opens one farm shift. */
internal class FarmShiftStartService(
    private val debug: ArcFarmsDebug,
    private val port: WorksiteRuntimePort,
    private val orderCycle: FarmOrderCycleController,
    private val worldAdmin: FarmWorldAdminService,
    private val registry: FarmBlockRegistry,
    private val field: FarmFieldController,
    private val carePlans: FarmCarePlanService,
    private val care: FarmCareController,
    private val transitions: FarmTransitionSink,
    private val persistBlocking: () -> Unit,
    private val random: RandomGenerator,
) {
    fun start(runtime: FarmRuntime, player: Player, now: Long, forcedOrder: FarmOrder? = null): Boolean {
        if (runtime.state.phase != FarmPhase.IDLE) return false
        if (orderCycle.isPaused(runtime.settings.id) || registry.isReindexing(runtime.settings.id)) return false
        if (worldAdmin.anyEditing()) return false
        if (!port.allowInteraction("farm-patch-scan:${runtime.settings.id}", 5_000)) return false
        val order = forcedOrder ?: FarmContractPlanner.select(
            orders = runtime.orderList,
            rareChancePercent = runtime.settings.rareOrderChancePercent,
            rareRoll = random.nextInt(100),
            selectionIndex = runtime.state.sequence,
        )
        val seederShift = carePlans.isSeederSequence(runtime, runtime.state.sequence + 1L)
        val targetSize = if (seederShift) runtime.settings.seederPatchSize else runtime.settings.preparationPatchSize
        val patch = field.selectPatch(runtime, player.location, seederShift, runtime.state.sequence)
        if (patch.isEmpty()) {
            if (port.allowInteraction("farm-patch-empty:${runtime.settings.id}:${player.uniqueId}", 10_000)) {
                port.sendActionBar(player, MessageKey.FARM_PATCH_UNAVAILABLE)
                debug.event(
                    "farm_patch_unavailable", "zone" to runtime.settings.id, "player" to player.name,
                    "search_radius" to runtime.settings.preparationSearchRadius,
                )
            }
            return false
        }
        val preparationCrop = order.required.filterKeys {
            MaterialRules.isPlantableCrop(MaterialRules.material(it))
        }.maxWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).key
        val started = FarmShiftEngine.start(
            runtime.state, order, patch, preparationCrop, now, runtime.settings.fieldCompletionPercent,
        )
        val previous = runtime.state
        runtime.state = started.state
        try {
            persistBlocking()
        } catch (failure: Exception) {
            runtime.state = previous
            port.log(Level.SEVERE, "Could not durably start farm patch ${runtime.settings.id}", failure)
            port.sendChat(player, MessageKey.GENERIC_ERROR)
            return false
        }
        if (field.release(runtime)) {
            runtime.state = runtime.state.copy(preparationReleased = true)
            runCatching(persistBlocking).onFailure { failure ->
                port.log(Level.SEVERE, "Could not confirm released farm patch ${runtime.settings.id}; recovery remains idempotent", failure)
            }
        }
        registry.addBeds(runtime.settings.id, patch)
        if (patch.size < targetSize) debug.event(
            "farm_patch_limited", "zone" to runtime.settings.id, "wanted" to targetSize,
            "available" to patch.size, "mechanized" to seederShift,
        )
        debug.event(
            "farm_patch_selected", "zone" to runtime.settings.id, "sequence" to runtime.state.sequence,
            "order" to order.id, "rarity" to order.rarity, "plots" to patch.size,
            "min_x" to patch.minOf(FarmPlotPosition::x), "max_x" to patch.maxOf(FarmPlotPosition::x),
            "y_levels" to patch.map(FarmPlotPosition::y).distinct().sorted(),
            "min_z" to patch.minOf(FarmPlotPosition::z), "max_z" to patch.maxOf(FarmPlotPosition::z),
            "mechanized" to seederShift,
        )
        transitions.apply(runtime, started.copy(state = runtime.state), player)
        if (seederShift) care.initialize(runtime, player, FarmCareType.SEEDER)
        return true
    }
}
