package ru.ruscrafting.farms.config

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.redis.RedisModuleConfig
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmContractRarity
import ru.ruscrafting.farms.domain.FarmCustomerType
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.MAX_FARM_PATCH_PLOTS
import java.nio.file.Path
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ceil
import kotlin.math.floor

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

data class FarmZoneSettings(
    val id: String,
    val reference: ZoneReference,
    val permission: String,
    val preparationPatchSize: Int,
    val preparationPatchMaxSize: Int,
    val preparationSearchRadius: Int,
    val careRadius: Int,
    val careTypes: List<FarmCareType>,
    val careTargetCount: Int,
    val animalRescueTargetCount: Int,
    val animalRescueMinSpacing: Double,
    val animalRescueMaxPlayerDistance: Int,
    val animalDeliveryRadius: Double,
    val displayViewRange: Float,
    val seederEveryShifts: Int,
    val seederPatchSize: Int,
    val seederPatchMaxSize: Int,
    val seederWorkingWidth: Int,
    val seederWaypointReach: Double,
    val diseaseInitialSpots: Int,
    val diseaseMaxSpots: Int,
    val diseaseSpreadSeconds: Int,
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
    val supplies: FarmSupplySettings,
    val delivery: FarmDeliverySettings,
    val rewards: FarmRewardSettings,
    val crops: Set<String>,
    val rareOrderChancePercent: Int,
    val orders: List<FarmOrderSettings>,
) {
    fun droughtTargetBeds(gardenBeds: Int): Int {
        if (gardenBeds <= 0) return 0
        val proportional = ceil(gardenBeds * droughtCoveragePercent / 100.0).toInt()
        return proportional.coerceIn(droughtMinBeds, droughtMaxBeds).coerceAtMost(gardenBeds)
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
    val toolMaterial: String,
    val seedAmount: Int,
)

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

data class LumberZoneSettings(
    val id: String,
    val reference: ZoneReference,
    val station: ZoneReference,
    val permission: String,
    val fellingQuota: Int,
    val processingQuota: Int,
    val processingPerUse: Int,
    val species: List<String>,
    val stationMaterials: Set<String>,
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
)

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
    val requiresWorldGuard: Boolean = buildList {
        addAll(farms.map(FarmZoneSettings::reference))
        lumbermills.forEach { add(it.reference); add(it.station) }
        addAll(mines.map(MineZoneSettings::reference))
    }.any { it.region != null }

    companion object {
        fun load(dataRoot: Path): ArcFarmsConfig = parse(ConfigManager.of(dataRoot, "config.yml"))

        fun inspect(dataRoot: Path): ArcFarmsConfig = parse(Config(dataRoot, "config.yml"))

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
                            .checked("$path.cart-load.custom-model-data", 0, 2_000_000),
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
                            .checked("$path.custom-model-data", 0, 2_000_000),
                    )
                }
                val contractCartPath = "contract-scene.cart"
                val contractCartVisual = FarmContractCartVisualSettings(
                    material = materialName(section.string("$contractCartPath.material", "MINECART")),
                    customModelData = section.int("$contractCartPath.custom-model-data", 0)
                        .checked("$contractCartPath.custom-model-data", 0, 2_000_000),
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
                val supplies = parseFarmSupplies(section, reference.world, id)
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
                val seederPatchSize = section.int("seeder-patch-size", 640)
                    .checked("seeder-patch-size", 1, MAX_FARM_PATCH_PLOTS)
                val seederPatchMaxSize = section.int("seeder-patch-max-size", 1_024)
                    .checked("seeder-patch-max-size", 1, MAX_FARM_PATCH_PLOTS)
                require(seederPatchMaxSize >= seederPatchSize) {
                    "Farm zone $id seeder-patch-max-size must be at least seeder-patch-size"
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
                val diseaseMaxSpots = section.int("disease-max-spots", 6)
                    .checked("disease-max-spots", 1, 32)
                require(diseaseInitialSpots <= diseaseMaxSpots) {
                    "Farm zone $id disease-initial-spots must not exceed disease-max-spots"
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
                FarmZoneSettings(
                    id = id,
                    reference = reference,
                    permission = permission(section.string("permission", "arcfarms.farm")),
                    preparationPatchSize = preparationPatchSize,
                    preparationPatchMaxSize = preparationPatchMaxSize,
                    preparationSearchRadius = section.int("preparation-search-radius", 48)
                        .checked("preparation-search-radius", 4, 64),
                    careRadius = section.int("care-radius", 10).checked("care-radius", 3, 24),
                    careTypes = careTypes,
                    careTargetCount = section.int("care-targets", 4).checked("care-targets", 2, 8),
                    animalRescueTargetCount = section.int("animal-rescue-targets", 6)
                        .checked("animal-rescue-targets", 2, 12),
                    animalRescueMinSpacing = section.finiteDouble("animal-rescue-min-spacing", 8.0, 0.0, 32.0),
                    animalRescueMaxPlayerDistance = section.int("animal-rescue-max-player-distance", placementMaxPlayerDistance)
                        .checked("animal-rescue-max-player-distance", 4, 64),
                    animalDeliveryRadius = section.finiteDouble("animal-delivery-radius", 3.0, 1.0, 8.0),
                    displayViewRange = section.finiteFloat("display-view-range", 2.0f, 0.25f, 8.0f),
                    seederEveryShifts = section.int("seeder-every-shifts", 2)
                        .checked("seeder-every-shifts", 0, 16),
                    seederPatchSize = seederPatchSize,
                    seederPatchMaxSize = seederPatchMaxSize,
                    seederWorkingWidth = section.int("seeder-working-width", 3)
                        .checked("seeder-working-width", 1, 12),
                    seederWaypointReach = section.finiteDouble("seeder-waypoint-reach", 2.8, 1.0, 6.0),
                    diseaseInitialSpots = diseaseInitialSpots,
                    diseaseMaxSpots = diseaseMaxSpots,
                    diseaseSpreadSeconds = section.int("disease-spread-seconds", 12)
                        .checked("disease-spread-seconds", 3, 300),
                    careAnimalEntities = section.stringList("care-animal-entities")
                        .ifEmpty { listOf("CHICKEN", "SHEEP") }
                        .map(::entityName)
                        .distinct()
                        .also { require(it.isNotEmpty()) { "Farm zone $id has no care animal entities" } },
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
                    incidentTriggerPercents = incidentTriggerPercents,
                    incidentCountMin = incidentCountMin,
                    incidentCountMax = incidentCountMax,
                    incidentQuota = section.int("incident-quota", 4).checked("incident-quota", 1, 64),
                    droughtPatches = droughtPatches,
                    droughtCoveragePercent = droughtCoveragePercent,
                    droughtMinBeds = droughtMinBeds,
                    droughtMaxBeds = droughtMaxBeds,
                    droughtInitialBeds = droughtInitialBeds,
                    droughtGrowthBeds = section.int("drought-growth-beds", 5)
                        .checked("drought-growth-beds", 1, 64),
                    droughtGrowthSeconds = section.int("drought-growth-seconds", 3)
                        .checked("drought-growth-seconds", 1, 300),
                    incidentTypes = incidentTypes,
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
                    supplies = supplies,
                    delivery = delivery,
                    rewards = parseFarmRewards(section, id),
                    crops = crops,
                    rareOrderChancePercent = rareOrderChancePercent,
                    orders = orders,
                )
            }

            val lumbermills = config.keys("lumber-zones").sorted().mapNotNull { id ->
                val section = config.section("lumber-zones.$id")
                if (!section.boolean("enabled", true)) return@mapNotNull null
                validateId(id, "lumber zone")
                val reference = parseReference(section, "", id)
                val station = parseReference(section, "station-", "$id station", reference.world)
                LumberZoneSettings(
                    id = id,
                    reference = reference,
                    station = station,
                    permission = permission(section.string("permission", "arcfarms.lumber")),
                    fellingQuota = section.int("felling-quota", 16).checked("felling-quota", 1, 100_000),
                    processingQuota = section.int("processing-quota", 6).checked("processing-quota", 1, 100_000),
                    processingPerUse = section.int("processing-per-use", 2).checked("processing-per-use", 1, 100_000),
                    species = section.stringList("species").map(::speciesName).distinct().also {
                        require(it.isNotEmpty()) { "Lumber zone $id has no species" }
                    },
                    stationMaterials = section.stringList("station-materials").map(::materialName).toSet().also {
                        require(it.isNotEmpty()) { "Lumber zone $id has no station materials" }
                    },
                ).also {
                    require(it.processingPerUse <= it.processingQuota) { "processing-per-use exceeds processing-quota in $id" }
                }
            }

            val mines = config.keys("mine-zones").sorted().mapNotNull { id ->
                val section = config.section("mine-zones.$id")
                if (!section.boolean("enabled", true)) return@mapNotNull null
                validateId(id, "mine zone")
                val materialWeights = parseWeightedList(section.stringList("materials"), "mine $id materials")
                val baseMaterial = materialName(section.string("base-material"))
                require(baseMaterial in materialWeights) { "Mine $id base-material must be present in materials" }
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
                ).also {
                    require(it.hazardTrigger < it.cartQuota) { "Mine $id hazard-trigger must be below cart-quota" }
                    require(it.temporaryMaterial !in it.materialWeights) { "Mine $id temp-material must not be a generated material" }
                }
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
                titleStaySeconds = config.int("ui.title-stay-seconds", 4).checked("ui.title-stay-seconds", 2, 10),
                markerHeight = config.int("ui.marker-height", 12).checked("ui.marker-height", 6, 24),
                missingBedHighlightThreshold = config.int("ui.missing-bed-highlight-threshold", 10)
                    .checked("ui.missing-bed-highlight-threshold", 1, 32),
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
                        .checked("ui.menu-background.custom-model-data", 0, 2_000_000),
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
                spawnRadius = section.int("delivery.spawn-radius", 8).checked("delivery.spawn-radius", 3, 16),
                minCrateSpacing = section.finiteDouble("delivery.min-crate-spacing", 5.0, 0.0, 16.0),
                pickup = FarmSupplyPointSettings(
                    world = world,
                    x = pickupCoordinate("x", -30_000_000.0, 30_000_000.0),
                    y = pickupCoordinate("y", -2_048.0, 2_048.0),
                    z = pickupCoordinate("z", -30_000_000.0, 30_000_000.0),
                ),
                itemMaterial = materialName(section.string("delivery.item.material", "BARREL")),
                itemCustomModelData = section.int("delivery.item.custom-model-data", 0)
                    .checked("delivery.item.custom-model-data", 0, 2_000_000),
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
            return FarmSupplySettings(
                tool = point("tool"),
                seeds = point("seeds"),
                water = point("water"),
                toolMaterial = materialName(section.string("supplies.tool-material", "IRON_HOE")),
                seedAmount = section.int("supplies.seed-amount", 16).checked("supplies.seed-amount", 1, 64),
            )
        }

        private fun parseFarmRewards(
            section: ru.arc.config.ConfigSection,
            zoneId: String,
        ): FarmRewardSettings {
            fun chance(path: String, default: Int = 100): Int =
                section.int(path, default).checked("farm-zones.$zoneId.$path", 0, 100)

            fun item(path: String, id: String): FarmItemRewardSettings = FarmItemRewardSettings(
                id = id,
                material = materialName(section.string("$path.material")),
                amount = section.int("$path.amount").checked("farm-zones.$zoneId.$path.amount", 1, 2_304),
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
                val command = section.string("$path.command").trim().removePrefix("/").also {
                    require(it.length in 1..512 && '\n' !in it && '\r' !in it) {
                        "Farm zone $zoneId command reward $rewardId is empty or too long"
                    }
                    val placeholders = COMMAND_PLACEHOLDER.findAll(it).map { match -> match.value }.toSet()
                    require(placeholders.all(ALLOWED_COMMAND_PLACEHOLDERS::contains)) {
                        "Farm zone $zoneId command reward $rewardId uses an unknown placeholder"
                    }
                    require('%' !in COMMAND_PLACEHOLDER.replace(it, "")) {
                        "Farm zone $zoneId command reward $rewardId contains an incomplete placeholder"
                    }
                }
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
                            .checked("farm-zones.$zoneId.$path.items.$itemId.amount", 1, 2_304),
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
                .checked("farm-zones.$zoneId.rewards.random-bundles.rolls", 0, 4)
            require(bundleRolls == 0 || bundleEntries.isNotEmpty()) {
                "Farm zone $zoneId enables random bundle rolls without bundle entries"
            }

            return FarmRewardSettings(
                experience = FarmExperienceRewardSettings(
                    amount = section.int("rewards.experience.amount", 75)
                        .checked("farm-zones.$zoneId.rewards.experience.amount", 0, 10_000),
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
            require(it.matches(Regex("[a-z0-9_-]{1,32}"))) { "$label must use lowercase letters, digits, _ or -" }
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

        private val COMMAND_PLACEHOLDER = Regex("%[a-z_]+%")
        private val ALLOWED_COMMAND_PLACEHOLDERS = setOf(
            "%player%",
            "%uuid%",
            "%zone%",
            "%sequence%",
            "%contribution%",
            "%rank%",
            "%grant_id%",
        )
    }
}

object ArcFarmsRedisBootstrap {
    fun load(dataRoot: Path, settings: ArcFarmsConfig): RedisModuleConfig {
        val redis = RedisModuleConfig.load(dataRoot)
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
