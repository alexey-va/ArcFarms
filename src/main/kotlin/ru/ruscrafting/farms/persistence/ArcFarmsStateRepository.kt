package ru.ruscrafting.farms.persistence

import ru.arc.persistence.CoalescingAsyncWriter
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.ArcFarmsState
import ru.ruscrafting.farms.domain.FarmCropDamage
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.domain.PendingFarmReward
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.LumberShiftState
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.MAX_FARM_PATCH_PLOTS
import ru.ruscrafting.farms.domain.MAX_FARM_INCIDENTS
import ru.ruscrafting.farms.domain.PlayerActivityStats
import ru.ruscrafting.farms.domain.ShiftOutcome
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class ArcFarmsPersistenceHealth(
    val pendingRequests: Int,
    val completedRequests: Long,
    val failedRequests: Long,
    val lastDurationMillis: Long,
    val maxDurationMillis: Long,
)

class ArcFarmsStateRepository(dataRoot: Path) : AutoCloseable {
    private val store = AtomicJsonStore(
        path = dataRoot.resolve("data/state.json"),
        type = ArcFarmsState::class.java,
        emptyValue = ::ArcFarmsState,
        validate = ::validateState,
    )
    private val writer = CoalescingAsyncWriter(store::saveAsync)
    private val pendingRequests = AtomicInteger()
    private val completedRequests = AtomicLong()
    private val failedRequests = AtomicLong()
    private val lastDurationMillis = AtomicLong()
    private val maxDurationMillis = AtomicLong()

    fun load(): ArcFarmsState {
        val state = store.load()
        val farms = state.farms.mapValues { (_, farm) ->
            val normalized = if (farm.diseaseDamagedCrops == null) {
                farm.copy(diseaseDamagedCrops = emptyList())
            } else farm
            val resolved = maxOf(normalized.incidentsResolved, if (normalized.incidentResolved) 1 else 0)
            val completedLegacyIncident = normalized.phase == FarmPhase.HARVESTING &&
                normalized.incidentResolved && normalized.incidentType == null && normalized.incidentCrop != null
            if (resolved == normalized.incidentsResolved && !completedLegacyIncident) {
                normalized
            } else {
                normalized.copy(
                    incidentsResolved = resolved,
                    incidentCrop = if (completedLegacyIncident) null else normalized.incidentCrop,
                    incidentProgress = if (completedLegacyIncident) 0 else normalized.incidentProgress,
                    incidentRequired = if (completedLegacyIncident) 0 else normalized.incidentRequired,
                )
            }
        }
        val stats = state.stats.mapValues { (_, playerStats) ->
            if (playerStats.weeklyContributions == null) {
                playerStats.copy(weeklyContributions = emptyMap())
            } else {
                playerStats
            }
        }
        val pausedFarmZones = state.pausedFarmZones.orEmpty()
        val farmPerks = state.farmPerks.orEmpty()
        return if (
            stats == state.stats && farms == state.farms && pausedFarmZones == state.pausedFarmZones &&
            farmPerks == state.farmPerks
        ) {
            state
        } else {
            state.copy(farms = farms, pausedFarmZones = pausedFarmZones, stats = stats, farmPerks = farmPerks)
        }
    }

    fun saveAsync(state: ArcFarmsState): CompletableFuture<Unit> {
        val started = System.nanoTime()
        pendingRequests.incrementAndGet()
        return writer.submit(state).whenComplete { _, failure ->
            pendingRequests.decrementAndGet()
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            lastDurationMillis.set(elapsed)
            maxDurationMillis.accumulateAndGet(elapsed, ::maxOf)
            if (failure == null) completedRequests.incrementAndGet() else failedRequests.incrementAndGet()
        }
    }

    fun health(): ArcFarmsPersistenceHealth = ArcFarmsPersistenceHealth(
        pendingRequests = pendingRequests.get(),
        completedRequests = completedRequests.get(),
        failedRequests = failedRequests.get(),
        lastDurationMillis = lastDurationMillis.get(),
        maxDurationMillis = maxDurationMillis.get(),
    )

    fun saveBlocking(state: ArcFarmsState) {
        saveAsync(state).get(SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    override fun close() {
        writer.closeAsync().get(SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        store.close()
    }

    private companion object {
        const val SAVE_TIMEOUT_SECONDS = 15L
        const val MAX_ZONE_CONTRIBUTORS = 10_000
        val ZONE_ID = Regex("[a-z0-9_-]{1,48}")
        val CONTENT_ID = Regex("[A-Z0-9_]{2,64}")

        fun validateState(state: ArcFarmsState) {
            require(state.schemaVersion == ArcFarmsState.SCHEMA_VERSION) { "Unsupported ArcFarms state schema" }
            require(state.farms.size <= 256 && state.lumbermills.size <= 256 && state.mines.size <= 256) {
                "ArcFarms state contains too many zones"
            }
            require(state.stats.size <= 1_000_000) { "ArcFarms player statistics are unbounded" }
            require(state.farms.keys.all(ZONE_ID::matches)) { "ArcFarms state contains an invalid farm zone id" }
            require(state.pausedFarmZones.orEmpty().size <= 256 && state.pausedFarmZones.orEmpty().all(ZONE_ID::matches)) {
                "ArcFarms paused farm zones are invalid"
            }
            require(state.lumbermills.keys.all(ZONE_ID::matches)) { "ArcFarms state contains an invalid lumber zone id" }
            require(state.mines.keys.all(ZONE_ID::matches)) { "ArcFarms state contains an invalid mine zone id" }
            state.farms.values.forEach(::validateFarm)
            state.lumbermills.values.forEach(::validateLumber)
            state.mines.values.forEach(::validateMine)
            state.stats.values.forEach(::validateStats)
            require(state.farmPerks.orEmpty().size <= 1_000_000) { "Farm perk ledgers are unbounded" }
            state.farmPerks.orEmpty().values.forEach { perks ->
                require(perks.weekStartEpochDay >= 0 && perks.spentPoints >= 0) { "Farm perk ledger is invalid" }
                require(perks.activeUntil.size <= ru.ruscrafting.farms.domain.FarmPerkType.entries.size) {
                    "Farm perk ledger contains too many active perks"
                }
                require(perks.activeUntil.values.all { it in 0 until Long.MAX_VALUE }) {
                    "Farm perk expiry is invalid"
                }
            }
            require(state.pendingFarmRewards.size <= 10_000) { "Pending farm rewards are unbounded" }
            require(state.pendingFarmRewards.map(PendingFarmReward::id).distinct().size == state.pendingFarmRewards.size) {
                "Pending farm rewards contain duplicate ids"
            }
            state.pendingFarmRewards.forEach(::validateFarmReward)
            require(state.claimedFarmRewardSequences.size <= 1_000_000) { "Claimed farm reward watermarks are unbounded" }
            require(state.claimedFarmRewardSequences.keys.all(REWARD_CLAIM_KEY::matches)) {
                "Claimed farm reward watermark contains an invalid key"
            }
            require(state.claimedFarmRewardSequences.values.all { it in 0 until Long.MAX_VALUE }) {
                "Claimed farm reward watermark contains an invalid sequence"
            }
        }

        private fun validateFarm(farm: FarmShiftState) {
            validateSequenceAndTimes(farm.sequence, farm.startedAt, farm.cooldownEndsAt)
            require(farm.placementSequence in 0 until Long.MAX_VALUE) { "Farm placement sequence is invalid" }
            farm.orderId?.let { require(ZONE_ID.matches(it)) { "Farm order id is invalid" } }
            require(farm.progress.size <= 12 && farm.progress.keys.all(CONTENT_ID::matches)) {
                "Farm crop progress is invalid"
            }
            require(farm.progress.values.all { it in 0..100_000 }) { "Farm crop progress is outside supported bounds" }
            require(farm.harvestCheckpoint in 0..10) { "Farm harvest checkpoint is invalid" }
            require(farm.harvestMilestone in 0..4) { "Farm harvest milestone is invalid" }
            require(farm.incidentsResolved in 0..MAX_FARM_INCIDENTS) { "Farm incident completion count is invalid" }
            listOf(farm.preparationCrop, farm.incidentCrop).filterNotNull().forEach {
                require(CONTENT_ID.matches(it)) { "Farm crop id is invalid" }
            }
            validateContributors(farm.contributors)

            require(
                farm.preparationPatch.size <= MAX_FARM_PATCH_PLOTS &&
                    farm.preparationPatch.distinct().size == farm.preparationPatch.size,
            ) {
                "Farm preparation patch is invalid"
            }
            farm.preparationPatch.forEach(::validatePlot)
            farm.tilledPlots.forEach(::validatePlot)
            farm.plantedPlots.forEach(::validatePlot)
            require(farm.tilledPlots.all(farm.preparationPatch::contains)) { "Farm tilled plots escaped their patch" }
            require(farm.plantedPlots.all(farm.tilledPlots::contains)) { "Farm planted plots were not tilled" }
            require(farm.preparationProgress >= 0 && farm.plantingProgress >= 0 && farm.preparationRequired >= 0) {
                "Farm preparation progress is negative"
            }
            if (farm.preparationPatch.isEmpty()) {
                require(
                    farm.tilledPlots.isEmpty() && farm.plantedPlots.isEmpty() && farm.preparationProgress == 0 &&
                        farm.plantingProgress == 0 && farm.preparationRequired == 0,
                ) { "Empty farm preparation patch contains progress" }
            } else {
                require(farm.preparationCrop != null) { "Farm preparation patch has no crop" }
                require(farm.preparationRequired in 1..farm.preparationPatch.size) {
                    "Farm preparation quota escaped its patch"
                }
                require(farm.preparationProgress == farm.tilledPlots.size) { "Farm tilling progress drifted from its plots" }
                require(farm.plantingProgress == farm.plantedPlots.size) { "Farm planting progress drifted from its plots" }
            }

            require(farm.careTargets.size <= 512 && farm.careTargets.map { it.id }.distinct().size == farm.careTargets.size) {
                "Farm care state is invalid"
            }
            farm.careTargets.forEach { target ->
                require(target.id in 0..511 && target.required in 1..8 && target.progress in 0..target.required) {
                    "Farm care target progress is invalid"
                }
                validatePoint(target.position)
            }
            require(
                farm.careGoal == null ||
                    (farm.careTargets.isNotEmpty() && farm.careGoal in 1..farm.careTargets.sumOf { it.required }),
            ) { "Farm care goal is invalid" }
            if (farm.phase == FarmPhase.CARE) {
                require(
                    farm.careType != null && farm.careTargets.isNotEmpty() &&
                        (farm.careType == FarmCareType.SEEDER || farm.careProgress() < farm.careRequired()),
                ) {
                    "Active farm care state is incomplete"
                }
            }
            require(
                farm.seederStage == null ||
                    (farm.phase == FarmPhase.CARE && farm.careType == FarmCareType.SEEDER),
            ) { "Farm seeder stage escaped machine care" }
            if (farm.phase == FarmPhase.CARE && farm.careType == FarmCareType.SEEDER) {
                require(farm.careTargets.count { it.role == FarmCareRole.SEEDER_HORSE } == 1) {
                    "Farm seeder state must contain one horse"
                }
            }

            require(farm.incidentProgress >= 0 && farm.incidentRequired in 0..1_024) { "Farm incident progress is invalid" }
            require(farm.incidentRequired == 0 || farm.incidentProgress <= farm.incidentRequired) {
                "Farm incident progress exceeds its quota"
            }
            require(farm.droughtPlots.size <= 64 && farm.droughtDamagedPlots.size <= 4_096) {
                "Farm drought state is unbounded"
            }
            farm.droughtPlots.forEach(::validatePlot)
            farm.droughtDamagedPlots.forEach(::validatePlot)
            require(farm.pestNests.size <= 16 && farm.pestNests.distinctBy { it.position }.size == farm.pestNests.size) {
                "Farm pest nests are invalid"
            }
            farm.pestNests.forEach { nest ->
                validatePlot(nest.position)
                require(nest.health in 1..20 && nest.spawned in 0..64) { "Farm pest nest state is invalid" }
            }
            require(farm.pestAlive in 0..32 && farm.pestDamagedCrops.size <= 4_096) { "Farm pest state is unbounded" }
            require(farm.pestDamagedCrops.distinctBy(FarmCropDamage::position).size == farm.pestDamagedCrops.size) {
                "Farm pest crop damage contains duplicate plots"
            }
            farm.pestDamagedCrops.forEach { damage ->
                validatePlot(damage.position)
                require(CONTENT_ID.matches(damage.crop)) { "Farm pest crop damage has an invalid crop" }
            }
            require(farm.diseaseDamagedCrops.orEmpty().size <= 4_096) { "Farm disease crop damage is unbounded" }
            require(
                farm.diseaseDamagedCrops.orEmpty().distinctBy(FarmCropDamage::position).size ==
                    farm.diseaseDamagedCrops.orEmpty().size,
            ) { "Farm disease crop damage contains duplicate plots" }
            farm.diseaseDamagedCrops.orEmpty().forEach { damage ->
                validatePlot(damage.position)
                require(CONTENT_ID.matches(damage.crop)) { "Farm disease crop damage has an invalid crop" }
            }
            farm.specialIncident?.let { special ->
                require(
                    farm.phase == FarmPhase.INCIDENT && farm.incidentType in setOf(
                        FarmIncidentType.GIANT_CROP,
                        FarmIncidentType.CHANNELS,
                        FarmIncidentType.NIGHT_SHIFT,
                        FarmIncidentType.MARKET,
                        FarmIncidentType.BIRDS,
                        FarmIncidentType.FOOD_DELIVERY,
                    ),
                ) { "Farm special incident state escaped its active incident" }
                require(special.points.size <= 16 && special.plots.size <= 128) {
                    "Farm special incident state is unbounded"
                }
                special.points.forEach(::validatePoint)
                special.plots.forEach(::validatePlot)
                special.crop?.let { require(CONTENT_ID.matches(it)) { "Farm special incident crop is invalid" } }
                require(special.solution.all { it in special.points.indices } && special.active.all { it in special.points.indices }) {
                    "Farm channel state references an unknown blockage"
                }
                when (farm.incidentType) {
                    FarmIncidentType.GIANT_CROP -> require(special.points.size == 1 && special.crop != null) {
                        "Farm giant crop state is incomplete"
                    }
                    FarmIncidentType.CHANNELS -> require(special.points.size in 3..8 && special.solution.isNotEmpty()) {
                        "Farm channel state is incomplete"
                    }
                    FarmIncidentType.NIGHT_SHIFT -> require(special.plots.isNotEmpty()) {
                        "Farm night shift state is incomplete"
                    }
                    FarmIncidentType.MARKET -> require(special.plots.isNotEmpty() && special.crop != null) {
                        "Farm market state is incomplete"
                    }
                    FarmIncidentType.BIRDS -> require(special.plots.isNotEmpty()) {
                        "Farm bird state is incomplete"
                    }
                    FarmIncidentType.FOOD_DELIVERY -> require(
                        special.points.isEmpty() && special.plots.isEmpty() && farm.incidentRequired in 2..512,
                    ) { "Farm food delivery state is incomplete" }
                    else -> error("Farm special incident state has an invalid type")
                }
                if (farm.incidentType != FarmIncidentType.MARKET) {
                    require(!special.marketAccepted && special.marketDeadlineAt == 0L) {
                        "Non-market farm incident contains market state"
                    }
                } else if (!special.marketAccepted) {
                    require(special.marketDeadlineAt == 0L) { "Pending farm market has a deadline" }
                }
            }
            require(farm.specialDamagedCrops.size <= 4_096) { "Farm special crop damage is unbounded" }
            require(farm.specialDamagedCrops.distinctBy(FarmCropDamage::position).size == farm.specialDamagedCrops.size) {
                "Farm special crop damage contains duplicate plots"
            }
            farm.specialDamagedCrops.forEach { damage ->
                validatePlot(damage.position)
                require(CONTENT_ID.matches(damage.crop)) { "Farm special crop damage has an invalid crop" }
            }
            require(farm.rewardMoneyBonusPercent in 0..200) { "Farm reward money bonus is invalid" }
            farm.deliveryPosition?.let { position ->
                require(position.world.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Farm delivery world is invalid" }
                require(listOf(position.x, position.y, position.z).all(Double::isFinite)) { "Farm delivery position is invalid" }
                require(position.x in -30_000_000.0..30_000_000.0 && position.z in -30_000_000.0..30_000_000.0) {
                    "Farm delivery position is outside the world border"
                }
                require(position.y in -2_048.0..2_048.0) { "Farm delivery height is invalid" }
            }
            require(farm.deliveredCrates.size <= 8 && farm.deliveredCrates.all { it in 0..7 }) {
                "Farm delivery crate state is invalid"
            }

            val worlds = buildSet {
                farm.preparationPatch.mapTo(this, FarmPlotPosition::world)
                farm.careTargets.mapTo(this) { it.position.world }
                farm.droughtPlots.mapTo(this, FarmPlotPosition::world)
                farm.droughtDamagedPlots.mapTo(this, FarmPlotPosition::world)
                farm.pestNests.mapTo(this) { it.position.world }
                farm.pestDamagedCrops.mapTo(this) { it.position.world }
                farm.diseaseDamagedCrops.orEmpty().mapTo(this) { it.position.world }
                farm.specialIncident?.points?.mapTo(this) { it.world }
                farm.specialIncident?.plots?.mapTo(this, FarmPlotPosition::world)
                farm.specialDamagedCrops.mapTo(this) { it.position.world }
                farm.deliveryPosition?.world?.let(::add)
            }
            require(worlds.size <= 1) { "Farm shift crosses worlds" }
            if (farm.phase == FarmPhase.IDLE) {
                require(
                    farm.orderId == null && farm.progress.isEmpty() && farm.preparationPatch.isEmpty() &&
                        farm.preparationCrop == null && !farm.preparationReleased && farm.careType == null &&
                        farm.careTargets.isEmpty() && farm.incidentCrop == null && farm.incidentType == null &&
                        farm.incidentProgress == 0 && farm.incidentRequired == 0 && !farm.incidentResolved &&
                        farm.incidentsResolved == 0 && farm.harvestCheckpoint == 0 && farm.harvestMilestone == 0 &&
                        farm.droughtPlots.isEmpty() && farm.droughtDamagedPlots.isEmpty() &&
                        !farm.pestNestsInitialized && farm.pestNests.isEmpty() && farm.pestAlive == 0 &&
                        farm.pestDamagedCrops.isEmpty() && farm.specialIncident == null &&
                        farm.diseaseDamagedCrops.orEmpty().isEmpty() &&
                        farm.specialDamagedCrops.isEmpty() && farm.rewardMoneyBonusPercent == 0 &&
                        farm.deliveryPosition == null && farm.deliveredCrates.isEmpty() &&
                        farm.startedAt == 0L && farm.cooldownEndsAt == 0L && farm.contributors.isEmpty() &&
                        farm.outcome == ShiftOutcome.NONE,
                ) { "Idle farm state contains an active shift" }
            } else {
                require(farm.orderId != null) { "Active farm state has no order" }
            }
            if (farm.phase == FarmPhase.INCIDENT) {
                require(
                    farm.incidentCrop != null && farm.incidentRequired > 0 &&
                        farm.incidentProgress < farm.incidentRequired && !farm.incidentResolved,
                ) { "Active farm incident state is incomplete" }
            }
            if (farm.phase == FarmPhase.COOLDOWN) {
                require(farm.outcome == ShiftOutcome.COMPLETED && farm.cooldownEndsAt > 0) { "Farm cooldown state is incomplete" }
            } else {
                require(farm.outcome == ShiftOutcome.NONE) { "Active farm outcome is inconsistent" }
            }
        }

        private fun validateLumber(lumber: LumberShiftState) {
            validateSequenceAndTimes(lumber.sequence, lumber.startedAt, lumber.cooldownEndsAt)
            lumber.species?.let { require(CONTENT_ID.matches(it)) { "Lumber species is invalid" } }
            require(lumber.felled in 0..100_000 && lumber.processed in 0..100_000) { "Lumber progress is invalid" }
            validateContributors(lumber.contributors)
            if (lumber.phase == LumberPhase.IDLE) {
                require(
                    lumber.species == null && lumber.felled == 0 && lumber.processed == 0 &&
                        lumber.contributors.isEmpty() && lumber.outcome == ShiftOutcome.NONE,
                ) { "Idle lumber state contains an active shift" }
            } else {
                require(lumber.species != null) { "Active lumber state has no species" }
            }
            if (lumber.phase == LumberPhase.COOLDOWN) {
                require(lumber.outcome == ShiftOutcome.COMPLETED && lumber.cooldownEndsAt > 0) {
                    "Lumber cooldown state is incomplete"
                }
            } else {
                require(lumber.outcome == ShiftOutcome.NONE) { "Active lumber outcome is inconsistent" }
            }
        }

        private fun validateMine(mine: MineShiftState) {
            validateSequenceAndTimes(mine.sequence, mine.startedAt, mine.cooldownEndsAt)
            require(mine.cart in 0..100_000 && mine.supports in 0..32) { "Mine progress is invalid" }
            validateContributors(mine.contributors)
            when (mine.phase) {
                MinePhase.IDLE -> require(
                    mine.cart == 0 && mine.supports == 0 && !mine.hazardResolved &&
                        mine.contributors.isEmpty() && mine.outcome == ShiftOutcome.NONE,
                ) { "Idle mine state contains an active shift" }
                MinePhase.HAZARD -> require(!mine.hazardResolved) { "Active mine hazard is already marked resolved" }
                MinePhase.EXTRACTION, MinePhase.COOLDOWN -> require(mine.hazardResolved) {
                    "Mine reached extraction before resolving its hazard"
                }
                MinePhase.MINING -> Unit
            }
            if (mine.phase == MinePhase.COOLDOWN) {
                require(mine.outcome == ShiftOutcome.COMPLETED && mine.cooldownEndsAt > 0) { "Mine cooldown state is incomplete" }
            } else {
                require(mine.outcome == ShiftOutcome.NONE) { "Active mine outcome is inconsistent" }
            }
        }

        private fun validateStats(stats: PlayerActivityStats) {
            val activities = ActivityKind.entries.toSet()
            val weekly = stats.weeklyContributions.orEmpty()
            require(
                stats.contributions.size <= activities.size && stats.completedShifts.size <= activities.size &&
                    weekly.size <= activities.size && stats.contributions.keys.all(activities::contains) &&
                    stats.completedShifts.keys.all(activities::contains) && weekly.keys.all(activities::contains),
            ) {
                "Player activity statistics contain unknown entries"
            }
            require(stats.contributions.values.all { it >= 0 }) { "Negative contribution" }
            require(stats.completedShifts.values.all { it >= 0 }) { "Negative completion count" }
            require(weekly.values.all { it.weekStartEpochDay in 0..4_000_000 && it.contribution >= 0 }) {
                "Invalid weekly contribution"
            }
        }

        private fun validateFarmReward(reward: PendingFarmReward) {
            require(REWARD_ID.matches(reward.id)) { "Pending farm reward id is invalid" }
            require(ZONE_ID.matches(reward.zoneId) && reward.id == "${reward.zoneId}:${reward.sequence}:${reward.playerId}") {
                "Pending farm reward identity is inconsistent"
            }
            require(reward.sequence in 0 until Long.MAX_VALUE && reward.contribution > 0) {
                "Pending farm reward sequence or contribution is invalid"
            }
            require(reward.experience in 0..10_000 && reward.moneyCents in 0..100_000_000L) {
                "Pending farm reward amount is outside supported bounds"
            }
            require(reward.items.size <= 128 && reward.items.all { item ->
                CONTENT_ID.matches(item.material) && item.amount in 1..2_304
            }) { "Pending farm reward items are invalid" }
            require(reward.fixedItemUnits in 0..reward.items.sumOf { it.amount }) {
                "Pending farm reward fixed item count is invalid"
            }
            require(reward.commands.size <= 32 && reward.commands.all { command ->
                command.length in 1..1_024 && '\n' !in command && '\r' !in command
            }) { "Pending farm reward commands are invalid" }
            require(reward.bundleIds.size <= 4 && reward.bundleIds.all(ZONE_ID::matches)) {
                "Pending farm reward bundle ids are invalid"
            }
        }

        private fun validateContributors(contributors: Map<java.util.UUID, Int>) {
            require(contributors.size <= MAX_ZONE_CONTRIBUTORS) { "Shift contributor state is unbounded" }
            require(contributors.values.all { it > 0 }) { "Shift contribution is invalid" }
        }

        private fun validateSequenceAndTimes(sequence: Long, vararg times: Long) {
            require(sequence in 0 until Long.MAX_VALUE) { "Shift sequence is invalid" }
            require(times.all { it >= 0 }) { "Shift timestamp is negative" }
        }

        private fun validatePlot(plot: FarmPlotPosition) {
            require(plot.world.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Farm plot world is invalid" }
            require(plot.x in -30_000_000..30_000_000 && plot.z in -30_000_000..30_000_000) {
                "Farm plot position is outside the world border"
            }
            require(plot.y in -4_096..4_096) { "Farm plot height is invalid" }
        }

        private fun validatePoint(point: FarmPointPosition) {
            require(point.world.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Farm point world is invalid" }
            require(listOf(point.x, point.y, point.z).all(Double::isFinite)) { "Farm point coordinates are invalid" }
            require(point.x in -30_000_000.0..30_000_000.0 && point.z in -30_000_000.0..30_000_000.0) {
                "Farm point is outside the world border"
            }
            require(point.y in -2_048.0..2_048.0 && point.yaw.isFinite() && point.pitch.isFinite() && point.pitch in -90f..90f) {
                "Farm point rotation or height is invalid"
            }
        }

        val REWARD_ID = Regex("[a-z0-9_-]{1,48}:[0-9]{1,19}:[0-9a-f-]{36}")
        val REWARD_CLAIM_KEY = Regex("[a-z0-9_-]{1,48}:[0-9a-f-]{36}")
    }
}
