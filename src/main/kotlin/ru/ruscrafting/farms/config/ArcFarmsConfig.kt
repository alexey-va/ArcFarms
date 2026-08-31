package ru.ruscrafting.farms.config

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.network.BackendServerId
import ru.arc.redis.RedisModuleConfig
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmContractRarity
import ru.ruscrafting.farms.domain.FarmCustomerType
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCarePlanner
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.LumberIncidentType
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MAX_FARM_PATCH_PLOTS
import ru.ruscrafting.farms.domain.TrustedFarmCommandTemplate
import java.nio.file.Path
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ceil
import kotlin.math.floor

private val ROUTE_MONSTER_TYPES = setOf("HUSK", "ZOMBIE", "SKELETON", "SPIDER", "PHANTOM")

data class NetworkSettings(
    val enabled: Boolean,
    val allowedOrigins: Set<String>,
    val playerAnnouncementsEnabled: Boolean,
    val workdayEnabled: Boolean,
    val nodeProbeEnabled: Boolean,
    val travelTicketSeconds: Int,
)

data class DebugSettings(
    val enabled: Boolean,
)

data class TeleportDestination(
    val server: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
)

data class CuboidBounds(
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int,
) {
    val volume: Long = (maxX - minX + 1L) * (maxY - minY + 1L) * (maxZ - minZ + 1L)

    init {
        require(minX <= maxX && minY <= maxY && minZ <= maxZ) { "Cuboid bounds are inverted" }
        require(volume <= 20_000_000L) { "Cuboid bounds exceed 20,000,000 blocks" }
    }

    fun contains(x: Int, y: Int, z: Int): Boolean = x in minX..maxX && y in minY..maxY && z in minZ..maxZ
}

data class ZoneReference(
    val world: String,
    val region: String?,
    val bounds: CuboidBounds?,
) {
    init {
        require(world.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid world name: $world" }
        require((region != null) xor (bounds != null)) { "Zone $world must define exactly one region or bounds" }
        region?.let { require(it.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid region name: $it" } }
    }
}

data class FarmCropLayoutSettings(
    val enabled: Boolean,
    val weights: Map<String, Int>,
    val smallComponentMaxSize: Int,
    val smallComponentMergeDistance: Int,
) {
    init {
        require(weights.isNotEmpty()) { "Farm crop layout has no crop weights" }
        require(weights.values.all { it in 1..10_000 }) { "Farm crop layout weights must be in 1..10000" }
        require(smallComponentMaxSize in 0..256) { "Farm crop layout small-component size is invalid" }
        require(smallComponentMergeDistance in 0..64) { "Farm crop layout merge distance is invalid" }
    }
}

data class FarmZoneSettings(
    val id: String,
    val reference: ZoneReference,
    val permission: String,
    val preparationPatchSize: Int,
    val preparationPatchMaxSize: Int,
    val fieldCompletionPercent: Int,
    val preparationSearchRadius: Int,
    val fixedCropRespawnSeconds: Int,
    val restoreBlocksPerTick: Int,
    val blockReindexBlocksPerTick: Int,
    val blockReindexMaxBlocks: Int,
    val cropLayout: FarmCropLayoutSettings,
    val backupBlocksPerTick: Int,
    val backupMaxBlocks: Int,
    val careRadius: Int,
    val careTypes: List<FarmCareType>,
    val careTargetsPerPlayer: Int,
    val careTargetsMax: Int,
    val careSpawnsPerUpdate: Int,
    val scarecrowTargetCount: Int,
    val scarecrowMinSpacing: Double,
    val pollinationCharges: Int,
    val irrigation: FarmIrrigationSettings,
    val appleTargetCount: Int,
    val applePlacementCount: Int,
    val appleSpawnsPerUpdate: Int,
    val appleMinSpacing: Double,
    val appleDisplayScale: Float,
    val appleDisplayYOffset: Double,
    val appleInteractionYOffset: Double,
    val appleLeafIndexLimit: Int,
    val animalRescueTargetCount: Int,
    val animalRescueMinSpacing: Double,
    val animalRescueMaxPlayerDistance: Int,
    val animalDeliveryRadius: Double,
    val supplyNearbyViewDistance: Float,
    val displayViewRange: Float,
    val seederEveryShifts: Int,
    val seederPatchSize: Int,
    val seederPatchMaxSize: Int,
    val seederComponentGap: Int,
    val seederComponentLimit: Int,
    val seederPigCount: Int,
    val seederPigLeadDistance: Double,
    val seederPigSpacing: Double,
    val seederPigCatchupDistance: Double,
    val seederHorseSpeed: Double,
    val seederPigSpeed: Double,
    val seederWorkingRadius: Double,
    val seederBlocksPerUpdate: Int,
    val diseaseInitialSpots: Int,
    val diseaseMaxSpots: Int,
    val diseaseSpreadSeconds: Int,
    val diseaseSpreadRadius: Double,
    val diseaseKillSeconds: Int,
    val scarecrowDeliveryRadius: Double,
    val scarecrowCarriedYOffset: Double,
    val moleBurrow: FarmMoleBurrowSettings,
    val careAnimalEntities: List<String>,
    val proceduralCareFixtures: Boolean,
    val careVisuals: Map<FarmCareRole, FarmCareVisualSettings>,
    val contractCartVisual: FarmContractCartVisualSettings,
    val music: FarmMusicSettings,
    val placementMinObjectiveDistance: Int,
    val placementMaxPlayerDistance: Int,
    val placementSearchRadius: Int,
    val incidentTriggerPercents: List<Int>,
    val incidentCountMin: Int,
    val incidentCountMax: Int,
    val incidentQuota: Int,
    val droughtPatches: Int,
    val droughtCoveragePercent: Int,
    val droughtMinBeds: Int,
    val droughtMaxBeds: Int,
    val droughtInitialBeds: Int,
    val droughtGrowthBeds: Int,
    val droughtGrowthSeconds: Int,
    val incidentTypes: List<FarmIncidentType>,
    val specialIncidents: FarmSpecialIncidentSettings,
    val processing: FarmProcessingSettings,
    val barnFire: FarmBarnFireSettings,
    val cropEffects: FarmCropBreakEffectsSettings,
    val pestEntity: String,
    val pestSpawnRadius: Int,
    val pestNestCount: Int,
    val pestNestHealth: Int,
    val pestSpawnsPerNest: Int,
    val pestMaxAlive: Int,
    val pestSpawnIntervalSeconds: Int,
    val pestSpawnChancePercent: Int,
    val pestEatRadius: Int,
    val pestEatPerPulse: Int,
    val pestEatIntervalMillis: Long,
    val pestNestMinSpacing: Double,
    val pestNestDisplayScale: Float,
    val damageSafety: FarmDamageSafetySettings,
    val supplies: FarmSupplySettings,
    val delivery: FarmDeliverySettings,
    val routeDelivery: FarmRouteDeliverySettings,
    val perks: FarmPerkSettings,
    val rewards: FarmRewardSettings,
    val crops: Set<String>,
    val rareOrderChancePercent: Int,
    val orders: List<FarmOrderSettings>,
    val droughtWaterRadius: Int = 5,
    val droughtWaterSettleTicks: Long = 21L,
    val placementReceivingExclusionPadding: Double = 1.5,
    val careAnimalFollowDistance: Double = 1.5,
    val careAnimalFollowSpeed: Double = 1.25,
    val careAnimalFollowImpulseBase: Double = 0.16,
    val careAnimalFollowImpulsePerBlock: Double = 0.025,
    val careAnimalFollowImpulseMax: Double = 0.42,
    val careAnimalFollowImpulseSmoothing: Double = 0.25,
    val inputCooldowns: FarmInputCooldownSettings = FarmInputCooldownSettings(),
    val deliveryCarriedForwardOffset: Double = 0.65,
    val scarecrowCarriedForwardOffset: Double = 0.7,
) {
    init {
        require(careTargetsPerPlayer <= careTargetsMax) {
            "Farm care target count per player must not exceed its maximum"
        }
        require(droughtWaterRadius in 1..8) { "Farm drought water radius must be in 1..8" }
        require(droughtWaterSettleTicks in 5L..100L) { "Farm drought water settle ticks must be in 5..100" }
        require(placementReceivingExclusionPadding in 0.0..4.0) {
            "Farm placement receiving exclusion padding must be in 0.0..4.0"
        }
        require(careAnimalFollowDistance in 0.75..4.0) { "Farm animal follow distance must be in 0.75..4.0" }
        require(careAnimalFollowSpeed in 0.25..2.5) { "Farm animal follow speed must be in 0.25..2.5" }
        require(careAnimalFollowImpulseBase in 0.0..1.0) { "Farm animal follow impulse base must be in 0.0..1.0" }
        require(careAnimalFollowImpulsePerBlock in 0.0..0.2) {
            "Farm animal follow impulse per block must be in 0.0..0.2"
        }
        require(careAnimalFollowImpulseMax in 0.05..1.0) { "Farm animal follow impulse max must be in 0.05..1.0" }
        require(careAnimalFollowImpulseBase <= careAnimalFollowImpulseMax) {
            "Farm animal follow impulse base must not exceed max"
        }
        require(careAnimalFollowImpulseSmoothing in 0.0..1.0) {
            "Farm animal follow impulse smoothing must be in 0.0..1.0"
        }
        require(deliveryCarriedForwardOffset in 0.0..2.0) {
            "Farm delivery carried forward offset must be in 0.0..2.0"
        }
        require(scarecrowCarriedForwardOffset in 0.0..2.0) {
            "Farm scarecrow carried forward offset must be in 0.0..2.0"
        }
    }

    fun droughtTargetBeds(gardenBeds: Int): Int {
        if (gardenBeds <= 0) return 0
        val proportional = ceil(gardenBeds * droughtCoveragePercent / 100.0).toInt()
        return proportional.coerceIn(droughtMinBeds, droughtMaxBeds).coerceAtMost(gardenBeds)
    }
}

data class FarmSpecialIncidentSettings(
    val channelBlockageCount: Int,
    val channelBlockageMaterial: String,
    val channelBlockageCustomModelData: Int,
    val channelBlockageDisplayScale: Float,
    val channelBlockageDisplayYOffset: Double,
    val nightCropPlacementCount: Int,
    val nightCropTargetCount: Int,
    val nightCropMinSpacing: Double,
    val nightPlayerTime: Long,
    val nightTimeTransitionSeconds: Int,
    val giantCropParticleStride: Int,
    val giantCropHitCooldownMillis: Long = 90L,
    val birdMinCount: Int,
    val birdMaxCount: Int,
    val birdBedsPerBird: Int,
    val birdSpawnMultiplier: Int,
    val birdSpawnHeight: Double,
    val birdFlyingSpeed: Double,
    val birdHealth: Double,
    val birdEatRadius: Double,
    val birdEatIntervalSeconds: Int,
    val birdRangedContribution: Int,
    val birdMeleeContribution: Int,
    val nightPatrolMinCount: Int,
    val nightPatrolMaxCount: Int,
    val nightPatrolBedsPerPatrol: Int,
    val nightPatrolEntity: String,
    val nightPatrolMinSpacing: Double,
    val nightPatrolRoamRadius: Double,
    val nightPatrolPathRefreshSeconds: Int,
    val nightPatrolSpawnMinPlayerDistance: Double,
    val nightPatrolReceivingSafeRadius: Double,
    val nightPatrolMovementSpeed: Double,
    val nightPatrolFollowRange: Double,
    val nightPatrolAttackDamage: Double,
    val nightPatrolHeldItem: String,
    val nightPatrolLightLevel: Int,
    val marketCropCount: Int,
    val marketMoneyBonusPercent: Int,
    val marketBaseSeconds: Int,
    val marketSecondsPerCrop: Double,
    val marketMinimumSeconds: Int,
    val marketMaximumSeconds: Int,
    val frost: FarmFrostSettings,
) {
    init {
        require(nightCropTargetCount <= nightCropPlacementCount) {
            "night-shift crop target must not exceed its placement count"
        }
        require(nightPatrolMinCount <= nightPatrolMaxCount) {
            "night-shift patrol minimum must not exceed its maximum"
        }
        require(birdMinCount <= birdMaxCount) { "bird minimum must not exceed its maximum" }
        require(giantCropHitCooldownMillis in 50L..1_000L) {
            "giant crop hit cooldown must be in 50..1000 milliseconds"
        }
    }

    fun nightPatrolCount(availableBeds: Int): Int {
        if (availableBeds <= 0 || nightPatrolMaxCount == 0) return 0
        return kotlin.math.ceil(availableBeds.toDouble() / nightPatrolBedsPerPatrol)
            .toInt()
            .coerceIn(nightPatrolMinCount, nightPatrolMaxCount)
            .coerceAtMost(availableBeds)
    }

    fun birdCount(availableBeds: Int): Int {
        if (availableBeds <= 0 || birdMaxCount == 0) return 0
        return kotlin.math.ceil(availableBeds.toDouble() / birdBedsPerBird)
            .toInt()
            .coerceIn(birdMinCount, birdMaxCount)
            .coerceAtMost(availableBeds)
    }
}

data class FarmFrostSettings(
    val campfireMinCount: Int,
    val campfireMaxCount: Int,
    val bedsPerCampfire: Int,
    val targetTemperature: Int,
    val heatPerSecondPerFire: Int,
    val coolingSecondsPerDegree: Int,
    val fuelSeconds: Int,
    val pickupRadius: Double,
    val deliveryRadius: Double,
    val fuelMaterial: String,
    val woodpileMaterial: String,
    val woodpileCustomModelData: Int,
    val woodpileDisplayTransform: String,
    val woodpileScale: Float,
    val woodpileYOffset: Double,
    val woodpileYawOffset: Float,
    val woodpileViewRange: Float,
) {
    init {
        require(campfireMinCount <= campfireMaxCount) { "frost campfire minimum must not exceed maximum" }
    }

    fun campfireCount(availableBeds: Int): Int {
        if (availableBeds <= 0) return 0
        return kotlin.math.ceil(availableBeds.toDouble() / bedsPerCampfire)
            .toInt()
            .coerceIn(campfireMinCount, campfireMaxCount)
            .coerceAtMost(availableBeds)
    }
}

enum class FarmProcessingVisualRole {
    MACHINE,
    WHEEL,
    INPUT_RACK,
    OUTPUT_PALLET,
    RAW_PACKAGE,
    PRODUCT_PACKAGE,
}

data class FarmProcessingVisualSettings(
    val material: String,
    val customModelData: Int,
    val itemModel: String?,
    val displayTransform: FarmItemDisplayTransform,
    val scale: Float,
    val yOffset: Double,
    val yawOffset: Float,
)

data class FarmProcessingSettings(
    val inputPackages: Int,
    val machineCycles: Int,
    val outputPackages: Int,
    val interactionRadius: Double,
    val proximityPickupRadius: Double,
    val deliveryRadius: Double,
    val crankInnerRadius: Double,
    val crankOuterRadius: Double,
    val crankRadiusTolerance: Double,
    val crankMaxStepDistance: Double,
    val crankTitleReminderSeconds: Int,
    val carriedYOffset: Double,
    val cargoReminderSeconds: Int,
    val cargoReturnSeconds: Int,
    val cargoProgressDistance: Double = 1.0,
    val crankVerticalTolerance: Double = 2.5,
    val spawnPerTick: Int,
    val displayViewRange: Float,
    val visuals: Map<FarmProcessingVisualRole, FarmProcessingVisualSettings>,
) {
    init {
        require(crankInnerRadius < crankOuterRadius) { "processing crank inner radius must be below outer radius" }
        require(cargoReminderSeconds < cargoReturnSeconds) {
            "processing cargo reminder must happen before automatic return"
        }
        require(cargoProgressDistance in 0.25..3.0) { "processing cargo progress distance must be in 0.25..3.0" }
        require(crankVerticalTolerance in 0.5..6.0) { "processing crank vertical tolerance must be in 0.5..6.0" }
    }
}

data class FarmBarnFireSettings(
    val hotspotCount: Int,
    val initialHotspotCount: Int,
    val spreadIntervalTicks: Int,
    val spreadHotspotsPerPulse: Int,
    val spawnPerTick: Int,
    val placementRadius: Int,
    val minSpacing: Double,
    val verticalSearch: Int,
    val sprayRange: Double,
    val sprayHitRadius: Double,
    val sprayCooldownTicks: Int,
    val particleStep: Double,
    val flameParticleIntervalTicks: Int,
    val particleHotspotLimit: Int,
    val waterSideStreams: Int = 4,
) {
    init {
        require(initialHotspotCount <= hotspotCount) {
            "barn fire initial hotspot count must not exceed its total hotspot cap"
        }
        require(waterSideStreams in 1..8) { "barn fire spray side streams must be in 1..8" }
    }
}

data class FarmCropBreakEffectsSettings(
    val blockParticleCount: Int = 14,
    val dustParticleCount: Int = 7,
    val composterParticleCount: Int = 3,
    val poofParticleCount: Int = 5,
) {
    init {
        require(blockParticleCount in 0..64) { "crop harvest block particle count must be in 0..64" }
        require(dustParticleCount in 0..64) { "crop harvest dust particle count must be in 0..64" }
        require(composterParticleCount in 0..32) { "crop harvest composter particle count must be in 0..32" }
        require(poofParticleCount in 0..32) { "crop harvest poof particle count must be in 0..32" }
    }
}

data class FarmRewardSettings(
    val experience: FarmExperienceRewardSettings,
    val money: FarmMoneyRewardSettings,
    val items: List<FarmItemRewardSettings>,
    val commands: List<FarmCommandRewardSettings>,
    val randomBundles: FarmRandomBundleSettings,
) {
    val requiresEconomy: Boolean get() = money.amountCents > 0
}

internal fun defaultWorksiteRewards(experience: Int = 100): FarmRewardSettings = FarmRewardSettings(
    FarmExperienceRewardSettings(experience, 100),
    FarmMoneyRewardSettings(0L, 100),
    emptyList(),
    emptyList(),
    FarmRandomBundleSettings(0, 100, emptyList()),
)

data class FarmExperienceRewardSettings(
    val amount: Int,
    val chancePercent: Int,
)

data class FarmMoneyRewardSettings(
    val amountCents: Long,
    val chancePercent: Int,
)

data class FarmItemRewardSettings(
    val id: String,
    val material: String,
    val amount: Int,
    val chancePercent: Int,
)

data class FarmCommandRewardSettings(
    val id: String,
    val command: String,
    val chancePercent: Int,
)

data class FarmRandomBundleSettings(
    val rolls: Int,
    val chancePercent: Int,
    val entries: List<FarmRewardBundleSettings>,
)

data class FarmRewardBundleSettings(
    val id: String,
    val weight: Int,
    val items: List<FarmBundleItemSettings>,
)

data class FarmBundleItemSettings(
    val material: String,
    val amount: Int,
)

data class FarmMusicSettings(
    val enabled: Boolean,
    val sound: String,
    val durationSeconds: Int,
    val volume: Float,
)

enum class FarmItemDisplayTransform { GROUND, FIXED, HEAD }

data class FarmContractCartVisualSettings(
    val material: String,
    val customModelData: Int,
    val displayTransform: FarmItemDisplayTransform,
    val scale: Float,
    val yOffset: Double,
    val yawOffset: Float,
    val loadYOffset: Double,
    val loadScale: Float,
    val viewRange: Float,
)

data class FarmCareVisualSettings(
    val material: String,
    val customModelData: Int,
    val displayTransform: FarmItemDisplayTransform,
    val displayScale: Float,
    val displayYOffset: Double,
)

data class FarmMoleBurrowSettings(
    val cells: Int,
    val maxBurrows: Int = 3,
    val minDepth: Int,
    val maxDepth: Int,
    val tunnelHeight: Int,
    val blocksPerTick: Int,
    val candidateAttempts: Int,
    val lightSpacing: Int,
    val lightLevel: Int,
    val lairVisual: FarmCareVisualSettings,
    val chamberCount: Int = 3,
    val moleCount: Int = 8,
    val guidanceCloseDistance: Int = 6,
    val guidanceFarDistance: Int = 14,
    val guidanceIntervalTicks: Int = 20,
    val decorationPercent: Int = 18,
    val tunnelWidth: Int = 2,
    val entranceMinBoundaryDistance: Int = 10,
)

data class FarmDamageSafetySettings(
    val maximumPercent: Int,
    val minimumRemaining: Int,
    val birdMaximum: Int,
    val pestMaximum: Int,
    val diseaseMaximum: Int,
)

data class FarmIrrigationSettings(
    val dryBlocksPerTick: Int,
    val waveBlocksPerTick: Int,
    val waveStartDelayTicks: Int,
    val ringIntervalTicks: Int,
    val ringWidth: Double,
    val particleSpacing: Double,
    val particleHeight: Double,
    val particleSpread: Double,
    val particleCount: Int,
)

data class FarmSupplyPointSettings(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
)

data class FarmSupplySettings(
    val tool: FarmSupplyPointSettings,
    val seeds: FarmSupplyPointSettings,
    val water: FarmSupplyPointSettings,
    val archery: FarmSupplyPointSettings,
    val fireEquipment: FarmSupplyPointSettings,
    val toolMaterial: String,
    val seedAmount: Int,
    val bowMaterial: String,
    val arrowMaterial: String,
    val arrowAmount: Int,
    val fireEquipmentMaterial: String,
    val fireEquipmentCustomModelData: Int,
    val fireEquipmentItemModel: String?,
    val itemScale: Float = 1.35f,
) {
    init {
        require(itemScale.isFinite() && itemScale in 0.5f..3.0f) { "farm supply item scale must be in 0.5..3.0" }
    }
}

data class FarmDeliverySettings(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val radius: Double,
    val crates: Int,
    val spawnRadius: Int,
    val minCrateSpacing: Double,
    val pickup: FarmSupplyPointSettings,
    val itemMaterial: String,
    val itemCustomModelData: Int,
    val displayTransform: FarmItemDisplayTransform,
    val displayScale: Float,
    val displayYOffset: Double,
    val carriedScale: Float,
    val carriedYOffset: Double,
    val displayViewRange: Float,
)

data class FarmRouteDeliverySettings(
    val sampleDistance: Double,
    val corridorRadius: Double,
    val hardResetDistance: Double,
    val hardCorrectionStrength: Double,
    val corridorCorrectionStrength: Double,
    val checkpointRadius: Double,
    val horseSpeed: Double,
    val horseJumpStrength: Double,
    val trailLookaheadPoints: Int,
    val trailHeight: Double,
    val trailParticleSize: Float,
    val trailSpacing: Double,
    val completionContribution: Int,
    val returnDelaySeconds: Int,
    val portalRightOffset: Double,
    val portalWidth: Float,
    val portalHeight: Float,
    val portalLabelHeight: Double,
    val portalLabelScale: Float,
    val portalArrivalSideOffset: Double,
    val ambushDistance: Double,
    val ambushMaxCount: Int,
    val ambushAfterFarmDistance: Double,
    val ambushEndSafeDistance: Double,
    val monsterMaxAlive: Int,
    val monsterSpawnDistance: Double,
    val monsterWaveMin: Int,
    val monsterWaveMax: Int,
    val monsterSpawnMinMultiplier: Double,
    val monsterSpawnMaxMultiplier: Double,
    val phantomSpawnHeight: Double,
    val monsterTypes: List<String>,
    val monsterMovementSpeed: Double,
    val monsterLightLevel: Int,
    val playerTime: Long,
    val timeTransitionSeconds: Int,
    val inactivityReminderSeconds: Int,
    val inactivityResetSeconds: Int,
    val inactivityMovementDistance: Double,
    val cartScale: Float,
    val cartBackOffset: Double,
    val cartYOffset: Double,
    val cartLoadCount: Int,
    val cartLoadSpacing: Double,
    val gunnerSeatYOffset: Double,
    val gunnerSeatBackOffset: Double,
    val gunnerInteractionWidth: Float,
    val gunnerInteractionHeight: Float,
    val rifleMaterial: String,
    val rifleCustomModelData: Int,
    val rifleItemModel: String?,
    val rifleDamage: Double,
    val rifleRange: Double,
    val rifleCooldownTicks: Int,
    val rifleRaySize: Double,
    val gunnerTrailLength: Int,
)

data class FarmPerkOfferSettings(
    val price: Long,
    val durationHours: Int,
)

data class FarmPerkSettings(
    val harvestArea: FarmPerkOfferSettings,
    val speed: FarmPerkOfferSettings,
    val sustenance: FarmPerkOfferSettings,
    val rewardBoost: FarmPerkOfferSettings,
    val rewardBonusPercent: Int,
    val sustainIntervalSeconds: Int,
    val speedAmplifier: Int = 0,
    val speedRefreshTicks: Int = 60,
    val sustenanceFood: Int = 2,
    val sustenanceSaturation: Float = 1.0f,
    val sustenanceHealth: Double = 1.0,
    val harvestAreaRadius: Int = 1,
) {
    init {
        require(speedAmplifier in 0..4) { "Farm speed perk amplifier must be in 0..4" }
        require(speedRefreshTicks in 20..200) { "Farm speed perk refresh ticks must be in 20..200" }
        require(sustenanceFood in 0..20) { "Farm sustenance food must be in 0..20" }
        require(sustenanceSaturation.isFinite() && sustenanceSaturation in 0.0f..20.0f) {
            "Farm sustenance saturation must be in 0.0..20.0"
        }
        require(sustenanceHealth.isFinite() && sustenanceHealth in 0.0..20.0) {
            "Farm sustenance health must be in 0.0..20.0"
        }
        require(harvestAreaRadius in 1..3) { "Farm harvest-area radius must be in 1..3" }
    }
}

data class FarmInputCooldownSettings(
    val supplyMillis: Long = 500L,
    val deliveryMillis: Long = 500L,
    val patchMissMillis: Long = 500L,
    val careMillis: Long = 100L,
    val careTargetMillis: Long = 250L,
    val moleMillis: Long = 500L,
    val frostPickupMillis: Long = 750L,
    val contractSceneMillis: Long = 700L,
) {
    init {
        require(listOf(
            supplyMillis, deliveryMillis, patchMissMillis, careMillis,
            careTargetMillis, moleMillis, frostPickupMillis, contractSceneMillis,
        ).all { it in 50L..5_000L }) { "Farm input cooldowns must be in 50..5000 milliseconds" }
    }
}

data class FarmOrderSettings(
    val id: String,
    val required: Map<String, Int>,
    val rarity: FarmContractRarity,
    val careTypes: List<FarmCareType>,
    val incidentTypes: List<FarmIncidentType>,
    val customerType: FarmCustomerType,
    val cartLoadMaterial: String,
    val cartLoadCustomModelData: Int,
)

data class MineZoneSettings(
    val id: String,
    val priority: Int,
    val reference: ZoneReference,
    val permission: String,
    val cartQuota: Int,
    val hazardTrigger: Int,
    val supportsRequired: Int,
    val restoreSeconds: Int,
    val temporaryMaterial: String,
    val baseMaterial: String,
    val materialWeights: LinkedHashMap<String, Int>,
    val engineVersion: Int = 1,
    val orders: List<MineOrderSettings> = emptyList(),
    val targetMultiplier: Int = 2,
    val incidentCountMin: Int = 3,
    val incidentCountMax: Int = 5,
    val rewards: FarmRewardSettings = defaultWorksiteRewards(125),
    val lostMinerDeliveryRadius: Double = 2.5,
    val lostMinerFollowSnapDistance: Double = 6.0,
    val lostMinerFollowOffsetZ: Double = -1.0,
    val extractionCheckpointRadius: Double = 1.6,
    val loadingDeliveryRadius: Double = 2.0,
    val cartVisual: MineCartVisualSettings = MineCartVisualSettings(),
) {
    init {
        require(engineVersion in 1..2) { "Mine zone $id engine-version must be 1 or 2" }
        require(targetMultiplier in 2..4) { "Mine zone $id target-multiplier must be 2..4" }
        require(incidentCountMin in 3..5 && incidentCountMax in incidentCountMin..5) {
            "Mine zone $id incident count range is invalid"
        }
        require(lostMinerDeliveryRadius in 1.0..6.0) {
            "Mine zone $id lost-miner delivery-radius is invalid"
        }
        require(lostMinerFollowSnapDistance in 2.0..16.0) {
            "Mine zone $id lost-miner follow-snap-distance is invalid"
        }
        require(lostMinerFollowOffsetZ in -3.0..3.0) {
            "Mine zone $id lost-miner follow-offset-z is invalid"
        }
        require(extractionCheckpointRadius in 0.75..4.0) {
            "Mine zone $id extraction checkpoint-radius is invalid"
        }
        require(loadingDeliveryRadius in 1.0..5.0) {
            "Mine zone $id loading delivery-radius is invalid"
        }
        require(engineVersion == 1 || orders.isNotEmpty()) { "Mine V2 zone $id has no orders" }
        require(orders.all { it.incidentTypes.size >= incidentCountMax }) {
            "Mine zone $id order has fewer incidents than incident-count-max"
        }
    }
}

data class MenuBackgroundSettings(
    val enabled: Boolean,
    val material: String,
    val customModelData: Int,
)

data class FarmScoreboardSettings(
    val enabled: Boolean,
    val provider: FarmScoreboardProvider,
    val replaceExisting: Boolean,
)

enum class FarmScoreboardProvider { BUKKIT, TAB }

class ArcFarmsConfig private constructor(
    val enabled: Boolean,
    val serverId: String,
    val network: NetworkSettings,
    val defaultLocale: String,
    val useClientLocale: Boolean,
    val bossbars: Boolean,
    val particles: Boolean,
    val sounds: Boolean,
    val titleStaySeconds: Int,
    val markerHeight: Int,
    val missingBedHighlightThreshold: Int,
    val taskHintCooldownMillis: Long = 900L,
    val taskTitleCooldownMillis: Long = 8_000L,
    val farmScoreboard: FarmScoreboardSettings,
    val menuBackground: MenuBackgroundSettings,
    val saveSeconds: Int,
    val completedCooldownSeconds: Int,
    val debug: DebugSettings,
    val destinations: Map<String, TeleportDestination>,
    val farms: List<FarmZoneSettings>,
    val lumbermills: List<LumberZoneSettings>,
    val mines: List<MineZoneSettings>,
) {
    init {
        require(taskHintCooldownMillis in 250L..5_000L) { "ui.task-hint-cooldown-millis must be in 250..5000" }
        require(taskTitleCooldownMillis in 1_000L..30_000L) { "ui.task-title-cooldown-millis must be in 1000..30000" }
    }

    val requiresWorldGuard: Boolean = buildList {
        addAll(farms.map(FarmZoneSettings::reference))
        lumbermills.forEach { add(it.reference); add(it.station) }
        addAll(mines.map(MineZoneSettings::reference))
    }.any { it.region != null }

    companion object {
        private const val MAX_CUSTOM_MODEL_DATA = Int.MAX_VALUE
        private val ENVIRONMENT_OWNED_ROOT_KEYS = setOf("farm-zones", "lumber-zones", "mine-zones")

        /** Always reads the accepted on-disk snapshot; hot reload must not leave a stale startup cache behind. */
        fun load(dataRoot: Path): ArcFarmsConfig = synchronizeAndParse(Config(dataRoot, "config.yml"))

        fun inspect(dataRoot: Path): ArcFarmsConfig = parse(Config(dataRoot, "config.yml"))

        fun synchronize(dataRoot: Path): ArcFarmsConfig = synchronizeAndParse(Config(dataRoot, "config.yml"))

        private fun synchronizeAndParse(config: Config): ArcFarmsConfig {
            config.mergeMissingFromBundled("config.yml", ENVIRONMENT_OWNED_ROOT_KEYS)
            return parse(config).also { config.saveStrict() }
        }

        private fun parse(config: Config): ArcFarmsConfig {
            val serverId = serverId(config.string("server-id", "spawn"), "server-id")
            val defaultLocale = config.string("locale.default", "ru").lowercase()
            require(defaultLocale in setOf("ru", "en")) { "locale.default must be ru or en" }
            val farms = config.keys("farm-zones").sorted().mapNotNull { id ->
                val section = config.section("farm-zones.$id")
                if (!section.boolean("enabled", true)) return@mapNotNull null
                validateId(id, "farm zone")
                val crops = section.stringList("crops").map(::materialName).toSet()
                require(crops.isNotEmpty()) { "Farm zone $id has no crops" }
                val cropLayoutWeights = section.keys("crop-layout.weights").sorted().associate { rawCrop ->
                    val crop = materialName(rawCrop)
                    crop to section.int("crop-layout.weights.$rawCrop", 0)
                        .checked("crop-layout.weights.$rawCrop", 1, 10_000)
                }.ifEmpty {
                    linkedMapOf(
                        "WHEAT" to 30,
                        "CARROTS" to 25,
                        "POTATOES" to 25,
                        "BEETROOTS" to 15,
                        "SWEET_BERRY_BUSH" to 1,
                    ).filterKeys(crops::contains)
                }
                require(cropLayoutWeights.isNotEmpty()) {
                    "Farm zone $id crop-layout.weights has no configured ordinary crop"
                }
                require(cropLayoutWeights.keys.all(crops::contains)) {
                    "Farm zone $id crop-layout.weights contains a crop outside farm-zones.$id.crops"
                }
                require(cropLayoutWeights.keys.none { it == "MELON" || it == "PUMPKIN" }) {
                    "Farm zone $id crop layout must not manage fixed melon or pumpkin blocks"
                }
                val cropLayout = FarmCropLayoutSettings(
                    enabled = section.boolean("crop-layout.enabled", true),
                    weights = cropLayoutWeights,
                    smallComponentMaxSize = section.int("crop-layout.small-component-max-size", 16)
                        .checked("crop-layout.small-component-max-size", 0, 256),
                    smallComponentMergeDistance = section.int("crop-layout.small-component-merge-distance", 10)
                        .checked("crop-layout.small-component-merge-distance", 0, 64),
                )
                val reference = parseReference(section, "", id)
                val incidentTypes = section.stringList("incident-types")
                    .ifEmpty { listOf(FarmIncidentType.PESTS.name, FarmIncidentType.DROUGHT.name) }
                    .map { value ->
                        runCatching { FarmIncidentType.valueOf(value.trim().uppercase()) }
                            .getOrElse { error("Farm zone $id has unknown incident type: $value") }
                    }
                    .distinct()
                require(incidentTypes.isNotEmpty()) { "Farm zone $id has no incident types" }
                val careTypes = section.stringList("care-types")
                    .ifEmpty { FarmCareType.entries.filterNot { it == FarmCareType.SEEDER }.map(FarmCareType::name) }
                    .map { value ->
                        runCatching { FarmCareType.valueOf(value.trim().uppercase()) }
                            .getOrElse { error("Farm zone $id has unknown care type: $value") }
                    }
                    .distinct()
                require(careTypes.isNotEmpty()) { "Farm zone $id has no care types" }
                require(FarmCareType.SEEDER !in careTypes) {
                    "Farm zone $id must configure the seeder through seeder-every-shifts, not care-types"
                }
                val rareOrderChancePercent = section.int("rare-order-chance-percent", 15)
                    .checked("rare-order-chance-percent", 0, 100)
                val orders = section.keys("orders").sorted().map { orderId ->
                    validateId(orderId, "farm order")
                    val path = "orders.$orderId"
                    val required = parseWeightedList(section.stringList("$path.crops"), "farm order $orderId crops")
                    require(required.keys.all(crops::contains)) { "Farm order $orderId contains a crop outside farm-zones.$id.crops" }
                    val orderCareTypes = section.stringList("$path.care-types").map { value ->
                        runCatching { FarmCareType.valueOf(value.trim().uppercase()) }
                            .getOrElse { error("Farm order $orderId has unknown care type: $value") }
                    }.distinct()
                    require(orderCareTypes.isNotEmpty() && FarmCareType.SEEDER !in orderCareTypes) {
                        "Farm order $orderId must define non-seeder care types"
                    }
                    require(orderCareTypes.all(careTypes::contains)) {
                        "Farm order $orderId uses a care type disabled in farm-zones.$id.care-types"
                    }
                    val orderIncidentTypes = section.stringList("$path.incident-types").map { value ->
                        runCatching { FarmIncidentType.valueOf(value.trim().uppercase()) }
                            .getOrElse { error("Farm order $orderId has unknown incident type: $value") }
                    }.distinct()
                    require(orderIncidentTypes.isNotEmpty() && orderIncidentTypes.all(incidentTypes::contains)) {
                        "Farm order $orderId must use incident types enabled in farm-zones.$id.incident-types"
                    }
                    require(orderIncidentTypes.size >= minOf(2, incidentTypes.size)) {
                        "Farm order $orderId must define both incident types so repeated incidents stay varied"
                    }
                    FarmOrderSettings(
                        id = orderId,
                        required = required.toMap(),
                        rarity = runCatching {
                            FarmContractRarity.valueOf(section.string("$path.rarity").trim().uppercase())
                        }.getOrElse { error("Farm order $orderId has unknown rarity") },
                        careTypes = orderCareTypes,
                        incidentTypes = orderIncidentTypes,
                        customerType = runCatching {
                            FarmCustomerType.valueOf(section.string("$path.customer").trim().uppercase())
                        }.getOrElse { error("Farm order $orderId has unknown customer") },
                        cartLoadMaterial = materialName(section.string("$path.cart-load.material")),
                        cartLoadCustomModelData = section.int("$path.cart-load.custom-model-data", 0)
                            .checked("$path.cart-load.custom-model-data", 0, MAX_CUSTOM_MODEL_DATA),
                    )
                }
                require(orders.isNotEmpty()) { "Farm zone $id has no orders" }
                require(orders.any { it.rarity == FarmContractRarity.COMMON }) { "Farm zone $id has no common orders" }
                require(rareOrderChancePercent == 0 || orders.any { it.rarity == FarmContractRarity.RARE }) {
                    "Farm zone $id enables rare contracts without rare orders"
                }
                val careVisualDefaults = mapOf(
                    FarmCareRole.WEED_ROOT to "MANGROVE_ROOTS",
                    FarmCareRole.SEEDER_HORSE to "SADDLE",
                    FarmCareRole.SEEDER_WAYPOINT to "WHEAT_SEEDS",
                    FarmCareRole.VALVE to "TRIPWIRE_HOOK",
                    FarmCareRole.HIVE to "BEE_NEST",
                    FarmCareRole.FLOWER_PATCH to "SUNFLOWER",
                    FarmCareRole.APPLE to "APPLE",
                    FarmCareRole.COVER_ANCHOR to "WHITE_CARPET",
                    FarmCareRole.SCARECROW to "CARVED_PUMPKIN",
                    FarmCareRole.ANIMAL to "WHEAT_SEEDS",
                    FarmCareRole.PEN to "OAK_FENCE_GATE",
                    FarmCareRole.DISEASED_CROP to "FERMENTED_SPIDER_EYE",
                    FarmCareRole.MOLE_MOUND to "MUD",
                )
                val careVisuals = careVisualDefaults.mapValues { (role, defaultMaterial) ->
                    val path = "care-visuals.${role.name.lowercase().replace('_', '-')}"
                    FarmCareVisualSettings(
                        material = materialName(section.string("$path.material", defaultMaterial)),
                        customModelData = section.int("$path.custom-model-data", 0)
                            .checked("$path.custom-model-data", 0, MAX_CUSTOM_MODEL_DATA),
                        displayTransform = section.string("$path.display-transform", "FIXED")
                            .trim()
                            .uppercase()
                            .let { raw ->
                                FarmItemDisplayTransform.entries.firstOrNull { it.name == raw }
                                    ?: error("$path.display-transform must be GROUND, FIXED, or HEAD")
                            },
                        displayScale = section.finiteFloat("$path.display-scale", 1.0f, 0.05f, 8.0f),
                        displayYOffset = section.finiteDouble("$path.display-y-offset", 0.45, -4.0, 4.0),
                    )
                }
                val contractCartPath = "contract-scene.cart"
                val contractCartVisual = FarmContractCartVisualSettings(
                    material = materialName(section.string("$contractCartPath.material", "MINECART")),
                    customModelData = section.int("$contractCartPath.custom-model-data", 0)
                        .checked("$contractCartPath.custom-model-data", 0, MAX_CUSTOM_MODEL_DATA),
                    displayTransform = section.string("$contractCartPath.display-transform", "GROUND")
                        .trim()
                        .uppercase()
                        .let { raw ->
                            FarmItemDisplayTransform.entries.firstOrNull { it.name == raw }
                                ?: error("$contractCartPath.display-transform must be GROUND, FIXED, or HEAD")
                        },
                    scale = section.finiteFloat("$contractCartPath.scale", 1.0f, 0.05f, 8.0f),
                    yOffset = section.finiteDouble("$contractCartPath.y-offset", 0.15, -4.0, 4.0),
                    yawOffset = section.finiteFloat("$contractCartPath.yaw-offset", 0.0f, -360.0f, 360.0f),
                    loadYOffset = section.finiteDouble("$contractCartPath.load-y-offset", 0.4, -2.0, 4.0),
                    loadScale = section.finiteFloat("$contractCartPath.load-scale", 1.1f, 0.05f, 4.0f),
                    viewRange = section.finiteFloat("$contractCartPath.view-range", 2.0f, 0.25f, 8.0f),
                )
                val delivery = parseFarmDelivery(section, reference.world, id)
                val processingVisualDefaults = mapOf(
                    FarmProcessingVisualRole.MACHINE to "CRAFTING_TABLE",
                    FarmProcessingVisualRole.WHEEL to "GRINDSTONE",
                    FarmProcessingVisualRole.INPUT_RACK to "BARREL",
                    FarmProcessingVisualRole.OUTPUT_PALLET to "BARREL",
                    FarmProcessingVisualRole.RAW_PACKAGE to "WHEAT",
                    FarmProcessingVisualRole.PRODUCT_PACKAGE to "BREAD",
                )
                val processing = FarmProcessingSettings(
                    inputPackages = section.int("processing.input-packages", 4)
                        .checked("processing.input-packages", 1, 16),
                    machineCycles = section.int("processing.machine-cycles", 6)
                        .checked("processing.machine-cycles", 1, 32),
                    outputPackages = section.int("processing.output-packages", 4)
                        .checked("processing.output-packages", 1, 16),
                    interactionRadius = section.finiteDouble("processing.interaction-radius", 2.2, 1.0, 5.0),
                    proximityPickupRadius = section.finiteDouble("processing.proximity-pickup-radius", 1.75, 0.5, 4.0),
                    deliveryRadius = section.finiteDouble("processing.delivery-radius", 2.4, 1.0, 5.0),
                    crankInnerRadius = section.finiteDouble("processing.crank.inner-radius", 2.4, 0.5, 4.0),
                    crankOuterRadius = section.finiteDouble("processing.crank.outer-radius", 4.0, 1.0, 6.0),
                    crankRadiusTolerance = section.finiteDouble("processing.crank.radius-tolerance", 1.0, 0.0, 2.0),
                    crankMaxStepDistance = section.finiteDouble("processing.crank.max-step-distance", 1.2, 0.25, 4.0),
                    crankTitleReminderSeconds = section.int("processing.crank.title-reminder-seconds", 8)
                        .checked("processing.crank.title-reminder-seconds", 3, 30),
                    carriedYOffset = section.finiteDouble("processing.carried-y-offset", 0.95, 0.0, 3.0),
                    cargoReminderSeconds = section.int("processing.cargo-watchdog.reminder-seconds", 12)
                        .checked("processing.cargo-watchdog.reminder-seconds", 5, 60),
                    cargoReturnSeconds = section.int("processing.cargo-watchdog.return-seconds", 30)
                        .checked("processing.cargo-watchdog.return-seconds", 10, 180),
                    cargoProgressDistance = section.finiteDouble("processing.cargo-progress-distance", 1.0, 0.25, 3.0),
                    crankVerticalTolerance = section.finiteDouble("processing.crank.vertical-tolerance", 2.5, 0.5, 6.0),
                    spawnPerTick = section.int("processing.spawn-per-tick", 4)
                        .checked("processing.spawn-per-tick", 1, 16),
                    displayViewRange = section.finiteFloat("processing.display-view-range", 3.0f, 0.25f, 8.0f),
                    visuals = processingVisualDefaults.mapValues { (role, defaultMaterial) ->
                        val path = "processing.visuals.${role.name.lowercase().replace('_', '-')}"
                        val itemModel = section.string("$path.item-model", "").trim().ifEmpty { null }
                        itemModel?.let { model ->
                            require(model.matches(Regex("[a-z0-9._-]+:[a-z0-9/._-]+"))) {
                                "$path.item-model must be a namespaced item model"
                            }
                        }
                        FarmProcessingVisualSettings(
                            material = materialName(section.string("$path.material", defaultMaterial)),
                            customModelData = section.int("$path.custom-model-data", 0)
                                .checked("$path.custom-model-data", 0, MAX_CUSTOM_MODEL_DATA),
                            itemModel = itemModel,
                            displayTransform = section.string("$path.display-transform", "FIXED")
                                .trim().uppercase().let { raw ->
                                    FarmItemDisplayTransform.entries.firstOrNull { it.name == raw }
                                        ?: error("$path.display-transform must be GROUND, FIXED, or HEAD")
                                },
                            scale = section.finiteFloat("$path.scale", 1.0f, 0.05f, 8.0f),
                            yOffset = section.finiteDouble("$path.y-offset", 0.0, -4.0, 4.0),
                            yawOffset = section.finiteFloat("$path.yaw-offset", 0.0f, -360.0f, 360.0f),
                        )
                    },
                )
                val barnFireHotspotCount = section.int("barn-fire.hotspots", 24)
                    .checked("barn-fire.hotspots", 1, 256)
                val barnFire = FarmBarnFireSettings(
                    hotspotCount = barnFireHotspotCount,
                    initialHotspotCount = section.int("barn-fire.initial-hotspots", minOf(6, barnFireHotspotCount))
                        .checked("barn-fire.initial-hotspots", 1, barnFireHotspotCount),
                    spreadIntervalTicks = section.int("barn-fire.spread.interval-ticks", 40)
                        .checked("barn-fire.spread.interval-ticks", 10, 400),
                    spreadHotspotsPerPulse = section.int("barn-fire.spread.hotspots-per-pulse", 1)
                        .checked("barn-fire.spread.hotspots-per-pulse", 1, 8),
                    spawnPerTick = section.int("barn-fire.spawn-per-tick", 8)
                        .checked("barn-fire.spawn-per-tick", 1, 32),
                    placementRadius = section.int("barn-fire.placement-radius", 16)
                        .checked("barn-fire.placement-radius", 2, 32),
                    minSpacing = section.finiteDouble("barn-fire.min-spacing", 1.4, 1.0, 8.0),
                    verticalSearch = section.int("barn-fire.vertical-search", 5)
                        .checked("barn-fire.vertical-search", 1, 12),
                    sprayRange = section.finiteDouble("barn-fire.spray.range", 18.0, 4.0, 32.0),
                    sprayHitRadius = section.finiteDouble("barn-fire.spray.hit-radius", 1.6, 0.5, 4.0),
                    sprayCooldownTicks = section.int("barn-fire.spray.cooldown-ticks", 5)
                        .checked("barn-fire.spray.cooldown-ticks", 1, 40),
                    particleStep = section.finiteDouble("barn-fire.spray.particle-step", 0.55, 0.2, 2.0),
                    flameParticleIntervalTicks = section.int("barn-fire.flame-particle-interval-ticks", 5)
                        .checked("barn-fire.flame-particle-interval-ticks", 1, 40),
                    particleHotspotLimit = section.int("barn-fire.particle-hotspot-limit", 24)
                        .checked("barn-fire.particle-hotspot-limit", 0, 64),
                    waterSideStreams = section.int("barn-fire.spray.side-streams", 4)
                        .checked("barn-fire.spray.side-streams", 1, 8),
                )
                val supplies = parseFarmSupplies(section, reference.world, id)
                val routeDelivery = FarmRouteDeliverySettings(
                    sampleDistance = section.finiteDouble("route-delivery.sample-distance", 2.5, 1.0, 8.0),
                    corridorRadius = section.finiteDouble("route-delivery.corridor-radius", 5.0, 2.0, 16.0),
                    hardResetDistance = section.finiteDouble("route-delivery.hard-reset-distance", 9.0, 3.0, 32.0),
                    hardCorrectionStrength = section.finiteDouble(
                        "route-delivery.hard-correction-strength", 0.42, 0.0, 1.0,
                    ),
                    corridorCorrectionStrength = section.finiteDouble(
                        "route-delivery.corridor-correction-strength", 0.24, 0.0, 1.0,
                    ),
                    checkpointRadius = section.finiteDouble("route-delivery.checkpoint-radius", 8.0, 2.0, 16.0),
                    horseSpeed = section.finiteDouble("route-delivery.horse-speed", 0.26, 0.1, 0.6),
                    horseJumpStrength = section.finiteDouble(
                        "route-delivery.horse-jump-strength", 0.45, 0.0, 1.0,
                    ),
                    trailLookaheadPoints = section.int("route-delivery.trail.lookahead-points", 28)
                        .checked("route-delivery.trail.lookahead-points", 4, 96),
                    trailHeight = section.finiteDouble("route-delivery.trail.height", 0.35, 0.05, 2.0),
                    trailParticleSize = section.finiteFloat("route-delivery.trail.particle-size", 1.15f, 0.5f, 3.0f),
                    trailSpacing = section.finiteDouble("route-delivery.trail.spacing", 0.7, 0.25, 2.0),
                    completionContribution = section.int("route-delivery.completion-contribution", 12)
                        .checked("route-delivery.completion-contribution", 1, 64),
                    returnDelaySeconds = section.int("route-delivery.return-delay-seconds", 3)
                        .checked("route-delivery.return-delay-seconds", 1, 15),
                    portalRightOffset = section.finiteDouble(
                        "route-delivery.portal.right-offset", 4.0, -12.0, 12.0,
                    ),
                    portalWidth = section.finiteFloat("route-delivery.portal.width", 3.6f, 1.0f, 8.0f),
                    portalHeight = section.finiteFloat("route-delivery.portal.height", 3.2f, 1.0f, 8.0f),
                    portalLabelHeight = section.finiteDouble(
                        "route-delivery.portal.label-height", 3.35, 1.0, 8.0,
                    ),
                    portalLabelScale = section.finiteFloat(
                        "route-delivery.portal.label-scale", 1.8f, 0.5f, 4.0f,
                    ),
                    portalArrivalSideOffset = section.finiteDouble(
                        "route-delivery.portal.arrival-side-offset", 3.0, 1.0, 6.0,
                    ),
                    ambushDistance = section.finiteDouble(
                        "route-delivery.monsters.distance-per-ambush", 120.0, 32.0, 512.0,
                    ),
                    ambushMaxCount = section.int("route-delivery.monsters.max-ambushes", 3)
                        .checked("route-delivery.monsters.max-ambushes", 0, 8),
                    ambushAfterFarmDistance = section.finiteDouble(
                        "route-delivery.monsters.after-farm-distance", 20.0, 0.0, 128.0,
                    ),
                    ambushEndSafeDistance = section.finiteDouble(
                        "route-delivery.monsters.end-safe-distance", 20.0, 0.0, 128.0,
                    ),
                    monsterMaxAlive = section.int("route-delivery.monsters.max-alive", 4)
                        .checked("route-delivery.monsters.max-alive", 0, 16),
                    monsterSpawnDistance = section.finiteDouble("route-delivery.monsters.spawn-distance", 10.0, 4.0, 24.0),
                    monsterWaveMin = section.int("route-delivery.monsters.wave-min", 3)
                        .checked("route-delivery.monsters.wave-min", 1, 12),
                    monsterWaveMax = section.int("route-delivery.monsters.wave-max", 5)
                        .checked("route-delivery.monsters.wave-max", 1, 16),
                    monsterSpawnMinMultiplier = section.finiteDouble(
                        "route-delivery.monsters.spawn-min-multiplier", 0.8, 0.1, 2.0,
                    ),
                    monsterSpawnMaxMultiplier = section.finiteDouble(
                        "route-delivery.monsters.spawn-max-multiplier", 1.15, 0.1, 3.0,
                    ),
                    phantomSpawnHeight = section.finiteDouble(
                        "route-delivery.monsters.phantom-spawn-height", 7.0, 1.0, 32.0,
                    ),
                    monsterTypes = section.stringList("route-delivery.monsters.types")
                        .ifEmpty { listOf("HUSK", "ZOMBIE", "SKELETON", "SPIDER", "PHANTOM") }
                        .map(String::trim)
                        .map(String::uppercase)
                        .distinct()
                        .also { types ->
                            require(types.isNotEmpty() && types.all { it in ROUTE_MONSTER_TYPES }) {
                                "route-delivery.monsters.types contains an unsupported entity"
                            }
                        },
                    monsterMovementSpeed = section.finiteDouble(
                        "route-delivery.monsters.movement-speed", 0.25, 0.1, 0.5,
                    ),
                    monsterLightLevel = section.int("route-delivery.monsters.light-level", 15)
                        .checked("route-delivery.monsters.light-level", 0, 15),
                    playerTime = section.string("route-delivery.player-time", "18000")
                        .toLongOrNull()?.also {
                            require(it in 0..24_000) { "route-delivery.player-time must be in 0..24000" }
                        } ?: error("route-delivery.player-time must be an integer"),
                    timeTransitionSeconds = section.int("route-delivery.transition-seconds", 18)
                        .checked("route-delivery.transition-seconds", 1, 60),
                    inactivityReminderSeconds = section.int("route-delivery.inactivity.reminder-seconds", 20)
                        .checked("route-delivery.inactivity.reminder-seconds", 5, 120),
                    inactivityResetSeconds = section.int("route-delivery.inactivity.reset-seconds", 45)
                        .checked("route-delivery.inactivity.reset-seconds", 15, 300),
                    inactivityMovementDistance = section.finiteDouble(
                        "route-delivery.inactivity.movement-distance", 2.0, 1.0, 8.0,
                    ),
                    cartScale = section.finiteFloat("route-delivery.cart-scale", 4.4f, 0.5f, 8.0f),
                    cartBackOffset = section.finiteDouble("route-delivery.cart-back-offset", 2.15, 1.5, 3.5),
                    cartYOffset = section.finiteDouble("route-delivery.cart-y-offset", 0.875, -2.0, 2.0),
                    cartLoadCount = section.int("route-delivery.cart-load-count", 4)
                        .checked("route-delivery.cart-load-count", 1, 8),
                    cartLoadSpacing = section.finiteDouble("route-delivery.cart-load-spacing", 0.24, 0.05, 0.75),
                    gunnerSeatYOffset = section.finiteDouble("route-delivery.gunner.seat-y-offset", -0.15, -2.0, 3.0),
                    gunnerSeatBackOffset = section.finiteDouble(
                        "route-delivery.gunner.seat-back-offset", 0.65, 0.0, 2.0,
                    ),
                    gunnerInteractionWidth = section.finiteFloat(
                        "route-delivery.gunner.interaction-width", 2.8f, 0.5f, 6.0f,
                    ),
                    gunnerInteractionHeight = section.finiteFloat(
                        "route-delivery.gunner.interaction-height", 0.7f, 0.4f, 4.0f,
                    ),
                    rifleMaterial = materialName(section.string("route-delivery.gunner.material", "CROSSBOW")),
                    rifleCustomModelData = section.int("route-delivery.gunner.custom-model-data", 2_100_103)
                        .checked("route-delivery.gunner.custom-model-data", 0, MAX_CUSTOM_MODEL_DATA),
                    rifleItemModel = section.string("route-delivery.gunner.item-model", "").trim().ifEmpty { null }
                        ?.also { model ->
                            require(model.matches(Regex("[a-z0-9._-]+:[a-z0-9/._-]+"))) {
                                "route-delivery.gunner.item-model must be a namespaced item model"
                            }
                        },
                    rifleDamage = section.finiteDouble("route-delivery.gunner.damage", 7.0, 1.0, 40.0),
                    rifleRange = section.finiteDouble("route-delivery.gunner.range", 42.0, 8.0, 96.0),
                    rifleCooldownTicks = section.int("route-delivery.gunner.cooldown-ticks", 6)
                        .checked("route-delivery.gunner.cooldown-ticks", 2, 100),
                    rifleRaySize = section.finiteDouble("route-delivery.gunner.ray-size", 0.65, 0.1, 2.0),
                    gunnerTrailLength = section.int("route-delivery.gunner.trail-length", 10)
                        .checked("route-delivery.gunner.trail-length", 0, 32),
                ).also {
                    require(it.hardResetDistance > it.corridorRadius) {
                        "farm-zones.$id route hard-reset-distance must exceed corridor-radius"
                    }
                    require(it.hardCorrectionStrength >= it.corridorCorrectionStrength) {
                        "farm-zones.$id route hard correction must be at least corridor correction"
                    }
                    require(it.monsterSpawnMinMultiplier <= it.monsterSpawnMaxMultiplier) {
                        "farm-zones.$id monster spawn multiplier range is invalid"
                    }
                    require(it.monsterWaveMin <= it.monsterWaveMax)
                    require(it.monsterMaxAlive == 0 || it.ambushMaxCount > 0)
                    require(it.inactivityReminderSeconds < it.inactivityResetSeconds) {
                        "farm-zones.$id route inactivity reminder must happen before reset"
                    }
                }
                fun perk(path: String, price: Long, hours: Int) = FarmPerkOfferSettings(
                    price = section.string("perks.$path.price", price.toString()).toLongOrNull()
                        ?.also { require(it in 1..1_000_000) { "farm-zones.$id perks.$path.price is invalid" } }
                        ?: error("farm-zones.$id perks.$path.price must be an integer"),
                    durationHours = section.int("perks.$path.duration-hours", hours)
                        .checked("perks.$path.duration-hours", 1, 24 * 30),
                )
                val perks = FarmPerkSettings(
                    harvestArea = perk("harvest-area", 250, 72),
                    speed = perk("speed", 180, 72),
                    sustenance = perk("sustenance", 150, 72),
                    rewardBoost = perk("reward-boost", 400, 72),
                    rewardBonusPercent = section.int("perks.reward-boost.bonus-percent", 25)
                        .checked("perks.reward-boost.bonus-percent", 1, 100),
                    sustainIntervalSeconds = section.int("perks.sustenance.interval-seconds", 5)
                        .checked("perks.sustenance.interval-seconds", 1, 60),
                    speedAmplifier = section.int("perks.speed.amplifier", 0)
                        .checked("perks.speed.amplifier", 0, 4),
                    speedRefreshTicks = section.int("perks.speed.refresh-ticks", 60)
                        .checked("perks.speed.refresh-ticks", 20, 200),
                    sustenanceFood = section.int("perks.sustenance.food", 2)
                        .checked("perks.sustenance.food", 0, 20),
                    sustenanceSaturation = section.finiteFloat("perks.sustenance.saturation", 1.0f, 0.0f, 20.0f),
                    sustenanceHealth = section.finiteDouble("perks.sustenance.health", 1.0, 0.0, 20.0),
                    harvestAreaRadius = section.int("perks.harvest-area.radius", 1)
                        .checked("perks.harvest-area.radius", 1, 3),
                )
                reference.bounds?.let { bounds ->
                    require(bounds.contains(floor(delivery.x).toInt(), floor(delivery.y).toInt(), floor(delivery.z).toInt())) {
                        "Farm zone $id delivery point is outside its bounds"
                    }
                    listOf(supplies.tool, supplies.seeds, supplies.water, delivery.pickup).forEach { point ->
                        require(bounds.contains(floor(point.x).toInt(), floor(point.y).toInt(), floor(point.z).toInt())) {
                            "Farm zone $id supply point is outside its bounds"
                        }
                    }
                }
                val droughtPatches = section.int("drought-patches", 3).checked("drought-patches", 1, 8)
                val droughtCoveragePercent = section.int("drought-coverage-percent", 35)
                    .checked("drought-coverage-percent", 1, 100)
                val droughtMinBeds = section.int("drought-min-beds", 30).checked("drought-min-beds", 1, 64)
                val droughtMaxBeds = section.int("drought-max-beds", 40).checked("drought-max-beds", 1, 64)
                require(droughtMinBeds <= droughtMaxBeds) {
                    "Farm zone $id drought-min-beds must not exceed drought-max-beds"
                }
                val droughtInitialBeds = section.int("drought-initial-beds", minOf(10, droughtMaxBeds))
                    .checked("drought-initial-beds", 1, 64)
                require(droughtInitialBeds <= droughtMaxBeds) {
                    "Farm zone $id drought-initial-beds must not exceed drought-max-beds"
                }
                val preparationPatchSize = section.int("preparation-patch-size", 100)
                    .checked("preparation-patch-size", 1, 512)
                val preparationPatchMaxSize = section.int("preparation-patch-max-size", 160)
                    .checked("preparation-patch-max-size", 1, 512)
                require(preparationPatchMaxSize >= preparationPatchSize) {
                    "Farm zone $id preparation-patch-max-size must be at least preparation-patch-size"
                }
                val seederPatchSize = section.int("seeder-patch-size", 3_840)
                    .checked("seeder-patch-size", 1, MAX_FARM_PATCH_PLOTS)
                val seederPatchMaxSize = section.int("seeder-patch-max-size", 6_144)
                    .checked("seeder-patch-max-size", 1, MAX_FARM_PATCH_PLOTS)
                require(seederPatchMaxSize >= seederPatchSize) {
                    "Farm zone $id seeder-patch-max-size must be at least seeder-patch-size"
                }
                val seederPigCount = section.int("seeder-pig-count", 3)
                    .checked("seeder-pig-count", 1, 5)
                val seederPigLeadDistance = section.finiteDouble("seeder-pig-lead-distance", 2.5, 1.0, 6.0)
                val seederPigSpacing = section.finiteDouble("seeder-pig-spacing", 1.4, 0.6, 3.0)
                val seederPigCatchupDistance = section.finiteDouble("seeder-pig-catchup-distance", 12.0, 3.0, 16.0)
                require(seederPigCatchupDistance > seederPigLeadDistance) {
                    "Farm zone $id seeder-pig-catchup-distance must exceed seeder-pig-lead-distance"
                }
                val placementMinObjectiveDistance = section.int("placement-min-objective-distance", 10)
                    .checked("placement-min-objective-distance", 2, 32)
                val placementMaxPlayerDistance = section.int("placement-max-player-distance", 28)
                    .checked("placement-max-player-distance", 4, 64)
                val placementSearchRadius = section.int("placement-search-radius", 32)
                    .checked("placement-search-radius", 4, 64)
                require(placementMinObjectiveDistance < placementMaxPlayerDistance) {
                    "Farm zone $id placement-min-objective-distance must be below placement-max-player-distance"
                }
                require(placementSearchRadius >= placementMinObjectiveDistance) {
                    "Farm zone $id placement-search-radius must reach placement-min-objective-distance"
                }
                val diseaseInitialSpots = section.int("disease-initial-spots", 2)
                    .checked("disease-initial-spots", 1, 16)
                val diseaseMaxSpots = section.int("disease-max-spots", 10)
                    .checked("disease-max-spots", 1, 32)
                require(diseaseInitialSpots <= diseaseMaxSpots) {
                    "Farm zone $id disease-initial-spots must not exceed disease-max-spots"
                }
                val moleBurrowMinDepth = section.int("mole-burrow.min-depth", 10)
                    .checked("mole-burrow.min-depth", 4, 64)
                val moleBurrowMaxDepth = section.int("mole-burrow.max-depth", 18)
                    .checked("mole-burrow.max-depth", 4, 64)
                require(moleBurrowMinDepth <= moleBurrowMaxDepth) {
                    "farm-zones.$id mole-burrow.min-depth must not exceed max-depth"
                }
                val moleLairPath = "mole-burrow.lair-visual"
                val moleBurrow = FarmMoleBurrowSettings(
                    cells = section.int("mole-burrow.cells", 8).checked("mole-burrow.cells", 3, 11),
                    maxBurrows = section.int("mole-burrow.max-burrows", 3)
                        .checked("mole-burrow.max-burrows", 1, 3),
                    minDepth = moleBurrowMinDepth,
                    maxDepth = moleBurrowMaxDepth,
                    tunnelHeight = section.int("mole-burrow.tunnel-height", 3)
                        .checked("mole-burrow.tunnel-height", 2, 4),
                    blocksPerTick = section.int("mole-burrow.blocks-per-tick", 256)
                        .checked("mole-burrow.blocks-per-tick", 8, 256),
                    candidateAttempts = section.int("mole-burrow.candidate-attempts", 12)
                        .checked("mole-burrow.candidate-attempts", 1, FarmCarePlanner.MAX_SPREAD_TARGETS),
                    lightSpacing = section.int("mole-burrow.light-spacing", 5)
                        .checked("mole-burrow.light-spacing", 2, 16),
                    lightLevel = section.int("mole-burrow.light-level", 11)
                        .checked("mole-burrow.light-level", 1, 15),
                    lairVisual = FarmCareVisualSettings(
                        material = materialName(section.string("$moleLairPath.material", "RABBIT_HIDE")),
                        customModelData = section.int("$moleLairPath.custom-model-data", 0)
                            .checked("$moleLairPath.custom-model-data", 0, MAX_CUSTOM_MODEL_DATA),
                        displayTransform = section.string("$moleLairPath.display-transform", "FIXED")
                            .trim().uppercase().let { raw ->
                                FarmItemDisplayTransform.entries.firstOrNull { it.name == raw }
                                    ?: error("$moleLairPath.display-transform must be GROUND, FIXED, or HEAD")
                            },
                        displayScale = section.finiteFloat("$moleLairPath.display-scale", 1.6f, 0.05f, 8.0f),
                        displayYOffset = section.finiteDouble("$moleLairPath.display-y-offset", 0.6, -4.0, 4.0),
                    ),
                    chamberCount = section.int("mole-burrow.chambers", 3)
                        .checked("mole-burrow.chambers", 0, 8),
                    moleCount = section.int("mole-burrow.moles", 8)
                        .checked("mole-burrow.moles", 1, 24),
                    guidanceCloseDistance = section.int("mole-burrow.guidance.close-distance", 6)
                        .checked("mole-burrow.guidance.close-distance", 1, 32),
                    guidanceFarDistance = section.int("mole-burrow.guidance.far-distance", 14)
                        .checked("mole-burrow.guidance.far-distance", 2, 64),
                    guidanceIntervalTicks = section.int("mole-burrow.guidance.interval-ticks", 20)
                        .checked("mole-burrow.guidance.interval-ticks", 5, 100),
                    decorationPercent = section.int("mole-burrow.decoration-percent", 18)
                        .checked("mole-burrow.decoration-percent", 0, 60),
                    tunnelWidth = section.int("mole-burrow.tunnel-width", 2)
                        .checked("mole-burrow.tunnel-width", 1, 3),
                    entranceMinBoundaryDistance = section.int("mole-burrow.entrance-min-boundary-distance", 10)
                        .checked("mole-burrow.entrance-min-boundary-distance", 0, 64),
                ).also {
                    require(it.guidanceFarDistance > it.guidanceCloseDistance) {
                        "farm-zones.$id mole guidance far-distance must exceed close-distance"
                    }
                    require(it.cells * it.tunnelWidth <= 16) {
                        "farm-zones.$id mole-burrow cells multiplied by tunnel-width must not exceed 16"
                    }
                }
                val incidentTriggerPercents = section.stringList("incident-trigger-percents")
                    .ifEmpty { listOf("15", "32", "50", "68", "85") }
                    .map { value ->
                        value.toIntOrNull()?.checked("incident-trigger-percents", 1, 99)
                            ?: error("farm-zones.$id.incident-trigger-percents must contain integers")
                    }.also { values ->
                        require(values.size in 1..8 && values == values.distinct().sorted()) {
                            "farm-zones.$id.incident-trigger-percents must contain 1..8 increasing unique percentages"
                        }
                        require(values.zipWithNext().all { (left, right) -> right - left >= 8 }) {
                            "farm-zones.$id incident triggers must be at least 8 percentage points apart"
                        }
                    }
                val incidentCountMin = section.int("incident-count.min", incidentTriggerPercents.size)
                    .checked("incident-count.min", 1, incidentTriggerPercents.size)
                val incidentCountMax = section.int("incident-count.max", incidentTriggerPercents.size)
                    .checked("incident-count.max", 1, incidentTriggerPercents.size)
                require(incidentCountMin <= incidentCountMax) {
                    "farm-zones.$id incident-count.min must not exceed incident-count.max"
                }
                require(incidentTypes.size >= incidentCountMax) {
                    "farm-zones.$id must enable at least incident-count.max distinct incident types"
                }
                orders.forEach { order ->
                    require(order.incidentTypes.size >= incidentCountMax) {
                        "Farm order ${order.id} must define at least incident-count.max distinct incident types"
                    }
                }
                val specialIncidents = FarmSpecialIncidentSettings(
                    channelBlockageCount = section.int("special-incidents.channels.blockages", 5)
                        .checked("special-incidents.channels.blockages", 3, 8),
                    channelBlockageMaterial = materialName(
                        section.string("special-incidents.channels.blockage.material", "MANGROVE_ROOTS"),
                    ),
                    channelBlockageCustomModelData = section.int("special-incidents.channels.blockage.custom-model-data", 0)
                        .checked("special-incidents.channels.blockage.custom-model-data", 0, MAX_CUSTOM_MODEL_DATA),
                    channelBlockageDisplayScale = section.finiteFloat(
                        "special-incidents.channels.blockage.display-scale",
                        1.6f,
                        0.5f,
                        3.0f,
                    ),
                    channelBlockageDisplayYOffset = section.finiteDouble(
                        "special-incidents.channels.blockage.y-offset",
                        0.8,
                        0.0,
                        2.0,
                    ),
                    nightCropPlacementCount = section.int("special-incidents.night-shift.placement-count", 90)
                        .checked("special-incidents.night-shift.placement-count", 6, 128),
                    nightCropTargetCount = section.int("special-incidents.night-shift.target-count", 24)
                        .checked("special-incidents.night-shift.target-count", 1, 128),
                    nightCropMinSpacing = section.finiteDouble(
                        "special-incidents.night-shift.crop-min-spacing",
                        6.0,
                        0.0,
                        32.0,
                    ),
                    nightPlayerTime = section.string("special-incidents.night-shift.player-time", "18000")
                        .toLongOrNull()?.also { require(it in 0..24_000) { "night-shift.player-time must be in 0..24000" } }
                        ?: error("night-shift.player-time must be an integer"),
                    nightTimeTransitionSeconds = section.int("special-incidents.night-shift.transition-seconds", 12)
                        .checked("special-incidents.night-shift.transition-seconds", 1, 30),
                    giantCropParticleStride = section.int("special-incidents.giant-crop.block-particle-stride", 4)
                        .checked("special-incidents.giant-crop.block-particle-stride", 1, 16),
                    giantCropHitCooldownMillis = section.int("special-incidents.giant-crop.hit-cooldown-millis", 90)
                        .checked("special-incidents.giant-crop.hit-cooldown-millis", 50, 1_000)
                        .toLong(),
                    birdMinCount = section.int("special-incidents.birds.min-count", 6)
                        .checked("special-incidents.birds.min-count", 1, 32),
                    birdMaxCount = section.int("special-incidents.birds.max-count", 14)
                        .checked("special-incidents.birds.max-count", 1, 32),
                    birdBedsPerBird = section.int("special-incidents.birds.beds-per-bird", 180)
                        .checked("special-incidents.birds.beds-per-bird", 16, 4_096),
                    birdSpawnMultiplier = section.int("special-incidents.birds.spawn-multiplier", 2)
                        .checked("special-incidents.birds.spawn-multiplier", 1, 4),
                    birdSpawnHeight = section.finiteDouble("special-incidents.birds.spawn-height", 4.0, 1.5, 12.0),
                    birdFlyingSpeed = section.finiteDouble("special-incidents.birds.flying-speed", 0.65, 0.1, 1.5),
                    birdHealth = section.finiteDouble("special-incidents.birds.health", 4.0, 1.0, 40.0),
                    birdEatRadius = section.finiteDouble("special-incidents.birds.eat-radius", 5.0, 1.0, 16.0),
                    birdEatIntervalSeconds = section.int("special-incidents.birds.eat-interval-seconds", 3)
                        .checked("special-incidents.birds.eat-interval-seconds", 1, 30),
                    birdRangedContribution = section.int("special-incidents.birds.ranged-contribution", 2)
                        .checked("special-incidents.birds.ranged-contribution", 1, 8),
                    birdMeleeContribution = section.int("special-incidents.birds.melee-contribution", 1)
                        .checked("special-incidents.birds.melee-contribution", 1, 8),
                    nightPatrolMinCount = section.int("special-incidents.night-shift.patrols.min-count", 3)
                        .checked("special-incidents.night-shift.patrols.min-count", 0, 16),
                    nightPatrolMaxCount = section.int("special-incidents.night-shift.patrols.max-count", 10)
                        .checked("special-incidents.night-shift.patrols.max-count", 0, 16),
                    nightPatrolBedsPerPatrol = section.int("special-incidents.night-shift.patrols.beds-per-patrol", 250)
                        .checked("special-incidents.night-shift.patrols.beds-per-patrol", 1, 2_000),
                    nightPatrolEntity = entityName(section.string("special-incidents.night-shift.patrols.entity", "HUSK")),
                    nightPatrolMinSpacing = section.finiteDouble(
                        "special-incidents.night-shift.patrols.min-spacing",
                        12.0,
                        0.0,
                        32.0,
                    ),
                    nightPatrolRoamRadius = section.finiteDouble(
                        "special-incidents.night-shift.patrols.roam-radius",
                        14.0,
                        4.0,
                        32.0,
                    ),
                    nightPatrolPathRefreshSeconds = section.int(
                        "special-incidents.night-shift.patrols.path-refresh-seconds",
                        5,
                    ).checked("special-incidents.night-shift.patrols.path-refresh-seconds", 2, 30),
                    nightPatrolSpawnMinPlayerDistance = section.finiteDouble(
                        "special-incidents.night-shift.patrols.spawn-min-player-distance",
                        8.0,
                        0.0,
                        32.0,
                    ),
                    nightPatrolReceivingSafeRadius = section.finiteDouble(
                        "special-incidents.night-shift.patrols.receiving-safe-radius",
                        15.0,
                        0.0,
                        64.0,
                    ),
                    nightPatrolMovementSpeed = section.finiteDouble(
                        "special-incidents.night-shift.patrols.movement-speed",
                        0.27,
                        0.05,
                        0.5,
                    ),
                    nightPatrolFollowRange = section.finiteDouble(
                        "special-incidents.night-shift.patrols.follow-range",
                        8.0,
                        2.0,
                        32.0,
                    ),
                    nightPatrolAttackDamage = section.finiteDouble(
                        "special-incidents.night-shift.patrols.attack-damage",
                        2.0,
                        0.0,
                        20.0,
                    ),
                    nightPatrolHeldItem = materialName(
                        section.string("special-incidents.night-shift.patrols.held-item", "TORCH"),
                    ),
                    nightPatrolLightLevel = section.int("special-incidents.night-shift.patrols.light-level", 15)
                        .checked("special-incidents.night-shift.patrols.light-level", 0, 15),
                    marketCropCount = section.int("special-incidents.market.crops", 256)
                        .checked("special-incidents.market.crops", 8, 512),
                    marketMoneyBonusPercent = section.int("special-incidents.market.money-bonus-percent", 25)
                        .checked("special-incidents.market.money-bonus-percent", 0, 200),
                    marketBaseSeconds = section.int("special-incidents.market.timer.base-seconds", 30)
                        .checked("special-incidents.market.timer.base-seconds", 0, 600),
                    marketSecondsPerCrop = section.finiteDouble(
                        "special-incidents.market.timer.seconds-per-crop",
                        0.3,
                        0.0,
                        30.0,
                    ),
                    marketMinimumSeconds = section.int("special-incidents.market.timer.minimum-seconds", 90)
                        .checked("special-incidents.market.timer.minimum-seconds", 10, 3_600),
                    marketMaximumSeconds = section.int("special-incidents.market.timer.maximum-seconds", 180)
                        .checked("special-incidents.market.timer.maximum-seconds", 10, 3_600),
                    frost = FarmFrostSettings(
                        campfireMinCount = section.int("special-incidents.frost.campfires.min-count", 4)
                            .checked("special-incidents.frost.campfires.min-count", 1, 16),
                        campfireMaxCount = section.int("special-incidents.frost.campfires.max-count", 6)
                            .checked("special-incidents.frost.campfires.max-count", 1, 16),
                        bedsPerCampfire = section.int("special-incidents.frost.campfires.beds-per-campfire", 180)
                            .checked("special-incidents.frost.campfires.beds-per-campfire", 16, 4_096),
                        targetTemperature = section.int("special-incidents.frost.temperature.target", 100)
                            .checked("special-incidents.frost.temperature.target", 10, 1_000),
                        heatPerSecondPerFire = section.int("special-incidents.frost.temperature.heat-per-second-per-fire", 2)
                            .checked("special-incidents.frost.temperature.heat-per-second-per-fire", 1, 100),
                        coolingSecondsPerDegree = section.int("special-incidents.frost.temperature.cooling-seconds-per-degree", 10)
                            .checked("special-incidents.frost.temperature.cooling-seconds-per-degree", 1, 600),
                        fuelSeconds = section.int("special-incidents.frost.fuel-seconds", 30)
                            .checked("special-incidents.frost.fuel-seconds", 1, 3_600),
                        pickupRadius = section.finiteDouble("special-incidents.frost.pickup-radius", 1.75, 0.5, 8.0),
                        deliveryRadius = section.finiteDouble("special-incidents.frost.delivery-radius", 2.25, 0.5, 8.0),
                        fuelMaterial = materialName(section.string("special-incidents.frost.fuel-material", "OAK_LOG")),
                        woodpileMaterial = materialName(section.string("special-incidents.frost.woodpile.material", "OAK_LOG")),
                        woodpileCustomModelData = section.int("special-incidents.frost.woodpile.custom-model-data", 0)
                            .checked("special-incidents.frost.woodpile.custom-model-data", 0, MAX_CUSTOM_MODEL_DATA),
                        woodpileDisplayTransform = section.string("special-incidents.frost.woodpile.display-transform", "GROUND")
                            .uppercase().also { value ->
                                require(value in setOf("NONE", "GROUND", "FIXED", "HEAD")) {
                                    "special-incidents.frost.woodpile.display-transform is invalid"
                                }
                            },
                        woodpileScale = section.finiteFloat("special-incidents.frost.woodpile.scale", 1.0f, 0.1f, 8.0f),
                        woodpileYOffset = section.finiteDouble("special-incidents.frost.woodpile.y-offset", 0.0, -4.0, 4.0),
                        woodpileYawOffset = section.finiteFloat("special-incidents.frost.woodpile.yaw-offset", 0.0f, -360.0f, 360.0f),
                        woodpileViewRange = section.finiteFloat("special-incidents.frost.woodpile.view-range", 3.0f, 0.5f, 16.0f),
                    ),
                )
                require(specialIncidents.marketMinimumSeconds <= specialIncidents.marketMaximumSeconds) {
                    "farm-zones.$id market timer minimum-seconds must not exceed maximum-seconds"
                }
                FarmZoneSettings(
                    id = id,
                    reference = reference,
                    permission = permission(section.string("permission", "arcfarms.farm")),
                    preparationPatchSize = preparationPatchSize,
                    preparationPatchMaxSize = preparationPatchMaxSize,
                    fieldCompletionPercent = section.int("field-completion-percent", 90)
                        .checked("field-completion-percent", 50, 100),
                    preparationSearchRadius = section.int("preparation-search-radius", 48)
                        .checked("preparation-search-radius", 4, 64),
                    fixedCropRespawnSeconds = section.int("fixed-crop-respawn-seconds", 20)
                        .checked("fixed-crop-respawn-seconds", 1, 3_600),
                    restoreBlocksPerTick = section.int("restore-blocks-per-tick", 24)
                        .checked("restore-blocks-per-tick", 1, 128),
                    blockReindexBlocksPerTick = section.int("block-reindex-blocks-per-tick", 131_072)
                        .checked("block-reindex-blocks-per-tick", 256, 262_144),
                    blockReindexMaxBlocks = section.int("block-reindex-max-blocks", 20_000_000)
                        .checked("block-reindex-max-blocks", 100_000, 50_000_000),
                    cropLayout = cropLayout,
                    backupBlocksPerTick = section.int("backup-blocks-per-tick", 2_048)
                        .checked("backup-blocks-per-tick", 128, 8_192),
                    backupMaxBlocks = section.int("backup-max-blocks", 4_000_000)
                        .checked("backup-max-blocks", 10_000, 20_000_000),
                    careRadius = section.int("care-radius", 10).checked("care-radius", 3, 24),
                    careTypes = careTypes,
                    careTargetsPerPlayer = section.int("care-targets-per-player", 15)
                        .checked("care-targets-per-player", 1, 64),
                    careTargetsMax = section.int("care-targets-max", 45)
                        .checked("care-targets-max", 1, 64),
                    careSpawnsPerUpdate = section.int("care-spawns-per-update", 10)
                        .checked("care-spawns-per-update", 1, 32),
                    scarecrowTargetCount = section.int("scarecrow-target-count", 5)
                        .checked("scarecrow-target-count", 1, 45),
                    scarecrowMinSpacing = section.finiteDouble("scarecrow-min-spacing", 12.0, 4.0, 64.0),
                    pollinationCharges = section.int("pollination-charges", 5)
                        .checked("pollination-charges", 1, 32),
                    irrigation = FarmIrrigationSettings(
                        dryBlocksPerTick = section.int("irrigation.dry-blocks-per-tick", 24)
                            .checked("irrigation.dry-blocks-per-tick", 1, 128),
                        waveBlocksPerTick = section.int("irrigation.wave-blocks-per-tick", 24)
                            .checked("irrigation.wave-blocks-per-tick", 1, 128),
                        waveStartDelayTicks = section.int("irrigation.wave-start-delay-ticks", 4)
                            .checked("irrigation.wave-start-delay-ticks", 0, 40),
                        ringIntervalTicks = section.int("irrigation.ring-interval-ticks", 2)
                            .checked("irrigation.ring-interval-ticks", 1, 20),
                        ringWidth = section.finiteDouble("irrigation.ring-width", 1.25, 0.5, 4.0),
                        particleSpacing = section.finiteDouble("irrigation.particle-spacing", 1.25, 0.5, 4.0),
                        particleHeight = section.finiteDouble("irrigation.particle-height", 2.0, 0.5, 4.0),
                        particleSpread = section.finiteDouble("irrigation.particle-spread", 0.38, 0.0, 1.5),
                        particleCount = section.int("irrigation.particle-count", 3)
                            .checked("irrigation.particle-count", 1, 12),
                    ),
                    appleTargetCount = section.int("apple-targets", 10).checked("apple-targets", 3, 256),
                    applePlacementCount = section.int("apple-placement-count", 40)
                        .checked("apple-placement-count", 3, 512),
                    appleSpawnsPerUpdate = section.int("apple-spawns-per-update", 20)
                        .checked("apple-spawns-per-update", 1, 64),
                    appleMinSpacing = section.finiteDouble("apple-min-spacing", 4.0, 0.0, 24.0),
                    appleDisplayScale = section.finiteFloat("apple-display-scale", 1.35f, 0.5f, 3.0f),
                    appleDisplayYOffset = section.finiteDouble("apple-display-y-offset", -0.82, -2.0, 2.0),
                    appleInteractionYOffset = section.finiteDouble("apple-interaction-y-offset", -0.88, -2.0, 2.0),
                    appleLeafIndexLimit = section.int("apple-leaf-index-limit", 8_192)
                        .checked("apple-leaf-index-limit", 256, 32_768),
                    animalRescueTargetCount = section.int("animal-rescue-targets", 6)
                        .checked("animal-rescue-targets", 2, 12),
                    animalRescueMinSpacing = section.finiteDouble("animal-rescue-min-spacing", 8.0, 0.0, 32.0),
                    animalRescueMaxPlayerDistance = section.int("animal-rescue-max-player-distance", placementMaxPlayerDistance)
                        .checked("animal-rescue-max-player-distance", 4, 64),
                    animalDeliveryRadius = section.finiteDouble("animal-delivery-radius", 3.0, 1.0, 8.0),
                    supplyNearbyViewDistance = section.finiteFloat("supply-nearby-view-distance", 30.0f, 1.0f, 192.0f),
                    displayViewRange = section.finiteFloat("display-view-range", 2.0f, 0.25f, 8.0f),
                    seederEveryShifts = section.int("seeder-every-shifts", 2)
                        .checked("seeder-every-shifts", 0, 16),
                    seederPatchSize = seederPatchSize,
                    seederPatchMaxSize = seederPatchMaxSize,
                    seederComponentGap = section.int("seeder-component-gap", 16)
                        .checked("seeder-component-gap", 0, 32),
                    seederComponentLimit = section.int("seeder-component-limit", 8)
                        .checked("seeder-component-limit", 1, 16),
                    seederPigCount = seederPigCount,
                    seederPigLeadDistance = seederPigLeadDistance,
                    seederPigSpacing = seederPigSpacing,
                    seederPigCatchupDistance = seederPigCatchupDistance,
                    seederHorseSpeed = section.finiteDouble("seeder-horse-speed", 0.24, 0.1, 0.6),
                    seederPigSpeed = section.finiteDouble("seeder-pig-speed", 0.46, 0.1, 0.8),
                    seederWorkingRadius = section.finiteDouble("seeder-working-radius", 8.0, 1.0, 16.0),
                    seederBlocksPerUpdate = section.int("seeder-blocks-per-update", 128)
                        .checked("seeder-blocks-per-update", 8, 256),
                    diseaseInitialSpots = diseaseInitialSpots,
                    diseaseMaxSpots = diseaseMaxSpots,
                    diseaseSpreadSeconds = section.int("disease-spread-seconds", 3)
                        .checked("disease-spread-seconds", 1, 300),
                    diseaseSpreadRadius = section.finiteDouble("disease-spread-radius", 4.0, 1.0, 12.0),
                    diseaseKillSeconds = section.int("disease-kill-seconds", 16)
                        .checked("disease-kill-seconds", 3, 300),
                    scarecrowDeliveryRadius = section.finiteDouble("scarecrow-delivery-radius", 2.5, 1.0, 6.0),
                    scarecrowCarriedYOffset = section.finiteDouble("scarecrow-carried-y-offset", 1.15, 0.0, 3.0),
                    moleBurrow = moleBurrow,
                    careAnimalEntities = section.stringList("care-animal-entities")
                        .ifEmpty { listOf("CHICKEN", "SHEEP") }
                        .map(::entityName)
                        .distinct()
                        .also { require(it.isNotEmpty()) { "Farm zone $id has no care animal entities" } },
                    careAnimalFollowDistance = section.finiteDouble("care.animal-follow-distance", 1.5, 0.75, 4.0),
                    careAnimalFollowSpeed = section.finiteDouble("care.animal-follow-speed", 1.25, 0.25, 2.5),
                    careAnimalFollowImpulseBase = section.finiteDouble(
                        "care.animal-follow-impulse-base", 0.16, 0.0, 1.0,
                    ),
                    careAnimalFollowImpulsePerBlock = section.finiteDouble(
                        "care.animal-follow-impulse-per-block", 0.025, 0.0, 0.2,
                    ),
                    careAnimalFollowImpulseMax = section.finiteDouble(
                        "care.animal-follow-impulse-max", 0.42, 0.05, 1.0,
                    ),
                    careAnimalFollowImpulseSmoothing = section.finiteDouble(
                        "care.animal-follow-impulse-smoothing", 0.25, 0.0, 1.0,
                    ),
                    inputCooldowns = FarmInputCooldownSettings(
                        supplyMillis = section.int("input-cooldowns.supply-millis", 500)
                            .checked("input-cooldowns.supply-millis", 50, 5_000).toLong(),
                        deliveryMillis = section.int("input-cooldowns.delivery-millis", 500)
                            .checked("input-cooldowns.delivery-millis", 50, 5_000).toLong(),
                        patchMissMillis = section.int("input-cooldowns.patch-miss-millis", 500)
                            .checked("input-cooldowns.patch-miss-millis", 50, 5_000).toLong(),
                        careMillis = section.int("input-cooldowns.care-millis", 100)
                            .checked("input-cooldowns.care-millis", 50, 5_000).toLong(),
                        careTargetMillis = section.int("input-cooldowns.care-target-millis", 250)
                            .checked("input-cooldowns.care-target-millis", 50, 5_000).toLong(),
                        moleMillis = section.int("input-cooldowns.mole-millis", 500)
                            .checked("input-cooldowns.mole-millis", 50, 5_000).toLong(),
                        frostPickupMillis = section.int("input-cooldowns.frost-pickup-millis", 750)
                            .checked("input-cooldowns.frost-pickup-millis", 50, 5_000).toLong(),
                        contractSceneMillis = section.int("input-cooldowns.contract-scene-millis", 700)
                            .checked("input-cooldowns.contract-scene-millis", 50, 5_000).toLong(),
                    ),
                    proceduralCareFixtures = section.boolean("procedural-care-fixtures", true),
                    careVisuals = careVisuals,
                    contractCartVisual = contractCartVisual,
                    music = FarmMusicSettings(
                        enabled = section.boolean("music.enabled", false),
                        sound = soundKey(section.string("music.sound", "minecraft:music.overworld.forest")),
                        durationSeconds = section.int("music.duration-seconds", 180)
                            .checked("music.duration-seconds", 5, 3_600),
                        volume = section.string("music.volume", "0.65").toFloatOrNull()?.also {
                            require(it.isFinite() && it in 0.0f..1.0f) { "Farm zone $id music.volume must be in 0.0..1.0" }
                        } ?: error("Farm zone $id music.volume must be a number"),
                    ),
                    placementMinObjectiveDistance = placementMinObjectiveDistance,
                    placementMaxPlayerDistance = placementMaxPlayerDistance,
                    placementSearchRadius = placementSearchRadius,
                    placementReceivingExclusionPadding = section.finiteDouble(
                        "placement.receiving-exclusion-padding", 1.5, 0.0, 4.0,
                    ),
                    incidentTriggerPercents = incidentTriggerPercents,
                    incidentCountMin = incidentCountMin,
                    incidentCountMax = incidentCountMax,
                    incidentQuota = section.int("incident-quota", 4).checked("incident-quota", 1, 64),
                    droughtPatches = droughtPatches,
                    droughtCoveragePercent = droughtCoveragePercent,
                    droughtMinBeds = droughtMinBeds,
                    droughtMaxBeds = droughtMaxBeds,
                    droughtInitialBeds = droughtInitialBeds,
                    droughtGrowthBeds = section.int("drought-growth-beds", 8)
                        .checked("drought-growth-beds", 1, 64),
                    droughtGrowthSeconds = section.int("drought-growth-seconds", 2)
                        .checked("drought-growth-seconds", 1, 300),
                    droughtWaterRadius = section.int("drought.water-radius", 5)
                        .checked("drought.water-radius", 1, 8),
                    droughtWaterSettleTicks = section.int("drought.water-settle-ticks", 21)
                        .checked("drought.water-settle-ticks", 5, 100)
                        .toLong(),
                    incidentTypes = incidentTypes,
                    specialIncidents = specialIncidents,
                    processing = processing,
                    barnFire = barnFire,
                    cropEffects = FarmCropBreakEffectsSettings(
                        blockParticleCount = section.int("crop-effects.harvest-burst.block-particle-count", 14)
                            .checked("crop-effects.harvest-burst.block-particle-count", 0, 64),
                        dustParticleCount = section.int("crop-effects.harvest-burst.dust-particle-count", 7)
                            .checked("crop-effects.harvest-burst.dust-particle-count", 0, 64),
                        composterParticleCount = section.int("crop-effects.harvest-burst.composter-particle-count", 3)
                            .checked("crop-effects.harvest-burst.composter-particle-count", 0, 32),
                        poofParticleCount = section.int("crop-effects.harvest-burst.poof-particle-count", 5)
                            .checked("crop-effects.harvest-burst.poof-particle-count", 0, 32),
                    ),
                    pestEntity = entityName(section.string("pest-entity", "SILVERFISH")),
                    pestSpawnRadius = section.int("pest-spawn-radius", 6).checked("pest-spawn-radius", 2, 16),
                    pestNestCount = section.int("pest-nests", 3).checked("pest-nests", 1, 8),
                    pestNestHealth = section.int("pest-nest-health", 3).checked("pest-nest-health", 1, 20),
                    pestSpawnsPerNest = section.int("pest-spawns-per-nest", 3).checked("pest-spawns-per-nest", 1, 8),
                    pestMaxAlive = section.int("pest-max-alive", 6).checked("pest-max-alive", 1, 32),
                    pestSpawnIntervalSeconds = section.int("pest-spawn-interval-seconds", 4)
                        .checked("pest-spawn-interval-seconds", 1, 60),
                    pestSpawnChancePercent = section.int("pest-spawn-chance-percent", 45)
                        .checked("pest-spawn-chance-percent", 1, 100),
                    pestEatRadius = section.int("pest-eat-radius", 3).checked("pest-eat-radius", 1, 8),
                    pestEatPerPulse = section.int("pest-eat-per-pulse", 8).checked("pest-eat-per-pulse", 1, 32),
                    pestEatIntervalMillis = section.int("pest-eat-interval-millis", 1_000)
                        .checked("pest-eat-interval-millis", 100, 10_000)
                        .toLong(),
                    pestNestMinSpacing = section.finiteDouble("pest-nest-min-spacing", 16.0, 4.0, 64.0),
                    pestNestDisplayScale = section.finiteFloat("pest-nest-display-scale", 2.0f, 0.25f, 4.0f),
                    damageSafety = FarmDamageSafetySettings(
                        maximumPercent = section.int("damage-safety.maximum-percent", 18)
                            .checked("damage-safety.maximum-percent", 0, 80),
                        minimumRemaining = section.int("damage-safety.minimum-remaining", 128)
                            .checked("damage-safety.minimum-remaining", 0, 65_536),
                        birdMaximum = section.int("damage-safety.birds-maximum", 96)
                            .checked("damage-safety.birds-maximum", 0, 4_096),
                        pestMaximum = section.int("damage-safety.pests-maximum", 128)
                            .checked("damage-safety.pests-maximum", 0, 4_096),
                        diseaseMaximum = section.int("damage-safety.disease-maximum", 96)
                            .checked("damage-safety.disease-maximum", 0, 4_096),
                    ),
                    supplies = supplies,
                    delivery = delivery,
                    routeDelivery = routeDelivery,
                    perks = perks,
                    rewards = parseRewards(section, id, "farm-zones", 75),
                    crops = crops,
                    rareOrderChancePercent = rareOrderChancePercent,
                    orders = orders,
                    deliveryCarriedForwardOffset = section.finiteDouble(
                        "delivery.carried-forward-offset", 0.65, 0.0, 2.0,
                    ),
                    scarecrowCarriedForwardOffset = section.finiteDouble(
                        "scarecrow-carried-forward-offset", 0.7, 0.0, 2.0,
                    ),
                ).also { farm ->
                    require(farm.applePlacementCount >= farm.appleTargetCount) {
                        "farm-zones.$id apple-placement-count must be at least apple-targets"
                    }
                }
            }

            val lumbermills = config.keys("lumber-zones").sorted().mapNotNull { id ->
                val section = config.section("lumber-zones.$id")
                if (!section.boolean("enabled", true)) return@mapNotNull null
                validateId(id, "lumber zone")
                val reference = parseReference(section, "", id)
                val station = parseReference(section, "station-", "$id station", reference.world)
                val fellingQuota = section.int("felling-quota", 16).checked("felling-quota", 1, 100_000)
                val processingQuota = section.int("processing-quota", 6).checked("processing-quota", 1, 100_000)
                val processingPerUse = section.int("processing-per-use", 2).checked("processing-per-use", 1, 100_000)
                val species = section.stringList("species").map(::speciesName).distinct().also {
                    require(it.isNotEmpty()) { "Lumber zone $id has no species" }
                }
                val engineVersion = section.int("engine-version", 1).checked("lumber engine-version", 1, 2)
                val incidentCountMin = section.int("incident-count-min", 3).checked("lumber incident-count-min", 3, 5)
                val incidentCountMax = section.int("incident-count-max", 5).checked("lumber incident-count-max", 3, 5)
                val orders = section.keys("orders").sorted().map { orderId ->
                    validateId(orderId, "lumber order")
                    val order = section.section("orders.$orderId")
                    LumberOrderSettings(
                        id = orderId,
                        species = order.stringList("species").ifEmpty { species }.map(::speciesName).distinct(),
                        fellingRequired = order.int("phases.felling-required", fellingQuota)
                            .checked("lumber order felling-required", 1, 100_000),
                        skiddingRequired = order.int("phases.skidding-required", processingQuota)
                            .checked("lumber order skidding-required", 1, 100_000),
                        sawingRequired = order.int("phases.sawing-required", processingQuota)
                            .checked("lumber order sawing-required", 1, 100_000),
                        stackingRequired = order.int("phases.stacking-required", processingQuota)
                            .checked("lumber order stacking-required", 1, 100_000),
                        incidentTypes = order.stringList("incidents").map { raw ->
                            runCatching { LumberIncidentType.valueOf(raw.trim().uppercase()) }.getOrElse {
                                throw IllegalArgumentException("Unknown lumber incident '$raw' in $id/$orderId")
                            }
                        },
                    )
                }
                LumberZoneSettings(
                    id = id,
                    reference = reference,
                    station = station,
                    permission = permission(section.string("permission", "arcfarms.lumber")),
                    fellingQuota = fellingQuota,
                    processingQuota = processingQuota,
                    processingPerUse = processingPerUse,
                    species = species,
                    stationMaterials = section.stringList("station-materials").map(::materialName).toSet().also {
                        require(it.isNotEmpty()) { "Lumber zone $id has no station materials" }
                    },
                    engineVersion = engineVersion,
                    orders = orders,
                    targetMultiplier = section.int("target-multiplier", 2).checked("lumber target-multiplier", 2, 4),
                    incidentCountMin = incidentCountMin,
                    incidentCountMax = incidentCountMax,
                    recoverySeconds = section.int("recovery-seconds", 90).checked("lumber recovery-seconds", 5, 3_600),
                    rewards = parseRewards(section, id, "lumber-zones", 110),
                    rushOrderDurationMillis = section.int("incidents.rush-order-duration-millis", 75_000)
                        .checked("lumber incidents.rush-order-duration-millis", 1_000, 3_600_000)
                        .toLong(),
                    forestFireCandidateMultiplier = section.int("incidents.forest-fire.candidate-multiplier", 4)
                        .checked("lumber incidents.forest-fire.candidate-multiplier", 2, 8),
                    sawInteractionCooldownMillis = section.int("interaction-cooldowns.saw-millis", 150)
                        .checked("lumber interaction-cooldowns.saw-millis", 50, 2_000)
                        .toLong(),
                    bundleInteractionCooldownMillis = section.int("interaction-cooldowns.bundle-millis", 350)
                        .checked("lumber interaction-cooldowns.bundle-millis", 50, 2_000)
                        .toLong(),
                    plankInteractionCooldownMillis = section.int("interaction-cooldowns.plank-millis", 350)
                        .checked("lumber interaction-cooldowns.plank-millis", 50, 2_000)
                        .toLong(),
                    dispatchInteractionCooldownMillis = section.int("interaction-cooldowns.dispatch-millis", 500)
                        .checked("lumber interaction-cooldowns.dispatch-millis", 50, 2_000)
                        .toLong(),
                ).also {
                    require(it.processingPerUse <= it.processingQuota) { "processing-per-use exceeds processing-quota in $id" }
                }
            }
            require(lumbermills.map(LumberZoneSettings::engineVersion).distinct().size <= 1) {
                "All lumber zones must use the same engine-version"
            }

            val mines = config.keys("mine-zones").sorted().mapNotNull { id ->
                val section = config.section("mine-zones.$id")
                if (!section.boolean("enabled", true)) return@mapNotNull null
                validateId(id, "mine zone")
                val materialWeights = parseWeightedList(section.stringList("materials"), "mine $id materials")
                val baseMaterial = materialName(section.string("base-material"))
                require(baseMaterial in materialWeights) { "Mine $id base-material must be present in materials" }
                val engineVersion = section.int("engine-version", 1).checked("mine engine-version", 1, 2)
                val incidentCountMin = section.int("incident-count-min", 3).checked("mine incident-count-min", 3, 5)
                val incidentCountMax = section.int("incident-count-max", 5).checked("mine incident-count-max", 3, 5)
                val orders = section.keys("orders").sorted().map { orderId ->
                    validateId(orderId, "mine order")
                    val order = section.section("orders.$orderId")
                    MineOrderSettings(
                        id = orderId,
                        prospectingRequired = order.int("phases.prospecting-required", 3)
                            .checked("mine order prospecting-required", 1, 100_000),
                        miningRequired = order.int("phases.mining-required", section.int("cart-quota", 16))
                            .checked("mine order mining-required", 1, 100_000),
                        loadingRequired = order.int("phases.loading-required", 4)
                            .checked("mine order loading-required", 1, 100_000),
                        incidentTypes = order.stringList("incidents").map { raw ->
                            runCatching { MineIncidentType.valueOf(raw.trim().uppercase()) }.getOrElse {
                                throw IllegalArgumentException("Unknown mine incident '$raw' in $id/$orderId")
                            }
                        },
                    )
                }
                MineZoneSettings(
                    id = id,
                    priority = section.int("priority", 0).checked("mine priority", -1000, 1000),
                    reference = parseReference(section, "", id),
                    permission = permission(section.string("permission", "arcfarms.mine")),
                    cartQuota = section.int("cart-quota", 16).checked("cart-quota", 2, 100_000),
                    hazardTrigger = section.int("hazard-trigger", 6).checked("hazard-trigger", 1, 99_999),
                    supportsRequired = section.int("supports-required", 1).checked("supports-required", 1, 32),
                    restoreSeconds = section.int("restore-seconds", 60).checked("restore-seconds", 5, 3600),
                    temporaryMaterial = materialName(section.string("temp-material")),
                    baseMaterial = baseMaterial,
                    materialWeights = LinkedHashMap(materialWeights),
                    engineVersion = engineVersion,
                    orders = orders,
                    targetMultiplier = section.int("target-multiplier", 2).checked("mine target-multiplier", 2, 4),
                    incidentCountMin = incidentCountMin,
                    incidentCountMax = incidentCountMax,
                    rewards = parseRewards(section, id, "mine-zones", 125),
                    lostMinerDeliveryRadius = section.finiteDouble("lost-miner.delivery-radius", 2.5, 1.0, 6.0),
                    lostMinerFollowSnapDistance = section.finiteDouble(
                        "lost-miner.follow-snap-distance", 6.0, 2.0, 16.0,
                    ),
                    lostMinerFollowOffsetZ = section.finiteDouble("lost-miner.follow-offset-z", -1.0, -3.0, 3.0),
                    extractionCheckpointRadius = section.finiteDouble(
                        "extraction.checkpoint-radius", 1.6, 0.75, 4.0,
                    ),
                    loadingDeliveryRadius = section.finiteDouble("loading.delivery-radius", 2.0, 1.0, 5.0),
                    cartVisual = run {
                        val path = "extraction.cart"
                        val itemModel = section.string("$path.item-model", "").trim().ifEmpty { null }
                        itemModel?.let { model ->
                            require(model.matches(Regex("[a-z0-9._-]+:[a-z0-9/._-]+"))) {
                                "mine-zones.$id.$path.item-model must be a namespaced item model"
                            }
                        }
                        MineCartVisualSettings(
                            material = materialName(section.string("$path.material", "MINECART")),
                            customModelData = section.int("$path.custom-model-data", 0)
                                .checked("mine $id cart custom-model-data", 0, MAX_CUSTOM_MODEL_DATA),
                            itemModel = itemModel,
                            displayTransform = section.string("$path.display-transform", "GROUND")
                                .trim().uppercase().let { raw ->
                                    FarmItemDisplayTransform.entries.firstOrNull { it.name == raw }
                                        ?: error("mine-zones.$id.$path.display-transform must be GROUND, FIXED, or HEAD")
                                },
                            scale = section.finiteFloat("$path.scale", 1.0f, 0.05f, 8.0f),
                            yOffset = section.finiteDouble("$path.y-offset", 0.15, -4.0, 4.0),
                            viewRange = section.finiteFloat("$path.view-range", 2.0f, 0.25f, 64.0f),
                            interactionWidth = section.finiteFloat("$path.interaction-width", 1.5f, 0.1f, 16.0f),
                            interactionHeight = section.finiteFloat("$path.interaction-height", 1.0f, 0.1f, 16.0f),
                        )
                    },
                ).also {
                    require(it.hazardTrigger < it.cartQuota) { "Mine $id hazard-trigger must be below cart-quota" }
                    require(it.temporaryMaterial !in it.materialWeights) { "Mine $id temp-material must not be a generated material" }
                }
            }
            require(mines.map(MineZoneSettings::engineVersion).distinct().size <= 1) {
                "All mine zones must use the same engine-version"
            }

            val allowedOrigins = config.stringList("network.allowed-origins")
                .ifEmpty { listOf("spawn", "survival", "parkour") }
                .mapTo(linkedSetOf()) { serverId(it, "network.allowed-origins") }
            require(serverId in allowedOrigins) { "network.allowed-origins must include server-id" }
            val destinations = listOf("farm", "lumber", "mine").associateWith { activity ->
                parseDestination(config, activity, allowedOrigins)
            }
            val network = NetworkSettings(
                enabled = config.boolean("network.enabled", true),
                allowedOrigins = allowedOrigins,
                playerAnnouncementsEnabled = config.boolean("network.player-announcements", false),
                workdayEnabled = config.boolean("network.workday", true),
                nodeProbeEnabled = config.boolean("network.node-probe", true),
                travelTicketSeconds = config.int("network.travel-ticket-seconds", 30).checked("network.travel-ticket-seconds", 10, 300),
            )
            return ArcFarmsConfig(
                enabled = config.boolean("enabled", true),
                serverId = serverId,
                network = network,
                defaultLocale = defaultLocale,
                useClientLocale = config.boolean("locale.use-client-locale", true),
                bossbars = config.boolean("ui.bossbars", true),
                particles = config.boolean("ui.particles", true),
                sounds = config.boolean("ui.sounds", true),
                titleStaySeconds = config.int("ui.title-stay-seconds", 4).checked("ui.title-stay-seconds", 2, 20),
                markerHeight = config.int("ui.marker-height", 12).checked("ui.marker-height", 6, 24),
                missingBedHighlightThreshold = config.int("ui.missing-bed-highlight-threshold", 10)
                    .checked("ui.missing-bed-highlight-threshold", 1, 32),
                taskHintCooldownMillis = config.int("ui.task-hint-cooldown-millis", 900)
                    .checked("ui.task-hint-cooldown-millis", 250, 5_000)
                    .toLong(),
                taskTitleCooldownMillis = config.int("ui.task-title-cooldown-millis", 8_000)
                    .checked("ui.task-title-cooldown-millis", 1_000, 30_000)
                    .toLong(),
                farmScoreboard = FarmScoreboardSettings(
                    enabled = config.boolean("ui.farm-scoreboard.enabled", true),
                    provider = config.string("ui.farm-scoreboard.provider", "BUKKIT").uppercase().let { raw ->
                        FarmScoreboardProvider.entries.firstOrNull { it.name == raw }
                            ?: error("ui.farm-scoreboard.provider must be BUKKIT or TAB")
                    },
                    replaceExisting = config.boolean("ui.farm-scoreboard.replace-existing", false),
                ),
                menuBackground = MenuBackgroundSettings(
                    enabled = config.boolean("ui.menu-background.enabled", false),
                    material = materialName(config.string("ui.menu-background.material", "GRAY_STAINED_GLASS_PANE")),
                    customModelData = config.int("ui.menu-background.custom-model-data", 0)
                        .checked("ui.menu-background.custom-model-data", 0, MAX_CUSTOM_MODEL_DATA),
                ),
                saveSeconds = config.int("state.save-seconds", 10).checked("state.save-seconds", 1, 300),
                completedCooldownSeconds = config.int("state.completed-cooldown-seconds", 180).checked("completed cooldown", 0, 3600),
                debug = DebugSettings(
                    enabled = config.boolean("debug.enabled", false),
                ),
                destinations = destinations,
                farms = farms,
                lumbermills = lumbermills,
                mines = mines.sortedByDescending(MineZoneSettings::priority),
            )
        }

        private fun parseReference(
            section: ru.arc.config.ConfigSection,
            prefix: String,
            label: String,
            defaultWorld: String? = null,
        ): ZoneReference {
            val world = section.stringOrNull("${prefix}world") ?: defaultWorld
                ?: error("$label is missing ${prefix}world")
            val region = section.stringOrNull("${prefix}region")?.takeIf(String::isNotBlank)
            val min = section.stringListOrNull("${prefix}bounds.min")
            val max = section.stringListOrNull("${prefix}bounds.max")
            val bounds = if (min != null || max != null) {
                require(min?.size == 3 && max?.size == 3) { "$label bounds require three min and max coordinates" }
                val a = min.map { it.toIntOrNull() ?: error("$label has a non-integer bound") }
                val b = max.map { it.toIntOrNull() ?: error("$label has a non-integer bound") }
                CuboidBounds(a[0], a[1], a[2], b[0], b[1], b[2])
            } else null
            return ZoneReference(world, region, bounds)
        }

        private fun ru.arc.config.ConfigSection.finiteFloat(
            path: String,
            default: Float,
            minimum: Float,
            maximum: Float,
        ): Float = string(path, default.toString()).toFloatOrNull()?.also {
            require(it.isFinite() && it in minimum..maximum) { "$path must be in $minimum..$maximum" }
        } ?: error("$path must be a finite number")

        private fun ru.arc.config.ConfigSection.finiteDouble(
            path: String,
            default: Double,
            minimum: Double,
            maximum: Double,
        ): Double = string(path, default.toString()).toDoubleOrNull()?.also {
            require(it.isFinite() && it in minimum..maximum) { "$path must be in $minimum..$maximum" }
        } ?: error("$path must be a finite number")

        private fun parseDestination(
            config: Config,
            activity: String,
            allowedOrigins: Set<String>,
        ): TeleportDestination {
            val path = "destinations.$activity"
            val server = serverId(config.string("$path.server"), "$path.server")
            require(server in allowedOrigins) { "$path.server must be present in network.allowed-origins" }
            val world = config.string("$path.world").trim().also {
                require(it.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid destination world: $it" }
            }
            fun coordinate(name: String, min: Double, max: Double): Double =
                config.string("$path.$name").toDoubleOrNull()?.also {
                    require(it.isFinite() && it in min..max) { "$path.$name is outside $min..$max" }
                } ?: error("$path.$name must be a finite number")
            return TeleportDestination(
                server = server,
                world = world,
                x = coordinate("x", -30_000_000.0, 30_000_000.0),
                y = coordinate("y", -2_048.0, 2_048.0),
                z = coordinate("z", -30_000_000.0, 30_000_000.0),
                yaw = coordinate("yaw", -360.0, 360.0).toFloat(),
                pitch = coordinate("pitch", -90.0, 90.0).toFloat(),
            )
        }

        private fun parseFarmDelivery(
            section: ru.arc.config.ConfigSection,
            world: String,
            zoneId: String,
        ): FarmDeliverySettings {
            fun coordinate(name: String, minimum: Double, maximum: Double): Double =
                section.string("delivery.$name").toDoubleOrNull()?.also {
                    require(it.isFinite() && it in minimum..maximum) {
                        "farm-zones.$zoneId.delivery.$name is outside $minimum..$maximum"
                    }
                } ?: error("farm-zones.$zoneId.delivery.$name must be a finite number")
            fun pickupCoordinate(name: String, minimum: Double, maximum: Double): Double {
                val raw = section.stringOrNull("delivery.pickup.$name") ?: section.string("delivery.$name")
                return raw.toDoubleOrNull()?.also {
                    require(it.isFinite() && it in minimum..maximum) {
                        "farm-zones.$zoneId.delivery.pickup.$name is outside $minimum..$maximum"
                    }
                } ?: error("farm-zones.$zoneId.delivery.pickup.$name must be a finite number")
            }
            return FarmDeliverySettings(
                world = world,
                x = coordinate("x", -30_000_000.0, 30_000_000.0),
                y = coordinate("y", -2_048.0, 2_048.0),
                z = coordinate("z", -30_000_000.0, 30_000_000.0),
                radius = coordinate("radius", 1.0, 8.0),
                crates = section.int("delivery.crates", 3).checked("delivery.crates", 1, 8),
                spawnRadius = section.int("delivery.spawn-radius", 16).checked("delivery.spawn-radius", 3, 48),
                minCrateSpacing = section.finiteDouble("delivery.min-crate-spacing", 5.0, 0.0, 16.0),
                pickup = FarmSupplyPointSettings(
                    world = world,
                    x = pickupCoordinate("x", -30_000_000.0, 30_000_000.0),
                    y = pickupCoordinate("y", -2_048.0, 2_048.0),
                    z = pickupCoordinate("z", -30_000_000.0, 30_000_000.0),
                ),
                itemMaterial = materialName(section.string("delivery.item.material", "BARREL")),
                itemCustomModelData = section.int("delivery.item.custom-model-data", 0)
                    .checked("delivery.item.custom-model-data", 0, MAX_CUSTOM_MODEL_DATA),
                displayTransform = section.string("delivery.display-transform", "GROUND")
                    .trim()
                    .uppercase()
                    .let { raw ->
                        FarmItemDisplayTransform.entries.firstOrNull { it.name == raw }
                            ?: error("farm-zones.$zoneId.delivery.display-transform must be GROUND, FIXED, or HEAD")
                    },
                displayScale = section.finiteFloat("delivery.display-scale", 2.0f, 0.05f, 8.0f),
                displayYOffset = section.finiteDouble("delivery.display-y-offset", 0.15, -2.0, 4.0),
                carriedScale = section.finiteFloat("delivery.carried-scale", 1.5f, 0.05f, 8.0f),
                carriedYOffset = section.finiteDouble("delivery.carried-y-offset", 0.65, -1.0, 3.0),
                displayViewRange = section.finiteFloat("delivery.display-view-range", 2.0f, 0.25f, 8.0f),
            )
        }

        private fun parseFarmSupplies(
            section: ru.arc.config.ConfigSection,
            world: String,
            zoneId: String,
        ): FarmSupplySettings {
            fun point(name: String): FarmSupplyPointSettings {
                fun coordinate(axis: String, minimum: Double, maximum: Double): Double =
                    section.string("supplies.$name.$axis").toDoubleOrNull()?.also {
                        require(it.isFinite() && it in minimum..maximum) {
                            "farm-zones.$zoneId.supplies.$name.$axis is outside $minimum..$maximum"
                        }
                    } ?: error("farm-zones.$zoneId.supplies.$name.$axis must be a finite number")
                return FarmSupplyPointSettings(
                    world = world,
                    x = coordinate("x", -30_000_000.0, 30_000_000.0),
                    y = coordinate("y", -2_048.0, 2_048.0),
                    z = coordinate("z", -30_000_000.0, 30_000_000.0),
                )
            }
            val archery = point("archery")
            val fireEquipment = if (section.stringOrNull("supplies.fire-equipment.x") != null) {
                point("fire-equipment")
            } else archery
            val fireEquipmentItemModel = section.string("supplies.fire-equipment-item-model", "").trim().ifEmpty { null }
            fireEquipmentItemModel?.let { model ->
                require(model.matches(Regex("[a-z0-9._-]+:[a-z0-9/._-]+"))) {
                    "farm-zones.$zoneId.supplies.fire-equipment-item-model must be a namespaced item model"
                }
            }
            return FarmSupplySettings(
                tool = point("tool"),
                seeds = point("seeds"),
                water = point("water"),
                archery = archery,
                fireEquipment = fireEquipment,
                toolMaterial = materialName(section.string("supplies.tool-material", "IRON_HOE")),
                seedAmount = section.int("supplies.seed-amount", 16).checked("supplies.seed-amount", 1, 64),
                bowMaterial = materialName(section.string("supplies.bow-material", "BOW")),
                arrowMaterial = materialName(section.string("supplies.arrow-material", "ARROW")),
                arrowAmount = section.int("supplies.arrow-amount", 32).checked("supplies.arrow-amount", 1, 64),
                fireEquipmentMaterial = materialName(section.string("supplies.fire-equipment-material", "SPYGLASS")),
                fireEquipmentCustomModelData = section.int("supplies.fire-equipment-custom-model-data", 0)
                    .checked("supplies.fire-equipment-custom-model-data", 0, MAX_CUSTOM_MODEL_DATA),
                fireEquipmentItemModel = fireEquipmentItemModel,
                itemScale = section.finiteFloat("supplies.item-scale", 1.35f, 0.5f, 3.0f),
            )
        }

        private fun parseRewards(
            section: ru.arc.config.ConfigSection,
            zoneId: String,
            root: String,
            defaultExperience: Int,
        ): FarmRewardSettings {
            fun chance(path: String, default: Int = 100): Int =
                section.int(path, default).checked("$root.$zoneId.$path", 0, 100)

            fun item(path: String, id: String): FarmItemRewardSettings = FarmItemRewardSettings(
                id = id,
                material = materialName(section.string("$path.material")),
                amount = section.int("$path.amount").checked("$root.$zoneId.$path.amount", 1, 2_304),
                chancePercent = chance("$path.chance-percent"),
            )

            val items = section.keys("rewards.items").sorted().map { rewardId ->
                validateId(rewardId, "farm reward")
                item("rewards.items.$rewardId", rewardId)
            }
            require(items.size <= 64) { "Farm zone $zoneId has too many item rewards" }

            val commands = section.keys("rewards.commands").sorted().map { rewardId ->
                validateId(rewardId, "farm command reward")
                val path = "rewards.commands.$rewardId"
                val command = TrustedFarmCommandTemplate.parse(section.string("$path.command")).value
                FarmCommandRewardSettings(rewardId, command, chance("$path.chance-percent"))
            }
            require(commands.size <= 32) { "Farm zone $zoneId has too many command rewards" }

            val bundleEntries = section.keys("rewards.random-bundles.entries").sorted().map { bundleId ->
                validateId(bundleId, "farm reward bundle")
                val path = "rewards.random-bundles.entries.$bundleId"
                val bundleItems = section.keys("$path.items").sorted().map { itemId ->
                    validateId(itemId, "farm reward bundle item")
                    FarmBundleItemSettings(
                        material = materialName(section.string("$path.items.$itemId.material")),
                        amount = section.int("$path.items.$itemId.amount")
                            .checked("$root.$zoneId.$path.items.$itemId.amount", 1, 2_304),
                    )
                }
                require(bundleItems.isNotEmpty() && bundleItems.size <= 27) {
                    "Farm zone $zoneId reward bundle $bundleId must contain 1..27 items"
                }
                FarmRewardBundleSettings(
                    id = bundleId,
                    weight = section.int("$path.weight", 1).checked("farm-zones.$zoneId.$path.weight", 1, 100_000),
                    items = bundleItems,
                )
            }
            require(bundleEntries.size <= 32) { "Farm zone $zoneId has too many random reward bundles" }
            require(bundleEntries.sumOf { it.weight.toLong() } <= 1_000_000L) {
                "Farm zone $zoneId random reward bundle weight is unbounded"
            }
            val bundleRolls = section.int("rewards.random-bundles.rolls", 0)
                .checked("$root.$zoneId.rewards.random-bundles.rolls", 0, 4)
            require(bundleRolls == 0 || bundleEntries.isNotEmpty()) {
                "Farm zone $zoneId enables random bundle rolls without bundle entries"
            }

            return FarmRewardSettings(
                experience = FarmExperienceRewardSettings(
                    amount = section.int("rewards.experience.amount", defaultExperience)
                        .checked("$root.$zoneId.rewards.experience.amount", 0, 10_000),
                    chancePercent = chance("rewards.experience.chance-percent"),
                ),
                money = FarmMoneyRewardSettings(
                    amountCents = moneyCents(section.string("rewards.money.amount", "0"), zoneId),
                    chancePercent = chance("rewards.money.chance-percent"),
                ),
                items = items,
                commands = commands,
                randomBundles = FarmRandomBundleSettings(
                    rolls = bundleRolls,
                    chancePercent = chance("rewards.random-bundles.chance-percent"),
                    entries = bundleEntries,
                ),
            )
        }

        private fun moneyCents(value: String, zoneId: String): Long {
            val amount = value.trim().toBigDecimalOrNull()
                ?: error("Farm zone $zoneId rewards.money.amount must be a number")
            require(amount.signum() >= 0 && amount <= BigDecimal("1000000")) {
                "Farm zone $zoneId rewards.money.amount must be in 0..1000000"
            }
            return try {
                amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
            } catch (_: ArithmeticException) {
                error("Farm zone $zoneId rewards.money.amount supports at most two decimal places")
            }
        }

        private fun parseWeightedList(values: List<String>, label: String): LinkedHashMap<String, Int> {
            require(values.isNotEmpty()) { "$label must not be empty" }
            val parsed = linkedMapOf<String, Int>()
            values.forEach { entry ->
                val split = entry.lastIndexOf(':')
                require(split in 1 until entry.lastIndex) { "$label entry must be MATERIAL:WEIGHT: $entry" }
                val material = materialName(entry.substring(0, split))
                val amount = entry.substring(split + 1).trim().toIntOrNull()
                    ?: error("$label has a non-integer value: $entry")
                require(amount in 1..100_000) { "$label value is outside 1..100000: $entry" }
                require(parsed.put(material, amount) == null) { "$label duplicates $material" }
            }
            require(parsed.values.sumOf(Int::toLong) <= 1_000_000L) { "$label total weight is unbounded" }
            return parsed
        }

        private fun validateId(value: String, label: String) {
            require(value.matches(Regex("[a-z0-9_-]{1,48}"))) { "Invalid $label id: $value" }
        }

        private fun serverId(value: String, label: String): String = value.trim().lowercase().also {
            runCatching { BackendServerId.of(it) }
                .getOrElse { failure -> throw IllegalArgumentException("$label must use lowercase letters, digits, _ or -", failure) }
        }

        private fun materialName(value: String): String = value.trim().uppercase().also {
            require(it.matches(Regex("[A-Z0-9_]{2,64}"))) { "Invalid material name: $value" }
        }

        private fun entityName(value: String): String = value.trim().uppercase().also {
            require(it.matches(Regex("[A-Z0-9_]{2,64}"))) { "Invalid entity type: $value" }
        }

        private fun soundKey(value: String): String = value.trim().lowercase().also {
            require(it.matches(Regex("[a-z0-9._-]+:[a-z0-9/._-]+"))) { "Invalid sound key: $value" }
        }

        private fun speciesName(value: String): String = materialName(value).also {
            require(!it.endsWith("_LOG") && !it.endsWith("_WOOD")) { "Use a wood species, not a block material: $value" }
        }

        private fun permission(value: String): String = value.trim().also {
            require(it.matches(Regex("[a-z0-9._-]{1,128}"))) { "Invalid permission: $value" }
        }

        private fun Int.checked(label: String, minimum: Int, maximum: Int): Int = also {
            require(it in minimum..maximum) { "$label must be in $minimum..$maximum" }
        }

    }
}

object ArcFarmsRedisBootstrap {
    fun load(dataRoot: Path, settings: ArcFarmsConfig): RedisModuleConfig =
        validate(RedisModuleConfig.load(dataRoot), settings)

    /** Reads the file without mutating ConfigManager's active cache, for transactional reload validation. */
    fun loadFresh(dataRoot: Path, settings: ArcFarmsConfig): RedisModuleConfig = validate(
        RedisModuleConfig(Config(dataRoot, ConfigManager.moduleYamlRelative(dataRoot, RedisModuleConfig.RESOURCE))),
        settings,
    )

    private fun validate(redis: RedisModuleConfig, settings: ArcFarmsConfig): RedisModuleConfig {
        require(!settings.network.enabled || redis.enabled) { "Redis must be enabled when ArcFarms network is enabled" }
        require(redis.serverName == settings.serverId) {
            "modules/redis.yml server-name must match config.yml server-id"
        }
        require(redis.host.isNotBlank() && redis.host.length <= 253) { "Redis host is invalid" }
        require(redis.port in 1..65_535) { "Redis port is outside 1..65535" }
        require(redis.username.length <= 128 && redis.password.length <= 512) { "Redis credentials exceed safe bounds" }
        return redis
    }
}
