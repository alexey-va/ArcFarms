package ru.ruscrafting.farms.paper.farm.shift

import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.config.ShiftStartPersistenceSettings
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.FarmContractPlanner
import ru.ruscrafting.farms.domain.FarmOrder
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.domain.FarmShiftEvent
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.ArcProductTelemetryBridge
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.admin.FarmWorldAdminService
import ru.ruscrafting.farms.paper.farm.care.FarmCarePlanService
import ru.ruscrafting.farms.paper.farm.enterprise.FarmEnterprisePort
import ru.ruscrafting.farms.paper.farm.field.FarmFieldController
import java.util.random.RandomGenerator
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/** Selects a contract and field, then durably opens one farm shift. */
internal class FarmShiftStartService(
    private val debug: ArcFarmsDebug,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val state: WorksiteStatePort,
    private val tasks: WorksiteTaskPort,
    private val orderCycle: FarmOrderCycleController,
    private val worldAdmin: FarmWorldAdminService,
    private val registry: FarmBlockRegistry,
    private val field: FarmFieldController,
    private val carePlans: FarmCarePlanService,
    private val enterprise: FarmEnterprisePort,
    private val transitions: FarmTransitionSink,
    private val persistAsync: () -> CompletableFuture<Unit>,
    private val retrySettings: () -> ShiftStartPersistenceSettings,
    private val random: RandomGenerator,
) {
    private data class PendingStart(
        val runtime: FarmRuntime,
        val player: Player,
        val playerId: java.util.UUID,
        val result: EngineResult<FarmShiftState, FarmShiftEvent>,
        val order: FarmOrder,
        val patch: List<FarmPlotPosition>,
        val targetSize: Int,
        val seederShift: Boolean,
        val token: RuntimeTaskSupervisor.Token,
    )

    private val pendingStarts = mutableMapOf<String, PendingStart>()
    private val pendingPlanning = mutableMapOf<String, Any>()

    fun clearPending() {
        pendingStarts.clear()
        pendingPlanning.clear()
    }

    fun isPending(zoneId: String): Boolean = zoneId in pendingStarts || zoneId in pendingPlanning

    /** True acknowledges admission; field selection and the durable start finish asynchronously. */
    fun start(runtime: FarmRuntime, player: Player, now: Long, forcedOrder: FarmOrder? = null): Boolean {
        if (runtime.state.phase != FarmPhase.IDLE) return false
        if (isPending(runtime.settings.id)) return false
        if (orderCycle.isPaused(runtime.settings.id) || registry.isReindexing(runtime.settings.id)) return false
        if (worldAdmin.anyEditing()) return false
        if (!access.allowInteraction("farm-patch-scan:${runtime.settings.id}", 5_000)) return false
        val order = forcedOrder ?: FarmContractPlanner.select(
            orders = enterprise.orderPool(runtime.settings.id, runtime.orderList),
            rareChancePercent = runtime.settings.rareOrderChancePercent,
            rareRoll = random.nextInt(100),
            selectionIndex = runtime.state.sequence,
        )
        val seederShift = carePlans.isSeederSequence(runtime, runtime.state.sequence + 1L)
        val targetSize = if (seederShift) runtime.settings.seederPatchSize else runtime.settings.preparationPatchSize
        val source = runtime.state
        val rules = runtime.settings
        val region = runtime.region
        val world = player.world
        val request = Any()
        val token = tasks.lifecycleToken()
        pendingPlanning[rules.id] = request
        fun current() = pendingPlanning[rules.id] === request && runtime.state === source && runtime.settings === rules &&
            runtime.region === region && player.isOnline && player.world === world &&
            !orderCycle.isPaused(rules.id) && !registry.isReindexing(rules.id) && !worldAdmin.anyEditing()
        runCatching {
            field.selectPatchAsync(runtime, player.location, seederShift, source.sequence, isCurrent = ::current)
        }.getOrElse { CompletableFuture.failedFuture(it) }.whenComplete { patch, failure ->
            tasks.runSync(token) {
                val accepted = current()
                if (pendingPlanning[rules.id] !== request) return@runSync
                pendingPlanning.remove(rules.id, request)
                if (!accepted) return@runSync
                if (failure != null) {
                    state.log(Level.WARNING, "Could not select farm patch for ${rules.id}; shift remains idle", failure)
                    audience.sendActionBar(player, MessageKey.FARM_PATCH_UNAVAILABLE)
                    return@runSync
                }
                stage(runtime, player, now, order, seederShift, targetSize, patch, token)
            }
        }
        return true
    }

    private fun stage(
        runtime: FarmRuntime, player: Player, now: Long, order: FarmOrder, seederShift: Boolean,
        targetSize: Int, patch: List<FarmPlotPosition>, token: RuntimeTaskSupervisor.Token,
    ) {
        if (patch.isEmpty()) {
            state.log(
                Level.WARNING,
                "Could not start farm shift: zone=${runtime.settings.id} sequence=${runtime.state.sequence + 1} " +
                    "reason=patch_unavailable player=${player.name} mechanized=$seederShift " +
                    "target=$targetSize search_radius=${runtime.settings.preparationSearchRadius} " +
                    "indexed_beds=${registry.beds(runtime.settings.id).size}",
            )
            if (access.allowInteraction("farm-patch-empty:${runtime.settings.id}:${player.uniqueId}", 10_000)) {
                ArcProductTelemetryBridge.worksiteStartRejected(
                    player.uniqueId, "farm", runtime.settings.id, runtime.state.sequence + 1L, "patch_unavailable",
                )
                audience.sendActionBar(player, MessageKey.FARM_PATCH_UNAVAILABLE)
                debug.event(
                    "farm_patch_unavailable", "zone" to runtime.settings.id, "player" to player.name,
                    "search_radius" to runtime.settings.preparationSearchRadius,
                )
            }
            return
        }
        val preparationCrop = order.required.filterKeys {
            MaterialRules.isPlantableCrop(MaterialRules.material(it))
        }.maxWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).key
        val started = FarmShiftEngine.start(
            runtime.state, order, patch, preparationCrop, now, runtime.settings.fieldCompletionPercent,
        )
        runtime.state = started.state
        enterprise.orderStarted(
            runtime.settings.id,
            order.id,
            started.state.sequence,
            started.state.startedAt,
        )
        val pending = PendingStart(
            runtime = runtime,
            player = player,
            playerId = player.uniqueId,
            result = started,
            order = order,
            patch = patch,
            targetSize = targetSize,
            seederShift = seederShift,
            token = token,
        )
        pendingStarts[runtime.settings.id] = pending
        persistUntilStable(pending, failedAttempts = 0)
    }

    private fun persistUntilStable(pending: PendingStart, failedAttempts: Int) {
        val zoneId = pending.runtime.settings.id
        val submittedState = pending.runtime.state
        runCatching(persistAsync).getOrElse { CompletableFuture.failedFuture(it) }.whenComplete { _, failure ->
            tasks.runSync(pending.token) {
                if (pendingStarts[zoneId] !== pending) return@runSync
                if (failure != null) {
                    scheduleRetry(pending, failedAttempts, failure)
                    return@runSync
                }
                if (pending.runtime.state != submittedState) {
                    persistUntilStable(pending, failedAttempts = 0)
                    return@runSync
                }
                finishDurableStart(pending)
            }
        }
    }

    private fun scheduleRetry(pending: PendingStart, previousFailures: Int, failure: Throwable) {
        val failedAttempts = if (previousFailures == Int.MAX_VALUE) Int.MAX_VALUE else previousFailures + 1
        val policy = retrySettings()
        val delayTicks = policy.retryDelayTicks(failedAttempts)
        if (failedAttempts == 1 || failedAttempts % policy.logEveryAttempts == 0) {
            state.log(
                Level.SEVERE,
                "Farm start persistence outcome is unknown; retaining shift and reservation " +
                    "zone=${pending.runtime.settings.id} sequence=${pending.result.state.sequence} " +
                    "attempt=$failedAttempts retry_ticks=$delayTicks",
                failure,
            )
        }
        tasks.runLater(pending.token, delayTicks) {
            persistUntilStable(pending, failedAttempts)
        }
    }

    private fun finishDurableStart(pending: PendingStart) {
        val runtime = pending.runtime
        val zoneId = runtime.settings.id
        registry.addBeds(zoneId, pending.patch)
        pendingStarts.remove(zoneId, pending)
        if (runtime.state != pending.result.state) {
            state.log(
                Level.WARNING,
                "Farm patch $zoneId changed while its durable start was pending; " +
                    "the current state was saved without applying stale transition effects",
            )
            return
        }
        if (pending.patch.size < pending.targetSize) debug.event(
            "farm_patch_limited", "zone" to zoneId, "wanted" to pending.targetSize,
            "available" to pending.patch.size, "mechanized" to pending.seederShift,
        )
        debug.event(
            "farm_patch_selected", "zone" to zoneId, "sequence" to runtime.state.sequence,
            "order" to pending.order.id, "rarity" to pending.order.rarity, "plots" to pending.patch.size,
            "min_x" to pending.patch.minOf(FarmPlotPosition::x),
            "max_x" to pending.patch.maxOf(FarmPlotPosition::x),
            "y_levels" to pending.patch.map(FarmPlotPosition::y).distinct().sorted(),
            "min_z" to pending.patch.minOf(FarmPlotPosition::z),
            "max_z" to pending.patch.maxOf(FarmPlotPosition::z),
            "mechanized" to pending.seederShift,
        )
        transitions.apply(runtime, pending.result.copy(state = runtime.state), pending.player)
        ArcProductTelemetryBridge.worksiteStarted(
            playerId = pending.playerId,
            kind = "farm",
            zoneId = zoneId,
            sequence = runtime.state.sequence,
            subject = pending.order.id,
            attributes = mapOf("order_rarity" to pending.order.rarity.name.lowercase()),
        )
        if (pending.player.isOnline) {
            val premium = enterprise.orderPremium(zoneId, runtime.state.sequence)
            if (premium != null) audience.sendChat(pending.player,
                if (premium.paidAtCompletion) MessageKey.COMPANY_ORDER_COMPLETION_PREMIUM
                else if (premium.simulated) MessageKey.COMPANY_ORDER_SHADOW else MessageKey.COMPANY_ORDER_PREMIUM,
                mapOf("amount" to net.kyori.adventure.text.Component.text(java.math.BigDecimal.valueOf(premium.workerPoolCents, 2).toPlainString())))
            else if (enterprise.projectView(zoneId) != null) audience.sendChat(pending.player, MessageKey.COMPANY_ORDER_NO_PREMIUM)
        }
    }
}
